// Host tests for audio/mov_pcm: uncompressed audio in a QuickTime file ('lpcm' as iPhone writes it, 'sowt', 'twos', ...), read
// from synthetic files built in memory (a video track first, so the sound track is not the first one, like on a phone).
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "audio/mov_pcm.h"

using namespace uv;
using namespace uv::audio;
using core::Status;

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);          \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)
#define CHECK_NEAR(a, b, eps) CHECK(std::fabs(static_cast<double>(a) - static_cast<double>(b)) <= (eps))

namespace {

using Bytes = std::vector<uint8_t>;

void put32(Bytes& b, uint32_t v) {
    for (int i = 3; i >= 0; --i) b.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
void put16(Bytes& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v >> 8));
    b.push_back(static_cast<uint8_t>(v));
}
void put64(Bytes& b, uint64_t v) {
    put32(b, static_cast<uint32_t>(v >> 32));
    put32(b, static_cast<uint32_t>(v));
}
void putTag(Bytes& b, const char* t) { b.insert(b.end(), t, t + 4); }
Bytes box(const char* type, const Bytes& payload) {
    Bytes b;
    put32(b, static_cast<uint32_t>(payload.size() + 8));
    putTag(b, type);
    b.insert(b.end(), payload.begin(), payload.end());
    return b;
}
Bytes cat(std::initializer_list<Bytes> parts) {
    Bytes b;
    for (const Bytes& p : parts) b.insert(b.end(), p.begin(), p.end());
    return b;
}

struct MemReader final : RandomReader {
    explicit MemReader(Bytes d) : data(std::move(d)) {}
    uint64_t size() const override { return data.size(); }
    bool readAt(uint64_t offset, void* dst, size_t n) override {
        if (offset + n > data.size()) return false;
        std::memcpy(dst, data.data() + offset, n);
        return true;
    }
    Bytes data;
};

enum class Entry { Lpcm16, Lpcm24Float32, Sowt, Twos, Raw8 };

Bytes soundEntry(Entry kind, int channels, int rate) {
    Bytes e;
    put32(e, 0);  // size, patched below
    const char* tag = kind == Entry::Sowt ? "sowt" : kind == Entry::Twos ? "twos" : kind == Entry::Raw8 ? "raw " : "lpcm";
    putTag(e, tag);
    for (int i = 0; i < 6; ++i) e.push_back(0);
    put16(e, 1);
    if (kind == Entry::Lpcm16 || kind == Entry::Lpcm24Float32) {
        const bool isFloat = kind == Entry::Lpcm24Float32;
        const int bits = isFloat ? 32 : 16;
        put16(e, 2); put16(e, 0); put32(e, 0);
        put16(e, 3); put16(e, 16); put16(e, 0xFFFE); put16(e, 0); put32(e, 65536);
        put32(e, 72);
        const double r = rate;
        uint64_t rb;
        std::memcpy(&rb, &r, 8);
        put64(e, rb);
        put32(e, static_cast<uint32_t>(channels));
        put32(e, 0x7F000000);
        put32(e, static_cast<uint32_t>(bits));
        put32(e, isFloat ? 1u : (4u | 8u));  // float, or signed + packed; little endian
        put32(e, static_cast<uint32_t>(bits / 8 * channels));
        put32(e, 1);
    } else {
        put16(e, 0); put16(e, 0); put32(e, 0);
        put16(e, static_cast<uint16_t>(channels));
        put16(e, kind == Entry::Raw8 ? 8 : 16);
        put16(e, 0); put16(e, 0);
        put32(e, static_cast<uint32_t>(rate) << 16);
    }
    const uint32_t size = static_cast<uint32_t>(e.size());
    for (int i = 0; i < 4; ++i) e[static_cast<size_t>(i)] = static_cast<uint8_t>(size >> (8 * (3 - i)));
    return e;
}

// ftyp, mdat (chunks of frames back to back after an unrelated video payload), moov with a video trak and a sound trak.
struct Built {
    Bytes file;
    int frames = 0;
};

