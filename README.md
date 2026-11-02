# Clock

Offline clock for Android (Kotlin, Jetpack Compose, AlarmManager, AppWidget). Package: `com.mux.clock`

Alarm, timer, world clock, stopwatch and three home screen widgets (digital, analog, world).

## Build
Push to GitHub -> Actions -> "Build APK" -> download artifact `Clock-debug-apk`.

## Structure
- `Hud.kt`        chamfered-corner HUD design system (panels, tags, buttons)
- `Data.kt`       settings, alarms, timers and cities (stored on the device), formatting helpers
- `Alarms.kt`     alarm and timer scheduling, ringing service, lock-screen ring activity, boot receiver
- `Stopwatch.kt`  stopwatch that keeps running while the app is closed
- `Widgets.kt`    digital, analog and world clock widgets
- `Screens.kt`    the five screens and dialogs, `MainActivity.kt` navigation

## Release (in-app updates)
1. Bump `appVersionName` in `app/build.gradle.kts` (and `version` in `metadata.json`).
2. Build, then create a GitHub Release tagged with the same number (e.g. `1.1.0`) and upload the APK.
3. Installed apps check `releases/latest` on open and offer the update.

## Before publishing
Edit `DEV_NAME`, `DEV_EMAIL`, `GITHUB_USER` and `REPO` in `app/src/main/java/com/mux/clock/Update.kt`,
and `author` in `metadata.json`. Replace the images in `public/screenshots` with real screenshots from your phone.
