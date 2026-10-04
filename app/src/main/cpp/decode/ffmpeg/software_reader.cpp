#include "decode/ffmpeg/software_reader.h"

#include <algorithm>
#include <cstring>

#include "decode/ffmpeg/libav_common.h"
#include "decode/ffmpeg/ts_math.h"

namespace uv::decode::ffmpeg {

struct SoftwareVideoReader::Impl {
    FormatHandle format;
    AVCodecContext* codec = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;
    SwsContext* sws = nullptr;
    int streamIndex = -1;
    TimeBase timeBase;
    int64_t startPts = 0;
    Rational fps{30, 1};

    bool draining = false;
    bool haveLast = false;
    int64_t lastIndex = 0;
    bool haveFrame = false;  // `frame` holds a picture returned by decode()

    ~Impl() {
        if (sws != nullptr) sws_freeContext(sws);
        if (frame != nullptr) av_frame_free(&frame);
        if (packet != nullptr) av_packet_free(&packet);
        if (codec != nullptr) avcodec_free_context(&codec);
    }
};

SoftwareVideoReader::SoftwareVideoReader() : impl_(new Impl()) {}
SoftwareVideoReader::~SoftwareVideoReader() = default;

Result<std::unique_ptr<SoftwareVideoReader>> SoftwareVideoReader::open(int fd, Rational fpsOverride, int threads) {
    std::unique_ptr<SoftwareVideoReader> r(new SoftwareVideoReader());
    Impl& m = *r->impl_;
    av_log_set_level(AV_LOG_ERROR);

    std::string error;
    if (openFormat(fd, &m.format, &error) < 0) {
        // MediaExtractor and libav both failed to parse it: an I/O problem or a format nobody here can read.
        return Error{Status::IoError, "software demuxer: " + error};
    }
    AVFormatContext* fmt = m.format.ctx;

    // The best video stream that is not a cover picture.
    int index = -1;
    int64_t bestPixels = -1;
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        const AVStream* s = fmt->streams[i];
        if (s->codecpar->codec_type != AVMEDIA_TYPE_VIDEO) continue;
        if ((s->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0) continue;
        const int64_t pixels = static_cast<int64_t>(s->codecpar->width) * s->codecpar->height;
        if (pixels > bestPixels) {
            bestPixels = pixels;
            index = static_cast<int>(i);
        }
    }
    if (index < 0) return Error{Status::NotFound, "no video track found"};
    AVStream* stream = fmt->streams[index];
    AVCodecParameters* par = stream->codecpar;
    if (par->width <= 0 || par->height <= 0) return Error{Status::UnsupportedFormat, "video track has no dimensions"};

    const AVCodec* decoder = avcodec_find_decoder(par->codec_id);
    if (decoder == nullptr) {
        return Error{Status::UnsupportedFormat,
                     std::string("no software decoder for ") + avcodec_get_name(par->codec_id)};
    }
    m.codec = avcodec_alloc_context3(decoder);
    if (m.codec == nullptr) return Error{Status::OutOfMemory, "avcodec_alloc_context3 failed"};
    int ret = avcodec_parameters_to_context(m.codec, par);
    if (ret < 0) return Error{Status::CodecError, "avcodec_parameters_to_context: " + avErrorString(ret)};
    m.codec->thread_count = std::max(1, threads);
    m.codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    ret = avcodec_open2(m.codec, decoder, nullptr);
    if (ret < 0) return Error{Status::CodecError, std::string("avcodec_open2 (") + decoder->name + "): " + avErrorString(ret)};

    m.frame = av_frame_alloc();
    m.packet = av_packet_alloc();
    if (m.frame == nullptr || m.packet == nullptr) return Error{Status::OutOfMemory, "cannot allocate a frame"};

    m.streamIndex = index;
    m.timeBase = TimeBase{stream->time_base.num, stream->time_base.den};
    m.startPts = stream->start_time != AV_NOPTS_VALUE ? stream->start_time : 0;
    m.fps = chooseFrameRate(fpsOverride, Rational{stream->avg_frame_rate.num, stream->avg_frame_rate.den},
                            Rational{stream->r_frame_rate.num, stream->r_frame_rate.den});

    VideoStreamInfo& info = r->info_;
    info.width = par->width;
    info.height = par->height;
    info.fps = m.fps;
    info.codec = decoder->name;
    info.colorTransfer = mediaTransferFromAv(static_cast<int>(par->color_trc));

    if (stream->duration > 0 && stream->duration != AV_NOPTS_VALUE) {
        info.durationFrames = durationToFrames(stream->duration, m.timeBase, m.fps);
        info.durationKnown = info.durationFrames > 0;
    } else if (fmt->duration > 0 && fmt->duration != AV_NOPTS_VALUE) {
        info.durationFrames = durationToFrames(fmt->duration, TimeBase{1, AV_TIME_BASE}, m.fps);
        info.durationKnown = info.durationFrames > 0;
    }
    if (!info.durationKnown) info.durationFrames = kUnknownDurationFrames;

#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(60, 31, 102)
    const AVPacketSideData* side =
        av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (side != nullptr && side->size >= 9 * static_cast<int>(sizeof(int32_t))) {
        const double angle = av_display_rotation_get(reinterpret_cast<const int32_t*>(side->data));
        if (angle == angle) info.rotationDegrees = clockwiseRotationFromDisplayMatrix(angle);  // not NaN
    }
#endif
    return r;
}

bool SoftwareVideoReader::seek(int64_t frame, std::string* error) {
    Impl& m = *impl_;
    const int64_t target = std::max(frameToPtsFloor(std::max<int64_t>(frame, 0), m.timeBase, m.startPts, m.fps), m.startPts);
    int ret = av_seek_frame(m.format.ctx, m.streamIndex, target, AVSEEK_FLAG_BACKWARD);
    if (ret < 0 && target > m.startPts) {
        // Some demuxers refuse a backward seek to a time before their first index entry: start over instead.
        ret = av_seek_frame(m.format.ctx, m.streamIndex, m.startPts, AVSEEK_FLAG_BACKWARD);
    }
    if (ret < 0) {
        *error = "av_seek_frame: " + avErrorString(ret);
        return false;
    }
    avcodec_flush_buffers(m.codec);
    m.draining = false;
    m.haveLast = false;
    m.haveFrame = false;
    return true;
}

ReadStatus SoftwareVideoReader::decode(int64_t* frameOut, std::string* error) {
    Impl& m = *impl_;
    m.haveFrame = false;
    for (;;) {
        int ret = avcodec_receive_frame(m.codec, m.frame);
        if (ret == 0) {
            int64_t index = 0;
            const int64_t pts = m.frame->best_effort_timestamp;
            if (pts != AV_NOPTS_VALUE) {
                index = ptsToFrame(pts, m.timeBase, m.startPts, m.fps);
            } else {
                index = m.haveLast ? m.lastIndex + 1 : 0;
            }
            if (m.haveLast && index <= m.lastIndex) {  // a duplicate index after rounding (variable frame rate)
                av_frame_unref(m.frame);
                continue;
            }
            m.lastIndex = index;
            m.haveLast = true;
            m.haveFrame = true;
            *frameOut = index;
            return ReadStatus::Frame;
        }
        if (ret == AVERROR_EOF) return ReadStatus::EndOfStream;
        if (ret != AVERROR(EAGAIN)) {
            if (ret == AVERROR_INVALIDDATA) continue;  // a damaged picture: move on to the next one
            *error = "avcodec_receive_frame: " + avErrorString(ret);
            return ReadStatus::Failed;
        }
        // The decoder wants more input.
        if (m.draining) return ReadStatus::EndOfStream;
        ret = av_read_frame(m.format.ctx, m.packet);
        if (ret == AVERROR_EOF) {
            m.draining = true;
            avcodec_send_packet(m.codec, nullptr);
            continue;
        }
        if (ret < 0) {
            *error = "av_read_frame: " + avErrorString(ret);
            return ReadStatus::Failed;
        }
        if (m.packet->stream_index != m.streamIndex) {
            av_packet_unref(m.packet);
            continue;
        }
        ret = avcodec_send_packet(m.codec, m.packet);
        av_packet_unref(m.packet);
        if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_INVALIDDATA) {
            *error = "avcodec_send_packet: " + avErrorString(ret);
            return ReadStatus::Failed;
        }
    }
}

