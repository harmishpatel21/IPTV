package com.iptv.tv.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.iptv.tv.data.Episode
import com.iptv.tv.data.Season
import com.iptv.tv.data.VodCategory
import com.iptv.tv.data.VodItem
import kotlinx.coroutines.delay

/** Categories look like "HINDI MOVIES | LATEST"; the first word groups them by language/section. */
fun groupOf(title: String) = title.split(' ', '|').first { it.isNotBlank() }.uppercase()

/** "HINDI MOVIES | LATEST" → "Hindi Movies | Latest". */
fun pretty(s: String) = s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

// ---------- Movies & Shows: language chips → one poster row per category ----------

@Composable
fun MoviesScreen(vm: AppViewModel, restore: FocusRequester) {
  LaunchedEffect(Unit) { vm.loadVodCats() }
  vm.vodCat?.let { return VodCategoryScreen(vm, it, restore) }
  if (vm.vodCats.isEmpty()) return CenterNote(vm.vodError ?: "Loading Movies & Shows…")

  val groups = remember(vm.vodCats) { vm.vodCats.map { groupOf(it.title) }.distinct() }
  val group = vm.vodGroup.takeIf { it in groups } ?: groups.first()
  val cats = vm.vodCats.filter { groupOf(it.title) == group }
  LaunchedEffect(group) { if (vm.focusKey == null) vm.focusKey = "group:$group" }

  LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item(key = "groups") {
      LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(groups, key = { it }) { g ->
          Pill(pretty(g), g == group, Modifier.restorable(vm, "group:$g", restore)) { vm.selectVodGroup(g) }
        }
      }
    }
    items(cats, key = { it.id }) { cat -> VodRow(vm, cat, restore) }
  }
}

@Composable
private fun VodRow(vm: AppViewModel, cat: VodCategory, restore: FocusRequester) {
  var items by remember(cat.id) { mutableStateOf<List<VodItem>?>(null) }
  var total by remember(cat.id) { mutableStateOf(0) }
  var error by remember(cat.id) { mutableStateOf<String?>(null) }
  LaunchedEffect(cat.id) {
    runCatching { vm.vodPage(cat.id, 1) }
      .onSuccess { items = it.items; total = it.total }
      .onFailure { error = it.message ?: "error" }
  }
  Column {
    RowTitle(pretty(cat.title) + if (total > 0) "  ·  ${"%,d".format(total)}" else "")
    val list = items
    when {
      error != null -> Text("Could not load: $error", fontSize = 11.sp, color = Dim, modifier = Modifier.padding(start = Gutter))
      list == null -> Box(Modifier.height(PosterWidth * 1.5f + 20.dp).padding(start = Gutter)) { Text("Loading…", fontSize = 11.sp, color = Dim) }
      list.isEmpty() -> Text("Nothing here yet", fontSize = 11.sp, color = Dim, modifier = Modifier.padding(start = Gutter))
      else -> LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { VodCard(vm, it, "vrow:${cat.id}:${it.id}", restore) }
        if (total > list.size) item(key = "more") {
          SeeAllCard("${"%,d".format(total)} titles",
            Modifier.width(PosterWidth).aspectRatio(2 / 3f).restorable(vm, "vrow:${cat.id}:more", restore)) {
            vm.vodCat = cat
            vm.focusKey = null
          }
        }
      }
    }
  }
}

// ---------- One category: sort options + paged poster grid ----------

private val SORTS = listOf("added" to "Recently added", "name" to "A–Z", "rating" to "Top rated")

@Composable
fun VodCategoryScreen(vm: AppViewModel, cat: VodCategory, restore: FocusRequester) {
  var sort by remember(cat.id) { mutableStateOf("added") }
  val scope = rememberCoroutineScope()
  val pager = remember(cat.id, sort) { VodPager(scope) { p -> vm.vodPage(cat.id, p, sort) }.also { it.more() } }
  LaunchedEffect(cat.id) { if (vm.focusKey == null) vm.focusKey = "sort:added" }

  Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
    Row(Modifier.padding(start = Gutter, end = Gutter, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(pretty(cat.title), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
      if (pager.total >= 0) Text("  ${"%,d".format(pager.total)} titles", fontSize = 11.sp, color = Dim)
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
        SORTS.forEach { (key, label) ->
          Pill(label, key == sort, Modifier.restorable(vm, "sort:$key", restore)) { sort = key }
        }
      }
    }
    PosterGrid(vm, pager, restore, keyPrefix = "vcat:${cat.id}:$sort")
  }
}

