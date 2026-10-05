# Mountain scenery lighting design

## Intent and scope

Issue: https://github.com/Attacktive/weatherd/issues/77

Make the existing procedural mountain scenery visually belong to the same world as the sky through physically inspired, painterly shading.
The user approved a mountain-first approach that preserves the seeded silhouettes, aspect-ratio adaptation, snowcap shapes, and FAR/NEAR depth planes.
This change must deliver complete treatment of rock, snow, forest, meadow, atmospheric depth, and localized contact shading for the mountain scene.
Beach water, countryside materials, and metropolis facade improvements are outside this implementation; issue #77 remains open for those scenes.

No new display settings, dependencies, third-party artwork, 3D renderer, or GPU-only rendering path are required.
The preview and wallpaper must continue using one implementation and the existing scene simulator inputs.

## Existing behavior and constraints

- `BackdropScenery.kt` constructs deterministic outlines and glyphs; the mountain scene contains FAR rock and snowcaps, followed by NEAR forest and meadow.
- `SceneRenderer.kt` caches paths by scene and surface dimensions, then fills each layer and glyph with one `sceneryLayerColor` value.
- `ScenePalette.kt` blends intrinsic material colors toward sky-derived plane tones according to day phase and weather.
- Scenery draws after celestial bodies and clouds, but before foreground fog, precipitation, lightning, and labels.
- The wallpaper prefers a hardware Canvas and falls back to a software Canvas.
- `renderImmutableBitmap` provides immutable cached images suitable for hardware-recorded Canvas commands.

These constraints rule out moving scenery into the static sky backdrop: doing so would put clouds in front of the terrain and lose the current inter-plane animation ordering.
They also rule out requiring a runtime shader that cannot render through the software fallback.

## Decision and alternatives

Use cached 2.5D surface patches and material fields inside the existing silhouettes.
The patches have approximate surface orientations and soft boundaries; their illumination follows the sky's celestial position and weather.
They are not reconstructed physical terrain, and the design does not claim physically accurate cast shadows.

Replacing flat fills with vertical gradients alone does not provide enough interior volume or material differentiation.
A full height-map lighting engine or 3D scene would introduce unnecessary backend, memory, and integration work for this target.
Coarse, softly blended surface patches provide controllable landscape volume without evaluating a normal map at every screen pixel every frame.

## Ownership and data flow

Introduce a focused internal `SceneryRenderer` that owns scenery paths, prepared surface patches, immutable material images, scenery details, and the existing scenery animations.
`SceneRenderer` continues to own the sky/weather pipeline and calls this renderer at the current scenery position in `renderForeground`.
Move existing scenery-only helpers and cache fields rather than leaving duplicate implementations or forwarding aliases.
Preserve the appearance and behavior of the other procedural scenes during this extraction.

`BackdropScenery.kt` remains the geometry source.
Prepared mountain surface data is derived alongside the existing outlines without consuming their random streams or changing their coordinates.
Snowcap glyphs reference the same prepared rock surface beneath them, so both materials receive consistent orientation-dependent illumination.

Derive a compact lighting value from `SceneParams`, surface dimensions, and the same celestial positioning and sky-palette calculations already used by the sky.
Extract shared pure calculations when necessary rather than introduce another sun trajectory or weather interpretation.
The lighting value contains an incoming light direction, direct-light color and strength, ambient sky color, and atmospheric/detail attenuation.
No independently persisted terrain lighting settings are introduced.

## Surface construction and materials

For each mountain massif, derive broad ridge and valley patches anchored to its crest, shoulders, and saddles.
Assign each patch an approximate surface normal and prepare a feathered mask; keep boundaries soft enough to avoid a low-poly triangle appearance.
Use separate stable seeds for surface variation, and clip every material and shading pass to its existing layer or glyph shape.
Sample the same parent rock patch field for snow instead of assigning a separate arbitrary snow lighting direction.

- Rock: subdued slope-aligned strata and crevice variation over broad lit and shaded faces.
- Snow: smooth material variation, warm illumination where exposed, and cooler shaded folds consistent with the rock beneath it.
- Forest: overlapping low-frequency canopy clusters, not individual animated trees or uniform pixel grain.
- Meadow: broad, low-contrast color and illumination patches, not individually drawn grass blades.

Texture coordinates remain attached to the virtual scene, including wallpaper scrolling, rather than to the visible viewport or animation clock.
Material fields do not regenerate when celestial progress, weather, or day phase changes.

