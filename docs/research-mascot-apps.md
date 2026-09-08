# Mascot App Research — comparison vs ShimejiHarness

*Research date: 2025-06 (this pass). Method: GitHub API repo search sorted by stars,
shallow-cloned the most relevant repos to `/tmp/mascot-research/`, read architecture in code
(not just READMEs). Network verified working; docs committed to repo.*

## Survey

| Repo | Platform | Lang / Stack | Stars* | Genre | Renderer | Notes |
|------|----------|--------------|--------|-------|----------|-------|
| **arkpets-lite** (`DYXDQ/arkpets-lite`) | Android | Autojs JS + Spine WebGL in a WebView overlay | 59 | Anime desk pet (Arknights) | Spine skeletal via `spine-webgl.js` | Closest Android comparison. Downloader for character models; floaty raw overlay window |
| **uDesktopMascot** (`MidraLab/uDesktopMascot`) | Windows / macOS | Unity (C#) | 353 | General desktop mascot | 3D VRM/GLB/FBX | Full-featured: voice/BGM folders, click voices, customizable GUI, streaming-asset mods |
| **Desktop Goose / MobileGoose** (`pixelomer/MobileGoose`) | iOS (jailbreak) | ObjC | 83 (mobile port) | Mischief goose | Frame-sprites | Mod system: `handleFrameInState:` per-frame hook; famous throw-the-goose drag |
| **floating-pet** (`huise513/floating-pet`) | Android (Flutter) | Dart + `flutter_overlay_window` | — | Care/tamagotchi pet | Emoji/vector widget + breathing scale animation | Stats: hunger/mood/energy decay on a timer; feed/play/sleep actions |
| **PetDesk** (`x1223/PetDesk`) | Android | Kotlin + NanoHTTPD | — | Agent-mirror pet | Static drawable per state | Local HTTP server; Claude Code POSTs state changes → pet mirrors lifecycle (thinking/building/working/error) |
| *Baseline (already researched)* | | | | | | |
| Shimeji-ee / linux-shimeji | Desktop | Java | 200 (wl_shimeji, Shijima-Qt) | Shimeji lineage | Frame-sprites, XML actions | Weighted-random action FSM; pose table extracted in `research-shimeji.md` |

\* stars as of research date; not a quality metric by itself.

## Deep dives

### arkpets-lite — the closest Android peer
- **Renderer**: WebView containing generated HTML/JS; Spine WebGL canvas renders skeletal
  animations (`.skel` + `.atlas` + `.png` per character, base64-inlined). Bones = real armatures,
  not frame swaps.
- **State machine**: hand-rolled weighted-random matrix `WT` over abstract slots `I, S, L, ML, MR, P`
  (Idle/Sit/Sleep/MoveL/MoveR/Special), each row a weight table, `ns(cur)` picks next weighted state.
  Animation names are *classified* into slots (`ca(n)` regex → I/M/S/L/P/A/D/O), so any Spine model
  maps into the same behavior shell. This is the same weighted-random-FSM idea we use, but ours is
  data-driven JSON in a pure JVM core.
- **Movement**: WebView renders; walking accumulates `_wox` and pokes the native side every 30 ms via
  an iframe `jsbridge://walk/{dx}` to move the floaty window. Bounded by screen edges, no gravity.
- **Interaction**: `ACTION_UP` within 500 ms and < 10 px movement = tap → optional direction
  switch (`behavior_direction_switching`) + play Interact animation. Drag = move the window; **no
  throw, no fling, no fall**.
- **Config**: `behavior_allow_walk/sit/sleep/special/interact`, `display_scale`, AI-activation
  factor (`behavior_ai_activation` 0–16) that scales idle-weight rows (fewer idle, more activity).

### uDesktopMascot (353★)
- Unity desktop app. Loads external VRM/GLB/FBX models from `StreamingAssets`; voice + BGM from
  folders (click-voice support); deep GUI customization; releases + CI badges.
- Shows the feature ceiling of a *general* mascot app: asset-driven everything, sound, menu, themes.
  Desktop-only, heavy (Unity runtime), no Android story, no permission/tool semantics.

### MobileGoose
- iOS port of Desktop Goose. Modding = subclass hook (`handleFrameInState:`) called per frame with
  the goose's state — a per-frame mod hook is a much lower-level extension point than our plugin
  interface (semantic actions like `PlayAnimation`, not raw frame ticks).
- Famous interaction: click-and-drag to **throw** the goose — we already ship fling-on-drag.

### floating-pet
- Tamagotchi genre: stats decay every 30 s, feed/play/sleep/wake commands, breathing idle scale
  animation via Flutter `AnimationController`, overlay via `flutter_overlay_window` plugin.
- Demonstrates care-gameplay (hunger/mood/energy + persistence) — a different genre from Shimeji
  mischief; not adopting, but the pattern shows the overlay stays a thin render shell.

### PetDesk
- 5 Kotlin files. FGS + `TYPE_APPLICATION_OVERLAY` + NanoHTTPD on `localhost:8765`.
  `POST /api/state?state=thinking|building|working|error|happy...` → `PetEngine.transition()` →
  `petView.setState(...)`. The pet **mirrors the agent's emotional/lifecycle state**.
- Closest spirit to our model-integration vision, but one-directional (agent → pet emotions, 10
  states, no tools, no schemas, no permissions). Ours is bidirectional: agent requests go through
  the harness with schemas/permissions and the mascot shows tool feedback.

## ShimejiHarness vs the field

| Dimension | Ours (ShimejiHarness) | arkpets-lite | PetDesk | floating-pet | Desktop Goose lineage |
|-----------|----------------------|--------------|---------|--------------|----------------------|
| Platform compliance | Overlay + FGS specialUse, no acc., no hidden APIs | Autojs floaty (overlay) | Overlay + FGS | Overlay plugin | Jailbreak (iOS port) |
| Renderer | Frame-sprites (2D), 14 anims/25 frames, offline | Spine skeletal (WebView) | Static sprites | Vector widget | Frame-sprites |
| FSM | Data-driven JSON, weighted-random, generic core (JVM, 36 tests) | Weighted-random WT matrix (hand-rolled JS) | Enum state switch | Action strings + timers | XML action files |
| Physics | gravity/fall/bounce/throw/fling/peak-trip, drag+fling | **none** (window move only) | none | none | throw on drag (desktop) |
| Interaction | tap <400 ms → weighted reactions; drag → fling | tap → interact anim + dir switch; drag → move | agent state hook | feed/play buttons | click/drag/throw |
| Extensibility | compiled-in trusted plugins + tool harness (schemas, permission-gated, event bus) | character packs (drop skel/atlas/png) | none | none | per-frame mod hook |
| Agent/model story | replaceable `ModelAdapter`; future on-device model calls tools via harness | AI-activation weight dial (no model) | HTTP state mirror to Claude Code | none | none |

**What we have that none of the sampled apps have**: a testable pure-JVM domain core, permission-
aware tool harness with schema validation, event-bus-driven visual feedback, physics (fall/bounce/
fling/trip), true FGS lifecycle, zero network permission.

**What they have that we could adopt (ranked):**

1. **Tap toggles facing direction** (arkpets `behavior_direction_switching`) — one-line FSM/view
   change; the mascot faces the way you tapped. Cheap, fun.
2. **Agent-latency "thinking" animation** — PetDesk mirrors lifecycle; we already get
   `ToolCallAccepted` before `PlayAnimation`. Add a distinct `tool_thinking` non-looping state so
   the mascot visibly "works" while the model computes. Backlog-friendly (config + 1–2 frames or a
   bounce loop).
3. **Behavior toggles in the UI** (arkpets config: allow walk/sit/sleep/special/interact) — expose
   existing FSM JSON knobs from the main screen instead of baking them in the service default.
4. **AI-activation dial** — arkpets scales idle-weight rows by a 0–16 factor. We could map the same
   idea to our cooldowns (more walking/less idle). Cheap; part of the FSM config.
5. **Sound on click** (uDesktopMascot click voices) — needs an asset + audio; would tie into the
   existing jarvis TTS stack later. Deferred.
6. **Multiple compiled-in characters** — arkpets scans a `res/` dir. For us: a compiled-in character
   registry (still trusted, no downloads). Backlog.
7. **Care-gameplay (stats)** — genre mismatch with Shimeji mischief; explicitly **not** adopting.

**Things we verified we should NOT copy:** WebView-rendered pets (arkpets — 92 MB repo, JS-in-HTML
monolith, no testability); per-frame mod hooks (MobileGoose — too low-level); Unity 3D mascot
(uDesktopMascot — desktop-only, heavyweight); localhost HTTP control APIs (PetDesk — open local
port violates our no-network stance; our EventBus already covers in-process model→pet).

## Verdict

Our architecture is already ahead of the Android mascot field on the dimensions that matter for the
project goals: physics, testable core, permission-aware plugins, offline-first, overlay compliance.
The two immediately-winnable adoptions are **tap-facing toggle** and a **tool_thinking state**;
both are small and directly serve the interaction + agent-visibility story. Everything else is
backlog or genre-mismatch.