package com.iptv.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.iptv.tv.data.MAG_USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun clock(ms: Long): String {
  val s = (ms / 1000).coerceAtLeast(0)
  val h = s / 3600
  val m = (s / 60) % 60
  val sec = s % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Movie / episode player: ◀ ▶ seek, OK pause, position saved every 5 s for resume, auto-next episode. */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayer(vm: AppViewModel, play: VodPlay) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val exo = remember {
    val http = DefaultHttpDataSource.Factory().setUserAgent(MAG_USER_AGENT).setAllowCrossProtocolRedirects(true)
    ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
  }
  val current by rememberUpdatedState(play)
  var error by remember { mutableStateOf<String?>(null) }
  var osdUntil by remember { mutableLongStateOf(0L) }
  var tick by remember { mutableIntStateOf(0) }
  var pos by remember { mutableLongStateOf(0L) }
  var dur by remember { mutableLongStateOf(0L) }
  var paused by remember { mutableStateOf(false) }
  var loading by remember { mutableStateOf(true) }
  val focus = remember { FocusRequester() }
  val showOsd = { osdUntil = System.currentTimeMillis() + 4000; tick++ }

  fun save() { if (exo.duration > 0) vm.recordVod(current, exo.currentPosition, exo.duration) }
  fun close(finished: Boolean) { if (!finished) save(); vm.closeVod(finished) }

  DisposableEffect(Unit) {
    val listener = object : Player.Listener {
      override fun onPlayerError(e: PlaybackException) { error = "Playback error: ${e.errorCodeName}"; osdUntil = Long.MAX_VALUE; tick++ }
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        paused = !isPlaying && exo.playbackState == Player.STATE_READY && !exo.playWhenReady
        if (isPlaying) showOsd()
      }
      override fun onPlaybackStateChanged(state: Int) {
        loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
        if (state == Player.STATE_ENDED) scope.launch {
          vm.recordVod(current, exo.duration, exo.duration)
          if (!vm.nextEpisode()) close(finished = true)
        }
      }
    }
    exo.addListener(listener)
    onDispose { exo.removeListener(listener); exo.release() }
  }

  // Load whenever the title/episode changes (auto-next or next-episode key).
  LaunchedEffect(play.key) {
    error = null
    loading = true
    showOsd()
    exo.stop()
    try {
      val url = vm.vodLink(play)
      exo.setMediaItem(MediaItem.Builder().setUri(url).apply {
        if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
      }.build(), play.resumeAt)
      exo.prepare()
      exo.playWhenReady = true
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
      error = e.message ?: "Could not start playback"
      osdUntil = Long.MAX_VALUE
    }
  }

  // Overlay clock + periodic save for resume / Continue Watching.
  LaunchedEffect(Unit) {
    var n = 0
    while (true) {
      pos = exo.currentPosition
      dur = exo.duration.coerceAtLeast(0)
      if (++n % 10 == 0 && exo.isPlaying) save()
      delay(500)
    }
  }

  LaunchedEffect(tick) {
    val wait = osdUntil - System.currentTimeMillis()
    if (wait in 1..60_000) { delay(wait); tick++ }
  }
  LaunchedEffect(Unit) { focus.requestFocus() }
  BackHandler { close(finished = false) }

  fun seek(deltaMs: Long) {
    exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0, exo.duration.coerceAtLeast(0)))
    showOsd()
  }
  fun toggle() {
    if (exo.playWhenReady) exo.pause() else exo.play()
    paused = !exo.playWhenReady
    showOsd()
  }
  fun next() { scope.launch { save(); vm.nextEpisode() } }

  Box(
    Modifier.fillMaxSize().background(Color.Black)
      .focusRequester(focus)
      .focusable()
      .onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        val held = e.nativeKeyEvent.repeatCount > 0 // holding ◀ ▶ skips faster
        when (e.key.nativeKeyCode) {
          AndroidKeyEvent.KEYCODE_DPAD_LEFT -> seek(if (held) -30_000 else -10_000)
          AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> seek(if (held) 30_000 else 10_000)
          AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seek(-30_000)
          AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(30_000)
          AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
          AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> toggle()
          AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (!exo.playWhenReady) toggle()
          AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (exo.playWhenReady) toggle()
          AndroidKeyEvent.KEYCODE_MEDIA_NEXT, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> next()
          AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_INFO -> showOsd()
          else -> return@onKeyEvent false
        }
        true
      },
  ) {
    AndroidView(
      factory = {
        PlayerView(it).apply {
          useController = false
          player = exo
          keepScreenOn = true
          isFocusable = false
          resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
    if (loading && error == null) {
      Text("Loading…", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
    }

    val visible = remember(tick, paused) { paused || System.currentTimeMillis() < osdUntil }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
      Column(
        Modifier.fillMaxWidth()
          .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
          .padding(start = 40.dp, end = 40.dp, top = 70.dp, bottom = 26.dp),
      ) {
        if (paused) Text("❚❚  Paused", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(play.item.name, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val ep = play.episode
        when {
          error != null -> Text(error!!, fontSize = 14.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 4.dp))
          ep != null -> Text("S${play.season?.number}:E${ep.number}  ·  ${ep.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE6E6EA), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        ProgressLine(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
          val nextEp = play.episodes?.getOrNull(play.index + 1)
          Text(nextEp?.let { "Next: E${it.number}  ${it.name}" } ?: "", fontSize = 11.sp, color = Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          Text(if (dur > 0) "${clock(pos)} / ${clock(dur)}" else "", fontSize = 11.sp, color = Color.White)
        }
        Text(
          "◀ ▶ skip 10 s (hold for 30 s)  ·  OK pause" + (if (play.episodes != null) "  ·  CH+ next episode" else "") + "  ·  Back to exit",
          fontSize = 10.sp, color = Dim, modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
  }
}
