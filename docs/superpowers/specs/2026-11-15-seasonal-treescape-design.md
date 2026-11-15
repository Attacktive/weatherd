# Issue #394 — Seasonal Treescape

## Intent

Add an optional Treescape scenery inspired by the contributor's flower meadow and low, asymmetric two-tree composition. Keep Weatherd's animated sky and weather rather than reproducing a static phone screenshot. The contributor authorized redistribution and explicitly requested seasonal changes.

## Approaches

- **Selected: integrated Treescape scenery.** Give the meadow and its two trees their own composition. This follows the supplied reference without replacing current scenery or foreground tree styles. It requires preparing seasonal artwork and clean alpha edges.
- **Not selected: Meadow scenery plus existing foreground trees.** Reuses current tree assets and seasonal behavior, but changes the reference's proportions and tree silhouettes.

## User-visible contract

- Add Treescape to the Scenery selector. Do not change the current selection or defaults.
- Treescape always includes its own meadow and two trees. The existing Trees toggle remains an independent optional foreground overlay; turning it off does not remove Treescape's built-in trees.
- Reuse the existing Tree season preference rather than introduce a second season setting. Show the season selector whenever Treescape is selected or the optional foreground trees are enabled. Show it only once when both are active. Label/help text should make clear that it controls Treescape as well as optional trees.
- Preserve Auto, Spring, Summer, Autumn, and Winter choices and the existing Auto resolution and missing-field compatibility. With both Treescape and optional trees active, they share the season.
- Spring: green flower meadow and fresh foliage. Summer: richer green grass and foliage. Autumn: muted meadow with bronze/rust foliage, closest to the submitted tree colors. Winter: frost/snow coloration across meadow and canopy. Winter does not introduce leaf shedding or new branch geometry.
- Preview, live wallpaper, simulation, and offline/fallback scenes show the same selection and season. Appearance presets retain the selected scenery and season.

## Artwork and composition

- Treat the supplied images as source material, not finished full-screen backgrounds. Remove phone chrome, Sun/halo/aircraft, and the original sky from derived artwork.
- Preserve a broad meadow foreground, a larger tree left of center, a smaller right tree, and open upper sky. Keep each trunk grounded.
- Preserve artwork proportions. Adapt placement/cropping to portrait, landscape, and launcher scrolling without stretching trees or leaving uncovered bottom/side strips.
- Separate meadow and tree material masks as needed to vary seasons without recoloring trunks or making flowers the same color as leaves. Do not create seasonal pixels during rendering.
- Keep pristine contributor sources and a deterministic preparation script with explicit masks/parameters. Generated resources must be reproducible from those inputs; credit the contributor and record permission provenance.
- The supplied screenshot resolution and baked-in daylight/shadows limit fidelity. First inspect rendered seasonal samples before accepting the artwork; do not hide halos or low-quality cutouts with extra fog, blur, or overlays. If clean extraction is not viable, consult before substituting newly generated artwork or changing scope.

## Rendering and integration

- Use a dedicated shared scenery layer following the existing bitmap scenery path, not a second preview-only renderer.
- Decode/prepare only selected assets off the frame loop. Publish immutable prepared resources, reuse cached geometry/material filters, and invalidate both production surfaces when preparation completes.
- Apply existing near-scenery environmental lighting: daylight color preservation, continuous dawn/dusk transitions, darker nights honoring night brightness, and weather desaturation/fog attenuation. Do not apply mountain-only alpenglow.
- Keep fog and precipitation in front of the scenery. Preserve independent screen effects, labels, and existing scene behavior.
- Extend existing scenery persistence and preset paths without renaming stored values or adding a new season preference. Inspect exhaustive enum consumers and compatibility tests before editing.

## Non-goals

No replacement of Evergreen/Broadleaf trees, no static captured sky, no new wind animation or scenery decorations, no network requests for seasons, no new dependencies unless separately justified, and no changes to default appearance.

## Acceptance and verification

- Visually inspect actual Android rendering for each season, clear daylight, dusk, night, overcast/rain, and fog, including portrait/landscape and launcher-scroll edges. Confirm the preview and live wallpaper share the composition.
- Exercise selection, season-control visibility with optional trees off/on, and preset restoration. Cover uncertain compatibility edges with focused tests rather than source-text/wiring assertions.
- Run repository JVM/lint/Detekt checks and ordinary local Android instrumentation on a disposable, non-provider-evaluation emulator with explicit ANDROID_SERIAL before opening a PR. Record commands and results; do not create a PR if local instrumentation cannot run.
- Review changes against coding conventions before committing. Use Gremlin for GitHub mutations, do not merge without authorization, update roadmap #194 only to the actual delivery state, and clean up task-created local branches/worktrees at completion.

## Implementation discretion

Resource names, asset format, mask representation, caching details, and exact seasonal color parameters are routine implementation choices. Scope changes, replacement artwork, a separate season setting, or changed existing tree behavior require consultation.