Built buildMov(Entry kind, int channels, int rate, const std::vector<int>& chunkFrames, const Bytes& pcm, bool perSampleTable = false,
               bool useCo64 = false) {
    int totalFrames = 0;
    for (int c : chunkFrames) totalFrames += c;
    const int bpf = static_cast<int>(pcm.size()) / totalFrames;
    Bytes ftyp = box("ftyp", cat({Bytes{'q', 't', ' ', ' '}, Bytes(4, 0), Bytes{'q', 't', ' ', ' '}}));
    Bytes videoPayload(1000, 0x55);  // pretend video samples come first in mdat
    const uint64_t mdatStart = ftyp.size() + 8;
    const uint64_t audioStart = mdatStart + videoPayload.size();
    Bytes mdat = box("mdat", cat({videoPayload, pcm}));

    // Sound track tables.
    Bytes stsd;
    put32(stsd, 0); put32(stsd, 1);
    Bytes entry = soundEntry(kind, channels, rate);
    stsd.insert(stsd.end(), entry.begin(), entry.end());
    Bytes stsz;
    put32(stsz, 0);
    if (perSampleTable) {
        put32(stsz, 0);
        put32(stsz, static_cast<uint32_t>(totalFrames));
        for (int i = 0; i < totalFrames; ++i) put32(stsz, static_cast<uint32_t>(bpf));
    } else {
        put32(stsz, static_cast<uint32_t>(bpf));
        put32(stsz, static_cast<uint32_t>(totalFrames));
    }
    Bytes stsc;  // one run per distinct chunk size: here the first chunk is its own run, the rest share the second
    put32(stsc, 0);
    const bool two = chunkFrames.size() > 1;
    put32(stsc, two ? 2 : 1);
    put32(stsc, 1); put32(stsc, static_cast<uint32_t>(chunkFrames[0])); put32(stsc, 1);
    if (two) { put32(stsc, 2); put32(stsc, static_cast<uint32_t>(chunkFrames[1])); put32(stsc, 1); }
    Bytes stco;
    put32(stco, 0);
    put32(stco, static_cast<uint32_t>(chunkFrames.size()));
    uint64_t at = audioStart;
    for (int c : chunkFrames) {
        if (useCo64) put64(stco, at); else put32(stco, static_cast<uint32_t>(at));
        at += static_cast<uint64_t>(c) * static_cast<uint64_t>(bpf);
    }
    Bytes stbl = box("stbl", cat({box("stsd", stsd), box("stsz", stsz), box("stsc", stsc), box(useCo64 ? "co64" : "stco", stco)}));
    Bytes hdlr; put32(hdlr, 0); put32(hdlr, 0); putTag(hdlr, "soun"); hdlr.insert(hdlr.end(), 12, 0);
    Bytes soundTrak = box("trak", box("mdia", cat({box("hdlr", hdlr), box("minf", stbl)})));

    Bytes vhdlr; put32(vhdlr, 0); put32(vhdlr, 0); putTag(vhdlr, "vide"); vhdlr.insert(vhdlr.end(), 12, 0);
    Bytes videoTrak = box("trak", box("mdia", cat({box("hdlr", vhdlr), box("minf", box("stbl", box("stsd", Bytes(8, 0))))})));

    Bytes moov = box("moov", cat({videoTrak, soundTrak}));
    Built b;
    b.file = cat({ftyp, mdat, moov});
    b.frames = totalFrames;
    return b;
}

Bytes s16le(const std::vector<int16_t>& v) {
    Bytes b;
    for (int16_t s : v) { b.push_back(static_cast<uint8_t>(s & 0xFF)); b.push_back(static_cast<uint8_t>((s >> 8) & 0xFF)); }
    return b;
}
Bytes s16be(const std::vector<int16_t>& v) {
    Bytes b;
    for (int16_t s : v) { b.push_back(static_cast<uint8_t>((s >> 8) & 0xFF)); b.push_back(static_cast<uint8_t>(s & 0xFF)); }
    return b;
}

std::vector<float> readAll(PcmDecoder& d, int step) {
    std::vector<float> all, buf(static_cast<size_t>(step) * 2);
    for (int guard = 0; guard < 100000; ++guard) {
        PcmReadResult r = d.read(buf.data(), step);
        CHECK(r.status == Status::Ok);
        all.insert(all.end(), buf.begin(), buf.begin() + r.frames * 2);
        if (r.eof) break;
    }
    return all;
}

void testLpcm16Stereo() {
    // 10 frames in two chunks (4 + 6); left = i * 1000, right = -i * 1000.
    std::vector<int16_t> samples;
    for (int i = 0; i < 10; ++i) { samples.push_back(static_cast<int16_t>(i * 1000)); samples.push_back(static_cast<int16_t>(-i * 1000)); }
    Built b = buildMov(Entry::Lpcm16, 2, 48000, {4, 6}, s16le(samples));
    Status st = Status::Ok;
    auto dec = openMovPcmDecoder(std::make_unique<MemReader>(b.file), &st);
    CHECK(st == Status::Ok);
    CHECK(dec != nullptr);
    if (!dec) return;
    CHECK(dec->sampleRate() == 48000);
    std::vector<float> all = readAll(*dec, 3);  // reads that straddle the chunk boundary
    CHECK(all.size() == 20);
    for (int i = 0; i < 10 && all.size() == 20; ++i) {
        CHECK_NEAR(all[static_cast<size_t>(i) * 2], i * 1000 / 32768.0, 1e-6);
        CHECK_NEAR(all[static_cast<size_t>(i) * 2 + 1], -i * 1000 / 32768.0, 1e-6);
    }
}

