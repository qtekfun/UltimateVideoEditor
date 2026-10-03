#include "render/preview_engine.h"

#include <unistd.h>

#include <algorithm>
#include <future>
#include <vector>

#include "decode/log.h"
#include "render/layout_math.h"

namespace uv::render {

using decode::AssetInfo;
using decode::Error;
using decode::GpuFrame;
using decode::Result;
using decode::Status;
using decode::VideoDecoder;
using Clock = std::chrono::steady_clock;

namespace {

constexpr int32_t kDefaultLookBehind = 30;
constexpr int32_t kDefaultLookAhead = 60;
constexpr size_t kPoolSize = 6;
// Ticks fire this long before a frame is due so it is drawn and queued before the vsync that shows it.
constexpr auto kPresentLead = std::chrono::milliseconds(4);

}  // namespace

// ---------------------------------------------------------------- RenderThread

RenderThread::RenderThread() : thread_([this] { run(); }) {}

RenderThread::~RenderThread() {
    {
        std::lock_guard<std::mutex> lock(mu_);
        stop_ = true;
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void RenderThread::post(Task task) { postAt(std::move(task), Clock::now()); }

void RenderThread::postAt(Task task, Clock::time_point when) {
    {
        std::lock_guard<std::mutex> lock(mu_);
        tasks_.emplace(when, std::move(task));
    }
    cv_.notify_all();
}

void RenderThread::postAndWait(Task task) {
    auto done = std::make_shared<std::promise<void>>();
    std::future<void> future = done->get_future();
    post([task = std::move(task), done] {
        task();
        done->set_value();
    });
    future.wait();
}

void RenderThread::run() {
    for (;;) {
        Task task;
        {
            std::unique_lock<std::mutex> lock(mu_);
            for (;;) {
                if (stop_) return;
                if (tasks_.empty()) {
                    cv_.wait(lock);
                    continue;
                }
                const auto when = tasks_.begin()->first;
                if (when <= Clock::now()) break;
                cv_.wait_until(lock, when);
            }
            task = std::move(tasks_.begin()->second);
            tasks_.erase(tasks_.begin());
        }
        task();
    }
}

// ---------------------------------------------------------------- PreviewEngine

PreviewEngine::PreviewEngine(size_t cacheBudgetBytes, ErrorSink sink)
    : sink_(std::move(sink)), cache_(cacheBudgetBytes), thread_(std::make_unique<RenderThread>()) {}

Result<std::unique_ptr<PreviewEngine>> PreviewEngine::create(size_t cacheBudgetBytes, ErrorSink sink) {
    std::unique_ptr<PreviewEngine> engine(new PreviewEngine(cacheBudgetBytes, std::move(sink)));
    Error error{Status::Ok, ""};
    engine->thread_->postAndWait([&engine, &error] {
        engine->egl_ = std::make_unique<EglContext>();
        Error e{Status::Ok, ""};
        if (engine->egl_->init(&e) != Status::Ok) {
            error = e;
            return;
        }
        engine->pipeline_ = std::make_unique<GlPipeline>(*engine->egl_);
        if (engine->pipeline_->init(&e) != Status::Ok) error = e;
    });
    if (error.code != Status::Ok) return error;
    return engine;
}

PreviewEngine::~PreviewEngine() {
    std::map<uint32_t, Asset> assets;
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        assets.swap(assets_);
    }
    // Decoders stop first while the render thread is still alive to drain their image readers.
    for (auto& entry : assets) entry.second.decoder->shutdown();
    assets.clear();
    thread_->postAndWait([this] {
        playing_ = false;
        cache_.clear();
        pipeline_.reset();
        egl_.reset();
    });
    thread_.reset();
}

void PreviewEngine::report(const Error& error) {
    UV_LOGE("preview error %d: %s", static_cast<int>(error.code), error.message.c_str());
    if (sink_) sink_(error);
}

Status PreviewEngine::attachSurface(ANativeWindow* window, Error* error) {
    Status result = Status::Ok;
    thread_->postAndWait([&] {
        Error e{Status::Ok, ""};
        result = egl_->attachWindow(window, &e);
        if (result == Status::Ok) result = egl_->makeCurrentWindow(&e);
        if (result != Status::Ok && error != nullptr) *error = e;
        if (result == Status::Ok) {
            pipeline_->clear(egl_->windowWidth(), egl_->windowHeight());
            egl_->swap(&e);
            maybeDraw(true);
        }
    });
    return result;
}

void PreviewEngine::detachSurface() {
    thread_->postAndWait([this] { egl_->detachWindow(); });
}

void PreviewEngine::surfaceChanged() {
    // The window surface adopts its new size at the next swap and a layout pass can resize it
    // several times, so redraw now and again shortly after; maybeDraw() also redraws by itself
    // whenever a swap changed the surface size.
    const auto redraw = [this] {
        if (egl_->hasWindow()) maybeDraw(true);
    };
    const auto now = Clock::now();
    thread_->postAt(redraw, now);
    thread_->postAt(redraw, now + std::chrono::milliseconds(40));
    thread_->postAt(redraw, now + std::chrono::milliseconds(120));
}

std::shared_ptr<VideoDecoder> PreviewEngine::decoderFor(uint32_t assetId) {
    std::lock_guard<std::mutex> lock(assetMu_);
    auto it = assets_.find(assetId);
    return it == assets_.end() ? nullptr : it->second.decoder;
}

Result<AssetInfo> PreviewEngine::openAsset(uint32_t assetId, int fd, decode::Rational fpsOverride) {
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        if (assets_.count(assetId) != 0) {
            close(fd);
            return Error{Status::InvalidState, "asset " + std::to_string(assetId) + " is already open"};
        }
    }
    VideoDecoder::Callbacks callbacks;
    callbacks.onImageAvailable = [this, assetId] { thread_->post([this, assetId] { drain(assetId); }); };
    callbacks.isCached = [this, assetId](int64_t frame) { return cache_.contains(FrameKey{assetId, frame}); };
    callbacks.onError = [this](const Error& e) { report(e); };

    auto opened = VideoDecoder::open(fd, fpsOverride, std::move(callbacks));
    if (!opened.ok()) return opened.error();
    std::shared_ptr<VideoDecoder> decoder(std::move(opened.value()));
    const AssetInfo info = decoder->info();
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        assets_[assetId] = Asset{decoder, info.colorTransfer == 7 /* HLG */ ? ColorMode::Hlg2020ToSdr709 : ColorMode::Sdr709,
                                 quarterTurns(info.rotationDegrees)};
    }
    applyWindowForBudget();
    return info;
}

