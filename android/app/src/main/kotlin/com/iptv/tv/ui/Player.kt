package com.iptv.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
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
import com.iptv.tv.data.NowNext
import com.iptv.tv.data.nowNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(UnstableApi::class)
@Composable
fun LivePlayer(vm: AppViewModel, play: LivePlay) {
  val context = LocalContext.current
  val ch = play.list[play.index]
  val exo = remember {
    val http = DefaultHttpDataSource.Factory().setUserAgent(MAG_USER_AGENT).setAllowCrossProtocolRedirects(true)
    ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
  }
  var error by remember { mutableStateOf<String?>(null) }
  var osdUntil by remember { mutableStateOf(0L) }
  var tick by remember { mutableIntStateOf(0) }
  var nn by remember { mutableStateOf<NowNext?>(null) }
  val focus = remember { FocusRequester() }
  val showOsd = { osdUntil = System.currentTimeMillis() + 4000; tick++ }

  DisposableEffect(Unit) {
    val listener = object : Player.Listener {
      override fun onPlayerError(e: PlaybackException) { error = "Stream error: ${e.errorCodeName}"; osdUntil = Long.MAX_VALUE }
      override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) showOsd() }
    }
    exo.addListener(listener)
    onDispose { exo.removeListener(listener); exo.release() }
  }

  // Tune whenever the channel changes (Up/Down zapping).
  LaunchedEffect(ch.id) {
    error = null
    nn = null
    showOsd()
    vm.markWatched(ch)
    exo.stop()
    try {
      val url = vm.liveLink(ch)
      exo.setMediaItem(MediaItem.Builder().setUri(url).apply {
        if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
      }.build())
      exo.prepare()
      exo.playWhenReady = true
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
      error = e.message ?: "Could not start channel"
      osdUntil = Long.MAX_VALUE
    }
    while (true) { // refresh now/next every 30 s
      nn = vm.epg(ch.id).nowNext()
      delay(30_000)
    }
  }

  LaunchedEffect(tick) {
    val wait = osdUntil - System.currentTimeMillis()
    if (wait in 1..60_000) { delay(wait); tick++ }
  }
  LaunchedEffect(Unit) { focus.requestFocus() }
  BackHandler { vm.closePlayer() }

  Box(
    Modifier.fillMaxSize().background(Color.Black)
      .focusRequester(focus)
      .focusable()
      .onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (e.key.nativeKeyCode) {
          AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(-1); true }
          AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(1); true }
          AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_INFO,
          AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { showOsd(); true }
          else -> false
        }
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

    val visible = remember(tick) { System.currentTimeMillis() < osdUntil }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
      Column(
        Modifier.fillMaxWidth()
          .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
          .padding(start = 40.dp, end = 40.dp, top = 70.dp, bottom = 26.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Box(Modifier.size(7.dp).background(Accent, CircleShape))
          Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Text(
          (if (ch.number > 0) "${ch.number}  ·  " else "") + parseName(ch.name).first,
          fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.padding(top = 4.dp),
        )
        val now = nn?.now
        when {
          error != null -> Text(error!!, fontSize = 14.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 4.dp))
          now != null -> Text("${now.title}   ${hhmm(now.start)} – ${hhmm(now.end)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE6E6EA), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
          Box(Modifier.fillMaxWidth(nn?.progress ?: 0f).fillMaxHeight().background(Accent))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
          Text(nn?.next?.let { "Next  ${hhmm(it.start)}  ${it.title}" } ?: "", fontSize = 11.sp, color = Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          Text("▲ ▼ change channel  ·  Back to exit", fontSize = 10.sp, color = Dim)
        }
      }
    }
  }
}

private fun hhmm(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
