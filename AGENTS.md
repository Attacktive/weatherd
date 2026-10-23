# AGENTS.md

Instructions and architectural invariants for agents working in the Weatherd codebase.

- Version: 1.6.19 (2026-10-23)
- Persona: Coding assistant pair-programming with the user on Weatherd.

## Documentation Scope

Keep `README.md` focused on the app overview, installation, basic builds/checks, and public attribution.
Put durable implementation constraints and developer procedures here, merging with existing sections instead of appending a feature-by-feature change history.
Document shared preview/live-wallpaper/simulator/fallback behavior once; individual features need only their exceptions and compatibility rules.

## Tools and Environment

Agents use standard file inspection, editing tools, and Gradle tasks (`./gradlew check`).
- In the current setup, `connectedDebugAndroidTest` removes the target Weatherd installation during teardown (observed on Pixel); use it only on a disposable device or emulator.
- To preserve an existing installation, build the app and test APKs, install both with `adb install -r -t`, and run `adb shell am instrument -w xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner` directly.
- Direct instrumentation still force-stops the app and its live-wallpaper service. On the emulator, this left a dead wallpaper binding and a black home screen even though the preview worked; selecting the same Weatherd component was ignored. Run instrumentation before final wallpaper validation, and recover a stranded binding by selecting a different wallpaper before reapplying Weatherd.
- Back up app data separately from the APK before any test workflow that removes packages; saving the APK does not preserve settings or photos.
- On API 34+, clear `UiAutomation`'s accessibility cache before polling navigation state. At the 320×640 CI viewport, scrolling visibly revealed a tab while cached nodes still described its old off-screen position; waiting for idle alone did not refresh those nodes.

### Local Checks and Instrumentation

Run the pull-request checks with `./gradlew test :app:lint :app:detekt`.
The existing CI Test gate builds the debug app and instrumentation APKs alongside unit tests, lint, and Detekt while the emulator starts; instrumentation remains required after that gate succeeds.
For an existing emulator installation, update both APKs in place and invoke instrumentation directly:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner
```

Replace `emulator-5554` with the intended device serial; the data-backup and wallpaper-binding warnings above still apply.
Settings navigation tests wake the device, dismiss a nonsecure keyguard, and retain a bounded 320×640 dp activity window through recreation so large displays exercise scroll restoration rather than skip it.
Navigation timeouts include activity state, the active accessibility tree, and window-manager diagnostics; assert the saved anchor remains visible with the first section off-screen, not exact pixel coordinates.
Sunbeam fixtures pin the UTC cloud-placement day and compare rays over the same unobscured pixels each frame so foreground cloud occlusion is not mistaken for a ray-field discontinuity.

### Automated Station Evaluation

The local harness uses the real Android provider-comparison service and report formatter on a dedicated emulator, without changing release behavior or provider defaults.
Requires Python 3.10+, the Android SDK, and debug app/test APKs.
The configured SDK directory is trusted local developer configuration; only allowlisted `adb` and emulator binaries execute, without a host shell.
Use a verified Italian station's exact published coordinates via the existing manual-location settings (`MANUAL_STATION`), not a nearby city center.
Station IDs contain four ICAO letters, such as `LIRF`; lowercase is normalized before fixed-host HTTPS observation requests.
GPS, device-location permissions, and an onsite tester are not required; keep the simulator disabled and **Tester observed: UNSURE**.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/evaluate-providers.py clone --source Pixel
python3 scripts/evaluate-providers.py boot --port 5556
python3 scripts/evaluate-providers.py install --serial emulator-5556
python3 scripts/evaluate-providers.py capture --serial emulator-5556 --station LIRF
python3 scripts/evaluate-providers.py collect --serial emulator-5556 --station LIRF --count 0
```