@Composable
fun PosterGrid(vm: AppViewModel, pager: VodPager, restore: FocusRequester, keyPrefix: String) {
  val grid = rememberLazyGridState()
  // Load the next page when the last rows come into view.
  LaunchedEffect(pager) {
    snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
      .collect { if (it >= pager.items.size - 14) pager.more() }
  }
  when {
    pager.items.isEmpty() && pager.loading -> return CenterNote("Loading…")
    pager.items.isEmpty() && pager.error != null -> return CenterNote("Could not load", pager.error)
    pager.items.isEmpty() && pager.done -> return CenterNote("Nothing found")
  }
  LazyVerticalGrid(
    state = grid,
    columns = GridCells.Adaptive(PosterWidth),
    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 6.dp, bottom = 40.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    itemsIndexed(pager.items, key = { _, it -> it.id }) { _, item -> VodCard(vm, item, "$keyPrefix:${item.id}", restore, Modifier.fillMaxWidth()) }
  }
}

// ---------- Search: channels + movies & shows ----------

@Composable
fun SearchScreen(vm: AppViewModel, restore: FocusRequester) {
  val scope = rememberCoroutineScope()
  var q by remember { mutableStateOf(vm.query.trim()) }
  LaunchedEffect(vm.query) { delay(500); q = vm.query.trim() } // search once typing pauses
  LaunchedEffect(Unit) { if (vm.focusKey == null) vm.focusKey = "search:field" }

  val ql = q.lowercase()
  val chans = remember(q, vm.channels) {
    if (q.isEmpty()) emptyList() else vm.channels.filter { it.name.lowercase().contains(ql) || it.number.toString() == q }
  }
  val pager = remember(q) { if (q.length < 2) null else VodPager(scope) { p -> vm.vodPage(null, p, search = q) }.also { it.more() } }

  LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item(key = "field") { SearchBox(vm, restore) { q = vm.query.trim() } }
    if (vm.query.trim() != q) item(key = "typing") { SearchNote("…") }
    else if (q.isNotEmpty()) {
      val vodState = when {
        pager == null -> null
        pager.items.isNotEmpty() -> null
        pager.loading || !pager.done -> "Searching movies & shows…"
        pager.error != null -> "Could not search movies & shows: ${pager.error}"
        else -> if (chans.isEmpty()) "No results for “$q”" else "No movies or shows match “$q”"
      }
      if (vodState != null) item(key = "vodstate") { SearchNote(vodState) }
    }
    if (chans.isNotEmpty()) item(key = "chans") {
      Column {
        RowTitle("Live TV · ${chans.size}")
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          items(chans.take(60), key = { it.id }) { LiveCard(vm, it, chans, "sch:${it.id}", restore) }
        }
      }
    }
    if (pager != null && pager.items.isNotEmpty()) {
      item(key = "vodtitle") { RowTitle("Movies & Shows · ${"%,d".format(pager.total)}") }
      // Posters in rows of 7, loading more as the last row appears.
      val rows = pager.items.chunked(7)
      itemsIndexed(rows, key = { i, _ -> "vr$i" }) { i, row ->
        if (i == rows.lastIndex) LaunchedEffect(pager.items.size) { pager.more() }
        Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          row.forEach { VodCard(vm, it, "sv:${it.id}", restore) }
        }
      }
    }
  }
}

/**
 * The search box is a button until OK is pressed; only then does it become a text field and open the TV keyboard.
 * (A plain text field pops the keyboard up whenever focus passes over it, trapping remote users.)
 */
