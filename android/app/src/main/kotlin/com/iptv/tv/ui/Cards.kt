package com.iptv.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.data.Channel

val Bg = Color(0xFF0B0B0F)
val Accent = Color(0xFFE50914)
val Muted = Color(0xFFA3A3AD)
val Dim = Color(0xFF6B6B75)

private val BADGE = Regex("""\s*[\[(|]?\b(8K|4K|UHD|FHD|HD|SD|HEVC|H\.?265|RAW)\b[\])|]?""", RegexOption.IGNORE_CASE)

/** "LOVE NATURE 4K" → ("LOVE NATURE", "4K"): the quality tag becomes a badge. */
fun parseName(name: String): Pair<String, String?> {
  var badge: String? = null
  val title = BADGE.replace(name) { m -> if (badge == null) badge = m.groupValues[1].uppercase(); " " }
    .replace(Regex("\\s+"), " ").trim()
  return (title.ifEmpty { name }) to badge
}

/** Stable colour per channel (the portal has no logos). Same hash as the web player. */
fun hue(s: String): Float {
  var h = 0L
  s.codePoints().forEach { h = (h * 31 + it) and 0xFFFFFFFFL }
  return (h % 360).toFloat()
}

fun mark(title: String) =
  (title.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().split(" ").firstOrNull()?.ifEmpty { null } ?: title).take(4).uppercase()

fun tileBrush(h: Float) = Brush.linearGradient(
  listOf(Color.hsl(h, 0.55f, 0.26f), Color.hsl((h + 35) % 360, 0.5f, 0.11f)),
)

val CardShape = RoundedCornerShape(6.dp)

@Composable
fun ChannelCard(
  ch: Channel,
  fav: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onFocus: () -> Unit,
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester? = null,
  progress: Float? = null,
  sub: String? = null,
) {
  val (title, badge) = parseName(ch.name)
  Column(modifier.width(TileWidth)) {
    Surface(
      onClick = onClick,
      onLongClick = onLongClick,
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16 / 9f)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { if (it.isFocused) onFocus() },
      shape = ClickableSurfaceDefaults.shape(CardShape),
      scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
      border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CardShape)),
      colors = ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
        contentColor = Color.White, focusedContentColor = Color.White,
      ),
    ) {
      Box(Modifier.fillMaxSize().background(tileBrush(hue(ch.name)))) {
        Text(
          mark(title), fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.1f),
          maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-10).dp),
        )
        if (ch.number > 0) {
          Text("${ch.number}", fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(8.dp, 6.dp))
        }
        if (fav) Text("★", fontSize = 12.sp, color = Color(0xFFFFCF33), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp, 4.dp))
        Row(Modifier.align(Alignment.BottomStart).padding(10.dp, 8.dp), verticalAlignment = Alignment.Bottom) {
          Text(
            title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 13.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
          )
          if (badge != null) Badge(badge)
        }
        if (progress != null) {
          Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent))
          }
        }
      }
    }
    if (sub != null) {
      Text(sub, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
  }
}

@Composable
fun Badge(text: String) {
  Text(
    text, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White,
    modifier = Modifier
      .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
      .padding(horizontal = 4.dp, vertical = 1.dp),
  )
}

val TileWidth = 168.dp
