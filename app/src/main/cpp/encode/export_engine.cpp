#include "encode/export_engine.h"
#include "core/fd_util.h"

#include <android/data_space.h>
#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <unordered_set>

#include "audio/audio_engine.h"
#include "decode/gpu_frame.h"
#include "decode/log.h"
#include "decode/open_decoder.h"
#include "decode/video_decoder_api.h"
#include "encode/picture_residency.h"
#include "render/gl_context.h"
#include "render/gl_pipeline.h"
#include "stabilise/stab_registry.h"

namespace uv::encode {

namespace {

using core::Status;
using Clock = std::chrono::steady_clock;

constexpr int32_t kColorFormatSurface = 0x7F000789;  // MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
constexpr int32_t kHevcProfileMain10 = 2;            // MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
constexpr int32_t kColorStandardBt2020 = 6;          // MediaFormat.COLOR_STANDARD_BT2020
constexpr int32_t kColorRangeLimited = 2;            // MediaFormat.COLOR_RANGE_LIMITED
constexpr int32_t kColorTransferHlg = 7;             // MediaFormat.COLOR_TRANSFER_HLG
constexpr int32_t kAudioSampleRate = 48000;
constexpr int32_t kAudioChannels = 2;
constexpr int32_t kAudioChunkFrames = 1024;
// The AAC encoder delays its output: decoding the file yields each input sample this many samples
// late, and the MP4 muxer writes no edit list to say so. Measured on the reference device (FDK AAC)
// with 1 ms clicks every 500 ms: they decode exactly 2048 samples (42.7 ms) late. The mix is shifted
// earlier by that much so sound lines up with picture in players that ignore the delay; the first
// 42.7 ms of the mix are therefore not heard.
constexpr int64_t kAacDelaySamples = 2048;
constexpr int64_t kIdleCheckFrames = 30;
constexpr int32_t kDecodeAhead = 4;  // frames the decoder may run ahead of the frame being drawn
constexpr auto kDecodeStall = std::chrono::seconds(15);
constexpr auto kDecodeRecoverAfter = std::chrono::seconds(3);  // a frame this late makes the decoder seek afresh
constexpr auto kDegradedStall = std::chrono::milliseconds(1500);        // after a repeated frame: give up on the next sooner
constexpr auto kDegradedRecoverAfter = std::chrono::milliseconds(500);
constexpr int64_t kMaxRepeatSeconds = 5;  // a layer that yields nothing for this long fails the export
constexpr int64_t kSlowFetchMs = 500;  // a frame this late is logged with the decoder's state

struct ExportFailure {
    Status status;
    std::string message;
};

[[noreturn]] void fail(Status status, std::string message) { throw ExportFailure{status, std::move(message)}; }

Status fromDecode(decode::Status s) {
    switch (s) {
        case decode::Status::Ok: return Status::Ok;
        case decode::Status::InvalidArgument: return Status::InvalidArgument;
        case decode::Status::UnsupportedFormat: return Status::UnsupportedFormat;
        case decode::Status::CodecError: return Status::CodecError;
        case decode::Status::EglError:
        case decode::Status::GlError: return Status::GlError;
        case decode::Status::InvalidState: return Status::NotInitialized;
        default: return Status::IoError;
    }
}

[[noreturn]] void failDecode(const decode::Error& e, const char* what) {
    fail(fromDecode(e.code), std::string(what) + ": " + e.message);
}

// Copies of encoded samples that arrive before every track has its format (the muxer cannot start
// earlier). Both encoders keep producing meanwhile, so this stays small.
struct Packet {
    int track;
    std::vector<uint8_t> data;
    AMediaCodecBufferInfo info;
};

class Muxer {
public:
    Muxer(int fd, int expectedTracks) : expected_(expectedTracks) {
        muxer_ = AMediaMuxer_new(fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
        if (muxer_ == nullptr) fail(Status::IoError, "cannot create the MP4 muxer (is the output seekable?)");
    }
    ~Muxer() {
        if (muxer_ != nullptr) AMediaMuxer_delete(muxer_);
    }
    Muxer(const Muxer&) = delete;
    Muxer& operator=(const Muxer&) = delete;

    int addTrack(AMediaFormat* format) {
        const ssize_t index = AMediaMuxer_addTrack(muxer_, format);
        if (index < 0) fail(Status::IoError, "muxer rejected a track format");
        if (++added_ == expected_) {
            if (AMediaMuxer_start(muxer_) != AMEDIA_OK) fail(Status::IoError, "muxer failed to start");
            started_ = true;
            for (Packet& p : pending_) write(p.track, p.data.data(), p.info);
            pending_.clear();
        }
        return static_cast<int>(index);
    }

    void write(int track, const uint8_t* data, const AMediaCodecBufferInfo& info) {
        if (!started_) {
            Packet p{track, std::vector<uint8_t>(data + info.offset, data + info.offset + info.size), info};
            p.info.offset = 0;
            pending_.push_back(std::move(p));
            return;
        }
        if (AMediaMuxer_writeSampleData(muxer_, static_cast<size_t>(track), data, &info) != AMEDIA_OK) {
            fail(Status::IoError, "writing to the output file failed (disk full?)");
        }
    }

    void finish() {
        if (!started_) fail(Status::CodecError, "the encoders produced no output");
        if (AMediaMuxer_stop(muxer_) != AMEDIA_OK) fail(Status::IoError, "finalising the MP4 failed");
        AMediaMuxer_delete(muxer_);
        muxer_ = nullptr;
    }

private:
    AMediaMuxer* muxer_ = nullptr;
    int expected_;
    int added_ = 0;
    bool started_ = false;
    std::vector<Packet> pending_;
};

class Encoder {
public:
    explicit Encoder(Muxer& muxer) : muxer_(muxer) {}
    ~Encoder() {
        if (codec_ != nullptr) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
        }
    }
    Encoder(const Encoder&) = delete;
    Encoder& operator=(const Encoder&) = delete;

