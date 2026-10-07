#include "encode/smart_export.h"

#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#include "decode/log.h"
#include "encode/export_engine.h"

namespace uv::encode::smart {
namespace {

bool preadAll(int fd, uint64_t offset, void* out, size_t size) {
    size_t done = 0;
    while (done < size) {
        const ssize_t n = ::pread(fd, static_cast<uint8_t*>(out) + done, size - done, static_cast<off_t>(offset + done));
        if (n <= 0) return false;
        done += static_cast<size_t>(n);
    }
    return true;
}

// Annex B encoder sample -> length-prefixed NAL units without parameter sets and access unit delimiters (the sink adds the
// parameter sets in front of every random access point itself).
bool annexBToSample(const uint8_t* data, size_t size, std::vector<uint8_t>* out, int* firstVcl) {
    out->clear();
    *firstVcl = -1;
    const auto nals = hevc::splitAnnexB(data, size);
    if (nals.empty()) return false;
    for (const hevc::NalRef& n : nals) {
        if (n.size < 2) return false;
        const uint8_t t = hevc::nalType(n.data);
        if (t == hevc::kVps || t == hevc::kSps || t == hevc::kPps || t == 35) continue;
        if (hevc::isVcl(t) && *firstVcl < 0) *firstVcl = t;
        hevc::appendNal(out, n.data, n.size);
    }
    return !out->empty();
}

}  // namespace

const InspectedAsset* Session::asset(int64_t key) const {
    const auto it = assets_.find(key);
    return it == assets_.end() ? nullptr : &it->second;
}
int Session::fdFor(int64_t key) const {
    const auto it = fds_.find(key);
    return it == fds_.end() ? -1 : it->second;
}
uint64_t Session::sizeOf(int64_t key) const {
    const auto it = sizes_.find(key);
    return it == sizes_.end() ? 0 : it->second;
}

std::unique_ptr<Session> Session::prepare(const ExportParams& p, std::string* why) {
    auto session = std::make_unique<Session>();
    Session* s = session.get();
    for (const auto& e : p.assetFds) s->fds_[e.first] = e.second;
    OutputSpec spec;
    spec.width = p.width;
    spec.height = p.height;
    spec.fps = p.fps;
    spec.hdr = p.hdr;
    std::map<int64_t, std::string> reasons;
    const AssetLookup lookup = [&](int64_t key) -> const AssetForSmart* {
        if (const auto it = s->assets_.find(key); it != s->assets_.end()) return &it->second.plan;
        const int fd = s->fdFor(key);
        if (fd < 0) return nullptr;
        struct stat st {};
        if (::fstat(fd, &st) != 0) return nullptr;
        InspectedAsset info = inspectAsset(
            [fd](uint64_t off, void* out, size_t n) { return preadAll(fd, off, out, n); }, static_cast<uint64_t>(st.st_size), spec);
        if (!info.plan.ok) UV_LOGI("smart export: asset %lld not copyable: %s", static_cast<long long>(key), info.why.c_str());
        s->sizes_[key] = static_cast<uint64_t>(st.st_size);
        return &s->assets_.emplace(key, std::move(info)).first->second.plan;
    };
    Context ctx;
    ctx.outWidth = p.width;
    ctx.outHeight = p.height;
    ctx.canvasWidth = p.canvasWidth;
    ctx.canvasHeight = p.canvasHeight;
    ctx.outFps = p.fps;
    ctx.projectFps = p.projectFps;
    ctx.hdr = p.hdr;
    ctx.hevc = p.codec == VideoCodec::Hevc;
    ctx.totalFrames = p.totalFrames;
    s->plan_ = planSmartExport(p.clips, ctx, lookup);
    if (!s->plan_.usable) {
        if (why != nullptr) *why = s->plan_.reason;
        return nullptr;
    }
    UV_LOGI("smart export plan: %zu segments, %lld of %lld frames copied, stored rotation %d", s->plan_.segments.size(),
            static_cast<long long>(s->plan_.copiedFrames), static_cast<long long>(p.totalFrames), s->plan_.rotation);
    return session;
}

SmartSink::SmartSink(int fd, Session* session, const ExportParams& params, bool hasAudio)
    : fd_(fd), session_(session), params_(params), hasAudio_(hasAudio), expectedTracks_(hasAudio ? 2 : 1) {}

int SmartSink::addTrack(AMediaFormat* format) {
    const char* mime = nullptr;
    AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);
    const std::string m = mime != nullptr ? mime : "";
    if (m.rfind("video/", 0) == 0) {
        void* csd = nullptr;
        size_t size = 0;
        if (!AMediaFormat_getBuffer(format, "csd-0", &csd, &size) || size == 0) throw SmartFailure{"the encoder gave no parameter sets"};
        encoderSets_ = hevc::parameterSetsFromAnnexB(static_cast<const uint8_t*>(csd), size);
        if (!encoderSets_.complete()) throw SmartFailure{"the encoder's parameter sets are incomplete"};
        const auto sps = hevc::parseSps(encoderSets_.sps[0].data(), encoderSets_.sps[0].size());
        if (!sps) throw SmartFailure{"cannot read the encoder's SPS"};
        encoderHvcc_ = hevc::buildHvcc(encoderSets_, *sps);
        encoderInBand_ = hevc::inBand(encoderSets_);
        haveVideoFormat_ = true;
        return 0;
    }
    if (m == "audio/mp4a-latm") {
        void* csd = nullptr;
        size_t size = 0;
        if (!AMediaFormat_getBuffer(format, "csd-0", &csd, &size) || size == 0) throw SmartFailure{"the audio encoder gave no config"};
        audio_.audioSpecificConfig.assign(static_cast<uint8_t*>(csd), static_cast<uint8_t*>(csd) + size);
        int32_t rate = 48000, channels = 2, bitrate = 0;
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, &bitrate);
        audio_.sampleRate = rate;
        audio_.channels = channels;
        audio_.bitrate = bitrate != 0 ? bitrate : params_.audioBitrate;
        haveAudioFormat_ = true;
        return 1;
    }
    throw SmartFailure{"unexpected track type " + m};
}

