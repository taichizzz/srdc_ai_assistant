# srdc_ai_assistant

**「IVI AI 助理」— an AI voice-control assistant for Android Automotive OS (AAOS).**

A single native Android app that lets a driver operate the in-vehicle infotainment (IVI)
screen by voice. It **listens** to the user's intent (cloud STT), **sees** the current screen
through an Android `AccessibilityService` (reads the live UI tree), **acts** by tapping the
right element on the user's behalf, re-reads the screen to confirm the state changed, and
**speaks** the result back (cloud TTS). Multi-step tasks are the same loop run repeatedly —
every step depends only on the screen _as it is right now_.

The breakthrough vs. the previous FoxMap approach: no per-app hardcoded HTTP APIs. The
Accessibility Service is a **universal operation bridge** that can read and operate any app's UI.

## Status

🚧 Early development — 2026 summer internship project (車用軟體研發處, 3 weeks).
Core mainline to defend at all costs: **M1.3 → M2.3 → M3.2**.

## Documentation

| Doc                                          | What it answers                                                                                                | Language |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------------------- | -------- |
| [docs/PROJECT.md](docs/PROJECT.md)           | **What & why** — vision, background, roadmap, tech stack, deliverables                                         | English  |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | **How it's built** — module layout, interfaces, data contracts, guardrails. **Read this before writing code.** | English  |
| [docs/PLAN.md](docs/PLAN.md)                 | **When & who** — schedule, milestone dates, division of labor                                                  | 中文     |
| [docs/reference/](docs/reference/)           | Original kickoff deck & architecture screenshots                                                               | —        |

## Build & Run

> ⏳ The Android project (`app/`) is not scaffolded yet. Build instructions will land with the
> first Week 1 milestone. Target: AAOS emulator (Android Studio → Automotive system image,
> API 33/34), with a phone/tablet AVD as fallback. Cloud credentials go in `local.properties`
> (never committed — see [.gitignore](.gitignore)).

## Team

| Member      | Owns                                                                           |
| ----------- | ------------------------------------------------------------------------------ |
| **Mark**    | Accessibility Bridge (eye & hand) + Week 3 integration                         |
| **Leo**     | Voice I/O (STT / TTS) → decision / pipeline layer                              |
| **Rebecca** | Product, testing, and delivery (demo video, presentation, semantic annotation) |
