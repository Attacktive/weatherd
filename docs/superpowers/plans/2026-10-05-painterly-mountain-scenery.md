# Painterly Mountain Scenery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the existing mountain silhouettes as coherently lit rock, snow, forest, and meadow with restrained material variation and atmospheric depth.

**Architecture:** Extract scenery ownership from `SceneRenderer`, retain its foreground ordering, and share the existing celestial/weather calculations. Prepare coarse oriented surface patches and immutable, terrain-bounded material/mask rasters only on geometry invalidation; update cached paint state when lighting inputs change. Keep the other scenery scenes visually unchanged.

**Tech Stack:** Kotlin, Android Canvas, existing bitmap helpers, JUnit 4, Android instrumentation, and the existing Gradle/CI checks; no new dependencies.

**Spec:** [Mountain scenery lighting design](../specs/2026-10-05-mountain-scenery-lighting-design.md)

## Global Constraints

- The user approved a mountain-first approach that preserves the seeded silhouettes, aspect-ratio adaptation, snowcap shapes, and FAR/NEAR depth planes.
- This change must deliver complete treatment of rock, snow, forest, meadow, atmospheric depth, and localized contact shading for the mountain scene.
- Beach water, countryside materials, and metropolis facade improvements are outside this implementation; issue #77 remains open for those scenes.
- No new display settings, dependencies, third-party artwork, 3D renderer, or GPU-only rendering path are required.
- The preview and wallpaper must continue using one implementation and the existing scene simulator inputs.
- Move existing scenery-only helpers and cache fields rather than leaving duplicate implementations or forwarding aliases.
- The sun-artwork visibility preference does not alter terrain illumination; celestial timing and meteorological conditions remain the lighting inputs.
- Retain only the active scene's prepared resources, not a growing dictionary of weather or celestial-progress variants.
- Publish immutable bitmap sources through the existing immutable-rasterization pattern and do not mutate them after publication.
- Steady-state frames must perform no avoidable bitmap, path, gradient, array, color-filter, or mask allocation and no noise generation or blur computation.
- Use a disposable emulator for installation and instrumentation; do not uninstall or replace the user's existing wallpaper installation for baseline captures.
- Preserve the current Android SDK floors and build configuration; the inspected app uses `minSdk = 26` and `compileSdk = 37`.

## Review Focus

- Decorative sun/moon/flare preferences must not dim terrain; Task 2 tests lighting independence and Task 3 checks rendered terrain pixels.
- High cirrus cover alone must not suppress terrain sunlight; Task 2 tests the existing low/mid opaque-cloud precedence against total-cover fallback.
- Dawn/day and dusk/night boundaries must not introduce lighting jumps; Task 2 tests endpoint continuity and Task 4 inspects progress sweeps.
- Portrait/landscape and virtual-width changes must not stretch surface relief or retain stale masks; Task 2 tests aspect-aware normals and Task 3 compares reused versus fresh renderers.
- Switching through NONE/PHOTO and changing weather must release inactive terrain ownership and restore correctly shaded geometry; Task 3 tests the rendered transition sequence and Task 4 measures retained sources after repeated switches.

---

## File Structure

Production files are in `app/src/main/java/xyz/attacktive/weatherd/domain/render/`:

| File | Change and responsibility |
| --- | --- |
| `SceneRenderer.kt` | Remove scenery fields/helpers; retain sky/weather rendering and delegate scenery at its existing foreground position. |
| `SceneryRenderer.kt` | New internal owner of layer/glyph paths, scenery details/animations, geometry invalidation, and the active mountain surface renderer. |
| `CelestialLighting.kt` | New JVM-pure home for the existing celestial position/visibility calculations and shared sun color calculation. |
| `ScenePalette.kt` | Expose intrinsic material color and weather occlusion calculations to terrain without duplicating or changing the other scenes' palette behavior. |
| `SceneryLighting.kt` | New reusable lighting state and pure material illumination calculation. |
| `MountainSurface.kt` | New JVM-pure, deterministic surface patch preparation using the existing outlines. |
| `MountainSurfaceRenderer.kt` | New cached mask/material raster preparation and mountain plane composition. |

