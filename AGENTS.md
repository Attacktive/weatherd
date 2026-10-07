# AGENTS.md

Instructions and architectural invariants for agents working in the Weatherd codebase.

- Version: 1.6.8 (2026-09-23)
- Persona: Coding assistant pair-programming with the user on Weatherd.

## Tools and Environment

Agents use standard file inspection, editing tools, and Gradle tasks (`./gradlew check`).
- The JDK is installed via SDKMAN.
- Before Gradle tasks in non-interactive shells, source `"$HOME/.sdkman/bin/sdkman-init.sh"`.
- In the current setup, `connectedDebugAndroidTest` removes the target Weatherd installation during teardown (observed on Pixel); use it only on a disposable device or emulator.
- To preserve an existing installation, build the app and test APKs, install both with `adb install -r -t`, and run `adb shell am instrument -w xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner` directly.
- Back up app data separately from the APK before any test workflow that removes packages; saving the APK does not preserve settings or photos.

### GitHub Actions Are Not a General-Purpose Remote Shell

- Do not create disposable or one-off GitHub Actions workflows to perform development work.
	- This includes patching files, generating assets, tuning rendering, inspecting linter results, running ad hoc builds, or compensating for the current agent runtime lacking a local shell, JDK, Android SDK, or other tooling.
	- Do not create a new workflow for a single issue, pull request, debugging attempt, or iteration.
- Treat the workflows present on `main` as the repository's intentional CI/CD surface.
	- Use existing workflows for validation when they already cover the required build, test, analysis, or release task.
	- Missing local execution capability is not, by itself, justification for adding another workflow.
- Adding a new persistent workflow requires explicit user approval and a durable repository-level use case.
	- Explain why an existing workflow cannot serve the purpose before proposing one.
- Never create a temporary workflow with the intention of deleting it afterward.
	- GitHub retains historical workflow identities and runs in the Actions UI even after the YAML file is removed.

### Merge-and-Release Shorthand

When the user gives a concise instruction whose clear intent is to both merge a pull request and release it, such as `merge the PR and 🚀 it`, `merge and release`, or `merge and ship it`, treat that as authorization for the complete merge-and-release sequence below.
Do not ask what the shorthand means and do not re-research or rediscover Weatherd's release procedure in the normal path.

- The shorthand authorizes both merging the indicated/current pull request and cutting the next beta release.
- It does not authorize promotion from beta to production.
- "Do not research" here means do not rediscover release mechanics.
- Before merging, require the pull request's required checks to be green.
- Merge by fast-forward only so the exact reviewed commit objects and their signatures survive unchanged.
	- Refresh `main` and the pull-request head immediately before the merge.
	- Move `main` to the pull-request head only when that move is a fast-forward.
	- Never squash, rebase, or create a merge commit for this operation.
	- Afterward, verify that GitHub reports the pull request merged, `main` points at the former pull-request head, and the merged commits still report valid verification.
	- If a fast-forward is impossible, stop and report it rather than rewriting signed commits.
- Release immediately after the merge:
	1. Read `versionCode` and `versionName` from `app/build.gradle.kts`.
	2. Unless the user specified a version or a different semantic-version bump, increment `versionCode` by one and increment the patch component of `versionName` by one.
	3. Commit only that version bump directly to `main`, using the global version-bump exception to the pull-request rule.
		- Use the broker's `commit_files` operation.
		- Use the commit message `chore: bump version to <versionName>`.
		- Include the active model's required `Co-authored-by` trailer.
		- Verify the commit is authored by `attacktive-gremlin[bot]` and has a valid GitHub signature.
	4. Create tag `<versionName>` at that exact signed version-bump commit.
		- Do not tag the feature pull-request head or any earlier commit.
	5. The existing tag-triggered `Release` workflow is the release mechanism.
		- In the normal path, do not inspect or re-research the workflow before using it.
		- It builds the signed APK and AAB, creates the GitHub Release with generated notes, creates Play "What's new" text, and uploads the AAB to the Google Play beta track with completed status.
	6. Watch the `Release` workflow through completion.
		- On failure, inspect the failing job/logs and diagnose from that evidence.
		- On success, report the released version/tag and that the GitHub Release and beta upload completed.
- A request to only `merge` does not imply a release.
- A request to only `release` does not imply merging unrelated pull requests.

## Architectural Invariants

### Scene Simulator Overrides Live Weather

The scene simulator on `HomeScreen` has two modes:

