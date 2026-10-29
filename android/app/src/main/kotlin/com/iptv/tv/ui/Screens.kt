package com.iptv.tv.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.data.Channel
import com.iptv.tv.data.NowNext
import com.iptv.tv.data.nowNext
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

const val ROW_SIZE = 40
private val Gutter = 40.dp

private fun hhmm(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

// ---------- Top navigation ----------

@Composable
fun TopNav(vm: AppViewModel) {
  Row(
    Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 18.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text("IPTV", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 18.dp))
    Tab.entries.forEach { t ->
      val selected = vm.tab == t
      Surface(
        onClick = { if (vm.tab != t) { vm.tab = t; vm.focusKey = null } },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
          containerColor = Color.Transparent, focusedContainerColor = Color.White,
          contentColor = if (selected) Color.White else Muted, focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
      ) {
        Text(t.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
      }
    }
  }
}

// ---------- Home ----------

private data class RowSpec(val key: String, val title: String, val channels: List<Channel>, val recent: Boolean = false, val genre: String? = null)

@Composable
fun HomeScreen(vm: AppViewModel, restore: FocusRequester) {
  val rows = buildList {
    vm.recentChannels.takeIf { it.isNotEmpty() }?.let { add(RowSpec("cw", "Continue Watching", it, recent = true)) }
    vm.favChannels.takeIf { it.isNotEmpty() }?.let { add(RowSpec("fav", "My Channels", it)) }
    vm.byGenre.forEach { (g, list) -> add(RowSpec("g${g.id}", g.title, list, genre = g.id)) }
  }
  LaunchedEffect(rows.isNotEmpty()) {
    if (vm.focusKey == null && rows.isNotEmpty()) vm.focusKey = "${rows[0].key}:${rows[0].channels[0].id}"
  }
  // When Continue Watching / My Channels appear above, show them instead of leaving them half scrolled off.
  val listState = rememberLazyListState()
  LaunchedEffect(rows.size) { if (listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0) }
  Column(Modifier.fillMaxSize()) {
    Hero(vm)
    LazyColumn(
      state = listState,
      contentPadding = PaddingValues(bottom = 40.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      items(rows, key = { it.key }) { row -> ChannelRow(vm, row, restore) }
    }
  }
}

@Composable
private fun ChannelRow(vm: AppViewModel, row: RowSpec, restore: FocusRequester) {
  val context = LocalContext.current
  val shown = row.channels.take(ROW_SIZE)
  Column {
    Text(row.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = Gutter, bottom = 8.dp))
    LazyRow(
      contentPadding = PaddingValues(horizontal = Gutter),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(shown, key = { it.id }) { ch ->
        val key = "${row.key}:${ch.id}"
        var nn by remember { mutableStateOf<NowNext?>(null) }
        if (row.recent) LaunchedEffect(ch.id) { nn = vm.epg(ch.id).nowNext() }
        ChannelCard(
          ch = ch,
          fav = ch.id in vm.favs,
          onClick = { vm.play(row.channels, row.channels.indexOf(ch)) },
          onLongClick = {
            if (row.recent) {
              vm.removeRecent(ch.id)
              Toast.makeText(context, "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
            } else {
              vm.toggleFav(ch.id)
              Toast.makeText(context, if (ch.id in vm.favs) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
            }
          },
          onFocus = { vm.focusKey = key; vm.focusedChannel = ch },
          focusRequester = if (vm.focusKey == key) restore else null,
          progress = if (row.recent) nn?.progress ?: 0f else null,
          sub = if (row.recent) nn?.now?.let { "Now: ${it.title}" } ?: " " else null,
        )
      }
      if (row.genre != null && row.channels.size > ROW_SIZE) {
        item(key = "more") {
          SeeAllCard(row.channels.size, onFocus = { vm.focusKey = "${row.key}:more" },
            focusRequester = if (vm.focusKey == "${row.key}:more") restore else null) {
            vm.liveGenre = row.genre
            vm.tab = Tab.Live
            vm.focusKey = null
          }
        }
      }
    }
  }
}

@Composable
private fun SeeAllCard(total: Int, onFocus: () -> Unit, focusRequester: FocusRequester?, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    modifier = Modifier.width(TileWidth).aspectRatio(16 / 9f)
      .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
      .onFocusChanged { if (it.isFocused) onFocus() },
    shape = ClickableSurfaceDefaults.shape(CardShape),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    border = ClickableSurfaceDefaults.border(
      border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), shape = CardShape),
      focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CardShape),
    ),
    colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF17171D), focusedContainerColor = Color(0xFF26262E)),
  ) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
      Text("See all ›", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
      Text("$total channels", fontSize = 10.sp, color = Muted)
    }
  }
}

