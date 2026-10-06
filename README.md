# Weatherd

[![Test](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml/badge.svg)](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml)

Android live wallpaper that renders a procedural weather scene from Open-Meteo Best Match, [MET Norway](https://api.met.no), or explicit ICON models from DWD, ItaliaMeteo, and MeteoSwiss through [Open-Meteo](https://open-meteo.com). Regional models automatically use a user-configured global fallback outside their coverage or when their request fails.

Uses device location or a manually searched city, refreshes on a configurable interval (15 min – 6 hr), and mirrors the live scene in an in-app preview.

Enable **Settings → Appearance → Sun and moon → Motion-responsive reflections** to move the sun's optical reflections as you tilt your phone, in both the preview and the live wallpaper.
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

## Painterly mountain scenery

The mountain backdrop keeps its seeded silhouettes and snowcap boundaries while adding broad, softly blended rock faces, shared rock/snow illumination, forest canopy variation, meadow patches, and localized forest-to-meadow contact shading.
Clear daylight reveals relief; twilight warms exposed surfaces, while overcast, fog, thunder, and night subdue their contrast.
The preview and wallpaper share the same renderer and simulator settings; hiding or styling sun/moon artwork does not change terrain illumination.
Material coordinates stay attached to the virtual landscape while the wallpaper scrolls, and the beach, countryside, and metropolis retain their existing appearance.

Scenery owns one active geometry/material cache, separate from lighting.
Scene or virtual-surface dimensions rebuild bounded immutable sources; weather and celestial progress update paints without regenerating textures or blur.
Both software and hardware Canvas paths remain supported without a new dependency or display setting.

### Measured mountain rendering

On the isolated Pixel emulator (API 36, software-emulated GPU), paired captures prewarmed clouds and used a cached backdrop, eight warm-up frames, and sixty measured frames for each clear/overcast/fog/rain/snow/thunderstorm case at 540×1200, 1200×540, and scrolling-width 810×1200.
Hardware timing was split into callback waiting, recording/submission, image delivery and producer-fence completion, then CPU readback; the table measures recording through GPU completion, excluding callback scheduling and screenshot-copy overhead.

| Hardware scene / surface | Before | After |
| --- | ---: | ---: |
| Clear, 540×1200 | 4.87 ms | 7.61 ms |
| Fog, 810×1200 | 7.79 ms | 10.29 ms |
| Thunderstorm, 810×1200 | 27.00 ms | 30.97 ms |

Every measured hardware case remained below the 33 ms budget at p95; the worst after p95 was 32.90 ms, with 7 individual render-through-fence overruns in 1,080 frames versus 4 before.
The complete screenshot-loop median increased from 17.15 to 18.87 ms and retains scheduling/readback stalls; it is not a renderer-only or launcher-jank measurement.
Software backdrop-blit plus foreground median increased from 10.04 to 16.20 ms across the same cases, with no measured 33 ms overruns.
This treatment adds rendering cost rather than claiming a performance improvement.

Active terrain sources retained 1.05 MiB (1,103,120 bytes) at 1080×2400 and dropped to zero through inactive scenery modes across 72 scene/viewport transitions.
Weather and celestial-progress updates retained the same immutable source objects; these figures measure terrain bitmap allocation, not total process or GPU memory.

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
