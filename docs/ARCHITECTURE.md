# ARCHITECTURE.md —「IVI AI 助理」AAOS Voice Control App

> **Audience:** every human and AI coding agent working on this repo.
> **How to use this doc:** read it before writing any code. If a task conflicts with a rule here, the rule wins — stop and flag it instead of improvising. Section 12 is the list of hard guardrails; Section 11 tells you which module your milestone lives in.
> **Siblings:** [PROJECT.md](PROJECT.md) — vision & why · [PLAN.md](PLAN.md) — schedule & ownership (中文). This doc is the source of truth for interfaces, data contracts, and code structure.

---

## 1. What we are building (one paragraph)

A single native Android app for **Android Automotive OS (AAOS)** that lets a driver operate the IVI screen by voice. The app listens to the user's intent (cloud STT), **reads the current screen** through an Android **AccessibilityService** (the "eye"), decides which on-screen element satisfies the intent (text matching by default, LM optional), **performs the tap/typing itself** (the "hand"), re-reads the screen to confirm the state changed, and speaks the result back (cloud TTS). Multi-step tasks are the same loop run repeatedly — every step depends only on the screen _as it is right now_.

## 2. Non-negotiable constraints (from the project kickoff)

1. **Decision logic lives in the app, not the cloud.** STT / LM / TTS are three _independent_ cloud services chained by the app.
2. **LM is optional.** Simple commands must resolve by plain text matching. The LM sits behind a feature flag and must be removable with one config change.
3. **The Accessibility Service is the only operation mechanism.** No per-app HTTP APIs, no intents-to-specific-apps shortcuts for the core loop (deep-links may exist only as explicitly-labeled fallbacks).
4. **Every action is followed by a read-back verification.** No fire-and-forget clicks.
5. **Week 1 code must not touch Accessibility. Week 2 code must not touch voice.** Integration happens only in Week 3.
6. **Cut order when behind schedule:** FoxMap/Target B first, then LM, then UI polish. The mainline **M1.3 → M2.3 → M3.2** is never cut.

## 3. High-level component map

```
app/
└── src/main/kotlin/com/foxconn/seeandsay/
    ├── pipeline/          # The brain: state machine + task runner
    │   ├── VoicePipeline.kt        # single-utterance loop (states below)
    │   ├── TaskRunner.kt           # multi-step orchestration (M3.2)
    │   └── PipelineState.kt        # sealed class of states
    ├── speech/            # Week 1 — voice I/O (NO accessibility imports allowed)
    │   ├── SttClient.kt            # interface
    │   ├── CloudSttClient.kt       # gRPC streaming (Google Cloud STT)
    │   ├── TtsClient.kt            # interface
    │   ├── CloudTtsClient.kt       # Google Cloud TTS
    │   ├── DeviceTtsClient.kt      # on-device fallback (android.speech.tts)
    │   └── MicRecorder.kt          # AudioRecord, 16 kHz mono PCM
    ├── bridge/            # Week 2 — the eye & hand (NO speech imports allowed)
    │   ├── UiBridge.kt             # interface: readScreen / click / setText / back
    │   ├── AccessibilityBridge.kt  # real impl backed by the service
    │   ├── SeeAndSayService.kt     # AccessibilityService subclass
    │   ├── SnapshotBuilder.kt      # UI tree → ScreenSnapshot
    │   └── model/ScreenSnapshot.kt # data classes + kotlinx.serialization
    ├── decision/          # Week 3 — intent → action
    │   ├── DecisionEngine.kt       # interface
    │   ├── TextMatcher.kt          # tier 1, always on
    │   └── LmPlanner.kt            # tier 2, behind FeatureFlags.LM_ENABLED
    ├── config/
    │   ├── FeatureFlags.kt         # LM_ENABLED, CLOUD_TTS_ENABLED, TARGET_B_ENABLED
    │   └── Secrets.kt              # reads from BuildConfig, never hardcode keys
    └── ui/
        ├── MainActivity.kt         # status + transcript log
        └── DebugScreen.kt          # live element list, pipeline state (M2.1)
```

**Dependency direction:** `ui → pipeline → {speech, bridge, decision}`. `speech`, `bridge`, and `decision` never import each other. Only `pipeline` composes them. This is what makes rule 5 in Section 2 enforceable.

## 4. Core interfaces (the contracts agents code against)

