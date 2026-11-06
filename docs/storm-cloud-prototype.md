# Issue #405: storm-cloud renderer and release staging

This is a production-integration implementation under review, not a published app update.
It targets Emanuele's [layered-cloud request](https://github.com/Attacktive/weatherd/issues/405), independently of #398's dry partly-cloudy correction and #406's rain-origin request.

## Scope and containment

The shared SceneRenderer invokes the prototype only inside its existing rainy cloud-deck path.
Preview and live wallpaper therefore use the same implementation, including the simulator.
The supplied central cloud remains a separate sprite from the upper dark bank and distant lower strip.
Every sprite preserves its source aspect ratio and drifts continuously to the right with the existing rain and overcast layers, at an independent wind-dependent speed, without vertical bobbing or alpha pulsing. The build retains the original central cloud pixels unchanged, filling only the transparent **outer edges** with shifted cloud details to make the repeat seamless. It no longer shifts the original transparent borders into the center, which previously produced an artificial, symmetrical hole. Ordinary horizontal repetition keeps scrolling continuous without mirrored cloud pairs.
The central cloud is uniformly scaled down in its own aspect ratio and raised within the deck; the derived debug PNGs taper the lowest hero/distant alpha rather than clipping at the near-opaque cloud bases. A reduced-opacity, soft atmospheric veil extends behind their lower edges without repainting the detailed RGB artwork.
The three detailed planes share one sky-depth coordinate system: the dark upper bank meets the screen edge, the central mass overlaps it, and the distant strip overlaps the main cloud's feathered lower edge instead of forming an isolated row.
The entire composition scales together with the cloud-size control.
At default size the artwork remains wider than the viewport. Seam-blended repeating texture sampling extends the image beyond its edges while it scrolls, without squeezing or stretching the original source; wide displays retain the same uniform scaling.
Natural daytime rain receives a muted, slightly darker sky grade in the cached palette path; the existing daylight ramp fades that grade through dawn/dusk and leaves night colors unchanged.
Custom and other built-in palettes are untouched, and photo drawing still bypasses the procedural sky.
Wind scale zero freezes the motion, and changing wind intensity maintains the current position rather than teleporting the cloud layers.
Textures decode once in the existing background cloud-prewarm step; draw callbacks reuse geometry, paint and color-filter state.
The existing opacity, size, contrast and day-phase/sky-brightness tint continue to apply.
Precipitation drawing is unchanged; #406 is not implemented here.
Dry partly-cloudy and overcast scenes, snow, sleet and fog without rain keep the previous renderer.

On this branch, debug and release builds enable the shared storm-cloud renderer by default. An ordinary Android Studio debug build prepares the four source-hash-verified PNGs if they are missing; valid prepared PNGs are reused. For a baseline comparison, pass `-PstormCloudPrototype=false`.
The renderer and rain-sky palette live in the shared main source set, so neither build variant compiles an inert substitute.
No settings key or preset schema changes are needed.

## Prepare the supplied artwork

Run from the repository root:

```sh
uv run --with pillow --with numpy --with scipy scripts/prepare-storm-cloud-prototype.py --download
```