- The headless clone is `Pixel_Weatherd_Provider_Evaluation`; preserve the source AVD hardware/system-image configuration but start with fresh app data, never copied userdata, snapshots, accounts, or photos.
- Creation retains the pristine source config, generated config/registry, and creation ledger under `captures/provider-evaluation/emulator/`.
- Refuse existing AVD targets rather than replacing them; mutating device commands require an explicit emulator serial whose AVD name matches the evaluation clone, never the original `Pixel` or physical devices.
- Choose a free even port if `5556` is occupied and use its corresponding serial throughout.
- `collect` runs in the foreground at fixed UTC boundaries, every 30 minutes by default; keep the host and clone running and stop with Ctrl+C, without a login service or concurrent collectors on the same clone.
- `--count 1` runs one scheduled capture; `--interval-seconds` changes cadence; scheduled runs default to `ROUTINE`, while one-off `capture` defaults to `DIAGNOSTIC`, excluded from summaries.
- Each unique `captures/provider-evaluation/runs/` directory retains unchanged `report.txt`, all provider statuses, `capture.json`, station metadata/source/retrieval time, instrumentation output, and observation/match revisions, including failed-collection diagnostics.
- Store public station coordinates separately from the privacy-preserving original report; upload nothing automatically.
- Observation-fetch failures or missing time matches leave runs `PENDING`; retry on the next scheduled tick without replacing the original report.
- Bound matching to 48 hours after capture or 96 total attempts, including the initial attempt; append an `EXPIRED` revision with the last pending reason/count when exhausted, or immediately when outside the observation API's 30-day recent-retention window.
- Never fetch or rewrite expired matches; explicit rematching of previously matched captures can pick up corrections within the same bounds.
- A failed, missing, or bounded optional rematch preserves the usable `MATCHED` revision and scores; record it separately in `rematch-*.json`, using `SKIPPED` for bounds and the attempted match status otherwise, without requeueing it.
- Successful rematches may replace the scored revision, including corrected measurement eligibility.

```sh
python3 scripts/evaluate-providers.py match captures/provider-evaluation/runs/<run-directory>
python3 scripts/evaluate-providers.py summarize
PYTHONPATH=scripts python3 -m unittest discover -s scripts/provider_evaluation -p 'test_*.py'
```

