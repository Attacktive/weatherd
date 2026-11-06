# Issue #405: local storm-cloud prototype

This is an unpublished, opt-in Android implementation, not generated concept artwork.
It targets Emanuele's [layered-cloud request](https://github.com/Attacktive/weatherd/issues/405), independently of #398's dry partly-cloudy correction and #406's rain-origin request.

## Scope and containment

The shared SceneRenderer invokes the prototype only inside its existing rainy cloud-deck path.
Preview and live wallpaper therefore use the same implementation, including the simulator.
The supplied central cloud remains a separate sprite from the upper dark bank and distant lower strip.
Every sprite uses one uniform scale, preserves its source aspect ratio, and has bounded independent horizontal motion instead of tiling, vertical bobbing, or alpha pulsing.
The central cloud is uniformly scaled down in its own aspect ratio and raised within the deck; the derived debug PNGs taper the lowest hero/distant alpha rather than clipping at the near-opaque cloud bases. A reduced-opacity, soft atmospheric veil extends behind their lower edges without repainting the detailed RGB artwork.
The three detailed planes share one sky-depth coordinate system: the dark upper bank meets the screen edge, the central mass overlaps it, and the distant strip overlaps the main cloud's feathered lower edge instead of forming an isolated row.
The entire composition scales together with the cloud-size control.
At default size the sources extend beyond both viewport edges throughout their bounded drift; wide displays crop uniformly scaled artwork rather than flattening or repeating it.
Natural daytime rain receives a muted, slightly darker sky grade in the cached palette path; the existing daylight ramp fades that grade through dawn/dusk and leaves night colors unchanged.
Custom and other built-in palettes are untouched, and photo drawing still bypasses the procedural sky.
Wind scale zero freezes the motion.
Textures decode once in the existing background cloud-prewarm step; draw callbacks reuse geometry, paint and color-filter state.
The existing opacity, size, contrast and day-phase/sky-brightness tint continue to apply.
Precipitation drawing is unchanged; #406 is not implemented here.
Dry partly-cloudy and overcast scenes, snow, sleet and fog without rain keep the previous renderer.

Normal debug builds leave the prototype disabled.
Release builds compile a separate no-op implementation and cannot include the debug-only PNG assets, even when the Gradle property is supplied.
No settings key, preset schema, application version, release workflow or tag changes are needed.

## Prepare the supplied artwork

Run from the repository root:

```sh
uv run --with pillow --with numpy --with scipy scripts/prepare-storm-cloud-prototype.py --download
```

Alternatively, provide the original attachments as `sheet.png` and `hero.png` in a directory and pass `--source-dir <directory>` instead of `--download`.
The script verifies the original attachment hashes and retains the originals outside the Android source sets.
It extracts the sheet's upper and lower bodies using connected components and nearest-core assignment, not fixed cuts through overlapping clouds, and uses the separate hero attachment for the center.
Cloud detail retains its original RGB; the debug-only derived hero/distant PNGs smoothly attenuate alpha at their lowest edges, and low-alpha export noise, saturated fringes, and detached artifacts are suppressed.
The two original files are used for local evaluation only; this does not establish their redistribution rights.
The output manifest records source and processed hashes.

Prepared files go into ignored `app/src/debug/assets/storm-clouds/`.
An opted-in debug build fails early if any of the four prepared PNGs is missing.
No network download occurs during Gradle builds or at app runtime.

## Build and exercise

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PstormCloudPrototype=true
```

The application ID is unchanged: use a disposable test device/emulator rather than overwriting a personal installation.
Choose rain or thunderstorm in Advanced's scene simulator, then compare portrait and landscape, day/night, multiple wind strengths, and cloud opacity/size/contrast settings.
The existing gray weather backdrop and rain remain visible beneath the new banks.
Build without `-PstormCloudPrototype=true` for the unchanged baseline.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:detekt
./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets -PstormCloudPrototype=true
uv run --with pillow --with numpy --with scipy python -m unittest discover -s scripts/tests -p test_storm_cloud_assets.py -v
```

The new Android tests use synthetic transparent fixtures, so they do not require the reporter's files or change baseline test expectations.
Before opening a PR, run the ordinary Android suite locally on exactly one disposable device with explicit `ANDROID_SERIAL`, following `docs/development.md`.
Then build with the opt-in property and run the targeted StormCloudPrototypeRenderingTest plus visual checks.
An APK build or desktop composition is not an instrumentation result or proof of smooth device rendering.

## Outstanding acceptance

Actual Android captures, affected-device appearance, smoothness and performance remain to be verified.
This is not a pixel-identical reproduction of iOS Weather.
Keep #405 open and do not release or promote this prototype without a subsequent explicit decision.
