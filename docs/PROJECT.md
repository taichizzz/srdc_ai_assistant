# 所見即可說 — AI In-Vehicle Voice Control System (AAOS)

> **This doc = the "what & why":** vision, background, roadmap, tech stack, deliverables. Start here.
> **Siblings:** [ARCHITECTURE.md](ARCHITECTURE.md) — how it's built (read before coding) · [PLAN.md](PLAN.md) — schedule & ownership (中文)
> **Platform:** Android Automotive OS (AAOS) native app · **Duration:** 3 weeks, 3 phases, live demo acceptance at each phase

---

## 1. Vision — 打造一位坐在副駕的無形助理

Build an invisible copilot: an assistant that **sits in the passenger seat, watches your screen, and does the tapping for you**. The driver never has to remember "that setting is on page 3" — they just say what they want, and the assistant looks at the current IVI screen, figures out what can be done, and taps the right element on their behalf.

Three core abilities:

| Ability | Meaning |
|---|---|
| **聽 (Listen)** | The user speaks their *intent* — no memorizing menu paths or fixed command phrases. |
| **看 (See — 所見)** | The assistant reads the *current* IVI screen by itself and understands what actions are available right now. |
| **動手 (Act — 可說)** | The assistant replaces the user's finger — it precisely clicks the target element on screen. |

## 2. Background — from「盲操作」to true「所見即可說」

The previous-generation approach (**FoxMap**) worked, but it was blind:

| Dimension | FoxMap (past) | This project (now) |
|---|---|---|
| **Visual perception** | None — blind operation | Reads the current screen (所見) *first*, then decides what to do |
| **Operation target** | Hardcoded, app-specific HTTP APIs | *Any* UI element on *any* screen — system-universal |
| **Client carrier** | User's web browser ("browser as a bridge") | A native Android app running directly on AAOS |

**The breakthrough:** abandon the "write a dedicated API for every app" approach. Instead, use the **Android Accessibility Service (無障礙服務)** as a *universal operation bridge* — one mechanism that can read and operate any app's UI.

## 3. System Architecture — 決策在端上，雲端為輔助

Decision-making lives **on-device**; the cloud is only an assistant.

```
                    ┌─────────────────────────────┐
                    │   核心: Android App (AAOS)   │
        ┌──────────►│   Main logic / pipeline      │◄─────────┐
        │           │   coordinator                │          │
        │           │   採集語音 → 讀畫面 → 決策 → 執行 │          │
        │           └──────────┬──────────────────┘          │
        │                      │                              │
        ▼                      ▼                              ▼
┌───────────────┐   ┌─────────────────────────┐   ┌──────────────────┐
│  Cloud: STT   │   │  眼與手                  │   │  Cloud: TTS      │
│  Cloud: LM    │   │  Android Accessibility   │   │                  │
│  (optional)   │   │  Service (same APK)      │   │                  │
└───────────────┘   │  reads UI tree (eye)     │   └──────────────────┘
                    │  performs clicks (hand)  │
                    └─────────────────────────┘
```

Key architectural decisions (from the kickoff deck — treat these as constraints):

1. **The Android app is the brain.** It coordinates the whole pipeline: capture voice → read screen → decide → execute. Decision logic lives in the app, **not** in the cloud.
2. **STT / LM / TTS are three independent cloud services**, chained by the app itself. There is no monolithic cloud agent.
3. **LM is optional and lazy.** Only complex intents get sent to the language model; simple commands are resolved by plain **text matching** against on-screen elements.
4. **Eyes and hands = Accessibility Service** running inside the *same* app: it reads the UI tree (eye) and performs clicks on the user's behalf (hand).

## 4. The Core Loop — how「打開設定」actually flows

Every command runs through the same six-step closed loop:

1. **使用者指令** — user says「打開設定」→ audio is sent to cloud STT and comes back as text.
2. **讀當前畫面 (所見)** — the Accessibility Service calls `getRootInActiveWindow()` and serializes the screen into a compact snapshot:
   ```json
   {
     "screen": "home",
     "elements": [
       { "i": 0, "text": "設定" },
       { "i": 1, "text": "音樂" }
     ]
   }
   ```
3. **App 決策 (LM optional)** — text-match「設定」against the element list → decide to click index 0. Complex/ambiguous intents get escalated to the LM with the snapshot as context.
4. **無障礙執行 (可說)** — trigger `performAction(ACTION_CLICK)` on the target node.
5. **回讀確認** — after the click, the app **re-reads the screen** to verify the state actually changed (we are now on the Settings page).
6. **語音回饋** — TTS announces「已進入設定」.

**Multi-step tasks are just this loop run repeatedly** — e.g.「打開設定」→「顯示」→「字型調大」is three consecutive loops, and every step depends only on *the screen as it is right now*. That is what makes the system robust: no hardcoded navigation paths.

