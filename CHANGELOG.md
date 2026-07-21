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

## [2026-07-21] — M2 Accessibility Bridge Phase 1: deterministic text decisions

### Added

- Added the previously absent frozen pure declarations in `bridge/UiBridge.kt` and
  `bridge/model/ScreenSnapshot.kt`, matching the architecture's four bridge operations and
  screen/element fields without adding Android imports or an accessibility implementation.
- Added `decision/Decision.kt` and `decision/TextMatcher.kt` with normalized exact-match
  precedence, substring fallback, clickable-only filtering, explicit `NoMatch`, and spoken
  ambiguity instead of selecting the first duplicate.
- Added `bridge/FakeUiBridge.kt` under JVM test sources with realistic 設定 / 音樂 / 導航 screen
  data, scripted snapshot progression, configurable action results, and ordered call recording.
- Added `decision/TextMatcherTest.kt` and `bridge/FakeUiBridgeTest.kt` coverage for normalized exact
  and noisy/full-width matching, tier precedence, substring fallback, ambiguity, no-match,
  non-clickable exclusion, shared reply-engine normalization, and fake call ordering.

### Changed

- Promoted `pipeline/TranscriptNormalizer.kt` to the module-neutral
  `normalization/TextNormalizer.kt`; `pipeline/RuleBasedReplyEngine.kt`, its existing JVM tests,
  and `decision/TextMatcher.kt` now consume that single implementation.

### Notes

- The bridge contracts did not exist when Phase 1 began, so only the declaration/data files were
  created. No `AccessibilityService`, node-tree walking, action implementation, manifest entry,
  verification wait, Android import, coordinate, external resource ID, or fixed sleep was added.
- Exact normalized matches are evaluated as a complete tier before substring matches so a precise
  label cannot become ambiguous merely because another clickable label contains it. Multiple
  matches in the selected tier return `Speak("有兩個『…』，你要哪一個？")` rather than a click.
- NFKC, Unicode punctuation/whitespace removal, and locale-stable lowercase remain the supported
  normalization policy. No partial Traditional/Simplified conversion was introduced because the
  project has no existing conversion table/library and semantic aliases belong to Phase 2.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 101 JVM unit
  tests passed with zero failures/skips, lint completed without a blocking issue, and debug and
  release APK variants assembled successfully.

## [2026-07-17] — M1.3 amendment: main-pipeline STT selector

### Added

- Added `ui/MainSttEngine.kt` with exactly two provider-neutral DEBUG choices: V1
  `latest_short` and V2 `chirp_3`.
- Added **Speech recognition model (main pipeline)** controls beside the live transcript and a
  focused ViewModel test covering default routing, idle selection, and rejected mid-session changes.

### Changed

- Changed `SttViewModel` to snapshot the selected main recognizer before each production Start and
  route that complete session through the injected V1 or Chirp 3 `SttClient` without changing the
  recognition contracts, result reducer, timeout behavior, or automatic reply/TTS tail.
- Changed `MainActivity` composition so the existing Chirp 3 client can serve the selected main
  route while remaining available to the independent three-engine comparison harness.
- Updated `docs/ARCHITECTURE.md`, `docs/demos/M1.1.md`, and `docs/demos/M1.3.md` with selector scope,
  credentials, release behavior, and live acceptance steps.

### Notes

- Kept V1 `latest_short` as the default and release-only route. The selector is DEBUG-only so model
  evaluation cannot silently change release recognition behavior.
- Model changes are blocked during permission, capture, draining, reply, TTS, loopback, and cloud
  smoke-test work; the selected value applies only to the next user-started session.
- Chirp 2 remains available only in the comparison/metrics harness; the main selector intentionally
  contains only the two user-requested options.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 92 unit tests
  passed with zero failures/skips, lint completed with no blocking issue, and both APK variants
  assembled successfully.

## [2026-07-17] — M1.3 amendment: Roxanne spoken identity

### Changed

- Changed the rule-based greeting and direct identity responses from `我是 IVI AI 助理` to the
  requested spoken identity `我是AI 助理 Roxanne`; the application display name remains
  **IVI AI 助理**.
- Updated reply-engine/ViewModel expectations and `docs/demos/M1.3.md` so automated and manual
  acceptance use the exact new introduction.

### Notes

- Kept the identity deterministic in `RuleBasedReplyEngine`; this changes reply content only and
  does not alter intent matching, the voice pipeline, TTS routing, or the selected TTS model.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 91 unit tests
  passed, lint completed with no blocking issue, and both APK variants assembled successfully.

## [2026-07-17] — M1.3 amendment: automatic-reply TTS model selector

### Added

- Added `ui/TtsModelOption.kt`, a provider-neutral WaveNet/Gemini selection shared by the automatic
  main pipeline and standalone DEBUG TTS harness, with model/speaker labels and secret-free log IDs.
- Added a DEBUG **Automatic reply TTS model (main pipeline)** selector near the voice-loop controls.
  It displays the requested model/speaker, is disabled during all active audio/pipeline work, and is
  independent from the standalone DEBUG TTS selector.
- Added `SttViewModelTest` coverage proving idle selection updates the main-pipeline router and an
  active STT session cannot change the model for an in-flight utterance.

### Changed

- Changed `ui/MainActivity.kt` so the production `VoicePipeline` owns a `SwitchableTtsClient` with
  independent WaveNet and Gemini cloud clients behind the existing cloud-to-device fallback. Debug
  selection affects subsequent automatic replies; release allocates only the WaveNet default.
- Changed the configured Gemini profile to the user-selected `gemini-3.1-flash-tts-preview`, `Kore`,
  and calm Taiwan-Mandarin low-distraction driving prompt. Renamed stale Flash-Lite/WaveNet-A symbols
  so code and UI labels reflect the actual configured profiles (`cmn-TW-Wavenet-B` for WaveNet).
- Generalized the old DEBUG-only model enum and `SwitchableTtsClient` documentation for safe reuse by
  both selectors without changing `TtsClient`, `VoicePipeline`, STT, or reply-engine contracts.
- Updated `docs/ARCHITECTURE.md`, `docs/demos/M1.2.md`, and `docs/demos/M1.3.md` with selector scope,
  release behavior, credential requirements, Logcat evidence, and device acceptance steps.

### Notes

- Kept WaveNet as the default and restricted the main-pipeline selector to DEBUG builds so release
  behavior cannot change accidentally during model evaluation.
- Selection is snapshotted per future `speak` call and blocked while audio is active; it never
  cancels or splits an in-flight synthesis/playback request.
- A model selection records the requested cloud profile only. `TtsEngine` remains the source of
  truth for whether cloud playback succeeded or on-device fallback actually spoke.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 91 unit tests
  passed with zero failures/skips, lint completed with no blocking issue, and both APK variants
  assembled successfully.

## [2026-07-17] — M1.2 amendment: IAM bearer precedence for Gemini-TTS

### Changed

- Changed `speech/CloudTtsSynthesizer.kt` to select credentials by synthesis profile: Gemini-TTS
  now prefers the short-lived OAuth bearer token and falls back to the existing API key, matching
  STT V2 Chirp; classic WaveNet retains its existing API-key-first, bearer-fallback behavior.
- Updated `local.properties.example`, `docs/ARCHITECTURE.md`, and `docs/demos/M1.2.md` to document
  that one externally minted service-account token can serve IAM-gated Chirp and Gemini calls while
  the existing company API key remains configured for STT V1 and WaveNet.

### Added

- Added in-process gRPC assertions in `speech/CloudTtsSynthesizerTest.kt` proving Gemini sends a
  bearer token and no API-key header when both exist, and retains API-key-only fallback when the
  bearer provider reports the typed not-configured condition.

### Notes

- Chose a non-blank Gemini model name as the credential-policy boundary because classic profiles
  omit `modelName`, keeping Google/gRPC/auth details inside `speech/` and leaving `TtsClient`, UI,
  and ViewModel contracts unchanged.
