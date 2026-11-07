# Birthday celebration

Issue: #416. This is an optional sky Easter egg, not a calendar or reminder service.

## Settings and privacy

Settings > Advanced > Just for fun contains a Birthday celebration toggle, disabled by default. Enabling it reveals the checkbox **Every day is my birthday 😉** and a month/day picker. The checkbox works without a stored date. Turning either toggle off preserves the date; Clear birthday removes it. No birth year, contact access, account lookup, notification, or additional permission is involved.

The three preferences are persisted independently, so a stale settings callback cannot overwrite an unrelated recent change. Month/day values use ISO `--MM-DD`; malformed values behave as an unset date. Appearance preset export, import, application, and reset do not include or replace personal birthday settings.

## Calendar and precedence

A selected date follows the device's local civil date, regardless of the selected weather city. February 29 is observed on February 28 in non-leap years. The every-day checkbox bypasses only date matching, not the opt-in toggle or the night gate.

Birthday eligibility is independent of the Seasonal celebrations appearance preference. An eligible birthday replaces the ordinary holiday visitor for that night, including between birthday appearances. Debug-only forced holiday selection retains precedence. Otherwise Halloween, Christmas, and New Year's existing worldwide windows remain unchanged.

Live weather, simulator, and offline fallback all use the same birthday eligibility. The effective scene must be NIGHT. No setting changes the weather, solar phase, or cached backdrop. Settled wallpaper refreshes respect the next local midnight, including daylight-saving transitions, without enabling extra animation.

## Rendering

The greeting and reused fireworks appear for 12 seconds in each deterministic 90-second slot, with a 700 ms fade at either end. The birthday banner selects an available sky band avoiding weather/location labels and the Moon, reserving space below the text for fireworks. All content respects night brightness, the existing frozen animation clock, cloud occlusion, foreground scenery, and final readability dimming.

The greeting is localized with the scene settings, including Italian **Buon compleanno!**. The banner requests Android's cursive system font with platform script fallback, adds restrained gold trim and four-point sparkles, and still measures each translated greeting against its safe sky bounds. Banner text measurements, paths, and paint objects are reused instead of allocating per frame; no font download or bundled font is required. Changing the greeting does not invalidate the backdrop cache.

## Validation

JVM tests cover defaults, independent persistence and mutation replay, invalid stored dates, preset isolation, local midnight boundaries, leap days, daylight saving, holiday precedence, shared scene builders, and deterministic frame sampling. `BirthdayCelebrationRenderingTest` covers Android canvas pixels, localized text bounds, both orientations, night dimming, and inactive windows.

Before opening a pull request, run the ordinary Android instrumentation suite on a disposable device or emulator with an explicit `ANDROID_SERIAL`, as required by `AGENTS.md`. APK assembly alone does not satisfy that gate.
