# Vulkan renderer — evaluation

Status: evaluation only, no implementation. Written 2026-10.

## Where rendering is today

One GLES 3.2 compositor, `render/gl_pipeline.{h,cpp}`, driven by two clients through the same entry point
`GlPipeline::drawScene(layers, canvas, surface…)`:

- the **preview** (`render/preview_engine.cpp`): a render thread that imports decoded `AHardwareBuffer` frames as external
  textures (`EGLImage`), converts colour (SDR / HLG / PQ → project space), runs the per-layer effect chain, composites with blend
  modes and masks onto the `SurfaceView`;
- the **exporter** (`encode/export_engine.cpp`): the same `drawScene`, but into the `MediaCodec` encoder's input surface, with its
  own EGL context.

Shaders are GLSL ES 3.20 strings in `render/shaders.h`, each mirrored by a CPU reference in `render/effect_math.h` /
`render/color_math.h` that the host tests pin. EGL setup is in `render/gl_context.*`.

## What measured numbers say

From the preview-performance work on the reference device (OPPO CPH2841, Snapdragon 8 Elite class): the render thread spends
about **0.2 ms on the blit, 0.15 ms drawing and 0.5 ms on swap per frame** at 4K60, against a 16.6 ms budget. Dropped frames were
a cache-policy problem (fixed in PR #6), not a GLES-overhead problem. CPU cost of the renderer is therefore ~5% of a frame
budget; there is little left to win by lowering driver overhead, which is Vulkan's main advantage.

## What Vulkan would change

| Area | GLES today | Vulkan |
|---|---|---|
| Shaders | GLSL ES strings compiled at runtime | GLSL → SPIR-V at build time (glslang/shaderc in CMake), pipeline objects, descriptor layouts; every shader in `shaders.h` plus the LUT 3D sampler is ported |
| Frame import | `EGLImage` from `AHardwareBuffer` | `VK_ANDROID_external_memory_android_hardware_buffer` + `VkSamplerYcbcrConversion` for YUV; per-device format quirks are the usual pain |
| Sync | `eglPresentationTimeANDROID`, native fences via `AImage` | explicit `VkFence`/semaphores, which is cleaner, but `MediaCodec`/`AImageReader` fence plumbing has to be redone |
| Effect chain | ping-pong FBOs (RGBA8 / RGBA16F), one draw per effect | render passes or compute shaders; blur and LUT map well to compute, but not required |
| Output | `EGLSurface` on the `SurfaceView` / encoder surface | `VkSwapchain` on the `ANativeWindow`; the encoder-surface path (export) is the awkward one — it needs a swapchain on the codec's surface |
| HDR | 10-bit EGL config + `ANativeWindow_setBuffersDataSpace` | `VK_EXT_swapchain_colorspace` / `VK_EXT_hdr_metadata`, more explicit |
| Debugging | RenderDoc and GPU traces work | validation layers help but add a learning curve |

## Expected gains vs cost

- **Gains:** lower CPU driver overhead per draw (already small), explicit control over memory and sync, compute shaders for heavy
  effects (large blurs, 65-point LUTs on many layers, future optical-flow style features), a more predictable multi-queue story if
  the preview and export ever run concurrently.
- **Costs:** a second renderer to maintain beside GLES (GLES stays as the fallback for devices with poor Vulkan drivers, so
  not a replacement), SPIR-V build step, per-vendor driver bugs, rewriting the host-tested shaders and re-establishing preview/export
  parity, and risk on the encoder-surface path. Realistically **3–5 weeks** for parity on the reference device, longer to harden
  across devices.

## Abstraction points (what to do first if this is ever pursued)

1. Introduce a small `Compositor` interface in front of `GlPipeline` with the operations the clients actually use: `drawScene`,
   `uploadTitle`, `uploadLut`, `setOutputSpace`, `clearSourceCache`. `PreviewEngine` and the exporter already only use those.
2. Keep the scene description (`LayerDraw`, `core::LayerFx`, the wire form) renderer-neutral — it already is.
3. Keep every colour/effect formula in the CPU reference files so both backends are tested against the same vectors.
4. Make the decoder → renderer handoff an opaque `GpuFrame` handle (it is an `AHardwareBuffer` plus metadata) imported by the
   backend, instead of the pipeline reaching into decoder internals.

## Recommendation

**Do not migrate now.** The renderer is not the bottleneck, the GLES path is verified on the reference device, and a Vulkan backend
would double the surface to keep in parity for no user-visible gain. Revisit when one of these becomes true: a feature needs
compute (large-radius blur, optical flow, AI effects on the GPU), profiling shows GPU time or driver CPU time on the render thread
dominating at the target resolutions, or GLES drivers on target devices regress. Until then, the cheap preparation is point 1 above
(the `Compositor` seam), which also makes a software or test renderer possible.
