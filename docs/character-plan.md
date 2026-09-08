# Character Plan: bundled open-source test character WITH LEGS

Goal: prove the mascot engine renders a real character with legs (separate,
animated feet), not just our procedural blob. Chosen character: **the classic
Shimeji** (orange mushroom-style character from Yuki Yamada's original
program), because it is (a) open-source, (b) 128×128 RGBA PNG strips with a
clean transparent background, (c) has explicit leg poses (walk alternates two
feet; there is even a "dangle legs while sitting" animation).

## Source & license
- Frames: `img/shime1.png … shime46.png` from `estenv/linux-shimeji`
  (Linux port of Yuki Yamada's original; license file = ZLIB/LIBPNG-style,
  "Permission is granted to anyone to use this software for any purpose ...
  and to redistribute it freely").
- Original program: `http://www.group-finity.com/Shimeji` (Yuki Yamada).
- We bundle a *subset* of frames inside `overlay/src/main/assets/mascot/` with
  an attribution file. Attribution retained; ok to redistribute under the same
  terms. (Note in memory: verify exact character-image license terms before any
  wider commercial redistribution; source repo itself does not separate image
  and code licensing.)

## Legs — verified programmatically (not just assumed)
Lowest opaque row of the frames (row 127, y=127 of 128):
- `shime1` (stand): two foot clusters at x 58–60 and 65–68 (feet together)
- `shime2` (walk/step 1): clusters at 56–58 and 72–76 (left foot forward wide)
- `shime3` (walk/step 2): clusters at 55–60 and 75–76 (right foot forward wide)
The walk cycle widens/alternates the feet → separate legs confirmed.
`shime31/32/33` are the explicit dangle-legs animation for later idle variety.

## Pose mapping (animationName → frames)
From the original Actions.xml pose table; frames chosen to match the FSM
animation frame counts so View loop math stays consistent:
| FSM animation | Frames            | Original action            |
|---------------|-------------------|----------------------------|
| idle          | shime1, shime1    | 立つ (Stand)               |
| walk          | shime2, shime3    | 歩く (Walk, feet alternate)|
| fall          | shime4            | 落ちる (Fall)              |
| climb         | shime14, shime13  | 壁を登る (climb wall)      |
| drag          | shime9, shime7    | つままれる (picked)        |
| magic_cast    | shime5, shime6, shime1 | 抵抗する (resist)     |

## Renderer integration
- New `MascotRenderer` interface in `:overlay`
  (`draw(canvas,width,height,state,animationName,frameIndex,frameCount,nowMs)`).
- `SpriteMascotRenderer`: loads frames via `BitmapFactory` from
  `assets/mascot/`, scales 128px art to the window (≈86% of min dimension),
  anchors the sprite at bottom-center (matching the original ImageAnchor
  64,128 = feet), flips horizontally when `facingLeft`.
- `poses.json` lists animations; renderer falls back to single-frame rendering
  per animation and the whole view falls back to `ProceduralMascotRenderer` if
  asset loading fails (log first).

## Verification
1. `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
2. Install on physical device + start overlay; logcat shows
   `SpriteMascotRenderer: loaded 6 animations, N frames`.
3. On screen: classic orange shimeji with feet; during walk, feet alternate;
   drag = picked pose with dangling legs.
4. Unit-level rendering tests remain in `:core` (asset loading is Android-side,
   so device verification is the honest check; no fake "rendering tested" claims).