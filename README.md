# Kosmos

**Wallpapers in Motion.**

Kosmos is a privacy-first, offline-first Android wallpaper app for static images and live wallpapers.

## Product direction

- Curated built-in presets and an optional online catalogue served as static JSON + CDN.
- Import JPG, PNG, and WebP images (first implementation milestone).
- Planned animated formats: MP4 and GIF.
- Preview wallpapers before applying.
- Apply static wallpapers to Home, Lock, or Both where Android supports it.
- Use Android's native confirmation flow for live wallpapers.
- Keep downloaded and imported media available offline.
- Stop live rendering and release playback resources when the wallpaper is not visible.
- No account, ads, analytics, or unnecessary permissions.

## Technology

- Kotlin and Jetpack Compose
- AndroidX Media3 (planned for video playback)
- Android WallpaperManager / WallpaperService
- Room, DataStore, and WorkManager (planned)

## Current implementation

The initial Android project is in place. The first milestone currently includes:

- Compose application shell with dark-first and light themes.
- Local image import through Android's system document picker.
- App-private image storage with file-size and image-dimension validation.
- Image preview.
- Static wallpaper application to Home, Lock, or Both.

Live wallpaper playback, GIF support, persistent database-backed library features, and the remote catalogue are not implemented yet.

## Build

Open the repository in Android Studio and sync the Gradle project. The project targets Android 8.0 (API 26) and uses compile SDK 36 with Java 17.

A GitHub Actions workflow builds the debug APK on pushes and pull requests.

## Privacy

Kosmos does not require an account. Imported media is stored in app-private storage. The first milestone does not request network or broad storage permissions. The online catalogue will be optional; downloaded assets will remain usable offline.
