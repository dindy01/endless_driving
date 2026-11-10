# Decisions: Endless Drive (3D)

## 1. Stack

The criteria were: (a) build, test and package from the CLI here with no GUI step; (b) real-time
3D at 60 fps on mid-range Android; (c) procedural or permissively licensed low-poly assets;
(d) AdMob with minimal glue.

| Option | CLI build/test here | 3D @60 fps | Assets | AdMob | Verdict |
|---|---|---|---|---|---|
| **Native Kotlin + OpenGL ES 3.0 (own small renderer)** | Yes: Gradle + SDK. The renderer runs behind a `GL` interface, so the *same* shaders and code render headlessly on the JVM (LWJGL + Mesa EGL) in tests | Yes. Low-poly flat-shaded geometry, about 40 draw calls a frame, scenery baked into road chunks | Every model is generated in code (`Meshes.kt`); sound is synthesised | First-party SDK, ~200 lines of glue | **Chosen** |
| Godot 4 | Possible: headless export with about 1 GB of export templates; Android export still needs the SDK and Gradle | Yes | Same | Third-party plugin, tied to the engine version | Rejected: heavier, ads depend on a plugin, game logic is harder to unit-test headlessly |
| Unity | No: needs a licensed editor; batch mode isn't practical in this container | Yes | n/a | Yes | Rejected |
| Google Filament | Yes, but materials need the `matc` compiler binary, and the PBR pipeline is overkill for low-poly | Yes | Same | Same as native | Rejected: more moving parts, no visual benefit for this style |

The deciding factor was **verifiability**. No emulator can run here (no KVM). With the GL
interface I can still render real frames through the production shaders on a GLES 3.2
software implementation (Mesa llvmpipe) and look at them. A game engine would be a black box
in this environment.

Versions: AGP 8.13.2, Gradle 8.14.3 (wrapper), Kotlin 2.2.21, compileSdk 36, targetSdk 35,
minSdk 23 (device must support GLES 3.0; this is declared in the manifest). AdMob 24.9.0,
UMP 3.2.0, Robolectric 4.16, LWJGL 3.3.4 (tests only).

## 2. Architecture

```
core/    pure Kotlin/JVM, no Android or GL. Gameplay: PlayerCar (physics + steering),
         TrafficSystem (pooled AI), Spawner, World (rules, collisions, near misses, pickups),
         Difficulty, Scoring, AdGate (ad policy), GameSession (run state machine, slow-mo,
         HUD snapshot, ordered UI->game command queue), fx/ParticleSystem
render/  pure Kotlin/JVM 3D renderer: GL interface, Mat4, MeshBuilder, Meshes (procedural
         models), RoadGeometry (endless centreline), RoadChunks (streamed road + baked
         scenery), Environment (day/weather cycle), Quality presets, FxBatch, SceneRenderer
app/     Android: AndroidGL (GLES30), GameSurfaceView (GL-thread loop), HudView (HUD + touch
         controls), TiltSteering, MainActivity (screens), Sfx, Haptics, Prefs, ads/
```

* **Road frame.** Gameplay runs in road coordinates: `s` is the distance along the centreline
  and `x` is the lateral offset. Curves and hills are purely visual: the renderer bends the road
  frame through `RoadGeometry`. The rules therefore stay exact and testable, and the fairness
  guarantee doesn't depend on geometry.
* **Threading.** The GL thread runs `GameSession.frame()` and then `SceneRenderer.render()`, so
  the simulation is single-threaded. UI threads only enqueue commands (start, pause, resume,
  revive, attract) into a fixed ring buffer that is applied in order at the start of the next
  frame. Input is written to volatile fields, and the HUD reads a primitive snapshot.
* **No per-frame allocations.** `AllocationTest` measures HotSpot's per-thread allocation
  counter over 60,000 full session frames (simulation, traffic AI, spawner, particles, command
  queue) and gets **0 bytes**. Traffic, pickups, particles and road chunks are fixed pools. The
  renderer reuses matrices, `RoadPose`s, an `FxBatch` and chunk builders, and the HUD formats
  numbers into char buffers. Renderer and HUD freedom from allocation is by construction and
  review, not measured.
* **Camera-relative rendering.** World positions are kept in doubles. Everything is drawn
  relative to the eye at the origin, so precision holds after any distance driven.

## 3. Orientation: landscape (locked, both directions)

A chase camera needs horizontal field of view to show both lanes, the shoulders and upcoming
traffic. Landscape is also how people hold a phone "like a steering wheel" for tilt control,
and it leaves room for two-thumb controls (steer on the left; brake and boost on the right).
The manifest sets `sensorLandscape`, and `configChanges` prevents recreation on a 180° flip.

## 4. Traffic fairness: why a path always exists

Continuous steering and moving traffic at different speeds rule out a "rows" argument, so the
guarantee is a **state invariant enforced every tick**.

* **(I1)** Objects in the **same lane** keep at least `GAP_SAME_LANE` = 18 m between them,
  edge to edge.
