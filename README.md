# AR Trace

Free, offline, native Android app (Kotlin, CameraX). Min Android 10.

- Pick any image and float it over the live camera: drag with one finger, pinch to scale, twist to rotate.
- Opacity slider, Lock (so it can't move), Mirror, Fit, and an Outline mode (turns a photo into green edge lines for easier tracing).
- Photo, Video (with sound), and Time-lapse capture. A time-lapse takes one frame every 1s / 2s / 5s / 10s / 30s / 1min and encodes them to an MP4 at 30 fps.
- Everything is saved on the phone only: Pictures/ARTrace and Movies/ARTrace. No internet permission, no accounts, no ads.

## Get the APK

**Option A - GitHub (no Android Studio needed)**
1. Create a new GitHub repo and upload this whole folder (keep `.github/workflows/build.yml`).
2. Open the repo's Actions tab, run "Build APK" (it also runs on every push).
3. When it finishes, download the `ARTrace-debug-apk` artifact, unzip, and install `app-debug.apk`.

**Option B - Android Studio**
Open this folder, wait for Gradle sync, press Run (or Build > Build APK).