    // Creates and configures the codec for `mime`; the caller fills `format` before this call.
    void create(const char* mime, AMediaFormat* format, ANativeWindow** inputSurface) {
        codec_ = AMediaCodec_createEncoderByType(mime);
        if (codec_ == nullptr) fail(Status::UnsupportedFormat, std::string("no encoder for ") + mime);
        if (AMediaCodec_configure(codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE) != AMEDIA_OK) {
            fail(Status::UnsupportedFormat, std::string("the encoder rejected the settings for ") + mime);
        }
        if (inputSurface != nullptr &&
            AMediaCodec_createInputSurface(codec_, inputSurface) != AMEDIA_OK) {
            fail(Status::CodecError, "cannot create the encoder input surface");
        }
        if (AMediaCodec_start(codec_) != AMEDIA_OK) fail(Status::CodecError, "the encoder failed to start");
    }

    AMediaCodec* codec() const { return codec_; }
    bool ended() const { return eos_; }

    // Moves every encoded buffer that is ready into the muxer. With `untilEos` it waits for the
    // end-of-stream marker.
    void drain(bool untilEos) {
        const auto start = Clock::now();
        for (;;) {
            AMediaCodecBufferInfo info{};
            const ssize_t index = AMediaCodec_dequeueOutputBuffer(codec_, &info, untilEos ? 10000 : 0);
            if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                if (Clock::now() - start > std::chrono::seconds(30)) fail(Status::CodecError, "the encoder never finished");
                continue;
            }
            if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* format = AMediaCodec_getOutputFormat(codec_);
                track_ = muxer_.addTrack(format);
                AMediaFormat_delete(format);
                continue;
            }
            if (index < 0) continue;  // output buffers changed: nothing to do on modern API levels
            size_t size = 0;
            uint8_t* data = AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(index), &size);
            const bool config = (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (data != nullptr && info.size > 0 && !config) {
                if (track_ < 0) fail(Status::CodecError, "encoded data arrived before the output format");
                muxer_.write(track_, data, info);
            }
            AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), false);
            if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
                eos_ = true;
                return;
            }
        }
    }

private:
    Muxer& muxer_;
    AMediaCodec* codec_ = nullptr;
    int track_ = -1;
    bool eos_ = false;
};

// Audio: offline mix -> AAC.
class AudioPump {
public:
    AudioPump(Muxer& muxer, const ExportParams& params) : encoder_(muxer), params_(params) {}

