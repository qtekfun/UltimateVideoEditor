#pragma once

// A small MP4 writer for smart export (SPECS.md 5.10), needed because AMediaMuxer cannot write several sample entries per
// track. One video track (HEVC, several `hvc1` sample entries, B-frame reordering through ctts and an edit list for its
// delay) and one optional AAC track. Samples are written to the file as they arrive (mdat with a 64-bit size, patched at
// the end, so files over 4 GB work); the sample tables follow in a moov box at the end, like AMediaMuxer's output.
// All integer maths; presentation times are exact frame multiples.

#include <cstdint>
#include <string>
#include <vector>

#include "encode/export_math.h"

namespace uv::encode {

class Mp4Writer {
public:
    struct VideoEntry {
        std::vector<uint8_t> hvcc;  // payload of the hvcC box
        std::vector<uint8_t> colr;  // payload of the colr box (e.g. "nclx" + 3 x u16 + flag byte); empty = no colr box
    };
    struct VideoConfig {
        int32_t width = 0, height = 0;
        Fps fps;
        int rotationDegrees = 0;  // stored picture is shown rotated clockwise by this much (0, 90, 180, 270)
        std::vector<VideoEntry> entries;
    };
    struct AudioConfig {
        int32_t sampleRate = 48000;
        int32_t channels = 2;
        int32_t bitrate = 0;
        std::vector<uint8_t> audioSpecificConfig;  // AAC csd-0
    };

    // `fd`: seekable, read/write, empty; not closed here. `audio` null = no audio track.
    Mp4Writer(int fd, VideoConfig video, const AudioConfig* audio);

    // Appends a video sample in decode order. `presFrame` is the frame it is shown at (0 = first frame of the movie).
    // False after an I/O error (see error()).
    bool addVideoSample(const uint8_t* data, size_t size, int entry, int64_t presFrame, bool sync);
    // Appends the next AAC frame (1024 samples each, back to back from time 0).
    bool addAudioSample(const uint8_t* data, size_t size);
    // Writes the sample tables and patches the mdat header. `frames` must equal the number of video samples.
    bool finish();

    int64_t videoSamples() const { return static_cast<int64_t>(video_.size()); }
    int64_t bytesWritten() const { return static_cast<int64_t>(pos_); }
    const std::string& error() const { return error_; }

    // Re-reads the finished file: the moov box sits where the sample data ends and fills the rest, and every `stride`-th sample
    // of each track, the first and the last, read back with the CRC taken when it was written. False with a reason on a mismatch.
    bool verify(int stride, std::string* why) const;

    // Tests only: moves the write position forward by `bytes` without writing them (a sparse hole), to exercise 64-bit offsets.
    bool skipBytesForTest(uint64_t bytes);

    // The moov box for the samples written so far; exposed for tests.
    std::vector<uint8_t> buildMoov() const;

private:
    struct Sample {
        uint32_t size;
        int64_t pres;  // video only
        bool sync;
        uint8_t entry;
        uint32_t crc;
    };
    struct Chunk {
        bool audio;
        uint8_t entry;
        uint64_t offset;
        uint32_t firstSample;
        uint32_t count;
    };

    bool put(const uint8_t* data, size_t size);
    bool flush();
    bool noteChunk(bool audio, uint8_t entry, size_t size);

    int fd_;
    VideoConfig vcfg_;
    bool hasAudio_;
    AudioConfig acfg_;
    std::vector<Sample> video_, audio_;
    std::vector<Chunk> chunks_;
    std::vector<uint8_t> buffer_;
    uint64_t pos_ = 0;       // file offset of the next byte (including the buffer)
    uint64_t flushed_ = 0;   // file offset of the buffer's first byte
    uint64_t mdatSizeAt_ = 0;
    uint64_t mdatStart_ = 0;
    uint64_t mdatEnd_ = 0;
    std::string error_;
    bool finished_ = false;
};

}  // namespace uv::encode