void PreviewEngine::closeAsset(uint32_t assetId) {
    std::shared_ptr<VideoDecoder> decoder;
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        auto it = assets_.find(assetId);
        if (it == assets_.end()) return;
        decoder = it->second.decoder;
        assets_.erase(it);
    }
    decoder->shutdown();  // render thread keeps draining until the codec is quiet
    thread_->post([this, assetId] {
        cache_.eraseIf([assetId](const FrameKey& k) { return k.asset == assetId; });
        if (pipeline_) pipeline_->clearSourceCache();  // the reader's buffers are gone with the decoder
        if (hasCurrent_ && curAsset_ == assetId) {
            hasCurrent_ = false;
            playing_ = false;
            drawnValid_ = false;
        }
    });
}

void PreviewEngine::applyWindowForBudget() {
    // The window must fit the cache or the decoder would evict what it just produced and loop.
    std::vector<std::shared_ptr<VideoDecoder>> decoders;
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        for (auto& entry : assets_) decoders.push_back(entry.second.decoder);
    }
    const size_t budget = cache_.budgetBytes();
    for (auto& d : decoders) {
        const size_t frameBytes = std::max<size_t>(1, static_cast<size_t>(d->info().width) * d->info().height * 4);
        const int64_t capacity = static_cast<int64_t>(budget / frameBytes);
        const int32_t ahead = static_cast<int32_t>(std::clamp<int64_t>(capacity * 2 / 3, 1, kDefaultLookAhead));
        const int32_t behind = static_cast<int32_t>(std::clamp<int64_t>(capacity / 4, 0, kDefaultLookBehind));
        windowBehind_.store(behind);
        windowAhead_.store(ahead);
        d->setWindow(behind, ahead);
    }
}

void PreviewEngine::setCacheBudget(size_t bytes) {
    cache_.setBudget(bytes);  // the cache is internally synchronised; evicted frames drop here
    applyWindowForBudget();
}

void PreviewEngine::setColorMode(uint32_t assetId, ColorMode mode) {
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        auto it = assets_.find(assetId);
        if (it == assets_.end()) return;
        it->second.mode = mode;
    }
    thread_->post([this] { maybeDraw(true); });
}

