package com.iptv.tv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.Channel
import com.iptv.tv.data.Episode
import com.iptv.tv.data.Genre
import com.iptv.tv.data.Portal
import com.iptv.tv.data.Programme
import com.iptv.tv.data.Progress
import com.iptv.tv.data.Recent
import com.iptv.tv.data.Season
import com.iptv.tv.data.Store
import com.iptv.tv.data.VodCategory
import com.iptv.tv.data.VodItem
import com.iptv.tv.data.VodPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { Home("Home"), Search("Search"), Live("Live TV"), Movies("Movies & Shows"), MyList("My List") }

/** What the live player is showing: a channel within the list it was started from (Up/Down zaps through it). */
data class LivePlay(val list: List<Channel>, val index: Int)

/** A movie (no season) or an episode within its season's list, plus where to resume (ms). */
data class VodPlay(val item: VodItem, val season: Season?, val episodes: List<Episode>?, val index: Int, val resumeAt: Long) {
  val episode: Episode? get() = episodes?.getOrNull(index)
  val key: String get() = progressKey(item.id, episode?.id)
}

fun progressKey(titleId: String, episodeId: String?) = "$titleId:${episodeId ?: "movie"}"

/** Resume only if meaningfully started and not basically finished. */
fun Progress?.resumeAt(): Long = if (this != null && pos > 30_000 && pos < dur - 60_000) pos else 0

class AppViewModel(app: Application) : AndroidViewModel(app) {
  private val store = Store(app)
  val portal = Portal(knownEndpoint = store.endpoint, onEndpoint = { store.endpoint = it })

  var status by mutableStateOf<String?>("Connecting to portal…")
    private set
  var genres by mutableStateOf<List<Genre>>(emptyList())
    private set
  var channels by mutableStateOf<List<Channel>>(emptyList())
    private set
  var byGenre by mutableStateOf<List<Pair<Genre, List<Channel>>>>(emptyList())
    private set
  private var byId: Map<String, Channel> = emptyMap()

  var favs by mutableStateOf(store.favs())
    private set
  var vodList by mutableStateOf(store.vodList())
    private set
  var recent by mutableStateOf(store.recent())
    private set
  var progress by mutableStateOf(store.progress())
    private set

  // Navigation
  var tab by mutableStateOf(Tab.Home)
  var liveGenre by mutableStateOf<String?>(null)
  var vodCat by mutableStateOf<VodCategory?>(null)   // category grid open inside Movies & Shows
  var detail by mutableStateOf<VodItem?>(null)        // title details overlay
  var playing by mutableStateOf<LivePlay?>(null)
    private set
  var vodPlaying by mutableStateOf<VodPlay?>(null)
    private set
  var query by mutableStateOf("")

  /** Key of the focused card, so focus can return there after an overlay closes. */
  var focusKey by mutableStateOf<String?>(null)
  var detailFocusKey by mutableStateOf<String?>(null)
  var focusedChannel by mutableStateOf<Channel?>(null)
  var focusedVod by mutableStateOf<VodItem?>(null)

  val overlayOpen: Boolean get() = playing != null || vodPlaying != null

  init { load() }

  fun load() = viewModelScope.launch {
    // Show the cached list instantly; refresh from the portal when it's older than 6 h (or missing).
    val cached = withContext(Dispatchers.IO) { store.loadChannels() }
    if (cached != null) apply(cached.first, cached.second)
    if (cached == null || store.channelCacheAge > 6 * 3600_000L) {
      try {
        if (cached == null) status = "Loading channels…"
        val g = portal.genres()
        val c = portal.channels()
        withContext(Dispatchers.IO) { store.saveChannels(g, c) }
        apply(g, c)
      } catch (e: CancellationException) { throw e } catch (e: Exception) {
        if (cached == null) status = "Could not load channels: ${e.message}"
      }
    }
  }

  private fun apply(g: List<Genre>, c: List<Channel>) {
    val grouped = c.groupBy { it.genre }
    genres = g.filter { grouped.containsKey(it.id) }
    channels = c
    byId = c.associateBy { it.id }
    byGenre = genres.map { it to grouped.getValue(it.id) }
    status = null
  }

  fun channel(id: String) = byId[id]
  fun genreTitle(id: String) = genres.firstOrNull { it.id == id }?.title

  val liveRecent: List<Channel> get() = recent.filter { it.type == "live" }.mapNotNull { byId[it.id] }
  val favChannels: List<Channel> get() = favs.mapNotNull { byId[it] }.sortedBy { it.number }

  fun toggleFav(id: String) {
    favs = if (id in favs) favs - id else favs + id
    store.saveFavs(favs)
  }

  fun inVodList(id: String) = vodList.any { it.id == id }

  fun toggleVodList(item: VodItem) {
    vodList = if (inVodList(item.id)) vodList.filterNot { it.id == item.id } else listOf(item) + vodList
    store.saveVodList(vodList)
  }

  fun removeRecent(id: String) {
    recent = recent.filterNot { it.id == id }
    store.saveRecent(recent)
  }

  private fun pushRecent(r: Recent) {
    recent = (listOf(r) + recent.filterNot { it.id == r.id }).take(40)
    store.saveRecent(recent)
  }

  // ---- Live ----

  fun play(list: List<Channel>, index: Int) { playing = LivePlay(list, index) }

  fun zap(step: Int) {
    val p = playing ?: return
    playing = p.copy(index = Math.floorMod(p.index + step, p.list.size))
  }

  fun markWatched(ch: Channel) = pushRecent(Recent("live", ch.id, System.currentTimeMillis()))

  fun closePlayer() { playing = null }

