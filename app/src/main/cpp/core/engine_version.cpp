#include "core/engine_version.h"

namespace uv::core {

// UV_ENGINE_VERSION is set by the Gradle build from gradle/version.properties; the fallback is for builds without it (host tests).
const char* engineVersion() noexcept {
#ifdef UV_ENGINE_VERSION
    return UV_ENGINE_VERSION;
#else
    return "0.0.0-dev";
#endif
}

}  // namespace uv::core
