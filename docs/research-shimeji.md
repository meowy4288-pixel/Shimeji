# Research: How Real Shimeji Projects Work (and what we apply)

Session researched three real, open-source implementations and the original
program. Goal: apply their architecture to the Android harness without
violating our constraints (no accessibility service, no network, no hidden
APIs, compiled-in trusted plugins).

## Canonical lineage
1. **Original "Shimeji" — Yuki Yamada** (Java desktop, 2009):
   `http://www.group-finity.com/Shimeji`. Behaviors + image strips, zlib-style
   license. Bundled the classic orange mushroom character (shime1–46.png,
   128×128 RGBA, feet at anchor 64,128).
2. **Shimeji-ee / Kilkakon lineage** (fork with more mascots + XML action
   system; `gil/shimeji-ee` is a maintained v1.0.1 fork; `TigerHix/...`,
   `DalekCraft2/...` are more forks). BSD-style license + attribution clause.
3. **`estenv/linux-shimeji`**: Linux port of the ORIGINAL Japanese program
   (kept `Actions.xml`/`Behavior.xml` in Japanese tags, zlib/LIBPNG). Best
   primary source for the original pose table.
4. **Modern rewrites**: `pixelomer/Shijima-Qt` (C++/Qt, GPL-3, asset loader
   maps paths → images, `DefaultMascot`), `CluelessCatBurger/wl_shimeji`
   (Wayland, C), `webmeji` (web). They keep the pose/behavior model.
5. **Android**: no prominent open-source Android shimeji; existing Android
   "shimeji" apps are APK-only. Android desktop-pet apps in general use
   `TYPE_APPLICATION_OVERLAY` (see accessibility section).

## The architecture the best implementations converge on
The behavior/action/pose split is the core design. It is worth adopting as our
FSM's second layer.

### 1. Behavior layer (decisions)
- XML list: `<Behavior Name Frequency Condition><NextBehavior Add=...>
  <BehaviorReference Name Frequency/></NextBehavior></Behavior>`.
- Engine evaluates *conditions* against mascot + environment (on floor? on
  wall? on ceiling? near mouse?), picks eligible behaviors by weighted random
  frequency, then follows `NextBehavior` references (some are
  additive/probabilistic chains, e.g. Sit → SitWhileDanglingLegs → LieDown).
- Hidden "always required" behaviors: `Fall`, `Dragged`, `Thrown` — these
  interrupt whatever else is running. (Our FSM interrupts: DRAG_STARTED,
  TOOL_FEEDBACK — same idea.)
- Classic behaviors on the desktop: ChaseMouse, SitAndFaceMouse, Fall,
  Dragged, Thrown, PullUp, Divided, StandUp, SitDown, SitWhileDanglingLegs,
  LieDown, CrawlAlongIECeiling, WalkAlongWorkAreaFloor, HoldOntoWall,
  FallFromWall, HoldOntoCeiling, WalkAlongWorkAreaFloor/Run...
- Multi-mascot: conditions reference `mascot.totalCount` (e.g., SplitIntoTwo
  has `Frequency=50 Condition=#{mascot.totalCount < 50}`).

### 2. Action layer (mechanics)
- `<Action Name Type BorderType>`; `Type` ∈ `Stay` (do not move), `Move`
  (constant velocity), `Embedded` (custom Java class), `Complex` (composites).
- `BorderType` ∈ Floor / Wall / Ceiling — the physics edge the anchor sticks to.
- Actions ARE NOT behaviors: many behaviors share few actions
  (Walk/Run/Dash all use the same 4 poses with different velocity).

### 3. Pose layer (animation + physics)
- `<Pose Image="/shime1.png" ImageAnchor="64,128" Velocity="0,0" Duration="6"/>`.
- `ImageAnchor` = the point in the image treated as the physics anchor (feet at
  bottom-center for the classic character).
- Per-pose `Velocity` = px advance per frame while the pose is active
  (Walk uses `-2,0` per 6 ms pose).
- `Duration` = ms per pose. Walk cycle: shime1,2,1,3 @ 6 ms each (fast scurry).
- Direction: mascot has `lookRight`; images flip horizontally when facing left.

### 4. Environment / borders
- Desktop: work area (screen) + active IE window borders; a mascot can be ON
  the floor, ON a wall (left/right), or ON the ceiling, and transitions between
  them (climb up a wall, crawl along a ceiling, fall off either).
