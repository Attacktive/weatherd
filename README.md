# Weatherd

[![Test](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml/badge.svg)](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml)

Android live wallpaper that renders a procedural weather scene from Open-Meteo Best Match, [MET Norway](https://api.met.no), or explicit ICON models from DWD, ItaliaMeteo, and MeteoSwiss through [Open-Meteo](https://open-meteo.com). Regional models automatically use a user-configured global fallback outside their coverage or when their request fails.

Uses device location or a manually searched city and mirrors the live scene in an in-app preview. Weather checks are triggered when the preview resumes, the wallpaper becomes visible, or relevant weather/location settings change; automatic fetches are throttled by a configurable minimum interval (15 min – 6 hr).

High relative humidity can add a restrained atmospheric haze without changing the reported weather condition. The effect begins above 85% relative humidity and ramps smoothly to a capped strength at 100%, while provider-reported fog remains the full semantic fog state and takes precedence.

The scene simulator overrides the preview and live wallpaper without replacing the cached real weather.
Returning to live mode displays cached weather without waiting for location or network requests when its location source and provider configuration still match the selected target.
On a first launch or after changing targets, it displays a clock-lit fallback until matching weather becomes available.
When a selected city or known device fix is available, the fallback calculates approximate sunrise and sunset locally for that location and date, including polar day/night, and advances celestial progress without a weather request.
Only an unavailable or invalid location uses a coarse device-local clock approximation; valid provider solar times and explicit simulator selections retain precedence.
Refreshes happen separately, and a slow or failed request does not prevent switching modes.

Enable **Settings → Appearance → Scenery & photos → Trees** to add a fixed foreground tree composition to any backdrop, including None and Photo.
When enabled, **Tree shape** offers **Evergreen**, the existing multi-tree conifer composition, or **Broadleaf**, a single spreading hero tree with a sinuous trunk and deliquescent branching inspired by [#271's reference](https://github.com/Attacktive/weatherd/issues/271).
This is a morphology choice, not a backdrop or seasonal landscape.
Trees remain off on fresh installs, and existing settings retain their on/off choice; settings and presets without a shape keep Evergreen.
Turning trees off hides the shape picker without clearing its selection; shape resets independently and is saved with appearance presets.
New preset writes use schema v13; v1–v12 presets remain readable, while older builds reject the new bank instead of silently dropping appearance settings.
The preview, live wallpaper, simulator, and clock-lit fallback share the same tree preference, shape, and placement.
Tree lighting follows day phase and cloudy weather, while fog and precipitation draw in front of the foliage.
First-party source sprites are reproducibly generated with `uv run scripts/generate-tree-textures.py` for Evergreen and `uv run scripts/generate-broadleaf-textures.py` for Broadleaf.
Only the selected style is decoded on first use, off the frame loop; no artwork is copied from the reference app or third parties.

Choose **Settings → Appearance → Sun & moon → Sun style** for **Classic**, the existing procedural sun; **Radiant**, the transparent sun image submitted by [@emanuelelina2014-arch in #255](https://github.com/Attacktive/weatherd/issues/255); or **Starburst**, an original broad, soft solar bloom inspired by the reference in [#291](https://github.com/Attacktive/weatherd/issues/291).
Classic remains the default for existing settings and presets; the choice is saved with appearance presets and shared by the preview, live wallpaper, simulator, and clock-lit fallback.
All three styles follow the existing sun visibility, size, color, weather veiling, and sunset fade controls.
Radiant preserves the image’s bright core and short rays, but its cached rendering texture fades the outer haze and distant baked reflections smoothly to transparent before the image borders, avoiding a pale ring or clipped edge.
Its additional wide optical halos are restrained; **Lens flare** and **Motion-responsive reflections** still control the separate procedural reflections and their motion.
The [submitted PNG](https://github.com/user-attachments/assets/68a92bcf-016d-4276-bc35-4c1e38ae6b78) is bundled unchanged; the softened immutable rendering texture is generated once off the frame loop on first use.
Starburst is reproducibly generated with `uv run scripts/generate-starburst-sun.py`, without sampling the supplied iOS image or other third-party artwork.
Starburst combines overlapping luminous lobes with faint uneven streaks, without a pointed-star silhouette; its wider bloom fades to transparent before the texture boundary, and the bitmap is decoded once off the frame loop only when selected.
Natural daylight uses a white light source; dawn, dusk, and explicit sun color choices retain their selected tint.
No rainbow reflections are baked into Starburst: **Lens flare** controls the existing chromatic halo and optical reflections, and **Motion-responsive reflections** controls their motion independently of the sun artwork.
Distant aircraft and their contrails cross in front of the solar disc in all three styles, while cloud layers can still obscure them in both the preview and live wallpaper.

The Moon uses a realistic lunar surface derived from the [NASA SVS CGI Moon Kit](https://svs.gsfc.nasa.gov/4720/), credited to NASA's Scientific Visualization Studio.
NASA SVS makes this imagery [public domain unless otherwise noted](https://svs.gsfc.nasa.gov/help/); the submitted image in [#283](https://github.com/Attacktive/weatherd/issues/283) is a visual reference, not a bundled asset.
Regenerate the north-up near-side disc with `uv run scripts/generate-moon-texture.py`; its unchanged source map lives in `scripts/assets/moon/lroc_color_2k.jpg`, and the generator checks its SHA-256 before projecting it.
Waxing and waning change the illumination without mirroring lunar markings; the new Moon draws neither a disc nor a lunar halo.
Choose **Settings → Appearance → Sun & moon → Moon size** for an independent **50–200%** scale, default **100%**, with an individual reset.
Moon size is saved with appearance presets and shared by the preview, live wallpaper, simulator, and fallback; older settings and presets retain 100%.
Source decoding and synthesis of the requested lunar phase both run in the background; drawing only reads an already prepared immutable sprite and never waits for phase generation.
An unprepared phase draws neither an old disc nor its halo; Home and the wallpaper request a redraw when that phase is ready, including after continuous animation has settled.
The phase cache is bounded, and resizing changes draw geometry without rebuilding a sprite or the static sky backdrop.

Choose **Settings → Appearance → Sun & moon → Sunbeams** for **Off**, **Through clouds** (default), or **Clear skies too**.
Through clouds draws soft, spreading atmospheric shafts from moving near low-cloud openings; Clear skies too also adds radial camera-style glare around an unobstructed sun.
Low and mid cloud cover both attenuate clear-sky rays, while high cirrus alone does not.
Distant cloud openings do not generate their own shafts.
Night, a hidden sun, rain/snow, fog, thunderstorms, and opaque cloud decks suppress the effect in either enabled mode.
Off removes the cloud shafts and extended clear-sky glare, preserving the selected sun style, its compact corona, and the independent lens flare controls.
The choice resets individually, is saved with appearance presets, and follows the shared preview, live-wallpaper, simulator, and fallback path; older settings and presets use Through clouds.
Cloud-shaft softness is cached in a small first-party procedural sprite and transformed each frame, without per-frame texture generation or blur work.
Clear-sky glare uses a shared, cached first-party texture generated with `uv run scripts/generate-sun-glare.py`: a hexagonal aperture's squared Fourier amplitude is convolved with a finite solar disc and averaged across three wavelengths.
Its paired radial structure follows [aperture diffraction](https://www.nature.com/articles/s41598-018-34400-z), rather than pretending that clear air contains cloud-opening shafts; it stays fixed around the solar source instead of independently swaying or pulsing its rays.
Exposure, tint, and edge falloff are tuned for the scene, so this remains an idealized optical approximation, not a calibrated camera simulation.

Choose **Settings → Appearance → Sky → Sky palette → Clear blue** for a richer blue zenith fading into a pale horizon, inspired by the reference in [#252](https://github.com/Attacktive/weatherd/issues/252).
**Natural** remains the default; existing palettes and saved settings are unchanged.
Use the existing **Sky brightness** and **Sky saturation** sliders to make the blue darker, lighter, muted, or vivid.
The palette is saved with appearance presets and shared by the preview, live wallpaper, simulator, and fallback; natural sunrise, sunset, and night colors remain intact, and cloudy/stormy weather still takes precedence.
The existing **Lens flare** option retains the chromatic halo around the sun, with smaller reflections spaced into a tighter train rather than extending toward the bottom of the scene.
The four-reflection train stays above the lower quarter during supported device tilt in portrait and landscape; near that boundary, its spacing compresses together without hiding the farthest circle or changing the separate chromatic halo.

In **Settings → Appearance → Sky**, choose **Birds**: **Off**, **Small flock** (3–5 birds), **Medium flock** (6–8), or **Large flock** (9–12).
**Bird interval** offers **Natural**, **30 seconds**, **1 minute**, **2 minutes**, **5 minutes**, and **10 minutes**.
Small + Natural preserves the existing restrained, randomized bird schedule; explicit intervals schedule one crossing per interval without randomly skipping appearances.
Birds still appear only in fair daylight: rain, fog, opaque cloud cover, and night suppress them regardless of the selected interval.
Timing follows the shared scene animation clock, with no background alarm or extra animation lifecycle.
Turning birds off hides the interval control without clearing its selection. Both controls reset individually, are saved with appearance presets, and apply through the shared preview, live-wallpaper, simulator, and fallback scene path.

## Settings navigation

Settings opens on **Appearance**, followed by **Weather**, **Wallpaper**, and **Advanced**.
Appearance is a category hub with short summaries rather than a single long list of controls:

- **Scenery & photos:** backdrop scenery, conditional photo slots, and the independent Trees switch with a conditional tree-shape picker.
- **Sky:** palette, brightness, night brightness, saturation, distant aircraft, and bird size/interval.
- **Clouds:** size, count, opacity, and contrast together.
- **Sun & moon:** visibility, sun style, sunbeams, independent sun/Moon size, sun color, lens flare, and motion-responsive reflections.
- **Weather effects:** precipitation intensity, glass droplets, and wind intensity.
- **On-screen text:** weather/location visibility, shared vertical position, and temperature unit.
- **Readability:** independent wallpaper dimming and status-bar shade, shared by Home and Lock.
- **Presets:** save, apply, rename, replace, and delete appearance presets.

Opening a category replaces the Settings title and hides the top-level tabs.
Toolbar and system Back both return to the Appearance hub; Back from the hub leaves Settings.
Hub and category scroll positions are retained during the Settings session and activity recreation.
Weather retains location, provider, and refresh controls, with applicable MET Norway attribution beside the weather configuration.
Wallpaper retains motion and performance controls, while Advanced contains the scene simulator.
The reorganization does not change stored settings, defaults, reset scope, preset contents, or the shared preview/live-wallpaper behavior.

In **Settings → Appearance → On-screen text**, enable either text label to reveal the **Vertical position** slider.
It moves current weather and the location name together, preserving their spacing and horizontal alignment in both the preview and live wallpaper.
The slider maps **0%** to the visible text block's top edge at the drawable area's top and **100%** to its bottom edge at the drawable area's bottom, with intermediate values using the full available travel.
Bounds adapt to whichever lines are visible without changing their font sizes or spacing. **Reset** and unset preferences use the 9% compatibility anchor, reproducing the historical weather baseline at 9% of scene height and location baseline at 12.1%, including when only one line is enabled.
Travel is continuous and linear from the top endpoint to that anchor and from the anchor to the bottom endpoint; percentages are not uniformly spaced over the entire scene height.
The position is saved with appearance presets. Earlier stored percentages keep their numeric value under this anchored mapping, while presets without a position retain the historical default placement.
The simulator honors both visibility switches and the saved position: its weather line shows the selected simulated condition without inventing a temperature, while the location line uses the selected or resolved real place name.

In **Settings → Appearance → Readability**, **Wallpaper dimming** darkens the completed scene behind launcher icons and widgets.
**Status-bar shade** adds an independent dark top edge that fades to transparent over the upper 18% of the visible wallpaper.
Both strengths range from **0%** (off, the default) to **60%** opacity; each **Reset** turns only that control off.
The settings apply to the preview, simulator, fallback, and Home/Lock wherever Android displays Weatherd, including photo backgrounds.
The shading stays fixed while the wallpaper scrolls, covers animated weather and glass droplets, and leaves Weatherd’s weather/location labels bright.
Both strengths are saved with appearance presets; older presets without them apply with both off.

## Experimental weather provider comparison

In **Settings → Weather → Compare weather providers**, testers can open a comparison, select the sky they actually see, and tap **Compare now** to query Open-Meteo Best Match, ItaliaMeteo ICON-2I, DWD ICON EU, and MET Norway at the same effective weather location.
The comparison bypasses normal primary/fallback selection without changing either preference, skips providers outside their declared coverage, and reports each provider's returned forecast-valid time, weather condition, cloud layers, temperature, and precipitation.
Precipitation totals are labeled with their source-specific accumulation windows: Open-Meteo's returned interval precedes its timestamp, while MET Norway's next-hour precipitation follows it. Missing interval metadata is shown as unknown rather than silently comparing incompatible periods.
Comparison requests are made only on the explicit tap; dismissing the dialog cancels in-progress sampling.
Results are not persisted, uploaded automatically, or included in the normal weather cache.
**Share report** opens Android's share chooser with a plain-text snapshot plus the tester's observed sky; the report contains no coordinates or place names.
The coordinates necessarily go to each weather provider being queried, as disclosed by the feature. Consult the privacy policy before using this experimental tool.

## Weather refresh timing

The configured refresh interval is the minimum time between automatic weather fetches, not a background timer. Weatherd checks when the preview resumes, when the wallpaper becomes visible, and after relevant location or provider changes. **Refresh now** and the home-screen refresh widget bypass that interval.

The widget queues network-constrained work. Its press effect confirms the tap immediately, an animated progress indicator appears when the accepted work starts running, and a brief ✓ reports a completed weather refresh. A brief ! means the refresh did not complete, including an unavailable location or a final provider failure. If the device is offline, queued work waits for a network connection. Check **Settings → Weather → Last updated** to see when weather was last refreshed; the scene may still look the same when conditions have not changed.

After an app update, Weatherd republishes the widget's refresh action without launching the app or forcing a weather fetch. While Android is still initializing the widget, taps are ignored rather than opening the scene preview; tap again once initialization finishes.

Enable **Settings → Appearance → Sun & moon → Motion-responsive reflections** to move the sun's optical reflections as you tilt your phone, in both the preview and the live wallpaper.
The option is off by default, requires **Lens flare**, and is disabled on devices without a compatible motion sensor.
It uses a gravity sensor or an accelerometer fallback only while a visible scene can draw sun reflections; collection stops when the scene is hidden, the screen is off, or weather/night hides the reflections.
Motion can use extra battery and reduce performance while active, and the reflections recenter after collection stops or the display rotates.

**Min SDK:** Android 8.0 (API 26)

<a href="https://play.google.com/store/apps/details?id=xyz.attacktive.weatherd">
	<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" width="200">
</a>

## Sister project

[Wallhavend](https://github.com/Attacktive/Wallhavend-android) rotates real photo wallpapers from [Wallhaven](https://wallhaven.cc) on a schedule — same bones, opposite art department.
[Get it on Google Play](https://play.google.com/store/apps/details?id=xyz.attacktive.wallhavend).

## Building from source

Requires JDK 21 and the Android SDK ([Android Studio](https://developer.android.com/studio) bundles both).

```sh
git clone https://github.com/Attacktive/weatherd.git
cd weatherd
./gradlew assembleDebug
```

Debug builds need no secrets. All weather providers work without API keys. `release.keystore` with `KEYSTORE_PASSWORD` are needed only for release signing.

Run the pull-request checks locally with `./gradlew test :app:lint :app:detekt`.
Run `./gradlew :app:connectedDebugAndroidTest` only on a disposable device or emulator: the current runner removes Weatherd during teardown, including its app data.
For an emulator with an existing installation, preserve app data by updating both APKs in place and invoking instrumentation directly:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner
```

Replace `emulator-5554` with the intended device serial; back up app data separately from the APK before a workflow that uninstalls packages.

The existing CI Test gate builds the debug app and instrumentation APKs alongside unit tests, lint, and Detekt while the emulator starts; instrumentation remains required after that gate succeeds.
Settings navigation tests wake the device, dismiss a nonsecure keyguard, and keep a bounded 320×640 dp activity window through recreation so scroll restoration is exercised on large displays instead of skipped.
Navigation timeouts include the activity state, active accessibility tree, and window-manager diagnostics; scroll checks require the saved anchor to remain visible with the first section off-screen rather than exact pixel coordinates.
Sunbeam fixtures pin the UTC day used for cloud placement instead of depending on the machine's date.
The cloud-boundary check compares rays over the same unobscured pixels in every frame, so foreground cloud occlusion is not mistaken for a discontinuity in the ray field.

## Daytime rendering performance

The corona and optical halos cache disjoint alpha-coverage strips and clip drawing to them, skipping transparent overdraw while retaining the original bitmap sampling, SCREEN compositing, colors, opacity, and time-based animation.
Coverage metadata is built once per source sprite and invalidated with its tile cache; the decoded atmospheric rainbow keeps its coverage for the layer's lifetime.
Frame-rate caps and non-sun rendering paths are unchanged.

For [#206](https://github.com/Attacktive/weatherd/issues/206), the Pixel emulator (API 36) at 1600×2560 produced these median frame times after eight warm-up frames and 60 measured frames:

| Scene / measurement | Before | After |
| --- | ---: | ---: |
| Software foreground: sun without lens flare | 24.82 ms | 15.26 ms |
| Software foreground: sun with lens flare | 38.24 ms | 22.12 ms |
| Software foreground: sun and 0.5 cloud cover | 45.57 ms | 30.03 ms |
| Hardware canvas: sun with lens flare, through image delivery/readback | 23.02 ms | 17.67 ms |

The software measurements exclude backdrop restoration; the hardware measurements include backdrop drawing, submission, and waiting for a readable frame.
These are renderer measurements, not launcher-jank or input-latency measurements.
Full-resolution software comparisons kept clouds, moon, overcast, mountains, and rain pixel-identical; clear-day lens flare differed at nine pixels by at most one channel value.

### Motion-responsive reflections

Sensor callbacks update normalized target offsets, while each rendered preview or wallpaper frame advances the visible offsets with the same time-based exponential filter.
This prevents a low-rate sensor stream from making reflections jump between otherwise smooth frames without increasing the sensor request rate or changing visibility-based sensor lifecycle gating.

For [#206](https://github.com/Attacktive/weatherd/issues/206), the Galaxy S23 at 1080×2340 with an uncapped clear-day simulator produced:

| Build | Instrumented gravity sample cadence | Instrumented longest continuous wallpaper render segment | User-observed reflection motion |
| --- | ---: | ---: | --- |
| 3.8.1 Play baseline | 66.35 ms / 15.07 Hz | 118.75 jobs/s | Stepped |
| Patched side-by-side debug build | 66.35 ms / 15.07 Hz | 117.50 jobs/s | Smooth |

The unchanged instrumented cadences and the user's before/after observation are consistent with frame-driven interpolation removing the stepping without faster sensor sampling or a lower frame-rate cap.

## Weather data attribution

Weatherd offers Open-Meteo Best Match, [MET Norway](https://api.met.no), and explicit ICON-family forecasts served through [Open-Meteo](https://open-meteo.com):

- DWD ICON Global (~11 km), ICON EU (~7 km), and ICON D2 (~2 km)
- [ItaliaMeteo ICON-2I](https://open-meteo.com/en/docs/italia-meteo-arpae-api) (~2 km)
- [MeteoSwiss ICON CH1 and CH2](https://open-meteo.com/en/docs/meteoswiss-api) (~1 km and ~2 km)

Regional models are selected only for coordinates inside their published grids. Outside that coverage, or if the primary request fails, Weatherd makes at most one request to the user's configured global fallback. The primary and fallback preferences remain unchanged, and cached weather retains the source that actually produced it.

Open-Meteo data is provided under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Explicit ICON choices request their corresponding Open-Meteo model identifier instead of automatic model selection.

MET Norway data is provided by The Norwegian Meteorological Institute ("MET Norway") under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Weatherd modifies provider data by normalizing provider-specific fields into its own weather-condition model and rendering the result as procedural scenes.

## Google Play vitals monitoring

Weatherd runs a daily monitoring workflow (`.github/workflows/google-play-vitals.yaml`) scheduled at 00:37 UTC, with support for manual triggers via `workflow_dispatch`.

The workflow retrieves crash and ANR clusters from the Google Play Developer Reporting API using [Attacktive/google-play-vitals-action](https://github.com/Attacktive/google-play-vitals-action) and compares them against the previous successful run's snapshot artifact (`play-vitals-snapshot`).
Missing or expired artifacts fall back safely to a baseline without false positive alerts.

### Prerequisites

1. The **Google Play Developer Reporting API** must be enabled in Google Cloud.
2. A Google Cloud service account with **View app information (read-only)** permissions in Google Play Console.
3. The service account JSON key stored in GitHub repository secrets as `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (reusing the existing deployment secret).
4. Enable the IAM API and IAM Service Account Credentials API in the service account's Google Cloud project.
5. Grant the service account `roles/iam.serviceAccountTokenCreator` on itself, as required by the [authentication action's access-token setup](https://github.com/google-github-actions/auth/tree/7c6bc770dae815cd3e89ee6cdf493a5fab2cc093#inputs-service-account-key-json).

### Reporting and alerts

Every run produces a job summary in GitHub Actions and uploads the JSON report as a 90-day retained artifact.
The workflow does not automatically file GitHub issues or dispatch notifications.
