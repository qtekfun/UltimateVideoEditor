#pragma once

// Process-wide store of stabilisation tables, keyed by the 24-bit key the Kotlin side derives from the
// clip's stabilise settings (domain/Stabilise.kt `StabKey`). The preview and the exporter look a layer's
// correction up here by the source frame it shows, so both draw the same picture and neither needs the
// scene description to carry the table.

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include "core/error.h"
#include "core/layer_fx.h"
#include "stabilise/path.h"

namespace uv::stab {

class StabRegistry {
public:
    static StabRegistry& instance();

    // Replaces any table under `key`. Bumps revision() so the preview redraws.
    void put(uint32_t key, StabTable table);
    void remove(uint32_t key);
    void clear();
    bool has(uint32_t key) const;
    // Correction (dx, dy, theta, scale) of `key` at the decoder frame `frame`; false when the key is unknown.
    bool lookup(uint32_t key, int64_t frame, float out[4]) const;
    // Changes whenever a table is added, replaced or removed.
    uint64_t revision() const { return revision_.load(); }

private:
    mutable std::mutex mu_;
    std::unordered_map<uint32_t, std::shared_ptr<const StabTable>> tables_;
    std::atomic<uint64_t> revision_{0};
};

// Reads the analysis cache at `path`, smooths it for `strength` (0..1) and `crop`, and stores the table
// under `key`. `fps` is the frame rate the decoder numbers frames with.
core::Status registerFromCache(uint32_t key, const std::string& path, decode::Rational fps, float strength, CropLevel crop);

// Fills the per-frame values (v[2..5] = dx, dy, theta, scale) of every stabilise effect in `fx` for the
// source frame `sourceFrame`. An effect whose table is not registered is removed, so the layer is drawn
// as it is.
void resolveStabilisation(core::LayerFx* fx, int64_t sourceFrame);

}  // namespace uv::stab
