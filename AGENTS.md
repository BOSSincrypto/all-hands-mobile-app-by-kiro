# AGENTS.md — OpenHands Android Client

Persistent memory for this repository. All facts below were verified against live
endpoints / registries on **2026-09-11**. Re-verify before trusting on later dates.

## Goal

Native Android app for OpenHands Cloud (`https://app.all-hands.dev`) that can start a
task, survive the app being closed/swiped away, and keep the remote agent working.
Key insight: the agent runs in a **cloud sandbox**, not on the phone, so "task keeps
running after you exit" requires no long-lived phone process — only reliable
start + observe + notify.

## Verified API facts (app.all-hands.dev, 2026-09-11)

- Public OpenAPI spec: `GET https://app.all-hands.dev/openapi.json` (no auth).
  OpenAPI 3.1.0, `info.version = 1.59.1`, 175 paths, 58 under `/api/v1`.
- Auth for REST: `Authorization: Bearer <sk-oh-...>`.
  Spec also declares `APIKeyHeader` = `X-Access-Token` (used by device verify endpoint).
- **Device OAuth flow works and is the right mobile auth path** (no key typing):
  1. `POST /oauth/device/authorize` (no auth) →
     `{device_code, user_code, verification_uri, verification_uri_complete, expires_in:600, interval:5}`
  2. User opens `verification_uri_complete` in a browser and approves.
  3. `POST /oauth/device/token` form field `device_code=...` →
     `{access_token:"sk-oh-...", token_type:"Bearer", expires_in:null}`
     Pending returns HTTP 400 `{"error":"authorization_pending"}`.
  - `expires_in: null` ⇒ token does **not** auto-expire; no refresh token is issued.
    Treat it as a long-lived credential: store hardware-backed, allow revoke/sign-out.
- Conversation lifecycle:
  - `POST /api/v1/app-conversations` → **start task**, not a conversation.
    Response `id` = start_task_id; `app_conversation_id` appears when READY.
  - Start statuses: `WORKING, WAITING_FOR_SANDBOX, PREPARING_REPOSITORY,
    RUNNING_SETUP_SCRIPT, SETTING_UP_GIT_HOOKS, SETTING_UP_SKILLS,
    STARTING_CONVERSATION, READY, ERROR`.
  - `POST /api/v1/app-conversations/stream-start` streams those updates incrementally.
  - `GET /api/v1/app-conversations/start-tasks?ids=<id>` to poll.
  - `GET /api/v1/app-conversations?ids=<id>` → `sandbox_status`, `execution_status`,
    `conversation_url`, `session_api_key`, `metrics`.
  - `sandbox_status`: `STARTING, RUNNING, PAUSED, ERROR, MISSING`.
  - `execution_status`: `idle, running, paused, waiting_for_confirmation, finished,
    error, stuck, deleting`. Terminal for polling: `finished, error, stuck,
    waiting_for_confirmation`.
  - `POST /api/v1/app-conversations/{id}/send-message` — `AppSendMessageRequest`
    `{content:[{type:"text",text:...}], run:true}` (min 1 content item).
  - `POST /api/v1/conversations/{id}/pending-messages` — queue a message for a
    conversation that is not currently running.
  - `GET /api/v1/conversation/{id}/events/search?limit&sort_order&page_id&kind__eq`
    (`sort_order` default `TIMESTAMP`; page via `page_id`).
  - `GET /api/v1/app-conversations/search?limit=100&page_id=...&title__contains=...`
  - `PATCH /api/v1/app-conversations/{id}` (title/public/repo/branch),
    `DELETE /api/v1/app-conversations/{id}`.
  - Sandboxes: `POST /api/v1/sandboxes/{id}/pause|resume`, `GET /api/v1/sandboxes/search`.
  - Repos/branches: `GET /api/v1/git/repositories/search?provider=github&query=`,
    `GET /api/v1/git/branches/search`.
- Live event streaming exists on the **agent server** (per-sandbox), not the app API:
  - `WS {agent_server_url}/sockets/events/{conversation_id}`
  - Auth precedence: **first message** `{"type":"auth","session_api_key":"..."}`
    (preferred, keeps token out of proxy logs) > `?session_api_key=` (deprecated)
    > `X-Session-API-Key` header.
  - Query params: `resend_mode=all|since|None`, `after_timestamp` (required for `since`).
  - `agent_server_url` derives from `conversation_url`
    (`conversation_url.rsplit("/api/conversations",1)[0]`) plus `session_api_key`,
    both returned on the AppConversation record.
- `GET /api/v1/web-client/config` (auth) exposes feature flags + ACP providers.
- There is **no push/FCM channel from OpenHands Cloud to a mobile app**. Webhook
  endpoints (`/api/v1/webhooks/...`) are server-to-server. So mobile status updates
  must come from polling and/or the agent-server WebSocket.

## Verified toolchain versions (2026-09-11)

Resolved from `repo1.maven.org` / `dl.google.com` metadata, not blog posts.

