// Host tool (not a test): what smart export would copy from a project, using the real planner and the real source files.
// Driven by scripts/smart-plan-report.py, which turns a project.json into the clip list read here.
//   g++ -std=c++20 -O1 -Iapp/src/main/cpp app/src/main/cpp/tests/smart_report.cpp app/src/main/cpp/encode/mp4_source.cpp \
//       app/src/main/cpp/encode/smart_source.cpp -o smart_report
//   smart_report <clips.txt> <outW> <outH> <fps> <hdr 0|1> <totalFrames>
// clips.txt, one clip per line: start duration sourceIn assetKey layer colorMode posX posY scaleX scaleY rotation opacity fadeIn
// titleKey neutral(0/1: no effect, mask, blend, keyframes, retime, reverse); then lines "asset <key> <path>".
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <sstream>

#include "encode/smart_source.h"

using namespace uv::encode;
using namespace uv::encode::smart;

int main(int argc, char** argv) {
    if (argc < 7) {
        std::printf("usage: %s clips.txt outW outH fps hdr totalFrames\n", argv[0]);
        return 2;
    }
    std::vector<VideoClip> clips;
    std::map<int64_t, std::string> paths;
    std::ifstream in(argv[1]);
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ls(line);
        std::string head;
        ls >> head;
        if (head == "asset") {
            int64_t key;
            std::string path;
            ls >> key;
            std::getline(ls >> std::ws, path);
            paths[key] = path;
            continue;
        }
        VideoClip c;
        c.startFrame = std::atoll(head.c_str());
        int neutral = 1;
        unsigned titleKey = 0;
        ls >> c.durationFrames >> c.sourceInFrame >> c.assetKey >> c.layer >> c.colorMode >> c.posX >> c.posY >> c.scaleX >> c.scaleY >> c.rotationDeg >>
            c.opacity >> c.fadeInFrames >> titleKey >> neutral;
        c.titleKey = titleKey;
        if (!neutral) c.reverse = true;  // any retime or effect: not copyable, which is all the planner needs to know
        clips.push_back(c);
    }
    Context ctx;
    ctx.outWidth = ctx.canvasWidth = std::atoi(argv[2]);
    ctx.outHeight = ctx.canvasHeight = std::atoi(argv[3]);
    ctx.outFps = ctx.projectFps = {std::atoi(argv[4]), 1};
    ctx.hdr = std::atoi(argv[5]) != 0;
    ctx.hevc = true;
    ctx.totalFrames = std::atoll(argv[6]);
    OutputSpec spec{ctx.outWidth, ctx.outHeight, ctx.outFps, ctx.hdr};
    std::map<int64_t, InspectedAsset> assets;
    const AssetLookup lookup = [&](int64_t key) -> const AssetForSmart* {
        if (auto it = assets.find(key); it != assets.end()) return &it->second.plan;
        const auto p = paths.find(key);
        InspectedAsset a;
        if (p == paths.end()) {
            a.why = "file not available";
        } else {
            const int fd = ::open(p->second.c_str(), O_RDONLY);
            struct stat st {};
            if (fd >= 0 && ::fstat(fd, &st) == 0) {
                a = inspectAsset([fd](uint64_t off, void* out, size_t n) { return ::pread(fd, out, n, static_cast<off_t>(off)) == static_cast<ssize_t>(n); },
                                 static_cast<uint64_t>(st.st_size), spec);
            } else {
                a.why = "cannot open";
            }
            if (fd >= 0) ::close(fd);
        }
        std::printf("asset %lld %s: %s\n", static_cast<long long>(key), p == paths.end() ? "?" : p->second.c_str(), a.plan.ok ? "copyable" : a.why.c_str());
        return &assets.emplace(key, std::move(a)).first->second.plan;
    };
    const Plan plan = planSmartExport(clips, ctx, lookup);
    if (!plan.usable) {
        std::printf("not usable: %s\n", plan.reason.c_str());
        return 0;
    }
    std::printf("rotation %d, %zu segments\n", plan.rotation, plan.segments.size());
    for (const CopySegment& s : plan.segments) {
        std::printf("  out %7lld .. %7lld  (%6lld frames)  asset %lld  source %lld\n", static_cast<long long>(s.outStart),
                    static_cast<long long>(s.outStart + s.frames - 1), static_cast<long long>(s.frames), static_cast<long long>(s.assetKey),
                    static_cast<long long>(s.run.presStart));
    }
    std::printf("copied %lld of %lld frames = %.1f%% (%.1f s of %.1f s)\n", static_cast<long long>(plan.copiedFrames),
                static_cast<long long>(ctx.totalFrames), 100.0 * plan.copiedFrames / ctx.totalFrames, plan.copiedFrames / double(ctx.outFps.num),
                ctx.totalFrames / double(ctx.outFps.num));
    return 0;
}
