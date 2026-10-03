#pragma once

// Offline export: renders the project frame by frame through the GLES compositor into a
// MediaCodec encoder input surface, mixes audio with the offline audio engine, encodes it to AAC
// and muxes both into an MP4. Runs on its own thread with its own EGL context, independent of the
// preview. Failures are reported with a core::Status and a message, never swallowed.

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "core/error.h"
#include "encode/export_math.h"

namespace uv::encode {

enum class VideoCodec : int32_t { H264 = 0, Hevc = 1 };

// A rasterised title: premultiplied RGBA8, `width` x `height` canvas pixels, top row first.
struct TitleImage {
    uint32_t key = 0;
    int32_t width = 0;
    int32_t height = 0;
    std::vector<uint8_t> rgba;
};

struct ExportParams {
    int32_t width = 0;
    int32_t height = 0;
    Fps fps;         // output frame rate
    Fps projectFps;  // rate that VideoClip frames are expressed in
    int32_t canvasWidth = 0;   // project resolution: clip positions are measured in this canvas
    int32_t canvasHeight = 0;
    VideoCodec codec = VideoCodec::H264;
    // HLG output: composite in the Rec.2020 HLG space, tag the stream BT.2020 / HLG and encode HEVC
    // Main10. Needs a ten-bit encoder surface; the job fails with UnsupportedFormat when the device
    // cannot provide one (the app checks codec support first and offers an SDR export instead).
    bool hdr = false;
    int32_t videoBitrate = 0;
    int32_t audioBitrate = 192000;
    int64_t totalFrames = 0;
    std::vector<VideoClip> clips;
    std::vector<TitleImage> titles;  // referenced by VideoClip::titleKey
    // (assetKey, fd): the job owns the descriptors and closes them.
    std::vector<std::pair<int64_t, int>> assetFds;
    // Audio snapshot (audio/audio_snapshot.h layout); empty means the movie has no audio track.
    std::vector<uint8_t> audioSnapshot;
    int outputFd = -1;  // read/write, seekable; owned by the job
};

class ExportJob {
public:
    using ProgressSink = std::function<void(int32_t permille)>;
    using DoneSink = std::function<void(core::Status status, const std::string& message)>;

    ExportJob(ExportParams params, ProgressSink progress, DoneSink done);
    ~ExportJob();  // cancels and joins
    ExportJob(const ExportJob&) = delete;
    ExportJob& operator=(const ExportJob&) = delete;

    void start();
    void cancel();

private:
    void run();
    void execute();  // throws ExportFailure
    void closeDescriptors();

    ExportParams params_;
    ProgressSink progress_;
    DoneSink done_;
    std::atomic<bool> cancelled_{false};
    std::thread thread_;
};

}  // namespace uv::encode