void SmartSink::start() {
    if (!haveVideoFormat_ || (hasAudio_ && !haveAudioFormat_)) throw SmartFailure{"a track format is missing"};
    Mp4Writer::VideoConfig v;
    v.width = params_.width;
    v.height = params_.height;
    v.fps = params_.fps;
    v.rotationDegrees = session_->plan().rotation;
    Mp4Writer::VideoEntry enc;
    enc.hvcc = encoderHvcc_;
    // The encoder's stream is tagged like the export: BT.2020 / HLG / limited, or BT.709.
    enc.colr = params_.hdr ? std::vector<uint8_t>{'n', 'c', 'l', 'x', 0, 9, 0, 18, 0, 9, 0} : std::vector<uint8_t>{'n', 'c', 'l', 'x', 0, 1, 0, 1, 0, 1, 0};
    v.entries.push_back(enc);
    for (const CopySegment& seg : session_->plan().segments) {
        if (entryOfAsset_.count(seg.assetKey) != 0) continue;
        const InspectedAsset* a = session_->asset(seg.assetKey);
        if (a == nullptr) throw SmartFailure{"a planned source is missing"};
        int found = -1;
        for (size_t i = 0; i < sourceEntries_.size(); ++i) {
            if (sourceEntries_[i].second == a->hvcc) found = static_cast<int>(i);
        }
        if (found < 0) {
            sourceEntries_.emplace_back(seg.assetKey, a->hvcc);
            Mp4Writer::VideoEntry e;
            e.hvcc = a->hvcc;
            e.colr = a->track.colrPayload;
            v.entries.push_back(std::move(e));
            found = static_cast<int>(sourceEntries_.size()) - 1;
        }
        entryOfAsset_[seg.assetKey] = found + 1;
    }
    writer_ = std::make_unique<Mp4Writer>(fd_, std::move(v), hasAudio_ ? &audio_ : nullptr);
    if (!writer_->error().empty()) throw SmartFailure{writer_->error()};
}

void SmartSink::checkEncodedFrame(int64_t frame) {
    const auto& segs = session_->plan().segments;
    while (nextSegment_ < segs.size() && nextEncoded_ >= segs[nextSegment_].outStart) {
        nextEncoded_ = segs[nextSegment_].outStart + segs[nextSegment_].frames;
        ++nextSegment_;
    }
    if (frame != nextEncoded_) {
        throw SmartFailure{"the encoder delivered frame " + std::to_string(frame) + " where " + std::to_string(nextEncoded_) + " was due"};
    }
    ++nextEncoded_;
}

