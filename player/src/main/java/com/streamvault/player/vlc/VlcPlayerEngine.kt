package com.streamvault.player.vlc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.media3.common.text.Cue
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.player.LiveAudioTap
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerRetryStatus
import com.streamvault.player.PlayerStats
import com.streamvault.player.PlayerSubtitleStyle
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.TimeshiftConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.Inet4Address
import java.net.InetAddress
import java.util.WeakHashMap

/**
 * PlayerEngine backed by libVLC (VLC's ffmpeg-based engine).
 *
 * Exists because Media3/ExoPlayer cannot handle this fork's raw MPEG-TS live sources
 * (mid-GOP/no-SPS tune-in, 50/60fps mistiming); libVLC opens the same URLs in seconds.
 *
 * Phase 1 scope: live playback (prepare/play/pause/stop), render-view lifecycle,
 * core state flows, volume/mute, playback speed, basic track selection.
 * Deliberately stubbed (unused for live IPTV): timeshift, DRM, a/v-offset sync,
 * injected Media3 cues, live audio tap, learned compatibility, preload/scrubbing
 * (libVLC's own tune-in is fast enough that ExoPlayer-style zap hacks are unnecessary).
 */
class VlcPlayerEngine(
    private val context: Context
) : PlayerEngine {

    companion object {
        private const val TAG = "VlcPlayerEngine"
        private const val NETWORK_CACHING_MS = 1500
        private const val VOLUME_MAX = 100
    }

    // Parity with Media3PlayerEngine's auxiliary-engine knobs so NetworkModule can
    // configure either engine identically. VLC has no MediaSession/audio-focus
    // integration yet, so these only record intent.
    var enableMediaSession: Boolean = true
    var bypassAudioFocus: Boolean = false

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isDisposed = false

    private val engineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var prepareJob: Job? = null

    private var requestedDecoderMode = DecoderMode.AUTO
    private var requestedSurfaceMode = PlayerSurfaceMode.AUTO
    private var lastStreamInfo: StreamInfo? = null
    private var lastVolume: Float = 1f
    private var prepareStartMs = 0L
    private var firstFrameTtffMs = 0L

    private var boundLayout: VLCVideoLayout? = null
    private val layoutUsesTextureView = WeakHashMap<View, Boolean>()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _videoFormat = MutableStateFlow(VideoFormat(0, 0))
    override val videoFormat: StateFlow<VideoFormat> = _videoFormat.asStateFlow()

    private val _error = MutableStateFlow<PlayerError?>(null)
    override val error: Flow<PlayerError?> = _error.asStateFlow()

    private val _retryStatus = MutableStateFlow<PlayerRetryStatus?>(null)
    override val retryStatus: StateFlow<PlayerRetryStatus?> = _retryStatus.asStateFlow()

    private val _playerStats = MutableStateFlow(PlayerStats())
    override val playerStats: StateFlow<PlayerStats> = _playerStats.asStateFlow()

    private val _availableAudioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = _availableAudioTracks.asStateFlow()

    private val _availableSubtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = _availableSubtitleTracks.asStateFlow()

    private val _availableVideoTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableVideoTracks: StateFlow<List<PlayerTrack>> = _availableVideoTracks.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _audioVideoOffsetMs = MutableStateFlow(0)
    override val audioVideoOffsetMs: StateFlow<Int> = _audioVideoOffsetMs.asStateFlow()

    private val _audioVideoSyncEnabled = MutableStateFlow(false)
    override val audioVideoSyncEnabled: StateFlow<Boolean> = _audioVideoSyncEnabled.asStateFlow()

    private val _timeshiftState = MutableStateFlow(LiveTimeshiftState(supported = false))
    override val timeshiftState: StateFlow<LiveTimeshiftState> = _timeshiftState.asStateFlow()

    private val _renderSurfaceType = MutableStateFlow(PlayerRenderSurfaceType.SURFACE_VIEW)
    override val renderSurfaceType: StateFlow<PlayerRenderSurfaceType> = _renderSurfaceType.asStateFlow()

    private val _mediaTitle = MutableStateFlow<String?>(null)
    override val mediaTitle: StateFlow<String?> = _mediaTitle.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _audioFocusDenied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val audioFocusDenied: Flow<Unit> = _audioFocusDenied.asSharedFlow()

    // --- Engine lifecycle -------------------------------------------------------------

    private fun getOrCreateLibVlc(): LibVLC {
        libVlc?.let { return it }
        // No -v/-vv: VLC's verbose log prints full stream URLs (credentials) to logcat.
        val options = arrayListOf(
            "--drop-late-frames",
            "--skip-frames",
            "--audio-time-stretch"
        )
        return LibVLC(context, options).also { libVlc = it }
    }

    private fun getOrCreatePlayer(): MediaPlayer {
        mediaPlayer?.let { return it }
        val player = MediaPlayer(getOrCreateLibVlc())
        player.setEventListener(MediaPlayer.EventListener { event -> onVlcEvent(event) })
        mediaPlayer = player
        boundLayout?.let { attachViewsTo(player, it) }
        return player
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        if (event.type != MediaPlayer.Event.TimeChanged &&
            event.type != MediaPlayer.Event.PositionChanged &&
            event.type != MediaPlayer.Event.Buffering
        ) {
            Log.d(TAG, "vlc event 0x" + Integer.toHexString(event.type))
        }
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                _playbackState.value = PlaybackState.BUFFERING
            }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering < 100f) {
                    if (_playbackState.value != PlaybackState.ERROR) {
                        _playbackState.value = PlaybackState.BUFFERING
                    }
                } else if (mediaPlayer?.isPlaying == true) {
                    _playbackState.value = PlaybackState.READY
                }
            }
            MediaPlayer.Event.Playing -> {
                if (firstFrameTtffMs == 0L && prepareStartMs > 0L) {
                    firstFrameTtffMs = SystemClock.elapsedRealtime() - prepareStartMs
                    Log.i(TAG, "time to playing: ${firstFrameTtffMs}ms")
                }
                _playbackState.value = PlaybackState.READY
                _isPlaying.value = true
                refreshTracks()
                refreshVideoFormat()
                refreshStats()
            }
            MediaPlayer.Event.Paused -> {
                _isPlaying.value = false
            }
            MediaPlayer.Event.Stopped -> {
                _isPlaying.value = false
                if (_playbackState.value != PlaybackState.ERROR) {
                    _playbackState.value = PlaybackState.IDLE
                }
            }
            MediaPlayer.Event.EndReached -> {
                _isPlaying.value = false
                _playbackState.value = PlaybackState.ENDED
            }
            MediaPlayer.Event.EncounteredError -> {
                _isPlaying.value = false
                _playbackState.value = PlaybackState.ERROR
                _error.value = PlayerError.SourceError(
                    "VLC could not play this stream." +
                        (lastStreamInfo?.title?.let { " ($it)" } ?: "")
                )
            }
            MediaPlayer.Event.TimeChanged -> {
                _currentPosition.value = event.timeChanged
            }
            MediaPlayer.Event.LengthChanged -> {
                _duration.value = event.lengthChanged
            }
            MediaPlayer.Event.Vout -> {
                refreshVideoFormat()
                refreshStats()
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                refreshTracks()
                refreshVideoFormat()
            }
        }
    }

    private fun refreshVideoFormat() {
        val track = runCatching { mediaPlayer?.currentVideoTrack }.getOrNull() ?: return
        val fps = if (track.frameRateDen > 0) {
            track.frameRateNum.toFloat() / track.frameRateDen.toFloat()
        } else {
            0f
        }
        _videoFormat.value = VideoFormat(
            width = track.width.coerceAtLeast(0),
            height = track.height.coerceAtLeast(0),
            frameRate = fps.coerceAtLeast(0f),
            bitrate = track.bitrate.coerceAtLeast(0),
            codecV = track.codec,
            codecA = _playerStats.value.audioCodec.takeIf { it != "Unknown" }
        )
    }

    private fun refreshTracks() {
        val player = mediaPlayer ?: return
        runCatching {
            val currentAudio = player.audioTrack
            _availableAudioTracks.value = player.audioTracks.orEmpty()
                .filter { it.id >= 0 }
                .map { desc ->
                    PlayerTrack(
                        id = desc.id.toString(),
                        name = desc.name,
                        language = null,
                        type = TrackType.AUDIO,
                        isSelected = desc.id == currentAudio
                    )
                }
            val currentSpu = player.spuTrack
            _availableSubtitleTracks.value = player.spuTracks.orEmpty()
                .filter { it.id >= 0 }
                .map { desc ->
                    PlayerTrack(
                        id = desc.id.toString(),
                        name = desc.name,
                        language = null,
                        type = TrackType.TEXT,
                        isSelected = desc.id == currentSpu
                    )
                }
            val currentVideo = player.videoTrack
            _availableVideoTracks.value = player.videoTracks.orEmpty()
                .filter { it.id >= 0 }
                .map { desc ->
                    PlayerTrack(
                        id = desc.id.toString(),
                        name = desc.name,
                        language = null,
                        type = TrackType.VIDEO,
                        isSelected = desc.id == currentVideo
                    )
                }
        }.onFailure { Log.w(TAG, "track refresh failed", it) }
    }

    private fun refreshStats() {
        val player = mediaPlayer ?: return
        val videoTrack = runCatching { player.currentVideoTrack }.getOrNull()
        var audioCodec: String? = null
        var lostPictures = 0
        runCatching {
            player.media?.let { media ->
                audioCodec = (0 until media.trackCount)
                    .mapNotNull { media.getTrack(it) }
                    .firstOrNull { it.type == IMedia.Track.Type.Audio }
                    ?.codec
                lostPictures = media.stats?.lostPictures ?: 0
                media.release()
            }
        }
        _playerStats.value = PlayerStats(
            videoCodec = videoTrack?.codec ?: "Unknown",
            audioCodec = audioCodec ?: "Unknown",
            videoDecoderName = "libVLC",
            audioDecoderName = "libVLC",
            activeDecoderPolicy = requestedDecoderMode.name,
            renderSurfaceType = _renderSurfaceType.value.name,
            width = videoTrack?.width ?: 0,
            height = videoTrack?.height ?: 0,
            droppedFrames = lostPictures,
            videoBitrate = videoTrack?.bitrate ?: 0,
            ttffMs = firstFrameTtffMs
        )
    }

    // --- Playback ---------------------------------------------------------------------

    override fun prepare(streamInfo: StreamInfo) {
        if (ensureNotDisposed("prepare")) return
        val player = getOrCreatePlayer()
        runCatching { player.stop() }

        lastStreamInfo = streamInfo
        prepareStartMs = SystemClock.elapsedRealtime()
        firstFrameTtffMs = 0L
        _error.value = null
        _currentPosition.value = 0L
        _duration.value = 0L
        _videoFormat.value = VideoFormat(0, 0)
        _playbackState.value = PlaybackState.BUFFERING

        prepareJob?.cancel()
        prepareJob = engineScope.launch {
            // libVLC's HTTP stack picks the unreachable link-local IPv6 that .local
            // mDNS names also resolve to and never falls back to IPv4, so resolve
            // .local hosts with the Android resolver (which prefers the working
            // route) and hand VLC a numeric-IPv4 URL instead.
            val playbackUrl = withContext(Dispatchers.IO) { resolveLocalHostname(streamInfo.url) }
            if (lastStreamInfo === streamInfo && !isDisposed) {
                startPlayback(player, streamInfo, playbackUrl)
            }
        }
    }

    private fun resolveLocalHostname(url: String): String {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return url
        if (!host.endsWith(".local", ignoreCase = true)) return url
        return runCatching {
            val ipv4 = InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()
                ?: return url
            url.replaceFirst(host, ipv4.hostAddress ?: return url)
        }.getOrElse {
            Log.w(TAG, "mDNS resolution failed for $host, passing hostname to VLC", it)
            url
        }
    }

    private fun startPlayback(player: MediaPlayer, streamInfo: StreamInfo, playbackUrl: String) {
        val media = Media(getOrCreateLibVlc(), android.net.Uri.parse(playbackUrl))
        when (requestedDecoderMode) {
            DecoderMode.AUTO -> media.setHWDecoderEnabled(true, false)
            DecoderMode.HARDWARE -> media.setHWDecoderEnabled(true, true)
            DecoderMode.SOFTWARE,
            DecoderMode.COMPATIBILITY -> media.setHWDecoderEnabled(false, false)
        }
        media.addOption(":network-caching=$NETWORK_CACHING_MS")
        streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let {
            media.addOption(":http-user-agent=$it")
        }
        streamInfo.headers.entries
            .firstOrNull { it.key.equals("referer", ignoreCase = true) }
            ?.let { media.addOption(":http-referrer=${it.value}") }
        if (streamInfo.proxyHost.isNotBlank() && streamInfo.proxyPort != null) {
            media.addOption(":http-proxy=${streamInfo.proxyHost}:${streamInfo.proxyPort}")
        }

        player.media = media
        media.release()
        player.rate = _playbackSpeed.value
        applyVolume()
        player.play()
        val uri = android.net.Uri.parse(playbackUrl)
        Log.i(
            TAG,
            "prepare: playing via libVLC (decoderMode=$requestedDecoderMode " +
                "host=${uri.host}:${uri.port} scheme=${uri.scheme})"
        )
    }

    override fun renewStreamUrl(streamInfo: StreamInfo) {
        if (ensureNotDisposed("renewStreamUrl")) return
        prepare(streamInfo)
    }

    override fun play() {
        mediaPlayer?.play()
    }

    override fun pause() {
        val player = mediaPlayer ?: return
        if (player.isSeekable) {
            player.pause()
        } else {
            // Live MPEG-TS is not pausable; stopping releases the provider connection
            // instead of silently falling behind the live edge.
            player.stop()
            _playbackState.value = PlaybackState.IDLE
        }
        _isPlaying.value = false
    }

    override fun stop() {
        prepareJob?.cancel()
        runCatching { mediaPlayer?.stop() }
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _mediaTitle.value = null
        lastStreamInfo = null
    }

    override fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        if (player.isSeekable) player.setTime(positionMs.coerceAtLeast(0L))
    }

    override fun seekForward(ms: Long) {
        seekTo(_currentPosition.value + ms)
    }

    override fun seekBackward(ms: Long) {
        seekTo(_currentPosition.value - ms)
    }

    // --- Volume / speed ---------------------------------------------------------------

    override fun setVolume(volume: Float) {
        lastVolume = volume.coerceIn(0f, 1f)
        if (!_isMuted.value) applyVolume()
    }

    override fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        applyVolume()
    }

    override fun toggleMute() {
        setMuted(!_isMuted.value)
    }

    private fun applyVolume() {
        val target = if (_isMuted.value) 0 else (lastVolume * VOLUME_MAX).toInt()
        mediaPlayer?.setVolume(target)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val sane = speed.coerceIn(0.25f, 4f)
        _playbackSpeed.value = sane
        mediaPlayer?.rate = sane
    }

    // --- Track selection ----------------------------------------------------------------

    override fun selectAudioTrack(trackId: String) {
        trackId.toIntOrNull()?.let { mediaPlayer?.setAudioTrack(it) }
        refreshTracks()
    }

    override fun selectVideoTrack(trackId: String) {
        trackId.toIntOrNull()?.let { mediaPlayer?.setVideoTrack(it) }
        refreshTracks()
    }

    override fun selectSubtitleTrack(trackId: String?) {
        mediaPlayer?.setSpuTrack(trackId?.toIntOrNull() ?: -1)
        refreshTracks()
    }

    override fun addExternalSubtitle(subtitleUri: android.net.Uri, language: String) {
        runCatching { mediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, subtitleUri, true) }
            .onFailure { Log.w(TAG, "addExternalSubtitle failed", it) }
    }

    // --- Render views -------------------------------------------------------------------

    override fun createRenderView(
        context: Context,
        resizeMode: PlayerSurfaceResizeMode,
        surfaceType: PlayerRenderSurfaceType
    ): View {
        val layout = VLCVideoLayout(context)
        val useTexture = when (surfaceType) {
            PlayerRenderSurfaceType.TEXTURE_VIEW -> true
            PlayerRenderSurfaceType.SURFACE_VIEW -> false
            PlayerRenderSurfaceType.AUTO -> requestedSurfaceMode == PlayerSurfaceMode.TEXTURE_VIEW
        }
        layoutUsesTextureView[layout] = useTexture
        return layout
    }

    override fun bindRenderView(renderView: View, resizeMode: PlayerSurfaceResizeMode) {
        if (ensureNotDisposed("bindRenderView")) return
        val layout = renderView as? VLCVideoLayout ?: run {
            Log.w(TAG, "bindRenderView: not a VLCVideoLayout, ignoring")
            return
        }
        val player = getOrCreatePlayer()
        if (boundLayout !== layout) {
            runCatching { player.detachViews() }
            boundLayout = layout
            attachViewsTo(player, layout)
        }
        applyResizeMode(player, resizeMode)
    }

    private fun attachViewsTo(player: MediaPlayer, layout: VLCVideoLayout) {
        val useTexture = layoutUsesTextureView[layout] ?: false
        runCatching {
            player.attachViews(layout, null, false, useTexture)
            _renderSurfaceType.value = if (useTexture) {
                PlayerRenderSurfaceType.TEXTURE_VIEW
            } else {
                PlayerRenderSurfaceType.SURFACE_VIEW
            }
        }.onFailure { Log.e(TAG, "attachViews failed", it) }
    }

    private fun applyResizeMode(player: MediaPlayer, resizeMode: PlayerSurfaceResizeMode) {
        val scale = when (resizeMode) {
            PlayerSurfaceResizeMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            PlayerSurfaceResizeMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            PlayerSurfaceResizeMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        }
        runCatching { player.videoScale = scale }
    }

    override fun clearRenderBinding() {
        runCatching { mediaPlayer?.detachViews() }
        boundLayout = null
    }

    override fun releaseRenderView(renderView: View) {
        if (boundLayout === renderView) {
            clearRenderBinding()
        }
        layoutUsesTextureView.remove(renderView)
    }

    // --- Modes recorded for future phases (no live-path effect) ------------------------

    override fun setDecoderMode(mode: DecoderMode) {
        requestedDecoderMode = mode
    }

    override fun setSurfaceMode(mode: PlayerSurfaceMode) {
        requestedSurfaceMode = mode
    }

    override fun setPlaybackBufferMode(mode: PlaybackBufferMode) = Unit
    override fun setVodHttpProtocolMode(mode: VodHttpProtocolMode) = Unit
    override fun setMediaSessionEnabled(enabled: Boolean) {
        enableMediaSession = enabled
    }

    override fun setFastRetryOnTransientFailures(enabled: Boolean) = Unit
    override fun setCompatibilityMemoryEnabled(enabled: Boolean) = Unit
    override fun clearLearnedPlaybackCompatibility() = Unit
    override fun setPreferredAudioLanguage(languageTag: String?) = Unit
    override fun setSubtitleStyle(style: PlayerSubtitleStyle) = Unit
    override fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) = Unit
    override fun setAudioOutputPreference(preference: AudioOutputPreference) = Unit

    // --- Stubs: not used for live IPTV (see CLAUDE.md) ----------------------------------

    override fun setAudioVideoSyncEnabled(enabled: Boolean) {
        _audioVideoSyncEnabled.value = false
    }

    override fun setAudioVideoOffsetMs(offsetMs: Int) = Unit

    override fun startLiveTimeshift(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) {
        _timeshiftState.value = LiveTimeshiftState(
            enabled = config.enabled,
            supported = false,
            message = "Timeshift is not available with the VLC engine yet."
        )
    }

    override fun stopLiveTimeshift() {
        _timeshiftState.value = LiveTimeshiftState(supported = false)
    }

    override fun seekToLiveEdge() = Unit
    override fun pauseTimeshift() = Unit
    override fun resumeTimeshift() = Unit

    override fun setInjectedSubtitleCues(cues: List<Cue>) = Unit
    override fun clearInjectedSubtitleCues() = Unit
    override fun setLiveAudioTap(tap: LiveAudioTap?) = Unit

    override fun setScrubbingMode(enabled: Boolean) = Unit
    override fun preload(streamInfo: StreamInfo?) = Unit

    // --- Teardown -----------------------------------------------------------------------

    override fun release() {
        if (isDisposed) return
        isDisposed = true
        engineScope.cancel()
        teardownPlayer()
        libVlc?.release()
        libVlc = null
    }

    override fun resetForReuse() {
        if (isDisposed) {
            Log.w(TAG, "resetForReuse ignored after terminal release")
            return
        }
        teardownPlayer()
        resetFlows()
    }

    private fun teardownPlayer() {
        prepareJob?.cancel()
        prepareJob = null
        val player = mediaPlayer ?: return
        mediaPlayer = null
        runCatching {
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
        }.onFailure { Log.w(TAG, "player teardown failed", it) }
        boundLayout = null
    }

    private fun resetFlows() {
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _videoFormat.value = VideoFormat(0, 0)
        _error.value = null
        _retryStatus.value = null
        _playerStats.value = PlayerStats()
        _availableAudioTracks.value = emptyList()
        _availableSubtitleTracks.value = emptyList()
        _availableVideoTracks.value = emptyList()
        _mediaTitle.value = null
        lastStreamInfo = null
        prepareStartMs = 0L
        firstFrameTtffMs = 0L
    }

    private fun ensureNotDisposed(operation: String): Boolean {
        if (isDisposed) {
            Log.w(TAG, "$operation ignored after terminal release")
        }
        return isDisposed
    }
}