## Illumination, depth, and contact shading

Compute each patch's directional response from its fixed normal and the current incoming light direction, with a nonnegative diffuse term plus ambient illumination.
Color exposed surfaces with the direct-light component and shaded surfaces with the ambient sky component; apply atmospheric blending after local illumination.
Use current phase and celestial progress for twilight warmth and visibility rather than switch between unrelated whole-scene colors at phase boundaries.

Clear weather retains readable directional relief.
Overcast, precipitation, fog, and thunder reduce direct illumination and surface contrast using the same meteorological interpretation as the sky.
At night, sunlight is absent and material detail is subdued; preserve the established silhouette-like night composition rather than add strong independent moon lighting.
The sun-artwork visibility preference does not alter terrain illumination; celestial timing and meteorological conditions remain the lighting inputs.

FAR rock and snow receive stronger sky-color integration, weaker texture contrast, and a prepared soft edge mask.
NEAR forest and meadow retain greater definition without becoming brighter than the weather permits.
Integrate these attenuation changes with the existing inter-plane haze and valley mist rather than add a second unrelated fog system.
Contact shading is a restrained, feathered darkening on the receiving meadow near the forest/meadow boundary, clipped to that receiver.
It must not become an outline around every layer or a shadow painted into the sky.

## Cache and lifecycle rules

Keep geometry/material preparation separate from lighting state.
Scene and virtual surface dimensions invalidate outlines, patch masks, material images, and contact/edge masks.
Changes to relevant phase, celestial progress, weather, and sky palette inputs invalidate only derived lighting and reusable paint/shader state.
Animation time advances existing moving details and mist but does not invalidate static surface data.

Use bounded, downscaled mask and material rasters covering terrain rather than allocate full-screen images for every patch.
Retain only the active scene's prepared resources, not a growing dictionary of weather or celestial-progress variants.
Publish immutable bitmap sources through the existing immutable-rasterization pattern and do not mutate them after publication.
Do not recycle an image while hardware-recorded Canvas commands may retain it; release obsolete references consistently with the existing bitmap lifecycle.
Steady-state frames must perform no avoidable bitmap, path, gradient, array, color-filter, or mask allocation and no noise generation or blur computation.
Rebuild work belongs to cache invalidation, not the animated draw loop.

## Verification and acceptance

Capture the current Android renderer before implementation using fixed simulator inputs and identical seeded geometry.
Capture the changed renderer under the same inputs in clear daylight, dawn, dusk, overcast, fog, precipitation, thunder, and night.
Exercise intermediate celestial progress values, portrait and landscape surfaces, wallpaper scrolling, and both preview and live wallpaper.
Use a disposable emulator for installation and instrumentation; do not uninstall or replace the user's existing wallpaper installation for baseline captures.

Acceptance requires all of the following:

1. Outer silhouettes and snowcap boundaries retain their existing deterministic geometry and aspect-ratio behavior.
2. Mountain masses read as broad volumes through interior relief, not as flat fills with a grain overlay or hard triangular facets.
3. Rock and snow share coherent illumination, and forest/meadow remain distinct without looking like unrelated color bands.
4. FAR material detail recedes into the actual sky/haze while NEAR contact shading stays localized and does not produce seams or halos.
5. Weather and night suppress direct-light contrast appropriately, and changing celestial progress produces no visible lighting pops or texture swimming.
6. Simulator changes reach preview and wallpaper through the same rendering path, and the other scenery modes retain their existing behavior.
7. Hardware and software Canvas rendering both exercise the new material and shading paths successfully.
8. Measure frame timing and retained bitmap memory against the captured baseline on the same device, viewport, and preset; reject sustained missed configured frame deadlines where the baseline met them, retain only bounded active-scene resources, and report observed values instead of claiming an unmeasured performance improvement.
9. Add focused regression tests only for uncertain consumer-visible behavior such as directional response, weather attenuation, and consistent parent-surface sampling; do not test helper forwarding, source text, or incidental pixel wording.
10. Run the repository's existing checks and inspect the actual rendered comparisons; tests alone do not establish visual acceptance.

After visual smoke evidence, update durable rendering invariants in `AGENTS.md` and the user-facing rendering description in `README.md` without creating a second set of agent instructions.
Remove throwaway capture scaffolding from tracked changes and include the relevant comparison evidence in the implementation pull request.
The next stage is a written implementation plan after the user reviews this specification; this document is not a claim that rendering has been implemented or measured.
