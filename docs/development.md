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

### Test Workflow Profiling

The required `Test` job keeps JVM tests, Android lint, Detekt, and both debug APK builds in the background while the SHA-pinned API 35 emulator action provisions and boots.
Connected instrumentation waits for that gate and reuses its outputs on the same runner; the existing opt-in provider-capture exclusion is unchanged.
Concurrency cancellation applies only to older runs of the same pull request, using the workflow, event, and PR number; push/main runs use their unique run ID and never request cancellation.
Canceled runs publish partial diagnostics without waiting for an unfinished background static gate; non-canceled runs still wait for and propagate that gate's result.

`scripts/ci/ci_metrics.py` records independent monotonic endpoints for dependency/cache setup, the static/build gate, emulator provisioning/boot, post-boot static wait, and the connected Gradle invocation.
These overlapping intervals must not be added together.
Both Gradle invocations use plain console output, `--info`, and `--profile`; the always-run summary distinguishes `FROM-CACHE`, same-runner `UP-TO-DATE`, actual executions, and missing evidence.
Task-cache hit rate is `FROM-CACHE / (FROM-CACHE + executed tasks with an observed build-cache key)`; a missing denominator is unknown, not a zero-percent hit rate.

The workflow installs the hash-pinned `defusedxml` wheel from `scripts/ci/requirements.txt` in an isolated Python environment before exercising the reporting tools; XML with DTDs/entities is reported as unusable rather than expanded.
For local reporting-tool checks, install that requirements file in an isolated Python environment and run `python -m unittest discover -s scripts/ci -p 'test_*.py'`.
Outside Actions, generated diagnostics default to the repository's ignored `build/weatherd-ci/`, not a predictable directory in shared temporary storage; `RUNNER_TEMP` and the reporting CLI's explicit `--directory` remain available.

Each `test-diagnostics-<attempt>` artifact has a 14-day retention setting on success or failure and contains Gradle logs/profiles, JVM/Android reports and result XML, individual Android test timings, and the phase summary.
Attempt-specific names identify the evidence but do not guarantee earlier archives remain available after a workflow rerun; download each archive before requesting another attempt.
During the connected invocation, a separate monitor captures only instrumentation-runner logcat tags and samples numeric runner load/memory and device uptime/load/process presence every 15 seconds.
Its five-second ADB probe limit bounds diagnostics only; it never limits or cancels tests.
Compare test XML durations and runner start/finish timestamps with the connected wall time and device samples to separate slow test bodies, pre/post-test runner overhead, ADB stalls, and host/device contention.
No general device logcat, bugreport, user settings, or provider/location payload is collected.
Missing or partial XML and unreached phase endpoints remain explicit in the summary, including emulator failure before the callback.

For a reproducible comparison, rerun the existing workflow at unchanged source and record each run ID **and attempt**, runner image, event/ref, dependency-cache state, task-cache outcomes, phase durations, and workflow/job timestamps; archive each attempt's diagnostics before rerunning it.
Use several attempts per retained candidate, separate cold from warm cache runs, retain outliers, and report medians plus total runner job-seconds rather than assuming wall-time savings equal billed savings.
Actions job/step timestamps include setup, post-action cache restore/save, and artifact overhead that the Gradle timers do not; billing data, when unavailable, must remain unknown.
Change one cache variable per candidate, avoid duplicate ownership of Gradle User Home, and keep a cache change only when the measured end-to-end result or stability improves.
Do not introduce configuration cache, snapshots, sharding, or a test timeout without evidence.

#### Baseline and Outlier Evidence (#349)

