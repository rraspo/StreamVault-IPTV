# CLAUDE.md — StreamVault-VLC (libVLC engine fork)

Fork of [Davidona/StreamVault-IPTV](https://github.com/Davidona/StreamVault-IPTV) that
swaps the ExoPlayer/Media3 playback engine for **libVLC** (VLC's ffmpeg-based engine),
keeping StreamVault's UI. Upstream is tracked as the `upstream` remote; work happens on
this fork's `master` (pushed to `origin` = rraspo/StreamVault-IPTV).

This file is the living spec. Keep it current when behavior changes.

## Why libVLC

The upstream ExoPlayer/Media3 engine fails on raw MPEG-TS live streams from
Xtream-Codes-style providers (played through a local proxy so clients hold no provider
credentials): extractor-mode bugs, ~2-minute channel opens on the normal mid-GOP/no-SPS
live tune-in, and 50/60fps channels playing at 0.5x. The same URLs open in 1–4s and play
60fps correctly in ffmpeg/VLC — the engine is the ceiling, not the streams. This is
settled; do not re-benchmark or attempt further ExoPlayer extractor patches.

## Architecture

The app talks only to the `PlayerEngine` interface
(`player/src/main/java/com/streamvault/player/PlayerEngine.kt`, ~18 StateFlows +
~45 methods). Engines:

- `Media3PlayerEngine` — upstream implementation, kept intact for A/B comparison.
- `VlcPlayerEngine` (`player/.../vlc/VlcPlayerEngine.kt`) — this fork's engine.

Both are constructed in `app/.../di/NetworkModule.kt` (`@MainPlayerEngine` singleton +
`@AuxiliaryPlayerEngine` fresh-per-injection for preview/multiview). The choice is a
DataStore preference (`PLAYER_USE_VLC_ENGINE`, default **VLC**) surfaced as a toggle in
Settings → Playback; it is read once at engine creation, so switching requires an app
restart (force-stop or reboot the device).

### VlcPlayerEngine scope by phase

- **Phase 1 (done when a channel plays fast at correct speed)**: libVLC
  `Media`/`MediaPlayer`; render views via `VLCVideoLayout`
  (`createRenderView`/`bindRenderView`/`releaseRenderView`/`clearRenderBinding`);
  flows `playbackState`, `isPlaying`, `currentPosition`, `duration`, `videoFormat`,
  `error`, `playerStats` (basics); `prepare`/`play`/`pause`/`stop`; volume/mute;
  playback speed; decoder mode (hw/sw).
- **Phase 2**: audio/subtitle track selection (VLC ES tracks → `PlayerTrack`),
  richer stats overlay values.
- **Phase 3**: error/buffering polish; native fast channel-zap (VLC tolerates mid-GOP
  tune-in, so ExoPlayer-style preload/scrubbing hacks are unnecessary — `preload()`/
  `setScrubbingMode()` stay no-ops).
- **Deliberately stubbed (not used for live IPTV)**: DVR/timeshift, DRM, a/v-offset
  sync, injected Media3 `Cue` subtitles, live audio tap, learned playback
  compatibility. `media3-common` stays on the classpath because `Cue`/
  `PlaybackException` leak into the `PlayerEngine` interface.

libVLC dependency: `org.videolan.android:libvlc-all` (3.x stable) in the `player`
module. Target device is a Google TV Streamer (MediaTek, Android 14) whose ABI list is
**`armeabi-v7a` only (32-bit)** — confirmed via `getprop ro.product.cpu.abilist`.
`app/build.gradle.kts` `abiFilters` already includes it.

## Build & deploy (containerized — nothing runs on the host)

- `./build.sh` — eclipse-temurin:17-jdk container + Android SDK 36 + Gradle 8.12
  (AGP 8.10.1, Kotlin 2.2.0). Builds `:app:assembleBeta` → `dist/streamvault-vlc-beta.apk`.
  SDK and Gradle caches persist under `~/.cache/android-sdk` and `~/.gradle`.
- `./deploy.sh <streamer-ip>` — containerized adb (alpine + android-tools), installs
  with `-r -g` so app data survives updates. Package: `com.streamvault.app.beta`.
- `keystore/` (gitignored, chmod 600) — stable release signing key reused from the
  earlier ExoPlayer patch-fork, so installs stay `adb install -r` and preserve app
  config. **Never commit it; losing it means a signature break and config loss.**

The `beta` variant is non-debuggable and non-minified, signed with the release key.

## Conventions

- UI copy stays **English**, matching upstream (project-explicit exception to the
  global Spanish-copy rule).
- No provider or proxy credentials in this repo, in logs, or in commit messages — the
  app is pointed at a LAN Xtream proxy at runtime; secrets live on the box in
  chmod-600 files only.
- Upstream sync: `git fetch upstream && git merge upstream/master` — keep the VLC
  engine additive (new files + the two NetworkModule provider bodies + settings toggle
  plumbing) so merges stay cheap.

## Test channels (acceptance for engine work)

On the target device, via the LAN proxy:
- stream **1** — 1080p60; played at 0.5x under ExoPlayer; must play at correct speed.
- streams **73** and **77** — mid-GOP/no-SPS tune-in; ExoPlayer took ~2 min to open,
  ffmpeg/VLC take 1–4s; must open in a few seconds.
- any normal 30fps channel — regression check.