- Every synthesis RPC still sends exactly one credential. Credential values and metadata are never
  logged, and the service-account JSON remains outside the repository and APK.
- Gemini authorization still requires `aiplatform.endpoints.predict` (for example through
  `roles/aiplatform.user`); assigning the role does not remove the need to mint and inject a current
  short-lived bearer token.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 90 unit tests
  passed with zero failures/skips, lint completed with no blocking issue, and both APK variants
  assembled successfully.

## [2026-07-17] — M1.3 amendment: contextual local replies and memory

### Added

- Added `pipeline/LocalReplyIntent.kt`, a normalized deterministic classifier for greeting,
  identity, help, thanks, repeat, conversational cancel, and simple open/play target requests with
  polite-prefix synonyms and an explicit missing-target result.
- Added `pipeline/ConversationMemory.kt` with a thread-safe, 500-character-bounded in-process
  snapshot of the last transcript, reply, requested target, and last explicitly verified action.
- Added focused pure-JVM coverage for intent synonyms, target extraction, clarification, helpful
  fallback, repeat/no-history, cancel/no-pending-target, memory bounds, and explicit verified-action
  recording in `pipeline/RuleBasedReplyEngineTest.kt`.

### Changed

- Changed `pipeline/RuleBasedReplyEngine.kt` from two exact rules plus a dead-end fallback to a
  serialized contextual local turn: common commands receive useful replies, `再說一次` repeats the
  prior reply, `取消` clears the remembered target, and unmatched text points users to `幫助`.
- Changed parsed target replies to acknowledge intent while explicitly stating that screen operation
  is not connected yet; the rule engine never fabricates an action before M2.2/M2.3.
- Updated the `ReplyEngine` documentation to permit bounded, non-persistent local context without
  changing its provider-neutral signature or adding Android/network behavior.
- Updated `docs/ARCHITECTURE.md` and `docs/demos/M1.3.md` with the exact memory lifecycle, repeat and
  cancel semantics, capability boundary, typed-input acceptance steps, and new fallback text.

### Notes

- Chose explicit local intent types instead of substring-only response rules so polite synonyms and
  targets remain testable while unrelated transcripts cannot accidentally match a command.
- Kept memory process-local, synchronized, and bounded to avoid storing conversation history,
  credentials, or audio; app process recreation intentionally clears it.
- Reserved `lastSuccessfulAction` for an explicit post-verification update. M1.3 records requested
  targets only because claiming screen success before M2.2/M2.3 would violate the architecture.
- Kept `ReplyEngine.replyTo(String): String` unchanged so a future LM-backed implementation can
  still replace the deterministic engine behind `LM_ENABLED` without reworking VoicePipeline.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 89 unit tests
  passed with zero failures/skips, lint completed with no blocking issue, and both APK variants
  assembled successfully.

## [2026-07-17] — M1.3 amendment: provider-final automatic endpointing

### Added

- Added an idempotent production-session end operation in `ui/SttViewModel.kt`, shared by manual
  **Stop** and the first non-blank final `SttResult`. It cancels only microphone capture, keeps the
  cloud collector alive to drain already-in-flight finals, and retains the existing five-second
  recoverable drain timeout.
- Added focused fake/virtual-time coverage in `ui/SttViewModelTest.kt` proving that partial results
  keep listening, a final result ends capture without manual Stop, microphone/input cleanup precedes
  TTS, and an automatically triggered stalled drain reaches Error with working Retry.

### Changed

- Changed the production M1.3 interaction from **Start → speak → Stop → automatic reply** to
  **Start → speak → provider final → automatic stop/drain/reply**. Manual Stop remains available as
  an idempotent early-end/slow-provider override and follows the exact same resource-release path.
- Updated `docs/ARCHITECTURE.md` to bind the new semantics without weakening push-to-talk: Start is
  still explicit, and final-result endpointing adds no wake word, pre-Start recording, barge-in, or
  automatic re-listen after TTS.
- Updated `docs/demos/M1.3.md` with explicit hands-off `你好` acceptance, manual Stop/final-race
  acceptance, automatic-drain timeout recovery, and the limitation that live behavior depends on
  Google emitting a non-blank final result.

### Notes

- Chose the existing provider-neutral `SttResult.isFinal` signal instead of adding WebRTC VAD,
  Sherpa-ONNX, a model asset, or a new contract because M1.3 targets one short command and Google
  already supplies an end-of-utterance decision. A local `SpeechEndpointer` remains a follow-up only
  if real-device final timing is too slow or inconsistent.
- Kept all final segments already in flight and retained exactly one ReplyEngine/TTS invocation per
  completed stream; repeated finals and a Stop/final race cannot re-arm the drain watchdog or start
  duplicate speech.
- Preserved the echo rule structurally: a provider final cancels the MicRecorder child, waits for its
  `finally`/audio-channel close and cloud completion, and only then enters Replying/Speaking.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 83 unit tests
  passed with zero failures/skips, lint completed with no blocking issue, and both APK variants
  assembled successfully.
- Automatic end timing and audible reply still require a live production V1 credential, network,
  and real microphone/speaker device; deterministic JVM fakes cannot prove Google's endpoint timing
  or physical AudioRecord release.

## [2026-07-16] — M1.3 Phase 2: automatic voice interaction loop

### Added

- Added `pipeline/VoicePipeline.kt`, which composes one completed transcript through
  `ReplyEngine` and the provider-neutral `TtsClient`, suspending until the generated reply finishes
  playback and propagating structured cancellation unchanged.
- Added `Replying`/`Speaking`, `lastReplyText`, and the default-enabled automatic voice-loop toggle
  to `ui/SttUiState.kt`.
- Added focused fake/virtual-time ViewModel coverage for partial suppression, exactly-once replies
  across multiple final segments, typed-input speech, microphone-before-TTS ordering, recoverable
  TTS failure, cancellation, toggle-off behavior, and a clean second explicit Start.
- Replaced the Phase 1-only `docs/demos/M1.3.md` with reproducible automated, mic-less emulator,
  real-device, toggle, echo-rule, fallback, and recovery acceptance steps.

### Changed

- Changed `ui/SttViewModel.kt` so a completed production session aggregates its non-blank final
  segments, releases and joins microphone capture, generates one reply, speaks it once, and returns
  to `Idle`. Typed input now invokes the same post-final pipeline without requiring mic permission.
- Changed `ui/MainActivity.kt` to inject `RuleBasedReplyEngine` plus a separately owned
  cloud-to-device `FallbackTtsClient` through `VoicePipeline`; the existing DEBUG TTS ViewModel keeps
  its independent client lifecycle.
- Reordered `ui/SttDebugScreen.kt` so session status and Start/Stop are followed immediately by the
  partial transcript, accumulated final transcript, and current/last assistant reply, with typed
  input next and all cloud/metrics/loopback/standalone-TTS tools below.
- Extended `ui/DebugAudioExclusionPolicy.kt` and its tests so integrated Replying/Speaking states
  block competing audio actions in addition to the existing standalone TTS gate.
- Updated `app/src/main/res/values/strings.xml` for the visible assistant reply and the DEBUG
  **Automatic voice reply** switch.

### Notes

- Defined “exactly once” at the completed STT-stream/session boundary: partial results never reply,
  multiple final segments are newline-joined in arrival order, and one pipeline invocation occurs
  only after the recognizer completes or Stop finishes draining.
- Kept push-to-talk semantics: Stop closes the active utterance, automatic reply starts afterward,
  and the app never opens the microphone again until the user explicitly presses Start.
- Joined the capture child before invoking TTS because resource-order enforcement, not UI disabling
  alone, is required to prevent the assistant from recording its own speech.
- Chose a DEBUG switch backed by state rather than a new production feature flag because the toggle
  is an evaluation aid; it defaults to enabled in every build, while release has no switch surface.
- Gave the production loop its own fallback TTS composition because sharing the standalone DEBUG
  ViewModel's closeable client would create ambiguous lifecycle ownership and double-disposal risk.