Alternatively, provide the original attachments as `sheet.png` and `hero.png` in a directory and pass `--source-dir <directory>` instead of `--download`.
The script verifies the original attachment hashes and retains the originals outside the Android source sets.
It extracts the sheet's upper and lower bodies using connected components and nearest-core assignment, not fixed cuts through overlapping clouds, and uses the separate hero attachment for the center.
Cloud detail retains its original RGB; the debug-only derived hero/distant PNGs smoothly attenuate alpha at their lowest edges, and low-alpha export noise, saturated fringes, and detached artifacts are suppressed.
Emanuele explicitly [authorized publication](https://github.com/Attacktive/weatherd/issues/405#issuecomment-6677621824) of the contributed imagery, with provenance recorded in `app/src/release/assets/storm-clouds/ARTWORK_PERMISSION.md`.
The output manifest records source and processed hashes plus the preparation version, so local debug caches regenerate the older mirrored, half-shifted or gap-prone textures after updating the branch.

Debug builds prepare the four PNGs automatically on first run, placing them in ignored `app/src/debug/assets/storm-clouds/`; they reuse a validated manifest on later builds. First-run preparation uses `python3` and an isolated environment under `.local/storm-cloud-venv/` to install Python-compatible Pillow, NumPy and SciPy pins and download two source-hash-verified GitHub attachments. Python 3.9–3.11 uses Pillow 11.3.0, NumPy 2.0.2 and SciPy 1.13.1; Python 3.12+ uses Pillow 13.0.0, NumPy 2.5.4 and SciPy 1.18.1. The installer checks the **existing virtual environment's** Python version, including when an earlier install failed, so manually deleting `.local/storm-cloud-venv/` is unnecessary. An internet connection is required only for that initial preparation, not for the running app. The existing tag-triggered Release workflow prepares equivalent assets in `app/src/release/assets/storm-clouds/` before building. No network download occurs at app runtime.

## Build and exercise

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The application ID is unchanged: use a disposable test device/emulator rather than overwriting a personal installation.
Choose rain or thunderstorm in Advanced's scene simulator, then compare portrait and landscape, day/night, multiple wind strengths, and cloud opacity/size/contrast settings.
The existing gray weather backdrop and rain remain visible beneath the new banks.
Use `-PstormCloudPrototype=false` to compare with the unchanged baseline.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:detekt
./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets -PstormCloudPrototype=true
uv run --with pillow --with numpy --with scipy python -m unittest discover -s scripts/tests -p test_storm_cloud_assets.py -v
```

The standard Android tests use synthetic transparent fixtures. On this branch the additional packaged-sprite test runs by default, decoding and drawing the real artwork prepared for the debug build.
Before opening a PR, run the ordinary Android suite locally on exactly one disposable device with explicit `ANDROID_SERIAL`, following `docs/development.md`.
Then build with the opt-in property and run the targeted StormCloudPrototypeRenderingTest plus visual checks.
An APK build or desktop composition is not an instrumentation result or proof of smooth device rendering.

## Release integration gate

Version 3.9.26 shipped the debug-only experiment and **did not change clouds in the published app**. A future version must not be described as shipping #405 until the release artifact itself is proven to use the new layers.

The shared Kotlin renderer now supports both build variants, and **release builds enable it by default**. Emanuele has [expressly authorized publication](https://github.com/Attacktive/weatherd/issues/405#issuecomment-6677621824) of the supplied artwork and described its AI-created screenshot origin. `ARTWORK_PERMISSION.md` records his grant, original attachment links, and supporting screenshot. The existing tag-triggered Release workflow downloads the hash-pinned original attachments and generates the four transparent PNGs before building, using pinned Pillow, NumPy and SciPy versions. The image downloads are explicit and never occur when the app runs; do not check in the original sheets.

For a **local release build**, first run `uv run --with pillow==13.0.0 --with numpy==2.5.4 --with scipy==1.18.1 scripts/prepare-storm-cloud-prototype.py --download --approved-release --output-dir app/src/release/assets/storm-clouds`. The `preReleaseBuild` dependency validates the permission record and all four high-definition transparent PNGs, failing the build if any is missing. The release BuildConfig field and both packaging validation tasks use the same enabled property, so normal tag-triggered builds activate and verify the renderer without extra flags. A release build also fails when cloud PNGs have been added but the renderer is disabled, preventing another no-op feature release.

Before tagging the corrected build, install and inspect the actual release APK, validate movement and landscape on a device, and verify that the APK and AAB both include matching `assets/storm-clouds/{upper,hero,distant,veil}.png` entries.
Run `python3 scripts/verify-storm-cloud-package.py` against the built release APK and AAB. Its regression tests live in `scripts/tests/test_storm_cloud_package.py` and cover missing artwork, missing rights documentation, non-RGBA PNGs and mismatched packages. Building a debug APK with `-PstormCloudPrototype=true` is not a substitute for verifying the release.

## Outstanding acceptance

Emanuele accepted the screenshot composition, requested animation verification and high-definition artwork, and expressly permitted publication of the supplied imagery. The release workflow is prepared to generate the PNGs and activate the renderer. Building and inspecting the resulting APK/AAB and checking installed release-build animation and display behavior remain outstanding.
This is not a pixel-identical reproduction of iOS Weather.
Keep #405 open until the actual release path is verified and published.
