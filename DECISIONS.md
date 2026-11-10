# Decisions

## 1. Stack choice

Criteria from the brief: (a) build/test/package from the CLI in this container with no GUI step,
(b) all assets procedural or permissively licensed, (c) stable 60 fps on a mid-range phone,
(d) AdMob with minimal glue.

| Option | CLI build here | Assets | 60 fps 2D | AdMob | Verdict |
|---|---|---|---|---|---|
| **Native Kotlin + Android Canvas (custom `View` + `Choreographer`)** | Yes. Gradle + Android SDK cmdline-tools. JVM unit tests and Robolectric run headless. | Everything drawn with Canvas primitives; sounds synthesised at runtime, so no asset files at all | Yes. Hardware-accelerated Canvas easily handles about 100 primitives per frame | First-party SDK, about 150 lines of glue | **Chosen** |
| Godot 4 (GDScript) | Possible but heavier: needs the editor binary plus export templates, and the Android export still needs the SDK and Gradle | Same, procedural drawing works | Yes | Third-party AdMob plugin, version-coupled to the engine | Rejected: more moving parts and a plugin dependency for ads |
| Flutter + Flame | Needs a roughly 1 GB SDK download; Android build still goes through Gradle/AGP | Same | Yes | `google_mobile_ads` is official | Rejected: biggest toolchain, an extra language/runtime layer, and no real gain for a single-screen 2D game |
| Unity | No: needs a licensed editor, which isn't practical headless | n/a | Yes | Yes | Rejected |

Environment note: `dl.google.com` and `maven.google.com` were blocked by the container's network
policy at first. I added them to a new personal cloud environment ("Android builds"). Without them,
no Android stack can build, because AGP, the SDK and AdMob all come from Google's Maven.
Maven Central sometimes returns HTTP 429 (shared egress IP), so `settings.gradle.kts` lists
Google's Central mirror (`maven-central.storage-download.googleapis.com`) before `mavenCentral()`.

Versions: AGP 8.13.2, Gradle 8.14.3 (wrapper), Kotlin 2.2.21, compileSdk 36, targetSdk 35, minSdk 23,
play-services-ads 24.9.0, UMP 3.2.0, Robolectric 4.16.

## 2. Architecture

```
core/   pure Kotlin/JVM, no Android imports. All rules live here:
        GameConfig (tuning), Difficulty, Spawner, World (simulation), LaneController (input
        buffer + easing), Collision, Scoring, AdGate (ad frequency policy), fx/ParticleSystem
app/    Android only: GameView (loop + touch), Renderer (Canvas), MainActivity (screens),
        Sfx (synthesised audio), Haptics, Prefs, ads/ (AdService, AdMobService, NoAdService)
```

* `World.update(dt)` performs **zero allocations**. `AllocationTest` measures HotSpot's
  per-thread allocation counter over 60,000 frames and gets 0 bytes. Obstacles, pickups and
  particles are fixed pools. Kinds are `Int` constants, not enums, so nothing iterates `values()`.
* The renderer pre-allocates its paints, paths, shaders, `RectF` and text `CharArray`s. Numbers
  are formatted into char buffers; popup labels are pre-built strings.
* Ad policy (`AdGate`) is pure and unit-tested. The SDK wrapper only loads and shows ads.

## 3. Difficulty formula (all in `core/Difficulty.kt`, constants in `GameConfig.kt`)

Units: 1 world unit = 1 lane width. `t` = seconds since the run started.

```
speed(t) = V_MIN + (V_MAX − V_MIN)·(1 − e^(−t/τs))      V_MIN=6, V_MAX=12 u/s, τs=75 s   (capped ≤ V_MAX)
level(t) = 0                               for t ≤ 15 s (grace period)
         = min(0.95, 1 − e^(−(t−15)/τd))   otherwise, τd = 75 s
gap(L)   = 6.5 + (2.6 − 6.5)·L             free road between rows (units), × U(1, 1.45) jitter
p2(L)    = clamp(0.9·(L − 0.2), 0, 0.6)    chance that a row blocks 2 lanes (never 3)
kind weights (L): car .38, barrier .30, oil .10+.12L, truck .06+.22L
```

* All of these are monotonic (non-decreasing, or non-increasing for gap) and capped. Unit tests
  sweep 0 to 7200 s.
* **The first 15 s are forgiving.** Level is 0, so rows are as far apart as they get, every row
  blocks only one lane, and only cars and barriers appear (no oil, no trucks). The first row
  appears about 2 s in.
* HUD speed is `speed × 18` km/h (108 km/h at the start, 216 km/h at the cap).

## 4. Spawner fairness (why patterns are always passable)

1. Rows never overlap in z. Each new row starts beyond `horizon`, which is the furthest z any
   earlier obstacle can occupy *at the moment the player reaches it*. For a moving vehicle that is
   its worst-case forward drift: `u·D/(v−u)` at the current, lowest possible, player speed.
2. A row blocks at most 2 of the 3 lanes.
3. `shifts` is the number of full lane changes that fit in the empty gap at an upper bound on the
   speed at arrival (speed only increases, so arrival time ≤ distance / current speed). The new
   row's blocked lanes are chosen so that **from every lane that was free in the previous row**,
   some free lane is reachable within `shifts` changes. If no layout qualifies, the row is empty.
4. Oil counts as "blocked" for these guarantees, so a clean line never needs to cross oil.