- M1.3 (the Week 1 voice-loop mainline) is code-complete pending real-device acceptance with a live
  STT credential, microphone/speaker, and usable cloud or Taiwan-Mandarin device TTS voice.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 82 unit tests
  passed with zero failures/skips, lint completed with no blocking issue, and both APK variants
  assembled successfully.

## [2026-07-16] — M1.3 Phase 1: deterministic reply engine

### Added

- Added `pipeline/ReplyEngine.kt`, the pure transcript-to-non-blank-reply contract
  for the M1.3 composition layer.
- Added `pipeline/TranscriptNormalizer.kt` with reusable NFKC normalization,
  punctuation/whitespace removal, and locale-stable lowercase comparison.
- Added `pipeline/RuleBasedReplyEngine.kt` with preset greetings (`你好`, `哈囉`,
  `hello`), a demo-friendly identity response, and a transcript-preserving fallback
  for all unmatched or empty input.
- Added focused pure-JVM tests covering noisy greeting variants, identity matching,
  unmatched fallback, the never-blank guarantee, and normalization behavior.
- Added `docs/demos/M1.3.md` with the deterministic Phase 1 acceptance matrix and
  an explicit note that automatic STT→reply→TTS wiring starts in Phase 2.

### Notes

- Placed the contract and implementation in `pipeline/` because this is
  composition-level reply logic; no `speech/`, `bridge/`, `decision/`, UI, or core
  Bridge contract was changed.
- Chose exact normalized rule keys rather than substring matching so unrelated
  utterances cannot accidentally trigger a greeting response.
- Preserved the trimmed original transcript only in the unknown-response text while
  using normalized text solely for matching, keeping spoken feedback understandable.
