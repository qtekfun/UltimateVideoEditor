#pragma once

#include <cstdint>
#include <string>

#include "core/error.h"

namespace uv::thumb {

// media_status_t values of NdkMediaError.h (a plain int here so the policy builds and is tested on the host).
constexpr int32_t kMediaErrorMalformed = -10001;
constexpr int32_t kMediaErrorUnsupported = -10002;
constexpr int32_t kMediaErrorIo = -10007;

// What a refusal of AMediaExtractor_setDataSourceFd means for the thumbnail. A photo (JPEG/PNG/WebP) is refused with
// "unsupported" because no extractor sniffs it; that is not an I/O failure, and the caller then tries the image decoder.
inline core::Status statusForExtractorError(int32_t mediaStatus) {
    switch (mediaStatus) {
        case kMediaErrorIo: return core::Status::IoError;
        case kMediaErrorMalformed:
        case kMediaErrorUnsupported: return core::Status::UnsupportedFormat;
        default: return core::Status::CodecError;
    }
}

// Whether a file the extractor refused is worth handing to the image decoder (everything except a read error).
inline bool mayBePhoto(int32_t mediaStatus) { return mediaStatus != kMediaErrorIo; }

// One line for the log and the error callback: what failed and the underlying code.
inline std::string describeFailure(const char* stage, int32_t code, int64_t fileSize) {
    return std::string(stage) + " " + std::to_string(code) + " (file " + std::to_string(fileSize) + " bytes)";
}

}  // namespace uv::thumb
