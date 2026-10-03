#pragma once

#include <cstdint>

namespace uv::core {

// Error codes shared with Kotlin (engine/EngineErrorCode). Keep values stable.
enum class Status : int32_t {
    Ok = 0,
    InvalidArgument = 1,
    BadSnapshot = 2,
    IoError = 3,
    UnsupportedFormat = 4,
    CodecError = 5,
    GlError = 6,
    NotInitialized = 7,
    Cancelled = 8,
};

constexpr bool ok(Status s) { return s == Status::Ok; }
const char* statusName(Status s);

}  // namespace uv::core
