# Development Reference

Detailed procedures and feature contracts supplement the core rules in [AGENTS.md](../AGENTS.md).
Read the relevant sections before changing the corresponding tools or rendering behavior.
Run commands from the repository root.

## Developer Procedures

### Local Checks and Instrumentation

Run the pull-request checks with `./gradlew test :app:lint :app:detekt`.
The existing CI Test gate builds the debug app and instrumentation APKs alongside unit tests, lint, and Detekt while the emulator starts; instrumentation remains required after that gate succeeds.
Ordinary connected tests exclude the opt-in `ProviderEvaluationCaptureTest` and require exactly one device selected with `ANDROID_SERIAL`.
Task-level `--serial` is rejected: the pinned AGP version mutates its immutable CLI-serial list when a device matches, throwing `UnsupportedOperationException`; environment-based selection avoids that broken path.
The read-only preflight rejects the reserved `Pixel_Weatherd_Provider_Evaluation` AVD even when explicitly selected, so scheduled captures cannot be interrupted by ordinary Gradle instrumentation.
The experimental `android.experimental.androidTest.builtin_test_platform=true` option is unsupported and rejected during configuration, before device lookup; ordinary instrumentation retains the current test engine.
Use a separate disposable emulator, substituting its serial below; connected-test teardown still removes Weatherd and its app data:

```sh
ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest
```

For an existing emulator installation, update both APKs in place and invoke instrumentation directly:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e notClass xyz.attacktive.weatherd.data.provider.ProviderEvaluationCaptureTest xyz.attacktive.weatherd.test/androidx.test.runner.AndroidJUnitRunner
```

Replace `emulator-5554` with the intended device serial; the data-backup and wallpaper-binding warnings in [AGENTS.md](../AGENTS.md#tools-and-environment) still apply.
Direct `adb` invocation bypasses Gradle's preflight and default filter: keep the explicit `notClass` argument and never target the reserved evaluation AVD for an ordinary suite.
The provider-evaluation harness below remains the explicit opt-in capture path; do not set `providerEvaluation=true` merely to silence an ordinary test report.
Settings navigation tests wake the device, dismiss a nonsecure keyguard, and retain a bounded 320×640 dp activity window through recreation so large displays exercise scroll restoration rather than skip it.
Navigation timeouts include activity state, the active accessibility tree, and window-manager diagnostics; assert the saved anchor remains visible with the first section off-screen, not exact pixel coordinates.
Sunbeam fixtures pin the UTC cloud-placement day and compare rays over the same unobscured pixels each frame so foreground cloud occlusion is not mistaken for a ray-field discontinuity.

### Diagnostics and Report Collection

**Settings → Advanced → Diagnostics** provides a reusable opt-in local journal and report-sharing flow.
The current instrumentation covers refresh-widget investigations such as [#284](https://github.com/Attacktive/weatherd/issues/284) and related app-activity lifecycle events; other features can record events through the same `Diagnostics` recorder without a separate logger.
Capture is off by default, independent of appearance presets, and retained across process restarts and in-place app updates until explicitly disabled.

For the current widget investigation (#284):

1. Enable **Capture diagnostic events**, ideally before the next app update.
2. Leave the failing widget in place and tap it once; note the approximate time and whether Weatherd opened.
3. Return to **Advanced → View and share report**, review the selectable text, then tap **Share**.
4. Use Android's chooser to copy or send the report, then paste the text or attach a saved copy to #284; nothing is uploaded automatically.
5. Disable capture when finished; this keeps existing evidence. **Clear diagnostic logs** requires confirmation and removes the retained records without changing the capture toggle.

Installing an instrumented update or re-adding the widget can change the failing launcher state; a successful tap afterward does not disprove the original report.
If the failure remains, collect it before re-adding the widget, then obtain a second report after a successful tap for comparison.

The private, no-backup journal retains up to 64 KiB in two bounded generations.
Serialized background writes record widget lifecycle/publication attempts, receiver delivery, WorkManager enqueue requests and completions, worker IDs/attempts/outcomes, and sanitized activity lifecycle/intent metadata.
The report adds app/Android/device/launcher versions, wall and monotonic timestamps, process-session IDs, per-session sequences, and storage/loss/rollover health.
It excludes coordinates, place names, weather payloads, arbitrary external intent strings/data/extras, and exception messages.
Enqueue completion under `KEEP` does not prove that the proposed work ID was accepted; correlate it with the actual worker ID.
An update marked `sent` establishes only the app-side publication call, not launcher consumption.
The journal is best-effort: abrupt process death can lose queued events even with zero reported drops, and missing app-side events cannot establish the launching caller or a launcher-specific fallback.
System activity-launch logs may still be needed; do not close #284 merely because a generic emulator capture succeeds.

### Automated Station Evaluation

The local harness uses the real Android provider-comparison service and report formatter on a dedicated emulator, without changing release behavior or provider defaults.
Requires Python 3.10+, the Android SDK, and debug app/test APKs.
The configured SDK directory is trusted local developer configuration; only allowlisted `adb` and emulator binaries execute, without a host shell.
Use a verified Italian station's exact published coordinates via the existing manual-location settings (`MANUAL_STATION`), not a nearby city center.
Station IDs contain four ICAO letters, such as `LIRF`; lowercase is normalized before fixed-host HTTPS observation requests.
GPS, device-location permissions, and an onsite tester are not required; keep the simulator disabled and **Tester observed: UNSURE**.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/evaluate-providers.py clone --source Pixel
python3 scripts/evaluate-providers.py boot --port 5556
python3 scripts/evaluate-providers.py install --serial emulator-5556
python3 scripts/evaluate-providers.py capture --serial emulator-5556 --station LIRF
python3 scripts/evaluate-providers.py collect --serial emulator-5556 --station LIRF --count 0
```

