package com.iptv.tv.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.iptv.tv.data.Channel
import com.iptv.tv.data.NowNext
import com.iptv.tv.data.VodItem
import com.iptv.tv.data.nowNext
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

const val ROW_SIZE = 40
val Gutter = 40.dp
val HeroHeight = 190.dp

fun hhmm(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Attach the shared restore-focus requester to the element whose key matches, and remember focus moves. */
fun Modifier.restorable(vm: AppViewModel, key: String, restore: FocusRequester): Modifier =
  (if (vm.focusKey == key) focusRequester(restore) else this).onFocusChanged { if (it.isFocused) vm.focusKey = key }

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
      val label = when (t) { Tab.Search -> "⌕  Search"; Tab.Settings -> "⚙"; else -> t.label }
      Pill(label, vm.tab == t) {
        if (vm.tab != t) { vm.tab = t; vm.vodCat = null; vm.focusKey = null }
      }
    }
  }
}

/** Rounded chip used for tabs, categories, seasons and sort options. */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    modifier = modifier,
    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
    colors = ClickableSurfaceDefaults.colors(
      containerColor = if (selected) Color(0xFF2A2A33) else Color.Transparent, focusedContainerColor = Color.White,
      contentColor = if (selected) Color.White else Muted, focusedContentColor = Color.Black,
    ),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
  ) {
    Text(text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
  }
}

@Composable
fun RowTitle(text: String) {
  Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = Gutter, bottom = 8.dp))
}

// ---------- Shared card builders ----------

@Composable
fun LiveCard(vm: AppViewModel, ch: Channel, list: List<Channel>, key: String, restore: FocusRequester,
             modifier: Modifier = Modifier, recentRow: Boolean = false) {
  val context = LocalContext.current
  var nn by remember { mutableStateOf<NowNext?>(null) }
  if (recentRow) LaunchedEffect(ch.id) { nn = vm.epg(ch.id).nowNext() }
  ChannelCard(
    ch = ch,
    fav = ch.id in vm.favs,
    onClick = { vm.play(list, list.indexOf(ch)) },
    onLongClick = {
      if (recentRow) {
        vm.removeRecent(ch.id)
        Toast.makeText(context, "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
      } else {
        vm.toggleFav(ch.id)
        Toast.makeText(context, if (ch.id in vm.favs) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
      }
    },
    onFocus = { vm.focusKey = key; vm.focusedChannel = ch; vm.focusedVod = null },
    focusRequester = if (vm.focusKey == key) restore else null,
    modifier = modifier,
    progress = if (recentRow) nn?.progress ?: 0f else null,
    sub = if (recentRow) nn?.now?.let { "Now: ${it.title}" } ?: " " else null,
  )
}

@Composable
fun VodCard(vm: AppViewModel, item: VodItem, key: String, restore: FocusRequester, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  PosterCard(
    item = item,
    onClick = { vm.openDetail(item) },
    onLongClick = {
      vm.toggleVodList(item)
      Toast.makeText(context, if (vm.inVodList(item.id)) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
    },
    onFocus = { vm.focusKey = key; vm.focusedVod = item; vm.focusedChannel = null },
    focusRequester = if (vm.focusKey == key) restore else null,
    modifier = modifier,
    saved = vm.inVodList(item.id),
  )
}

// ---------- Home ----------

@Composable
fun HomeScreen(vm: AppViewModel, restore: FocusRequester) {
  val context = LocalContext.current
  val cw = vm.recent.filter { if (it.type == "vod") it.item != null else vm.channel(it.id) != null }
  val favs = vm.favChannels
  val liveRecent = vm.liveRecent
  val firstKey = when {
    cw.isNotEmpty() -> "cw:${cw[0].id}"
    favs.isNotEmpty() -> "fav:${favs[0].id}"
    vm.vodList.isNotEmpty() -> "myvod:${vm.vodList[0].id}"
    else -> vm.byGenre.firstOrNull()?.let { (g, l) -> "g${g.id}:${l[0].id}" }
  }
  LaunchedEffect(firstKey != null) { if (vm.focusKey == null && firstKey != null) vm.focusKey = firstKey }

  // When Continue Watching / My List rows appear above, show them instead of leaving them half scrolled off.
  val listState = rememberLazyListState()
  val personalRows = listOf(cw, favs, vm.vodList).count { it.isNotEmpty() }
  LaunchedEffect(personalRows) { if (listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0) }

  Column(Modifier.fillMaxSize()) {
    Hero(vm)
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      if (cw.isNotEmpty()) item(key = "cw") {
        Column {
          RowTitle("Continue Watching")
          LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cw, key = { it.id }) { r ->
              val key = "cw:${r.id}"
              if (r.type == "vod") {
                VodWideCard(
                  r, onClick = { vm.resumeVod(r) },
                  onLongClick = {
                    vm.removeRecent(r.id)
                    Toast.makeText(context, "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
                  },
                  onFocus = { vm.focusKey = key; vm.focusedVod = r.item; vm.focusedChannel = null },
                  focusRequester = if (vm.focusKey == key) restore else null,
                )
              } else {
                LiveCard(vm, vm.channel(r.id)!!, liveRecent, key, restore, recentRow = true)
              }
            }
          }
        }
      }
      if (favs.isNotEmpty()) item(key = "fav") {
        Column {
          RowTitle("My Channels")
          LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(favs, key = { it.id }) { LiveCard(vm, it, favs, "fav:${it.id}", restore) }
          }
        }
      }
      if (vm.vodList.isNotEmpty()) item(key = "myvod") {
        Column {
          RowTitle("My Movies & Shows")
          LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.vodList, key = { it.id }) { VodCard(vm, it, "myvod:${it.id}", restore) }
          }
        }
      }
      genreRows(vm, restore)
    }
  }
}