void SmartSink::writeEncoded(int track, const uint8_t* data, const AMediaCodecBufferInfo& info) {
    if (!writer_) throw SmartFailure{"data before the sink started"};
    const uint8_t* p = data + info.offset;
    const size_t size = static_cast<size_t>(info.size);
    if (track == 1) {
        if (!writer_->addAudioSample(p, size)) throw SmartFailure{writer_->error()};
        return;
    }
    // Presentation time -> frame (exact inverse of the exporter's frameToNs, whose rounding is far below half a frame).
    const i128 num = static_cast<i128>(info.presentationTimeUs) * params_.fps.num;
    const int64_t frame = static_cast<int64_t>((2 * num + static_cast<i128>(1000000) * params_.fps.den) / (static_cast<i128>(2000000) * params_.fps.den));
    if (info.presentationTimeUs <= lastEncodedPts_) throw SmartFailure{"the encoder reordered frames"};
    lastEncodedPts_ = info.presentationTimeUs;
    checkEncodedFrame(frame);
    std::vector<uint8_t> sample;
    int vcl = -1;
    if (!annexBToSample(p, size, &sample, &vcl)) throw SmartFailure{"an encoded frame is malformed"};
    const bool irap = vcl >= 0 && hevc::isIrap(static_cast<uint8_t>(vcl));
    // The first frame after a copied stretch must start a new coded video sequence.
    if (encodedFrames_ < 3 || needIrap_) UV_LOGI("smart export: encoded sample frame=%lld type=%d flags=0x%x pts=%lld needIrap=%d encoded=%lld copied=%lld", static_cast<long long>(frame), vcl, info.flags, static_cast<long long>(info.presentationTimeUs), needIrap_ ? 1 : 0, static_cast<long long>(encodedFrames_), static_cast<long long>(copiedFrames_));
    if (encodedFrames_ == 0 && !irap) throw SmartFailure{"the encoded stream does not start with an IDR picture"};
    if (irap) {
        sample.insert(sample.begin(), encoderInBand_.begin(), encoderInBand_.end());
    }
    if (needIrap_ && !irap) {
        UV_LOGW("smart export: frame %lld after a copied stretch is NAL type %d, flags 0x%x", static_cast<long long>(frame), vcl, info.flags);
        throw SmartFailure{"the encoder did not start a new sequence after a copied stretch"};
    }
    if (needIrap_) UV_LOGI("smart export: frame %lld after a copied stretch starts with NAL type %d, flags 0x%x", static_cast<long long>(frame), vcl, info.flags);
    needIrap_ = false;
    if (!writer_->addVideoSample(sample.data(), sample.size(), 0, frame, irap)) throw SmartFailure{writer_->error()};
    ++encodedFrames_;
}

void SmartSink::copySegment(const CopySegment& seg) {
    if (!writer_) throw SmartFailure{"a copy before the sink started"};
    const InspectedAsset* a = session_->asset(seg.assetKey);
    const int fd = session_->fdFor(seg.assetKey);
    if (a == nullptr || fd < 0) throw SmartFailure{"a planned source is missing"};
    const auto it = entryOfAsset_.find(seg.assetKey);
    if (it == entryOfAsset_.end()) throw SmartFailure{"no sample entry for a copied source"};
    const CopyRun& run = seg.run;
    const std::vector<GopSample>& gop = a->plan.samples;
    int64_t written = 0;
    std::vector<uint8_t> sample;
    for (size_t k = run.firstSample; k < run.endSample; ++k) {
        const bool first = k == run.firstSample;
        if (!first && gop[k].pres < run.presStart) continue;  // leading picture of the first access point: dropped
        const Mp4Sample& m = a->track.samples[k];
        if (m.size == 0 || m.size > (64u << 20)) throw SmartFailure{"a source sample has an implausible size"};
        sample.resize(m.size);
        if (!preadAll(fd, m.offset, sample.data(), m.size)) throw SmartFailure{"reading a source sample failed"};
        if (!hevc::dropNals(&sample, 4, hevc::kDolbyRpu)) throw SmartFailure{"a source sample is malformed"};
        if (first) {
            const int type = hevc::firstVclType(sample.data(), sample.size(), 4);
            if (!hevc::isIrap(static_cast<uint8_t>(std::max(type, 0))) || type < 0) throw SmartFailure{"a copied stretch does not start at a random access point"};
            if (hevc::isCra(static_cast<uint8_t>(type)) && !hevc::craToBla(&sample, 4)) throw SmartFailure{"cannot convert a CRA picture"};
            const std::vector<uint8_t> sets = hevc::inBand(a->sets);
            sample.insert(sample.begin(), sets.begin(), sets.end());
        }
        const int64_t pres = seg.outStart + (gop[k].pres - run.presStart);
        if (!writer_->addVideoSample(sample.data(), sample.size(), it->second, pres, first)) throw SmartFailure{writer_->error()};
        ++written;
    }
    if (written != seg.frames) throw SmartFailure{"a copied stretch has " + std::to_string(written) + " frames, expected " + std::to_string(seg.frames)};
    copiedFrames_ += written;
    needIrap_ = true;
}

void SmartSink::finish() {
    if (!writer_) throw SmartFailure{"nothing was written"};
    if (encodedFrames_ + copiedFrames_ != params_.totalFrames) {
        throw SmartFailure{"the file has " + std::to_string(encodedFrames_ + copiedFrames_) + " frames, expected " + std::to_string(params_.totalFrames)};
    }
    if (!writer_->finish()) throw SmartFailure{writer_->error()};
    std::string why;
    if (!writer_->verify(150, &why)) throw SmartFailure{"the finished file failed its check: " + why};
}

}  // namespace uv::encode::smart
