# Weatherd

[![Test](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml/badge.svg)](https://github.com/Attacktive/weatherd/actions/workflows/test.yaml)

Android live wallpaper that renders a procedural weather scene from [Open-Meteo](https://open-meteo.com).

Uses device location or a manually searched city, refreshes on a configurable interval (15 min – 6 hr), and mirrors the live scene in an in-app preview.

**Min SDK:** Android 8.0 (API 26)

## Sister project

[Wallhavend](https://github.com/Attacktive/Wallhavend-android) rotates real photo wallpapers from [Wallhaven](https://wallhaven.cc) on a schedule — same bones, opposite art department. [Get it on Google Play](https://play.google.com/store/apps/details?id=xyz.attacktive.wallhavend).

## Building from source

Requires JDK 17 and the Android SDK ([Android Studio](https://developer.android.com/studio) bundles both).

```sh
git clone https://github.com/Attacktive/weatherd.git
cd weatherd
./gradlew assembleDebug
```

Debug builds need no secrets. Weather data comes from Open-Meteo with no API key. `release.keystore` with `KEYSTORE_PASSWORD` are needed only for release signing.

Run the unit tests with `./gradlew test`.

---

Weather data by [Open-Meteo](https://open-meteo.com).