- LM generation is intentionally absent. A future LM-backed `ReplyEngine` can be
  selected behind `LM_ENABLED` (default false) without changing the local contract.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`:
  all 75 unit tests passed, lint completed with no blocking issue, and both debug
  and release APKs assembled successfully.

## [2026-07-16] — Chirp V2 service-account token workflow

### Changed

- Changed only `speech/CloudSttV2Client.kt` credential selection to prefer a
  short-lived OAuth bearer token for IAM-gated Chirp 2/3 calls, falling back to the
  existing API key only when no token is configured. V1 STT and Cloud TTS retain
  their API-key-first behavior, and every RPC still sends exactly one credential.
- Changed `app/build.gradle.kts` so a shell `GCP_STT_ACCESS_TOKEN` overrides the
  gitignored `local.properties` token for DEBUG builds. Default/release BuildConfig
  fields remain empty.
- Updated `local.properties.example` and `docs/demos/M1.1.md` with an external
  service-account activation/token-minting workflow that keeps the JSON outside the
  repository and APK while preserving the existing company API key.
- Updated the V2 in-process gRPC credential test to prove bearer precedence when
  both modes are configured and API-key fallback when the token is unavailable.

### Notes

- A service-account JSON is used only by the developer's external `gcloud` process
  to mint an approximately one-hour OAuth access token; the Android app never reads,
  packages, logs, or persists the JSON/private key.
- Chose a per-build environment override because it avoids copying the temporary
  token into a project file and lets the unchanged API key continue serving V1 STT
  and Cloud TTS.
- This environment could not read the supplied Downloads file because macOS denied
  the process access, and `gcloud` was not installed in its shell. Live token minting,
  IAM role acceptance, Chirp availability, and real-device recognition therefore
  remain local-machine acceptance steps.
- Verified the focused in-process `CloudSttV2ClientTest`: all credential, protocol,
  failure-mapping, and cancellation cases passed without a real credential/network.

## [2026-07-16] — DEBUG TTS model evaluation picker

### Added

- Added `config/GcpTtsSynthesisProfile` and centralized profiles for the existing
  `cmn-TW-Wavenet-A` baseline and `gemini-2.5-flash-lite-preview-tts` with the single speaker
  `Kore`, Taiwan-Mandarin style prompt, 4,000-byte Gemini text bound, and unchanged 24 kHz
  LINEAR16 output.
- Added provider-neutral `speech/SwitchableTtsClient.kt` so the DEBUG selector changes only the
  next cloud utterance while preserving one shared device fallback and lifecycle cleanup.
- Added `ui/DebugTtsModel.kt`, selected-model state, Compose radio controls, and visible
  model/speaker details in the DEBUG text-to-speech section.
- Added provider-neutral `TtsPlaybackEngine` route state at the fallback boundary. The DEBUG screen
  now shows **TTS engine (active/last): Cloud/On-device**, and each attempt prints a CSV-safe
  credential-free `TtsEngine` Logcat line (`engine=cloud` or `engine=on_device`).
- Added focused in-process request and pure routing/ViewModel tests for Gemini model/Kore encoding,
  single-speaker configuration, selection, and state retention.

### Changed

- Parameterized `CloudTtsSynthesizer` and `CloudTtsClient` with immutable non-secret synthesis
  profiles while retaining WaveNet A as the constructor default and leaving `TtsClient` unchanged.
- Updated `docs/demos/M1.2.md` with repeatable WaveNet-versus-Flash-Lite selection and live-device
  comparison steps, including how cloud-to-device fallback affects acceptance evidence.

### Notes

- Used `VoiceSelectionParams.name = Kore` because Cloud TTS single-speaker requests encode the
  speaker as the voice name; `speakerId` is reserved for multi-speaker configuration, which this
  IVI assistant does not need.
- Reused the existing Cloud TTS V1 unary endpoint, dependency, LINEAR16/WAV parser, AudioTrack,
  audio focus, cancellation, echo exclusion, API-key-first/bearer-fallback credential precedence,
  and device fallback. No new dependency, TTS/STT contract, VoicePipeline, bridge, decision, or
  Accessibility change was added.
- Flash-Lite and its `cmn-TW` language support are Preview. Live Google acceptance depends on the
  project and credential having Gemini-TTS access plus `aiplatform.endpoints.predict`; a standard
  API key can still be rejected even when it works for classic WaveNet, after which the existing
  on-device fallback speaks.
- Engine reporting occurs immediately before each actual client attempt, so a cloud rejection is
  visibly/logically followed by On-device rather than leaving a misleading Cloud label. Logging
  contains no text, audio, model prompt, API key, token, or authorization metadata and cannot break
  speech if the Android logger itself fails.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 70 unit tests
  passed, lint completed with no blocking issue, and debug/release APKs assembled successfully.

## [2026-07-16] — M1.2 Phase 5: Test and acceptance closeout

### Added

- Added one focused `TtsViewModelTest` for the only genuine matrix gap found: a new Speak request
  cancels and joins the prior suspended utterance, starts the replacement once, prevents stale state,
  and finishes without surfacing cancellation as Error.

### Changed

- Strengthened the existing ViewModel recovery test to throw the production
  `CloudTtsException(CloudTtsFailureReason.Unavailable)` category instead of a generic exception,
  proving typed cloud failure reaches recoverable Error without leaking provider detail.
- Consolidated `docs/demos/M1.2.md` into the final reproducible milestone script, separating the
  deterministic fake-based matrix from live cloud, device fallback, Stop, pitch/sample-rate, and
  microphone/audio-focus acceptance.
- Marked **M1.2 (TTS) complete**: cloud synthesis/playback, on-device fallback, cancellation,
  replacement, WAV parsing, feature-flag routing, and echo-rule gates are implemented and covered.

### Notes

- Audited the complete TTS suite before adding tests. Success/state transitions, typed recovery,
  Stop/cancellation, cloud/device fallback, flag-off routing, valid/extra/malformed WAV handling,
  and echo exclusion already had focused coverage; they were not duplicated.
- Added no production capability, dependency, vendor, contract, VoicePipeline, bridge, decision,
  Accessibility, or STT change in this closeout phase.
- Live Google credential acceptance, audible cloud/device voices, correct real-device pitch/speed,
  Android audio focus/routing, and physical mic/speaker exclusion remain reproducible manual
  acceptance steps because JVM fakes cannot prove hardware or external-service behavior.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 66 unit tests
  passed, lint completed, and debug/release APKs assembled successfully. This closes M1.2.

## [2026-07-16] — M1.2 Phase 4: Cloud playback and device fallback

### Added

- Added `speech/Linear16WavParser.kt`, parsing RIFF chunks rather than assuming a 44-byte header,
  validating PCM/mono/16-bit/sample-rate metadata, handling chunk padding, and returning only the
  copied `data` payload for playback; malformed or truncated audio fails recoverably.
- Added `speech/CloudTtsAudioPlayer.kt` with a speech-internal player seam and Android AudioTrack
  implementation using dynamic 24 kHz mono PCM16 configuration, static PCM writes, an event-driven
  final-frame marker, bounded completion timeout, transient assistant audio focus, and guaranteed
  Stop/release/focus cleanup on cancellation.
- Added contract-compliant `speech/CloudTtsClient.kt`, composing Phase 3 synthesis, WAV parsing, and
  PCM playback so `speak(text)` returns only after audible cloud playback completes.
- Added `speech/FallbackTtsClient.kt`, selecting cloud first when enabled, device only when disabled,
  and falling back once to DeviceTtsClient for typed cloud synthesis/parser/playback failures.
- Added `config/FeatureFlags.kt` as the architecture-owned behavior flag boundary, plus a
  `CLOUD_TTS_ENABLED` BuildConfig field and documented DEBUG `local.properties` override.
- Added `ui/DebugAudioExclusionPolicy.kt` with pure, testable gates preventing TTS start until every
  capture/drain/output state is released and blocking every microphone entry during Speaking.
- Added focused `CloudTtsClientTest`, `FallbackTtsClientTest`, and
  `DebugAudioExclusionPolicyTest` coverage for WAV-header stripping, malformed audio, cloud/device
  routing, flag-off behavior, cancellation/Stop, and bidirectional echo exclusion.

### Changed

- Changed the DEBUG TTS composition in `ui/MainActivity.kt` from device-only to an owned
  CloudTtsClient plus DeviceTtsClient behind FallbackTtsClient; release builds still do not allocate
  the DEBUG-only ViewModel because its UI is absent.
- Changed `ui/SttDebugScreen.kt` to use the shared exclusion policy and updated the explanation in
  `res/values/strings.xml` to describe cloud-first/device-fallback behavior.
- Updated `local.properties.example` and `docs/demos/M1.2.md` with flag usage, live cloud/device
  fallback acceptance, correct-pitch/speed checks, cancellation, and echo-rule verification.

### Notes

- Chose RIFF chunk scanning instead of removing a fixed byte count because WAV may contain optional
  metadata and padded chunks; only the validated `data` chunk can be interpreted as PCM samples.
- Chose AudioTrack `MODE_STATIC` plus a final-frame marker because each bounded unary response is
  already complete in memory and `TtsClient.speak` must suspend through actual playback without a
  sleep-based estimate.
- Chose `USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`, and transient audio focus for cloud output; focus
  is requested immediately before `play()` and abandoned in every success/failure/cancellation path.
- Chose fallback only for typed CloudTtsException failures. Structured cancellation never triggers
  device speech, preventing Stop from unexpectedly replaying the same utterance through fallback.
- `CLOUD_TTS_ENABLED` defaults true; a DEBUG-only local false value bypasses credential lookup and
  network work entirely. FeatureFlags is the sole runtime code boundary reading this behavior flag.
- Preserved the echo rule without changing STT internals: the DEBUG UI cannot invoke TTS while STT
  owns or is releasing audio, and cannot invoke production capture, loopback, or smoke-test controls
  throughout the complete TTS Speaking state. M1.3 will actively stop its pipeline-owned mic before
  integrated speech.
- Added no VoicePipeline, bridge, decision, Accessibility, STT client/contract/session changes, or
  TtsClient contract changes.
- Live API-key acceptance, cloud voice output, Android audio routing/focus, correct audible pitch and
  speed, and real on-device fallback require a networked Android device and remain manual checks.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 65 unit tests
  passed, lint completed, and debug/release APKs assembled successfully.

## [2026-07-16] — M1.2 Phase 3: Google Cloud TTS synthesis boundary

### Added

- Added `config/GcpTtsConfig.kt` with the non-secret V1 endpoint, `cmn-TW` language,
  `cmn-TW-Wavenet-A` voice, LINEAR16 encoding, and 24 kHz mono PCM16 metadata.
- Added provider-neutral `speech/SynthesizedAudio.kt` so cloud bytes carry the sample rate, channel
  count, bit depth, and explicit LINEAR16-WAV container required by Phase 4 playback.
- Added `speech/CloudTtsFailure.kt` with fixed recoverable categories for configuration,
  authentication, permission, quota, network, timeout, invalid input, empty response, lifecycle,
  and unknown failures.
- Added `speech/CloudTtsSynthesizer.kt`, owning one reusable TLS OkHttp gRPC channel, unary
  synthesis, API-key-first/bearer-fallback authentication, a 15-second RPC deadline, input
  validation, cancellation-safe call bridging, provider-neutral result mapping, and disposal.
- Added `speech/CloudTtsSynthesizerTest.kt` with an in-process fake Google service covering request
  text/voice/format, audio response mapping, mutually exclusive credential modes, missing
  configuration, recoverable quota status, and coroutine-to-RPC cancellation.

### Changed

- Added `com.google.api.grpc:grpc-google-cloud-texttospeech-v1:2.92.0` to
  `app/build.gradle.kts`, reusing the existing gRPC 1.76.0 BOM, protobuf runtime, and Android
  `grpc-okhttp` transport without adding Netty or Google ADC/service-account libraries.
- Updated `docs/demos/M1.2.md` with the Phase 3 automated acceptance boundary, exact synthesis
  settings, credential assumptions, and Phase 4 live/playback responsibilities.

### Notes

- Kept the binding `TtsClient.speak(text): Unit` contract unchanged. With playback explicitly out of
  Phase 3, the approved design exposes synthesis through `CloudTtsSynthesizer`; Phase 4 will compose
  it with `AudioTrack` and device fallback to create a contract-compliant `CloudTtsClient` that
  returns only after playback completes.
- Chose unary `SynthesizeSpeech` because this milestone synthesizes one bounded utterance at a time
  and does not require streaming text or audio.
- Chose the documented `cmn-TW-Wavenet-A` Taiwan-Mandarin voice for a stable premium default; voice
  changes remain centralized in `GcpTtsConfig`.
- Chose LINEAR16 at 24 kHz for lossless speech and straightforward Android PCM playback. Google unary
  LINEAR16 includes a WAV header, so the provider-neutral format explicitly says `Linear16Wav` and
  Phase 4 must parse the WAV data chunk before sending samples to `AudioTrack`.
- Reused M1.1 credential precedence: a non-blank API key is sent alone as `x-goog-api-key`; otherwise
  a bearer token is sent as Authorization with optional non-secret quota-project attribution.
- Added no playback, DEBUG/production wiring, fallback selection, VoicePipeline, bridge, decision,
  Accessibility, STT changes, or `TtsClient` contract changes.
- Live Google credential acceptance, returned voice audio, WAV parsing, audible playback, and device
  fallback remain Phase 4 acceptance work requiring network and an Android audio device.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 57 unit tests
  passed, lint completed, and debug/release APKs assembled. Gradle dependency insight found no
  `grpc-netty` artifact in `debugRuntimeClasspath`.

## [2026-07-16] — M1.2 Phase 2: On-device Taiwan-Mandarin speech

### Added

- Added `speech/DeviceTtsClient.kt`, implementing the unchanged provider-neutral `TtsClient`
  contract with Android `TextToSpeech`, `Locale.TAIWAN`, transient audio-focus ownership,
  suspend-until-playback-completes behavior, bounded asynchronous initialization, typed recoverable
  failures, cancellation-safe Stop, and lifecycle disposal.
- Added a speech-internal `DeviceTtsEngine` boundary so Android callbacks remain inside `speech/`
  while pure JVM tests can deterministically drive initialization and utterance completion.
- Added `speech/DeviceTtsClientTest.kt` coverage proving completion resumes `speak`, playback errors
  remain recoverable, cancellation stops playback without becoming a user error, and missing zh-TW
  data is rejected before a speak request.

### Changed

- Replaced the Phase 1 silent placeholder in `ui/MainActivity.kt` with `DeviceTtsClient`; DEBUG Speak
  now uses the installed on-device engine, while release composition does not initialize DEBUG-only
  TTS resources.
- Changed `ui/TtsViewModel.kt` lifecycle cleanup to cancel active speech and close AutoCloseable TTS
  clients, ensuring Android `TextToSpeech.shutdown()` runs when the ViewModel is cleared.
- Updated `ui/SttDebugScreen.kt`, `res/values/strings.xml`, and `docs/demos/M1.2.md` for real device
  Speak/Stop verification with the exact utterance `「你好，我是 IVI AI 助理」`.
- Removed the temporary `SilentPlaceholderTtsClient`; no fabricated or silent playback path remains.

### Notes

- Chose `Locale.TAIWAN` (`zh-TW`) because the milestone targets Taiwan Mandarin; Android's
  `LANG_MISSING_DATA` and `LANG_NOT_SUPPORTED` results map to distinct recoverable failures.
- Chose unique opaque utterance IDs plus `UtteranceProgressListener.onDone` as the completion
  boundary because `TtsClient.speak` must not return merely when Android accepts the request.
- Bounded asynchronous engine initialization at 10 seconds so a broken engine callback cannot leave
  the UI in Speaking indefinitely.
- Serialized requests with a coroutine Mutex and Android `QUEUE_FLUSH` because this DEBUG harness
  owns one utterance at a time; cancellation removes callback ownership and calls `stop()` promptly.
- Preserved the echo rule by keeping TTS Speak mutually exclusive with all existing STT microphone
  and playback activity in the DEBUG screen and requesting transient Android audio focus before
  speech; Stop/cancellation releases focus before controls are usable.
- Added no Cloud TTS, VoicePipeline, bridge, decision, Accessibility, STT contract, or STT production
  changes. Cloud TTS and credential reuse remain Phase 3 work.
- Audible output and installed Taiwan-Mandarin voice availability require an Android device or
  emulator; pure JVM tests intentionally use a fake engine and cannot verify sound or routing.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 52 unit tests
  passed, debug/release APKs assembled, and Android lint completed without a blocking issue.

## [2026-07-16] — M1.2 Phase 1: TTS contract and DEBUG state harness

### Added

- Added provider-neutral `speech/TtsClient.kt` with the binding suspend-until-playback-completes
  contract and cancellation/failure expectations, without Google or audio SDK types.
- Added `speech/SilentPlaceholderTtsClient.kt`, a clearly labeled temporary implementation that
  produces no audio and exposes a one-second cancellable state-verification interval only.
- Added `ui/TtsUiState.kt` with explicit Idle, Speaking, Completed, and Error states plus current
  text and recoverable error state.
- Added `ui/TtsViewModel.kt` with provider-neutral client injection, lifecycle-scoped replacement,
  Speak/Stop cancellation, fixed non-secret failure mapping, and a Factory composition boundary.
- Added DEBUG-only text, Speak, Stop, current-text, status, and error controls to
  `ui/SttDebugScreen.kt`, composed from a separate TtsViewModel in `ui/MainActivity.kt`.
- Added test-only `speech/FakeTtsClient.kt` and three focused `ui/TtsViewModelTest.kt` scenarios for
  Speaking → Completed, cancellation → Idle, and recoverable failure → successful retry.
- Added `docs/demos/M1.2.md` with exact Phase 1 automated and DEBUG UI acceptance steps.

### Changed

- Changed the shared DEBUG screen to disable STT/microphone controls while TTS is Speaking and to
  disable TTS Speak while microphone, cloud STT, loopback playback, or transition work is active.
  STT production clients, contracts, reducers, and session code remain unchanged.
- Added Phase 1 TTS strings that explicitly state the placeholder produces no audio.

### Notes

- Kept standalone TTS in a separate TtsViewModel rather than expanding SttViewModel because M1.2
  state and cancellation can evolve without risking the completed production STT reducer/session.
- Chose a one-second cancellable placeholder interval so a tester can observe Speaking and exercise
  Stop; it is a coroutine delay for state verification, not simulated playback or an audio claim.
- Cancelled a predecessor synchronously before the replacement wrapper joins it because an immediate
  Stop must never leave the older client request running.
- Kept TtsClient's single `speak(text)` contract unchanged from architecture; concrete clients must
  suspend through real playback and respond promptly to cancellation in Phases 2 and 3.
- Cloud TTS authentication will reuse the existing provider-neutral ApiKeyProvider /
  AccessTokenProvider plumbing. Per project direction, the company API key is assumed to work for
  Cloud TTS unlike STT V2; Phase 1 makes no cloud call, so this remains a Phase 3 live checkpoint.
- Added no DeviceTtsClient, CloudTtsClient, Google TTS dependency, audio playback, VoicePipeline,
  bridge, decision, Accessibility, or production TTS integration.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`: all 48 unit tests
  passed, debug/release APKs assembled, and Android lint completed without a blocking issue.
