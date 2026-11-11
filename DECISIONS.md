# Decisions

Written from scratch for the 3D brief. Nothing from the earlier top-down prototype in this
repository is reused except the generic Gradle wrapper script.

## 1. Stack

Criteria: (a) build, test and package entirely from the CLI in this container, (b) real-time 3D at
60 fps on mid-range phones, (c) procedural or permissively licensed assets with no manual work,
(d) AdMob with minimal glue.

| Option | CLI build here | 3D at 60 fps | Assets | AdMob | Verdict |
|---|---|---|---|---|---|
| **Native Kotlin + OpenGL ES 2.0** (custom renderer) | Yes: Gradle + SDK command-line tools. Logic tests on the JVM, UI tests on Robolectric. The renderer is plain Kotlin behind a small `Gl` interface, so it also runs on desktop Mesa for headless screenshots. | Yes: about 20 draw calls and under 100k vertices per frame, vertex-lit, one cheap fragment shader | All generated in code | First-party SDK, one 170-line wrapper | **Chosen** |
| Godot 4 | Not here: the editor binary and export templates are hosted on GitHub releases, which this container's network policy blocks (HTTP 403) | Yes | Procedural possible | Third-party plugin, version-coupled to the engine | Rejected |
| Unity | No: needs a licensed editor and a GUI activation step | Yes | Yes | Official plugin | Rejected |

Trade-off accepted with the chosen option: I had to write the renderer, and there is no scene
editor. In return the APK is small (2.4 MB release), there is no engine start-up time, and every
line is testable.

Versions: AGP 8.13.2, Gradle 8.14.3 (wrapper), Kotlin 2.2.21, compileSdk 36, targetSdk 35,
minSdk 23, play-services-ads 24.9.0, UMP 3.2.0, Robolectric 4.16, LWJGL 3.3.4 (desktop harness only).

Build environment note: Maven Central rate-limits this container's shared egress IP (HTTP 429),
so `settings.gradle.kts` lists Google's Central mirror before `mavenCentral()`.

## 2. Orientation: landscape, locked

A chase camera looking down a road wants a wide view, and two-thumb controls (steer left, brake
and nitro right) need the long edge. The activity uses `sensorLandscape` (either landscape
rotation) and handles configuration changes itself, so it is never recreated mid-run.

## 3. Architecture

```
core    World (simulation), Road, Traffic (pool + spawner + AI), Difficulty, Collision, Combo,
        EnvCycle, ParticleSystem, AdGate. No Android, no rendering.
engine  Renderer, ChunkMesher, Models, MeshBuilder, Batch, Mat4, Gl (interface), Quality.
app     GameSurface (GL thread: step + draw), GameController, HudView, MainActivity, Audio,
        Haptics, TiltInput, Settings, ads/.
desktop LwjglGl + Shots (preview harness).
```

* The player lives in road space: `s` = distance along the centreline, `x` = lateral offset.
  All gameplay (collisions, spawning, near misses) is done in those coordinates; the renderer
  maps them onto the curved, hilly world. Curves still matter to gameplay: they push the car
  outwards (`drift = 0.085 * curvature * v^2`, capped at 1.2 m/s).