Observations come from the [NOAA Aviation Weather Center API](https://aviationweather.gov/data/api/); retain raw METAR/SPECI records and use an inclusive ±15-minute tolerance with signed offsets.
Exact distance ties prefer the earlier observation independently of response order, for both capture-time and forecast-valid-time matches; keep those two comparisons separate.
Accuracy summaries deduplicate routine station/observation pairs and use shared eligible paired denominators against ItaliaMeteo.
Provider availability is successful samples divided by all obtained samples for that provider in eligible routine captures, without observation deduplication.
Report routine collection success separately as completed collections divided by all routine attempts, including failures before provider samples; incomplete collections are not successes.
Emitted `scoring_rules` freeze the pilot cloud-category mapping: automated or ambiguous sky reports are unscorable, never clear, and forecast trends are not observations.
Corrected reports require resolvable revision ordering; cloud categories remain unscorable because `COR` can replace the automation flag, while eligible temperature observations remain usable.
Evaluate temperature separately; cloud heights are not low/mid/high cover measurements, precipitation amounts are not scored, and descriptive results do not establish a provider ranking.
The instrumentation collector skips ordinary suites unless explicitly opted in by the harness.

### Google Play Vitals Monitoring

The existing `.github/workflows/google-play-vitals.yaml` runs daily at 00:37 UTC and supports `workflow_dispatch`.
It retrieves crash/ANR clusters using [Attacktive/google-play-vitals-action](https://github.com/Attacktive/google-play-vitals-action), comparing against the previous successful `play-vitals-snapshot` artifact; missing or expired artifacts establish a baseline without false-positive alerts.
Each run writes a job summary and a JSON report retained for 90 days; it does not file issues or dispatch notifications.

Prerequisites:

1. Enable the Google Play Developer Reporting API.
2. Give the Google Cloud service account **View app information (read-only)** permission in Play Console.
3. Store its JSON key as `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, reusing the deployment secret.
4. Enable the IAM API and IAM Service Account Credentials API in the service account's project.
5. Grant the account `roles/iam.serviceAccountTokenCreator` on itself per the [authentication action's access-token setup](https://github.com/google-github-actions/auth/tree/7c6bc770dae815cd3e89ee6cdf493a5fab2cc093#inputs-service-account-key-json).

### Roadmap Updates

- When finishing work on an issue, update the [Weatherd roadmap](https://github.com/Attacktive/weatherd/issues/194) to reflect the issue’s completion and any resulting roadmap changes before considering the work complete.

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
	- Never squash, rebase, or create a merge commit via GitHub's merge options; local squashing or rebasing of feature branches and force-pushing to the pull request branch is permitted when requested, provided the merge into `main` remains a fast-forward.
	- Afterward, verify that GitHub reports the pull request merged, `main` points at the former pull-request head, and the merged commits still report valid verification.
	- If a fast-forward is impossible, stop and report it rather than rewriting signed commits.
- Release immediately after the merge:
	1. Read `versionCode` and `versionName` from `app/build.gradle.kts`.
	2. Unless the user specified a version or a different semantic-version bump, increment `versionCode` by one and increment the patch component of `versionName` by one.
	3. Commit only that version bump directly to `main`, using the global version-bump exception to the pull-request rule.
		- Use the commit message `chore: bump version to <versionName>`.
		- Include the active model's required `Co-authored-by` trailer.
		- Verify the commit has a valid GitHub signature.
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

### Settings Navigation Is UI State

Settings opens on Appearance, followed by Weather, Wallpaper, and Advanced.
Appearance categories have their own titles and hide the top-level tab strip; toolbar and system Back return to the hub before leaving Settings.
Keep the keyed `SaveableStateHolder` owned by `SettingsScreen` so the hub and every category retain independent scroll positions across visits and recreation.
Measure the header and page together, and wait for the view's first pre-draw before composing restorable page content: transient header or zero-inset viewports otherwise clamp saved scroll offsets before the final viewport is known.
Keep category summaries to one or two high-signal settings facts, localized consistently, rather than serializing every control.
Category regrouping must not change stored setting keys, defaults, reset scope, preset membership, simulator behavior, or the shared scene-provider path.
Weather/Wallpaper internals stay in place unless an actual setting move requires touching them; MET Norway attribution belongs with Weather.

Appearance categories are Scenery & photos (backdrops, conditional photo slots, Trees/shape), Sky (palette, brightness/night brightness, saturation, aircraft, birds/interval), Clouds (size/count/opacity/contrast), Sun & moon (visibility, styles, sunbeams, sizes, sun color, optical effects), Weather effects (precipitation, glass droplets, wind), On-screen text (visibility/position/unit), Readability (dimming/status-bar shade), and Presets (save/apply/rename/replace/delete).
Weather owns location, providers, and refresh; Wallpaper owns motion/performance; Advanced owns the simulator.

### System Appearance and Display Compatibility

The app follows system light/dark appearance without an app-theme preference; this must not change scene lighting, simulator phase, or saved appearance settings.
Keep icons over the edge-to-edge scene light and opaque navigation bars consistent with system appearance.
Preview controls avoid system bars and cutouts, including landscape side cutouts and tall top insets, without insetting the weather scene.
Display settings apply through the shared scene path and are saved in appearance presets.
Preserve independent resets and missing-field defaults: Classic sun, Through clouds sunbeams, Natural palette, Moon size 100%, Trees off with Evergreen shape, and readability strengths off.
Tree shape and bird interval remain selected when their parent toggles hide those controls.
New preset writes use schema v13; v1–v12 remain readable, while older builds reject the new bank rather than silently dropping appearance settings.

### On-Screen Text Position

Show the vertical-position control when either weather or location text is enabled; move visible lines together without changing horizontal alignment, font sizes, or spacing.
Map 0% to the block's top edge at the drawable top and 100% to its bottom edge at the drawable bottom, adapting bounds to the visible lines.
Reset/unset values use the 9% compatibility anchor, preserving the historical weather baseline at 9% of scene height and location baseline at 12.1%, even with only one line enabled.
Travel is continuous and linear on either side of the anchor, not uniformly spaced across the whole height; retain stored numeric percentages and the historical placement for presets missing the field.
Simulation honors visibility/position, showing the simulated condition without an invented temperature and the selected or resolved real location.

### Weather Acquisition and Refresh Widget

The 15-minute–6-hour refresh interval is a minimum between automatic fetches, not a background timer.
Check on preview resume, wallpaper visibility, and relevant location/provider changes; **Refresh now** and the widget bypass the interval.
Regional model selection uses published grids and the explicit Open-Meteo model identifier; outside coverage or after primary failure, make at most one request to the configured global fallback without rewriting either preference.
Cache the provider that actually returned the weather.
The widget queues network-constrained work: acknowledge a tap immediately, animate progress when accepted work starts, then show a brief ✓ for completed refresh or ! for incomplete refresh, including unavailable location/final provider failure.
Offline requests wait for network; **Settings → Weather → Last updated** reports refresh time even if the scene looks unchanged.
After app updates, republish the refresh action without opening the app or forcing a fetch; ignore initialization-time taps rather than launching the preview.

### Experimental Provider Comparison

**Settings → Weather → Compare weather providers** queries Open-Meteo Best Match, ItaliaMeteo ICON-2I, DWD ICON EU, and MET Norway at the same effective location only after **Compare now**; users can select their observed sky.
Bypass normal primary/fallback selection without changing preferences; skip providers outside coverage and show returned forecast-valid time, condition, cloud layers, temperature, and precipitation.
Label accumulation windows: Open-Meteo's interval precedes its timestamp, MET Norway's next-hour precipitation follows it, and missing interval metadata stays unknown.
Dismissal cancels sampling; normal app results are not persisted, automatically uploaded, or placed in the normal weather cache.
**Share report** opens the Android chooser with plain text and the tester observation, excluding coordinates and place names.
Coordinates necessarily go to queried providers; preserve that disclosure and the privacy-policy guidance.

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

The simulator state is persisted through `SettingsRepository` and observed independently of location/weather acquisition in `WeatherSceneProvider.sceneState`.
`WeatherSceneState` publishes immutable settings, cached weather, photo revision, and location-label inputs; both surfaces derive their scene through `paramsFor` from a single captured state.
Disabling simulation selects cached weather or the normal clock-lit fallback without waiting for a refresh.
Without matching weather, use locally calculated approximate sunrise/sunset and celestial progress for the selected city or known device fix and date, including polar day/night.
Only unavailable/invalid locations use coarse device-local clock timing; valid provider solar times and explicit simulation retain precedence.
Weather acquisition remains separate so slow or failed requests cannot block mode switching or replace cached real weather with simulated conditions.
Cached snapshots are rendered only when `snapshotTarget` matches the effective location source, manual or known device coordinates, and requested primary/fallback provider configuration in the captured settings.
The actual provider attribution in `WeatherSnapshot.source` may differ from that requested configuration after fallback; never use actual attribution as the cache-target key.
Manual location labels derive from the captured settings; geocoded labels carry `locationLabelTarget` and are shown only for a matching location source.
`deviceLocation` captures the most recently known device fix; if a simulator status refresh observes a different point, the old snapshot is hidden and its refresh interval cannot throttle acquisition for the new point.
The preview reacts to state emissions, and the wallpaper invalidates its parameter cache when the state identity changes; unchanged inputs retain the once-per-second clock update.
Never put weather I/O inside the simulator-settings mutation lock or a settings collector.
Overlapping ordinary refreshes for the latest weather/location target share application-scoped work; changing targets or starting a forced refresh prevents reuse of obsolete work.
Explicit forced refreshes retain their existing independent request-order guards.
Simulator button actions are relative intents evaluated against the latest persisted settings inside the mutation lock, not absolute values captured by the composable.
`PhotoBackgroundRepository.state` publishes usable bucket availability and the render-invalidating revision together after a completed file mutation; consumers must not recreate independently published photo state.
Only an unfinished celestial-progress slider gesture stays local to the preview; committing it updates the shared selection.
To prevent the simulator and live weather paths from drifting, whenever any new render parameter, intensity multiplier, scene setting, or display behavior is introduced:

1. Add it to `SceneParams` and `sceneParamsFor`.
2. Wire it into `WeatherSceneProvider` for live, simulated, and fallback parameters, using the settings in the captured `WeatherSceneState`.
3. Wire it into `debugSceneParams` in `SceneDebugPresets.kt` (unless deliberately intended as debug-only).
4. Keep `HomeScreen` consuming the shared provider parameters rather than assembling a second simulator scene or mirroring individual display settings in `HomeViewModel`.
5. Add unit tests in `SceneDebugPresetsTest` and `WeatherSceneProviderTest` asserting that the setting reaches the generated `SceneParams`.

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
- `SceneScreenEffects` draws once after restoring world translation and completing backdrop crossfades: glass droplets, readability overlays, then weather/location labels. Both production surfaces suppress foreground labels and use this shared viewport-sized pass so screen effects do not scroll or double-composite.
- Readability strengths are opt-in display settings shared by Home/Lock, preview, simulator, live weather, and fallback. `wallpaperDimmingStrength` and `statusBarShadeStrength` normalize to 0–0.6, with non-finite values resolving off. Uniform black dimming covers the completed scene; the additional top gradient fades out over 18% of visible height. Cache its shader by height, preserve label brightness, and exclude both strengths from `backdropSignature`.
- `LightningBoltLayer` builds deterministic, midpoint-displaced channels with tapered filled cores and branches rooted on parent vertices. It caches geometry and an immutable downscaled software-blurred bloom by strike seed and surface dimensions; per-frame drawing changes only flash opacity. Keep blur generation out of steady-state frames and preserve `SceneRenderer`'s shared strike schedule, echoes, sheet lightning, and rain illumination.
- `MoonLayer` prepares only the requested quantized lunar phase off the rendering thread, including source decoding, phase-image synthesis, and the bounded phase-alpha samples used for star compensation. Render callbacks capture an atomically published immutable prepared phase and never construct a phase image or wait on its preparation lock; an unprepared phase draws neither a stale disc nor a lunar halo. Keep retained phases bounded without recycling a bitmap a frame may still reference, and invalidate both Home and the wallpaper on preparation completion even when animation is settled.
- `MoonOcclusionLayer` gives the lunar surface spatial transmission rather than whole-disc dimming. Rasterize the existing scattered-cloud draw path into its reusable, bounded moon-local buffer; dense overlap hides lunar detail while clear gaps remain bright and thin edges stay translucent. Keep the halo's phase-weighted attenuation independent. Mask intersecting stars and meteors in their own small layers before drawing the transmitted moon, compensating only for opacity removed by clouds so lunar phase alpha is never applied twice at uncovered edges. Obscured pixels must reveal the existing sky rather than a black disc or additional background stars. Never introduce a full-screen star layer or per-pixel cloud-geometry sampling loop. Invalidate stationary masks for changed scene inputs, geometry, daily cloud layout, and completion of cloud-bank prewarming.
- Cloud decks use `CloudLayer`; fair-weather cumulus uses seeded placement populations, while overcast uses the dedicated `cloud_overcast_hero`, `cloud_overcast_support`, and `cloud_overcast_veil` sources.
- `scripts/generate-cloud-textures.py` reproducibly regenerates the procedural clear-sky cloud textures it owns with `uv run scripts/generate-cloud-textures.py` and samples no third-party artwork. The dedicated overcast sources are first-party authored assets and are not derived from the reference APK.
	- `cloud_cumulus_sparse` / `_scattered` / `_broken` select the resource-backed near clear-sky placement profiles drawn by `drawScatteredClouds`, while a dedicated in-between partly-cloudy profile reuses the same hero sprites without adding another decoded bitmap family. At high clear-sky coverage, one low-alpha `cloud_overcast_hero` bank is reused behind that profile as a broad transitional mass; it must not enable the overcast ceiling, tint, or bank stack. `cloud_cumulus_far` selects the distant profile. The near profiles share the four `cloud_cumulus_hero_*` source sprites and synthesize additional morphology variants by scaling and composing them; the far profile shares the `cloud_cumulus_far_veil_*` sprites.
	- `drawCloudDrift` composes overcast from a far veil, a support bank, one screen-dominant hero bank, and a low bridge veil. At least one visible bank must span most of a portrait viewport, and the combined bank field must reach well into the mid-sky so overcast reads as a ceiling rather than floating fair-weather puffs. Preserve the dedicated assets' 3:1 proportions instead of vertically stretching them; bank height is capped to 55% of the surface in wide viewports.
- A cloud deck spans `CLOUD_TEXTURE_VIEWPORTS` viewport widths before repeating unless it passes its own `viewports`. Overcast banks deliberately use shorter spans so their source masses remain screen-dominant.
- Dense fog without precipitation suppresses the animated overcast banks and relies on its own fog base plus drifting veil tiles instead. Fog uses broad, elongated, heavily blurred bands at multiple heights, never recognizable cumulus silhouettes; its layers share one prevailing drift with parallax instead of counter-scrolling like smoke, and their tint follows the day phase.
- Overcast bank sources are decoded once during prewarm and then transformed, tinted, and alpha-scaled without regenerating their source pixels. Do not generate source cloud artwork or source masks from `renderForeground` / `drawCloudDrift`; the bounded lunar transmission buffer only composites already-decoded artwork. Do not use artwork from the reference APK or third parties.

### Humidity Haze Is Drawing Strength, Not Fog Semantics

Provider relative humidity is optional weather data. Missing or non-finite humidity must behave exactly like no humidity haze.
`SceneParams.fogDensity` remains reserved for provider-reported fog and continues to drive all semantic fog behavior such as palette, cloud, celestial, visibility, and optical decisions.
`SceneParams.humidityHazeDensity` is a separate derived overlay: it is zero through 85% relative humidity, rises smoothly above that threshold, and is capped at 0.25 at 100% or higher.
Provider-reported fog takes precedence and forces humidity haze to zero, so the shared fog layers never stack.
`effectiveFogDensity()` combines those two values for drawing the existing fog base and drifting veil geometry only. Never feed that combined drawing strength back into weather classification or any semantic `fogDensity` consumer.
Backdrop invalidation follows the derived `humidityHazeDensity`, not raw humidity: humidity changes below the onset threshold must not invalidate a static backdrop, while changes that alter the derived haze must invalidate it.
The `HUMID HAZE` simulator preset stays at the end of `SCENE_PRESETS` so persisted preset indices remain stable.

### Cloud Coverage Belongs to Placement, Never to Paint Alpha

When a provider supplies vertical cloud layers, low cover drives the near cumulus population and its sun-edge sampling, mid cover drives the distant deck, and the stronger of low/mid cover drives opaque-sky effects such as the overcast ceiling, haze, and direct-sun obstruction.
High cover is not an opaque-cumulus signal; it drives the dedicated sparse, scattered, and broken cirrus populations, which are generated reproducibly and draw behind lower cloud layers.
When a provider supplies only total cover, preserve the legacy single-scalar rendering path rather than inventing layer values.

How much cloud a clear sky holds is chosen by the sparse, scattered, and broken placement profiles and by cross-fading the newly introduced population, never by scaling one fixed deck's alpha.
Alpha scales an entire cloud body uniformly, so raising it cannot add a cloud; it can only make the same silhouette less transparent.
A sunlit crown that cannot reach white reads as haze no matter how the placement is tuned.

The corollaries:

- `CUMULUS_NEAR_ALPHA` stays near 255. `params.cloudScale` is the user's opacity preference and is the only scene-level control that should pull it down.
- Near morphology variants come from one shared four-bitmap hero source set. Prefer transformed or composite variants of that set before adding another decoded bitmap family; the seeded daily layout cycles through neighboring morphology variants before repeating so one coverage profile does not randomly collapse back to one shape.
- Sparse, scattered, partly cloudy, and broken describe placement populations. Cross-fading draws the lower population at full alpha and grows the next population above the blend floor; do not replace that with a single population whose alpha tracks cloudiness.
- The far deck is the exception because distance is carried by haze, size, and a dedicated pair of veil sprites; it also thickens with coverage.
- The hero sprites bake their own sunlit-to-shadow ramp, so `cumulusTint(DayPhase.DAY)` is pure white. Tinting daylight applies the shading twice and grays the crowns.
- Low-sun cloud lighting uses luminance-aware RGB grading (`CloudColorGrade`) at the `ColorMatrix` boundary during dawn and dusk, providing warm cream/golden highlights and cool slate-blue shadows without altering source or rendered alpha.
- Day and night retain their restored appearance with identity grading; base multiply tint smoothly transitions between night blue and daylight white without flat peach/orange overlays.
- Dry overcast banks receive phase grading, while rainy, snowy, thundery, dense-fog, and cirrus paths preserve identity treatment.
- Cloud contrast and material grading are RGB-only transforms on cloud artwork. They must preserve the source alpha mask exactly and stay independent of cloud opacity or coverage.
- Sky palette, brightness, and saturation customize the clear phase gradient before overcast grayness and storm darkening. `NATURAL` at 100% brightness/saturation must preserve the established rendering exactly, and stylized palettes must still yield to weather transforms.
- A dry sky keeps its full clear-day blue until `OVERCAST_GRAY_FLOOR`, where the overcast ceiling starts drawing. Graying earlier leaves white clouds with nothing to read against.

### Preview Cloud Work Before Building

`uv run scripts/preview-clear-sky.py [out.png]` renders the clear-sky decks over the sky gradient on the desktop, so a texture or tuning change can be judged in seconds instead of a build-and-install round trip.

It is a design aid, not a test: it re-implements a slice of `SceneRenderer` in Python and will drift unless the deck geometry, alpha ramp and tint are mirrored into it whenever the Kotlin changes. Where the two disagree, the Kotlin is right.
- Fog retains downscaled scrolling veil tiles pre-rendered into the `tiles` map and blitted with alpha and motion; generation happens only on cache misses, never per frame.

### Scenery and Tree Artwork

Trees are an independent fixed foreground composition over any backdrop, including None and Photo, not a backdrop or seasonal landscape.
Evergreen uses the multi-tree conifer composition; Broadleaf uses an inset left hero and a smaller independently grown right companion, leaving the upper-middle sky open.
Keep the hero crown fully visible in portrait, landscape, and launcher scrolling without reducing scale; both trunks remain grounded.
Mountains and both tree shapes share weather/day-phase lighting: preserve clear-day artwork colors, warm dawn/dusk continuously, desaturate overcast/precipitation, keep snow lighter than rain, and soften distant mountains in fog more than nearby foliage.
At night, reduce mountain-blue saturation while retaining ambient foliage detail; night brightness dims both, easing through dawn/dusk without changing alpha or placement.
Do not per-pixel grade custom photos; retain their weather overlays/night dimming and grade any trees over them normally.
Fog and precipitation draw in front.
Regenerate first-party sources with `uv run scripts/generate-tree-textures.py` and `uv run scripts/generate-broadleaf-textures.py`; Broadleaf authors separate trunk/branch/canopy structures and supports `--variant hero` or `--variant companion`.
Decode only the selected style on first use off the frame loop; never copy reference-app or third-party artwork.

### Sun, Moon, and Optical Effects

- Classic is procedural; Radiant uses the [unchanged submitted PNG](https://github.com/user-attachments/assets/68a92bcf-016d-4276-bc35-4c1e38ae6b78) credited in the README; Starburst is first-party artwork inspired by [#291](https://github.com/Attacktive/weatherd/issues/291), not sampled from its iOS reference.
- All sun styles honor visibility, size, color, weather veiling, and sunset fade; natural daylight uses a white source, while low sun and explicit colors retain their tint.
- Prepare Radiant's immutable softened texture once on first use off the frame loop, preserving its bright core/short rays but fading outer haze and distant baked reflections before the boundary; keep extra wide halos restrained.
- Generate Starburst with `uv run scripts/generate-starburst-sun.py`: broad overlapping luminous lobes and faint uneven streaks, not a pointed star; fade before the texture boundary and decode only when selected, off the frame loop.
- Never bake rainbow reflections into Starburst; Lens flare controls the separate arc/reflections and Motion-responsive reflections controls motion independently.
- Generate the north-up near-side Moon with `uv run scripts/generate-moon-texture.py` from unchanged `scripts/assets/moon/lroc_color_2k.jpg`, checking its SHA-256 before projection; retain NASA attribution from the README.
- The image in [#283](https://github.com/Attacktive/weatherd/issues/283) is reference only; waxing/waning changes illumination without mirroring markings, and new Moon draws neither disc nor halo.
- Moon size is independent, 50–200%, default 100%; change draw geometry without regenerating phase sprites or the static backdrop.
- Sunbeams offers Off, Through clouds (default), and Clear skies too; moving near low-cloud openings generate soft spreading shafts, never distant openings.
- Clear skies too adds source-fixed radial glare; low/mid cover attenuates it but high cirrus alone does not.
- Night, hidden sun, precipitation, fog, thunder, and opaque decks suppress enabled sunbeams; Off removes shafts/extended glare but preserves the selected sun, compact corona, and independent lens flare.
- Cache cloud-shaft softness in a small first-party sprite, transforming each frame without texture generation or blur.
- Generate shared clear-sky glare with `uv run scripts/generate-sun-glare.py`: a hexagonal aperture's squared Fourier amplitude convolved with a finite solar disc and averaged over three wavelengths.
- Follow [aperture diffraction](https://www.nature.com/articles/s41598-018-34400-z) rather than swaying/pulsing cloud shafts in clear air; tuned exposure/tint/falloff is an idealized optical approximation, not a calibrated camera.
- Clear blue uses a rich blue zenith and pale horizon, inspired by [#252](https://github.com/Attacktive/weatherd/issues/252); retain natural sunrise/sunset/night and cloudy/stormy precedence.
- Lens flare draws one restrained feathered prismatic arc inspired by [#290](https://github.com/Attacktive/weatherd/issues/290), with an open upper edge and low-sun fade, not stacked rainbow rings.
- Keep the band compact and spectrally colored, with subdued reflection circles/horizontal streak; use translucent blending to tint blue sky rather than additive whitish haze.
- Fade the arc/reflections with moving near-cloud solar occlusion even with Sunbeams Off; suppress for night, hidden sun, precipitation, fog, thunder, and heavy opaque cover; Lens flare Off removes all procedural rainbow treatment.
- Keep all four reflections above the lower quarter during supported tilt in portrait/landscape; compress spacing near the boundary without hiding the farthest circle or changing arc geometry.

### Sky Wildlife and Aircraft

Birds offers Off, Small flock (3–5), Medium (6–8), and Large (9–12); Bird interval offers Natural, 30 seconds, 1 minute, 2 minutes, 5 minutes, and 10 minutes.
Small + Natural retains the restrained randomized schedule; explicit intervals cause one crossing each interval without random skips.
Birds remain fair-daylight only, suppressed by rain, fog, opaque cover, or night; schedule on the shared scene animation clock without background alarms or another animation lifecycle.
Distant aircraft/contrails cross in front of every sun style; clouds can obscure the body and lights together.
The Distant aircraft toggle also enables fair-weather night flights: faint silhouette, red port/green starboard navigation lights that follow heading, white tail light, red beacon, brief white strobe, compact core halos, and no night contrail.
Subdue lights at dawn/dusk and omit them in daylight without changing flight paths, timing, weather restrictions, or daytime appearance.

### Rendering Performance and Motion Sensors

Corona/optical sprites cache disjoint alpha-coverage strips and clip transparent overdraw while retaining full-bitmap filtering/compositing.
Build coverage once per source and invalidate with its tile cache; retain the procedural arc's immutable sprite/coverage for the layer lifetime, never generating artwork per frame or changing frame caps/non-sun paths for this optimization.
Motion-responsive reflections defaults off, requires Lens flare, and is disabled without a compatible sensor; it can use extra battery/reduce performance.
Use gravity or an accelerometer fallback only while a visible scene can draw reflections; stop when hidden, screen-off, or weather/night suppresses reflections, and recenter on stop or rotation.
Sensor callbacks set normalized targets; each rendered preview/wallpaper frame advances visible offsets with the same time-based exponential filter, without increasing sensor rate or changing visibility gating.

Historical [#206](https://github.com/Attacktive/weatherd/issues/206) measurements below predate the revised prismatic arc in #290; do not present them as current telemetry.
Pixel emulator, API 36, 1600×2560: median frame times after eight warm-up frames and 60 measured frames:

| Scene / measurement | Before | After |
| --- | ---: | ---: |
| Software foreground: sun without lens flare | 24.82 ms | 15.26 ms |
| Software foreground: sun with lens flare | 38.24 ms | 22.12 ms |
| Software foreground: sun and 0.5 cloud cover | 45.57 ms | 30.03 ms |
| Hardware canvas: sun with lens flare, through image delivery/readback | 23.02 ms | 17.67 ms |

Software measurements exclude backdrop restoration; hardware includes backdrop drawing, submission, and readable-frame waiting.
These measure rendering, not launcher jank/input latency; full-resolution software comparisons kept clouds, Moon, overcast, mountains, and rain pixel-identical, with clear-day lens flare differing at nine pixels by at most one channel value.

Galaxy S23, 1080×2340, uncapped clear-day simulator:

| Build | Instrumented gravity sample cadence | Instrumented longest continuous wallpaper render segment | User-observed reflection motion |
| --- | ---: | ---: | --- |
| 3.8.1 Play baseline | 66.35 ms / 15.07 Hz | 118.75 jobs/s | Stepped |
| Patched side-by-side debug build | 66.35 ms / 15.07 Hz | 117.50 jobs/s | Smooth |

The unchanged instrumented cadences and before/after user observation are consistent with frame-driven interpolation removing stepping, not faster sensor sampling or a lower frame cap.