- Real audible speech, Android audio focus/routing, device-engine availability, and Cloud TTS
  credential/API acceptance remain intentionally unverifiable until Phases 2 and 3.

## [2026-07-16] — V2 global endpoint correction

### Fixed

- Fixed `config/GcpSttV2Config.kt` so `GCP_STT_LOCATION=global` opens Google's
  canonical `speech.googleapis.com` endpoint instead of the invalid
  `global-speech.googleapis.com` hostname. The recognizer resource correctly remains
  `projects/{project}/locations/global/recognizers/_`.
- Added `config/GcpSttV2ConfigTest.kt` coverage for global, regional, multi-regional,
  recognizer-path, and blank-location behavior.

### Changed

- Updated `docs/demos/M1.1.md` to distinguish global endpoint routing from regional
  and multi-regional routing.

### Notes

- Special-cased only endpoint construction because `global` is a valid resource
  location but not a DNS prefix; all other location values retain Google's
  `{location}-speech.googleapis.com` convention.
- Authentication remains a separate concern: a standard API key supplies project/
  quota identity but cannot satisfy V2's OAuth scope and
  `speech.recognizers.recognize` IAM requirement.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`:
  all 45 unit tests passed, lint completed, and both APK variants assembled.

## [2026-07-16] — DEBUG STT V1 / Chirp 2 / Chirp 3 evaluation

### Added

- Added `config/GcpSttV2Config.kt` with non-secret V2 model, Taiwan-Mandarin,
  explicit PCM, regional endpoint, and inline-recognizer resource configuration.
- Added `speech/CloudSttV2Client.kt`, a provider-neutral `SttClient` implementation
  for V2 `chirp_2` and `chirp_3` using regional TLS gRPC, config-first streaming,
  bounded audio/result buffers, final-only confidence, recoverable status mapping,
  prompt cancellation, and reusable channel disposal.
- Added a DEBUG-only V1 / Chirp 2 / Chirp 3 selector in `ui/SttDebugScreen.kt`, with
  all three provider-neutral clients composed in `ui/MainActivity.kt`. The existing
  V1 client remains the normal production Start/Stop client.
- Added `ui/DebugSttEngine.kt` and `ui/DebugSttMetrics.kt` to measure first-token,
  Stop-to-final, and total latency with a monotonic clock; classify each outcome;
  display the comparison evidence; and emit one CSV-friendly `SttMetricsCsv` log
  row per DEBUG run without credentials or raw audio.
- Added deterministic in-process V2 gRPC tests covering config-first request order,
  inline recognizer, both model identifiers, explicit audio settings, interim/final
  mapping, final-only confidence, mutually exclusive API-key/bearer metadata,
  recoverable statuses, and collector cancellation.
- Added a virtual-time metrics test and ViewModel selection test proving latency
  boundaries, CSV formatting, V1/Chirp client routing, and production V1 isolation.

### Changed

- Changed `app/build.gradle.kts` to add
  `com.google.api.grpc:grpc-google-cloud-speech-v2:4.74.0`, retaining the gRPC 1.76.0
  BOM and Android `grpc-okhttp` transport; V1 remains at the matching 4.74.0 stub.
- Added debug-only `GCP_STT_LOCATION` injection from gitignored `local.properties`;
  default and release BuildConfig keep it empty. Updated `local.properties.example`
  with project/location setup and availability cautions.
- Extended DEBUG-only fields in `ui/SttUiState.kt`, `ui/SttViewModel.kt`, Compose
  strings, and screen rendering for engine selection, isolated raw transcripts,
  evaluation timings, outcome, rubric guidance, and CSV logging.
- Updated `docs/demos/M1.1.md` with a reproducible same-utterance comparison, CSV
  schema, automatic scoring definitions, manual/external criteria, and explicit
  live-GCP/device requirements.

### Notes

- Chose V2 explicit decoding (`LINEAR16`, 16 kHz, mono) because it exactly matches
  MicRecorder output and avoids ambiguity or transcoding during model comparison.
- Chose the V2 implicit recognizer path
  `projects/{project}/locations/{location}/recognizers/_` because model/language/
  decoding are supplied inline and no persistent recognizer resource is needed.
- Chose `asia-southeast1` as the DEBUG default because current Google tables list
  `cmn-Hant-TW` for Chirp 2 and Chirp 3 there and it is geographically suitable for
  Taiwan testing; it remains configurable because regional/model availability can
  change and must be confirmed before evaluation.
- Kept API-key precedence over bearer authentication, with exactly one credential
  per RPC, because it matches the existing approved V1 behavior. V2 additionally
  requires a non-blank project ID for its recognizer resource.
- Split audio requests at 15,000 bytes because Google V2 limits each streaming audio
  field to 15 KB; retained a four-chunk upstream buffer and bounded result bridge so
  a slow uplink or UI collector cannot grow memory without limit.
- Measured first token from the first audio chunk requested by the selected client
  to the first non-blank interim, final latency only from Stop to a later final, and
  total latency from stream start to final. These distinct monotonic boundaries map
  directly to the supplied evaluation rubric without changing `SttResult`.
- Kept the comparison harness isolated from production transcripts and routing:
  `CloudSttClient` V1 internals, `SttClient`, `SttResult`, and normal production Start
  are unchanged.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`:
  all 41 unit tests passed, debug/release APKs assembled, and Android lint completed
  without a blocking issue. Resolved runtime dependencies contain `grpc-okhttp`
  1.76.0 and no `grpc-netty` transport.