bool SoftwareVideoReader::convertRgba(uint8_t* dst, int32_t stride, std::string* error) {
    Impl& m = *impl_;
    if (!m.haveFrame) {
        *error = "no decoded picture to convert";
        return false;
    }
    const AVFrame* f = m.frame;
    m.sws = sws_getCachedContext(m.sws, f->width, f->height, static_cast<AVPixelFormat>(f->format), info_.width, info_.height,
                                 AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (m.sws == nullptr) {
        *error = "sws_getCachedContext failed for pixel format " + std::to_string(f->format);
        return false;
    }
    int matrix = SWS_CS_DEFAULT;
    switch (f->colorspace) {
        case AVCOL_SPC_BT709:
            matrix = SWS_CS_ITU709;
            break;
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M:
            matrix = SWS_CS_ITU601;
            break;
        case AVCOL_SPC_SMPTE240M:
            matrix = SWS_CS_SMPTE240M;
            break;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:
            matrix = SWS_CS_BT2020;
            break;
        default:  // unspecified: HD is Rec.709, SD is Rec.601, as players assume
            matrix = f->height >= 720 ? SWS_CS_ITU709 : SWS_CS_ITU601;
            break;
    }
    const int srcFull = f->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
    sws_setColorspaceDetails(m.sws, sws_getCoefficients(matrix), srcFull, sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16,
                             1 << 16);
    uint8_t* planes[4] = {dst, nullptr, nullptr, nullptr};
    int strides[4] = {stride, 0, 0, 0};
    const int rows = sws_scale(m.sws, f->data, f->linesize, 0, f->height, planes, strides);
    if (rows <= 0) {
        *error = "sws_scale produced no rows";
        return false;
    }
    return true;
}

}  // namespace uv::decode::ffmpeg
