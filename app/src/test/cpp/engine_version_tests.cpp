// engineVersion() is what the hub footer ("Engine v...") shows. Built twice by scripts/run-native-tests.sh: once with
// -DUV_ENGINE_VERSION="<versionName of gradle/version.properties>" (compared with EXPECTED_VERSION) and once without it, where it must
// say it is a development build. A version typed into the source (the footer once said 0.1.0 for weeks) fails the first build.
#include <cstdio>
#include <cstring>

#include "core/engine_version.h"

#ifndef EXPECTED_VERSION
#error "EXPECTED_VERSION must be defined (the build script passes versionName from gradle/version.properties)"
#endif

int main() {
    const char* version = uv::core::engineVersion();
#ifdef UV_ENGINE_VERSION
    if (std::strcmp(version, EXPECTED_VERSION) != 0) {
        std::fprintf(stderr, "FAIL engineVersion() is '%s', gradle/version.properties says '%s'\n", version, EXPECTED_VERSION);
        return 1;
    }
#else
    if (std::strcmp(version, "0.0.0-dev") != 0) {
        std::fprintf(stderr, "FAIL a build without UV_ENGINE_VERSION must report 0.0.0-dev, not '%s'\n", version);
        return 1;
    }
#endif
    std::printf("engine version ok: %s\n", version);
    return 0;
}
