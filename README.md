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

Run the pull-request checks locally with `./gradlew test :app:lint :app:detekt`. Run instrumentation tests on a connected device or emulator with `./gradlew :app:connectedDebugAndroidTest`.

## Weather data attribution

Weatherd offers Open-Meteo Best Match, [MET Norway](https://api.met.no), and explicit ICON-family forecasts served through [Open-Meteo](https://open-meteo.com):

- DWD ICON Global (~11 km), ICON EU (~7 km), and ICON D2 (~2 km)
- [ItaliaMeteo ICON-2I](https://open-meteo.com/en/docs/italia-meteo-arpae-api) (~2 km)
- [MeteoSwiss ICON CH1 and CH2](https://open-meteo.com/en/docs/meteoswiss-api) (~1 km and ~2 km)

Regional models are selected only for coordinates inside their published grids. Outside that coverage, or if the primary request fails, Weatherd makes at most one request to the user's configured global fallback. The primary and fallback preferences remain unchanged, and cached weather retains the source that actually produced it.

Open-Meteo data is provided under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Explicit ICON choices request their corresponding Open-Meteo model identifier instead of automatic model selection.

MET Norway data is provided by The Norwegian Meteorological Institute ("MET Norway") under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Weatherd modifies provider data by normalizing provider-specific fields into its own weather-condition model and rendering the result as procedural scenes.