- Live API-key/token acceptance, project IAM/quota, current Chirp 2/3 availability
  in the configured region, Mandarin recognition quality/latency, and microphone/
  network conditions remain real-device/live-GCP verification items.

## [2026-07-16] — M1.1 closeout: timeout, credential check, and acceptance

### Added

- Added production lack-of-progress watchdogs in `ui/SttViewModel.kt`: a bounded
  first-transcript wait and a separate final-drain wait after Stop. Expiry cancels
  and joins microphone/RPC work before exposing recoverable Error with Retry.
- Added three focused `ui/SttViewModelTest.kt` tests: one covers API-key-only,
  token-only, both, and neither local configuration; two use coroutine virtual time
  to cover stalled first response and stalled Stop-time drain.

### Changed

- Changed the local cloud-configuration check to inspect `ApiKeyProvider` first and
  fall back to `AccessTokenProvider`, matching the existing CloudSttClient credential
  precedence without retaining, displaying, or logging either value.
- Changed `ui/MainActivity.kt` composition to share the same
  `BuildConfigApiKeyProvider` between the configuration check and CloudSttClient.
- Updated `ui/SttUiState.kt`, `ui/SttDebugScreen.kt`, and `strings.xml` so local
  configuration explicitly means API-key or OAuth-token presence, not remote Google
  acceptance.
- Replaced `docs/demos/M1.1.md` with the final reproducible M1.1 acceptance script,
  separating deterministic build/unit checks from real-device, microphone, network,
  and live-credential checks.

### Notes

- Chose 15 seconds for a first non-blank STT transcript because short push-to-talk
  commands should show recognition progress promptly while still allowing device,
  TLS, and cloud setup latency.
- Chose 5 seconds for Stop-time final draining because MicRecorder is already closed
  at that boundary and a short final response must not leave the UI in Stopping.
- Cancelled the first-response watchdog after the first non-blank interim or final
  because it guards connection/no-progress stalls, not total speaking duration.
- Used monotonic production-session identities because a stale watchdog must never
  cancel or overwrite a newer Start or a Retry recovery.
- Kept both watchdogs in `viewModelScope` and cancel-and-joined the captured session
  on expiry because microphone and RPC cleanup must precede visible timeout Error.
- Kept API-key precedence consistent with CloudSttClient because the configuration
  indicator should describe the credential mode the next RPC will actually select.
- Kept the check local-only: Configured means a credential is present in the debug
  build, not that Google accepted it or that API/billing/quota is valid.
- Added no broad error taxonomy and made no changes to `SttClient`, `SttResult`,
  `GcpSttConfig`, `CloudSttClient`, or speech/bridge/decision/pipeline/TTS/
  Accessibility implementations.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`:
  all 34 unit tests passed (including 17 ViewModel tests), debug/release APKs
  assembled, and Android lint completed without a blocking issue.
- Live API-key acceptance, Taiwan-Mandarin recognition, physical microphone release,
  and vehicle/device connectivity remain explicit real-device acceptance items; the
  repository environment did not make a live Google speech call.

## [2026-07-16] — M1.1 Phase 6: Production streaming transcription

### Added

- Added production `SttClient` injection to `ui/SttViewModel.kt` and its Factory,
  with `ui/MainActivity.kt` composing the existing `CloudSttClient` behind the
  provider-neutral interface.
- Added a bounded production audio handoff that streams the existing MicRecorder
  Flow into `SttClient.stream` and reduces every emitted interim/final `SttResult`
  through the existing production transcript path.
- Added five focused `SttViewModelTest.kt` scenarios for production interim/final
  reduction, prior-session replacement, Stop-time final draining, recoverable cloud
  failure plus Retry, and cancellation that remains invisible to the user.
- Added a reproducible Phase 6 no-credential checkpoint and live production
  speak-to-text acceptance procedure to `docs/demos/M1.1.md`.

### Changed

- Changed normal **Start** from microphone-only collection to one structured
  MicRecorder → production SttClient session. Starting again cancels and joins the
  predecessor before the replacement owns microphone/network resources.
- Changed normal **Stop** to cancel only the production capture child, retain
  `Stopping`, and let audio completion half-close the existing cloud stream so a
  delayed final result can arrive before `Completed`.
- Changed the production reducer's status handling so finals received during an
  active push-to-talk session preserve `Listening`/`Stopping`; transcript behavior
  remains partial replacement, one final append, and partial clearing.
- Changed `ui/SttUiState.kt`, `ui/SttDebugScreen.kt`, and MainActivity KDoc to
  describe real production cloud boundaries while preserving typed input, DEBUG
  PCM loopback, and the isolated DEBUG cloud smoke-test fields.
- Changed production failure handling to leave every cloud/microphone failure in a
  recoverable `Error` state using fixed or existing non-secret messages; normal
  `CancellationException` is still propagated internally and never shown.

### Notes

- Reused one `CloudSttClient` instance for production and DEBUG smoke roles because
  the UI makes their microphone sessions mutually exclusive and one reusable TLS
  channel avoids duplicate network resources; ViewModel disposal closes it once.
- Kept ViewModel typed only against `SttClient` because provider, gRPC, protobuf,
  credential-mode, and Google-specific details must remain outside the UI layer.
- Chose a four-chunk production channel because it matches Phase 5's bounded
  approximately 400 ms uplink window and prevents unbounded memory growth under
  backpressure without adding a second microphone capture path.
- Closed the audio channel only after MicRecorder collection unwinds because the
  microphone must be released before cloud half-close/completion, preserving the
  echo rule and safe future playback ordering.
- Cancelled and joined the entire prior session on a new Start, but only the capture
  child on Stop, because replacement prioritizes exclusive ownership while an
  ordinary Stop must still drain Google's final recognition response.
- Preserved the existing shared final-transcript reducer for cloud and typed input
  because emulator/later-phase testing must exercise the same committed-text path;
  the DEBUG cloud smoke results remain deliberately isolated.
- Made no changes to `CloudSttClient`, `SttClient`, `SttResult`, `GcpSttConfig`,
  credential plumbing, or any speech/config/bridge/decision/pipeline/TTS/
  Accessibility implementation.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`:
  all 31 unit tests passed (including 14 ViewModel tests), debug/release APKs
  assembled, and Android lint completed without a blocking issue.
