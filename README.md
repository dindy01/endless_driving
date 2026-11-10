# Endless Drive (3D)

An endless third-person driving game for Android: low-poly 3D, a chase camera, analog steering
(touch or tilt), traffic, near-miss combos, boost, and a cycle of day, dusk, night and rain.
Everything is generated in code: models, road, scenery and sound.

The stack, architecture, difficulty formula, fairness guarantee and the assumptions made are in
[DECISIONS.md](DECISIONS.md).

## Build: one command

Requirements:
* JDK 17+
* An Android SDK with `platforms;android-36` and `build-tools;35.0.0`. Set it via
  `ANDROID_HOME`, or put `sdk.dir=...` in `local.properties`.
* Network access to Google Maven and Maven Central (or the Google mirror of Central listed in
  `settings.gradle.kts`).

```bash
./gradlew build
```

This runs every test (core, render, Robolectric), runs lint, and builds:

| Output | Path |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK (R8-minified, resources shrunk, **unsigned**) | `app/build/outputs/apk/release/app-release-unsigned.apk` |

Release AAB: `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.

One-time SDK setup on a fresh Linux box:

```bash
mkdir -p $HOME/android-sdk/cmdline-tools && cd $HOME/android-sdk/cmdline-tools
curl -sO https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q commandlinetools-linux-*_latest.zip && mv cmdline-tools latest
yes | latest/bin/sdkmanager --licenses
latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
export ANDROID_HOME=$HOME/android-sdk
```

### Optional: headless 3D render tests

`render/src/test/.../RenderTest.kt` renders real frames through the production shaders with an
OpenGL ES 3 context created via EGL on Mesa's software rasteriser. It is **skipped** (not failed)
when those libraries are missing. To enable it on Debian/Ubuntu:

```bash
sudo apt-get install -y libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri
./gradlew :render:test              # PNGs in render/build/screenshots/
./gradlew :app:testDebugUnitTest    # UI layers in app/build/screenshots/
python3 tools/composite.py          # full-screen previews in build/previews/ (needs Pillow)
```

## Run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.endlessdrive.game/.MainActivity
```

The device needs OpenGL ES 3.0 (virtually every Android 6+ phone has it).

**Controls:**
* **Touch (default):** drag anywhere on the left side to steer. Hold **BRAKE**, tap **BOOST**
  (8 s cooldown).
* **Tilt:** Settings → Controls. Rotate the phone like a steering wheel.
* Graphics quality (Low/Medium/High), sound and vibration are in Settings.

## Ads

All AdMob IDs are in **`app/ads.properties`**. They are Google's test IDs, so replace them before
publishing; the privacy-policy URL there is a placeholder too. The game is fully playable offline
or with ads blocked.

## Signing a release

```bash
$ANDROID_HOME/build-tools/35.0.0/zipalign -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks my.keystore --out endless-drive.apk aligned.apk
```

## Layout

```
core/    game rules (pure Kotlin) + tests: traffic fuzz (10,000 patterns), collisions,
         difficulty, scoring/near-miss, steering, session state machine, ad gating,
         zero-allocation check
render/  OpenGL ES 3 renderer (pure Kotlin behind a GL interface) + headless render tests
app/     Android: GL surface + game loop, HUD/controls, tilt, screens, ads/UMP, audio, prefs
tools/   composite.py (preview images)
```

## Verification

See the final report in the session, and DECISIONS.md §12. Nothing has been run on a real
device or emulator.
