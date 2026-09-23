# AGENTS.md

Instructions and architectural invariants for agents working in the Weatherd codebase.

- Version: 1.6.5 (2026-09-23)
- Persona: Coding assistant pair-programming with the user on Weatherd.

## Tools and Environment

Agents use standard file inspection, editing tools, and Gradle tasks (`./gradlew check`).
- The JDK is installed via SDKMAN.
- Before Gradle tasks in non-interactive shells, source `"$HOME/.sdkman/bin/sdkman-init.sh"`.

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
- Cloud decks use `CloudLayer`; fair-weather cumulus draws shared transparent source sprites, while overcast sheets are generated once per lazy layer. Their transforms, opacity, and day-phase tints change without rerasterizing the source pixels.
- `scripts/generate-cloud-textures.py` reproducibly regenerates the procedural cloud textures it owns with `uv run scripts/generate-cloud-textures.py` and samples no third-party artwork. Runtime cloud rendering has two families:
	- `cloud_sheet_far` / `cloud_sheet_near` identify the overcast and precipitation decks drawn by `drawCloudDrift`; `CloudLayer` procedurally builds each deck once on first use from deterministic multi-scale noise and reuses the resulting bitmap afterward.
	- `cloud_cumulus_sparse` / `_scattered` / `_broken` select the near clear-sky placement profiles drawn by `drawScatteredClouds`, and `cloud_cumulus_far` selects the distant profile. The near profiles share the four `cloud_cumulus_hero_*` source sprites and synthesize additional morphology variants by scaling and composing them; the far profile shares the `cloud_cumulus_far_veil_*` sprites.
- A cloud deck spans `CLOUD_TEXTURE_VIEWPORTS` viewport widths before repeating unless it passes its own `viewports`. A shorter span shrinks the apparent spacing and feature scale.
- Overcast depth comes from three separate spatial scales: large cloud bodies define the silhouette, medium billows shape the volume, and fine turbulence only breaks up the surface. The two large body fields merge smoothly; never carve a boundary from which field is dominant or from how similar their strengths are, because that exposes contour-like handoff seams. A separate low-frequency gap field may thin cloud shoulders while protecting dense cores. Overcast lighting is broad density-and-billow shading: do not subtract opposite offset samples or whiten low-alpha edges, because those operations behave like an emboss or edge-detection filter and produce bright contour halos. Do not derive overcast geometry from the old streak textures or paste fair-weather hero sprites into the deck; generation stays one-time and per-frame rendering remains a normal bitmap-shader draw.
- Procedural overcast generation must never start from `renderForeground` / `drawCloudDrift`. Owners prewarm it on a background dispatcher; if the cache is not ready yet, the frame skips the animated sheet instead of blocking the render thread.

### Cloud Coverage Belongs to Placement, Never to Paint Alpha

How much cloud a clear sky holds is chosen by the sparse, scattered, and broken placement profiles and by cross-fading the newly introduced population, never by scaling one fixed deck's alpha.
Alpha scales an entire cloud body uniformly, so raising it cannot add a cloud; it can only make the same silhouette less transparent.
A sunlit crown that cannot reach white reads as haze no matter how the placement is tuned.

The corollaries:

- `CUMULUS_NEAR_ALPHA` stays near 255. `params.cloudScale` is the user's opacity preference and is the only scene-level control that should pull it down.
- Near morphology variants come from one shared four-bitmap hero source set. Prefer transformed or composite variants of that set before adding another decoded bitmap family; the seeded daily layout cycles through neighboring morphology variants before repeating so one coverage profile does not randomly collapse back to one shape.
- Sparse, scattered, and broken describe placement populations. Cross-fading draws the lower population at full alpha and grows the next population above the blend floor; do not replace that with a single population whose alpha tracks cloudiness.
- The far deck is the exception because distance is carried by haze, size, and a dedicated pair of veil sprites; it also thickens with coverage.
- The hero sprites bake their own sunlit-to-shadow ramp, so `cumulusTint(DayPhase.DAY)` is pure white. Tinting daylight applies the shading twice and grays the crowns.
- A dry sky keeps its full clear-day blue until `OVERCAST_GRAY_FLOOR`, where the overcast ceiling starts drawing. Graying earlier leaves white clouds with nothing to read against.

### Preview Cloud Work Before Building

`uv run scripts/preview-clear-sky.py [out.png]` renders the clear-sky decks over the sky gradient on the desktop, so a texture or tuning change can be judged in seconds instead of a build-and-install round trip.

It is a design aid, not a test: it re-implements a slice of `SceneRenderer` in Python and will drift unless the deck geometry, alpha ramp and tint are mirrored into it whenever the Kotlin changes. Where the two disagree, the Kotlin is right.
- Fog retains downscaled scrolling tiles pre-rendered into the `tiles` map and blitted with alpha and motion.