- Live Google credential acceptance, Taiwan-Mandarin interim/final transcripts,
  network/quota behavior, and physical microphone release could not be verified in
  repository tests; they require one approved live credential and a real mic device.

## [2026-07-16] — M1.1 Phase 5 amendment: API-key authentication

### Added

- Added provider-neutral `config/ApiKeyProvider.kt` and
  `config/BuildConfigApiKeyProvider.kt` so CloudSttClient can inspect an optional
  plain API key without exposing BuildConfig or gRPC types to UI/contracts.
- Added debug-only `GCP_STT_API_KEY` documentation to
  `local.properties.example`, including Speech-to-Text API restriction, optional
  Android package/signing SHA-1 restriction, and service-account JSON prohibition.
- Added deterministic provider tests plus in-process gRPC metadata tests proving
  API-key-only, bearer-only, API-key-precedence, and neither-configured behavior.

### Changed

- Changed `app/build.gradle.kts` to read `GCP_STT_API_KEY` from gitignored
  `local.properties`, inject it only for debug builds, and force the default/release
  BuildConfig value to an empty string.
- Changed `speech/CloudSttClient.kt` to select credentials at stream start. A
  non-blank API key sends only `x-goog-api-key`; otherwise the existing OAuth path
  sends only `authorization: Bearer` plus optional `x-goog-user-project`.
- Generalized recoverable missing/rejected-credential messages so they do not imply
  one authentication mechanism and never include credential/provider detail.
- Updated `docs/demos/M1.1.md` with API-key setup, precedence, restriction, release,
  deterministic-test, failure-recovery, and live smoke-test instructions.

### Notes

- Added API-key authentication alongside bearer authentication because the company
  supplies a plain key for this development path; the SttClient/SttResult contracts,
  production Start wiring, reducer, UI, and other modules remain unchanged.
- Chose API-key precedence so exactly one credential is deterministic when both
  local values are accidentally present. The bearer provider is not invoked and
  `authorization`/`x-goog-user-project` are omitted in API-key mode.
- Attached `x-goog-api-key` through the existing per-RPC CallCredentials boundary
  because all metadata stays inside `speech/` and the reusable production channel
  remains TLS-protected.
- Kept ApiKeyProvider suspend and provider-neutral so a future approved secret source
  can retrieve it cancellably without changing CloudSttClient or the UI boundary.
- Treated the API key as a long-lived secret: it is debug-only, never logged or
  included in errors, must be restricted to Speech-to-Text and preferably the app
  identity, and must never be committed or reused as service-account material.
- Confirmed no service-account JSON exists in the repository/workspace and current
  `local.properties` contains only `sdk.dir`; no real API key was inspected or added.