    void start(const std::vector<std::pair<int64_t, int>>& assetFds) {
        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, kAudioSampleRate);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, kAudioChannels);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, params_.audioBitrate);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_AAC_PROFILE, 2);  // AAC-LC
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 16384);
        try {
            encoder_.create("audio/mp4a-latm", format, nullptr);
        } catch (...) {
            AMediaFormat_delete(format);
            throw;
        }
        AMediaFormat_delete(format);

        if (const int32_t s = engine_.start(true); s != 0) {
            fail(static_cast<Status>(s), "cannot start the offline audio mixer");
        }
        for (const auto& entry : assetFds) {
            if (const int32_t s = engine_.setAssetFd(entry.first, entry.second); s != 0) {
                fail(static_cast<Status>(s), "cannot register a media file with the audio mixer");
            }
        }
        if (const int32_t s = engine_.setSnapshot(params_.audioSnapshot.data(), params_.audioSnapshot.size()); s != 0) {
            fail(static_cast<Status>(s), "the audio snapshot was rejected");
        }
        engine_.seekFrame(0);
        engine_.play();
        pcm_.resize(static_cast<size_t>(kAudioChunkFrames) * kAudioChannels);
        // Throw away the first kAacDelaySamples of the mix (see above).
        for (int64_t skipped = 0; skipped < kAacDelaySamples;) {
            const int32_t n = static_cast<int32_t>(std::min<int64_t>(kAudioChunkFrames, kAacDelaySamples - skipped));
            engine_.renderOffline(pcm_.data(), n);
            skipped += n;
        }
    }

    ~AudioPump() { engine_.stop(); }

    // Mixes and encodes until `targetSamples` samples have been written in total.
    void pumpTo(int64_t targetSamples) {
        while (written_ < targetSamples) {
            const int32_t n = static_cast<int32_t>(std::min<int64_t>(kAudioChunkFrames, targetSamples - written_));
            engine_.renderOffline(pcm_.data(), n);
            feed(n, 0);
        }
    }

    void finish() {
        feed(0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        encoder_.drain(true);
    }

    void drain() { encoder_.drain(false); }

    void checkFaults() {
        std::vector<audio::AudioFault> faults;
        engine_.pollFaults(&faults);
        for (const audio::AudioFault& f : faults) {
            if (f.kind == audio::AudioFault::Kind::Decode) {
                fail(f.status == Status::Ok ? Status::CodecError : f.status,
                     "an audio clip could not be decoded for the export (clip " + std::to_string(f.clipKey) + ")");
            }
            if (f.kind == audio::AudioFault::Kind::OfflineStall) {
                fail(Status::CodecError, "the audio of clip " + std::to_string(f.clipKey) + " was not ready after 30 s");
            }
        }
    }

    int64_t written() const { return written_; }

private:
    // Queues `frames` interleaved stereo frames from pcm_ (or an empty end-of-stream buffer).
    void feed(int32_t frames, uint32_t flags) {
        int32_t done = 0;
        do {
            ssize_t index = -1;
            const auto start = Clock::now();
            for (;;) {
                index = AMediaCodec_dequeueInputBuffer(encoder_.codec(), 2000);
                if (index >= 0) break;
                encoder_.drain(false);  // free output buffers so the encoder can take more input
                if (Clock::now() - start > std::chrono::seconds(10)) fail(Status::CodecError, "the audio encoder stalled");
            }
            size_t capacity = 0;
            uint8_t* in = AMediaCodec_getInputBuffer(encoder_.codec(), static_cast<size_t>(index), &capacity);
            if (in == nullptr) fail(Status::CodecError, "the audio encoder gave no input buffer");
            const int32_t fit = static_cast<int32_t>(capacity / (sizeof(int16_t) * kAudioChannels));
            const int32_t chunk = std::min(frames - done, fit);
            int16_t* out = reinterpret_cast<int16_t*>(in);
            for (int32_t i = 0; i < chunk * kAudioChannels; ++i) {
                const float v = std::clamp(pcm_[static_cast<size_t>(done) * kAudioChannels + static_cast<size_t>(i)], -1.0f, 1.0f);
                out[i] = static_cast<int16_t>(std::lrintf(v * 32767.0f));
            }
            const int64_t ptsUs = samplesToUs(written_, kAudioSampleRate);
            const bool last = (done + chunk >= frames);
            const uint32_t f = last ? flags : 0;
            if (AMediaCodec_queueInputBuffer(encoder_.codec(), static_cast<size_t>(index), 0,
                                             static_cast<size_t>(chunk) * sizeof(int16_t) * kAudioChannels,
                                             static_cast<uint64_t>(ptsUs), f) != AMEDIA_OK) {
                fail(Status::CodecError, "the audio encoder rejected input");
            }
            written_ += chunk;
            done += chunk;
        } while (done < frames);
    }

    Encoder encoder_;
    const ExportParams& params_;
    audio::AudioEngine engine_;
    std::vector<float> pcm_;
    int64_t written_ = 0;
};

// Decoded frames of one asset, shared between the decoder thread (isCached) and the export thread.
struct AssetState {
    std::unique_ptr<decode::IVideoDecoder> decoder;
    int64_t lastUsedFrame = 0;  // output frame that last needed this decoder
    std::shared_ptr<decode::GpuFrame> lastDelivered;  // the picture this layer last showed, repeated when a frame cannot be had
    bool degraded = false;                             // the last frame had to be repeated: give up on the next one sooner
    int64_t repeatedRun = 0;                           // frames repeated in a row
    decode::AssetInfo info;
    int turns = 0;
    bool reverse = false;  // the clip being drawn plays backwards: the decoder window is behind the frame
    std::mutex mu;
    std::map<int64_t, std::shared_ptr<decode::GpuFrame>> frames;
    int64_t mediaKey = 0;
};