Tests are `app/src/test/java/xyz/attacktive/weatherd/domain/render/SceneryLightingTest.kt`, `app/src/test/java/xyz/attacktive/weatherd/domain/render/MountainSurfaceTest.kt`, and `app/src/androidTest/java/xyz/attacktive/weatherd/domain/render/MountainSceneryRenderingTest.kt`.
Existing `ScenePaletteTest.kt`, `BackdropSceneryTest.kt`, and Android rendering tests remain behavioral backstops; do not add tests for extraction forwarding or rewrite incidental expected colors to accommodate changes.
Update `AGENTS.md` and `README.md` only after actual visual smoke evidence.

## Task 1: Capture the baseline and extract scenery ownership

**Files:** Create production `SceneryRenderer.kt`; modify production `SceneRenderer.kt`; use a throwaway instrumentation capture class `app/src/androidTest/java/xyz/attacktive/weatherd/domain/render/MountainSceneryCapture.kt`, never committed.

**Consumes:** Existing `sceneryOutlinesFor(scene: BackdropScene, aspectRatio: Float): SceneryOutlines?`, `SceneParams`, `renderImmutableBitmap`, and `SceneRenderer.renderBackdrop` / `renderForeground`.

**Produces:** `internal class SceneryRenderer` with `fun draw(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float)`; the existing public `SceneRenderer` interfaces remain unchanged.

- [ ] **Step 1: Launch an isolated disposable target.** Use the SDK containing the discovered `adb`; inspect available AVDs and current devices again before launching a separate `Pixel` instance with `-read-only -no-snapshot -no-window` on an unused even console port. Set `SERIAL` only to this newly launched instance, never to the user's existing device or emulator.
- [ ] **Step 2: Run the baseline checks.** Source `"$HOME/.sdkman/bin/sdkman-init.sh"`, then run `./gradlew test :app:lint :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest`; expect successful Gradle completion. Baseline failures stop feature changes and are reported rather than repaired speculatively.
- [ ] **Step 3: Record a reproducible capture manifest.** Store baseline images, exact `SceneParams`, animation times, dimensions, backend, build commit, and measurement protocol under ignored `.superpowers/issue77/`. Use `debugSceneParams` with existing CLEAR, OVERCAST, FOG, RAIN, SNOW, and THUNDERSTORM presets, all four day phases, and progress values `0f`, `0.5f`, and `1f`; set `backdropScene` to MOUNTAINS. Cover portrait, landscape, scrolling virtual width, and the other scenery scenes before extraction. Keep these originals untouched and derive comparison output from the manifest.
- [ ] **Step 4: Move existing scenery implementation.** Transfer the fields currently at `SceneRenderer.kt:92-105`, the scenery helpers currently at `:395-1011`, scenery-only path/pass types, helicopter constants, and eligibility logic into `SceneryRenderer`. Give it its own reusable paints, paths, rectangles, and matrices; keep color math, random seeds, draw order, and animation timing unchanged. Move the `drawsScenery` guard into `SceneryRenderer.draw` so NONE/PHOTO transitions release active prepared terrain instead of leaving it retained invisibly.
- [ ] **Step 5: Remove avoidable extracted hot-loop work.** Compare scene and integer dimensions directly instead of allocating a string geometry key. Cache sky-gradient results, material tones, and horizon/haze/reflection gradients only when their relevant lighting inputs change; do not allocate a `SkyGradient`, pairs, or collection iterators for repeated identical frames. Translate reusable valley-mist shaders with reusable matrices; cache helicopter random choices per existing slot and reuse pass storage. Do not alter trajectories or add another animation schedule.
- [ ] **Step 6: Exercise the cutover.** Rebuild and run existing rendering instrumentation on the disposable instance, then recapture the unchanged scene matrix with the same manifest. Inspect actual images and compare terrain/detail crops; investigate any appearance difference before adding the material treatment. Existing tests must pass, with no new wiring-only tests.
- [ ] **Step 7: Review changes against coding conventions.** Route the nontrivial code diff through the read-only `convention-police` audit and resolve evidenced violations.
- [ ] **Step 8: Commit the extraction.** Freshly enumerate/read all mandatory omp rules and commit only the extraction as `refactor: isolate scenery rendering`. Do not publish or describe the mountain feature as complete at this point.

## Task 2: Implement shared lighting and deterministic surface descriptions

**Files:** Create production `CelestialLighting.kt`, `SceneryLighting.kt`, and `MountainSurface.kt`; modify production `SceneRenderer.kt` and `ScenePalette.kt`; create JVM `SceneryLightingTest.kt` and `MountainSurfaceTest.kt`.