void PreviewEngine::setCurrent(uint32_t assetId, int64_t frame) {
    auto decoder = decoderFor(assetId);
    if (!decoder) {
        report(Error{Status::NotFound, "asset " + std::to_string(assetId) + " is not open"});
        return;
    }
    const int64_t last = std::max<int64_t>(decoder->info().durationFrames - 1, 0);
    frame = std::clamp<int64_t>(frame, 0, last);
    hasCurrent_ = true;
    curAsset_ = assetId;
    curFrame_ = frame;
    decoder->setTarget(frame);
}

void PreviewEngine::seek(uint32_t assetId, int64_t frame) {
    thread_->post([this, assetId, frame] {
        setCurrent(assetId, frame);
        if (playing_) {  // keep playing from the new position
            playStart_ = Clock::now();
            playStartFrame_ = curFrame_;
        }
        maybeDraw(false);
    });
}

void PreviewEngine::play(uint32_t assetId, int64_t startFrame) {
    thread_->post([this, assetId, startFrame] {
        setCurrent(assetId, startFrame);
        if (!hasCurrent_) return;
        playing_ = true;
        playStart_ = Clock::now();
        playStartFrame_ = curFrame_;
        ++playGeneration_;
        tick(playGeneration_);
    });
}

void PreviewEngine::pause() {
    thread_->post([this] {
        playing_ = false;
        ++playGeneration_;
    });
}

void PreviewEngine::tick(uint64_t generation) {
    if (!playing_ || generation != playGeneration_) return;
    auto decoder = decoderFor(curAsset_);
    if (!decoder) {
        playing_ = false;
        return;
    }
    const decode::Rational fps = decoder->info().fps;
    const int64_t elapsedNs =
        std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() + kPresentLead - playStart_).count();
    const int64_t advanced = static_cast<int64_t>(static_cast<__int128>(elapsedNs) * fps.num /
                                                  (static_cast<__int128>(fps.den) * 1000000000));
    const int64_t last = std::max<int64_t>(decoder->info().durationFrames - 1, 0);
    int64_t frame = playStartFrame_ + advanced;
    if (frame >= last) {
        frame = last;
        playing_ = false;
    }
    if (frame != curFrame_) {
        curFrame_ = frame;
        decoder->setTarget(frame);
    }
    const auto frameDueNs = [&](int64_t framesFromStart) {
        return static_cast<int64_t>(static_cast<__int128>(framesFromStart) * fps.den * 1000000000 / fps.num);
    };
    const auto due = playStart_ + std::chrono::nanoseconds(frameDueNs(frame - playStartFrame_));
    maybeDraw(false, std::chrono::duration_cast<std::chrono::nanoseconds>(due.time_since_epoch()).count());
    if (playing_) {
        thread_->postAt([this, generation] { tick(generation); },
                        playStart_ + std::chrono::nanoseconds(frameDueNs(advanced + 1)) - kPresentLead);
    }
}

void PreviewEngine::drain(uint32_t assetId) {
    auto decoder = decoderFor(assetId);
    if (!decoder) return;
    const AssetInfo info = decoder->info();
    decoder->drainImages([&](int64_t frame, AHardwareBuffer* buffer) -> int {
        int releaseFence = -1;
        const FrameKey key{assetId, frame};
        if (!cache_.contains(key)) {
            std::shared_ptr<GpuFrame> gpuFrame;
            for (auto it = pool_.begin(); it != pool_.end(); ++it) {
                if ((*it)->width() == static_cast<uint32_t>(info.width) &&
                    (*it)->height() == static_cast<uint32_t>(info.height)) {
                    gpuFrame = std::move(*it);
                    pool_.erase(it);
                    break;
                }
            }
            if (!gpuFrame) {
                auto allocated = decode::allocateGpuFrame(static_cast<uint32_t>(info.width),
                                                          static_cast<uint32_t>(info.height));
                if (!allocated.ok()) {
                    report(allocated.error());
                    decoder->markResolved(frame);
                    return -1;
                }
                gpuFrame = allocated.value();
            }
            Error error{Status::Ok, ""};
            const auto blitStart = Clock::now();
            const Status blitStatus = pipeline_->blitToFrame(buffer, *gpuFrame, &releaseFence, &error);
            times_.blitNs += std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - blitStart).count();
            ++times_.blits;
            if (blitStatus != Status::Ok) {
                report(error);
                decoder->markResolved(frame);
                return -1;
            }
            const size_t bytes = gpuFrame->bytes();
            // Evict outside the playback window first; see LruCache::put.
            const int64_t behind = windowBehind_.load();
            const int64_t ahead = windowAhead_.load();
            const auto inWindow = [&](const FrameKey& k) {
                return hasCurrent_ && k.asset == curAsset_ && k.frame >= curFrame_ - behind && k.frame <= curFrame_ + ahead;
            };
            for (auto& old : cache_.put(key, std::move(gpuFrame), bytes, inWindow)) {
                if (old.use_count() == 1 && pool_.size() < kPoolSize) pool_.push_back(std::move(old));
            }
        }
        decoder->markResolved(frame);
        return releaseFence;
    });
    maybeDraw(false);
}