The unchanged-source baseline uses [run 40174702312](https://github.com/Attacktive/weatherd/actions/runs/40174702312), attempts 1–3, at `30e72946a6a2919d27238004a09bba4d4eca9d2b`, on `ubuntu-24.04` image `20261012.334.1`, Temurin `21.0.12+1`, with Gradle `9.8.2`, AGP `9.4.1`, Kotlin `2.4.21`, and KSP `2.3.12`.
All attempts passed the existing push/main gate.
Workflow wall time is `run_started_at` through the Test job's completion; runner consumption is the Test job's `started_at` through `completed_at`, not the deprecated billing API's zero-valued public-repository durations.

| Baseline attempt | Workflow seconds | Runner job-seconds | Static Gradle seconds | Connected Gradle seconds | JDK/cache setup seconds | Post-JDK cache step seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 470 | 468 | 373 | 78 | 2 | 3 |
| 2 | 496 | 492 | 333 | 140 | 7 | 0 |
| 3 | 483 | 480 | 347 | 110 | 8 | 0 |

Attempt 1 reported `gradle cache is not found` and a competing cache-save reservation; attempts 2–3 restored the dependency cache, and all three restored the independent wrapper cache.
Task build caching was disabled and all three logs contain zero `FROM-CACHE` tasks, so there is no baseline task-cache hit-rate denominator; dependency-cache hits are not task-cache hits.

The retained [33-minute outlier](https://github.com/Attacktive/weatherd/actions/runs/40052071132) logs say `Emulator booted.` at `2026-10-24T01:59:08.6357415Z`, `Static gate passed.` at `02:05:11.1973387Z`, and `Running tests on devices: test(AVD) - 15` at `02:05:16.3883089Z`.
The next output appears at `02:29:51.8895454Z`, a 1,475.50-second silent interval after instrumentation started; `BUILD SUCCESSFUL in 24m 40s` follows.
This localizes the delay after boot and the static gate, but cannot distinguish a slow test body, ADB/device stall, or test-runner overhead retrospectively.
Do not attribute that historical delay to emulator boot or KVM without additional evidence.

#### Measured Candidate (#349)

The matched candidate uses [run 40182492869](https://github.com/Attacktive/weatherd/actions/runs/40182492869), attempts 1–3, at `d8b29f415ca8f6905863a071f51adc4d53686e96`, with unchanged application and Android/JVM test source, required tasks, API 35 emulator configuration, runner image, and toolchain.
All three Test workflow attempts passed; dependency and wrapper caches restored in every attempt.
The table uses Actions timestamps and the two `BUILD SUCCESSFUL in ...` Gradle durations, including setup, reporting, artifact, and post-action overhead in workflow/job time.

| Candidate attempt | Workflow seconds | Runner job-seconds | Static Gradle seconds | Connected Gradle seconds | JDK/cache setup seconds | Post-JDK cache step seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 331 | 328 | 171 | 130 | 7 | 0 |
| 2 | 340 | 337 | 179 | 126 | 9 | 0 |
| 3 | 508 | 504 | 268 | 200 | 12 | 0 |

| Measurement | Baseline | Candidate |
| --- | ---: | ---: |
| Median workflow seconds | 483 | 340 |
| Workflow range, seconds | 470–496 | 331–508 |
| Total Test runner job-seconds, three attempts | 1,440 | 1,169 |
| Dependency-cache hits | 2/3 | 3/3 |
| Task-cache hits / cache-keyed executions | Off; denominator unknown | 32/59 (54.24%) each |

In the preselected three-attempt comparison, median workflow time fell 29.6%, and aggregate Test runner job-seconds fell 18.8%, with the slower third attempt included rather than discarded; the later verification outlier below qualifies any broader cost or stability claim.
These are three-attempt measurements, not a prediction or a cold-start guarantee: the baseline includes one dependency-cache miss, the candidate is warm, push/main and PR events differ, and hosted-runner timing varies.
The two warm baseline attempts have a 489.5-second workflow median, so the improvement is not dependent on including the cold baseline row.
Earlier exploratory candidates are separate evidence, not matched samples: [40181095981](https://github.com/Attacktive/weatherd/actions/runs/40181095981) took 674 workflow seconds / 409 job-seconds including 265 seconds queued behind a canceled run; [40181918531](https://github.com/Attacktive/weatherd/actions/runs/40181918531) took 391 / 389 seconds before the reporting-only refactor.
The canceled run's reporter waited 257 seconds for the background build; canceled finalization now publishes partial evidence without that wait, with a regression covering the fast path.

The phase markers expose the overlap rather than counting boot plus build serially:

| Candidate attempt | Static/build seconds | Emulator provisioning/boot seconds | Post-boot static wait seconds | Connected invocation seconds |
| --- | ---: | ---: | ---: | ---: |
| 1 | 172.53 | 168.52 | 4.09 | 130.58 |
| 2 | 182.15 | 166.51 | 16.14 | 127.01 |
| 3 | 271.33 | 197.56 | 74.59 | 201.17 |

All three summaries report 183 Android passes, zero failures, and zero skips; the retained third-attempt XML also contains 553 JVM passes with zero failures/skips.
The connected phase reuses 84 `UP-TO-DATE` tasks and executes the connected-test task; `preDebugAndroidTestBuild SKIPPED` already appears in both baseline and candidate and is not a newly skipped test.
The slower third attempt retains 183 test starts/finishes, individual durations, and 14 device samples: no sampled ADB error, a maximum 0.273-second probe, and one-minute host load spanning 1.84–9.45 on four CPUs.
Its longest test is `SettingsNavigationTest.homeControlsAvoidSystemBarsAndDisplayCutouts` at 55.536 seconds; both static and connected phases also slow down, so do not attribute the entire wall-time increase to that test or claim a root cause from load correlation alone.
This demonstrates the new diagnostic path without sharding, weakening assertions, or imposing a test timeout.

The rerun artifact API exposed only attempt 3 after the benchmark, and an earlier uploaded artifact ID returned 404.
The first two attempts' phase/cache/result-count evidence above comes from retained attempt logs; only the third attempt's complete XML and per-test archive remained downloadable at collection time.
Future benchmark collection must download each archive between attempts, rather than relying on attempt-specific names alone.

#### Later Verification Outlier and Limits

The subsequent [final-head verification run 40184828037](https://github.com/Attacktive/weatherd/actions/runs/40184828037) passed at `2e2c1dd4733c34794dc99564e2307d79cdd7e3a2`, but took 888 workflow seconds and 885 Test runner seconds.
Its static Gradle invocation took 241 seconds; the connected invocation took 619 seconds.
The retained XML and runner start/finish timestamps localize 482.232 seconds, 77.8% of the connected interval, to three passing navigation test cases:

| SettingsNavigationTest case | Seconds |
| --- | ---: |
| toolbarBackReturnsToHubBeforeLeavingSettings | 237.784 |
| homeControlsAvoidSystemBarsAndDisplayCutouts | 162.243 |
| categoryScrollSurvivesReopeningAndRecreation | 82.205 |

All 183 Android cases still pass with no skips; 42 device probes report no ADB error, with a maximum 0.264-second probe.
This identifies the long tail within specific JUnit case intervals rather than emulator boot or unexplained Gradle setup; it does not identify the precise framework/fixture call that stalled, nor prove the historical 24-minute run had the same cause.
The navigation tests' selectors, synchronization, deadlines, and assertions remain unchanged; further tuning needs finer-grained evidence, not a shorter timeout or weaker coverage.

| Sample | Median workflow seconds | Mean Test runner job-seconds |
| --- | ---: | ---: |
| Baseline, three attempts | 483 | 480 |
| Matched candidate plus later verification, four observations | 424 | 513.5 |

Including this later observation leaves the median lower but raises mean Test runner use 7.0% above the small baseline sample.
Retain task caching for the measured static-work reduction—the static Gradle median is 210 seconds across these four observations versus 347 baseline seconds—but do not claim that overall runner cost or instrumentation stability is now reliably lower.
The normal-path speedup and the unresolved navigation tail must both remain visible in delivery reports.

#### Cache Ownership Decision

`actions/setup-java` remains the sole Gradle User Home cache owner.
Its [primary-key save guard](https://github.com/actions/setup-java/blob/v6/src/cache.ts) skips saving after an exact dependency-cache hit; task-cache reuse must therefore be measured rather than assumed to refresh on every source-only PR.
The evaluated [setup-gradle 6.4.0 basic provider](https://github.com/gradle/actions/blob/v6.4.0/docs/setup-gradle.md#basic-caching) uses the same dependency-hash strategy and cached directories, so replacing the owner with that provider was not treated as an independent performance improvement.
The enhanced provider changes both cache behavior and [licensing/terms](https://github.com/gradle/actions/blob/v6.4.0/DISTRIBUTION.md); it was not adopted implicitly as part of this optimization.
Never enable both setup-java Gradle caching and setup-gradle Gradle User Home caching together.

CodeQL's manual traced build explicitly uses `./gradlew --no-build-cache assembleDebug`, while retaining dependency caching.
The initial global-cache candidate restored `compileDebugKotlin` and `compileDebugJavaWithJavac` from cache, leaving CodeQL with `CodeQL could not process any code written in Java/Kotlin`.
The analysis build must execute compilation for extraction; do not remove this override or disable analysis to make a cached build appear green.

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

Independent, offline satellite cloud-mask validation is described in [the satellite evaluation guide](provider-satellite-evaluation.md). It is diagnostic and does not change METAR scoring, provider defaults, or the collector.

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

### Cloud Formation Placement

Near and distant cumulus, the partly-cloudy support bank, and the overcast bank stack share a vertical lift of 12% of the viewport height in portrait and 4% in landscape.
This raises formations toward the upper sky while preserving cloud artwork, body size, population, opacity, tint, drift, and the relative spacing between decks; the smaller landscape lift limits top-edge clipping.
Keep cloud drawing, cast-shadow sampling, sun-edge sampling, and lunar transmission/halo sampling on the same translated geometry.
Cirrus, fog veils, the sky gradient and overcast ceiling, celestial positions, and scenery remain in place; do not translate the entire canvas or change weather classification to reposition clouds.
The shared renderer applies this composition to preview, live wallpaper, simulator, and fallback without a new setting or preset field.
Mirror clear-sky placement changes into `scripts/preview-clear-sky.py`; Android rendering remains authoritative.

### Scenery and Tree Artwork

Trees are an independent fixed foreground composition over any backdrop, including None and Photo, not a backdrop or seasonal landscape.
Evergreen uses the multi-tree conifer composition; Broadleaf uses an inset left hero and a smaller independently grown right companion, leaving the upper-middle sky open.
Keep the hero crown fully visible in portrait, landscape, and launcher scrolling without reducing scale; both trunks remain grounded.
Mountain artwork scales uniformly to the viewport width, preserving its full horizontal composition without extra portrait enlargement or cropping.
Lift the width-scaled artwork by 9% of viewport height without enlarging, stretching, or cropping its horizontal composition; its transparent base fades into the underlying sky below. Clamp the artwork's top to zero in short viewports so landscape peaks remain visible.
Mountains and both tree shapes share weather/day-phase lighting: preserve clear-day artwork colors, warm dawn/dusk continuously, desaturate overcast/precipitation, keep snow lighter than rain, and soften distant mountains in fog more than nearby foliage.
Mountains alone use luminance-aware alpenglow at low sun: peach/pink highlights over cool violet shadows, including the mist baked into the artwork; trees retain their existing warm material tint.
Compose that RGB grade before weather/night/fog lighting in the cached color matrix, attenuate it with the existing low-sun/weather strength, and preserve clear-day/night colors, source alpha, and placement without regenerating pixels per frame.
At night, reduce mountain-blue saturation while retaining ambient foliage detail; night brightness dims both, easing through dawn/dusk without changing alpha or placement.
Do not per-pixel grade custom photos; retain their weather overlays/night dimming and grade any trees over them normally.
Fog and precipitation draw in front.
Regenerate first-party sources with `uv run scripts/generate-tree-textures.py` and `uv run scripts/generate-broadleaf-textures.py`; Broadleaf authors separate trunk/branch/canopy structures and supports `--variant hero` or `--variant companion`.
Decode only the selected style on first use off the frame loop; never copy reference-app or third-party artwork.

### Sun, Moon, and Optical Effects

- Classic is procedural; Radiant uses the [unchanged submitted PNG](https://github.com/user-attachments/assets/68a92bcf-016d-4276-bc35-4c1e38ae6b78) credited in the [README](../README.md#artwork-credits); Starburst is first-party artwork inspired by [#291](https://github.com/Attacktive/weatherd/issues/291), not sampled from its iOS reference.
- All sun styles honor visibility, size, color, weather veiling, and sunset fade; natural daylight uses a white source, while low sun and explicit colors retain their tint.
- Orange uses a pale peach-orange source inspired by [#308](https://github.com/Attacktive/weatherd/issues/308), shared by Classic, Radiant, and Starburst, with a near-white disc core refined for [#365](https://github.com/Attacktive/weatherd/issues/365). Classic lifts only its innermost color stops; image styles prepare a bounded RGB-only core patch off the frame loop and retain the original filtered artwork outside it. Preserve source alpha, disc geometry, warm shoulders, halo, bloom, rays, lens effects, and sky; Natural, White, and Golden retain their existing treatment. Select Orange under Appearance → Sun & moon → Sun color; the reference's rainbow remains the independent Lens flare effect from #290.
- Prepare Radiant's immutable softened texture once on first use off the frame loop, preserving its bright core/short rays but fading outer haze and distant baked reflections before the boundary; keep extra wide halos restrained.
- Generate Starburst with `uv run scripts/generate-starburst-sun.py`: broad overlapping luminous lobes and faint uneven streaks, not a pointed star; fade before the texture boundary and decode only when selected, off the frame loop.
- Never bake rainbow reflections into Starburst; Lens flare controls the separate arc/reflections and Motion-responsive reflections controls motion independently.
- Generate the north-up near-side Moon with `uv run scripts/generate-moon-texture.py` from unchanged `scripts/assets/moon/lroc_color_2k.jpg`, checking its SHA-256 before projection; retain NASA attribution from the [README](../README.md#artwork-credits).
- The image in [#283](https://github.com/Attacktive/weatherd/issues/283) is reference only; waxing/waning changes illumination without mirroring markings, and new Moon draws neither disc nor halo.
- Soften the rendered Moon texture colors by 8% toward white during off-thread phase preparation, preserving the original NASA source file, moon opacity, surface markings, cloud blocking, and existing halo.
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