void testSeek() {
    std::vector<int16_t> samples;
    for (int i = 0; i < 48000; ++i) { samples.push_back(static_cast<int16_t>(i % 30000)); samples.push_back(0); }
    Built b = buildMov(Entry::Lpcm16, 2, 48000, {24000, 24000}, s16le(samples));
    Status st = Status::Ok;
    auto dec = openMovPcmDecoder(std::make_unique<MemReader>(b.file), &st);
    CHECK(dec != nullptr);
    if (!dec) return;
    CHECK(dec->seekToMicros(500000) == Status::Ok);  // frame 24000 = first frame of the second chunk
    float out[4];
    PcmReadResult r = dec->read(out, 2);
    CHECK(r.frames == 2 && !r.eof);
    CHECK_NEAR(out[0], 24000 / 32768.0, 1e-6);
    CHECK_NEAR(out[2], 24001 / 32768.0, 1e-6);
    CHECK(dec->seekToMicros(10'000'000) == Status::Ok);  // past the end: nothing left
    r = dec->read(out, 2);
    CHECK(r.frames == 0 && r.eof);
    CHECK(dec->seekToMicros(-5) == Status::Ok);  // before the start clamps to 0
    r = dec->read(out, 1);
    CHECK(r.frames == 1);
    CHECK_NEAR(out[0], 0.0, 1e-9);
    CHECK(dec->seekToMicros(1'000'000 - 21) == Status::Ok);  // 47999.0 frames after rounding: the last frame
    r = dec->read(out, 2);
    CHECK(r.frames == 1 && r.eof);
}

void testMonoAndLegacyEntries() {
    Built mono = buildMov(Entry::Sowt, 1, 44100, {5}, s16le({100, 200, 300, 400, 500}));
    Status st = Status::Ok;
    auto dec = openMovPcmDecoder(std::make_unique<MemReader>(mono.file), &st);
    CHECK(dec != nullptr);
    if (dec) {
        CHECK(dec->sampleRate() == 44100);
        std::vector<float> all = readAll(*dec, 8);
        CHECK(all.size() == 10);
        if (all.size() == 10) CHECK(all[6] == all[7] && std::fabs(all[6] - 400 / 32768.0) < 1e-6);  // mono is duplicated
    }
    Built big = buildMov(Entry::Twos, 2, 48000, {2}, s16be({16384, -16384, 8192, 0}));
    dec = openMovPcmDecoder(std::make_unique<MemReader>(big.file), &st);
    CHECK(dec != nullptr);
    if (dec) {
        std::vector<float> all = readAll(*dec, 8);
        CHECK(all.size() == 4);
        if (all.size() == 4) { CHECK_NEAR(all[0], 0.5, 1e-6); CHECK_NEAR(all[1], -0.5, 1e-6); CHECK_NEAR(all[2], 0.25, 1e-6); }
    }
    Built eight = buildMov(Entry::Raw8, 1, 8000, {3}, Bytes{128, 255, 0});
    dec = openMovPcmDecoder(std::make_unique<MemReader>(eight.file), &st);
    CHECK(dec != nullptr);
    if (dec) {
        std::vector<float> all = readAll(*dec, 8);
        CHECK(all.size() == 6);
        if (all.size() == 6) { CHECK_NEAR(all[0], 0.0, 1e-6); CHECK_NEAR(all[2], 127 / 128.0, 1e-6); CHECK_NEAR(all[4], -1.0, 1e-6); }
    }
}

void testFloatAndTables() {
    Bytes pcm;
    const float values[] = {0.5f, -0.25f, 1.0f, 0.0f};
    for (float f : values) {
        uint32_t v;
        std::memcpy(&v, &f, 4);
        for (int i = 0; i < 4; ++i) pcm.push_back(static_cast<uint8_t>(v >> (8 * i)));  // little endian float
    }
    Built f = buildMov(Entry::Lpcm24Float32, 2, 48000, {1, 1}, pcm, /*perSampleTable=*/true, /*useCo64=*/true);
    Status st = Status::Ok;
    auto dec = openMovPcmDecoder(std::make_unique<MemReader>(f.file), &st);
    CHECK(dec != nullptr);
    if (dec) {
        std::vector<float> all = readAll(*dec, 8);
        CHECK(all.size() == 4);
        if (all.size() == 4) { CHECK(all[0] == 0.5f && all[1] == -0.25f && all[2] == 1.0f && all[3] == 0.0f); }
    }
}

