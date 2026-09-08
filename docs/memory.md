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
- RUNNING ON DEVICE as of this session: MainActivity UI + mascot overlay window
  (`type=2038`) confirmed present in `dumpsys window windows`.
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