@Composable
private fun SearchBox(vm: AppViewModel, restore: FocusRequester, onSubmit: () -> Unit) {
  var editing by remember { mutableStateOf(false) }
  var wasEditing by remember { mutableStateOf(false) }
  val field = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val shape = RoundedCornerShape(8.dp)
  val placeholder = "Search channels, movies and shows — press OK to type"

  Box(Modifier.padding(horizontal = Gutter).fillMaxWidth()) {
    if (editing) {
      var hadFocus by remember { mutableStateOf(false) }
      BasicTextField(
        value = vm.query,
        onValueChange = { vm.query = it },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); editing = false; onSubmit() }),
        modifier = Modifier.fillMaxWidth()
          .focusRequester(field)
          .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) editing = false }
          .background(Color(0xFF17171D), shape)
          .border(BorderStroke(2.dp, Accent), shape)
          .padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⌕  ", fontSize = 16.sp, color = Muted)
            Box { if (vm.query.isEmpty()) Text(placeholder, fontSize = 14.sp, color = Dim); inner() }
          }
        },
      )
      LaunchedEffect(Unit) { wasEditing = true; field.requestFocus(); keyboard?.show() }
    } else {
      Surface(
        onClick = { editing = true },
        modifier = Modifier.fillMaxWidth().restorable(vm, "search:field", restore),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
          containerColor = Color(0xFF17171D), focusedContainerColor = Color(0xFF17171D),
          contentColor = Color.White, focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
          border = Border(BorderStroke(2.dp, Color(0xFF2A2A33)), shape = shape),
          focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
      ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("⌕  ", fontSize = 16.sp, color = Muted)
          if (vm.query.isEmpty()) Text(placeholder, fontSize = 14.sp, color = Dim)
          else Text(vm.query, fontSize = 16.sp, color = Color.White, maxLines = 1)
        }
      }
      // After typing, put focus back on the box (the text field that had it is gone).
      LaunchedEffect(Unit) { if (wasEditing) runCatching { restore.requestFocus() } }
    }
  }
}

@Composable
private fun SearchNote(text: String) =
  Text(text, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = Gutter))

// ---------- Title details (overlay): movie Play/Resume, or seasons → episodes ----------

@Composable
fun DetailScreen(vm: AppViewModel, item: VodItem, restore: FocusRequester) {
  val last = vm.recent.firstOrNull { it.id == item.id && it.type == "vod" }
  var seasons by remember(item.id) { mutableStateOf<List<Season>?>(null) }
  var season by remember(item.id) { mutableStateOf<Season?>(null) }
  var episodes by remember(item.id) { mutableStateOf<List<Episode>?>(null) }
  var error by remember(item.id) { mutableStateOf<String?>(null) }

  if (item.isSeries) {
    LaunchedEffect(item.id) {
      runCatching { vm.seasons(item) }
        .onSuccess { s -> seasons = s; season = s.firstOrNull { it.id == last?.seasonId } ?: s.firstOrNull() }
        .onFailure { error = "Could not load seasons: ${it.message}" }
    }
    LaunchedEffect(season?.id) {
      val s = season ?: return@LaunchedEffect
      episodes = null
      runCatching { vm.episodes(item, s) }.onSuccess { episodes = it }.onFailure { error = "Could not load episodes: ${it.message}" }
    }
  }

  // Detail elements use their own keys; the shared requester follows vm.detailFocusKey.
  fun Modifier.detailFocus(key: String) =
    (if (vm.detailFocusKey == key) focusRequester(restore) else this).onFocusChanged { if (it.isFocused) vm.detailFocusKey = key }

  Box(Modifier.fillMaxSize().background(Bg)) {
    if (item.poster != null) {
      AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier))
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg.copy(alpha = 0.55f), Bg.copy(alpha = 0.92f), Bg))))

    LazyColumn(contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 36.dp, bottom = 40.dp)) {
      item(key = "head") {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
          Box(Modifier.width(170.dp).aspectRatio(2 / 3f).background(tileBrush(hue(item.name)), CardShape)) {
            if (item.poster != null) AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
          }
          Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(item.name, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
              if (item.rating > 0) Text("★ %.1f".format(item.rating), fontSize = 12.sp, color = Color(0xFF46D369), fontWeight = FontWeight.Bold)
              if (item.year.isNotEmpty()) Text(item.year, fontSize = 12.sp, color = Color(0xFFDDDDDD))
              if (item.isSeries) Badge("SERIES")
              if (item.genres.isNotEmpty()) Text(item.genres, fontSize = 12.sp, color = Color(0xFFDDDDDD), maxLines = 1)
            }
            Text(item.descr, fontSize = 12.sp, color = Color(0xFFDCDCE2), maxLines = 5, overflow = TextOverflow.Ellipsis,
              lineHeight = 17.sp, modifier = Modifier.padding(top = 12.dp).width(560.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 18.dp)) {
              val (label, action) = playAction(vm, item, last, seasons, season, episodes)
              BigButton("▶  $label", primary = true, Modifier.detailFocus("play"), onClick = action)
              BigButton(if (vm.inVodList(item.id)) "✓  My List" else "+  My List", primary = false, Modifier.detailFocus("list")) { vm.toggleVodList(item) }
            }
            val credits = listOf("Cast" to item.actors, "Director" to item.director, "Country" to item.country).filter { it.second.isNotEmpty() }
            credits.forEach { (k, v) ->
              Text("$k: $v", fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).width(560.dp))
            }
          }
        }
      }
      error?.let { item { Text(it, fontSize = 12.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 20.dp)) } }
      val ss = seasons
      if (item.isSeries && ss != null) {
        item(key = "seasons") {
          Column(Modifier.padding(top = 26.dp)) {
            Text("Episodes", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              items(ss, key = { it.id }) { s -> Pill("Season ${s.number}", s.id == season?.id, Modifier.detailFocus("season:${s.id}")) { season = s } }
            }
          }
        }
        val eps = episodes
        if (eps == null) item { Text("Loading episodes…", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(top = 14.dp)) }
        else itemsIndexed(eps, key = { _, it -> it.id }) { i, ep ->
          val p = vm.progress[progressKey(item.id, ep.id)]
          EpisodeRow(ep, if (p != null && p.dur > 0) p.pos.toFloat() / p.dur else 0f, Modifier.detailFocus("ep:${ep.id}")) {
            vm.detailFocusKey = "ep:${ep.id}"
            vm.playVod(VodPlay(item, season, eps, i, p.resumeAt()))
          }
        }
      }
    }
  }
}