**Consumes:** `SceneParams`, `SceneryLayer`, `SceneryGlyph`, `SceneryOutlines`, the existing sky palette, opaque-cloud interpretation, and day-phase progress ramps.

**Produces:**

```kotlin
internal const val CELESTIAL_X_FRACTION = 0.72f
internal fun celestialHeightFraction(dayPhase: DayPhase, progress: Float): Float
internal fun sunVisibility(dayPhase: DayPhase, progress: Float): Float
internal fun sunColor(dayPhase: DayPhase, preset: SunColorPreset): Int
internal fun sceneryMaterialColor(material: SceneryMaterial): Int
internal fun skyOcclusionFor(params: SceneParams): Float
internal class SceneryLighting
internal fun surfaceColorFor(material: SceneryMaterial, plane: SceneryPlane, diffuse: Float, lighting: SceneryLighting): Int
internal data class MountainSurfacePatch(val outline: List<OutlinePoint>, val normalX: Float, val normalY: Float, val normalZ: Float)
internal data class MountainSurface(val layerIndex: Int, val patches: List<MountainSurfacePatch>)
internal fun mountainSurfacesFor(outlines: SceneryOutlines, aspectRatio: Float): List<MountainSurface>
internal fun surfaceDiffuseFor(patch: MountainSurfacePatch, lighting: SceneryLighting): Float
```

`SceneryLighting.update(width: Int, height: Int, params: SceneParams): Boolean` updates reusable state and returns whether relevant lighting inputs changed.
Its read-only Float output properties are `directionX`, `directionY`, `directionZ`, `directStrength`, `textureStrength`, `farAtmosphere`, and `nearAtmosphere`; `directColor` and `ambientColor` are read-only Int properties.
The screen-space orientation convention is positive x right, positive y up, and positive z toward the viewer; normalize directions using physical pixel distances so aspect ratio cannot skew their meaning.

- [ ] **Step 1: Write failing lighting tests.** Define `clearParams(phase: DayPhase)` with cloudiness/fog/wind zero, no precipitation, and thunder false; `lighting(params)` initializes one `SceneryLighting` at 1080 by 2400. The tests `night removes directional sunlight`, `decorative visibility preserves terrain lighting`, `decorative sun styling preserves terrain lighting`, `opaque cloud layers attenuate direct light`, `total cover fallback attenuates direct light`, `fog attenuates direct light`, and `thunder alone attenuates direct light and surface contrast` are specified by the following assertions; the final pair belongs to the thunder-only test:

```kotlin
assertEquals(0f, lighting(clearParams(DayPhase.NIGHT)).directStrength, 0f)
assertEquals(lighting(clearParams(DayPhase.DAY)).directStrength, lighting(clearParams(DayPhase.DAY).copy(sunVisible = false, moonVisible = false, lensFlareEnabled = false)).directStrength, 0f)
assertEquals(lighting(clearParams(DayPhase.DAY)).directColor, lighting(clearParams(DayPhase.DAY).copy(sunColorPreset = SunColorPreset.ORANGE, sunSizeScale = 2f)).directColor)
assertTrue(lighting(highCirrusParams).directStrength > lighting(opaqueLowCloudParams).directStrength)
assertTrue(lighting(totalOvercastParams).directStrength < lighting(clearParams(DayPhase.DAY)).directStrength)
assertTrue(lighting(fogParams).directStrength < lighting(clearParams(DayPhase.DAY)).directStrength)

val clearDayParams = clearParams(DayPhase.DAY)
val thunderOnlyParams = clearDayParams.copy(thunder = true)
val clearDayLighting = lighting(clearDayParams)
val thunderOnlyLighting = lighting(thunderOnlyParams)

assertTrue(thunderOnlyLighting.directStrength < clearDayLighting.directStrength)
assertTrue(thunderOnlyLighting.textureStrength < clearDayLighting.textureStrength)
```

