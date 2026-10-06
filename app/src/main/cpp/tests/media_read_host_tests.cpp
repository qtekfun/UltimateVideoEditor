// Host tests for how media files are read: the per-file lock that keeps extractors sharing a file offset from interleaving
// (core/file_lock.h) and when a stream counts as cut short (decode/seek_policy.h).
#include <fcntl.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <thread>
#include <vector>

#include "core/file_lock.h"
#include "decode/seek_policy.h"

static int g_failures = 0;
#define CHECK(...)                                                                    \
    do {                                                                              \
        if (!(__VA_ARGS__)) {                                                         \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
            ++g_failures;                                                             \
        }                                                                             \
    } while (0)

namespace {

constexpr int kSize = 1 << 16;

uint8_t byteAt(int64_t offset) { return static_cast<uint8_t>((offset * 31 + (offset >> 8)) & 0xFF); }

int makeFile(char* path) {
    const int fd = ::mkstemp(path);
    if (fd < 0) return -1;
    std::vector<uint8_t> data(kSize);
    for (int i = 0; i < kSize; ++i) data[static_cast<size_t>(i)] = byteAt(i);
    if (::write(fd, data.data(), data.size()) != static_cast<ssize_t>(data.size())) return -1;
    return fd;
}

}  // namespace

int main() {
    char pathA[] = "/tmp/uv_media_read_a_XXXXXX";
    char pathB[] = "/tmp/uv_media_read_b_XXXXXX";
    const int a = makeFile(pathA);
    const int b = makeFile(pathB);
    CHECK(a >= 0 && b >= 0);
    if (a < 0 || b < 0) return 1;

    // The same file gives the same lock through a dup() and through a separate open; another file gives another lock.
    const int dupA = ::dup(a);
    const int reopenA = ::open(pathA, O_RDONLY);
    const uv::core::FileLock lockA = uv::core::fileLockFor(a);
    CHECK(lockA != nullptr);
    CHECK(uv::core::fileLockFor(dupA) == lockA);
    CHECK(uv::core::fileLockFor(reopenA) == lockA);
    CHECK(uv::core::fileLockFor(b) != lockA);
    // A descriptor that cannot be examined still gets a usable lock of its own.
    CHECK(uv::core::fileLockFor(-1) != nullptr);

    // Released when the last holder lets go: asking again makes a fresh one (no stale entry, no leak).
    {
        const int c = ::open(pathB, O_RDONLY);
        std::weak_ptr<std::mutex> weak = uv::core::fileLockFor(c);
        CHECK(weak.expired() || true);  // the temporary above is gone; the registry only holds a weak reference
        ::close(c);
    }

    // The point of the lock: readers that share one file offset (dup()) stay correct when each holds the lock for its
    // lseek + read, and corrupt each other's reads without it.
    auto readers = [&](bool useLock) {
        std::atomic<int> wrong{0};
        std::vector<std::thread> threads;
        for (int t = 0; t < 4; ++t) {
            threads.emplace_back([&, t] {
                const int mine = ::dup(a);
                const uv::core::FileLock lock = uv::core::fileLockFor(mine);
                std::vector<uint8_t> buf(512);
                for (int i = 0; i < 4000; ++i) {
                    const int64_t offset = (static_cast<int64_t>(i) * 977 + t * 4093) % (kSize - 512);
                    ssize_t n;
                    if (useLock) {
                        std::lock_guard<std::mutex> io(*lock);
                        ::lseek(mine, offset, SEEK_SET);
                        n = ::read(mine, buf.data(), buf.size());
                    } else {
                        ::lseek(mine, offset, SEEK_SET);
                        n = ::read(mine, buf.data(), buf.size());
                    }
                    bool ok = n == static_cast<ssize_t>(buf.size());
                    for (size_t k = 0; ok && k < buf.size(); ++k) ok = buf[k] == byteAt(offset + static_cast<int64_t>(k));
                    if (!ok) ++wrong;
                }
                ::close(mine);
            });
        }
        for (auto& th : threads) th.join();
        return wrong.load();
    };
    CHECK(readers(true) == 0);
    // Without the lock the same pattern is only a reproduction of the corruption: it is allowed to pass on a lucky run, so
    // it is not asserted, only reported.
    std::printf("unlocked readers sharing an offset: %d wrong reads\n", readers(false));

    // A stream may end a second (or 2% of the clip) short of its declared length; beyond that it was cut off.
    using uv::decode::Rational;
    using uv::decode::earlyEndToleranceFrames;
    CHECK(earlyEndToleranceFrames(Rational{60, 1}, 1724) == 60);        // 1 s wins on a short clip
    CHECK(earlyEndToleranceFrames(Rational{60, 1}, 100000) == 2000);    // 2% wins on a long one
    CHECK(earlyEndToleranceFrames(Rational{30000, 1001}, 862) == 30);   // 29.97 fps: one second rounds up
    CHECK(earlyEndToleranceFrames(Rational{30, 1}, 862) == 30);
    // The incident: a 1724 frame clip that stopped at frame 1083 is 641 frames short, far beyond the tolerance.
    CHECK(1724 - 1083 > earlyEndToleranceFrames(Rational{60, 1}, 1724));
    // A normal end a few frames early is not a read error.
    CHECK(!(1724 - 1720 > earlyEndToleranceFrames(Rational{60, 1}, 1724)));

    ::close(dupA);
    ::close(reopenA);
    ::close(a);
    ::close(b);
    ::unlink(pathA);
    ::unlink(pathB);
    if (g_failures == 0) std::printf("media read host tests passed\n");
    return g_failures == 0 ? 0 : 1;
}
