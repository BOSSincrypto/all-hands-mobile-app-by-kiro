# OpenHands Android

Native Android client for [OpenHands Cloud](https://app.all-hands.dev). Start a task, close
the app, and the agent keeps working.

## Why closing the app is safe

The agent runs in an OpenHands **cloud sandbox**, not on the phone. The app only needs to
start the task, then observe it. That single fact drives the whole design:

- No long-lived background service, so nothing to be killed and nothing to drain the battery.
- A short-lived foreground service covers only the *launch* window, because sandbox
  provisioning plus repository setup can take minutes and a plain coroutine dies when the
  process is killed on swipe.
- Live updates arrive over a WebSocket while a conversation screen is open, and stop as soon
  as the sandbox is no longer running.

This also sidesteps platform limits that break naive designs: since Android 15 a `dataSync`
foreground service is capped at 6 hours per 24, `BOOT_COMPLETED` may not start one at all,
and WorkManager workers get roughly 10 minutes.

## Security

| Concern | How it is handled |
| --- | --- |
| Credential at rest | AES-256/GCM under a hardware-backed **Android Keystore** key. `androidx.security-crypto` is deprecated, so the Keystore is used directly. |
| Credential in use | The key requires user authentication with a **zero-second timeout**, so every decrypt needs its own biometric or device-credential unlock. Nothing is cached across launches. |
| Biometric changes | The key is created with `setInvalidatedByBiometricEnrollment(true)`. Enrolling a new fingerprint destroys the key; the app detects this and asks you to sign in again. |
| No key typing | Sign-in uses OpenHands' **device OAuth flow**: the app shows a short code, you approve it in a real browser session, and the app exchanges it for a token. |
| Transport | Cleartext refused, `RESTRICTED_TLS` only, and **user-added CAs are not trusted**, so a locally installed certificate cannot intercept the token. |
| WebSocket auth | The session key is sent as the first frame, not a query parameter, keeping it out of reverse-proxy access logs. |
| Screen contents | `FLAG_SECURE` blocks screenshots and keeps agent output out of the recents thumbnail. |
| Backups | Cloud backup and device transfer are fully excluded. |
| Logging | HTTP logging exists only in the debug variant; the release APK contains no logging class at all. |
| Sign-out | Deletes the Keystore key, the encrypted token, and the local cache. |

The token that OpenHands issues does not expire and has no refresh token, so it is treated
as a long-lived credential: hardware-bound, biometric-gated, and revocable from Settings.

## Features

- Conversation list with live status, cost, and repository
- Start a task with repository, branch, and model pickers
- Chat with the agent, including tool calls and results
- Approve or reject actions when the agent asks for confirmation
- Pause the agent, resume a paused sandbox
- Browse and read sandbox files
- Run shell commands in the sandbox
- Manage OpenHands secrets
- Offline cache, so the list and history render without network
- English and Russian

Agent Canvas and full account settings open in the browser from Settings. They are web
surfaces with no mobile API, so wrapping them natively would add bulk without adding value.

## Building

Requirements: JDK 17+ (21 recommended), Android SDK with platform 37.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # lint (warnings are errors)
./gradlew assembleRelease        # release APK, ~3.7 MB
```

Outputs land in `app/build/outputs/apk/`.

CI runs lint, unit tests, and both APK builds on every push, and uploads the APKs as
workflow artifacts. Download them from the **Actions** tab.

### Release signing

Without signing config, `assembleRelease` falls back to the debug identity, which is fine
for personal sideloading. To sign properly, create `keystore.properties` (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

In CI the same values are read from the `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` secrets.

## Stack

Kotlin 2.4.20, AGP 9.4.0, Gradle 9.7.1, Compose BOM 2026.09.00, Retrofit 3 + OkHttp 5,
Hilt, Room, kotlinx.serialization. `compileSdk 37` (required by Compose 1.12),
`targetSdk 36` (required by Google Play since 2026-08-31), `minSdk 30`.

Versions were resolved from Maven Central and Google Maven metadata on 2026-09-11.

## API

Built against the OpenHands Cloud V1 API, spec version 1.59.1 as published at
`https://app.all-hands.dev/openapi.json`. See `AGENTS.md` for the verified endpoint notes.
