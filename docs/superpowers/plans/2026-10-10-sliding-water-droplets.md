# Sliding Water Droplets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task, subject to the executing harness's instructions. Steps use checkbox (`- [ ]`) syntax for tracking. The tasks depend on one another; do not implement them concurrently.

**Goal:** Add foreground glass-like droplets that bead and slide down the screen during rain without replacing the existing falling rain trails.

**Architecture:** A small Android-free motion model samples a bounded, deterministic population from the shared animation clock; a dedicated Canvas layer draws translucent beads and highlights with reusable drawing objects. Extend the existing screen-space label pass to include droplets, keeping both effects outside live-wallpaper parallax and below interface text. Derive eligibility and density from existing `SceneParams.precipitation` and `precipitationScale`, so preview, simulator, live weather, and fallback use the same inputs without another preference.

**Tech Stack:** Kotlin, Android Canvas/Paint/Path, Jetpack Compose preview, WallpaperService, JUnit 4 unit tests, AndroidJUnit4 bitmap instrumentation tests, Gradle.

**Spec:** [Weatherd issue #87](https://github.com/Attacktive/weatherd/issues/87), whose complete requirement is: “Separate from the existing realistic falling rain trails: add foreground glass-like droplets that bead and slide down the screen during rain.” There are no issue comments at planning time. The decisions below fill in unspecified behavior; they are not additional requirements quoted from the issue.

## Global Constraints

- Read the current root `AGENTS.md`, global operating rules, and `STYLE.md` before implementation; re-fetch the repository before relying on the source map below.
- Planning baseline: `a2051f4b3b55555584203fe5b7aea1d738adec6e` on `main`. Line references are navigation aids, not patch anchors.
- Preserve `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`, and the existing JDK 17 build. Add no dependencies, permissions, network work, sensors, preferences, or resource downloads.
- `INTENSITY_SCALE_RANGE = 0.1f..2f` remains unchanged. Rain intensity affects droplet population, not the motion phase of existing droplets.
- Preserve existing rain/sleet/snow rendering, cloud/fog/lightning behavior, backdrop caching, simulator preset indices, and frame-rate caps.
- Use procedural first-party geometry, not reference APK or third-party artwork. No actual scene refraction, framebuffer readback, full-screen bitmap capture, or new GPU pipeline is required to achieve the glass-like appearance.
- New steady-state drawing must allocate no bitmaps, shaders, paths, paints, arrays, collections, RNGs, or per-droplet objects. Reuse a bounded population and scratch objects; do not use per-frame blur or `saveLayer` for the effect.
- This plan introduces no independent render setting: existing precipitation inputs already travel through `sceneParamsFor`, `debugSceneParams`, and `WeatherSceneProvider`. Leave those contracts unchanged rather than creating preview-only state. If scope changes to include a new setting, wire every path and the required provider/preset tests before delivery.
- Before Gradle commands, source `"$HOME/.sdkman/bin/sdkman-init.sh"`. Never run `connectedDebugAndroidTest` against an installation whose data must survive; it removes Weatherd during teardown in the current setup.
- Do not merge, release, bump the version, or add GitHub Actions workflows as part of implementation.

## Review Focus

1. Launcher scrolling: glass remains attached to the visible screen, not the wider weather scene; cover visible-width renderer reuse in Task 3 and verify actual parallax with the wallpaper smoke run.
2. Frame skipping, visibility resume, and the six-hour animation-clock wrap: motion remains finite and reproducible without accumulated simulation steps; cover in Task 1.
3. Rain ending or changing to snow/sleet, and lowering intensity: no stale droplets, ghost trails, or repositioned surviving beads; cover in Tasks 1–3.
4. Portrait/landscape, preview/wallpaper size differences, and renderer reuse: round beads keep their proportions and no previous viewport or material state leaks; cover in Tasks 1–2.
5. Day/night, photo backdrops, and overlay labels: bodies remain translucent and labels render above droplets; cover in Tasks 2–3 and visually on both real surfaces.

---

## Inspected Source Map

Paths below are relative to the repository root; `render/` abbreviates `app/src/main/java/xyz/attacktive/weatherd/domain/render/` only in this source map.

| Existing location | Relevant behavior |
| --- | --- |
| `render/SceneRenderer.kt:230–318` | `renderForeground(..., includeOverlayLabels: Boolean = true)` draws precipitation, then lightning, then labels. `renderOverlayLabels(...)` is the separate screen-space entry point. |
| `render/SceneRenderer.kt:2003–2148` | `drawRain` draws far, near, and close falling streaks. Despite its name, `drawCloseDrops` is another fast `drawLines` trail layer, not glass beads; leave it intact. |
| `render/SceneRenderer.kt:3705–3711` | `precipitationDropCount(precipitation, scale, width, height): Int` applies the observation visibility floor and square-root intensity shaping; reuse it for density. |
| `render/SceneAnimationClock.kt:3–10` | The shared monotonic phase wraps at six hours; no renderer-local wall clock is necessary. |
| `service/WeatherLiveWallpaperService.kt:215–225` under `app/src/main/java/xyz/attacktive/weatherd/` | `drawViewport` translates the world, calls foreground with labels disabled, restores the canvas, and draws labels at visible `width`. This is the glass-effect boundary. |
| `ui/home/HomeScreen.kt:169–196` under the same package root | Preview caches its backdrop and calls the shared foreground renderer at visible dimensions. Its default screen-effect path must remain sufficient. |
| `render/SceneDebugPresets.kt:39–45` | Existing `DRIZZLE`, `RAIN`, `HEAVY RAIN`, and `THUNDERSTORM` presets already supply rain; `SLEET`/`SNOW` are negative controls. |
| `app/src/androidTest/java/xyz/attacktive/weatherd/domain/render/LightningRenderingTest.kt` | Existing deterministic bitmap comparisons across reused renderers and changed dimensions. Follow this style without testing private cache fields. |
| `app/src/androidTest/java/xyz/attacktive/weatherd/domain/render/SceneRendererStateTest.kt` | Existing foreground-to-backdrop paint-state regression patterns. |

The planning environment reported “No language servers configured for this project.” At execution time, use LSP references if available before changing exported renderer signatures; otherwise enumerate literal references. The inspected production callers are `SceneRenderer.render`, `HomeScreen`, and `WeatherLiveWallpaperService`; instrumentation tests also call `renderForeground`.

## Selected Visual and Motion Design

These are implementation decisions, with visual tuning values explicitly provisional:

- Rain only, including drizzle and thunderstorms. Dry weather, fog/humidity without rain, snow, and sleet draw no glass droplets. Disable immediately when rain ends; do not invent drying persistence.
- A varied population combines newly forming beads, stationary rounded beads, and slower sliding elongated beads. Gravity dominates; do not feed falling-rain slant, wind scale, or device motion into glass motion.
- Bodies have low-opacity neutral shading, a restrained dark lower rim, and a small bright upper highlight. Most of the background remains visible through each body. Sliding droplets may stretch modestly, but must not become long falling streaks, opaque white dots, or glowing rods.
- Size uses the visible viewport's shorter edge, not raw density-independent pixels or the virtual wallpaper width. Start with radii in `0.004f..0.012f` of that edge and sliding vertical stretch up to `1.8f`.
- Use a fixed pool of at most 48 slots. Slot identity and motion never depend on active count, weather severity, or intensity, so increasing density adds droplets without teleporting existing ones.
- Start with a 24-second cycle per slot, independently staggered by a deterministic slot hash. In local cycle seconds: `[0, 2)` forms at its anchor, `[2, 8)` stays beaded, `[8, 22)` accelerates downward along a subtly curved lane, and `[22, 24)` is invisible before reseeding. Reach below the bottom edge before the invisible/reset interval; never teleport a visible bead across the screen.
- Hash `(slot, cycle)` for a new anchor/size/lane each cycle. Anchors start inside the viewport, with vertical anchors initially in `0.05f..0.65f` of height. Keep slide displacement an analytic function of cycle progress; never integrate velocity from frame deltas.
- Count density in normalized dimensions: scale both visible dimensions by `1080f / minOf(width, height)`, call `precipitationDropCount` with those dimensions and the existing scale, divide by `20f`, round, then clamp to `1..48` for eligible rain. Zero/negative scale or non-positive dimensions yield zero. This preserves a useful population at preview sizes without making a higher-DPI screen disproportionately wet.
- The numbers above are starting art direction, not measured performance or user-approved pixel targets. Tune only against rendered evidence and record the final choices. Tests must protect bead/slide/exit behavior, not freeze incidental tuning values.

## Execution Preparation

- [ ] Fetch and check upstream freshness; preserve any user changes. Read current instructions and use the worktree skill before isolated implementation. Create `feature/sliding-water-droplets` before Task 1, not at delivery time.
- [ ] Read the source map and interfaces, and identify an authorized disposable Android target or a data-preserving install workflow. Do not substitute a temporary CI workflow for unavailable local tooling.

### Task 1: Implement deterministic bead-and-slide motion

**Files:**
- Create: `app/src/main/java/xyz/attacktive/weatherd/domain/render/GlassDropletMotion.kt`
- Create: `app/src/test/java/xyz/attacktive/weatherd/domain/render/GlassDropletMotionTest.kt`

**Interfaces:**
- Consumes: `SceneParams`, `PrecipitationKind`, `precipitationDropCount(Precipitation, Float, Float, Float): Int`.
- Produces: `internal fun glassDropletCount(params: SceneParams, width: Float, height: Float): Int`.
- Produces: `internal class GlassDropletFrame` with mutable Float fields `centerX`, `centerY`, `radius`, `verticalStretch`, and `opacity`; use `opacity == 0f` for invisible slots.
- Produces: `internal fun sampleGlassDroplet(slot: Int, width: Float, height: Float, timeSeconds: Float, out: GlassDropletFrame): Unit`. It overwrites every output field on every call, has no Android dependencies, and allocates nothing per sample.

- [ ] **Step 1: Add failing behavior tests.** Use the following cases; sample motion at `0.25f` intervals over two complete cycles to locate bead/slide/reset intervals without special-casing a slot for tests.

| Test name | Required assertions |
| --- | --- |
| `onlyRainProducesGlassDroplets` | Zero count for null/snow/sleet, scale `0f`, and zero viewport; positive count for drizzle with `observed = 0f` at scale `0.1f`. |
| `densityRespectsIntensityAndRemainsBounded` | Nondecreasing counts at scales `0.1f`, `1f`, `2f`; at most 48 slots, including extreme aspect ratios; equal counts for `360×780` and `1080×2340`. |
| `beadHoldsThenSlidesDownward` | A visible full-size interval holds center/radius fixed; the subsequent visible sliding interval increases centerY; the exit/reset interval has zero opacity; the next cycle uses a different lane. |
| `reusedOutputSurvivesResizeAndClockWrap` | Overwrite one scratch frame through different times/dimensions and `21_599.9f` → `0f`; every output is finite, and the final sample equals a fresh sample at `0f`. |
| `geometryScalesWithTheVisibleViewport` | At equal time, normalized centers/radius match for `360×780` and `1080×2340`; vertical stretch and opacity are unchanged. |

- [ ] **Step 2: Run the targeted JVM suite and observe failure.** `source "$HOME/.sdkman/bin/sdkman-init.sh" && ./gradlew :app:testDebugUnitTest --tests 'xyz.attacktive.weatherd.domain.render.GlassDropletMotionTest'`. Before implementation, unresolved new symbols are an expected compile failure; capture the actual diagnostic rather than guessing it.
- [ ] **Step 3: Implement the interfaces and selected motion design.** Use primitive arithmetic/hash mixing, positive modulo, and a reusable output object. Keep independent slot/cycle identity; density changes must not alter phase or speed. Derive radius from `minOf(width, height)` so normalized samples preserve their geometry across sizes.
- [ ] **Step 4: Re-run the same targeted JVM command.** All named assertions must pass. This does not establish visual quality.
- [ ] **Step 5: Review changes against coding conventions.** Apply the active harness's required convention review, fix violations, and inspect the scoped diff before committing.
- [ ] **Step 6: Commit the motion model and behavioral tests.** Suggested subject: `feat: model glass droplet bead and slide motion`. Follow current signing, rule-reading, and active-model attribution requirements.

### Task 2: Draw a reusable translucent glass layer

**Files:**
- Create: `app/src/main/java/xyz/attacktive/weatherd/domain/render/GlassDropletLayer.kt`
- Create: `app/src/androidTest/java/xyz/attacktive/weatherd/domain/render/GlassDropletRenderingTest.kt`

**Interfaces:**
- Consumes: all Task 1 interfaces.
- Produces: `internal class GlassDropletLayer` with `fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float): Unit`.
- Owns its Paint/Path/RectF/scratch frame objects for its lifetime; borrows the caller's canvas only during `draw` and does not touch `SceneRenderer`'s shared paint or tile cache.

- [ ] **Step 1: Add failing bitmap behavior tests.** Use the following cases on real Android Canvas; recycle test-owned bitmaps. Locate bead and sliding samples with the motion interface rather than exposing private drawing state.

| Test name | Required assertions |
| --- | --- |
| `beadTransmitsBackgroundAndHasGlassHighlights` | On dark and light backgrounds, the sampled body responds to background color; its upper highlight is brighter than its body, and its lower rim is darker than neighboring light background. Use regional relational measurements, not golden images or a generic pixel-change check. |
| `nonRainDoesNotLeaveGlassPixels` | Dry/snow/sleet/zero-intensity draws leave the supplied bitmap exactly unchanged, including after a rainy draw on the same layer. |
| `reusedLayerPreservesGeometryAndMaterial` | Rain → dry → rain, low → high → low intensity, and portrait → landscape → portrait produce the same final frame as a fresh layer at identical dimensions/time. |

- [ ] **Step 2: Build and run this instrumentation class using the safe commands below, and observe the missing-layer failure before adding it.** Missing-symbol compilation is a valid initial failure; later image assertion failures must be diagnosed from their actual output.
- [ ] **Step 3: Implement `GlassDropletLayer.draw`.** Iterate only the active slot prefix. Sample into one scratch frame, skip invisible/offscreen slots, and draw rounded/teardrop geometry with separate reusable body, rim, and highlight paints. Use a reused path or local transforms for stretched sliding beads; save/restore any canvas transform. Do not retain previous frame pixels, create a full-screen overlay bitmap, or introduce blur/refraction. Keep the body deliberately translucent and the highlight small, with bounded per-slot draw calls.
- [ ] **Step 4: Run the targeted instrumentation class again.** Require `OK (...)` with no instrumentation failures. Inspect rendered bead and slide frames on both dark and light backgrounds; numeric tests alone cannot establish “glass-like.” Capture visual evidence for the next task's surface smoke run.
- [ ] **Step 5: Review changes against coding conventions.** Apply the active harness's convention review before committing; check the new draw loop for avoidable allocation and shared-state leakage.
- [ ] **Step 6: Commit the drawable layer and rendering regressions.** Suggested subject: `feat: render translucent sliding glass droplets`.

### Task 3: Integrate screen-space droplets in preview and wallpaper

**Files:**
- Modify: `app/src/main/java/xyz/attacktive/weatherd/domain/render/SceneRenderer.kt:185–318`
- Modify: `app/src/main/java/xyz/attacktive/weatherd/service/WeatherLiveWallpaperService.kt:215–225`
- Create: `app/src/androidTest/java/xyz/attacktive/weatherd/domain/render/GlassDropletSceneRenderingTest.kt`
- Modify after successful smoke: `README.md` and root `AGENTS.md`.
- Intentionally unchanged: `HomeScreen.kt`, `SceneParams.kt`, `WeatherSceneProvider.kt`, `SceneDebugPresets.kt`, settings persistence/UI, and existing falling-rain functions. Revisit only if current source has changed or runtime evidence proves an integration gap.

**Interfaces:**
- Consumes: `GlassDropletLayer.draw` from Task 2 and the existing `timeSeconds`.
- Produces: replace the last parameter of `SceneRenderer.renderForeground` with `includeScreenEffects: Boolean = true`; preserve the other parameters and default behavior of drawing a complete animated preview.
- Produces: `fun renderScreenEffects(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float): Unit`, which draws glass first, then the existing overlay labels.
- Remove the obsolete public `renderOverlayLabels` entry point after migrating its caller; keep the private `drawOverlayLabels` implementation. Do not retain a deprecated flag/alias.

- [ ] **Step 1: Enumerate renderer references, then add failing integration regressions.** Use LSP references if available, otherwise exact-name search. Add the following behavioral tests; keep API-forwarding/default-path comparisons in a throwaway smoke harness, not permanent tests.

| Test name | Required assertions |
| --- | --- |
| `screenPassUsesVisibleWidthAfterWideWorld` | Render a wide world with screen effects excluded, then render the isolated screen pass at `360×780`. Compare with a fresh renderer's screen pass at the same time; prior world dimensions/paint state must not move or distort glass. |
| `screenPassKeepsLabelsAboveGlass` | At a deterministic sample with bead/glyph overlap, compare the combined pass with two real passes: rain with labels absent, then labels with rain absent. The overlap region must match glass-then-label compositing and differ from the reversed order. Do not duplicate the private text drawing implementation. |
| `weatherTransitionClearsScreenGlass` | Rain → snow/sleet/dry on the same renderer, with labels absent: each isolated non-rain screen pass leaves its supplied background unchanged. |

- [ ] **Step 2: Run the targeted integration class and observe failure.** Missing new API references are expected before the cutover. Do not weaken the assertions to accept world-space beads.
- [ ] **Step 3: Add one renderer-owned glass layer and perform the clean cutover.** At the end of `renderForeground`, after existing lightning, call `renderScreenEffects` only when `includeScreenEffects` is true. In `renderScreenEffects`, draw glass with visible dimensions and then invoke the existing private label drawing. In wallpaper `drawViewport`, pass `includeScreenEffects = false` to the translated world call, restore the canvas, then call `renderScreenEffects(canvas, width, height, params, timeSeconds)` exactly once. The preview's existing default call and `render()` now include both screen effects automatically; do not add another preview population or clock. Preserve the existing scene crossfade rather than changing its semantics.
- [ ] **Step 4: Run all three new suites and the existing renderer instrumentation suite.** Re-run JVM tests plus the complete `xyz.attacktive.weatherd.domain.render` instrumentation package using the commands below. Also run the repository checks once: `source "$HOME/.sdkman/bin/sdkman-init.sh" && ./gradlew check :app:lint :app:detekt`. Require successful Gradle completion and instrumentation `OK (...)`; report any unavailable execution prerequisite explicitly rather than claiming it passed.
- [ ] **Step 5: Exercise the real app and already-running wallpaper.** Use the smoke matrix below, inspect actual motion, and record observations. This is required even if bitmap tests pass. Ask the user to operate an interactive device they are using; do not change its wallpaper/settings or drive its UI without the executing harness's required authorization.
- [ ] **Step 6: Update documentation after the smoke run.** Add a concise README description of automatic rain-only glass droplets and their shared intensity behavior. In `AGENTS.md`, document the world/screen coordinate boundary, glass-before-label ordering, deterministic shared-clock motion, and no per-frame allocation/refraction requirement. Record final tuned values and observed verification in the PR description, not fabricated frame-rate claims.
- [ ] **Step 7: Review changes against coding conventions.** Apply the current harness's required mechanical audit immediately before committing; review all changed callsites and verify old flag/API references are gone. An independent final review must check issue coverage, the five Review Focus cases, paint/canvas isolation, and preservation of falling rain. Correct issues before delivery.
- [ ] **Step 8: Commit, push, and open the implementation PR.** Suggested subject: `feat: add screen-space glass droplets during rain`. Read any current PR template and delivery rules. The implementation PR may use `Closes #87` only once the feature and smoke criteria are satisfied. Wait for required checks, report the PR and observed evidence, and stop without merging. Remove only agent-created clean worktrees/local branches according to current rules.

## Safe Android Verification Commands

Choose the intended serial from `adb devices -l` and set `ANDROID_SERIAL`; do not default to whichever personal device happens to be attached. Build both APKs locally:

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -e class xyz.attacktive.weatherd.domain.render.GlassDropletRenderingTest xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$ANDROID_SERIAL" shell am instrument -w -e class xyz.attacktive.weatherd.domain.render.GlassDropletSceneRenderingTest xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$ANDROID_SERIAL" shell am instrument -w -e package xyz.attacktive.weatherd.domain.render xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner
```

Direct instrumentation avoids the known Gradle teardown uninstall but is not a substitute for separate data backup when a workflow can remove packages. For unit-only Task 1 verification, no device is needed. Local JVM tests use Android stub defaults, so keep motion Android-free and run actual Canvas assertions on Android.

## Real-Surface Smoke Matrix and Acceptance

- [ ] Preview and already-running wallpaper: select `DRIZZLE`, `RAIN`, `HEAVY RAIN`, and `THUNDERSTORM`; stationary beads and slower sliding bodies appear over the unchanged fast falling trails. Observe at least one complete bead-to-slide-to-exit cycle, not just a still screenshot.
- [ ] Repeat rain in day and night and over a photo and a procedural/scenery backdrop. Glass reads as translucent water rather than white particles; labels and preview controls remain legible above it.
- [ ] At precipitation intensity `0.1`, `1.0`, and `2.0`, population changes in the expected direction without reseeding surviving droplets. Wind changes still affect falling rain but do not blow attached glass beads across the screen.
- [ ] With wallpaper scrolling enabled on a supported launcher, swipe home pages: the weather world moves while glass and labels remain screen-anchored. Confirm no duplicate droplet pass during scene fades.
- [ ] Switch to `CLEAR`, `FOG`, `HUMID HAZE`, `SNOW`, and `SLEET`: glass disappears immediately, while existing scene effects remain. Turn simulation off and confirm live/fallback eligibility follows the shared params rather than stale simulator rain.
- [ ] Rotate/resize the surface, change frame-rate caps, hide/show the app, and turn the screen off/on: no distorted beads, visible recycling jump, or resume-time runaway. Preview and wallpaper use the same visual rules, even when dimensions/aspect ratios differ.
- [ ] On the same device/build configuration, compare rain with and without the new layer using a temporary local measurement harness or profiler. Inspect allocations after warm-up and record measured render cost; do not invent an FPS/battery claim or add permanent benchmarking infrastructure. Resolve a repeatable regression or visible jank before delivery.

**Completion evidence:** Passing named unit/instrumentation checks, successful repository checks, actual preview and wallpaper observations covering the matrix, final visual captures or recordings, documentation updates, and a reviewed implementation PR. A plan, compiling layer, or isolated bitmap alone is not completion of issue #87.