1. **Live mode (`debugEnabled = false`)**: `WeatherSceneProvider` derives both the preview and live wallpaper from current weather and clock-derived day phase.
2. **Debug mode (`debugEnabled = true`)**: The persisted simulator selection overrides weather in `WeatherSceneProvider`, so the preview and already-running live wallpaper both render the chosen `SCENE_PRESETS` preset, day phase and celestial progress until debug mode is turned off.

**Debug mode must remain WYSIWYG between the preview and live wallpaper.**
It pins meteorological conditions (cloud cover, precipitation kind/severity, fog, thunder, wind) and celestial timing while preserving all user-configured display settings:

- Intensity sliders (precipitation, wind, cloud intensity)
- Sky palette/brightness/saturation and cloud contrast
- Scenery / backdrop selections (none, metropolis, beach, mountains, countryside, photos)
- Frame rate caps
- Photo background assignments and revisions

The simulator state is persisted through `SettingsRepository`; changing it while the wallpaper is visible must refresh `WeatherSceneProvider` without requiring the wallpaper to be reset.
To prevent the simulator and live weather paths from drifting, whenever any new render parameter, intensity multiplier, scene setting, or display behavior is introduced:

1. Add it to `SceneParams` and `sceneParamsFor`.
2. Wire it into `WeatherSceneProvider` (for the live wallpaper and live preview).
3. Wire it into `debugSceneParams` in `SceneDebugPresets.kt` (unless deliberately intended as debug-only).
4. Expose it in `HomeViewModel` from `SettingsRepository`.
5. Pass it into `debugSceneParams` in `HomeScreen.kt`.
6. Add unit tests in `SceneDebugPresetsTest` asserting that the setting reaches the generated `SceneParams`.

#### Example

```kotlin
// In SceneDebugPresets.kt
fun debugSceneParams(
	preset: ScenePreset,
	dayPhase: DayPhase,
	precipitationScale: Float = 1f,
	windScale: Float = 1f,
	cloudScale: Float = 1f
) = SceneParams(
	dayPhase = dayPhase,
	cloudiness = preset.cloudiness,
	fogDensity = preset.fogDensity,
	precipitation = preset.precipitation,
	thunder = preset.thunder,
	windFactor = preset.windFactor,
	precipitationScale = precipitationScale,
	windScale = windScale,
	cloudScale = cloudScale
)
```

### Two-Phase Rendering Pipeline (`SceneRenderer`)

- `renderBackdrop(canvas, width, height, params)`: Static layers (sky gradient, overcast ceiling, fog base, haze, vignette). Cached into a `Bitmap` by `WeatherLiveWallpaperService` and `HomeScreen`; re-rasterized only when `backdropSignature(params)` changes.
- `renderForeground(canvas, width, height, params, timeSeconds)`: Dynamic animated layers (stars, celestial body, birds, clouds, scenery, fog drift, precipitation, lightning, overlay text). Redrawn every frame.
- `LightningBoltLayer` builds deterministic, midpoint-displaced channels with tapered filled cores and branches rooted on parent vertices. It caches geometry and an immutable downscaled software-blurred bloom by strike seed and surface dimensions; per-frame drawing changes only flash opacity. Keep blur generation out of steady-state frames and preserve `SceneRenderer`'s shared strike schedule, echoes, sheet lightning, and rain illumination.
- Cloud decks use `CloudLayer`; fair-weather cumulus uses seeded placement populations, while overcast uses the dedicated `cloud_overcast_hero`, `cloud_overcast_support`, and `cloud_overcast_veil` sources.
- `scripts/generate-cloud-textures.py` reproducibly regenerates the procedural clear-sky cloud textures it owns with `uv run scripts/generate-cloud-textures.py` and samples no third-party artwork. The dedicated overcast sources are first-party authored assets and are not derived from the reference APK.
	- `cloud_cumulus_sparse` / `_scattered` / `_broken` and the in-between partly-cloudy profile select nested near populations drawn by `drawDryClouds`; they reuse four `cloud_cumulus_hero_*` sources and their transformed/composite morphology variants. `cloud_cumulus_far` retains the distant veil sources. The dry population remains underneath incoming overcast banks instead of disappearing at a cover threshold.
	- `drawCloudDrift` uses five overlapping portrait passes: far veil, support bank, one screen-dominant hero bank, bridge veil, and lower veil. The hero spans three viewports and the veil/support passes retain their shorter repeat spans; the bank field extends into the lower sky. Preserve the sources' 3:1 proportions; landscape keeps its existing four-pass layout and 55% surface-height cap.