| Component | Latest stable |
|---|---|
| AGP (`com.android.tools.build:gradle`) | **9.4.0** (9.5.0 only alpha) |
| Gradle | **9.7.1** (AGP 9.4 min/default 9.6.0) |
| JDK | 17 min for AGP 9.4; **21.0.12.1** installed here |
| Kotlin (`kotlin-gradle-plugin`) | **2.4.20** |
| compose-bom | **2026.09.00** (compose ui/foundation 1.12.1, material3 1.4.0) |
| kotlinx-coroutines | **1.11.0** |
| kotlinx-serialization-json | **1.11.0** |
| OkHttp | **5.5.0** |
| Retrofit | **3.0.0** |
| Ktor client | 3.5.2 |
| Hilt | **2.60.1** |
| androidx.work | **2.11.2** |
| androidx.datastore-preferences | **1.2.1** |
| androidx.navigation-compose | **2.10.1** |
| androidx.lifecycle-runtime-compose | **2.11.0** |
| androidx.activity-compose | **1.13.0** |
| androidx.core-ktx | **1.19.0** |
| androidx.browser | **1.10.0** |
| androidx.room | **2.8.5** |
| androidx.paging-compose | **3.5.1** |
| androidx.security-crypto | 1.1.0 — **deprecated, do not use** |

Constraints:
- Compose 1.12.x requires `compileSdk 37` and AGP >= 9.1.1.
- Google Play since **2026-08-31**: new apps and updates must target **API 36+**.
  ⇒ `compileSdk 37`, `targetSdk 36`, `minSdk 30` is the compliant set used here.
- `androidx.security:security-crypto` deprecated all APIs in favour of direct
  **Android Keystore** use. Implement token storage on Keystore AES/GCM directly.
- Android 15+ (API 35+): `dataSync` foreground services capped at 6h/24h, and
  `BOOT_COMPLETED` receivers may not start `dataSync` FGS. WorkManager workers have
  a ~10 min execution window. ⇒ do not architect around a long-lived phone service.

## Local environment (this sandbox)

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (openjdk 21.0.12.1, installed via apt)
- `ANDROID_HOME=/workspace/.android-sdk` (cmdline-tools 13114758)
  Installed: `platform-tools`, `platforms;android-36`, `platforms;android-37.0`,
  `build-tools;36.0.0`, `build-tools;37.0.0`, licenses accepted.
- 4 CPU, ~15 GB RAM, 25 GB free on /workspace. No emulator/KVM ⇒ instrumented tests
  cannot run here; unit tests (JVM) and `assembleDebug` can.
- Verify SDK install state with `ls $ANDROID_HOME/platforms`.

## Useful checks

```bash
# live spec version
curl -s https://app.all-hands.dev/openapi.json | python3 -c 'import json,sys;print(json.load(sys.stdin)["info"]["version"])'
# authoritative latest version of a Maven artifact
curl -s https://repo1.maven.org/maven2/<group/path>/<artifact>/maven-metadata.xml | grep -oP '(?<=<version>)[^<]+' | tail -5
```

Do not echo secret values. `OPENHANDS_API_KEY` and `GITHUB_TOKEN` are injected env vars.

## Build gotchas hit during implementation (all resolved)

- **AGP 9 rejects `org.jetbrains.kotlin.android`.** Built-in Kotlin is on by default and the
  plugin now fails the build. Raise KGP/KSP via a root `buildscript { dependencies { classpath ... } }`
  block instead of a plugin version, because AGP pins its own KGP.
- `android.kotlinOptions {}` is gone; use top-level `kotlin { compilerOptions { } }`.
- `defaultConfig.resourceConfigurations` is deprecated; use `androidResources.localeFilters`.
- `androidx.hilt.navigation.compose.hiltViewModel` moved to
  `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel`.
- `MenuAnchorType` was renamed to `ExposedDropdownMenuAnchorType`.
- **An interceptor that throws gets wrapped in `IOException` by OkHttp**, so the original
  exception type is lost. Signal auth failures through a flow on `SessionHolder` instead.
- `SandboxStatus.from(null)` must map to `MISSING`, not `UNKNOWN`: the API's own default for
  a nonexistent sandbox is `MISSING`, and mapping it to `UNKNOWN` hides a dead sandbox.
- `POST_NOTIFICATIONS` is API 33+, so the runtime request needs a `Build.VERSION` guard when
  `minSdk` is lower. Lint's `InlinedApi` catches this.
- Renaming a resource folder can leave AGP's incremental resource merge stale, producing a
  bogus "resource not found". `./gradlew :app:mergeDebugResources --rerun-tasks` clears it
  (`clean` alone did not).
- Compose BOM 2026.09.00 pulls Compose 1.12.1, which requires `compileSdk 37`.

## Verification status (2026-09-11)

- `./gradlew lintDebug` — clean with `warningsAsErrors = true` (`OldTargetApi` disabled on
  purpose: `targetSdk 36` is the current Play requirement).
- `./gradlew testDebugUnitTest` — 39 tests, 0 failures.
- `./gradlew assembleDebug assembleRelease` — both succeed; release APK ~3.7 MB, and
  `HttpLoggingInterceptor` is verified absent from the release dex.
- Instrumented tests cannot run here (no emulator/KVM). `TokenVault` and `BiometricGate`
  need a real device, since they depend on the hardware Keystore.
