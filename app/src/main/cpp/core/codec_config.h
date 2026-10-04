#pragma once

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <cstdint>
#include <cstring>
#include <vector>

#include <sys/types.h>

namespace uv::core {

// The Huawei hisi H.264 decoder forgets its SPS/PPS on AMediaCodec_flush: every packet after a seek is rejected ("PPS or
// SPS of this slice not valid") and nothing is ever output, because an MP4 keeps the parameter sets only in the track
// format (csd-0..2). The MediaCodec documentation asks for codec-specific data to be queued again after a flush; decoders
// that keep it simply ignore the repeat. Every decoder that flushes (preview, thumbnails) uses these two helpers.
using CodecConfig = std::vector<std::vector<uint8_t>>;

// Copies csd-0..2 out of a track format.
inline CodecConfig captureCodecConfig(AMediaFormat* format) {
    CodecConfig config;
    for (const char* key : {"csd-0", "csd-1", "csd-2"}) {
        void* data = nullptr;
        size_t size = 0;
        if (AMediaFormat_getBuffer(format, key, &data, &size) && data != nullptr && size > 0) {
            const auto* bytes = static_cast<const uint8_t*>(data);
            config.emplace_back(bytes, bytes + size);
        }
    }
    return config;
}

// Queues the parameter sets with BUFFER_FLAG_CODEC_CONFIG. Call right after AMediaCodec_flush. Returns false when an input
// buffer could not be had or was too small (the caller logs; decoding then behaves as it did before).
inline bool queueCodecConfig(AMediaCodec* codec, const CodecConfig& config) {
    for (const std::vector<uint8_t>& entry : config) {
        const ssize_t index = AMediaCodec_dequeueInputBuffer(codec, 100000);
        if (index < 0) return false;
        size_t capacity = 0;
        uint8_t* buffer = AMediaCodec_getInputBuffer(codec, static_cast<size_t>(index), &capacity);
        if (buffer == nullptr || capacity < entry.size()) {
            AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(index), 0, 0, 0, 0);
            return false;
        }
        std::memcpy(buffer, entry.data(), entry.size());
        AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(index), 0, entry.size(), 0,
                                     AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG);
    }
    return true;
}

}  // namespace uv::core