/** Label + action for the main button: Resume where you left off, else start (S1:E1 for series). */
private fun playAction(
  vm: AppViewModel, item: VodItem, last: com.iptv.tv.data.Recent?,
  seasons: List<Season>?, season: Season?, episodes: List<Episode>?,
): Pair<String, () -> Unit> {
  if (!item.isSeries) {
    val resume = vm.progress[progressKey(item.id, null)].resumeAt()
    val label = if (resume > 0) "Resume · ${((vm.progress[progressKey(item.id, null)]!!.dur - resume) / 60_000).coerceAtLeast(1)} min left" else "Play"
    return label to { vm.playVod(VodPlay(item, null, null, 0, resume)) }
  }
  if (last?.epId != null) return "Resume ${last.label}" to { vm.resumeVod(last) }
  val first = seasons?.firstOrNull() ?: return "Loading…" to {}
  val eps = episodes.takeIf { season?.id == first.id }
  return "Play S${first.number}:E1" to {
    if (eps != null && eps.isNotEmpty()) vm.playVod(VodPlay(item, first, eps, 0, 0))
  }
}

@Composable
private fun BigButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    modifier = modifier,
    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    colors = ClickableSurfaceDefaults.colors(
      containerColor = if (primary) Color.White.copy(alpha = 0.9f) else Color(0xFF6D6D7A).copy(alpha = 0.45f),
      focusedContainerColor = Color.White,
      contentColor = if (primary) Color.Black else Color.White, focusedContentColor = Color.Black,
    ),
    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Accent), shape = RoundedCornerShape(6.dp))),
  ) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp))
  }
}

@Composable
private fun EpisodeRow(ep: Episode, progress: Float, modifier: Modifier, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    colors = ClickableSurfaceDefaults.colors(
      containerColor = Color.White.copy(alpha = 0.04f), focusedContainerColor = Color.White.copy(alpha = 0.14f),
      contentColor = Color.White, focusedContentColor = Color.White,
    ),
    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(6.dp))),
  ) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("${ep.number}", fontSize = 18.sp, color = Muted, modifier = Modifier.width(44.dp))
      Text(ep.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      if (progress > 0) ProgressLine(progress, Modifier.width(110.dp)) else Box(Modifier.size(1.dp))
    }
  }
}
