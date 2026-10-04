// Software audio decoding with libavcodec, as a PcmDecoder: used when the platform cannot decode a file's audio
// (AC-3/E-AC-3, ALAC, Vorbis in odd containers, WMA, old camcorder audio, ...). Interleaved stereo float at the
// stream's own rate; libswresample downmixes more than two channels properly (the MediaCodec path takes the
// first two). Only compiled into builds with the FFmpeg fallback.

#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <vector>

#include "audio/ffmpeg_pcm.h"
#include "audio/pcm_decoder.h"
#include "decode/ffmpeg/libav_common.h"
#include "decode/ffmpeg/ts_math.h"

namespace uv::audio {

namespace {

using namespace uv::decode::ffmpeg;
using core::Status;

class FfmpegPcmDecoder final : public PcmDecoder {
public:
    static std::unique_ptr<FfmpegPcmDecoder> open(int fd, Status* status) {
        std::unique_ptr<FfmpegPcmDecoder> d(new FfmpegPcmDecoder());
        *status = d->init(fd);
        if (*status != Status::Ok) return nullptr;
        return d;
    }

    ~FfmpegPcmDecoder() override {
        if (swr_ != nullptr) swr_free(&swr_);
        if (frame_ != nullptr) av_frame_free(&frame_);
        if (packet_ != nullptr) av_packet_free(&packet_);
        if (codec_ != nullptr) avcodec_free_context(&codec_);
    }

    int32_t sampleRate() const override { return rate_; }

    Status seekToMicros(int64_t micros) override {
        micros = std::max<int64_t>(micros, 0);
        const int64_t ts = std::max(microsToPtsFloor(micros, tb_, startPts_), startPts_);
        int ret = av_seek_frame(format_.ctx, streamIndex_, ts, AVSEEK_FLAG_BACKWARD);
        if (ret < 0 && ts > startPts_) ret = av_seek_frame(format_.ctx, streamIndex_, startPts_, AVSEEK_FLAG_BACKWARD);
        if (ret < 0) return Status::IoError;
        avcodec_flush_buffers(codec_);
        if (swr_ != nullptr) swr_free(&swr_);  // recreated with the first frame after the seek
        pending_.clear();
        pendingPos_ = 0;
        draining_ = false;
        eof_ = false;
        awaitFirst_ = true;
        targetSample_ = static_cast<int64_t>((static_cast<__int128>(micros) * rate_ + 500000) / 1000000);
        skip_ = 0;
        return Status::Ok;
    }

    PcmReadResult read(float* dst, int32_t maxFrames) override {
        PcmReadResult out;
        while (out.frames < maxFrames) {
            const size_t available = (pending_.size() - pendingPos_) / 2;
            if (available == 0) {
                pending_.clear();
                pendingPos_ = 0;
                if (eof_) {
                    out.eof = true;
                    return out;
                }
                const Status st = pump();
                if (st != Status::Ok) {
                    out.status = st;
                    return out;
                }
                continue;
            }
            const size_t n = std::min<size_t>(available, static_cast<size_t>(maxFrames - out.frames));
            std::memcpy(dst + static_cast<size_t>(out.frames) * 2, pending_.data() + pendingPos_, n * 2 * sizeof(float));
            pendingPos_ += n * 2;
            out.frames += static_cast<int32_t>(n);
        }
        out.eof = eof_ && pending_.size() == pendingPos_;
        return out;
    }

private:
    FfmpegPcmDecoder() = default;

    Status init(int fd) {
        av_log_set_level(AV_LOG_ERROR);
        const int own = ::dup(fd);
        if (own < 0) return Status::IoError;
        std::string error;
        if (openFormat(own, &format_, &error) < 0) return Status::IoError;
        AVFormatContext* fmt = format_.ctx;
        const int index = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (index < 0) return Status::UnsupportedFormat;  // no audio track
        AVStream* stream = fmt->streams[index];
        AVCodecParameters* par = stream->codecpar;
        const AVCodec* decoder = avcodec_find_decoder(par->codec_id);
        if (decoder == nullptr || par->sample_rate <= 0) return Status::UnsupportedFormat;
        codec_ = avcodec_alloc_context3(decoder);
        if (codec_ == nullptr) return Status::CodecError;
        if (avcodec_parameters_to_context(codec_, par) < 0) return Status::CodecError;
        if (avcodec_open2(codec_, decoder, nullptr) < 0) return Status::CodecError;
        frame_ = av_frame_alloc();
        packet_ = av_packet_alloc();
        if (frame_ == nullptr || packet_ == nullptr) return Status::CodecError;
        streamIndex_ = index;
        tb_ = TimeBase{stream->time_base.num, stream->time_base.den};
        startPts_ = stream->start_time != AV_NOPTS_VALUE ? stream->start_time : 0;
        rate_ = par->sample_rate;
        return Status::Ok;
    }