class Renderer {
public:
    Renderer(const ExportParams& params, ANativeWindow* window) : params_(params), residency_(params.pictureBudgetBytes) {
        decode::Error e{decode::Status::Ok, ""};
        if (egl_.init(&e, true, params.hdr) != decode::Status::Ok) failDecode(e, "EGL setup failed");
        if (params.hdr && !egl_.tenBit()) {
            fail(Status::UnsupportedFormat, "this device offers no ten-bit encoder surface, so HDR cannot be exported");
        }
        if (!egl_.supportsPresentationTime()) {
            fail(Status::GlError, "this device lacks EGL_ANDROID_presentation_time, needed for exact timestamps");
        }
        if (egl_.attachWindow(window, &e, params.hdr) != decode::Status::Ok) failDecode(e, "cannot attach the encoder surface");
        if (params.hdr && !egl_.hdrSurface()) {
            fail(Status::UnsupportedFormat, "the encoder surface cannot be tagged BT.2020 HLG on this device");
        }
        if (egl_.makeCurrentWindow(&e) != decode::Status::Ok) failDecode(e, "cannot bind the encoder surface");
        if (params.hdr) {
            // ADATASPACE_BT2020_HLG (what the EGL colour-space attribute sets) is full range: the Pixel 8's encoder converts the RGB
            // frames with that range and tags the stream "pc" although the compositor writes limited-range-compatible HLG and the
            // format asks for limited. The ITU variant is the same primaries and transfer with limited range.
            ANativeWindow_setBuffersDataSpace(window, ADATASPACE_BT2020_ITU_HLG);
        }
        pipeline_ = std::make_unique<render::GlPipeline>(egl_);
        if (pipeline_->init(&e) != decode::Status::Ok) failDecode(e, "GLES setup failed");
        space_ = params.hdr ? render::OutputSpace::Hlg2020 : render::OutputSpace::Sdr709;
        pipeline_->setOutputSpace(space_);
        pipeline_->setInterpolationQuality(2);  // the exporter has the time for the wide flow search
        for (const TitleImage& title : params.titles) {
            upfront_.insert(title.key);
            if (pipeline_->uploadTitle(title.key, title.width, title.height, title.rgba.data(), &e, title.displayWidth,
                                       title.displayHeight) != decode::Status::Ok) {
                failDecode(e, "a title could not be prepared for the export");
            }
        }
        for (const LutImage& lut : params.luts) {
            if (pipeline_->uploadLut(lut.key, lut.size, lut.rgb.data(), &e) != decode::Status::Ok) {
                failDecode(e, "a LUT could not be prepared for the export");
            }
        }
        for (const auto& entry : params.assetFds) fds_[entry.first] = entry.second;
    }

    ~Renderer() {
        for (auto& entry : assets_) entry.second->decoder->shutdown();
        if (pipeline_) pipeline_->clearSourceCache();
        assets_.clear();
        pool_.clear();
        pipeline_.reset();
    }

    // Loads a still the first time a frame draws it and releases the least recently used ones beyond the budget; the
    // pictures this frame draws are never released. Titles are uploaded up front and are not managed here.
    void ensurePicture(uint32_t key) {
        framePictures_.insert(key);
        if (!params_.pictureLoader || upfront_.count(key) != 0) return;
        if (residency_.contains(key)) {
            residency_.touch(key);
            return;
        }
        PictureData picture;
        if (!params_.pictureLoader(key, &picture) || picture.rgba == nullptr) {
            fail(Status::InvalidArgument, "a picture could not be loaded for the export");
        }
        decode::Error e{decode::Status::Ok, ""};
        if (pipeline_->uploadTitle(key, picture.width, picture.height, picture.rgba, &e, picture.displayWidth,
                                   picture.displayHeight) != decode::Status::Ok) {
            failDecode(e, "a picture could not be prepared for the export");
        }
        const int64_t bytes = static_cast<int64_t>(picture.width) * picture.height * 4;
        for (uint32_t gone : residency_.admit(key, bytes, framePictures_)) pipeline_->releaseTitle(gone);
    }

