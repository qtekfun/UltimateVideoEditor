#include "stabilise/stab_registry.h"

#include <algorithm>

#include "stabilise/stab_cache.h"

namespace uv::stab {

StabRegistry& StabRegistry::instance() {
    static StabRegistry registry;
    return registry;
}

void StabRegistry::put(uint32_t key, StabTable table) {
    auto shared = std::make_shared<const StabTable>(std::move(table));
    {
        std::lock_guard<std::mutex> lock(mu_);
        tables_[key] = std::move(shared);
    }
    revision_.fetch_add(1);
}

void StabRegistry::remove(uint32_t key) {
    bool removed;
    {
        std::lock_guard<std::mutex> lock(mu_);
        removed = tables_.erase(key) != 0;
    }
    if (removed) revision_.fetch_add(1);
}

void StabRegistry::clear() {
    {
        std::lock_guard<std::mutex> lock(mu_);
        tables_.clear();
    }
    revision_.fetch_add(1);
}

bool StabRegistry::has(uint32_t key) const {
    std::lock_guard<std::mutex> lock(mu_);
    return tables_.count(key) != 0;
}

bool StabRegistry::lookup(uint32_t key, int64_t frame, float out[4]) const {
    std::shared_ptr<const StabTable> table;
    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = tables_.find(key);
        if (it == tables_.end()) return false;
        table = it->second;
    }
    return table->lookup(frame, out);
}

core::Status registerFromCache(uint32_t key, const std::string& path, decode::Rational fps, float strength, CropLevel crop) {
    if (key == 0 || fps.num <= 0 || fps.den <= 0) return core::Status::InvalidArgument;
    CacheHeader header;
    std::vector<MotionSample> samples;
    if (core::Status s = readCache(path, &header, &samples); s != core::Status::Ok) return s;
    if (samples.size() < 2) return core::Status::UnsupportedFormat;
    StabTable table = buildTable(samples, fps, static_cast<double>(header.aspect), strength, crop);
    if (table.frames() == 0) return core::Status::UnsupportedFormat;
    StabRegistry::instance().put(key, std::move(table));
    return core::Status::Ok;
}

void resolveStabilisation(core::LayerFx* fx, int64_t sourceFrame) {
    auto& effects = fx->effects;
    for (size_t i = 0; i < effects.size();) {
        core::EffectOp& op = effects[i];
        if (op.type != core::EffectType::Stabilise) {
            ++i;
            continue;
        }
        float correction[4];
        if (!StabRegistry::instance().lookup(static_cast<uint32_t>(op.v[0]), sourceFrame, correction)) {
            effects.erase(effects.begin() + static_cast<std::ptrdiff_t>(i));
            continue;
        }
        for (int k = 0; k < 4; ++k) op.v[2 + k] = correction[k];
        ++i;
    }
}

}  // namespace uv::stab
