# Shimeji Harness — Project Memory

**Location of truth for this Android companion-mascot project.**
Update this file whenever context would otherwise be lost. Companion docs:
- `docs/research-shimeji.md` — how real Shimeji projects work, applied findings
- `docs/character-plan.md` — open-source test character (with legs) integration plan

## Current mission
Android Shimeji-style companion: draggable, animated mascot overlay + extensible,
permission-aware plugin harness (compiled-in plugins). Original spec:
`/home/kisa/Downloads/upgraded-shimeji-build-prompt.md`.

Repo: `https://github.com/meowy4288-pixel/Shimeji` (pushed; main, commit `96d4bc8`).

## Environment (Fedora 44 KDE — desktop Linux)
- Project root: `/home/kisa/Downloads/shimeji-harness/`
- `JAVA_HOME=~/jdks/jdk-17.0.10+7`; `ANDROID_HOME=~/android-sdk`
- Gradle wrapper 8.4 committed; build verified: `./gradlew :core:test`
  (33/33 pass) and `:app:assembleDebug` (BUILD SUCCESSFUL)
- Android SDK: platforms;android-34, build-tools;34.0.0, platform-tools,
  emulator + system-images;android-34;google_apis;x86_64 (installed later)
- SSH key `~/.ssh/id_ed25519` → GitHub user **meowy4288-pixel** (only credential)
- Emulator AVD `shimeji` exists but the Qt window failed to register with adb on
  Wayland — physical device is the reliable path

## Architecture (3 modules, decided)
- `:core` — pure Kotlin/JVM (no Android classes). HarnessEngine, plugin API
  (ShimejiPlugin/PluginContext), FSM, physics, event bus, schema validator,
  model adapter, MockAgentPlugin, MockPlugin, DeviceControlPlugin, bundled
  `shimeji_fsm.json`. Enforced at Gradle level (`kotlin-jvm` plugin).
- `:overlay` — Android library. Foreground service (user-started), one bounded
  `TYPE_APPLICATION_OVERLAY` window (FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL),
  Choreographer frame loop, touch drag + velocity fling, ProceduralMascotRenderer
  (Canvas circle character), FSM-driven animation, insets-aware PhysicsBounds.
- `:app` — Compose UI (permissions, mascot controls, plugin toggles, tool
  catalog, mock-agent demo triggers), composition root (`ShimejiHarnessApplication`
  owns the single shared engine), SharedPreferences plugin toggles.

Package roots: `dev.delpa.shimeji.{core,overlay,app}`. compileSdk/target 34,
minSdk 26, AGP 8.2.0, Kotlin 1.9.22, Compose BOM 2024.01.00, coroutines 1.7.3,
serialization 1.6.2.

## Opt-in constraints (from spec + decisions)
- No accounts, no backend, no telemetry, no network permission, no root, no
  hidden APIs, NO AccessibilityService, no persistent background inference.
- "Plugin" = trusted compiled-in module. "MCP-like" = architecture analogy only.
- No inventing version numbers / claims of passing without running.

## Verification status (honest)
- VERIFIED: core tests 33/33; debug APK assembles; APK installs on physical
  device (I2405, Android 16 / API 36); overlay permission grantable via
  `adb shell cmd appops set dev.delpa.shimeji SYSTEM_ALERT_WINDOW allow`;
  notifications via `pm grant`.
- RUNNING ON DEVICE (verified via dumpsys + logcat): MainActivity UI + mascot
  overlay window (`type=2038`), `SpriteMascotRenderer` loads the bundled classic
  Shimeji (`loaded 6 animations, 12 frames`), mascot stands on the real bottom
  edge (IME-inset fix confirmed by window frame y=2338..2674 on 1260x2674 display).
- VERIFIED BY USER EYES: drag/touch, walk feet, fall, tool-feedback animation
  (still to confirm; mascot is on the device live).
- NOT VERIFIED: end-to-end behavior polish on device, character rendering with
  legs, behaviors beyond FSM states.

## Device (physical)
- Model: I2405 (vivo-family/Infinix), Android 16, SDK 36, serial
  `10BG120SYZ006HD`. Overlay DRAWING works; touches not yet exercised manually.
- IMPORTANT device finding: this OEM reports persistent bogus IME insets on
  overlay windows → we removed `TYPE_IME` from the insets mask (mascot stands on
  real bottom edge now). See `currentInsets()` in ShimejiOverlayService.

## Decisions & gotchas ledger (keep appending)
- MainViewModel: never shadow the `Application` constructor param with a
  property named `app` (`val engine = app.engine` resolved to `Application`
  → unresolved; renamed to `harness`).
- `Json.encodeToString(result.data)` needs `JsonObject.serializer()` (no
  `encodeToString` extension import in serialization 1.6.2 for JsonObject).
- `register()`/`unregister()` used to silently drop pre-start registrations;
  fixed so register-then-start works. Shutdown gate check ordered before the
  NOT_STARTED gate so shutdown reports SHUTTING_DOWN.
- `appops set ... SYSTEM_ALERT_WINDOW allow` did NOT stick on API 36;
  `cmd appops set <pkg> SYSTEM_ALERT_WINDOW allow` DID.
