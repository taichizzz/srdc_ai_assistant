# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Renamed the user-facing application name from 「所見即可說」 to **SeeAndSay** in
  `app_name`, `README.md`, and `docs/demos/M1.1.md`. The internal package
  (`com.foxconn.seeandsay`) is unchanged. The Phase 1 verification note below is
  left as the historical record of what the UI displayed at that time.

## [2026-07-15] — M1.1 Phase 2: Permission and STT debug UI

### Added

- Added `RECORD_AUDIO` and `INTERNET` to `app/src/main/AndroidManifest.xml`; no
  unrelated permission or service declaration was added.
- Added provider-neutral `speech/SttResult.kt` and `speech/SttClient.kt`
  declarations. They contain no provider, gRPC, protobuf, audio, or network
  implementation.
- Added `ui/SttUiState.kt` with the required `SttStatus` states, transcript/error
  fields, and explicit microphone-permission outcomes.
- Added `ui/SttViewModel.kt` as the StateFlow-backed single source of truth for
  permission, Start/Stop, error recovery, and transcript reduction.
- Added `ui/SttDebugScreen.kt` with visible permission/session status,
  Start/Stop, partial/final transcripts, error/Retry, permanent-denial Settings
  recovery, and permission-independent typed input.
- Added six pure ViewModel unit tests covering typed final input, partial
  clearing, Retry, Start/Stop without fabricated text, and both denial branches.
- Added the reproducible Phase 2 permission and typed-input procedures to
  `docs/demos/M1.1.md`.

### Changed

- Replaced the Phase 1 placeholder in `ui/MainActivity.kt` with lifecycle-aware
  StateFlow collection and an Activity Result API microphone-permission flow.
- Updated `speech/README.md` and the root `README.md` to describe the completed
  Phase 2 boundary and remaining audio/cloud work.
- Replaced the Phase 1 status resource with Phase 2 debug-screen labels while
  retaining **SeeAndSay** as the only user-facing application name.
- Removed AndroidX's transitively merged dynamic-receiver permission because
  Phase 2 registers no dynamic receiver and the APK must contain only its scoped
  Internet and microphone permissions.
- Added lifecycle Compose/ViewModel and coroutine dependencies required by the
  state-only UI; no speech provider dependency was added.

### Notes

- Extended the required `SttUiState` fields with `MicrophonePermissionStatus` so
  retryable and permanent denial remain distinct after recomposition.
- Used `ActivityResultContracts.RequestPermission` because permission requests
  must be lifecycle-aware and begin only when Start is pressed.
- Classified a denied callback with no permission rationale as permanent denial,
  matching Android's route to application Settings when another dialog is
  suppressed.
- Reconciled a grant in `onResume` without auto-starting because returning from
  Settings must restore a usable state, not begin microphone capture implicitly.
- Reconciled revocation of a prior grant back to `NotRequested`/`Idle` so stale
  Granted state cannot bypass the next runtime permission request.
- Routed typed text through `onSttResult(SttResult(isFinal = true))` so future GCP
  final results and emulator input share one append/partial-clear path.
- Kept the typed-field draft as `rememberSaveable` UI state; committed transcript
  state remains exclusively owned by `SttViewModel`.
- Appended committed transcript segments with a single newline to keep the debug
  log readable and deterministic.
- Start enters `Listening` only as a Phase 2 state transition after permission;
  it allocates no microphone/network resource and never creates transcript text.
- Stop moves an active state directly to `Completed`; asynchronous `Stopping` is
  reserved for the later audio/cloud shutdown phase.
- Used manifest-merger removal markers for AndroidX's synthetic receiver grant;
  this preserves the AndroidX dependency while avoiding an unused APK permission.
- Verified `./gradlew testDebugUnitTest assembleDebug`: build succeeded and all
  six ViewModel tests passed.
- Verified `./gradlew lintDebug`: Android lint succeeded with no blocking issue.
- Verified install and cold launch on an API 29 phone AVD. Typed input committed
  with zero microphone permission; grant, retryable denial, permanent denial,
  Settings recovery, Start, and Stop were exercised without a crash.
- AAOS-specific layout/permission behavior was not verified because no AAOS AVD
  is configured locally; Phase 2 uses standard Android permission and Compose APIs.

## [2026-07-15] — M1.1 Phase 1: Android project foundation

### Added

- Added the Gradle root and single Android `app` module.
- Added the Gradle 8.9 wrapper so builds do not depend on a system Gradle
  installation.
- Added a launchable Compose `MainActivity` under
  `com.foxconn.seeandsay.ui` with a Phase 1 checkpoint screen.
- Added the `com.foxconn.seeandsay.speech` package boundary and documentation;
  executable speech code remains intentionally deferred.
- Added the Android manifest and AAOS-compatible optional hardware declarations.
- Added `docs/demos/M1.1.md` with the foundation checkpoint and final reproducible
  M1.1 acceptance script.
- Added root build and setup instructions to `README.md`.

### Changed

- Expanded `.gitignore` for Android/Gradle outputs, `local.properties`, and all
  JSON files so service-account material cannot be committed accidentally.

### Fixed

- Pinned `androidx.activity:activity-compose` to 1.9.3 because 1.10.0 requires
  compile SDK 35, while the documented and locally installed baseline is SDK 34.

### Notes

- Chose one `app` module because the binding architecture requires a single APK.
- Chose package `com.foxconn.seeandsay` to match the architecture module map.
- Chose minimum SDK 29 and compile/target SDK 34 to match the documented AAOS
  baseline and the locally installed stable platform.
- Chose Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and the
  Kotlin Compose plugin 2.0.21 as a mutually compatible, reproducible toolchain.
- Chose Compose BOM 2024.12.01 for centralized Compose dependency compatibility.
- Kept the automotive and touchscreen features optional so the same Week 1 APK
  can be tested on AAOS, a phone, or a tablet as the project risk plan requires.
- Did not create `bridge`, `decision`, TTS, LM, Accessibility, or pipeline code;
  those are outside M1.1 Phase 1 and prohibited by the Week 1 guardrails.
- Verified `./gradlew testDebugUnitTest assembleDebug`; the build succeeded and
  produced `app-debug.apk` (the unit-test task is `NO-SOURCE` until Phase 8).
- Verified `./gradlew lintDebug`; Android lint completed successfully.
- Verified a cold install and launch on an API 29 phone AVD because no AAOS AVD
  was configured locally; `MainActivity` reached `RESUMED` and its UI hierarchy
  contained both **所見即可說** and **M1.1 Android foundation is ready**.
