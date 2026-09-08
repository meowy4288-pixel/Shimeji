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

**Things we verified we should NOT copy (architectural/spec reasons ONLY — licensing is NOT a
factor: this is a non-commercial/personal project, per user decision, so we can lift features,
assets, and behavior patterns freely)**: WebView-rendered pets (arkpets — 92 MB repo, JS-in-HTML
monolith, no testability); per-frame mod hooks (MobileGoose — too low-level); Unity 3D mascot
(uDesktopMascot — desktop-only, heavyweight); localhost HTTP control APIs (PetDesk — open local
port violates our no-network stance; our EventBus already covers in-process model→pet).

## Verdict

Our architecture is already ahead of the Android mascot field on the dimensions that matter for the
project goals: physics, testable core, permission-aware plugins, offline-first, overlay compliance.
The two immediately-winnable adoptions are **tap-facing toggle** and a **tool_thinking state**;
both are small and directly serve the interaction + agent-visibility story. Everything else is
backlog or genre-mismatch.

## Browser-extension Shimeji world (new research pass)

The user reported that the browser Shimeji extension (same classic sprite lineage — that's why
"our shimeji" shows up) has many features we lack. Sources: `deceasedone/web_companion` (1719-line
TS/React Shimeji replication, credits shimejis.xyz + classic Shimeji), `fuyufjh/ArkPets-Chrome`
(real Chrome extension pattern: content-script canvases + settings persistence). Key confirmation:
**the classic action set and frame numbering are identical to our bundled sprites** — web_companion
comments: `1 stand / 2,3 walk / 4 grabbed / 9 fall / 10 splat / 11 sit / 12,13 wall climb /
14 wall hold / 18,19 ceiling / 20 ceiling hold`. We already ship 13,14,18,19,20; missing for
cling/ceiling/splat: 10 (splat), 12 (first climb frame), plus 23–25 (sleep) etc. from linux-shimeji.

### The full classic action menu (ground context)
walk along window floor · run · crawl · walk left/right and sit · grab window left/right wall ·
walk and grab wall · pull up shimeji* · stand up · sit down · sit while dangling legs · lie down ·
sleep · split into two* · sit and face mouse · sit and spin head · chase mouse · pin to mouse ·
steal something · throw element… · jump to… · remove · remove all. Ceiling/wall contexts swap in
climb along [ceiling]/hold onto/fall from + wall variants. Wall context adds climb up [wall].

### Feature-gap matrix (browser/classic → Android-adoptable)
| Feature | Where seen | Android analog | Effort | Blocked by |
|---|---|---|---|---|
| Context action menu | web_companion, classic | long-press → action sheet → `fsm.forceState(...)` (context-aware) | M | none |
| Climb wall / ceiling / hold / drop | classic, web_companion | 3 new FSM states + frames (mostly already bundled; pull shime10/12/23-25) | M (next milestone) | none |
| Spin while thrown | web_companion (`rot += vx*0.25`) | rotate bitmap during `thrown` | S | none |
| Splat → dazed stun | web_companion | `dazed` state: stun timer + stars | S | none |
| Multi-mascot summon / dismiss / count | web_companion `useShimeji`, ArkPets `activeCharacters[]` | spawner UI + k overlay windows | M (backlog) | none |
| Settings persistence (characters, size, toggles) | ArkPets `chrome.storage.local` | SharedPreferences/DataStore | S | none |
| App filter (blacklist/hide-on-app) | ArkPets websiteFilter + fullscreen-hide | hide mascot on chosen apps → needs foreground-app | S | accessibility observer (opt-in) OR UsageStats appop |
| Walk on real window edges / grab window walls | classic "window floor/wall", web_companion platforms | per-app window bounds | M | accessibility observer (opt-in) |
| Steal page elements / synthetic taps | web_companion `data-shimeji-stealable` | tap injection — NOT doing (accessibility-as-action) | M | accessibility (out of scope) |
| Chase mouse / pin to mouse / face mouse | web_companion | overlay gets pointer events → chase finger while held | S-M | none |
| Jump to… / throw element… (selectors) | web_companion | crosshair mode: tap target → mascot jumps there | M | none |
| Split into two / pull up shimeji | classic joke actions | skip | — | — |

### Accessibility decision block (user question: "why are we not doing accessibility?")
- We never needed it for RENDERING: `TYPE_APPLICATION_OVERLAY` already draws above every app and
  the user sees/touches the mascot live. Accessibility as a rendering mechanism is the classic
  Play-policy abuse pattern (scary consent dialog "read your screen", store rejection, OEM
  fragility) — and our own spec explicitly ruled it out.
- The ONLY real gains as an OPTIONAL OBSERVER (window geometry + foreground app, never touch
  injection): (a) hide-on-app / per-app behavior (ArkPets fullscreen-hide analog), (b) walking on
  real app window edges + grabbing window walls — the classic signature move.
- Since this is a personal sideloaded app (non-commercial), Play-policy risk is moot for 2 users.
  Remaining cons: consent dialog, OEM reliability, observer overhead, spec deviation.
- **Decision point**: keep spec (no accessibility; window mimicry stays approximate) OR add an
  opt-in observer toggle (default OFF, gated behind settings, `canAcceptVisualCommand`-style
  guard) that unlocks foreground-awareness + true window-edge geometry. Overlay stays the only
  render/touch layer either way, and tapping is never injected.
- Recorded in memory.md; pending user call.
