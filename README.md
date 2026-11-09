# Arka

**Wallpapers in Motion.**

Arka is an Android wallpaper library for static images and animated wallpapers. It keeps imported files in app-private storage and provides a small, curated online catalogue with an offline cache.

## Features

### Foundation
- Kotlin, Jetpack Compose and Material 3.
- Fixed dark theme with amber accents.
- Import JPG, PNG, WebP, GIF and MP4 through Android's document picker.
- App-private media and thumbnail storage; no broad storage permission.
- Import size, decoded dimensions, GIF animation and video-duration checks.
- Search, favourites, categories, media-type filters and generated presets.
- Eight locally generated preinstalled wallpapers are available without an internet connection.

### Wallpaper engine
- Static wallpaper application to Home, Lock or Both (subject to Android/OEM support).
- MP4 live wallpapers through AndroidX Media3 ExoPlayer.
- GIF live wallpapers through AnimatedImageDrawable on Android 9+ and a compatibility decoder on Android 8/8.1.
- Playback starts only while the wallpaper engine is visible.
- Playback pauses while the wallpaper is not visible; the video decoder is retained across surface recreation and released when the engine is destroyed.
- Native Android live-wallpaper preview and confirmation.

### Discover and downloads
- Catalogue metadata is served from `catalog/catalogue.json` on this repository's raw GitHub endpoint.
- Catalogue data is cached locally; the last fetched catalogue remains available offline.
- Online search combines Openverse and Wikimedia Commons results, with creator and licence shown when provided.
- Downloads use HTTPS only, enforce an HTTPS host allowlist across redirects, apply byte limits and pass through the same media validation as local imports.
- Catalogue preview and download failures do not affect the local library.

## Video decoding fallback

MP4 playback first uses AndroidX Media3 and the device MediaCodec decoders. If those fail to initialize or render a frame, Arka uses the GPL-3.0 FFmpegKit Android build to decode the video into a temporary, muted animated GIF in app cache, then renders it through the existing GIF wallpaper path. The fallback is capped at 30 seconds and 10 frames per second to limit CPU and storage use. The temporary conversion is deleted when playback stops.

FFmpegKit Android is provided by the independent [ffmpegkit-maintained/ffmpeg](https://github.com/ffmpegkit-maintained/ffmpeg) project, using the pinned `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9` artifact. It is not affiliated with the original FFmpegKit authors. See the dependency's bundled third-party notices and upstream source for component-level licensing.

## Build

Open the repository in Android Studio and sync Gradle. The project targets Android 8.0 (API 26), compiles against SDK 36 and uses Java 17.

GitHub Actions builds a debug APK on pushes and pull requests to `main`.

## Security and privacy

- No account, analytics, advertising SDK or telemetry.
- No broad storage permissions.
- App backups are disabled; imported wallpaper files remain in app-private storage.
- Cleartext traffic is disabled. Network access is used only for the read-only catalogue and allowlisted Openverse/Wikimedia media.
- Catalogue size, download size, media dimensions and video duration are bounded.
- Imported and downloaded files are validated before being made visible in the library.
- Wallpaper paths are checked against the app's private wallpaper directory before use.

## License

Arka is distributed under the GNU General Public License v3.0 only. See [LICENSE](LICENSE). Third-party dependencies and media retain their respective licenses; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Known validation limits

- Android CI confirms that the debug APK compiles; it does not replace testing on physical devices.
- Android 8/8.1 uses the platform's legacy GIF decoder.
- Home/Lock screen behaviour, frame pacing, memory use and visibility callbacks still need device testing across OEM launchers.
- The catalogue is intentionally small; its contents can be extended by updating the JSON file while keeping the documented URL and licence requirements.