private fun LazyListScope.genreRows(vm: AppViewModel, restore: FocusRequester) {
  items(vm.byGenre, key = { "g${it.first.id}" }) { (g, list) ->
    val rowKey = "g${g.id}"
    Column {
      RowTitle(g.title)
      LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list.take(ROW_SIZE), key = { it.id }) { LiveCard(vm, it, list, "$rowKey:${it.id}", restore) }
        if (list.size > ROW_SIZE) item(key = "more") {
          SeeAllCard("${list.size} channels", Modifier.width(TileWidth).aspectRatio(16 / 9f).restorable(vm, "$rowKey:more", restore)) {
            vm.liveGenre = g.id
            vm.tab = Tab.Live
            vm.focusKey = null
          }
        }
      }
    }
  }
}

@Composable
fun SeeAllCard(caption: String, modifier: Modifier, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    modifier = modifier,
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
      Text(caption, fontSize = 10.sp, color = Muted)
    }
  }
}

/** Top of Home: details of whatever card has focus, Netflix-on-TV style. */
@Composable
fun Hero(vm: AppViewModel) {
  val vod = vm.focusedVod
  if (vod != null) return VodHero(vm, vod)
  val ch = vm.focusedChannel ?: vm.liveRecent.firstOrNull() ?: vm.channels.firstOrNull() ?: return
  val (title, badge) = parseName(ch.name)
  var nn by remember(ch.id) { mutableStateOf<NowNext?>(null) }
  LaunchedEffect(ch.id) {
    delay(350) // don't fetch EPG for every card the focus flies past
    nn = vm.epg(ch.id).nowNext()
  }
  val h = hue(ch.name)
  Box(
    Modifier.fillMaxWidth().height(HeroHeight).background(
      Brush.horizontalGradient(listOf(Bg, Color.hsl(h, 0.6f, 0.22f).copy(alpha = 0.9f)), startX = 300f),
    ),
  ) {
    Text(
      mark(title), fontSize = 150.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.06f),
      maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
    )
    HeroFade()
    Column(Modifier.padding(start = Gutter, top = 8.dp).width(520.dp)) {
      HeroTitle(title)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        if (ch.number > 0) Text("CH ${ch.number}", fontSize = 11.sp, color = Color(0xFFDDDDDD))
        if (badge != null) Badge(badge)
        vm.genreTitle(ch.genre)?.let { Text(it, fontSize = 11.sp, color = Color(0xFFDDDDDD)) }
      }
      nn?.now?.let { now ->
        Text("Now · ${now.title}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
          maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        ProgressLine(nn!!.progress, Modifier.padding(top = 6.dp).width(200.dp))
        HeroDescr(now.descr.ifEmpty { nn?.next?.let { "Up next at ${hhmm(it.start)}: ${it.title}" } ?: "" })
      }
    }
    HeroHint("Hold OK to add to My List")
  }
}