    void renderFrame(int64_t frame) {
        decode::Error e{decode::Status::Ok, ""};
        const int w = egl_.windowWidth();
        const int h = egl_.windowHeight();
        const int64_t projectFrame = outputToProjectFrame(frame, params_.fps, params_.projectFps);
        outputFrame_ = frame;
        framePictures_.clear();

        // Every clip under the playhead, bottom layer first. `held` keeps the frames alive until the draw.
        struct Used {
            AssetState* asset;
            int64_t source;
            bool reverse;
            int64_t keepBehind;  // frames behind `source` (in the direction of play) that the next frame still reads
        };
        std::vector<std::shared_ptr<decode::GpuFrame>> held;
        std::vector<render::LayerDraw> layers;
        std::vector<Used> used;
        for (const VideoClip* clip : layersAt(params_.clips, projectFrame)) {
            const core::Pose pose = poseAt(*clip, projectFrame);
            const float opacity = static_cast<float>(opacityAt(*clip, projectFrame));
            if (clip->titleKey != 0) {
                ensurePicture(clip->titleKey);
                render::LayerDraw title;
                title.titleKey = clip->titleKey;
                title.fx = fxAt(*clip, projectFrame);
                title.transform = render::LayerTransform{
                    static_cast<float>(pose.posX),   static_cast<float>(pose.posY),        static_cast<float>(pose.scaleX),
                    static_cast<float>(pose.scaleY), static_cast<float>(pose.rotationDeg), opacity};
                layers.push_back(title);
                continue;
            }
            AssetState& asset = assetFor(clip->assetKey, clip->layer * 2 + clip->lane);
            asset.lastUsedFrame = frame;
            setDirection(asset, clip->reverse);
            const int64_t source = sourceFrameFor(*clip, projectFrame, asset.info.durationFrames);
            held.push_back(fetch(asset, source));
            render::LayerDraw layer;
            layer.frame = held.back().get();
            const int64_t direction = clip->reverse ? -1 : 1;
            // Smooth slow motion blends with the neighbouring frame; noise reduction and flicker removal read the
            // neighbours in time. The neighbour ahead is waited for, the one behind is only used if still decoded.
            const int mixPermille = sourceMixFor(*clip, projectFrame);
            if (mixPermille > 0) {
                const int64_t other = blendFrameFor(*clip, source, asset.info.durationFrames);
                if (other >= 0) {
                    held.push_back(fetch(asset, other));
                    layer.blendWith = held.back().get();
                    layer.blendMix = static_cast<float>(mixPermille) / 1000.0f;
                }
            }
            const core::NeighbourNeeds needs = core::neighbourNeeds(fxAt(*clip, projectFrame));
            int64_t keepBehind = 0;
            if (needs.next) {
                const int64_t ahead = source + direction;
                if (ahead >= 0 && (asset.info.durationFrames <= 0 || ahead < asset.info.durationFrames)) {
                    held.push_back(fetch(asset, ahead));
                    layer.next = held.back().get();
                }
            }
            if (needs.prev) {
                keepBehind = 1;
                const int64_t behind = source - direction;
                std::lock_guard<std::mutex> lock(asset.mu);
                const auto it = asset.frames.find(behind);
                if (it != asset.frames.end()) {
                    held.push_back(it->second);
                    layer.prev = held.back().get();
                }
            }
            // The clip carries what its source is (render::ColorMode value of any target); the target is the export's.
            layer.mode = render::colorModeFor(render::sourceTransferOf(static_cast<render::ColorMode>(clip->colorMode)), space_);
            layer.turns = asset.turns;
            layer.fx = fxAt(*clip, projectFrame);
            stab::resolveStabilisation(&layer.fx, source);  // the stabiliser's correction for this source frame
            layer.transform = render::LayerTransform{
                static_cast<float>(pose.posX),   static_cast<float>(pose.posY),     static_cast<float>(pose.scaleX),
                static_cast<float>(pose.scaleY), static_cast<float>(pose.rotationDeg), opacity};
            layers.push_back(layer);
            used.push_back({&asset, source, clip->reverse, keepBehind});
        }
        // No layers draws black: a gap in the timeline.
        if (pipeline_->drawScene(layers, params_.canvasWidth, params_.canvasHeight, w, h, &e) != decode::Status::Ok) {
            failDecode(e, "drawing a frame failed");
        }
        for (const Used& u : used) {
            if (u.reverse) {
                evictAfter(*u.asset, u.source + u.keepBehind);
            } else {
                evictBefore(*u.asset, u.source - u.keepBehind);
            }
        }
        if (frame % kIdleCheckFrames == 0) releaseIdleDecoders(frame);

        egl_.setPresentationTimeExact(frameToNs(frame, params_.fps));
        if (egl_.swap(&e) != decode::Status::Ok) failDecode(e, "presenting a frame to the encoder failed");
    }

    int64_t repeatedFrames() const { return repeatedFrames_; }

    void checkDecoderError() {
        std::lock_guard<std::mutex> lock(errorMu_);
        if (decoderError_) {
            // Say where in the movie it happened, so the clip can be found on the timeline.
            const int64_t seconds = params_.fps.num > 0 ? outputFrame_ * params_.fps.den / params_.fps.num : 0;
            char at[32];
            std::snprintf(at, sizeof(at), "%lld:%02lld", static_cast<long long>(seconds / 60), static_cast<long long>(seconds % 60));
            fail(fromDecode(decoderError_->code), "decoding failed at " + std::string(at) + " of the movie: " + decoderError_->message);
        }
    }

private:
    // One decoder per (media, slot), the slot being layer * 2 + lane: two layers, or the two sides
    // of a transition, showing the same file at different source frames must not fight over a
    // single decoder's position.
    AssetState& assetFor(int64_t key, int32_t layerSlot) {
        const auto slot = std::make_pair(key, layerSlot);
        auto it = assets_.find(slot);
        if (it != assets_.end()) return *it->second;
        auto fdIt = fds_.find(key);
        if (fdIt == fds_.end() || fdIt->second < 0) fail(Status::InvalidArgument, "a clip refers to media that was not provided");
        // The decoder takes ownership of its descriptor, even when opening fails; the job keeps the original.
        const int fd = core::openIndependent(fdIt->second);
        if (fd < 0) fail(Status::IoError, "cannot duplicate a media file descriptor");

        auto state = std::make_unique<AssetState>();
        AssetState* raw = state.get();
        state->mediaKey = key;
        decode::DecoderCallbacks callbacks;
        callbacks.onImageAvailable = [this] { wake(); };
        callbacks.isCached = [raw](int64_t frame) {
            std::lock_guard<std::mutex> lock(raw->mu);
            return raw->frames.count(frame) != 0;
        };
        callbacks.onError = [this](const decode::Error& error) {
            {
                std::lock_guard<std::mutex> lock(errorMu_);
                if (!decoderError_) decoderError_ = error;
            }
            wake();
        };
        auto opened = decode::openVideoDecoder(fd, decode::Rational{params_.projectFps.num, params_.projectFps.den}, std::move(callbacks));
        if (!opened.ok()) failDecode(opened.error(), "cannot open a video clip");
        state->decoder = std::move(opened.value());
        state->info = state->decoder->info();
        state->turns = ((state->info.rotationDegrees / 90) % 4 + 4) % 4;
        state->decoder->setWindow(0, kDecodeAhead);
        return *assets_.emplace(slot, std::move(state)).first->second;
    }