  suspend fun liveLink(ch: Channel) = portal.liveLink(ch.cmd)

  // ---- EPG: cached 2 min, de-duplicated, at most 2 requests at a time ----

  private val epgCache = HashMap<String, Pair<Long, Deferred<List<Programme>>>>()
  private val epgGate = Semaphore(2) // be gentle with the portal

  suspend fun epg(id: String): List<Programme> {
    val hit = epgCache[id]
    val job = if (hit != null && System.currentTimeMillis() - hit.first < 120_000) hit.second else
      viewModelScope.async { epgGate.withPermit { runCatching { portal.epg(id) }.getOrDefault(emptyList()) } }
        .also { epgCache[id] = System.currentTimeMillis() to it }
    return job.await()
  }

  // ---- Video Club ----

  var vodCats by mutableStateOf<List<VodCategory>>(emptyList())
    private set
  var vodError by mutableStateOf<String?>(null)
    private set
  var vodGroup by mutableStateOf(store.vodGroup)
    private set

  fun loadVodCats() {
    if (vodCats.isNotEmpty()) return
    vodError = null
    viewModelScope.launch {
      try { vodCats = portal.vodCategories() } catch (e: CancellationException) { throw e } catch (e: Exception) {
        vodError = "Could not load Movies & Shows: ${e.message}"
      }
    }
  }

  fun selectVodGroup(g: String) { vodGroup = g; store.vodGroup = g }

  private val pageCache = HashMap<String, Pair<Long, VodPage>>()

  suspend fun vodPage(cat: String?, page: Int, sort: String = "added", search: String = ""): VodPage {
    val key = "$cat|$page|$sort|$search"
    pageCache[key]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second }
    return portal.vodList(cat, page, search, sort).also { pageCache[key] = System.currentTimeMillis() to it }
  }

  private val seasonCache = HashMap<String, List<Season>>()
  private val episodeCache = HashMap<String, List<Episode>>()

  suspend fun seasons(item: VodItem) = seasonCache[item.id] ?: portal.seasons(item).also { seasonCache[item.id] = it }
  suspend fun episodes(item: VodItem, season: Season) =
    episodeCache["${item.id}:${season.id}"] ?: portal.episodes(item, season).also { episodeCache["${item.id}:${season.id}"] = it }

  fun openDetail(item: VodItem) { detail = item; detailFocusKey = "play" }
  fun closeDetail() { detail = null }

  fun playVod(p: VodPlay) {
    vodPlaying = p
    recordVod(p, p.resumeAt, progress[p.key]?.dur ?: 0)
  }

  /** Resume a Continue Watching entry: reload the episode list so auto-next keeps working. */
  fun resumeVod(r: Recent) {
    val item = r.item ?: return
    val resume = progress[progressKey(item.id, r.epId)].resumeAt()
    if (r.epId == null) return playVod(VodPlay(item, null, null, 0, resume))
    viewModelScope.launch {
      runCatching {
        val season = Season(r.seasonId!!, r.seasonNum, "")
        val eps = episodes(item, season)
        playVod(VodPlay(item, season, eps, eps.indexOfFirst { it.id == r.epId }.coerceAtLeast(0), resume))
      }
    }
  }

  fun recordVod(p: VodPlay, pos: Long, dur: Long) {
    progress = progress + (p.key to Progress(pos, dur, System.currentTimeMillis()))
    store.saveProgress(progress)
    val ep = p.episode
    pushRecent(Recent("vod", p.item.id, System.currentTimeMillis(), p.item,
      p.season?.id, p.season?.number ?: 0, ep?.id, ep?.number ?: 0, ep?.name ?: "", pos, dur))
  }

  /** Next episode, rolling over into the next season. False when there's nothing after this one. */
  suspend fun nextEpisode(): Boolean {
    val p = vodPlaying ?: return false
    val eps = p.episodes ?: return false
    if (p.index + 1 < eps.size) { playVod(p.copy(index = p.index + 1, resumeAt = 0)); return true }
    return runCatching {
      val seasons = seasons(p.item)
      val next = seasons.getOrNull(seasons.indexOfFirst { it.id == p.season?.id } + 1) ?: return false
      val nextEps = episodes(p.item, next).ifEmpty { return false }
      playVod(VodPlay(p.item, next, nextEps, 0, 0))
      true
    }.getOrDefault(false)
  }

  fun closeVod(finished: Boolean) {
    val p = vodPlaying
    vodPlaying = null
    if (finished && p != null && p.episodes == null) removeRecent(p.item.id) // a finished movie leaves Continue Watching
  }

  suspend fun vodLink(p: VodPlay) = portal.vodLink(p.item, p.season, p.episode)
}

/** Infinite paging over a portal list, one portal page (14 titles) at a time. */
class VodPager(private val scope: CoroutineScope, private val fetch: suspend (Int) -> VodPage) {
  val items = mutableStateListOf<VodItem>()
  var total by mutableIntStateOf(-1)
    private set
  var error by mutableStateOf<String?>(null)
    private set
  var loading by mutableStateOf(false)
    private set
  private var page by mutableIntStateOf(0)
  private var pages by mutableIntStateOf(1)

  val done: Boolean get() = page >= pages

  fun more() {
    if (loading || done) return
    loading = true
    scope.launch {
      try {
        val r = fetch(page + 1)
        page = r.page
        pages = r.pages
        total = r.total
        val seen = items.mapTo(HashSet()) { it.id }
        items += r.items.filter { seen.add(it.id) }
      } catch (e: CancellationException) { throw e } catch (e: Exception) {
        error = e.message
        pages = 0
      } finally {
        loading = false
      }
    }
  }
}
