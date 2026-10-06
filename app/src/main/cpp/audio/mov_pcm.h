#pragma once

// Uncompressed audio in a QuickTime/MP4 file ('lpcm', 'sowt', 'twos', 'in24', 'in32', 'fl32', 'fl64', 'raw '), as a PcmDecoder.
// Android's MediaExtractor does not expose these tracks (iPhone footage with a linear-PCM soundtrack, e.g. Apple Log or
// some external-mic recordings, opens as video only), so the platform decoder reports "no audio track". The samples are
// raw, so no codec is needed: the sample tables say where each chunk lies and the bytes are converted to float here.
// Pure logic over a RandomReader (host-testable); the Android entry point wraps a duplicated file descriptor.
// Edit lists are not applied (a start offset of a few milliseconds is ignored). More than two channels use the first two.

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "audio/pcm_decoder.h"
#include "core/error.h"

namespace uv::audio {

// Random access to the bytes of a file.
class RandomReader {
public:
    virtual ~RandomReader() = default;
    virtual uint64_t size() const = 0;
    // Reads exactly `n` bytes at `offset`; false when that range is not (fully) readable.
    virtual bool readAt(uint64_t offset, void* dst, size_t n) = 0;
};

struct MovPcmFormat {
    int32_t sampleRate = 0;
    int32_t channels = 0;
    int32_t bitsPerSample = 0;  // 8, 16, 24, 32 or 64
    bool isFloat = false;
    bool bigEndian = false;
    bool isSigned = true;
    int32_t bytesPerFrame() const { return ((bitsPerSample + 7) / 8) * channels; }
};

// A run of whole frames stored contiguously in the file.
struct MovPcmChunk {
    uint64_t offset = 0;
    uint64_t firstFrame = 0;
    uint64_t frames = 0;
};

struct MovPcmTrack {
    MovPcmFormat format;
    std::vector<MovPcmChunk> chunks;
    uint64_t totalFrames = 0;
};

// Finds the first sound track with an uncompressed sample entry and reads its sample tables.
// UnsupportedFormat: no such track (or an unsupported layout); IoError: unreadable or damaged boxes.
core::Status parseMovPcm(RandomReader& reader, MovPcmTrack* out);

// Converts `frames` frames of `fmt` at `src` to interleaved stereo float in [-1, 1].
void convertMovPcm(const uint8_t* src, size_t frames, const MovPcmFormat& fmt, float* dstStereo);

// Takes ownership of `reader`. Returns nullptr and sets *status on failure.
std::unique_ptr<PcmDecoder> openMovPcmDecoder(std::unique_ptr<RandomReader> reader, core::Status* status);

// Duplicates `fd` (the caller keeps its own) and reads it with pread().
std::unique_ptr<PcmDecoder> openMovPcmDecoderFd(int fd, core::Status* status);

}  // namespace uv::audio