Use `SceneCloudLayers(low = 0.05f, mid = 0.05f, high = 0.8f)` versus `SceneCloudLayers(low = 0.8f, mid = 0.05f, high = 0.05f)` at total cloudiness `0.8f`, total-only cloudiness `0.85f`, and fog density `1f` for their independent cases.
The thunder-only case changes no cloud, fog, precipitation, wind, or timing inputs; both attenuation assertions must fail if thunder has no effect even when other weather attenuation is implemented.
Keep the existing THUNDERSTORM preset in Task 1's visual capture matrix, not as the isolated thunder unit fixture.
Add `dawn meets daylight continuously` and `dusk meets night continuously`: for ROCK and SNOW on both planes, compare `surfaceColorFor` at DAWN/1 versus DAY/0 and DUSK/1 versus NIGHT/0; summed RGB difference must be at most 3, allowing one quantization step per channel rather than pinning a palette color.
Add `sky customization reaches ambient terrain light`: changing sky brightness or palette must change `ambientColor`, while decorative preferences above must not.

- [ ] **Step 2: Write failing surface tests.** Add `surface preparation preserves seeded geometry and is deterministic`: prepare mountain surfaces at portrait and landscape aspects, assert input outlines remain unchanged, and assert repeated preparation produces equal patch data. Add `surface normals are normalized and aspect aware`: assert `abs(normalX * normalX + normalY * normalY + normalZ * normalZ - 1f) < 0.0001f` for every patch, then encode equivalent physical slopes at two aspect ratios and compare corresponding normals within that tolerance. Add `diffuse response clamps back facing surfaces`: an aligned normal returns 1 within tolerance and the opposing normal returns 0. Add `night material color ignores directional response`: `surfaceColorFor` must return the same color when its diffuse argument changes from 0 to 1 at night.
- [ ] **Step 3: Run the tests red.** Run `./gradlew :app:testDebugUnitTest --tests '*SceneryLightingTest' --tests '*MountainSurfaceTest'`; expect missing new production symbols, not an unrelated dependency or fixture error.
- [ ] **Step 4: Implement the shared calculations.** Move celestial height/visibility and their constants without aliases, retaining the existing trajectory and fade values. Move `sunColor` as packed JVM-pure RGB arithmetic with unchanged results for existing sky callers. Rename/expose the existing intrinsic material and overcast functions as `sceneryMaterialColor` and `skyOcclusionFor`, migrating every caller while preserving other scenes' outputs. Reuse the sky's low/mid-cloud precedence and weather transforms; high cirrus alone is not opaque cover.
- [ ] **Step 5: Implement reusable terrain lighting.** Cache relevant input primitives, not copied `SceneParams` or allocated per-frame keys. Use the existing celestial height and x fraction to derive the direction; blend natural direct-light color with the existing dawn/daylight and dusk/warmth/night ramps so phase endpoints match. Derive ambient color from the actual tuned/weather-transformed sky, suppress direct light explicitly at night, and blend atmosphere after local material illumination. Decorative celestial/flare settings are excluded from the lighting key.
- [ ] **Step 6: Implement coarse surface preparation.** Partition each range at prominent saddles, ignoring small dips inside a broad crest, and create broad ridge/valley facet envelopes anchored to the existing shoulders and summits. Compute fixed normalized approximate normals in physical aspect-correct coordinates. Forest/meadow receive gentler patch orientation changes. Use independent stable material seeds; do not consume geometry RNGs, alter outlines, or prepare a second snow relief field.
- [ ] **Step 7: Run the tests green.** Run the same focused JVM command and the existing palette/scenery tests; expect all selected tests to pass. Remove any affected incidental implementation/default-color pins rather than re-pin them to the new code, but retain consumer-visible weather, continuity, depth, and geometry assertions.
- [ ] **Step 8: Review changes against coding conventions.** Perform the mandatory read-only audit and resolve evidenced violations.
- [ ] **Step 9: Commit the pure calculations.** Freshly enumerate/read mandatory omp rules and commit as `feat: prepare mountain surface lighting`. These interfaces are consumed by Task 3 in the same implementation branch; do not ship an unconnected library as the deliverable.

## Task 3: Render cached material fields, depth, and contact shading

**Files:** Create production `MountainSurfaceRenderer.kt`; modify production `SceneryRenderer.kt`; create Android `MountainSceneryRenderingTest.kt`.

**Consumes:** Task 2's lighting and patch interfaces, unchanged scenery outlines/glyphs, and `renderImmutableBitmap`.