* **(I2)** Objects in **different lanes** keep at least `GAP_OTHER_LANE` = 65 m between them
  along the road, edge to edge.

The AI never changes lanes. It only caps its own speed, processing cars front to back:
`v ≤ v_front + (gap − required)/dt`. That makes the gap after the step at least the required
value, by construction. Cars behind the player never go faster than the player, so they never
rear-end it. The spawner adds an object only if it satisfies I1 and I2 against every existing
object; otherwise it tries the other lane, then skips. Shoulder hazards are outside the paved
lanes and don't count.

**Why I2 is enough.** Say the player passes a block in lane A and must then reach lane B before
the next block. With I2, the free stretch is at least 65 m. A full lane change (3.5 m) at the
**guaranteed** lateral speed of 4 m/s takes 0.875 s. At the player's absolute top speed
(45 m/s base × 1.3 boost = 58.5 m/s), the player then covers 51 m, plus a car length plus
margin, which is less than 65 m. The real car is faster than 4 m/s laterally even at top speed
with the slowest steering response; a test checks this.

**Independent verification** (`TrafficFuzzTest`). There are **10,000 patterns**, each 12 s
long. Start times are spread over grace, ramp-up and capped difficulty. Three player speed
profiles are used: nominal throttle, boost whenever ready, and random brake/boost. Each pattern
runs the real spawner, the AI and the PlayerCar longitudinal physics. Alongside, a reachability
search tracks every lateral position (0.2 m bins, paved lanes only) that is reachable at
≤ 4 m/s without touching any real hitbox. A pattern fails if the reachable set becomes empty,
or if I1/I2 is ever violated. The result is 0 failures. Mutation tests confirm the checker does
reject a truck wall and lane closures that are too close together.

Limitation: the guarantee is geometric. Human reaction time is covered by the 300 m visibility,
the generous gaps and the slower steering response at speed, but it is not formally modelled.

## 5. Difficulty (`core/Difficulty.kt`)

```
vBase(t)   = 25 + 20·(1 − e^(−t/90))   m/s     90 → 162 km/h, capped at 45 m/s
level(t)   = 0                                  for t ≤ 15 s (grace)
           = min(0.95, 1 − e^(−(t−15)/80))      otherwise
spacing(t) = 140 m during grace, else 95 + (38 − 95)·level   metres of player travel per spawn event
kinds(L)   = car .46 · truck .12+.06L · stopped car .07+.05L · barrier .06+.04L ·
             cones .06+.04L · roadworks .03+.07L        (+12 % chance of a shoulder breakdown)
traffic speeds: cars 0.45–0.70·vBase (left lane +1.5 m/s), trucks 0.38–0.52·vBase
```

All of these are monotonic and capped; tests sweep 0 to 7,200 s. **The first 15 s are
forgiving.** The spawn spacing is 140 m, and only moving cars and trucks appear (no stopped
cars, barriers, cones or roadworks). Traffic is pre-populated from 110 m ahead so the road is
never empty at the start.

## 6. Controls and car feel

* **Touch (default).** Put a thumb anywhere on the left 55 % of the screen and drag. Steering is
  the drag distance divided by 64 dp, so it is fully analog and returns to 0 on release.
  BRAKE (hold) and BOOST (tap) buttons sit bottom-right, and multi-touch works.
* **Tilt.** Gravity, or the accelerometer if gravity isn't available, is projected into screen
  space for either landscape rotation. Steering is the rotation angle about the screen normal:
  2.5° dead zone, full lock at 28.5°, low-pass filtered. This direction mapping is unit-tested
  but unverified on a device.
* **Steering response.** Input expo is `1 + 0.8·speedNorm` (finer near centre at speed). The
  lateral speed follows the target with a first-order lag of τ = 0.10 s at low speed and 0.22 s
  at top speed, so it is calmer at speed. Maximum lateral authority (6.5 m/s) is the same at
  every speed, so the fairness guarantee holds.
* **Throttle** is automatic toward vBase. Brake goes down to 12 m/s at 16 m/s². Boost is ×1.3
  for 2.5 s with an 8 s cooldown; the HUD meter and the button ring show recharge.
* **Shoulder** (|x| > 3.5 m): speed is capped at 60 % of vBase, with an "OFF ROAD" warning.
  Touching the **guard rail** (inner face at 6.3 m) crashes the car; a shield absorbs it and
  bounces the car back.

## 7. Gameplay interpretations

* **Score** is metres driven, plus 25 per coin, plus near-miss bonuses.
* **Near miss.** While the player overlaps an object along the road, the minimum edge-to-edge
  lateral gap is tracked. A pass counts if the gap was under 1.0 m and the player was doing at
  least 20 m/s. A gap under 0.4 m is a "close call" (×1.5). Points are 50 × combo, with the combo
  growing up to ×8 within a 3 s window. Driving centred in the adjacent lane (1.66 m gap) does
  **not** count, so the player has to commit. The HUD shows the combo multiplier and a timer bar.
