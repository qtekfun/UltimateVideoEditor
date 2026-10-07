#pragma once

// Smart export runtime (SPECS.md 5.10, DECISIONS.md "Smart export"): the part that touches files and the encoder. The planner
// (smart_plan.h) says which stretches are copied; the sink writes the MP4 with the copied samples and the encoder's samples in
// decode order. Any doubt raises SmartFailure and the export is redone normally.

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "encode/hevc_nal.h"
#include "encode/mp4_writer.h"
#include "encode/smart_plan.h"
#include "encode/smart_source.h"

namespace uv::encode {

struct ExportParams;

namespace smart {

// Thrown for every reason to give up on copying: a source that does not read, an encoder that does not behave, a file that
// fails the check. The caller deletes the output and exports the whole movie normally.
struct SmartFailure {
    std::string message;
};

// Inspects the source files the clips use (lazily, once each), plans the copy and keeps the file facts for the sink.
class Session {
public:
    // Returns null with `why` when the movie has nothing worth copying or the settings rule it out.
    static std::unique_ptr<Session> prepare(const ExportParams& params, std::string* why);

    const Plan& plan() const { return plan_; }
    const InspectedAsset* asset(int64_t key) const;
    int fdFor(int64_t key) const;
    uint64_t sizeOf(int64_t key) const;

private:
    Plan plan_;
    std::map<int64_t, InspectedAsset> assets_;
    std::map<int64_t, int> fds_;
    std::map<int64_t, uint64_t> sizes_;
};

// Receives the encoder's samples and the copied ones. Track 0 is the video, track 1 the audio (when there is any).
class SmartSink {
public:
    SmartSink(int fd, Session* session, const ExportParams& params, bool hasAudio);

    int addTrack(AMediaFormat* format);  // returns the track index; videos are track 0
    void start();                         // all tracks known: builds the sample entries and the writer
    void writeEncoded(int track, const uint8_t* data, const AMediaCodecBufferInfo& info);
    // Writes the samples of `segment` straight from its source file.
    void copySegment(const CopySegment& segment);
    // Frames the encoder has delivered so far (to know that it caught up before a copied stretch is written).
    int64_t encodedFrames() const { return encodedFrames_; }
    // Both encoders have delivered their format: samples can be written.
    bool started() const { return writer_ != nullptr; }
    // Writes the moov box and checks the file; throws SmartFailure when anything is off.
    void finish();

private:
    int entryFor(int64_t assetKey);
    void checkEncodedFrame(int64_t frame);

    int fd_;
    Session* session_;
    const ExportParams& params_;
    bool hasAudio_;
    int expectedTracks_;
    std::unique_ptr<Mp4Writer> writer_;
    hevc::ParameterSets encoderSets_;
    std::vector<uint8_t> encoderInBand_;
    std::vector<uint8_t> encoderHvcc_;
    Mp4Writer::AudioConfig audio_;
    bool haveVideoFormat_ = false;
    bool haveAudioFormat_ = false;
    std::map<int64_t, int> entryOfAsset_;
    std::vector<std::pair<int64_t, std::vector<uint8_t>>> sourceEntries_;  // asset key -> hvcC, entry index = 1 + position
    int64_t encodedFrames_ = 0;
    int64_t copiedFrames_ = 0;
    int64_t nextEncoded_ = 0;   // the output frame the next encoded sample must show
    size_t nextSegment_ = 0;
    int64_t lastEncodedPts_ = -1;
    bool needIrap_ = false;     // a copied stretch was just written: the next encoded frame must be an IDR
};

}  // namespace smart
}  // namespace uv::encode
