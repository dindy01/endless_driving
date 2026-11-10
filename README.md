# Endless Drive

A top-down, 3-lane endless driving game for Android, written in Kotlin. Visuals are drawn
procedurally on Canvas and sound is synthesised at runtime, so the app has no art or audio assets.

* Tap the left or right half of the screen, or swipe, to change lanes.
* Avoid slow cars, barriers, trucks and oil slicks (oil locks your steering briefly).
* Collect coins. A shield absorbs one hit and lasts 5 s.
* Pass close to traffic for near-miss bonuses and combos.

See [DECISIONS.md](DECISIONS.md) for the stack choice, architecture, difficulty formula and the
assumptions made.

## Requirements

* JDK 17 or newer (built with JDK 21)
* Android SDK with `platforms;android-36` and `build-tools;35.0.0` (AGP downloads missing
  build-tools automatically if licences are accepted)
* Network access to `dl.google.com` / `maven.google.com` and Maven Central (or its Google mirror)

Point Gradle at the SDK with `ANDROID_HOME=/path/to/sdk` or a `local.properties` file containing
`sdk.dir=/path/to/sdk`.

```bash
# one-time SDK setup from scratch (Linux)
mkdir -p $HOME/android-sdk/cmdline-tools && cd $HOME/android-sdk/cmdline-tools
curl -sO https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q commandlinetools-linux-*_latest.zip && mv cmdline-tools latest
yes | latest/bin/sdkmanager --licenses
latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
export ANDROID_HOME=$HOME/android-sdk
```

## Build (one command)

```bash
./gradlew build
```

This runs all unit tests (core and Robolectric) and Android lint, and produces:

| Output | Path |
|---|---|
| Debug APK (installable) | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK (minified, **unsigned**) | `app/build/outputs/apk/release/app-release-unsigned.apk` |

Other useful tasks:

```bash
./gradlew :app:assembleDebug        # just the debug APK
./gradlew :app:bundleRelease        # release AAB (unsigned)
./gradlew :core:test                # pure game-logic tests (fast, no Android)
./gradlew :app:testDebugUnitTest    # Robolectric UI/lifecycle tests + screenshots
./gradlew :app:lintDebug            # lint report: app/build/reports/lint-results-debug.html
```

Rendered screenshots from the Robolectric run end up in `app/build/screenshots/`.

## Run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.endlessdrive.game/.MainActivity
```

## Signing a release

```bash
$ANDROID_HOME/build-tools/35.0.0/zipalign -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks my.keystore --out endless-drive.apk aligned.apk
```

## Ads

All AdMob identifiers live in **`app/ads.properties`**. They are Google's test IDs, so replace them
before publishing. The privacy-policy URL is also there and is currently a placeholder. The game is
fully playable offline or with ads blocked.

## Project layout

```
core/  pure Kotlin game logic + JVM unit tests (spawner fuzz, collision, difficulty, scoring,
       lane input, ad gating, zero-allocation check)
app/   Android: GameView (loop/input), Renderer (Canvas), MainActivity (screens), Sfx, Haptics,
       Prefs, ads/ (AdService, AdMobService + UMP)
```