These four signatures are the **Bridge**. Do not add primitives without updating this doc first.

```kotlin
interface UiBridge {
    suspend fun readScreen(): ScreenSnapshot          // ui_read_screen
    suspend fun click(elementIndex: Int): Boolean     // ui_click
    suspend fun setText(elementIndex: Int, text: String): Boolean  // ui_set_text
    suspend fun back(): Boolean                       // ui_back
}

interface SttClient {
    fun stream(audio: Flow<ByteArray>): Flow<SttResult>  // partial + final transcripts
}

interface TtsClient {
    suspend fun speak(text: String)                   // suspends until playback done
}

interface DecisionEngine {
    suspend fun decide(transcript: String, screen: ScreenSnapshot): Decision
}

sealed class Decision {
    data class Click(val index: Int) : Decision()
    data class SetText(val index: Int, val text: String) : Decision()
    object Back : Decision()
    data class Speak(val text: String) : Decision()   // answer without acting
    object NoMatch : Decision()
}
```

## 5. Data contracts

### 5.1 ScreenSnapshot (the "所見")

```json
{
  "screen": "home",
  "capturedAt": 1789000000000,
  "elements": [
    {
      "i": 0,
      "text": "設定",
      "clickable": true,
      "editable": false,
      "bounds": [0, 120, 240, 200]
    },
    {
      "i": 1,
      "text": "音樂",
      "clickable": true,
      "editable": false,
      "bounds": [0, 220, 240, 300]
    }
  ]
}
```

Rules for `SnapshotBuilder`:

- Include only nodes that are **visible** AND (**clickable** OR **editable** OR text-bearing).
- `text` = node text, falling back to `contentDescription`, falling back to a labeled child's text.
- If a text-bearing node is not clickable itself, **climb to the nearest clickable ancestor** and attach that ancestor's node reference to the element — this is the node `click()` acts on.
- Cap the list (~40 elements) and strip whitespace — the snapshot must stay small enough to embed in an LM prompt.
- Keep a parallel in-memory `elementIndex → AccessibilityNodeInfo` map for the _current_ snapshot only; it is invalidated the moment a new snapshot is taken.

### 5.2 LM contract (only when `LM_ENABLED`)

Request: `{ "transcript": "...", "screen": <ScreenSnapshot> }`
Response (strict JSON, reject anything else): `{ "action": "click|set_text|back|speak|no_match", "index": 0, "text": "..." }`
On malformed output: retry once, then fall back to `TextMatcher`.

## 6. The state machine (single utterance)

```
Idle → Listening → Transcribing → Reading → Deciding → Acting → Verifying → Speaking → Idle
                                                          │
                                                          └─ (failure at any step) → Recovering → Speaking(error) → Idle
```

Rules:

- Implemented as a `sealed class PipelineState`, driven by one coroutine in `VoicePipeline`. **No component may bypass the state machine to call another component directly.**
- `Verifying` = take a **fresh** snapshot after the action and check the expected change (target screen title present, previous element gone, or edit field now containing the text). A click without a verified change is a **failed** step.
- `Speaking` must release the mic first (see Section 8, echo problem).

**Multi-step (M3.2):** `TaskRunner` holds a goal (e.g., the remaining intent「字型調大」), runs the single-utterance machine per step, and after each `Verifying` decides: goal reached → done; progress made → loop again with the new screen; no progress twice in a row → abort and speak a failure message. Hard cap: **6 steps per task**.

## 7. Concurrency & threading rules

- Everything is **Kotlin coroutines + Flow**. No raw threads, no callbacks leaking out of the `speech` package (wrap gRPC callbacks into Flows at the boundary).
- `AccessibilityNodeInfo` access happens **only** inside `bridge/` on the service's thread context; snapshots handed out are pure immutable data.
- One pipeline run at a time: starting a new utterance cancels the previous coroutine scope cleanly (structured concurrency; every client must be cancellation-safe).

## 8. Error handling & known sharp edges (agents: read before touching the relevant module)

