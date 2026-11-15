# Refresh on unlock

Settings > Weather > Refresh on unlock is opt-in and defaults to off, including for existing installations with no stored key. It is independent of the normal refresh interval, not an appearance preference. Applying or resetting an appearance preset cannot change it. Enabling the preference does not itself force a refresh.

## Events and lifecycle

One application-scoped receiver listens only for Android's system-only `ACTION_USER_PRESENT` broadcast. Wallpaper visibility, preview resume, `ACTION_SCREEN_ON`, and the credential-storage `ACTION_USER_UNLOCKED` broadcast are not unlock triggers. Multiple wallpaper engines do not register extra receivers.

The receiver exists only while the preference is enabled and the Weatherd process is alive. It does not restart a dead or force-stopped process and is not a promise to observe every unlock under OEM process restrictions. Android may defer broadcast delivery or background work. There is no polling, alarm, foreground service, new permission, or permanent notification.

Disabling the preference unregisters the receiver and cancels only `weather-refresh-on-unlock` work. Scheduling and cancellation are serialized; both enqueueing and execution recheck the persisted preference. Existing preview, wallpaper, manual-refresh, and widget paths are unchanged.

## Work, cooldown, and failures

The receiver only enqueues a dedicated one-time WorkManager request. Enqueueing has an eight-second timeout and always releases the pending broadcast, including cancellation before the coroutine starts. The actual network request runs outside the receiver.

Unique work uses `KEEP` and a connected-network constraint: duplicate offline or running requests coalesce instead of building a backlog. Offline work may run after the device is locked again when connectivity returns. Turning the preference off cancels that pending work.

The process-scoped controller allows one unlock refresh attempt every five minutes, measured with `SystemClock.elapsedRealtime()`, and never overlaps its own attempts. The cooldown starts at execution, not at the original offline unlock. Failed or canceled attempts consume the cooldown; rejected events do not extend it. Disabling and reenabling within the same process does not reset it. The cooldown is in memory and resets after process death; WorkManager still retains its unique pending/running request.

Eligible work calls the existing shared weather provider with `force = true`, independently of the configured automatic interval. It retains normal location selection/freshness, fallback providers, simulator suppression, last-success weather, and request-order guards. It does not restart scene animation or change widget state. Provider skips (such as simulation or unavailable location) complete without retrying; failures are logged and terminate this work rather than scheduling an automatic retry loop. A later eligible unlock may try again. Coroutine cancellation propagates normally.

This adds possible network/location activity, so the setting warns about battery and data use. No battery-cost estimate or OEM compatibility claim has been established.

## Validation

Added JVM tests cover disabled behavior, duplicate and concurrent unlock attempts, exact cooldown boundaries, non-sliding throttling, failure recovery, cancellation, preference persistence and stale callback replay, appearance-preset isolation, accepted/ignored broadcast actions, bounded enqueue failure, and forced shared-provider delegation.

Before opening a PR, run the full checks and ordinary local Android instrumentation on one disposable target, following [the development procedures](development.md#local-checks-and-instrumentation):

```sh
./gradlew test :app:lint :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest
```

Replace the serial with the intended disposable emulator. Connected teardown removes the app and its data; never target the reserved provider-evaluation AVD.

Device acceptance should confirm the toggle survives recreation, enabling alone does not force weather, repeated real keyguard dismissals within five minutes do not repeat unlock requests, wallpaper visibility and screen-on without dismissal do not count as unlocks, network restoration coalesces queued requests, disabling cancels waiting work, and provider failures preserve the last successful scene. Confirm the existing refresh controls and simulator still behave as before. Unit tests alone do not validate actual OEM broadcast delivery or battery impact.
