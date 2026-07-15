# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Renamed the user-facing application name to **「IVI AI 助理」** (`app_name`, `README.md`,
  `docs/demos/M1.1.md`). Naming history: original **所見即可說** → interim **SeeAndSay** →
  current **「IVI AI 助理」**. The internal codename is unchanged: package
  `com.foxconn.seeandsay` and `Theme.SeeAndSay` stay as-is.
- Removed the duplicate root `project.md`; the single project overview now lives only at
  `docs/PROJECT.md`.
- Updated `README.md` Build & Run to real Gradle steps now that `app/` is scaffolded.

## [2026-07-15] — M1.1 Phase 3: Microphone capture and debug PCM loopback

### Added

- Added `speech/AudioConfig.kt` as the single 16 kHz mono PCM16 format source,
  including input/output channel masks, 100 ms chunk math, duration/frame
  conversions, and device-safe recorder-buffer selection.
- Added `speech/MicRecorder.kt` with a provider-neutral `AudioCaptureSource` and
  a cold `Flow<ByteArray>` AudioRecord implementation. It validates permission,
  device minimum buffer, initialization, start state, dead-object/invalid/read
  failures, and always stops/releases its recorder on completion or cancellation.
- Added `speech/BoundedPcmBuffer.kt` to concatenate debug PCM in order while
  enforcing an exact ten-second/320,000-byte memory cap.
- Added `speech/DebugAudioPlayer.kt` with an internal AudioTrack marker callback
  wrapped in a cancellable suspend function; it plays raw PCM and does not import
  or use Android TextToSpeech.
- Added a `BuildConfig.DEBUG`-only **Record & Play Back** verification section to
  `ui/SttDebugScreen.kt`, including captured byte count, automatic-cap status,
  playback status, and a manual **Stop & Play Back** action.
- Added pure unit tests in `speech/AudioConfigTest.kt` and
  `speech/BoundedPcmBufferTest.kt` for chunk/duration/frame/buffer arithmetic,
  ordered concatenation, partial final chunks, and hard-cap enforcement.
- Added `MainDispatcherRule.kt` and a ViewModel loopback test proving capture
  cleanup completes before playback starts.
- Added the reproducible Phase 3 capture, logging, cancellation, and audible
  loopback procedures to `docs/demos/M1.1.md`.

### Changed

- Changed `ui/SttViewModel.kt` to inject the same cold `AudioCaptureSource` into
  both production Start/Stop and debug loopback flows, with all sessions owned by
  `viewModelScope` and ordered through cancel-and-join replacement.
- Changed production Start from the Phase 2 placeholder to real microphone Flow
  collection. Phase 3 deliberately consumes PCM without creating transcript text;
  cloud streaming remains Phase 5.
- Changed Stop to expose `Stopping` while the active collector is cancelled and
  joined, then expose `Completed` only after recorder cleanup.
- Extended `ui/SttUiState.kt` with debug recording, playback, retained-byte, and
  cap-reached fields without changing the required `SttStatus` enum contract.
- Changed `ui/MainActivity.kt` to create `MicRecorder` and `DebugAudioPlayer`
  through `SttViewModel.Factory` and reuse the existing first-use permission flow
  for both production and debug recording.
- Changed `ui/SttDebugScreen.kt` to disable production microphone controls during
  raw playback and to disable typed submission only while audio is actively being
  acquired, stopped, or played. Permission-independent typed input remains intact.
- Added the coroutine-test dependency in `app/build.gradle.kts` and adapted all six
  approved Phase 2 ViewModel tests to the lifecycle-owned asynchronous session.
- Added Phase 3 labels to `app/src/main/res/values/strings.xml`; the user-facing
  application name remains **SeeAndSay**.

### Notes

- Kept the binding 16,000 Hz, mono, PCM16 format because Phase 5 will send these
  exact bytes to Google Cloud STT without transcoding.
- Chose 3,200-byte chunks because 16,000 samples/s × one channel × two bytes ×
  100 ms balances streaming latency with AudioRecord call overhead.
- Chose an AudioRecord internal buffer of at least two chunks, or the larger
  device minimum, to add scheduler headroom without changing emitted chunk size.
- Chose `MediaRecorder.AudioSource.VOICE_RECOGNITION` because the captured signal
  is intended for recognition and should avoid communication-call routing.
- Used `Dispatchers.IO` plus blocking reads so no raw thread or Android callback
  escapes `speech/`; cancellation is checked between approximately 100 ms reads.
- Copied every successful read before Flow emission because the reusable
  AudioRecord target is mutable and downstream collection may suspend.
- Treated zero, negative, dead-object, invalid-operation, and bad-value reads as
  visible failures so unsupported or lost microphones cannot leave the UI hanging.
- Logged only sequential chunk number and byte size; raw PCM is never logged.
- Chose a ten-second debug cap (320,000 bytes) to bound memory while covering the
  requested manual quality check. A final oversized chunk contributes only its
  remaining prefix and the cap event is logged.
- Used the production `MicRecorder.capture()` Flow for debug recording instead of
  a second recorder path so loopback verifies the exact Phase 5 audio intake.
- Used cancel-and-join before AudioTrack playback because the architecture echo
  rule forbids recording while any audio is playing.
- Chose static AudioTrack playback with a final-frame marker callback because the
  complete debug buffer is bounded and completion should be event-driven rather
  than based on a fixed sleep.
- Added a PCM-duration-derived playback timeout plus a two-second device margin so
  a missing AudioTrack marker becomes recoverable instead of hanging the UI.
- Kept loopback state separate from transcript fields so verification audio never
  fabricates or mutates recognized text.
- Gated the loopback affordance with `BuildConfig.DEBUG`; release builds retain no
  user-accessible verification control.
- Preserved `README.md` unchanged from the teammate-authored version.
- Verified `./gradlew testDebugUnitTest`: all 12 unit tests passed, including
  recorder/playback ordering and all previously approved Phase 2 reducers.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug`: the combined
  Phase 3 checkpoint succeeded and Android lint reported no blocking issue.
- Verified debug APK installation and a cold `MainActivity` launch on the API 29
  `SeeAndSayApi29` phone AVD; the UI hierarchy contained **SeeAndSay**, **Start**,
  and **Record & Play Back (DEBUG)**.
- The available phone AVD rejected AudioRecord construction as unsupported. The
  UI left `Listening` and displayed the recoverable microphone error without a
  crash or hang, confirming that failure branch but not continuous capture.
- Continuous 10–15 second chunk capture, audible PCM quality, pitch/speed, audio
  routing, and record-then-play behavior remain unverified until run on a physical
  microphone/speaker device. The mic-less AAOS emulator cannot verify them.

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