| Sharp edge                                                             | Rule                                                                                                                                                           |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `getRootInActiveWindow()` returns null or a stale tree                 | Retry with backoff (3× / 150 ms). Prefer waiting on `TYPE_WINDOW_CONTENT_CHANGED` events (debounced ~300 ms) after an action instead of sleeping a fixed time. |
| Assistant hears its own TTS                                            | Never record while speaking. `Speaking` state must request audio focus and stop `MicRecorder` first. No open-mic barge-in in v1.                               |
| Duplicate labels (two「設定」on screen)                                | `TextMatcher` must detect ambiguity and return `Speak("有兩個『設定』…")` asking the user, not click index 0 blindly.                                          |
| Target exists but is off-screen                                        | Out of scope for v1 mainline. If needed for Target A, add `ACTION_SCROLL_FORWARD` as an explicit, doc-updated 5th primitive — do not hack it into `click`.     |
| STT/Chinese text mismatch (全形/半形, punctuation, 「設定」vs「设置」) | All matching goes through one `normalize()` (NFKC, strip punctuation/whitespace, lowercase Latin). Never compare raw strings.                                  |
| Cloud unreachable in the car                                           | STT failure → speak a canned offline message. TTS failure → fall back to `DeviceTtsClient`. The app must never hang silently.                                  |
| Emulator has no working mic                                            | Debug UI must always allow **typed input injected at the `Transcribing` output** — this is also how Week 2 is tested per the plan.                             |

## 9. Configuration & secrets

- Cloud credentials via `local.properties` → `BuildConfig`. **Never commit keys.** `.gitignore` covers `local.properties` and `*.json` service-account files.
- `FeatureFlags` is the only place behavior toggles live: `LM_ENABLED` (default **false**), `CLOUD_TTS_ENABLED` (default true, false = on-device TTS), `TARGET_B_ENABLED` (default false).
- Locale: primary zh-TW for STT/TTS and matching; keep locale a single constant.

## 10. Testing strategy

- `pipeline/` and `decision/` are pure Kotlin — unit test them against a **FakeUiBridge** (scripted snapshots) and **FakeSttClient**. The multi-step logic (M3.2) must have unit tests for: success, no-progress abort, ambiguity, step cap.
- `bridge/` is verified on-device/emulator via the Debug screen (M2.1 acceptance) — instrument tests optional, manual demo script required.
- Every milestone has a written demo script in `docs/demos/` (exact utterances, expected screens) so the live 驗收 is reproducible.

## 11. Milestone → module map

| Milestone                | Touches                                                        | Definition of done                      |
| ------------------------ | -------------------------------------------------------------- | --------------------------------------- |
| M1.1 STT                 | `speech/MicRecorder`, `CloudSttClient`, `ui/`                  | Spoken words appear as text on screen   |
| M1.2 TTS                 | `speech/CloudTtsClient` (+ device fallback)                    | Arbitrary text spoken aloud             |
| M1.3 Voice loop          | `pipeline/VoicePipeline` (speech states only)                  | 「你好」→ reply spoken, no manual steps |
| M2.1 Read screen         | `bridge/SeeAndSayService`, `SnapshotBuilder`, `ui/DebugScreen` | Live element list of any foreground app |
| M2.2 Operate             | `bridge/AccessibilityBridge`, `decision/TextMatcher`           | Typed「設定」opens Settings             |
| M2.3 Closed loop         | `pipeline` Verifying state                                     | Every click auto-confirmed by re-read   |
| M3.1 Single-step voice   | `pipeline` full machine                                        | Voice「打開設定」end-to-end             |
| M3.2 Multi-step          | `pipeline/TaskRunner`                                          | 「打開設定→顯示→字型調大」chain works   |
| M3.3 Target B (optional) | kitt-map repo (`contentDescription` annotation)                | FoxMap elements appear in snapshots     |

## 12. Agent working agreements (hard guardrails)

1. **Stay in your module.** A Week 1 task never adds code to `bridge/`; a Week 2 task never adds code to `speech/`. Cross-module wiring happens only in `pipeline/` and only for Week 3 tasks.
2. **Never widen the Bridge silently.** New primitive ⇒ update Section 4 + Section 5 of this doc in the same PR.
3. **Every action code path ends in a verification.** PRs adding an action without a read-back check get rejected.
4. **LM code must compile out cleanly.** Nothing outside `decision/LmPlanner` may reference the LM.
5. **No hardcoded coordinates, resource IDs of other apps, or sleep-based waits** (event-driven waits per Section 8).
6. **Small PRs mapped to a milestone ID** (e.g., `M2.1: snapshot builder`). If a change doesn't serve a milestone, it doesn't ship this month.
7. **When blocked or when reality contradicts this doc, stop and report** — do not invent a workaround that violates Section 2.