    // Returns the decoded frame for `source`, waiting for the decoder. A different frame stands in only
    // when the stream is known never to produce `source` (it skipped it, or it lies past the last frame):
    // a frame that is merely late must be waited for, or the export shows the previous picture twice.
    std::shared_ptr<decode::GpuFrame> fetch(AssetState& asset, int64_t source) {
        asset.decoder->setTarget(source);
        const auto began = Clock::now();
        auto lastProgress = began;
        auto lastRecover = began;
        size_t seen = 0;
        for (;;) {
            checkDecoderError();
            drain(asset);
            {
                std::lock_guard<std::mutex> lock(asset.mu);
                const FramePick pick = pickSourceFrame(asset.frames, source, asset.decoder->isUnavailable(source));
                if (pick.kind != FramePick::Kind::Wait) {
                    const auto waited = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - began).count();
                    if (waited > kSlowFetchMs) {
                        UV_LOGW("slow frame: source %lld took %lld ms (%s)", static_cast<long long>(source),
                                static_cast<long long>(waited), asset.decoder->describe().c_str());
                    }
                    if (pick.kind == FramePick::Kind::Substitute) {
                        UV_LOGW("frame %lld is not in the stream: showing %lld instead", static_cast<long long>(source),
                                static_cast<long long>(pick.frame));
                    }
                    asset.lastDelivered = asset.frames.at(pick.frame);
                    asset.degraded = false;
                    asset.repeatedRun = 0;
                    return asset.lastDelivered;
                }
                if (asset.frames.size() != seen) {
                    seen = asset.frames.size();
                    lastProgress = Clock::now();
                }
            }
            const auto recoverAfter = asset.degraded ? kDegradedRecoverAfter : kDecodeRecoverAfter;
            if (Clock::now() - lastProgress > recoverAfter && Clock::now() - lastRecover > recoverAfter) {
                lastRecover = Clock::now();
                asset.decoder->recover(source);
            }
            if (Clock::now() - lastProgress > (asset.degraded ? kDegradedStall : kDecodeStall)) {
                std::string have;
                {
                    std::lock_guard<std::mutex> lock(asset.mu);
                    if (!asset.frames.empty()) {
                        have = " cached " + std::to_string(asset.frames.begin()->first) + ".." + std::to_string(asset.frames.rbegin()->first) +
                               " (" + std::to_string(asset.frames.size()) + ")";
                    }
                }
                const std::string state = asset.decoder->describe();
                UV_LOGE("decoder stalled: wanted %lld;%s; %s", static_cast<long long>(source), have.c_str(), state.c_str());
                // Do not lose the whole export for one picture: show the one this layer showed last (or the nearest held one),
                // count it, and report it at the end. A clip that yields nothing for more than kMaxRepeatSeconds of movie is
                // really broken and still fails.
                std::shared_ptr<decode::GpuFrame> stand = asset.lastDelivered;
                if (!stand) {
                    std::lock_guard<std::mutex> lock(asset.mu);
                    if (!asset.frames.empty()) {
                        auto nearest = asset.frames.lower_bound(source);
                        if (nearest == asset.frames.end()) --nearest;
                        stand = nearest->second;
                    }
                }
                const int64_t maxRun = kMaxRepeatSeconds * params_.fps.num / params_.fps.den;
                if (stand && asset.repeatedRun < maxRun) {
                    ++asset.repeatedRun;
                    ++repeatedFrames_;
                    asset.degraded = true;
                    if (repeatedFrames_ <= 20) {
                        UV_LOGW("frame %lld of media %lld could not be decoded: repeating the previous picture (%lld so far)",
                                static_cast<long long>(source), static_cast<long long>(asset.mediaKey),
                                static_cast<long long>(repeatedFrames_));
                    }
                    asset.lastDelivered = stand;
                    return stand;
                }
                fail(Status::CodecError, "the decoder stalled at source frame " + std::to_string(source) + " (" + state + ")");
            }
            std::unique_lock<std::mutex> lock(wakeMu_);
            wakeCv_.wait_for(lock, std::chrono::milliseconds(20));
        }
    }

    void drain(AssetState& asset) {
        asset.decoder->drainImages([&](int64_t frame, AHardwareBuffer* buffer) -> int {
            {
                std::lock_guard<std::mutex> lock(asset.mu);
                if (asset.frames.count(frame) != 0) {
                    asset.decoder->markResolved(frame);
                    return -1;
                }
            }
            decode::Error e{decode::Status::Ok, ""};
            std::shared_ptr<decode::GpuFrame> gpu = takeFromPool(asset.info.width, asset.info.height);
            if (!gpu) {
                auto allocated = decode::allocateGpuFrame(static_cast<uint32_t>(asset.info.width),
                                                          static_cast<uint32_t>(asset.info.height));
                if (!allocated.ok()) {
                    asset.decoder->markResolved(frame);
                    failDecode(allocated.error(), "cannot allocate a frame buffer");
                }
                gpu = allocated.value();
            }
            int releaseFence = -1;
            if (pipeline_->blitToFrame(buffer, *gpu, &releaseFence, &e) != decode::Status::Ok) {
                asset.decoder->markResolved(frame);
                failDecode(e, "converting a decoded frame failed");
            }
            {
                std::lock_guard<std::mutex> lock(asset.mu);
                asset.frames[frame] = std::move(gpu);
            }
            asset.decoder->markResolved(frame);
            return releaseFence;
        });
    }

    std::shared_ptr<decode::GpuFrame> takeFromPool(int32_t width, int32_t height) {
        for (auto it = pool_.begin(); it != pool_.end(); ++it) {
            if ((*it)->width() == static_cast<uint32_t>(width) && (*it)->height() == static_cast<uint32_t>(height)) {
                auto frame = std::move(*it);
                pool_.erase(it);
                return frame;
            }
        }
        return nullptr;
    }

    // The decoder keeps its window ahead of the frame for a clip that plays forwards and behind it for one
    // that plays backwards (a mirrored window: one pass over a GOP then serves the next stretch).
    void setDirection(AssetState& asset, bool reverse) {
        if (asset.reverse == reverse) return;
        asset.reverse = reverse;
        if (reverse) {
            const int64_t frameBytes = static_cast<int64_t>(asset.info.width) * asset.info.height * 4;
            asset.decoder->setWindow(reverseWindowFrames(frameBytes), 0);
        } else {
            asset.decoder->setWindow(0, kDecodeAhead);
        }
    }

    // Playing backwards the frames after `source` are never needed again; recycle them.
    void evictAfter(AssetState& asset, int64_t source) {
        std::lock_guard<std::mutex> lock(asset.mu);
        for (auto it = asset.frames.upper_bound(source); it != asset.frames.end();) {
            if (it->second.use_count() == 1 && pool_.size() < 8) pool_.push_back(std::move(it->second));
            it = asset.frames.erase(it);
        }
    }

    // Frames before `source` are never needed again (output only moves forward); recycle them.
    void evictBefore(AssetState& asset, int64_t source) {
        std::lock_guard<std::mutex> lock(asset.mu);
        auto end = asset.frames.lower_bound(source);
        for (auto it = asset.frames.begin(); it != end;) {
            if (it->second.use_count() == 1 && pool_.size() < 8) pool_.push_back(std::move(it->second));
            it = asset.frames.erase(it);
        }
    }

    // Hardware decoders are scarce: close the ones no clip has needed for a couple of seconds.
    void releaseIdleDecoders(int64_t frame) {
        const int64_t idle = std::max<int64_t>(60, static_cast<int64_t>(2) * params_.fps.num / params_.fps.den);
        bool released = false;
        for (auto it = assets_.begin(); it != assets_.end();) {
            if (frame - it->second->lastUsedFrame > idle) {
                it->second->decoder->shutdown();
                it = assets_.erase(it);
                released = true;
            } else {
                ++it;
            }
        }
        if (released) pipeline_->clearSourceCache();
    }

    void wake() {
        std::lock_guard<std::mutex> lock(wakeMu_);
        wakeCv_.notify_all();
    }

    const ExportParams& params_;
    PictureResidency residency_;                // stills loaded on demand and their bytes
    std::unordered_set<uint32_t> upfront_;       // title keys uploaded before the first frame
    std::unordered_set<uint32_t> framePictures_;  // pictures the frame being rendered draws
    render::EglContext egl_;
    std::unique_ptr<render::GlPipeline> pipeline_;
    render::OutputSpace space_ = render::OutputSpace::Sdr709;
    std::map<int64_t, int> fds_;
    std::map<std::pair<int64_t, int32_t>, std::unique_ptr<AssetState>> assets_;
    std::vector<std::shared_ptr<decode::GpuFrame>> pool_;

    std::mutex wakeMu_;
    std::condition_variable wakeCv_;
    std::mutex errorMu_;
    int64_t repeatedFrames_ = 0;  // frames shown again because the decoder could not deliver them
    int64_t outputFrame_ = 0;  // render thread: the output frame being made, for error messages
    std::optional<decode::Error> decoderError_;
};