* **Pooling / allocations**: traffic is a ring queue of 128 preallocated obstacles, pickups 64,
  particles 256, road chunks 20 (each a reused vertex buffer), road centreline a 2048-sample ring.
  `World.update` is measured at **0 bytes over 60,000 frames** (`MiscCoreTest.updateDoesNotAllocate`).
  Simulation + rendering together are measured at **1.7 bytes per frame** on the desktop harness
  (that residue is inside LWJGL's driver calls, not game code). The HUD formats numbers into a
  reused `char[]`.
* **Streaming, no pop-in**: the world is built in 32 m chunks (road, markings, rails, terrain,
  trees, buildings, lamps, signs in one mesh). A chunk is rebuilt when it is recycled to the far
  end, at most one per frame, beyond the fog end, so it fades in. Geometry is a deterministic
  function of (seed, chunk index), so it can be rebuilt after a GL context loss.
* **Precision**: the camera position is kept in doubles and everything sent to the GPU is
  camera-relative, so nothing jitters however far you drive.
* **Parallax depth** is real 3D: bushes and rocks 7-13 m from the road, trees to 46 m, buildings
  20-40 m, a low plain beyond, and two mountain silhouette layers at infinity.

## 4. Difficulty (all in `core/Difficulty.kt`, constants in `core/Cfg.kt`)

`t` = seconds since the run started.

```
cruise(t)  = 25 + (55 - 25) * (1 - e^(-t/80))            m/s   (90 -> 198 km/h, capped at 55)
level(t)   = 0                                  for t <= 15 s  (grace period)
           = min(0.95, 1 - e^(-(t-15)/70))      afterwards
gapTime(L) = 2.3 - 1.35 * L                     s     comfortable time between forced lane changes
guard      = max(1.3 * cruise * 0.6,  (1.1 * cruise - u) * gapTime(L)) + 6.2   m
             (u = speed of the obstacle ahead; 0.6 s is the hard minimum for a lane change)
P(next cluster is in the other lane) = 0.5 + 0.3 L
P(cluster is static)                 = 0 if L = 0, else 0.06 + 0.2 L
cluster size                         = 1 .. 1 + floor(2.6 L)        (max 3)
traffic speed                        = 34 % .. 58 % of cruise at spawn
nitro = cruise * 1.3 for 2.5 s, 7 s cooldown;  brake and shoulder floor = cruise * 0.62
```

Everything is monotonic in `t` and capped; `DifficultyTest` sweeps 0 to 7200 s.
**First 15 s**: level 0 means only single moving vehicles, the widest gaps, and the first one at
least 95 m away. Because obstacles are spawned 430 m ahead, the spawner evaluates the level at the
time the player will *arrive*, and static hazards are only allowed for arrival times after 35 s.

## 5. Why the spawner can never build an impassable pattern

Invariant: **for every obstacle A and the first obstacle B ahead of it in the other lane, the
free distance between them is at least `B.guard`.** So after passing A there is always room to
change lanes before B, even at nitro top speed.

It holds because (1) clusters occupy a single lane and are only spawned once that distance
exists, and (2) AI vehicles never overtake and never close in on B beyond its guard; they slow
down instead. Traffic never exceeds 0.61 x cruise and the player never drops below 0.62 x cruise,
so nothing can hit the player from behind either.

A consequence worth knowing: traffic in the free lane queues up before a static hazard (stopped
car, cones, roadworks) instead of driving alongside it, like a jam before roadworks. Letting it
drive through would create a moving wall the player could only survive by braking to traffic
speed, and I chose a guarantee that never depends on braking. Static clusters get extra room
behind them to keep these queues short.

The fuzz test (`SpawnerFuzzTest`, 10,000 patterns, about 100,000 clusters, 23,000 forced lane
alternations) checks this with a validator that shares no reasoning with the spawner: it steps the
real traffic simulation at 60 Hz and tracks the set of lateral positions reachable by a
deliberately weak driver (constant speed with no braking, never leaving the paved lanes, only 80 %
of the real steering rate minus the worst-case curve drift). An empty set means impassable. It runs
at cruise, nitro and brake-floor speeds, from early, mid and capped difficulty. A second test
confirms the validator does reject walls and too-tight alternations.

## 6. Gameplay interpretations

* **Steering** is analog. Input is low-pass filtered (time constant 0.06 s at low speed rising to
  0.13 s at top speed) and maximum lateral speed only grows from 6.0 to 7.5 m/s while forward
  speed nearly triples, so the steering angle shrinks with speed. Touch: a floating pad, drag
  distance = steering amount (62 dp = full lock) with a soft centre curve. Tilt: roll the phone,
  about 22 degrees = full lock, small dead zone; in tilt mode the left half of the screen brakes.
* **Shoulder**: when more than half the car is past the edge line, speed falls to 0.62 x cruise
  and nitro cannot be triggered. **Guard rail** at 5.8 m from the centre: crash. **Obstacles
  beyond the road**: broken-down cars parked on the shoulder (hazard lights), which you only hit
  if you leave the road.
* **Crash**: 1.4 s at quarter speed with camera shake, a flash, sparks and debris, then game over.
* **Near miss**: passing a moving or stopped vehicle with less than 0.9 m lateral clearance while
  at least 8 m/s faster. 100 points x combo; consecutive near misses within 3.5 s raise the combo
  (max x8).
* **Score** = metres driven + 50 per coin + near-miss bonuses.
* **Shield**: lasts 5 s, absorbs one hit (destroys that obstacle or bounces off the rail), then
  1 s of invulnerability. One appears every 24-38 s in the free lane.
* **Coins** appear in the free lane next to clusters, which also teaches the correct line.
* **Environment**: clear, dusk, night, light rain, 75 s each with 8 s cross-fades. Night has
  headlight beams on the road, light glows, tail lights, street lamps and lit windows.
* **Visual speed**: FOV 50 to 63 degrees with speed, +8 on nitro; speed lines above about
  140 km/h and during nitro. I implemented speed lines rather than true motion blur (the brief
  allows either), because a blur pass costs a full-screen render target on mid-range GPUs.
* **Shadows**: blob shadows under vehicles (medium), plus a second sun-offset soft layer (high).
  Not shadow maps.
* **Quality presets**: low / medium / high = draw distance 230 / 320 / 410 m, shadows off / blob /
  blob + soft, particles 96 / 176 / 256, scenery density 45 / 75 / 100 %, render resolution
  70 / 85 / 100 %.
* **Retry** resets the pools in place and rebuilds all chunks: about 20 ms on the desktop harness.
* **Audio**: one streaming `AudioTrack` fed by a mixer thread. Engine drone whose pitch and
  volume follow speed, plus synthesised effects (coin, shield, shield break, crash, nitro,
  near-miss whoosh, click). Any audio failure disables audio instead of crashing.

## 7. Ads

* Ids only in `app/ads.properties` (Google test ids). No banners.
* **Interstitial** (`core/AdGate`): considered only when a finished run is left (Retry, Menu, or
  quitting from pause), never during gameplay; never during the first 3 completed runs of a
  session; at most one per 4 completed runs; at least 3 minutes after the previous one. A session
  is the process lifetime. If no ad is loaded, nothing happens and the slot is not consumed.
* **Rewarded continue**: offered on game over only if an ad is loaded and it has not been used
  this run. Earning the reward clears 170 m of road and gives 2.5 s of invulnerability.
* **Consent**: the UMP flow runs at launch; ads are only initialised if `canRequestAds()`.
  Settings shows "Ad privacy options" when UMP requires it, and a privacy-policy link whose URL
  is a placeholder.
* Every SDK call is wrapped; failed loads retry with back-off (20 s up to 5 min).

## 8. Lifecycle and persistence

* `onPause` (screen off, home, call) and loss of window focus pause the run; the GL context is
  preserved when possible and fully rebuilt when not.
* After recreation or process death the app returns to the menu. An in-progress run is not
  restored: restoring a half-finished arcade run is low value and pausing covers the common case.
* SharedPreferences: best score (saved the moment game over is shown), steering mode, sound,
  vibration, graphics quality, lifetime run count.

## 9. What was verified, and what was not

Verified in this container:

* `./gradlew build` from a clean checkout: compiles, all tests pass, lint reports 0 errors,
  debug APK and R8-minified unsigned release APK are produced.
* 38 core tests (difficulty, 10,000-pattern spawner fuzz, collision rules, scoring and near miss,
  shield, nitro, steering, road geometry, ad gating, zero allocation, a bot that plays 60 runs of
  3 minutes) and 14 Robolectric tests (screen flow, pause/resume, backgrounding, recreation,
  persistence, continue once per run, interstitial cadence through the real activity, play with
  no ads, touch controls, tilt mapping, UI screenshots).
* The real renderer was run on desktop Mesa (software GL) through the `Gl` interface, with the
  shaders compiled as GLSL ES 1.00, the same source Android gets. Frames for day, dusk, night,
  rain, nitro, roadworks and crash were inspected; a selection is in `docs/screens/`.

**Not verified, because this container has no KVM and so no emulator, and no device:**

* The app has never run on Android. Unverified on real hardware: the GLES20 backend
  (`AndroidGl`, 60 lines of direct calls), EGL config selection, actual frame rate, audio output,
  haptics, tilt direction and feel, touch feel, live AdMob test ads and the UMP form.
* 60 fps is an expectation from the workload (about 20 draw calls, under 100k vertices,
  vertex lighting), not a measurement. The only timing I have is 11.5 ms per frame for simulation
  plus rendering on a *software* rasteriser, which says little about a phone GPU.
* Tilt sign per rotation is derived from the sensor coordinate conventions and unit-tested as a
  mapping, but which way feels right in the hand needs a device.
* Game feel and difficulty tuning were checked only with a bot and by reading frames, not by a
  person playing.

Known limitations:

* Speed lines instead of motion blur; blob shadows instead of shadow maps; scenery casts no shadows.
* Traffic never changes lanes, and it queues before static hazards (section 5).
* Robolectric cannot render the GL surface, so UI screenshots have a flat background.
* Remaining lint warnings (12): mostly `UseKtx` suggestions, `ClickableViewAccessibility` on the
  HUD (a game control surface) and the intentionally non-resizable activity.