@Composable
private fun VodHero(vm: AppViewModel, item: VodItem) {
  val r = vm.recent.firstOrNull { it.id == item.id && it.type == "vod" }
  Box(Modifier.fillMaxWidth().height(HeroHeight).background(Bg)) {
    if (item.poster != null) {
      AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.55f))
      Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Bg, Bg, Bg.copy(alpha = 0.6f), Color.Transparent))))
    }
    HeroFade()
    Column(Modifier.padding(start = Gutter, top = 8.dp).width(520.dp)) {
      HeroTitle(item.name)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        if (item.rating > 0) Text("★ %.1f".format(item.rating), fontSize = 11.sp, color = Color(0xFF46D369), fontWeight = FontWeight.Bold)
        if (item.year.isNotEmpty()) Text(item.year, fontSize = 11.sp, color = Color(0xFFDDDDDD))
        if (item.isSeries) Badge("SERIES")
        if (r != null && r.label.isNotEmpty()) Badge(r.label)
        if (item.genres.isNotEmpty()) Text(item.genres, fontSize = 11.sp, color = Color(0xFFDDDDDD), maxLines = 1)
      }
      if (r != null && r.dur > 0) {
        Text(r.epName.ifEmpty { "Continue watching" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
          color = Color.White, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
        ProgressLine(r.pos.toFloat() / r.dur, Modifier.padding(top = 6.dp).width(200.dp))
      }
      HeroDescr(item.descr)
    }
    HeroHint("OK for details  ·  Hold OK to add to My List")
  }
}

@Composable private fun HeroFade() =
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg), startY = 250f)))

@Composable private fun HeroTitle(text: String) =
  Text(text, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable private fun HeroDescr(text: String) =
  Text(text, fontSize = 11.sp, color = Color(0xFFCFCFD6), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))

@Composable private fun BoxScope.HeroHint(text: String) =
  Text(text, fontSize = 9.sp, color = Dim, modifier = Modifier.align(Alignment.BottomEnd).padding(end = Gutter, bottom = 4.dp))

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
        GenreItem(g.title, chans.size, g.id == genre, Modifier.restorable(vm, "genre:${g.id}", restore)) { vm.liveGenre = g.id }
      }
    }
    ChannelGrid(vm, list, restore, keyPrefix = "live:$genre", empty = "No channels")
  }
}

@Composable
private fun GenreItem(title: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
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
  if (list.isEmpty()) return CenterNote(empty)
  LazyVerticalGrid(
    columns = GridCells.Adaptive(TileWidth),
    contentPadding = PaddingValues(start = 12.dp, end = Gutter, top = 4.dp, bottom = 40.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    items(list, key = { it.id }) { ch -> LiveCard(vm, ch, list, "$keyPrefix:${ch.id}", restore, Modifier.fillMaxWidth()) }
  }
}

// ---------- My List ----------

@Composable
fun MyListScreen(vm: AppViewModel, restore: FocusRequester) {
  val favs = vm.favChannels
  val vods = vm.vodList
  LaunchedEffect(Unit) {
    if (vm.focusKey == null) vm.focusKey = favs.firstOrNull()?.let { "mylist:${it.id}" } ?: vods.firstOrNull()?.let { "mylistvod:${it.id}" }
  }
  if (favs.isEmpty() && vods.isEmpty()) return CenterNote("Your list is empty", "Hold OK on any channel, movie or show to add it.")
  LazyColumn(contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (favs.isNotEmpty()) item {
      Column {
        RowTitle("My Channels · ${favs.size}")
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          items(favs, key = { it.id }) { LiveCard(vm, it, favs, "mylist:${it.id}", restore) }
        }
      }
    }
    if (vods.isNotEmpty()) item {
      Column {
        RowTitle("My Movies & Shows · ${vods.size}")
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          items(vods, key = { it.id }) { VodCard(vm, it, "mylistvod:${it.id}", restore) }
        }
      }
    }
  }
}

@Composable
fun CenterNote(text: String, sub: String? = null) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
      if (sub != null) { Spacer(Modifier.height(6.dp)); Text(sub, fontSize = 12.sp, color = Muted) }
    }
  }
}