- The headless clone is `Pixel_Weatherd_Provider_Evaluation`; preserve the source AVD hardware/system-image configuration but start with fresh app data, never copied userdata, snapshots, accounts, or photos.
- Creation retains the pristine source config, generated config/registry, and creation ledger under `captures/provider-evaluation/emulator/`.
- Refuse existing AVD targets rather than replacing them; mutating device commands require an explicit emulator serial whose AVD name matches the evaluation clone, never the original `Pixel` or physical devices.
- Choose a free even port if `5556` is occupied and use its corresponding serial throughout.
- `collect` runs in the foreground at fixed UTC boundaries, every 30 minutes by default; keep the host and clone running and stop with Ctrl+C, without a login service or concurrent collectors on the same clone.
- `--count 1` runs one scheduled capture; `--interval-seconds` changes cadence; scheduled runs default to `ROUTINE`, while one-off `capture` defaults to `DIAGNOSTIC`, excluded from summaries.
- Each unique `captures/provider-evaluation/runs/` directory retains unchanged `report.txt`, all provider statuses, `capture.json`, station metadata/source/retrieval time, instrumentation output, and observation/match revisions, including failed-collection diagnostics.
- Store public station coordinates separately from the privacy-preserving original report; upload nothing automatically.
- Observation-fetch failures or missing time matches leave runs `PENDING`; retry on the next scheduled tick without replacing the original report.
- Bound matching to 48 hours after capture or 96 total attempts, including the initial attempt; append an `EXPIRED` revision with the last pending reason/count when exhausted, or immediately when outside the observation API's 30-day recent-retention window.
- Never fetch or rewrite expired matches; explicit rematching of previously matched captures can pick up corrections within the same bounds.
- A failed, missing, or bounded optional rematch preserves the usable `MATCHED` revision and scores; record it separately in `rematch-*.json`, using `SKIPPED` for bounds and the attempted match status otherwise, without requeueing it.
- Successful rematches may replace the scored revision, including corrected measurement eligibility.

