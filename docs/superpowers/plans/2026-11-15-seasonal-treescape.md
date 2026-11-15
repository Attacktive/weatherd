# Seasonal Treescape Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add issue #394's seasonal meadow-and-two-tree scenery beneath the live sky.

**Architecture:** A dedicated TreescapeLayer shares prepared immutable seasonal bitmaps between renderer instances and draws meadow and tree artwork with cached near-scenery lighting. Existing SceneParams.backdropScene/treeSeason carry the selection and resolved season through live, simulator, and fallback paths. Both rendering surfaces prewarm the selected scenery/season off-thread.

**Tech Stack:** Kotlin, Android Canvas/Compose, Python/Pillow/NumPy for reproducible offline artwork preparation; no new runtime dependencies.

**Spec:** ../specs/2026-11-15-seasonal-treescape-design.md

## Global Constraints

- Preserve current appearance defaults, existing tree styles, stored enum names, and season migration semantics.
- Treescape includes its own two trees; the independent Trees overlay is optional.
- Reuse the existing season preference. Winter means frost/snow coloration, not leaf shedding.
- Keep pristine sources and a deterministic preparation recipe. No decoding, seasonal pixel transforms, or geometry allocation during steady-state frames.
- Actual Android visual evidence and ordinary local disposable-device instrumentation are required before a PR; use Gremlin for GitHub mutations.

## Review Focus

- Wide landscape: trees remain grounded and the meadow covers the full bottom/side edges without stretching tree geometry.
- Tree overlay off: Treescape's season selector remains visible, updates the scenery, and appears only once with overlays on.
- Auto changes location/month while animation is settled: both surfaces prepare the newly resolved season and repaint.
- Rapid selection/season changes: stale background workers cannot evict or publish the wrong selected season.
- Presets and downgrade compatibility: Treescape plus Auto survives current serialization; unknown scenery still falls back safely in name parsing.

### Task 1: Reproducible seasonal artwork

**Files:** Create scripts/assets/treescape/{scene,meadow}.jpg, scripts/prepare-treescape.py, app/src/main/res/drawable-nodpi/treescape_{meadow,trees}_{spring,summer,autumn,winter}.webp.

**Interfaces:** Produces transparent meadow and integrated two-tree images with identical dimensions across seasons within each family. The script owns crop coordinates, material masks, seasonal transforms, and hashes. Originals are never modified.

- [ ] Download originals, print dimensions/modes/hashes, and preserve provenance in the recipe.
- [ ] Prepare alpha-separated meadow and two-tree material layers. Remove sky, captured UI, and bottom export edges. Use explicit material masks so seasonal foliage changes do not recolor trunks.
- [ ] Generate four seasonal variants with deterministic lossless WebP encoding. Produce throwaway sky-composited contact sheets and inspect them before Kotlin integration.
- [ ] Check transparent sky, opaque bottom coverage, absence of captured phone chrome, identical family geometry, and reproducibility. If clean extraction fails, stop with visual evidence and consult rather than substituting artwork.

### Task 2: Shared scenery and controls

**Files:** Modify domain/model/BackdropScene.kt; domain/render/{BackdropScenery,SceneRenderer}.kt; ui/home/HomeScreen.kt; service/WeatherLiveWallpaperService.kt; ui/settings/AppearanceSections.kt; localized values*/strings.xml. Create domain/render/TreescapeLayer.kt.

**Interfaces:** TreescapeLayer(resources) exposes isTextureReady(season: TreeSeason), prewarm(season: TreeSeason), draw(canvas: Canvas, width: Float, height: Float, params: SceneParams). SceneRenderer.isSceneryTextureReady and prewarmSceneryTexture consume scene and resolved season. Existing SceneParams is unchanged.

- [ ] Extend actual scenery-selection/preset compatibility coverage for TREESCAPE; add geometry/rendering edge coverage where existing tests cannot exercise the new layer. Run focused tests after implementation per the repository's feature-verification policy.
- [ ] Add TREESCAPE to the enum and picker; exclude it from procedural silhouette dispatch. Preserve existing names and defaults.
- [ ] Implement off-thread selected-season resource preparation with immutable bounded publication; cached layout independently scales meadow and tree composition for portrait/landscape, preserving tree proportions and bottom coverage.
- [ ] Draw Treescape after clouds and before optional foreground trees/fog/precipitation using SceneryLighting(NEAR).
- [ ] Migrate all scenery readiness/preparation callers. Preview keys readiness/effect by resolved season; wallpaper queues preparation from captured SceneParams, including settled clock refreshes. Reuse existing request guards and frame invalidation patterns.
- [ ] Split optional tree-style visibility from shared season visibility, showing one season selector when treesEnabled or TREESCAPE. Add contextual help and localized picker labels without changing saved settings keys.
- [ ] Verify provider/simulator/fallback season propagation with existing tests and a Treescape selection case; verify preset round-trip and independent Trees overlay behavior.

### Task 3: Android proof and delivery

**Files:** Update docs/development.md, README.md credits, root AGENTS.md only as needed for durable scenery contracts. Add focused Android rendering/UI contract coverage in existing test patterns; remove throwaway contact sheets/scripts from deliverable.

- [ ] Run ./gradlew test :app:lint :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest with the local SDK and JDK.
- [ ] Use a new disposable emulator, never the existing personal/evaluation installation. Run ordinary instrumentation with explicit ANDROID_SERIAL.
- [ ] Inspect actual Android-rendered seasonal/day/weather/orientation samples and preview/wallpaper consistency. Record observed results and limits; do not equate APK builds with visual proof.
- [ ] Document shared seasonal scenery behavior once, source permissions and regeneration, preserving README scope.
- [ ] Review changes against coding conventions, then commit via Gremlin with fresh global rule enumeration and active-model attribution.
- [ ] Open a PR only after local instrumentation passes; watch existing CI, update roadmap #194 to the actual delivery state, and remove clean task-created worktree/local branch. Do not merge.

## Execution rulings

- Repository feature-verification policy takes precedence over skill-level blanket TDD/baseline-suite requirements: use focused consumer-visible edge tests and one final check run, not redundant mid-flight full builds.
- This plan is executed inline; final independent code/convention review follows the repository's required procedure.
