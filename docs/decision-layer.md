# Decision layer — design (LM-first)

**Status:** current design, confirmed with mentors. Supersedes the earlier fallback-style design.

**Revision history**
1. Original: normalize → hand-written Intent Table → snapshot match.
2. Interim: Intent Table removed; LM used only when snapshot matching found nothing.
3. **Current: LM-first.** The schema-constrained model is the primary interpreter for every
   utterance. Deterministic matching is retained for grounding and as a failure fallback.

---

## The flow

```
transcript + index-free screen description
  → schema-constrained LM            (primary interpreter — every utterance)
  → structured UserGoal
  → local grounding                  (find the element on THIS screen that serves the goal)
  → capability + index validation     (clickable? editable? in range? snapshot fresh?)
  → act via UiBridge
  → re-read snapshot and VERIFY the state changed
  → speak the result
```

**Fallback path** — when the LM is disabled, unreachable, rejected, or returns invalid output:

```
transcript → normalize → deterministic TextMatcher against the snapshot → Decision
```

This keeps direct commands (「打開設定」) working offline and during credential/provider failure. It is
a safety net, not the normal route.

---

## Why LM-first

A hand-written intent table can never cover the phrasing tail:

```
字太小了 · 我看不清楚 · 文字可以放大嗎 · 把字調大一點 · can you increase the font size?
```

All of these must produce the same goal. A schema-constrained model does that natively, so the table
is redundant — and enumerating phrasings by hand is strictly worse. Modern models support
schema-constrained output (Vertex `responseSchema`), which makes the *shape* of the answer guaranteed
and machine-parseable.

---

## The division of responsibility

| Question | Answered by |
|---|---|
| What does the user want? | **The LM** |
| Is that available on this screen? | Local Kotlin |
| Which element represents it? | Local Kotlin |
| Is acting on it safe? | Local Kotlin |
| Did it actually work? | Local Kotlin (verification) |

The model interprets. The app grounds, authorizes, executes, and verifies. This is what keeps an
untrusted model from driving a car's screen.

---

## The LM contract

The model returns a **goal**, never an action, index, or coordinate.

```json
{
  "intent": "adjust_text_size | adjust_volume | adjust_brightness | open_target | go_back | stop | unknown",
  "direction": "increase | decrease | null",
  "target": "string | null",
  "control_query": "string | null",
  "confidence": 0.0,
  "needs_clarification": false,
  "clarification_question": "string | null"
}
```

`target` / `control_query` are **semantic descriptions** (e.g. 「文字大小」), which local code searches
for in the snapshot. They are never indices. Returning a description rather than a coordinate is what
lets goal-specific keyword knowledge move out of local code without giving the model control.

### Validation rules (non-negotiable)

A schema guarantees the *shape* of a response, not its *correctness*:

1. Reject unknown intent names, unknown enum values, missing required fields, extra properties, and
   confidence outside `0.0..1.0`.
2. **Reject outright any response containing an element index, UI function call, or coordinate.**
3. Low confidence or `needs_clarification` → ask the user; never guess.
4. Malformed output → retry once, then fall back to deterministic matching.
5. `UserGoal` carries no index field, so a model-chosen element is structurally impossible — the
   content checks are defence in depth on top of that.

---

## Constraints that still hold

- **Execution stays on-device.** The model advises; the app decides which element, acts, and verifies.
- **Ambiguity becomes a question, not an action.** Several plausible candidates → ask.
- **Every action is verified** by comparing normalized before/after snapshots. An unverified change is
  a failed action.
- **`LM_ENABLED` remains a real switch.** Production default is `true`, but with it off the app must
  still build, initialise no provider, and handle direct commands deterministically.
- No provider-specific type may leak outside the LM client, mirroring the `SttClient` / `TtsClient`
  boundaries.

---

## Accepted trade-offs

LM-first was chosen with these costs understood:

- **Latency**: every command incurs a model round trip, where deterministic matching was
  near-instant. Mitigated by using a Flash-tier model.
- **Connectivity**: without network the assistant falls back to deterministic matching only.
- **Cost**: one inference per utterance.
- **Demo risk**: a credential or quota failure degrades the assistant to the deterministic path, which
  is why that path is retained rather than deleted.

---

## Open items

1. **Live Vertex acceptance is unverified** — project/location/model availability, IAM roles
   (`roles/aiplatform.user`), token validity, and quota have not been confirmed against the real
   endpoint. This is the highest-risk unknown.
2. **Off-screen targets** (「打開 Spotify」from another app): currently returns an honest
   `NotOnThisScreen`. The mechanism to reach them — accessibility HOME + launcher navigation vs. an
   explicitly-labelled Intent launch (§2.3 permits the latter only as a labelled fallback) — remains
   an open decision.
3. **Text-size verification limitation**: the snapshot contract carries element text and capabilities
   but not font scale, so a pure visual size change with identical labels may not be detectable.
