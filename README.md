# Kosmos

**Wallpapers in Motion.**

Kosmos is an offline-first Android wallpaper app for static images and animated wallpapers.

## Current implementation

### Foundation
- Kotlin, Jetpack Compose, and Material 3.
- Dark and light themes, with the selected theme saved locally.
- Local library stored in app-private storage.
- Import through Android's system document picker; no broad storage permission.
- Supported imports: JPG, PNG, WebP, GIF, and MP4.
- File-size, media-dimension, and video-duration validation.
- Generated, downscaled thumbnails for library previews.
- Android GitHub Actions build workflow.

### Wallpaper engine
- Static image application to Home, Lock, or Both (subject to Android/OEM support).
- MP4 live wallpapers using AndroidX Media3 ExoPlayer.
- GIF live wallpapers using AnimatedImageDrawable on Android 9+ and a compatibility decoder on Android 8/8.1.
- Live playback starts only while the wallpaper engine is visible.
- Playback, frame callbacks, and pending GIF decoding are stopped or released when the wallpaper becomes invisible, its surface is destroyed, or its engine is destroyed.
- Live wallpaper setup uses Android's native preview and confirmation screen.

## Build

Open the repository in Android Studio and sync the Gradle project. The project targets Android 8.0 (API 26), compiles against SDK 36, and uses Java 17.

GitHub Actions builds a debug APK on pushes and pull requests to `main`.

## Privacy

Kosmos does not require an account. Imported media stays in app-private storage. The current implementation does not request network access, broad storage access, analytics, or advertising permissions.

## Current limitations

- GIF compatibility rendering on Android 8/8.1 uses the platform's legacy GIF decoder.
- Device-specific wallpaper preview and Home/Lock behaviour still need testing across Android versions and OEM launchers.
- Built-in presets, remote catalogue, and download management belong to later milestones.