**Produces:** `internal class MountainSurfaceRenderer(outlines: SceneryOutlines, surfaces: List<MountainSurface>, width: Int, height: Int)` with `fun updateLighting(lighting: SceneryLighting)` and `fun drawPlane(canvas: Canvas, plane: SceneryPlane)`; `SceneryRenderer.draw` becomes the complete mountain rendering path.

- [ ] **Step 1: Write failing rendered-behavior tests.** Add `rock and snow share directional faces` using a synthetic two-face rock ridge, a snow glyph spanning both faces, and a clear directional lighting state; assert the exposed rock face is brighter than its shaded face and corresponding snow has the same ordering. Add `material masks preserve terrain and snow boundaries`: render actual planes to a transparent software bitmap, assert zero alpha outside the union of original terrain contours, and compare renders with/without the snow glyph to assert unchanged rock pixels just outside its snow boundary. Rock beneath snow is opaque, so do not incorrectly require transparent pixels outside snow. Use interior sample rectangles away from antialiasing rather than a golden image or source-text assertion.
- [ ] **Step 2: Add transition and backend coverage.** Add `reused renderer matches fresh renderer after weather and viewport changes`, comparing actual scene pixels after clear/overcast/clear and portrait/landscape/portrait sequences at fixed animation time. Add `mountains disappear through photo and none and return without stale shading` with MOUNTAINS/PHOTO/NONE/MOUNTAINS. Add `decorative sun preferences preserve terrain pixels`, comparing only terrain interior, excluding sky artwork and fog/mist. Add `hardware preserves directional faces and clipping`, using `RenderNode`/`HardwareRenderer` readback on supported test devices to repeat the directional and boundary assertions; software tests remain unconditional. Bitmap/readback helpers are local to this test, not a new production abstraction.
- [ ] **Step 3: Run the tests red.** Run `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`; missing new material-rendering symbols are the initial expected failure. Do not mistake an unrelated build/environment error for that red result.
- [ ] **Step 4: Prepare bounded material images and masks.** Start with quarter-resolution terrain-band rasters capped at 1024 pixels on the longest side, preserving source aspect ratio and applying the same scale to both axes. Allocate patch images only for their cropped bounds, not the whole viewport; prepare feathered masks and silhouette clipping before publication. Clip each live material draw group to its cached original path as well, preventing filtered upscaling from bleeding outside terrain or snow boundaries. Rock uses slope-aligned strata/crevice variation, snow smooth variation, forest broad canopy clusters, and meadow low-contrast patches. Snow clips and recolors its parent rock masks rather than generating independently oriented masks.
- [ ] **Step 5: Compose the complete mountain planes.** Prepare reusable paints, color filters, shader matrices, and rectangles. On lighting changes only, update base/material and patch colors through `surfaceColorFor`. Draw FAR rock and snow before existing valley mist/inter-plane haze; draw NEAR forest and meadow afterward; add a cached feathered contact darkening clipped to meadow near its forest boundary. Attenuate FAR texture/contrast and soften its prepared edge coverage using the actual sky target; do not add an unrelated fog layer or globally outline terrain.
- [ ] **Step 6: Integrate lifecycle and animation.** Construct surfaces only when scene/dimensions change, refresh new surface paint state even when the current lighting values have not changed, and drop obsolete scene references on NONE/PHOTO or another scenery selection. Never mutate/recycle images retained by recorded hardware commands. Keep UVs attached to virtual-scene coordinates; only existing details and mist respond to animation time. Lighting updates must not regenerate noise, masks, or material images.
- [ ] **Step 7: Run tests green and smoke the actual renderer.** Build app/test APKs with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, discover the actual produced paths as `APP_APK` and `TEST_APK`, and install them only on `$SERIAL` using `adb -s "$SERIAL" install -r -t "$APP_APK"` and `adb -s "$SERIAL" install -r -t "$TEST_APK"`. Run `adb -s "$SERIAL" shell am instrument -w -e class xyz.attacktive.weatherd.domain.render.MountainSceneryRenderingTest xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner`; expect successful instrumentation with the directional, boundary, transition, and hardware assertions passing. Run the capture matrix and read the produced software/hardware images; verify shared rock/snow faces, no horizon seams/contact outlines, and no texture motion during progress changes.
- [ ] **Step 8: Review changes against coding conventions.** Perform the mandatory read-only audit and resolve evidenced violations.
- [ ] **Step 9: Commit the connected rendering path.** Freshly enumerate/read mandatory omp rules and commit as `feat: render painterly mountain materials`. Do not call the feature complete until Task 4's visual, performance, and end-to-end criteria are observed.