    // Decodes one frame (or reaches the end of the stream); appends its stereo samples to pending_.
    Status pump() {
        for (;;) {
            int ret = avcodec_receive_frame(codec_, frame_);
            if (ret == 0) {
                const Status st = consume();
                av_frame_unref(frame_);
                return st;
            }
            if (ret == AVERROR_EOF) {
                eof_ = true;
                return Status::Ok;
            }
            if (ret != AVERROR(EAGAIN)) {
                if (ret == AVERROR_INVALIDDATA) continue;
                return Status::CodecError;
            }
            if (draining_) {
                eof_ = true;
                return Status::Ok;
            }
            ret = av_read_frame(format_.ctx, packet_);
            if (ret == AVERROR_EOF) {
                draining_ = true;
                avcodec_send_packet(codec_, nullptr);
                continue;
            }
            if (ret < 0) return Status::IoError;
            if (packet_->stream_index != streamIndex_) {
                av_packet_unref(packet_);
                continue;
            }
            ret = avcodec_send_packet(codec_, packet_);
            av_packet_unref(packet_);
            if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_INVALIDDATA) return Status::CodecError;
        }
    }

    Status consume() {
        if (swr_ == nullptr) {
            AVChannelLayout in{};
            if (frame_->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || frame_->ch_layout.nb_channels == 0) {
                av_channel_layout_default(&in, std::max(1, frame_->ch_layout.nb_channels));
            } else {
                av_channel_layout_copy(&in, &frame_->ch_layout);
            }
            AVChannelLayout out = AV_CHANNEL_LAYOUT_STEREO;
            const int ret = swr_alloc_set_opts2(&swr_, &out, AV_SAMPLE_FMT_FLT, rate_, &in, static_cast<AVSampleFormat>(frame_->format),
                                                rate_, 0, nullptr);
            av_channel_layout_uninit(&in);
            if (ret < 0 || swr_ == nullptr || swr_init(swr_) < 0) return Status::CodecError;
        }

        // Exact positioning after a seek: the demuxer landed on a sync point before the target.
        if (awaitFirst_) {
            awaitFirst_ = false;
            if (frame_->pts != AV_NOPTS_VALUE) {
                const int64_t startSample = decode::ffmpeg::ptsToFrame(frame_->pts, tb_, startPts_, decode::Rational{rate_, 1});
                skip_ = std::max<int64_t>(0, targetSample_ - startSample);
            }
        }

        const int maxOut = swr_get_out_samples(swr_, frame_->nb_samples);
        if (maxOut < 0) return Status::CodecError;
        const size_t base = pending_.size();
        pending_.resize(base + static_cast<size_t>(maxOut) * 2);
        uint8_t* outPlanes[1] = {reinterpret_cast<uint8_t*>(pending_.data() + base)};
        const int n = swr_convert(swr_, outPlanes, maxOut, const_cast<const uint8_t**>(frame_->extended_data), frame_->nb_samples);
        if (n < 0) return Status::CodecError;
        pending_.resize(base + static_cast<size_t>(n) * 2);

        if (skip_ > 0) {
            const int64_t drop = std::min<int64_t>(skip_, n);
            pending_.erase(pending_.begin() + static_cast<std::ptrdiff_t>(base),
                           pending_.begin() + static_cast<std::ptrdiff_t>(base) + static_cast<std::ptrdiff_t>(drop * 2));
            skip_ -= drop;
        }
        return Status::Ok;
    }

    FormatHandle format_;
    AVCodecContext* codec_ = nullptr;
    AVFrame* frame_ = nullptr;
    AVPacket* packet_ = nullptr;
    SwrContext* swr_ = nullptr;
    int streamIndex_ = -1;
    TimeBase tb_;
    int64_t startPts_ = 0;
    int32_t rate_ = 0;

    std::vector<float> pending_;
    size_t pendingPos_ = 0;
    bool draining_ = false;
    bool eof_ = false;
    bool awaitFirst_ = false;
    int64_t targetSample_ = 0;
    int64_t skip_ = 0;
};

}  // namespace

std::unique_ptr<PcmDecoder> openSoftwarePcmDecoder(int fd, Status* status) {
    return FfmpegPcmDecoder::open(fd, status);
}

}  // namespace uv::audio