- Shell cannot `am start-foreground-service` an unexported service; the app
  itself must start it (its UI button works; synthetic taps via `input tap`).
- Overlay window insets on OEM: exclude TYPE_IME (bogus giant inset), keep
  systemBars | displayCutout | systemGestures.
- Physics uses bottom-epsilon grounding (1999.99-style) → test tolerances ≥0.02.
- Follow-up tasks in old tests were fixed with `runCurrent()` after submissions
  (collector scheduler turns).

## Open questions / next research tracks
1. Shimeji reference architecture (behaviors vs FSM) — see research doc.
2. Open-source character WITH LEGS to prove sprite-strip rendering (see
   character-plan doc). Procedural renderer currently draws stub arms only.
3. AccessibilityService vs overlay trade-off — researched in `research-shimeji.md`
   (recommendation: KEEP overlay; accessibility violates user's own spec
   constraint and hurts Play policy/ease-of-use; overlay already draws over
   other apps).
4. Multi-mascot? Edge-grab/cling behaviors? Sprite-sheet vs strip loading?
5. Tap-facing toggle + tool_thinking state (research-mascot-apps.md adopt list) — small, do with
   the next review-fix pass if user wants.
## Research — Mascot-app comparison (docs/research-mascot-apps.md)
- Surveyed via GitHub API (stars-sorted) + shallow clones: arkpets-lite (Android/Spine WebView
  overlay, weighted-random FSM slots I/S/L/ML/MR/P, jsbridge window walk, config toggles,
  AI-activation dial), uDesktopMascot (Unity desktop, VRM/GLB/FBX + voice/BGM), MobileGoose (iOS,
  per-frame mod hook, throw-on-drag), floating-pet (Flutter tamagotchi overlay, stats decay),
  PetDesk (Kotlin overlay + NanoHTTPD on localhost:8765 mirroring Claude Code state).
- VERDICT: we're ahead on physics (only Android sample with gravity/fall/bounce/throw), testable
  pure-JVM core, permission-aware tool harness, FGS compliance, zero network permission.
- ADOPT candidates (ranked): (1) tap toggles facing direction (arkpets behavior_direction_switching,
  cheap/fun); (2) distinct tool_thinking animation while model computes (PetDesk lifecycle idea,
  we already get ToolCallAccepted pre-PlayAnimation); (3) expose behavior toggles + AI-activation
  dial from UI; (4) sound on click (deferred, needs asset); (5) multiple compiled-in characters (backlog).
- NOT copying: WebView-rendered pets (no testability), per-frame mod hooks, Unity 3D, localhost HTTP
  control API (violates no-network stance), care/tamagotchi gameplay (genre mismatch).

## Decisions — copying / licensing stance
- USER: "it doesn't matter if we copy, it's not a commercial product." Personal/sideloaded
  project (2 users). Treat other mascot apps as a menu: lift features, assets, behavior
  patterns freely; no licensing gatekeeping. Keep our OWN hard constraints (no network
  permission, no AccessibilityService rendering, overlay-based, trusted compiled-in plugins).
- Recorded in research-mascot-apps.md: NOT-copy list is now architectural/spec-only.

## Milestone — Interaction + Animation (2024-09-09-ish, device I2405/API36)
- **New pose states (14 animations / 25 frames total)**: sit(`shime11`), dangle-legs(`shime31,33`), lie(`shime21`), look_up(`shime26`), jump(`shime22`), bounce(`shime18,19`), poke(`shime5,6`), trip(`shime20,19`); frames copied from `linux-shimeji/img/`, RCA via `poses.json`.
- **Tap-to-interact**: `MascotView.poke()` → weighted random jump/bounce/poke via `fsm.forceState`, gated by `canAcceptVisualCommand(COSMETIC_FEEDBACK)` (drag blocks it). Service differentiates tap (<400ms, no drag) from drag in ACTION_UP.
- **Idle variety (weighted random chains)**: idle → sit/dangle/lie/look_up (GROUNDED guards), sit → dangle/look_up; rebalanced walking=0.6/sit=0.7/look_up=0.6/dangle=0.5/lie=0.35, idle dwell cooldown 4000ms.
- **Hard-landing trip**: peak fall speed >= 1400 px/s → forceState("trip"), only when cosmetic-acceptable (physics already bounces normal landings).
- **Bug fixed**: animation phase now resets to 0 when FSM state changes — non-looping reactions (jump/poke/trip/magic_cast) previously showed their LAST frame immediately due to cumulative `animPhaseMs`.
- **Verified on device (logcat)**: `poke -> jump (accepted=true)`, `falling -> jump`, `jump -> idle`; look_up ×3 autonomously; walking↔idle at ~2.5–4s cadence; user drags → `dragging`/`falling` cycles; 36/36 core tests PASS; BUILD SUCCESSFUL.
- **Gotchas added**: (1) THIS OEM STRIPS `Log.d` in logcat — use `Log.i` for verification logs; (2) "Start mascot" button coords vary between dumps (351,1194 and 227,992 observed) — always re-query via uiautomator, don't hardcode; (3) logcat main buffer rolls fast on this device (OEM spam) — sample periodically or clear-then-capture; (4) synthetic `input tap` must target the CURRENT overlay frame (mascot walks); parse `dumpsys window windows` for `type=2038` frame each time.
