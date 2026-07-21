# Week 2 (Accessibility Bridge) — Build vs. Reuse Decision

**Question addressed:** for M2.1 / M2.2 / M2.3, why do we build our own bridge instead of adopting
TalkBack or droidrun?

**Short answer:** we *do* reuse both — as references, in different dimensions. We do not fork or
depend on either, because each was built for a different consumer, and adapting them costs more
than building to our spec while importing risks we cannot carry into a vehicle.

**Key fact underlying everything below:** TalkBack, droidrun, and our bridge all use the *same*
public Android API (`AccessibilityService`, `getRootInActiveWindow()`, `AccessibilityNodeInfo`).
Neither project has privileged access. So nothing is gained in capability by adopting them — only
in code we would then have to strip, adapt, and maintain.

---

## What each project actually is

| | TalkBack | droidrun |
|---|---|---|
| Purpose | Screen reader for blind users | Automation/agent tool for developers |
| Converts screen into | **Speech** (for a human) | **Structured data** (for software) |
| Who acts | The human, via gestures | An LLM, off-device |
| Where it runs | Entirely on-device, standalone app | Split: Python agent on a laptop over ADB + on-device Kotlin portal |
| Can act on elements | No | Yes |
| Verifies after acting | N/A | No |

One-line difference: **TalkBack narrates the screen for a human to act on; droidrun extracts the
screen for software to act on.**

---

## M2.1 — 讀畫面 (produce an indexed element snapshot)

**What the milestone requires:** convert the live UI tree into a compact, *addressable* list —
`{i, text, clickable, editable, bounds}`, capped ~40 elements — plus an in-memory index → node map
valid for the current snapshot only. The critical property is **addressability**: element `i=0` must
still be `i=0` a moment later so `ui_click(0)` can act on it.

**TalkBack — why not.** It reads the tree, but its output is speech plus *accessibility focus*, which
is a navigation cursor for a person, not a handle a program can act on. There is no snapshot API; it
is a finished application, not a library.

*Risk if we forked it:* inherit ~100k+ lines of screen-reader concerns (braille, gesture navigation,
focus management, narration) that must be stripped; TalkBack speaks every element aloud, which in a
car is actively harmful and collides with our own TTS (echo rule); only one accessibility service can
meaningfully own the interaction, so it would compete with our assistant; Apache-2.0 fork obligations
(attribution, NOTICE, marking modifications) require legal review for a deliverable that ships in a
vehicle; and we would be rebasing onto an actively developed Google app for the project's lifetime.

**droidrun — why not (wholesale).** Its on-device portal genuinely extracts the tree in Kotlin and is
the closest existing fit — our kickoff names it as the reference for exactly this. But its output
format targets an *off-device* consumer reached over ADB, not an in-app decision layer.

*Risk if we adopted it directly:* we import the ADB/agent coupling, which has no meaning in a car;
and we would still rewrite the output into our `ScreenSnapshot` format and apply our filtering rules
(visible AND clickable/editable/text-bearing, climb to nearest clickable ancestor, cap the list) —
i.e. most of the work remains, with an unfamiliar codebase underneath it.

**What we do instead.** `SnapshotBuilder` walks the tree and emits our snapshot format directly. We
study droidrun for *what to extract per node and how to flatten the tree*, and TalkBack for the
hardened edge cases (null/stale root, node recycling, event timing).

---

## M2.2 — 操作畫面 (act on an element by index)

**What the milestone requires:** four primitives — `ui_read_screen`, `ui_click`, `ui_set_text`,
`ui_back` — plus deterministic matching of the user's words to an on-screen element, including
ambiguity detection (two elements labelled 「設定」 must trigger a question, not a blind tap).

**TalkBack — why not.** It cannot do this at all. A screen reader has no concept of "find the element
matching this intent and act on it" — the human performs the action. This entire milestone would be
written from scratch regardless, only inside someone else's codebase.

**droidrun — why not (wholesale).** It *does* perform taps, and its execution patterns are relevant.
But its action *selection* is LLM-driven and off-device.