## 5. The Bridge Interface

Week 2 defines a minimal, stable contract between "the brain" and "the hands and eyes." Keep it to four primitives:

| Primitive | Purpose |
|---|---|
| `ui_read_screen` | Return the compact element snapshot of the current screen |
| `ui_click` | Click an element (by index / node reference) |
| `ui_set_text` | Type text into an editable field |
| `ui_back` | Global back navigation |

Everything the assistant does — no matter how complex the task — must be expressible as a sequence of these four calls plus read-backs. Resist adding primitives until a real milestone forces it.

> **Source of truth:** exact Kotlin signatures and the `ScreenSnapshot` data contract live in [ARCHITECTURE.md §4–5](ARCHITECTURE.md#4-core-interfaces-the-contracts-agents-code-against). This table is a summary — do not add primitives here without updating that doc first.

## 6. Roadmap — 三週三階段

Each week ends with a **現場 Demo 驗收** (live demo acceptance). Milestones are the acceptance criteria, not suggestions.

> This section describes **what each milestone proves**. For **dates & who owns what** see [PLAN.md](PLAN.md); for **which code module each milestone touches** see [ARCHITECTURE.md §11](ARCHITECTURE.md#11-milestone--module-map).

### Week 1 · 語音大腦 (App 雛形)

Build the Android app skeleton and chain the cloud services. **Warning from the deck: do NOT touch Accessibility this week.**

- **M1.1 STT 串接** — record mic audio (`AudioRecord`) → send to cloud STT → recognized text correctly displayed on screen.
- **M1.2 TTS 串接** — send a text string → cloud TTS → speaker successfully reads it out loud.
- **M1.3 語音互動 Loop** — say「你好」→ app understands → generates a reply (LM *or* preset rules) → speaks it back, fully automatic inside the app.

> Escape hatch: if the AAOS emulator is being difficult, develop and test on a regular phone/tablet first — the code is the same.

### Week 2 · 虛擬手眼 (Accessibility Bridge)

Extend the Week 1 app so it can *see* and *act*. **Test with buttons/typed commands this week — don't rush to wire up voice yet.**

- **M2.1 讀畫面** — with the service enabled, extract a concise element list of the current screen in real time (shown on a debug page or in logs).
- **M2.2 操作畫面** — type the command「設定」→ the app precisely opens the Settings page on the user's behalf.
- **M2.3 操作閉環** — after every click, the app automatically re-reads the screen and confirms the state changed.

### Week 3 · 最終整合實戰 (Boss 戰)

Connect the Week 1 voice pipeline to the Week 2 Bridge.

- **Integration base:** **M3.1** single-step execution → **M3.2** continuous multi-step navigation.
- **Target A (標準難度) — Car Settings:** voice-drive「打開設定」→「顯示」→「字型調大」. Standard Android UI natively supports Accessibility, so this is the primary acceptance target.
- **Target B (進階挑戰) — FoxMap 改造:** the map is mostly custom-drawn UI, invisible to Accessibility by default. The challenge is to go back into `kitt-map` and add semantic annotations (`contentDescription` etc.) so FoxMap becomes "visible and operable."

> **主管提醒 (project-critical):** if the schedule gets tight, decisively drop Target B *and* the LM. Defend the core mainline at all costs: **M1.3 → M2.3 → M3.2**.

## 7. Suggested Tech Stack

Everything below is a suggestion consistent with the deck's constraints — swap pieces freely as long as the milestones survive.

### Platform & language
- **Kotlin** — the only sane choice for AAOS; the key reference repo (`droidrun/mobilerun-portal`) is 97% Kotlin, so staying in Kotlin lets you lift patterns directly.
- **Target: Android Automotive OS emulator** (Android Studio → Automotive system image, API 33/34). Fall back to a phone/tablet AVD when the AAOS emulator misbehaves (deck explicitly allows this for Week 1).
- **Min SDK 29+** — keeps `AccessibilityNodeInfo` APIs modern without cutting off the emulator images you'll actually use.

### App architecture
- **Single APK, two logical halves:** a foreground app (UI + pipeline coordinator) and an `AccessibilityService` declared in the same manifest.
- **MVVM + Kotlin Coroutines/Flow** — the core loop is naturally async (mic stream → network → UI tree → action → read-back), so model it as a small **state machine** (`Idle → Listening → Transcribing → Deciding → Acting → Verifying → Speaking`) driven by a coroutine pipeline. This makes the M2.3/M3.2 "read-back confirm" step a first-class state instead of an afterthought.
- **kotlinx.serialization** for the screen-snapshot JSON.
- **Jetpack Compose** for the debug/status UI (element list viewer, pipeline state, transcript log). Keep the UI minimal — the product is the loop, not the screen.

### Voice I/O (Week 1)
- **STT: Google Cloud Speech-to-Text** via gRPC streaming — the deck points at `android-docs-samples/speech` as the official example; streaming gives you partial transcripts, which feels much better in a car.
- **TTS: Google Cloud Text-to-Speech** for consistency with STT auth/setup. (Pragmatic fallback: Android's on-device `TextToSpeech` engine gets M1.2 done in an hour if cloud setup stalls — swap in cloud TTS after.)
- **Audio capture:** `AudioRecord` at 16 kHz mono PCM, exactly what the STT streaming API expects.
- **gRPC + OkHttp** for transport; keep cloud credentials in a local `local.properties`-injected config, never in the repo.

### The Bridge (Week 2)
- **`AccessibilityService`** with `canRetrieveWindowContent=true`; screen reading via `getRootInActiveWindow()`, walking `AccessibilityNodeInfo` into your compact `{i, text}` snapshot (filter to visible + clickable/text-bearing nodes so the LM prompt stays tiny).
- **Actions:** `performAction(ACTION_CLICK)`, `ACTION_SET_TEXT`, `performGlobalAction(GLOBAL_ACTION_BACK)` — mapping 1:1 onto `ui_click`, `ui_set_text`, `ui_back`.
- Define the Bridge as a plain Kotlin `interface` so the coordinator can be unit-tested against a fake bridge without an emulator.

### Decision layer (Week 3, LM optional)
- **Tier 1 (default): plain text matching** — normalized substring/fuzzy match of the transcript against element `text`. This alone passes Target A.
- **Tier 2 (optional): LM intent parsing** — send `{transcript, screen_snapshot}` to a small hosted model (e.g., Gemini Flash or GPT-4o-mini) and ask for `{action, target_index}` as strict JSON. Because the deck says LM is droppable, keep it behind a feature flag so cutting it is a one-line change.

### References (from the deck — read these first)
| Resource | Why it matters |
|---|---|
| `droidrun/mobilerun-portal` | **Most important.** 97% Kotlin implementation of extracting the UI tree and performing clicks — the perfect reference for the whole Bridge. |
| `android-docs-samples/speech` | Official gRPC streaming STT/TTS examples for Android. |
| Google Accessibility Codelab | The authoritative intro: creating the Service, manifest declaration, `performAction`. |
| `livekit-examples/agent-starter-android` | Backup plan only — client skeleton if a full-cloud agent approach is ever needed. |

## 8. Deliverables — 三週後的交付成果包

1. A runnable **Android APK**
2. The complete **code repo**
3. A live-operation **demo video** showing the seamless「所見即可說」flow
4. A **results presentation** covering system architecture, the pitfalls encountered, and how they were solved

**The single success criterion is「跑通完整閉環」— the complete closed loop works end-to-end.** When progress stalls, cut features without hesitation and defend the core mainline.

## 9. Risk Management & Cut Lines

| Risk | Mitigation |
|---|---|
| AAOS emulator problems | Develop Week 1 on a phone/tablet; the app is identical. |
| Cloud STT/TTS setup drags on | Temporarily use on-device `TextToSpeech` / `SpeechRecognizer` to keep M1.3 moving, swap cloud back in later. |
| Custom-drawn UI invisible to Accessibility (FoxMap) | That's Target B by design — it's the first thing to cut. |
| LM latency / cost / flakiness | LM is optional per the deck; text matching alone must pass Target A. Feature-flag it. |
| Schedule slips in general | Cut in this order: Target B → LM → polish. Never cut: **M1.3 → M2.3 → M3.2**. |

## 10. Open Questions for Mentor

Decisions we need from the mentor before / early in Week 1:

1. **STT / TTS provider** — which cloud vendor should we use, and are keys / quota already available?
2. **AAOS environment** — who provides the test setup, and is it OK to develop on a regular Android phone/tablet first (per the kickoff escape hatch)?
3. **LM** — is it a required feature or confirmed optional? If required, which model / service?
4. **FoxMap / `kitt-map`** — repo access and location for the Target B annotation work?
5. **Demo acceptance** — date and format of the live 驗收 (each Friday? final week only?).

## 11. Glossary

- **AAOS** — Android Automotive OS, the native in-vehicle Android platform (not Android Auto phone projection).
- **IVI** — In-Vehicle Infotainment, the car's central screen system.
- **所見即可說** — "what you see, you can say": the assistant reads the current screen, so anything visible is voice-operable.
- **盲操作** — "blind operation": the old approach of firing hardcoded APIs without seeing the screen.
- **Bridge** — the four-primitive Accessibility interface (`ui_read_screen`, `ui_click`, `ui_set_text`, `ui_back`).
- **閉環 / read-back loop** — after every action, re-read the screen to confirm the state actually changed before proceeding.