void setVideoFormat(AMediaFormat* format, const ExportParams& p) {
    const char* mime = p.codec == VideoCodec::Hevc ? "video/hevc" : "video/avc";
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, p.width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, p.height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, kColorFormatSurface);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, p.videoBitrate);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE,
                          static_cast<int32_t>(std::lround(static_cast<double>(p.fps.num) / p.fps.den)));
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BITRATE_MODE, 1);  // VBR
    if (p.hdr) {
        // HEVC Main10, BT.2020 primaries, HLG transfer, limited range: what the compositor outputs in an HLG project.
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE, kHevcProfileMain10);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, kColorStandardBt2020);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_RANGE, kColorRangeLimited);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, kColorTransferHlg);
        return;
    }
    // Tag the stream as BT.709 limited-range SDR, which is what the compositor outputs.
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, 1);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_RANGE, kColorRangeLimited);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, 3);
}

}  // namespace

ExportJob::ExportJob(ExportParams params, ProgressSink progress, DoneSink done)
    : params_(std::move(params)), progress_(std::move(progress)), done_(std::move(done)) {}

ExportJob::~ExportJob() {
    cancel();
    if (thread_.joinable()) thread_.join();
    closeDescriptors();  // only does anything when the job was never started
}

void ExportJob::closeDescriptors() {
    if (params_.outputFd >= 0) ::close(params_.outputFd);
    params_.outputFd = -1;
    for (auto& entry : params_.assetFds) {
        if (entry.second >= 0) ::close(entry.second);
        entry.second = -1;
    }
}