*Risk if we adopted it directly:* it inverts our first constraint (決策在端上 — decisions on-device);
it introduces a cloud/off-device dependency into the action path; and it has no local validation gate
before acting. Our design requires validating every action locally — is the index valid, is the
element actually clickable, is the snapshot still current — before touching the screen.

**What we do instead.** `AccessibilityBridge` implements the four primitives over
`performAction(...)`; `TextMatcher` performs normalized matching (NFKC, punctuation/whitespace
stripping, Traditional-Chinese handling) with explicit ambiguity detection. We adapt droidrun's
*execution* approach; we replace its *decision* layer entirely.

---

## M2.3 — 操作閉環 (read-back verification)

**What the milestone requires:** after every action, take a **fresh** snapshot and confirm the state
actually changed. An action without a verified change is a **failed** action. Waiting is
event-driven (`TYPE_WINDOW_CONTENT_CHANGED`, debounced) — never a fixed sleep.

**Neither project provides this.** TalkBack does not act, so verification is not applicable. droidrun
lets the agent/LLM judge whether the task succeeded; there is no deterministic local re-read.

*Risk if we relied on either:* silent failures. A tap that did not take effect is reported as
success, and a multi-step task proceeds from a wrong screen state. With a driver at the wheel, an
unverified action is a safety problem, not a cosmetic one.

**What we do instead.** The verification loop is our own, and it is the piece that makes multi-step
tasks (M3.2) reliable: every step depends on the screen *as it actually is now*, confirmed.

---

## Cross-cutting risks of forking either project

1. **Legal/licensing.** Forking Apache-2.0 code creates attribution, NOTICE, and
   modification-marking obligations, and invites a legal review for software shipped in a vehicle.
   Our current position — 100% first-party code, third-party material only as declared Gradle
   dependencies — is cleaner and was obtained at no engineering cost.
2. **Maintenance.** Both upstreams are actively developed. A fork means either freezing at a
   snapshot or continually rebasing our logic onto someone else's roadmap.
3. **Inverted effort.** Deleting ~80% of a large codebase and writing the missing 20% is more work,
   and more risk, than writing a few hundred lines against a specification we already have
   (ARCHITECTURE.md §5.1 defines the snapshot rules precisely).
4. **Architectural contamination.** Their assumptions leak: ADB connectivity, off-device
   orchestration, LLM-decides-and-acts. Each conflicts with a stated project constraint.
5. **Service exclusivity.** Only one accessibility service can meaningfully own the interaction; a
   TalkBack-derived service would compete with our assistant rather than serve it.
6. **Debuggability under a deadline.** In a three-week project, failures inside a large unfamiliar
   codebase are far more expensive than failures in a few hundred lines we wrote.

---

## What we genuinely reuse

```
Our bridge
 ├─ SHAPE       ← droidrun  (tree → indexed elements → act by index)
 ├─ ROBUSTNESS  ← TalkBack  (null/stale root, node lifecycle, event debouncing)
 └─ VERIFICATION ← ours     (re-read and confirm — neither project has this)
```

Reuse happens at the level of *proven approach*, not merged code. That is deliberate: it gives us
their hard-won lessons without their coupling, licensing, or maintenance burden.

---

## Summary for review

| Milestone | TalkBack | droidrun | Ours |
|---|---|---|---|
| M2.1 read screen → addressable snapshot | Reads tree, but output is speech/focus for a human; no API | Closest fit; output targets an off-device consumer over ADB | Emits our snapshot format with stable indices |
| M2.2 act on element | Not supported at all | Acts, but selection is LLM-driven and off-device | Four primitives + deterministic local matching with ambiguity detection |
| M2.3 verify after action | Not applicable | Not provided (LLM judges success) | Fresh-snapshot read-back; unverified action = failed action |

**Conclusion:** adopt the approaches, not the codebases. The bridge is small, our requirements are
specific and non-negotiable, and the verification loop — the part that matters most in a vehicle —
does not exist in either project.