* **Crash.** 0.9 s of 0.25× slow motion (traffic and particles keep moving slowly), camera shake,
  car spin, sparks, smoke and debris. The game-over panel appears at 1.3 s.
* **Continue** (rewarded ad, once per run) clears 140 m ahead, gives 2.5 s of invulnerability
  (the car blinks) and resumes at 80 % speed.
* **Shield:** absorbs one hit (traffic, obstacle or rail) and lasts 5 s. It spawns every 25–40 s,
  first at about 18 s. **Coins** come in lines of 6, placed in a lane that is clear at spawn time.
* **Retry** resets pools in place inside one frame. A test measures well under 1 s, end to end
  in Robolectric. If an interstitial is due, Retry starts as soon as the ad closes.

## 8. Visuals

* **Environment cycle** (`Environment.kt`): CLEAR → DUSK → NIGHT → light RAIN, each 75 s, with
  8 s eased blends. Each phase blends sun direction and colour, ambient, sky gradient, fog and
  wetness. Rain adds thicker fog, darker wet surfaces and falling streaks around the camera.
  Night adds stars, emissive windows, lamp-post glows, headlight beams on the road, and red tail
  lights (brighter when the AI brakes). Barriers and roadworks have blinking amber lights, and
  stopped cars have hazard lights.
* **Camera:** the chase cam follows the road frame from 7–8 m behind and 2.55 m up, looking
  14 m ahead, so it banks naturally through curves and over hills. FOV goes from 58° to 74° with
  speed, +7° while boosting. Speed lines appear above about 70 % speed and during boost.
* **Depth and parallax:** real perspective for near scenery, plus a distant mountain ring fixed
  in world orientation (effectively at infinity), so it slides against nearer layers as the road
  curves.
* **Streaming:** 24 m road chunks (asphalt, markings, shoulders, rails and posts, terrain verges
  to ±70 m with rolling hills, baked trees, bushes, billboards, town buildings and lamps). They
  are pooled (18 slots) and rebuilt in place beyond the fog distance. Traffic spawns 340 m ahead,
  beyond the maximum 300 m draw distance, so there is **no visible pop-in**.
* **Quality presets:**

  | Preset | Draw distance | Blob shadows | Particles | Scenery | Rain drops | Glows | Resolution |
  |---|---|---|---|---|---|---|---|
  | Low | 150 m | off | 120 | 45 % | 160 | night only | 75 % |
  | Medium (default) | 220 m | on | 260 | 75 % | 320 | on | 100 % |
  | High | 300 m | on | 400 | 100 % | 520 | on | 100 % |

  "Shadows" here are blob shadows under vehicles; real shadow mapping wasn't worth its cost for
  this flat-shaded style.

## 9. Audio

`Sfx` synthesises everything at startup: an engine loop whose playback rate scales with speed,
crash, coin, shield, shield break, near miss (pitch rises with combo), boost and UI click. Each
sound is written once as a small WAV to the cache and played through SoundPool. Any failure
disables audio instead of crashing.

## 10. Ads

* IDs live **only** in `app/ads.properties`, and they are Google's public test IDs.
* **Interstitial:** considered only when leaving game over (Retry or Menu) or quitting from
  pause, never during play. `AdGate` rules:
  * never in the first 3 completed runs of a session (session = process lifetime);
  * at most once per 4 completed runs;
  * at least 3 minutes since the previous one.
* **Rewarded "Continue":** shown on game over only when an ad is loaded and continue hasn't been
  used this run.
* **Failure handling:** loads retry with backoff (15 s up to 5 min); with no ad loaded, nothing
  is shown and play continues. No banners.
* **UMP:** consent info is requested at launch, and the form shows if required (on the menu).
  Ads initialise only when `canRequestAds()`. Settings shows "Ad privacy options" when UMP
  requires it, plus a placeholder privacy-policy link.

## 11. Lifecycle and persistence

* `onPause` (home, screen off, call) pauses the run and stops the GL thread, audio and tilt
  sensor. Losing window focus while playing also pauses. Paused frames stop rendering (render
  mode switches to when-dirty).
* `onSurfaceCreated` recreates every GL resource after an EGL context loss, and
  `preserveEGLContextOnPause` is on.
* After recreation or process death the app returns to the menu. The best score is saved at
  game over, and the run count when a run completes. An in-progress run isn't serialised.
* Saved in SharedPreferences: best score, sound, vibration, control scheme, graphics quality and
  total runs.

## 12. Verification status and known limitations

See README "Verification". In short:
* **Not verified on a device or emulator** (no KVM here). Unverified: real-device frame rate,
  touch and tilt feel, audio output, haptics, live AdMob test ads and the UMP form.
* Headless rendering uses Mesa's CPU rasteriser. Its ~6–7 ms per frame at 1280×720 says nothing
  about phone GPUs. The scene budget is about 40 draw calls and ~60–100k vertices.
* Traffic AI doesn't change lanes. A car can queue behind a stopped vehicle or roadworks in its
  own lane.
* Collisions use road-frame AABBs (fine for arcade play). Car yaw while steering is visual only.