/** Top of Home: details of whatever card has focus, Netflix-on-TV style. */
@Composable
private fun Hero(vm: AppViewModel) {
  val ch = vm.focusedChannel ?: vm.recentChannels.firstOrNull() ?: vm.channels.firstOrNull() ?: return
  val (title, badge) = parseName(ch.name)
  var nn by remember(ch.id) { mutableStateOf<NowNext?>(null) }
  LaunchedEffect(ch.id) {
    delay(350) // don't fetch EPG for every card the focus flies past
    nn = vm.epg(ch.id).nowNext()
  }
  val h = hue(ch.name)
  Box(
    Modifier.fillMaxWidth().height(190.dp).background(
      Brush.horizontalGradient(listOf(Bg, Color.hsl(h, 0.6f, 0.22f).copy(alpha = 0.9f)), startX = 300f),
    ),
  ) {
    Text(
      mark(title), fontSize = 150.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.06f),
      maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg), startY = 250f)))
    Column(Modifier.padding(start = Gutter, top = 8.dp).width(520.dp)) {
      Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        if (ch.number > 0) Text("CH ${ch.number}", fontSize = 11.sp, color = Color(0xFFDDDDDD))
        if (badge != null) Badge(badge)
        vm.genreTitle(ch.genre)?.let { Text(it, fontSize = 11.sp, color = Color(0xFFDDDDDD)) }
      }
      nn?.now?.let { now ->
        Text("Now · ${now.title}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
          maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.padding(top = 6.dp).width(200.dp).height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
          Box(Modifier.fillMaxWidth(nn!!.progress).fillMaxHeight().background(Accent))
        }
        val descr = now.descr.ifEmpty { nn?.next?.let { "Up next at ${hhmm(it.start)}: ${it.title}" } ?: "" }
        Text(descr, fontSize = 11.sp, color = Color(0xFFCFCFD6), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
      }
    }
    Text("Hold OK to add to My List", fontSize = 9.sp, color = Dim, modifier = Modifier.align(Alignment.BottomEnd).padding(end = Gutter, bottom = 4.dp))
  }
}

// ---------- Live TV: categories on the left, channels on the right ----------

@Composable
fun LiveScreen(vm: AppViewModel, restore: FocusRequester) {
  val genre = vm.liveGenre ?: vm.byGenre.firstOrNull()?.first?.id
  val list = vm.byGenre.firstOrNull { it.first.id == genre }?.second ?: emptyList()
  LaunchedEffect(Unit) { if (vm.focusKey == null && genre != null) vm.focusKey = "genre:$genre" }
  Row(Modifier.fillMaxSize().padding(top = 6.dp)) {
    LazyColumn(
      Modifier.width(210.dp).fillMaxHeight(),
      contentPadding = PaddingValues(start = Gutter - 12.dp, end = 8.dp, bottom = 30.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      items(vm.byGenre, key = { it.first.id }) { (g, chans) ->
        val key = "genre:${g.id}"
        GenreItem(
          title = g.title, count = chans.size, selected = g.id == genre,
          focusRequester = if (vm.focusKey == key) restore else null,
          onFocus = { vm.focusKey = key },
          onClick = { vm.liveGenre = g.id },
        )
      }
    }
    ChannelGrid(vm, list, restore, keyPrefix = "live:$genre", empty = "No channels")
  }
}

@Composable
private fun GenreItem(title: String, count: Int, selected: Boolean, focusRequester: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth()
      .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
      .onFocusChanged { if (it.isFocused) onFocus() },
    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    colors = ClickableSurfaceDefaults.colors(
      containerColor = if (selected) Color(0xFF26262E) else Color.Transparent, focusedContainerColor = Color.White,
      contentColor = if (selected) Color.White else Color(0xFFCFCFD6), focusedContentColor = Color.Black,
    ),
  ) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(title, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Text("$count", fontSize = 9.sp, color = Dim)
    }
  }
}

@Composable
fun ChannelGrid(vm: AppViewModel, list: List<Channel>, restore: FocusRequester, keyPrefix: String, empty: String) {
  val context = LocalContext.current
  if (list.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(empty, color = Muted, fontSize = 13.sp) }
    return
  }
  LazyVerticalGrid(
    columns = GridCells.Adaptive(TileWidth),
    contentPadding = PaddingValues(start = 12.dp, end = Gutter, top = 4.dp, bottom = 40.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    items(list, key = { it.id }) { ch ->
      val key = "$keyPrefix:${ch.id}"
      ChannelCard(
        ch = ch,
        fav = ch.id in vm.favs,
        onClick = { vm.play(list, list.indexOf(ch)) },
        onLongClick = {
          vm.toggleFav(ch.id)
          Toast.makeText(context, if (ch.id in vm.favs) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
        },
        onFocus = { vm.focusKey = key; vm.focusedChannel = ch },
        focusRequester = if (vm.focusKey == key) restore else null,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

// ---------- My List / placeholders ----------

@Composable
fun MyListScreen(vm: AppViewModel, restore: FocusRequester) {
  val list = vm.favChannels
  LaunchedEffect(Unit) { if (vm.focusKey == null && list.isNotEmpty()) vm.focusKey = "mylist:${list[0].id}" }
  Column(Modifier.fillMaxSize().padding(start = Gutter - 12.dp)) {
    Text("My Channels", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 12.dp))
    ChannelGrid(vm, list, restore, keyPrefix = "mylist", empty = "Your list is empty. Hold OK on any channel to add it.")
  }
}

@Composable
fun ComingSoon(text: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
      Spacer(Modifier.height(6.dp))
      Text("Coming in the next build", fontSize = 12.sp, color = Muted)
    }
  }
}