void PreviewEngine::maybeDraw(bool force, int64_t presentNs) {
    if (!egl_ || !egl_->hasWindow() || !hasCurrent_) return;
    const FrameKey key{curAsset_, curFrame_};
    if (!force && drawnValid_ && drawnKey_ == key) return;
    std::shared_ptr<GpuFrame> frame;
    if (!cache_.get(key, &frame)) {
        if (playing_) stalls_.fetch_add(1);
        return;
    }
    ColorMode mode = ColorMode::Sdr709;
    int turns = 0;
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        auto it = assets_.find(curAsset_);
        if (it != assets_.end()) {
            mode = it->second.mode;
            turns = it->second.turns;
        }
    }
    Error error{Status::Ok, ""};
    const auto drawStart = Clock::now();
    const int drawW = egl_->windowWidth();
    const int drawH = egl_->windowHeight();
    const Status drawStatus = pipeline_->draw(*frame, mode, turns, drawW, drawH, &error);
    const auto swapStart = Clock::now();
    if (drawStatus == Status::Ok) egl_->setPresentationTime(presentNs);
    const Status swapStatus = drawStatus == Status::Ok ? egl_->swap(&error) : drawStatus;
    const auto drawEnd = Clock::now();
    times_.drawNs += std::chrono::duration_cast<std::chrono::nanoseconds>(swapStart - drawStart).count();
    times_.swapNs += std::chrono::duration_cast<std::chrono::nanoseconds>(drawEnd - swapStart).count();
    ++times_.draws;
    if (swapStatus != Status::Ok) {
        report(error);
        return;
    }
    if (playing_) logStageTimes();
    // Swapping dequeues the next buffer, which is where a resize shows up: draw again at that size.
    if (egl_->windowWidth() != drawW || egl_->windowHeight() != drawH) {
        thread_->post([this] {
            if (egl_->hasWindow()) maybeDraw(true);
        });
    }
    drawnKey_ = key;
    drawnValid_ = true;
    framesDrawn_.fetch_add(1);
}

void PreviewEngine::logStageTimes() {
    const auto now = Clock::now();
    if (times_.lastLog.time_since_epoch().count() == 0) times_.lastLog = now;
    if (now - times_.lastLog < std::chrono::seconds(1)) return;
    const auto ms = [](int64_t ns, int64_t n) { return n > 0 ? static_cast<double>(ns) / 1e6 / static_cast<double>(n) : 0.0; };
    UV_LOGI("stage ms/frame: blit %.2f (%lld) draw %.2f swap %.2f (%lld draws) stalls %lld", ms(times_.blitNs, times_.blits),
            static_cast<long long>(times_.blits), ms(times_.drawNs, times_.draws), ms(times_.swapNs, times_.draws),
            static_cast<long long>(times_.draws), static_cast<long long>(stalls_.load()));
    times_ = StageTimes{};
    times_.lastLog = now;
}

PreviewStats PreviewEngine::stats() const {
    int64_t decoded = 0;
    {
        std::lock_guard<std::mutex> lock(assetMu_);
        for (const auto& entry : assets_) decoded += entry.second.decoder->framesDecoded();
    }
    return PreviewStats{static_cast<int64_t>(cache_.usedBytes()), static_cast<int64_t>(cache_.budgetBytes()),
                        static_cast<int64_t>(cache_.size()), framesDrawn_.load(), stalls_.load(), decoded};
}

}  // namespace uv::render
