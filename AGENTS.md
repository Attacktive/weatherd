# AGENTS.md

Instructions and architectural invariants for agents working in the Weatherd codebase.

- Version: 1.2.0 (2026-09-18)
- Persona: Coding assistant pair-programming with the user on Weatherd.

## Tools and Environment

Agents use standard file inspection, editing tools, and Gradle tasks (`./gradlew check`).
- The JDK is installed via SDKMAN.
- Before Gradle tasks in non-interactive shells, source `"$HOME/.sdkman/bin/sdkman-init.sh"`.

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

To prevent the debug preview from drifting, whenever any new render parameter, intensity multiplier, scene setting, or display behavior is introduced:

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
- Cloud decks use `CloudLayer` with original, lazily decoded `drawable-nodpi` textures and repeating bitmap shaders; their transforms, opacity, and day-phase tints change without rerasterizing the textures.
- `scripts/generate-cloud-textures.py` reproducibly regenerates every cloud asset with `uv run scripts/generate-cloud-textures.py` and samples no third-party artwork. There are two families:
	- `cloud_sheet_far` / `cloud_sheet_near` are the churned overcast and precipitation sheets, drawn by `drawCloudDrift`.
	- `cloud_cumulus_sparse` / `_scattered` / `_broken` / `_far` are the clear-sky decks, drawn by `drawScatteredClouds`.
- A texture spans `CLOUD_TEXTURE_VIEWPORTS` viewport widths before repeating unless a deck passes its own `viewports`. A shorter span shrinks the sampled features, which is the only handle a deck has on apparent cloud size.

### Cloud Coverage Belongs to the Texture, Never to the Paint Alpha

How much cloud a clear sky holds is chosen by picking among the baked coverage steps and cross-fading between them, never by scaling a deck's alpha.

Alpha scales an entire deck uniformly, so raising it cannot add a cloud; it can only make the same cloud less transparent. A deck that leans on alpha for coverage caps its own densest pixel below opaque, and a sunlit crown that cannot reach white reads as haze no matter how the texture is tuned. That failure is invisible in the Kotlin and only shows up on a device, which is why `CloudLayerTest` asserts that every cumulus texture contains fully opaque pixels and that the three steps cover progressively more sky.

The corollaries:

- `CUMULUS_NEAR_ALPHA` stays near 255. `params.cloudScale` is the user's opacity preference and is the only thing that should pull it down.
- The coverage steps share one noise seed, so a lower cut grows each mass rather than replacing it. Cross-fading draws the lower step at full alpha and the next over it at the blend weight; keep that order or the shared core goes translucent mid-fade.
- The far deck is the exception, and only because distance is carried by haze and size: it has one texture and does thicken with coverage.
- The textures bake their own sunlit-to-shadow ramp, so `cumulusTint(DayPhase.DAY)` is pure white. Tinting daylight applies the shading twice and grays the crowns.
- A dry sky keeps its full clear-day blue until `OVERCAST_GRAY_FLOOR`, where the overcast ceiling starts drawing. Graying earlier leaves white clouds with nothing to read against.

### Preview Cloud Work Before Building

`uv run scripts/preview-clear-sky.py [out.png]` renders the clear-sky decks over the sky gradient on the desktop, so a texture or tuning change can be judged in seconds instead of a build-and-install round trip.

It is a design aid, not a test: it re-implements a slice of `SceneRenderer` in Python and will drift unless the deck geometry, alpha ramp and tint are mirrored into it whenever the Kotlin changes. Where the two disagree, the Kotlin is right.
- Fog retains downscaled scrolling tiles pre-rendered into the `tiles` map and blitted with alpha and motion.

## Code Conventions

- Do not hard-wrap code only because it is too long; soft-wrapping exists.
	- This includes comments; keep one sentence per physical line.
	- Exception: a function call with many arguments may wrap with one argument per line.
		- This applies even when an argument is a callback; each argument stays on its own line instead of being hugged by a trailing `, arg)`.
		- When wrapped, start the arguments on the following physical lines and put the closing delimiter on its own line.
- Prefer `if`/`switch` expressions over the ternary operator (`? :`).
- A multiline expression requires an empty line after it before the next statement.
- In brace-based languages, a closing brace requires an empty line before other statements.
	- Exception: a multiline `is` branch inside a Kotlin `when` clause.
- Do not put an empty line immediately after an opening brace.
- Always put braces around `if`, `for`, `while`, and similar control-flow bodies.
	- `if (true) return` is wrong.
	- A `case` block is the exception; add braces only when block scope is genuinely required, such as for a `let`, `const`, `class`, or function declaration.
- Indent code with tabs.
- Do not insert an empty line between a simple variable declaration and the `if` statement immediately checking it.
- Multiline comments that are not KDoc/JSDoc should use `/* */` blocks.

### JavaScript / TypeScript

- Prefer TypeScript over plain JavaScript.
- Use single quotes for strings; JSON stays double-quoted.
- Prefer template literals to string concatenation.
- Prefer `T[]` over `Array<T>`.
- Use JSDoc (`/** */`) for comments documenting declarations; reserve `//` for inline notes in a function body.
- Prefer separate `import` and `import type` statements over inline type imports such as `import { a, type b }`.
- Prefer grouped exports such as `export { a, b }` or other export-once patterns over scattering `export` keywords inline.
- Avoid inline object types in parameters, variables, and return types; prefer dedicated named `interface` or `type` definitions.
- In interfaces and type literals, put an empty line before an index signature such as `[key: string]: unknown`.
- Drop return type annotations when they can be fully inferred or when they significantly decrease readability.

### Testing

- Wrap test declarations with multiline arguments:

```ts
it(
	'description',
	() => {
		// body
	}
);
```

- Format assertions with the matcher method on its own indented line:

```ts
expect(actual)
	.toBe(expected);
```

### YAML

- Default to the `.yaml` extension over `.yml`.
	- This applies to GitHub Actions workflows even though `.yml` is more common; GitHub accepts either.
	- Exception: when a tool only recognizes `.yml`, use `.yml`; for example, Codacy requires `detekt.yml` and ignores `detekt.yaml`.
- Wrap simple string values in single quotes, such as `name: 'Sync platform branches'`, `runs-on: 'ubuntu-latest'`, and `branches: ['main']`.
	- Leave mapping keys, booleans such as `fail-fast: false`, and numbers such as `fetch-depth: 0` unquoted.
	- Never quote a value containing a `${{ ... }}` expression; `branch: ${{ matrix.branch }}` stays bare.
	- Block scalars such as `run: |` are not simple values; the shell inside keeps its own quoting.
