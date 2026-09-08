# Shimeji Harness — Android companion mascot

A runnable Android **Shimeji-style companion**: a draggable, animated mascot
overlay with an extensible, permission-aware **plugin harness** and a mock
agent that drives it. Built from `upgraded-shimeji-build-prompt.md`.

Design principles enforced at the build level:

- `:core` is a **pure Kotlin/JVM module** — no Android classes. Domain logic
  (harness, plugin lifecycle, FSM, physics, adapters) is fully unit-tested on
  the JVM.
- Plugins are **trusted, compiled-in Kotlin modules** — no APK/DEX/script
  download, no network permission, no accounts/backend/telemetry.
- "MCP-like" is an *architectural analogy only* — the
  `ModelAdapter`/`MockAgentPlugin` boundary is where a future on-device model
  adapter would plug in. There is no real MCP protocol.
- The mascot renderer is **procedural** (Canvas-drawn) — no external art
  assets required.

## Modules

| Module | Kind | Responsibility |
|---|---|---|
| `:core` | Kotlin JVM (no Android) | Harness engine, plugin API, schema validator, FSM, physics, event bus, model adapter, mock + device plugins, bundled FSM config |
| `:overlay` | Android library | Foreground service, `TYPE_APPLICATION_OVERLAY` window, touch/drag, frame loop, procedural mascot renderer, FSM-driven animation |
| `:app` | Android application | Composition root, single shared `HarnessEngine`, Compose settings UI, permission flow, demo triggers |

## Building

Environment used for verification:

- JDK 17 (`JAVA_HOME=~/jdks/jdk-17.0.10+7`)
- Android SDK at `~/android-sdk` (`local.properties` → `sdk.dir=/home/kisa/android-sdk`)
  - `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`
- Gradle wrapper 8.4 (committed in `gradle/wrapper`)

```bash
cd shimeji-harness
export JAVA_HOME=~/jdks/jdk-17.0.10+7
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=~/android-sdk

# JVM domain tests (33 tests)
./gradlew :core:test --console=plain

# Debug APK
./gradlew :app:assembleDebug --console=plain
# -> app/build/outputs/apk/debug/app-debug.apk
```

Install on a device/emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Running on a device (manual checklist)

1. Open **Shimeji Harness**.
2. Grant **Display over other apps** ("Manage") and **Notifications** (API 33+).
   The UI reports current permission state and refreshes on return-to-foreground.
3. Tap **Start mascot** — a foreground notification appears and the mascot
   window is added (bounded window, `FLAG_NOT_FOCUSABLE |
   FLAG_NOT_TOUCH_MODAL`, so the app underneath keeps normal input).
4. **Drag** the mascot by touch (touch-slop gated); release flings with an
   estimated velocity. Physics clamps to insets-aware bounds and rests on the
   ground.
5. Plugins section lists compiled-in plugins with enable/disable toggles;
   the tool catalog shows each plugin's callable tools. The **device** plugin
   is disabled by default and reports battery only when enabled.
6. **Run mock agent** proposes a call from the active catalog and executes it;
   a successful call publishes a `PlayAnimation` visual event (the overlay
   plays "magic_cast"). **Call battery directly** exercises the enforced
   rejection path for a disabled plugin/tool.

## Feature checklist (from the spec)

Implemented:

- [x] Draggable overlay mascot with physics (gravity, velocity, bounds,
      wall/ground collision, capped fall/fling speed)
- [x] Procedural renderer — no bundled art
- [x] FSM config from bundled JSON asset (with in-code fallback config)
- [x] Autonomous behavior when idle; animation feedback on tool events
- [x] Plugin harness: register/enable/disable/pause, per-plugin execution
      mutex, async enable with FAILED state, cleanup cancel
- [x] Tool catalog: active-only aggregation, unavailable signatures,
      revision counter, validation (params, plugin, tool, capability,
      duplicate ids, ambiguous registrations), stale-target recheck
- [x] Permission model: `READ_BATTERY` capability gated on
      `BATTERY_STATS`; overlay + notification permission flow in UI
- [x] Event bus: requested → accepted/rejected → started → completed/failed,
      with request IDs and duplicate-request rejection
- [x] Confirmation-required result type (typed, non-executing)
- [x] JsonSchema-style validator for tool params
- [x] Model adapter boundary (`ModelAdapter` interface + `MockAgentPlugin`)
- [x] No network permission, no accounts, no telemetry, no root/hidden APIs,
      no `AccessibilityService`, no persistent background inference
- [x] Kotlin, coroutines, Flow, kotlinx.serialization, Gradle Kotlin DSL,
      constructor injection

## Verification status (honest)

- **Verified**: `./gradlew :core:test` — 33/33 pass (HarnessEngine 17,
  ShimejiFSM 8, PhysicsEngine 8). `./gradlew :app:assembleDebug` —
  BUILD SUCCESSFUL; APK produced (9 dex files, `assets/shimeji_fsm.json`
  bundled).
- **Not yet verified**: on-device/emulator runtime behavior (overlay window,
  foreground service, notification actions, touch drag) — no emulator or
  device was attached during development.

## Known limitations

- The overlay window is a bounded rectangle that may intercept touches on
  transparent pixels *inside* its rectangle (Android has no supported
  nonrectangular hit-test without hidden APIs). The window is sized to the
  mascot to minimize this.
- IME insets are only excluded if the overlay window reports them, which
  overlay windows commonly do not; the mascot may be visually overlapped by
  the keyboard while typing.
- `BATTERY_STATS` is a normal (install-time) permission on API < 23 and a
  signature/privileged permission on modern Android — it will generally read
  as "not granted" on a regular build. The plugin reports availability via
  `SystemInfo.hasBatteryPermission` accordingly.
- Plugins are *compiled in*; there is no runtime plugin install path by
  design.