```sh
python3 scripts/evaluate-providers.py match captures/provider-evaluation/runs/<run-directory>
python3 scripts/evaluate-providers.py summarize
PYTHONPATH=scripts python3 -m unittest discover -s scripts/provider_evaluation -p 'test_*.py'
```

Observations come from the [NOAA Aviation Weather Center API](https://aviationweather.gov/data/api/); retain raw METAR/SPECI records and use an inclusive ±15-minute tolerance with signed offsets.
Exact distance ties prefer the earlier observation independently of response order, for both capture-time and forecast-valid-time matches; keep those two comparisons separate.
Accuracy summaries deduplicate routine station/observation pairs and use shared eligible paired denominators against ItaliaMeteo.
Provider availability is successful samples divided by all obtained samples for that provider in eligible routine captures, without observation deduplication.
Report routine collection success separately as completed collections divided by all routine attempts, including failures before provider samples; incomplete collections are not successes.
Emitted `scoring_rules` freeze the pilot cloud-category mapping: automated or ambiguous sky reports are unscorable, never clear, and forecast trends are not observations.
Corrected reports require resolvable revision ordering; cloud categories remain unscorable because `COR` can replace the automation flag, while eligible temperature observations remain usable.
Evaluate temperature separately; cloud heights are not low/mid/high cover measurements, precipitation amounts are not scored, and descriptive results do not establish a provider ranking.
The instrumentation collector skips ordinary suites unless explicitly opted in by the harness.

### Google Play Vitals Monitoring

The existing `.github/workflows/google-play-vitals.yaml` runs daily at 00:37 UTC and supports `workflow_dispatch`.
It retrieves crash/ANR clusters using [Attacktive/google-play-vitals-action](https://github.com/Attacktive/google-play-vitals-action), comparing against the previous successful `play-vitals-snapshot` artifact; missing or expired artifacts establish a baseline without false-positive alerts.
Each run writes a job summary and a JSON report retained for 90 days; it does not file issues or dispatch notifications.

Prerequisites:

1. Enable the Google Play Developer Reporting API.
2. Give the Google Cloud service account **View app information (read-only)** permission in Play Console.
3. Store its JSON key as `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, reusing the deployment secret.
4. Enable the IAM API and IAM Service Account Credentials API in the service account's project.
5. Grant the account `roles/iam.serviceAccountTokenCreator` on itself per the [authentication action's access-token setup](https://github.com/google-github-actions/auth/tree/7c6bc770dae815cd3e89ee6cdf493a5fab2cc093#inputs-service-account-key-json).

## Rendering and Artwork

### Scenery and Tree Artwork

Trees are an independent fixed foreground composition over any backdrop, including None and Photo, not a backdrop or seasonal landscape.
Evergreen uses the multi-tree conifer composition; Broadleaf uses an inset left hero and a smaller independently grown right companion, leaving the upper-middle sky open.
Keep the hero crown fully visible in portrait, landscape, and launcher scrolling without reducing scale; both trunks remain grounded.
Mountains and both tree shapes share weather/day-phase lighting: preserve clear-day artwork colors, warm dawn/dusk continuously, desaturate overcast/precipitation, keep snow lighter than rain, and soften distant mountains in fog more than nearby foliage.
At night, reduce mountain-blue saturation while retaining ambient foliage detail; night brightness dims both, easing through dawn/dusk without changing alpha or placement.
Do not per-pixel grade custom photos; retain their weather overlays/night dimming and grade any trees over them normally.
Fog and precipitation draw in front.
Regenerate first-party sources with `uv run scripts/generate-tree-textures.py` and `uv run scripts/generate-broadleaf-textures.py`; Broadleaf authors separate trunk/branch/canopy structures and supports `--variant hero` or `--variant companion`.
Decode only the selected style on first use off the frame loop; never copy reference-app or third-party artwork.

### Sun, Moon, and Optical Effects

- Classic is procedural; Radiant uses the [unchanged submitted PNG](https://github.com/user-attachments/assets/68a92bcf-016d-4276-bc35-4c1e38ae6b78) credited in the [README](../README.md#artwork-credits); Starburst is first-party artwork inspired by [#291](https://github.com/Attacktive/weatherd/issues/291), not sampled from its iOS reference.
- All sun styles honor visibility, size, color, weather veiling, and sunset fade; natural daylight uses a white source, while low sun and explicit colors retain their tint.
- Orange uses a pale peach-orange source inspired by [#308](https://github.com/Attacktive/weatherd/issues/308), shared by Classic, Radiant, and Starburst. Classic retains that tint through its core and shoulder rather than bleaching a white ring around a warmer center; Natural, White, and Golden retain their existing treatment. Select Orange under Appearance → Sun & moon → Sun color; the reference's rainbow remains the independent Lens flare effect from #290.
- Prepare Radiant's immutable softened texture once on first use off the frame loop, preserving its bright core/short rays but fading outer haze and distant baked reflections before the boundary; keep extra wide halos restrained.
- Generate Starburst with `uv run scripts/generate-starburst-sun.py`: broad overlapping luminous lobes and faint uneven streaks, not a pointed star; fade before the texture boundary and decode only when selected, off the frame loop.
- Never bake rainbow reflections into Starburst; Lens flare controls the separate arc/reflections and Motion-responsive reflections controls motion independently.
- Generate the north-up near-side Moon with `uv run scripts/generate-moon-texture.py` from unchanged `scripts/assets/moon/lroc_color_2k.jpg`, checking its SHA-256 before projection; retain NASA attribution from the [README](../README.md#artwork-credits).
- The image in [#283](https://github.com/Attacktive/weatherd/issues/283) is reference only; waxing/waning changes illumination without mirroring markings, and new Moon draws neither disc nor halo.
- Moon size is independent, 50–200%, default 100%; change draw geometry without regenerating phase sprites or the static backdrop.
- Sunbeams offers Off, Through clouds (default), and Clear skies too; moving near low-cloud openings generate soft spreading shafts, never distant openings.
- Clear skies too adds source-fixed radial glare; low/mid cover attenuates it but high cirrus alone does not.
- Cloud-driven shaft strengths and clear-glare attenuation ease over elapsed animation time with a 1.2-second response, independent of frame rate; neighboring opening samples cross-fade rather than instantly replacing lit shafts. Keep cloud geometry/speed and independent lens-reflection occlusion unchanged. History belongs to each renderer, resets for scene/viewport changes (including celestial-progress edits while animation is paused), and initializes from the current samples on first draw, clock reversal, or gaps over two seconds; suppression remains immediate.
- Night, hidden sun, precipitation, fog, thunder, and opaque decks suppress enabled sunbeams; Off removes shafts/extended glare but preserves the selected sun, compact corona, and independent lens flare.
- Cache cloud-shaft softness in a small first-party sprite, transforming each frame without texture generation or blur.
- Generate shared clear-sky glare with `uv run scripts/generate-sun-glare.py`: a hexagonal aperture's squared Fourier amplitude convolved with a finite solar disc and averaged over three wavelengths.
- Follow [aperture diffraction](https://www.nature.com/articles/s41598-018-34400-z) rather than swaying/pulsing cloud shafts in clear air; tuned exposure/tint/falloff is an idealized optical approximation, not a calibrated camera.
- Clear blue uses a rich blue zenith and pale horizon, inspired by [#252](https://github.com/Attacktive/weatherd/issues/252); retain natural sunrise/sunset/night and cloudy/stormy precedence.
- Lens flare draws one restrained feathered prismatic arc inspired by [#290](https://github.com/Attacktive/weatherd/issues/290), with an open upper edge and low-sun fade, not stacked rainbow rings.
- Rainbow size offers 60–140%, default/reset 100%, under Lens flare in Sun & moon; hiding the slider when Lens flare is off retains its value. Scale only the cached prismatic arc's drawing destination around its existing sun/motion anchor; never resize the solar disc, optical ghosts, streak, corona, or sunbeams, alter weather attenuation, or regenerate artwork/coverage for a size change.
- Keep the band compact and spectrally colored, with subdued reflection circles/horizontal streak; use translucent blending to tint blue sky rather than additive whitish haze.
- Fade the arc/reflections with moving near-cloud solar occlusion even with Sunbeams Off; suppress for night, hidden sun, precipitation, fog, thunder, and heavy opaque cover; Lens flare Off removes all procedural rainbow treatment.
- Keep all four reflections above the lower quarter during supported tilt in portrait/landscape; compress spacing near the boundary without hiding the farthest circle or changing arc geometry.

### Sky Wildlife and Aircraft

Birds offers Off, Small flock (3–5), Medium (6–8), and Large (9–12); Bird interval offers Natural, 30 seconds, 1 minute, 2 minutes, 5 minutes, and 10 minutes.
Small + Natural retains the restrained randomized schedule; explicit intervals cause one crossing each interval without random skips.
Birds remain fair-daylight only, suppressed by rain, fog, opaque cover, or night; schedule on the shared scene animation clock without background alarms or another animation lifecycle.
Distant aircraft/contrails cross in front of every sun style; clouds can obscure the body and lights together.
The Distant aircraft toggle also enables fair-weather night flights: faint silhouette, red port/green starboard navigation lights that follow heading, white tail light, red beacon, brief white strobe, compact core halos, and no night contrail.
Subdue lights at dawn/dusk and omit them in daylight without changing flight paths, timing, weather restrictions, or daytime appearance.

### Rendering Performance and Motion Sensors

Corona/optical sprites cache disjoint alpha-coverage strips and clip transparent overdraw while retaining full-bitmap filtering/compositing.
Build coverage once per source and invalidate with its tile cache; retain the procedural arc's immutable sprite/coverage for the layer lifetime, never generating artwork per frame or changing frame caps/non-sun paths for this optimization.
Motion-responsive reflections defaults off, requires Lens flare, and is disabled without a compatible sensor; it can use extra battery/reduce performance.
Use gravity or an accelerometer fallback only while a visible scene can draw reflections; stop when hidden, screen-off, or weather/night suppresses reflections, and recenter on stop or rotation.
Sensor callbacks set normalized targets; each rendered preview/wallpaper frame advances visible offsets with the same time-based exponential filter, without increasing sensor rate or changing visibility gating.

Historical [#206](https://github.com/Attacktive/weatherd/issues/206) measurements below predate the revised prismatic arc in #290; do not present them as current telemetry.
Pixel emulator, API 36, 1600×2560: median frame times after eight warm-up frames and 60 measured frames:

| Scene / measurement | Before | After |
| --- | ---: | ---: |
| Software foreground: sun without lens flare | 24.82 ms | 15.26 ms |
| Software foreground: sun with lens flare | 38.24 ms | 22.12 ms |
| Software foreground: sun and 0.5 cloud cover | 45.57 ms | 30.03 ms |
| Hardware canvas: sun with lens flare, through image delivery/readback | 23.02 ms | 17.67 ms |

Software measurements exclude backdrop restoration; hardware includes backdrop drawing, submission, and readable-frame waiting.
These measure rendering, not launcher jank/input latency; full-resolution software comparisons kept clouds, Moon, overcast, mountains, and rain pixel-identical, with clear-day lens flare differing at nine pixels by at most one channel value.

Galaxy S23, 1080×2340, uncapped clear-day simulator:

| Build | Instrumented gravity sample cadence | Instrumented longest continuous wallpaper render segment | User-observed reflection motion |
| --- | ---: | ---: | --- |
| 3.8.1 Play baseline | 66.35 ms / 15.07 Hz | 118.75 jobs/s | Stepped |
| Patched side-by-side debug build | 66.35 ms / 15.07 Hz | 117.50 jobs/s | Smooth |

The unchanged instrumented cadences and before/after user observation are consistent with frame-driven interpolation removing stepping, not faster sensor sampling or a lower frame cap.