## Task 4: Validate visual acceptance, performance, and real app surfaces

**Files:** Tune only the affected rendering files as supported by observed captures; update root `AGENTS.md` and `README.md` after smoke evidence; no new workflow, settings, or permanent telemetry files.

**Consumes:** The complete renderer, baseline capture manifest, focused tests, the existing scene simulator, and current frame-rate settings.

**Produces:** Observed comparison images, measured timing/retained-source data, updated durable documentation, and the implementation pull request ready for user review but not merged.

- [ ] **Step 1: Inspect paired captures and progress sweeps.** Recapture the manifest matrix, then sweep intermediate celestial progress values through dawn/day/dusk/night. Read each comparison image; reject flat textured-cardboard relief, hard facets, detached snow, bright weather-inappropriate greenery, FAR blur halos, seams, or texture swimming. Change one visual variable per cycle and recapture that case; do not bundle multiple speculative fixes.
- [ ] **Step 2: Observe preview and live wallpaper on the disposable instance.** Exercise the actual HomeScreen simulator and already-running wallpaper with the same chosen preset, phase, progress, and display preferences. Observe both surfaces after simulator edits and wallpaper scrolling; confirm coherent weather/material response without resetting wallpaper. Leave the user's original installation and currently running emulator untouched.
- [ ] **Step 3: Measure performance rather than assert it.** Use the same device, dimensions, backend, presets, and capture/readback protocol before/after; record warm steady-state and geometry-rebuild costs separately. Use eight warm-up frames and sixty measured frames per case, report observed timing distribution and configured-deadline misses, and reject sustained new deadline misses where baseline met the cap. Record the actual sum of retained terrain bitmap `allocationByteCount` separately from process/GPU memory, with repeated scene/weather/viewport transitions to show active resources stay bounded. Tune the initial raster bound only from measured evidence and recapture the affected visual cases.
- [ ] **Step 4: Run the complete existing checks.** Source SDKMAN and run `./gradlew check :app:lint :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest`; install the freshly produced app/test APKs using Task 3's explicit target commands, then run `adb -s "$SERIAL" shell am instrument -w xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner`. Expected outcome is successful Gradle and instrumentation completion with no failing tests. Existing CI is the remote gate; do not create an ad hoc workflow for captures or profiling.
- [ ] **Step 5: Update durable docs from observed behavior.** Describe the new ownership, shared lighting/cache invariants, material treatment, and actual performance method/results in `AGENTS.md` and `README.md`. Remove the throwaway capture class from tracked/source changes, retain reproducible comparison evidence outside the code diff, and reconcile every spec acceptance criterion with its observation or test result. No unfinished surface material or unexercised backend may be silently reduced out of scope.
- [ ] **Step 6: Review changes against coding conventions.** Obtain an independent whole-branch quality review and the mandatory read-only `convention-police` audit, then resolve evidenced issues.
- [ ] **Step 7: Commit final acceptance/documentation changes.** Freshly enumerate/read mandatory omp rules before committing; preserve signed history and derive the active model attribution rather than copy a prior session's identity.
- [ ] **Step 8: Publish and watch the implementation PR.** Update this branch's draft planning PR with complete implementation, visual evidence, measured results, and exercised verification. Push normally after fresh rule enumeration/read, mark it ready only when implementation is actually complete, and wait for CI plus online linters. Return to main and remove only task-created local branches/worktrees after inspecting their status; do not merge or release without a new user instruction.

## Plan Review and Execution Handoff

This plan implements spec acceptance 1/6 through Task 1 and Task 3; 2/3/4/5 through Task 2, Task 3, and Task 4; 7 through Task 3; 8 through Task 4; 9 through Task 2 and Task 3; and 10 plus documentation through Task 4.
All five Review Focus conditions have an owning test task, with actual-surface visual checks in Task 4.

Recommended execution is **Native**: the four tasks share closely coupled renderer/cache interfaces, so one integration owner should implement them in sequence with an independent whole-branch review at the end.
The alternative is **Subagent-driven**: task-by-task implementers and reviewer gates before the final branch review, with more context handoffs.
The user must review this written plan and choose an execution method before product-code changes; plan publication is not rendering implementation or visual/performance verification.