- A cloud deck spans `CLOUD_TEXTURE_VIEWPORTS` viewport widths before repeating unless it passes its own `viewports`. Overcast banks deliberately use shorter spans so their source masses remain screen-dominant.
- Dense dry fog suppresses both cloud families and relies on its own fog base plus drifting veil tiles. Precipitation instead retains full-strength banks and disables the dry population. Fog uses broad, elongated, heavily blurred bands, never recognizable cumulus silhouettes; its layers share one prevailing drift with parallax and their tint follows the day phase.
- Overcast bank sources are decoded once during prewarm and then transformed, tinted, and alpha-scaled without rerasterizing their source pixels. Do not generate cloud masks from `renderForeground` / `drawCloudDrift`, and do not use artwork from the reference APK or third parties.

### Cloud Coverage Belongs to Placement, Never to Paint Alpha

When a provider supplies vertical cloud layers, low cover drives the near cumulus population and its sun-edge sampling, mid cover drives the distant deck, and the stronger of low/mid cover drives opaque-sky effects such as the overcast ceiling, haze, and direct-sun obstruction.
High cover is not an opaque-cumulus signal; it drives the dedicated sparse, scattered, and broken cirrus populations, which are generated reproducibly and draw behind lower cloud layers.
When a provider supplies only total cover, preserve the legacy single-scalar rendering path rather than inventing layer values.

How much cloud a clear sky holds is chosen by nested sparse, scattered, partly cloudy, and broken populations, never by scaling one fixed deck's alpha.
Alpha scales an entire cloud body uniformly, so raising it cannot add a cloud; it can only make the same silhouette less transparent.
A sunlit crown that cannot reach white reads as haze no matter how the placement is tuned.

The corollaries:

- `CUMULUS_NEAR_ALPHA` remains 248; established bodies retain material opacity multiplied by the user's `params.cloudScale`. Only initial birth and incoming additions/banks use coverage-dependent weights.
- `SceneRenderer` resolves its injected epoch-day source once at foreground entry and caches one immutable `NearCloudLayout` containing 32 ordered placements. Sparse/scattered/partly/broken use prefixes of 8/12/20/32; changing coverage or preferences must not reseed or move existing bodies.
- Draw the established prefix with `FULL` scope and only the incoming suffix with `ADDITIONS`. Drawing, cast-shadow sampling, and sun obstruction must consume the same frame-local layout, geometry, size, scope, and alpha. `CloudLayer` has no clock or per-profile near-layout cache; its far cache uses the supplied snapshot's epoch.
- Near morphology variants share four hero sources and cycle through neighboring variants before repeating. Portrait bodies span multiple heights; landscape retains its height-constrained sizing.
- Near-population progress is continuous over low cover 0.10..0.75, with smooth initial birth over 0.10..0.20. Dry banks grow over the retained population over opaque cover 0.70..0.85; never fade the two families in opposite directions or restore the old blend floor/late plateau.
- The far deck carries distance through haze, size, and dedicated veil sprites and thickens with mid cover.
- `CloudLighting` applies an RGB-only affine material grade: low sun warms illuminated material while keeping shadows cool, night uses subdued blue-gray, and day preserves baked cloud RGB. Contrast around RGB 128 precedes multiplicative depth/weather/cast-shadow tint; all transforms preserve source alpha exactly.
- Cache the material grade by phase/progress and reuse matrix/filter/style state for unchanged inputs. The daylight overcast material base receives phase lighting once; cirrus keeps its separate phase tint with the identity grade. Never traverse or regenerate source pixels to grade clouds.
- Sky palette, brightness, and saturation customize the clear phase gradient before overcast grayness and storm darkening. `NATURAL` at 100% brightness/saturation must preserve the established rendering exactly, and stylized palettes must still yield to weather transforms.
- A dry sky keeps its full clear-day blue until `OVERCAST_GRAY_FLOOR`, where the overcast ceiling starts drawing. Graying earlier leaves white clouds with nothing to read against.

### Preview Cloud Work Before Building

`uv run scripts/preview-clear-sky.py [out.png]` renders an aspect-correct contact sheet covering the portrait ladder, simulator 0.70/0.85 inputs, and independent cloud layers.
Use `--phase {dawn,day,dusk,night} --progress FLOAT --cover FLOAT` for one full-size portrait; existing `--cloud-size` and `--cloud-count` flags remain available.

It is a design aid, not a test: mirror Kotlin's material grade, nested populations, geometry, opacity, and overcast handoff into it. Its nominal anchors and representative morphology intentionally differ from Android's seeded daily jitter. Kotlin and actual Android surface observation are authoritative.
- Fog retains downscaled scrolling veil tiles pre-rendered into the `tiles` map and blitted with alpha and motion; generation happens only on cache misses, never per frame.
