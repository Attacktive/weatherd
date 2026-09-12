# AGENTS.md

Instructions and architectural invariants for agents working in the Weatherd codebase.

## Architectural Invariants

### Debug Preview is Meant to Be WYSIWYG

The in-app preview on `HomeScreen` has two modes:
1. **Live mode (`debugEnabled = false`)**: Renders real-time conditions derived from `WeatherSceneProvider` using current weather data and clock-derived day phase.
2. **Debug mode (`debugEnabled = true`)**: A weather simulator cycling through fixed presets (`SCENE_PRESETS`).

**The debug preview is strictly meant to be WYSIWYG with the live wallpaper.**
It pins meteorological conditions (cloud cover, precipitation kind/severity, fog, thunder, wind) while preserving all user-configured display settings:
- Intensity sliders (precipitation, wind, cloud intensity)
- Scenery / backdrop selections (none, metropolis, beach, mountains, countryside, photos)
- Frame rate caps
- Photo background assignments and revisions

**Never let the debug preview drift.**
Whenever any new render parameter, intensity multiplier, scene setting, or display behavior is introduced:
1. Add it to `SceneParams` and `sceneParamsFor`.
2. Wire it into `WeatherSceneProvider` (for the live wallpaper and live preview).
3. **Always** wire it into `debugSceneParams` in `SceneDebugPresets.kt`.
4. Expose it in `HomeViewModel` from `SettingsRepository`.
5. Pass it into `debugSceneParams` in `HomeScreen.kt`.
6. Add unit tests in `SceneDebugPresetsTest` asserting that the setting reaches the generated `SceneParams`.

### Two-Phase Rendering Pipeline (`SceneRenderer`)

- `renderBackdrop(canvas, width, height, params)`: Static layers (sky gradient, overcast ceiling, fog base, haze, vignette). Cached into a `Bitmap` by `WeatherLiveWallpaperService` and `HomeScreen`; re-rasterized only when `backdropSignature(params)` changes.
- `renderForeground(canvas, width, height, params, timeSeconds)`: Dynamic animated layers (stars, celestial body, birds, clouds, scenery, fog drift, precipitation, lightning, overlay text). Redrawn every frame.
- Soft drifting layers (clouds, fog) use downscaled scrolling tiles pre-rendered into the `tiles` map, blitted with alpha and motion.

## Code Conventions

- Indent with tabs.
- No ternary operators (`? :`); prefer `if`/`switch` expressions.
- Braces on all control flow statements (`if`, `for`, `while`).
- One sentence per physical line in comments.
- Multiline comments that are not KDoc must use `/* */` blocks.
- Empty line after multiline expressions and closing braces before other statements.
- No empty line after opening braces.
