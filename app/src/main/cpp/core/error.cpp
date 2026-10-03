#include "core/error.h"

namespace uv::core {

const char* statusName(Status s) {
    switch (s) {
        case Status::Ok: return "Ok";
        case Status::InvalidArgument: return "InvalidArgument";
        case Status::BadSnapshot: return "BadSnapshot";
        case Status::IoError: return "IoError";
        case Status::UnsupportedFormat: return "UnsupportedFormat";
        case Status::CodecError: return "CodecError";
        case Status::GlError: return "GlError";
        case Status::NotInitialized: return "NotInitialized";
        case Status::Cancelled: return "Cancelled";
    }
    return "Unknown";
}

}  // namespace uv::core
