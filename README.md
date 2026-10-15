# Weatherd

[![Test](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml/badge.svg)](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml)

Android live wallpaper that renders a procedural weather scene from Open-Meteo Best Match, [MET Norway](https://api.met.no), or explicit ICON models from DWD, ItaliaMeteo, and MeteoSwiss through [Open-Meteo](https://open-meteo.com). Regional models automatically use a user-configured global fallback outside their coverage or when their request fails.

Uses device location or a manually searched city and mirrors the live scene in an in-app preview. Weather checks are triggered when the preview resumes, the wallpaper becomes visible, or relevant weather/location settings change; automatic fetches are throttled by a configurable minimum interval (15 min – 6 hr).

High relative humidity can add a restrained atmospheric haze without changing the reported weather condition. The effect begins above 85% relative humidity and ramps smoothly to a capped strength at 100%, while provider-reported fog remains the full semantic fog state and takes precedence.

The scene simulator overrides the preview and live wallpaper without replacing the cached real weather.
Returning to live mode displays cached weather without waiting for location or network requests when its location source and provider configuration still match the selected target.
On a first launch or after changing targets, it displays a clock-lit fallback until matching weather becomes available.
Refreshes happen separately, and a slow or failed request does not prevent switching modes.

Enable **Settings → Appearance → Scenery & photos → Trees** to add a fixed composition of detailed foreground evergreens to any backdrop, including None and Photo.
Trees are off by default and saved with appearance presets; old settings and presets keep them off.
New preset writes use schema v7; v1–v6 presets remain readable, while older builds reject the new bank instead of silently reinterpreting normalized text travel as a baseline coordinate.
The preview, live wallpaper, simulator, and clock-lit fallback share the same tree preference and placement.
Tree lighting follows day phase and cloudy weather, while fog and precipitation draw in front of the foliage.
The three first-party source sprites are reproducibly generated with `uv run scripts/generate-tree-textures.py` and decoded once off the frame loop on first use; no artwork is copied from the reference app or third parties.

Choose **Settings → Appearance → Sun & moon → Sun style** to switch between **Classic**, the existing procedural sun, and **Radiant**, the transparent sun image submitted by [@emanuelelina2014-arch in #255](https://github.com/Attacktive/weatherd/issues/255).
Classic remains the default for existing settings and presets; the choice is saved with appearance presets and shared by the preview, live wallpaper, simulator, and clock-lit fallback.
Both styles follow the existing sun visibility, size, color, weather veiling, and sunset fade controls.
Radiant preserves the image’s bright core and short rays, but its cached rendering texture fades the outer haze and distant baked reflections smoothly to transparent before the image borders, avoiding a pale ring or clipped edge.
Its additional wide optical halos are restrained; **Lens flare** and **Motion-responsive reflections** still control the separate procedural reflections and their motion.
The [submitted PNG](https://github.com/user-attachments/assets/68a92bcf-016d-4276-bc35-4c1e38ae6b78) is bundled unchanged; the softened immutable rendering texture is generated once off the frame loop on first use.

## Settings navigation

Settings opens on **Appearance**, followed by **Weather**, **Wallpaper**, and **Advanced**.
Appearance is a category hub with short summaries rather than a single long list of controls:

- **Scenery & photos:** backdrop scenery, conditional photo slots, and the independent Trees switch.
- **Sky:** palette, brightness, night brightness, saturation, and distant aircraft.
- **Clouds:** size, count, opacity, and contrast together.
- **Sun & moon:** visibility, sun style, size, color, lens flare, and motion-responsive reflections.
- **Weather effects:** precipitation intensity, glass droplets, and wind intensity.
- **On-screen text:** weather/location visibility, shared vertical position, and temperature unit.
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
Bounds adapt to whichever lines are visible without changing their font sizes or spacing; **Reset** returns to the near-top 9% setting.
The position is saved with appearance presets. Earlier stored percentages keep their numeric value under this normalized mapping, while presets without a position use the near-top default.
The simulator honors both visibility switches and the saved position: its weather line shows the selected simulated condition without inventing a temperature, while the location line uses the selected or resolved real place name.

## Weather refresh timing

The configured refresh interval is the minimum time between automatic weather fetches, not a background timer. Weatherd checks when the preview resumes, when the wallpaper becomes visible, and after relevant location or provider changes. **Refresh now** and the home-screen refresh widget bypass that interval.

The widget queues network-constrained work. Its press effect confirms the tap immediately, an animated progress indicator appears when the accepted work starts running, and a brief ✓ reports a completed weather refresh. A brief ! means the refresh did not complete, including an unavailable location or a final provider failure. If the device is offline, queued work waits for a network connection. Check **Settings → Weather → Last updated** to see when weather was last refreshed; the scene may still look the same when conditions have not changed.

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

Requires JDK 17 and the Android SDK ([Android Studio](https://developer.android.com/studio) bundles both).

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