void ExportJob::start() {
    thread_ = std::thread([this] { run(); });
}

void ExportJob::cancel() { cancelled_.store(true); }

void ExportJob::run() {
    Status status = Status::Ok;
    std::string message;
    try {
        message = execute();
    } catch (const ExportFailure& f) {
        status = f.status;
        message = f.message;
    } catch (const std::exception& e) {
        status = Status::CodecError;
        message = std::string("unexpected error: ") + e.what();
    }
    closeDescriptors();
    if (status != Status::Ok) UV_LOGE("export failed (%s): %s", core::statusName(status), message.c_str());
    done_(status, message);
}

std::string ExportJob::execute() {
    if (params_.width <= 0 || params_.height <= 0 || params_.fps.num <= 0 || params_.fps.den <= 0 ||
        params_.projectFps.num <= 0 || params_.projectFps.den <= 0 || params_.totalFrames <= 0 ||
        params_.videoBitrate <= 0) {
        fail(Status::InvalidArgument, "invalid export settings");
    }
    if (params_.canvasWidth <= 0 || params_.canvasHeight <= 0) {  // no project size given: use the output's
        params_.canvasWidth = params_.width;
        params_.canvasHeight = params_.height;
    }
    if (params_.hdr && params_.codec != VideoCodec::Hevc) {
        fail(Status::InvalidArgument, "HDR export needs HEVC (Main10); H.264 cannot carry HLG here");
    }
    const bool hasAudio = !params_.audioSnapshot.empty();
    const auto begin = Clock::now();

    Muxer muxer(params_.outputFd, hasAudio ? 2 : 1);

    Encoder video(muxer);
    ANativeWindow* surface = nullptr;
    {
        AMediaFormat* format = AMediaFormat_new();
        setVideoFormat(format, params_);
        try {
            video.create(params_.codec == VideoCodec::Hevc ? "video/hevc" : "video/avc", format, &surface);
        } catch (...) {
            AMediaFormat_delete(format);
            throw;
        }
        AMediaFormat_delete(format);
    }
    std::unique_ptr<ANativeWindow, void (*)(ANativeWindow*)> surfaceGuard(surface, ANativeWindow_release);

    std::unique_ptr<AudioPump> audioPump;
    if (hasAudio) {
        audioPump = std::make_unique<AudioPump>(muxer, params_);
        audioPump->start(params_.assetFds);
    }

    int64_t repeated = 0;
    {
        Renderer renderer(params_, surface);

        int64_t lastReport = -1;
        for (int64_t frame = 0; frame < params_.totalFrames; ++frame) {
            if (cancelled_.load()) fail(Status::Cancelled, "export cancelled");
            renderer.renderFrame(frame);
            video.drain(false);
            if (audioPump) {
                audioPump->pumpTo(framesToSamples(frame + 1, params_.fps, kAudioSampleRate));
                audioPump->drain();
                // Every frame: a clip whose audio never becomes ready blocks each render for 30 s, so a check every 30
                // frames would let such an export run for the better part of an hour before failing.
                audioPump->checkFaults();
            }
            const int32_t permille = progressPermille(frame + 1, params_.totalFrames);
            if (permille != lastReport) {
                lastReport = permille;
                progress_(permille);
            }
        }
        renderer.checkDecoderError();
        repeated = renderer.repeatedFrames();
        if (AMediaCodec_signalEndOfInputStream(video.codec()) != AMEDIA_OK) {
            fail(Status::CodecError, "cannot end the video stream");
        }
    }

    video.drain(true);
    if (audioPump) {
        audioPump->checkFaults();
        audioPump->finish();
    }
    muxer.finish();
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - begin).count();
    UV_LOGI("export done: %lld frames in %lld ms", static_cast<long long>(params_.totalFrames), static_cast<long long>(ms));
    if (repeated > 0) {
        return std::to_string(repeated) + " frames could not be decoded and were repeated";
    }
    return {};
}

}  // namespace uv::encode