void testRejects() {
    // A file without a PCM sound track is not this reader's business: UnsupportedFormat, so the next decoder is tried.
    Bytes ftyp = box("ftyp", Bytes(12, 0));
    Bytes vhdlr; put32(vhdlr, 0); put32(vhdlr, 0); putTag(vhdlr, "vide"); vhdlr.insert(vhdlr.end(), 12, 0);
    Bytes videoOnly = cat({ftyp, box("mdat", Bytes(10, 1)),
                           box("moov", box("trak", box("mdia", box("hdlr", vhdlr))))});
    Status st = Status::Ok;
    CHECK(openMovPcmDecoder(std::make_unique<MemReader>(videoOnly), &st) == nullptr);
    CHECK(st == Status::UnsupportedFormat);

    // No moov at all (not a QuickTime file).
    CHECK(openMovPcmDecoder(std::make_unique<MemReader>(Bytes(64, 0x42)), &st) == nullptr);
    CHECK(st != Status::Ok);

    // A sound track with a compressed sample entry ('mp4a') is left to the platform.
    Built b = buildMov(Entry::Lpcm16, 2, 48000, {2}, s16le({1, 2, 3, 4}));
    Bytes damaged = b.file;
    for (size_t i = 0; i + 4 <= damaged.size(); ++i) {
        if (std::memcmp(&damaged[i], "lpcm", 4) == 0) std::memcpy(&damaged[i], "mp4a", 4);
    }
    CHECK(openMovPcmDecoder(std::make_unique<MemReader>(damaged), &st) == nullptr);
    CHECK(st == Status::UnsupportedFormat);

    // A file cut short keeps the frames it has: drop the last 2 bytes of mdat's tail by shrinking the reader's view.
    // (moov follows mdat here, so cutting would lose it; instead point a chunk past the end via a truncated copy of the tables.)
    Built c = buildMov(Entry::Lpcm16, 2, 48000, {2, 2}, s16le({1, 2, 3, 4, 5, 6, 7, 8}));
    Bytes cut = c.file;
    // Overwrite the second chunk offset (the last 4 bytes of stco) with an offset beyond the file.
    for (size_t i = cut.size(); i-- > 8;) {
        if (std::memcmp(&cut[i - 4], "stco", 4) == 0) {
            const size_t tableEnd = i - 4 + 4 + 4 + 4 + 4 + 4;  // type + version + count + two offsets
            const uint32_t beyond = static_cast<uint32_t>(cut.size() + 100);
            for (int k = 0; k < 4; ++k) cut[tableEnd - 4 + static_cast<size_t>(k)] = static_cast<uint8_t>(beyond >> (8 * (3 - k)));
            break;
        }
    }
    auto dec = openMovPcmDecoder(std::make_unique<MemReader>(cut), &st);
    CHECK(dec != nullptr);
    if (dec) CHECK(readAll(*dec, 16).size() == 4);  // only the first chunk (2 frames)
}

void testConvert24And32() {
    MovPcmFormat f24;
    f24.channels = 1; f24.bitsPerSample = 24; f24.bigEndian = true; f24.sampleRate = 48000;
    const uint8_t b24[] = {0x40, 0x00, 0x00, 0x80, 0x00, 0x00};  // +0.5 and -1.0
    float out[4];
    convertMovPcm(b24, 2, f24, out);
    CHECK_NEAR(out[0], 0.5, 1e-7);
    CHECK_NEAR(out[2], -1.0, 1e-7);
    MovPcmFormat f32 = f24;
    f32.bitsPerSample = 32; f32.bigEndian = false;
    const uint8_t b32[] = {0x00, 0x00, 0x00, 0x20};  // 0x20000000 / 2^31 = 0.25
    convertMovPcm(b32, 1, f32, out);
    CHECK_NEAR(out[0], 0.25, 1e-7);
}

}  // namespace

int main() {
    testLpcm16Stereo();
    testSeek();
    testMonoAndLegacyEntries();
    testFloatAndTables();
    testRejects();
    testConvert24And32();
    if (g_failures == 0) std::printf("mov_pcm host tests passed\n");
    return g_failures == 0 ? 0 : 1;
}