- Border contact uses "isOn" tolerance (small epsilon), not exact equality.
- Android mapping: floor = usable bottom edge, walls = left/right usable edges,
  ceiling = top edge. (No other-window introspection without accessibility,
  which we reject — so no IE-window equivalent; screen edges only.)

## Original pose table (authoritative, linux-shimeji `Actions.xml`)
Anchor 64,128 everywhere. Frames:
- Stand `shime1`; Walk/Run/Dash `shime1,2,1,3` (floor, vel −2/−4/−8)
- Fall `shime4`; Jump `shime22`
- Picked `shime9,7,1,8,10`; Resist `shime5,6,5,6,1,5` (×30)
- Trip `shime19,18,20,20,19`; Bounce `shime18,19`
- Sit `shime11`; comfortable `shime30`; sit-with-legs-down `shime31`;
  **dangle-legs `shime31,32,31,33`** (our future "dangle" idle)
- Hold wall `shime13`; climb wall `shime14,14,12,13,13,13` (×16)
- Hold ceiling `shime23`; traverse ceiling `shime25,25,23,24,24,24`
- Lie `shime21`; look up sitting `shime26`; sit-look-up/mouse `shime26,11`

## Accessibility vs. overlay — honest verdict: KEEP TYPE_APPLICATION_OVERLAY
The user asked whether an AccessibilityService would be better than a simple
overlay. Research says no for this product, and it would violate the original
spec constraint (no AccessibilityService). Reasons:

1. **Overlay already does the job.** `TYPE_APPLICATION_OVERLAY` exists
   specifically to draw over other apps ("Display over other apps"). It
   receives touches inside its window, which is exactly what a draggable mascot
   needs. Messenger-style chat bubbles and every floating-pet app use it.
2. **Accessibility adds nothing we need.** Its real powers are window-content
   reading and global action injection — both useless (and policy-dangerous)
   for a mascot. `TYPE_ACCESSIBILITY_OVERLAY` windows can draw above everything
   without the overlay permission, but they are managed by the accessibility
   framework, require the service toggled on in Settings (with the scary
   "can observe your actions" warning), and that toggle is exactly the support
   burden we want to avoid.
3. **Play policy risk.** Google Play requires accessibility services to serve
   an accessibility purpose; non-accessibility use (auto-clickers, floating
   pets, ad overlays) is a suspension/removal reason. There were widespread
   enforcement actions in 2021+. Even sideloaded, users distrust enabling it.
4. **Foreground service is the correct "long-living" piece**, not an
   accessibility service.
5. If we later want "react to the foreground app" (e.g., hide mascot in
   fullscreen video), the right tools are overlay window focus/type flags or a
   user-opt-in capability — not accessibility.

Conclusion: retain overlay window. Documented here so future sessions don't
re-litigate it.

## Applied plan (what we did / will do)
- [x] `SpriteMascotRenderer` in `:overlay` implementing a new `MascotRenderer`
      interface, drawing Bundled Classic Shimeji frames via
      `assets/mascot/poses.json` pose lists. Procedural renderer remains as a
      zero-art fallback.
- [x] Bundle open-source character frames (with legs) + attribution file.
- [ ] Behavior layer: expand FSM with `dangle` idle variant, `cling` (wall),
      `hang` (ceiling) states using the classic pose tables above; implement
      weighted-random idle selection with conditions (our `guards` already
      give conditions; add simple frequency weights).
- [ ] Anchor-aware physics: use pose `ImageAnchor` for placement (feet-anchored
      rendering matches physics ground exactly). Today the window is the body.
- [ ] Optional: multi-mascot spawn via `SplitIntoTwo`-style plugin action.
- [ ] Optional: extract pose vocabulary (actions/behaviors) into a
      schema-validated JSON asset in `:core` mirrors (Android-freedom
      preserved: asset lives in overlay/app but schema validator in core).

## Key files for future reference
- Original pose table: `/tmp/shimeji-research/linux-shimeji/conf/Actions.xml`
  (Japanese tags), `Behavior.xml` (decision tree).
- gil-ee English XML: `/tmp/shimeji-research/gil-ee/conf/actions.xml`
  (`<Action Name Type BorderType>` + `<Pose Image ImageAnchor Velocity Duration>`).
- Shijima-Qt: `/tmp/shimeji-research/shijima/` (C++ asset loader pattern).