- Kept `.gitignore` unchanged with `local.properties` and all JSON files excluded.
- The Phase 4 **Check cloud configuration** action remains an OAuth-token presence
  check; API-key acceptance is verified through the Phase 5 DEBUG cloud smoke test.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`;
  all 26 unit tests passed, debug/release APKs assembled, and lint reported no
  blocking issue. Six CloudSttClient tests include the three required credential
  cases plus existing request/mapping/error/cancellation coverage.
- Confirmed generated release BuildConfig contains an empty `GCP_STT_API_KEY`, and
  a repository/workspace credential scan found no API key, OAuth token, private key,
  credential JSON, or credential logging statement.

## [2026-07-16] — M1.1 Phase 5: Google Cloud streaming STT client

### Added

- Added `speech/CloudSttClient.kt`, implementing the unchanged provider-neutral
  `SttClient` contract with Google Cloud Speech-to-Text V1 bidirectional gRPC.
  The client reuses one TLS OkHttp channel to `speech.googleapis.com:443`, sends
  configuration first, then sends the existing MicRecorder PCM Flow.
- Added `speech/CloudSttFailure.kt` with fixed, non-secret recoverable reasons and
  messages for missing configuration, invalid/expired authentication, permission,
  quota, connectivity, timeout, and unknown cloud failures.
- Added a DEBUG-only **Cloud STT test** to the M1.1 screen. It reuses the existing
  permission flow and MicRecorder, displays/logs raw interim and final results,
  and stores them in smoke-only state without touching production transcripts.
- Added `speech/CloudSttClientTest.kt` with an in-process fake Google Speech gRPC
  service covering configuration-first ordering, PCM request order, exact V1
  recognition settings, interim/final mapping, final-only confidence, all required
  status mappings, missing-token gating, and collector-to-RPC cancellation.
- Added `speech/FakeSttClient.kt` under test sources and a ViewModel test proving
  smoke-test results never enter the production transcript reducer.
- Added reproducible no-token and live-device Phase 5 smoke-test procedures to
  `docs/demos/M1.1.md`.

### Changed

- Added the gRPC 1.76.0 BOM, `grpc-okhttp`, Google Speech V1 gRPC/proto stub 4.74.0,
  protobuf-java 3.25.8, and test-only `grpc-inprocess` dependencies to
  `app/build.gradle.kts`; no Netty transport or ADC/service-account credential
  library was added.
- Excluded duplicate `META-INF/INDEX.LIST` dependency metadata during Android
  packaging so both debug and release APK assembly succeed.
- Changed `ui/MainActivity.kt` to own one Phase 5 CloudSttClient and close its
  reusable channel with ViewModel disposal while retaining the Phase 4 provider.
- Extended `ui/SttUiState.kt`, `ui/SttViewModel.kt`, `ui/SttDebugScreen.kt`, and
  `strings.xml` only for the isolated DEBUG cloud verification path. Production
  Start/Stop still collects microphone audio without cloud recognition; Phase 6
  remains responsible for production wiring and transcript reduction.

### Notes

- Confirmed the post-restructure module layout and the approved Phase 4 signatures
  for AccessTokenProvider, GcpSttConfig, SttClient, SttResult, and MicRecorder before
  coding; the structure had not materially diverged from `docs/ARCHITECTURE.md`.
- Found no development token or GCP project ID in the current gitignored
  `local.properties`; deterministic in-process tests therefore prove the client
  without blocking on external credentials.
- Assumed the human-owned GCP project has Speech-to-Text enabled with active
  billing/quota, that development uses `gcloud auth print-access-token`, production
  uses an approved token broker, and the target device can reach Google.
- Chose the Google V1 generated stub 4.74.0 with gRPC 1.76.0 and protobuf 3.25.8
  because those are the versions declared by the published Speech artifact; using
  one gRPC BOM prevents transport/stub version drift.
- Chose `grpc-okhttp` because it is the Android-oriented transport required by the
  milestone; `grpc-netty` and native-heavy server transports are absent from the
  runtime graph. The in-process transport is test-only and performs no network I/O.
- Kept authentication as per-call `CallCredentials`: `authorization: Bearer` is
  attached only in memory, and non-blank project IDs add `x-goog-user-project` for
  quota attribution. Neither metadata nor token is logged or included in failures.
- Sent V1 `LINEAR16`, 16,000 Hz, mono, `cmn-Hant-TW`, `latest_short`, one alternative,
  and interim-results configuration as the first and only config request, matching
  GcpSttConfig and the MicRecorder byte format without transcoding.
- Bounded outbound scheduling to four approximately 100 ms chunks (about 400 ms)
  and gated each send on gRPC readiness so a slow uplink cannot grow memory without
  bound. The result bridge is separately bounded to 16 recognition values.
- Half-closed the request stream when microphone input completes and waits up to
  three seconds for the server's final response. The wait is response-driven, not
  a fixed sleep; timeout cancels the call and becomes a recoverable failure.
- Set each RPC deadline to 305 seconds to make Google streaming recognition's
  approximately five-minute per-stream limit explicit. Stream rollover is later
  work and is not required for short push-to-talk commands.
- Mapped confidence only for final results; interim confidence is always `null`
  because Google does not populate confidence for interim recognition.
- Kept every gRPC/protobuf/Google type inside CloudSttClient in production code;
  ViewModel, UI state, and the unchanged SttClient/SttResult contracts remain
  provider-neutral.
- Kept production Start unchanged by design. The DEBUG smoke path uses dedicated
  state and never invokes `onSttResult`, so Phase 6 can wire the production reducer
  without inheriting verification-only behavior.
- Release shrinking remains disabled, so no R8 keep rules were added. Release
  assembly proves the dependency set packages successfully; keep rules will be
  added only if shrinking is enabled later.
- Verified `./gradlew testDebugUnitTest`: all 22 unit tests passed, including four
  in-process CloudSttClient tests and the smoke-state isolation test.
- Verified `./gradlew assembleDebug lintDebug assembleRelease`: debug and release
  APKs assembled and Android lint completed without a blocking issue.
- Confirmed the resolved runtime graph contains grpc-okhttp and no grpc-netty,
  Google auth, or service-account credential library.
- Live Google acceptance, TLS/DNS/device connectivity, quota-project attribution,
  real microphone streaming, and actual Taiwan-Mandarin transcripts could not be
  verified in-repository. They require a fresh live token, enabled/billed GCP
  project, network, and preferably a physical microphone device.
- No emulator or physical device was attached to ADB for the final Phase 5 audit,
  so the final APK launch and DEBUG smoke-control interaction remain manual checks.

## [2026-07-15] — M1.1 Phase 4: Google Cloud authentication plumbing

### Added

- Added provider-neutral `config/AccessTokenProvider.kt` with a suspend token
  contract that contains no Google, gRPC, protobuf, or Android type.
- Added `config/BuildConfigAccessTokenProvider.kt` for debug development tokens
  and the typed `CloudSpeechNotConfiguredException` with a fixed non-secret,
  recoverable message.
- Added `config/GcpSttConfig.kt` with the Phase 5 Google Cloud Speech-to-Text V1
  host, `cmn-Hant-TW` language, `latest_short` model, `LINEAR16` encoding, 16 kHz
  mono metadata, interim-results flag, and optional quota project ID.
- Added committed `local.properties.example` documenting `GCP_STT_PROJECT_ID`,
  `GCP_STT_ACCESS_TOKEN`, `gcloud auth print-access-token`, short token lifetime,
  API/billing prerequisites, and the production token-broker requirement.
- Added a local-only cloud configuration status and **Check cloud configuration**
  action to the STT debug screen. It checks token presence but performs no network
  or recognition request and never displays the token.
- Added `config/FakeAccessTokenProvider.kt` under test sources for Phase 4 and
  future CloudSttClient tests.
- Added provider tests for configured and blank values, ViewModel coverage for
  typed not-configured recovery plus permission-independent typed input, and GCP
  constant/audio-format tests.
- Added reproducible Phase 4 configured/not-configured and release-safety steps to
  `docs/demos/M1.1.md`.

### Changed

- Changed `app/build.gradle.kts` to read the optional access token and project ID
  from gitignored root `local.properties`, escape them for generated source, and
  inject them only into the debug BuildConfig variant.
- Defined empty default/release BuildConfig values so release builds never embed
  the developer token or inherit machine-local cloud configuration.
- Changed `ui/SttViewModel.kt` and its Factory to inject `AccessTokenProvider`,
  run the suspend local configuration check in `viewModelScope`, and map missing or
  unexpected provider failures to fixed recoverable UI state without logging them.
- Extended `ui/SttUiState.kt` with explicit NotChecked, Configured, and
  NotConfigured states plus an in-progress flag. Configured means locally present,
  not accepted by Google.
- Changed `ui/MainActivity.kt`, `ui/SttDebugScreen.kt`, and `strings.xml` to wire
  and display the local authentication check while preserving microphone capture,
  debug playback, and typed-input behavior.

### Notes

- Confirmed Google Cloud Platform / Google Cloud Speech-to-Text as the provider;
  Phase 4 adds authentication plumbing only and intentionally adds no CloudSttClient,
  gRPC, protobuf, streaming, recognition, or network dependency.
- Assumed a human-selected GCP project has the Speech-to-Text API enabled and
  active billing/quota; repository configuration cannot verify this yet.
- Assumed development tokens are obtained with `gcloud auth print-access-token`
  and production will use an organization-approved token broker; the production
  broker design and approval remain human-owned prerequisites.
- Assumed the test device can reach `speech.googleapis.com`; Phase 4 makes no
  network call, so connectivity remains unverified until Phase 5.
- Found no GCP project ID or token in the current machine's `local.properties`;
  the no-token path is therefore the local build default and does not block work.
- Kept `AccessTokenProvider.currentToken()` suspend so a future organization broker
  can fetch and refresh on demand with structured coroutine cancellation.
- Chose a typed missing-configuration exception rather than a generic exception so
  UI recovery can be deterministic without inspecting provider message text.
- Used a fixed exception/UI message and discarded successful check results so no
  bearer token, Authorization header, or provider failure text reaches logs or UI.
- Trimmed the injected development value at the provider boundary to reject blank
  configuration and tolerate accidental surrounding whitespace.
- Treated the development OAuth token as an approximately one-hour credential; it
  is never persisted or refreshed by the app in Phase 4 and requires rebuilding
  the debug APK after local replacement.
- Scoped both local properties to debug BuildConfig and forced empty release values
  because a developer bearer token must never be packaged for production.
- Kept the optional project ID non-secret and nullable; Phase 5 may use it for the
  `x-goog-user-project` quota header without forcing configuration in Phase 4.
- Selected Google Cloud Speech-to-Text V1 with `latest_short` because M1.1 targets
  short Mandarin commands and the decision was already binding for Phase 5.
- Reused `AudioConfig` constants for 16 kHz mono metadata so the cloud request cannot
  silently drift from captured PCM format.
- Preserved `.gitignore` coverage for `local.properties`, all JSON files,
  service-account JSON names, keystores, and build outputs; no ignore rule was
  weakened.
- Retained **「IVI AI 助理」** as the approved user-facing name and left the
  teammate-authored `README.md` unchanged.
- Verified `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`;
  all 17 unit tests passed, both APK variants assembled, and debug lint reported
  no blocking issue.
- Confirmed generated debug and release BuildConfig fields are empty on the
  current no-token machine. In particular, release remains empty independently
  of debug local configuration by build-type definition.
- Confirmed `local.properties` remains ignored while `local.properties.example`
  is commit-eligible, and a repository scan found no credential/private-key
  pattern or tracked JSON/keystore file.
- Installed and cold-launched the no-token debug APK on the API 29
  `SeeAndSayApi29` phone AVD. The UI showed **IVI AI 助理** and **Not checked**;
  checking mapped to `Error` / **Not configured** with the fixed message and
  visible Retry/typed-input controls, and Retry restored `Idle` without a crash.
- Tested the configured-value provider branch with an in-memory non-secret unit
  value only. A real token/project/quota was unavailable, so token acceptance,
  expiry, Google authentication, quota attribution, and connectivity remain
  intentionally unverified until Phase 5.

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
