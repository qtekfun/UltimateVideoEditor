// Host tool (not a test): shows what smart export would make of a media file.
//   g++ -std=c++20 -O1 -Iapp/src/main/cpp app/src/main/cpp/tests/smart_probe.cpp app/src/main/cpp/encode/mp4_source.cpp \
//       app/src/main/cpp/encode/smart_source.cpp -o /tmp/smart_probe
//   smart_probe <file> <outW> <outH> <fps> <hdr 0|1> [srcStart srcEnd]
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <map>

#include "encode/smart_source.h"

using namespace uv::encode;
using namespace uv::encode::smart;

int main(int argc, char** argv) {
    if (argc < 6) {
        std::printf("usage: %s file outW outH fps hdr [srcStart srcEnd]\n", argv[0]);
        return 2;
    }
    const int fd = ::open(argv[1], O_RDONLY);
    if (fd < 0) { std::perror("open"); return 1; }
    struct stat st {};
    ::fstat(fd, &st);
    OutputSpec spec;
    spec.width = std::atoi(argv[2]);
    spec.height = std::atoi(argv[3]);
    spec.fps = {std::atoi(argv[4]), 1};
    spec.hdr = std::atoi(argv[5]) != 0;
    const InspectedAsset a = inspectAsset(
        [&](uint64_t off, void* out, size_t n) { return ::pread(fd, out, n, static_cast<off_t>(off)) == static_cast<ssize_t>(n); },
        static_cast<uint64_t>(st.st_size), spec);
    std::printf("%s: ok=%d why=%s\n", argv[1], a.plan.ok, a.why.c_str());
    if (!a.parsed) return 0;
    std::printf("entry=%s %ux%u rotation=%d timescale=%u samples=%zu editStart=%lld nclx=%d (%u,%u,%u)\n", a.track.entryType.c_str(),
                a.track.width, a.track.height, a.track.rotationDegrees, a.track.timescale, a.track.samples.size(),
                static_cast<long long>(a.track.editStart), a.track.hasNclx, a.track.primaries, a.track.transfer, a.track.matrix);
    std::map<int, int> types;
    int offGrid = 0;
    for (const GopSample& g : a.plan.samples) {
        if (g.irapType >= 0) ++types[g.irapType];
        if (g.pres == kNoFrame) ++offGrid;
    }
    for (const auto& [t, n] : types) std::printf("  random access NAL type %d: %d\n", t, n);
    std::printf("  samples off the frame grid: %d\n", offGrid);
    if (argc >= 8) {
        const auto run = trimToGop(a.plan.samples, std::atoll(argv[6]), std::atoll(argv[7]), 30);
        if (run) {
            std::printf("  copy run: samples [%zu, %zu) shows source frames %lld .. %lld (%lld frames) of the stretch %s..%s\n", run->firstSample,
                        run->endSample, static_cast<long long>(run->presStart), static_cast<long long>(run->presStart + run->count - 1),
                        static_cast<long long>(run->count), argv[6], argv[7]);
        } else {
            std::printf("  no copy run\n");
        }
    }
    return 0;
}