The fuzz test checks this with an **independent** validator. It runs a space-time reachability
search over the exactly simulated trajectory, at 120 Hz, with exact moving-obstacle positions, the
real hitboxes and the full 120 ms lane-change animation (no early chaining, so it is conservative).
It runs once from each start lane, over 10,000 seeds × 12 rows, spread over grace, ramp-up and
capped difficulty. A mutation test confirms the validator does reject walls and rows that are too
close together.

## 5. Gameplay interpretations / assumptions

* **Lane change**: 120 ms ease-out cubic. Inputs go into an 8-slot ring buffer. A buffered input
  starts once the current change is at least 50 % done. Inputs older than 350 ms are dropped, so a
  stale tap doesn't fire long after an oil lock ends.
* **Tap vs swipe**: a tap fires on finger-up, using the screen half. A horizontal move beyond 28 dp
  fires a swipe immediately, and that gesture then produces no tap. Multi-touch taps are tracked
  per pointer. Trade-off: a tap waits for finger-up (typically 50 to 90 ms) so a swipe that starts
  on the "wrong" half doesn't trigger a tap first.
* **Near miss** ("within a lane-width margin"): while the player and a solid obstacle overlap in
  z, the minimum edge-to-edge lateral gap is tracked. If it is below 0.5 lane widths when the
  obstacle passes, that counts as a near miss. In practice this means "an obstacle in the adjacent
  lane"; two lanes away never counts. Below 0.2 it is a "close call" worth double. Base 25 points
  × combo (up to ×5, 2.5 s window).
* **Score** = 2 points per unit of distance + coins (10 each) + near-miss bonuses.
* **Oil slick**: not lethal; locks steering for 0.6 s.
* **Shield**: absorbs one solid hit (destroys that obstacle, then 0.6 s invulnerability), lasts 5 s.
  It does not cancel oil. It spawns every 25 to 40 s (first one at about 20 s) on the safe lane.
* **Coins** appear as short trails on the guaranteed-safe lane, which also guides new players.
* **Day/night**: 120 s cycle. Day turns to night at about 60 s and back at about 120 s, with a
  6 s eased transition. At night: darker palette, headlight cones, tail-light glows, lamp glows,
  blinking barrier lights.
* **Parallax**: ground details scroll at 1.0×, trees and street lamps at 1.12× (taller, so closer
  to the camera), clouds at 1.6× and only by day.
* **Retry** resets pools in place (no allocation, about 0 ms) and starts immediately, unless an
  interstitial is due (see below).

## 6. Ads

* IDs live **only** in `app/ads.properties` (Google's public test IDs). Gradle injects them into
  the manifest placeholder and `BuildConfig`.
* **Interstitial**: shown only when the player leaves the game-over screen (Retry or Menu) or quits
  from the pause menu, never during gameplay. Rules from `AdGate`: never during the first 3
  completed runs of a session; at most once per 4 completed runs; at least 3 minutes after the
  previous one. A session is the process lifetime, so it survives activity recreation but not
  process death. When an interstitial is due, Retry starts as soon as the ad closes.
* **Rewarded "Continue"**: offered on game over only if one is loaded and continue hasn't been used
  this run. On reward, the road ahead is cleared and the player gets 2 s of invulnerability.
* **Failure handling**: every SDK call is wrapped. Loads retry with exponential backoff (15 s up to
  5 min). If no ad is loaded, nothing is shown and the game simply continues. No banners.
* **UMP**: consent info is requested on launch, and the form is shown if required (menu screen
  only). Ads initialise only when `canRequestAds()`. Settings shows "Ad privacy options" when UMP
  reports it is required, plus a placeholder privacy-policy link.

## 7. Lifecycle

* Portrait is locked in the manifest, and `configChanges` covers rotation, size and uiMode, so no
  recreation.
* `onPause` (screen off, home, call) pauses the run and stops the Choreographer loop and audio.
  Losing window focus during play (notification shade) also pauses.
* After recreation or process death the app returns to the main menu. Best score is persisted
  as soon as the crash sequence ends, and run count when a run completes. An in-progress run is not
  serialised: restoring a half-finished endless run is low value, and pausing on background already
  covers the common case.
* Persistence: SharedPreferences (`best`, `sound`, `vibration`, `total_runs`). It is backed up via
  the backup rules.

## 8. Verification status and known limitations

* **Verified headlessly**: 35 core JVM tests, plus 14 Robolectric tests (run for both debug and
  release variants). The Robolectric tests cover screen flow, touch input, continue-once,
  interstitial caps, offline/no-fill, pause on background, recreation, process death and
  persistence. Real frames were rendered with Robolectric native graphics and saved to
  `app/build/screenshots/`. Lint reports 0 errors.
* **Not verified on a device or emulator.** The build container has no KVM, so no emulator could
  run. Real-device frame pacing (60 fps), audio output, haptics, live AdMob test-ad display and the
  UMP form rendering are therefore unverified.
* Renderer per-frame allocation-freedom is by construction and code review. Only the core
  simulation is *measured* allocation-free (Robolectric's Canvas shadows make measuring the
  renderer meaningless).
* The tests disable the real Choreographer loop (`GameView.loopEnabled = false`) and step frames
  with `advance(dt)`, because Robolectric delivers vsync instantly and a self-reposting loop would
  never go idle.
* Remaining lint warnings: `Overdraw`, `NonResizeableActivity` (intentional, portrait-only game),
  `PluralsCandidate` (run-stats string), `UseKtx`.
* An in-progress run is not restored after process death; the app returns to the menu.
