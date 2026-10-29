package com.iptv.tv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.Channel
import com.iptv.tv.data.Genre
import com.iptv.tv.data.Portal
import com.iptv.tv.data.Programme
import com.iptv.tv.data.Recent
import com.iptv.tv.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { Home("Home"), Live("Live TV"), Movies("Movies & Shows"), MyList("My List") }

/** What the player is showing: a channel within the list it was started from (Up/Down zaps through it). */
data class LivePlay(val list: List<Channel>, val index: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {
  val portal = Portal()
  private val store = Store(app)

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
  var recent by mutableStateOf(store.recent())
    private set

  var tab by mutableStateOf(Tab.Home)
  var liveGenre by mutableStateOf<String?>(null)
  var playing by mutableStateOf<LivePlay?>(null)
    private set

  /** Key of the focused card ("row:channelId"), so focus can return there after the player closes. */
  var focusKey by mutableStateOf<String?>(null)
  var focusedChannel by mutableStateOf<Channel?>(null)

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

  val recentChannels: List<Channel> get() = recent.filter { it.type == "live" }.mapNotNull { byId[it.id] }
  val favChannels: List<Channel> get() = favs.mapNotNull { byId[it] }.sortedBy { it.number }

  fun toggleFav(id: String) {
    favs = if (id in favs) favs - id else favs + id
    store.saveFavs(favs)
  }

  fun removeRecent(id: String) {
    recent = recent.filterNot { it.id == id }
    store.saveRecent(recent)
  }

  fun play(list: List<Channel>, index: Int) {
    playing = LivePlay(list, index)
  }

  fun zap(step: Int) {
    val p = playing ?: return
    playing = p.copy(index = Math.floorMod(p.index + step, p.list.size))
  }

  fun markWatched(ch: Channel) {
    recent = (listOf(Recent("live", ch.id, System.currentTimeMillis())) + recent.filterNot { it.id == ch.id }).take(30)
    store.saveRecent(recent)
  }

  fun closePlayer() { playing = null }

  // ---- EPG: cached 2 min, de-duplicated ----

  private val epgCache = HashMap<String, Pair<Long, Deferred<List<Programme>>>>()
  private val epgGate = Semaphore(2) // be gentle with the portal

  suspend fun epg(id: String): List<Programme> {
    val hit = epgCache[id]
    val job = if (hit != null && System.currentTimeMillis() - hit.first < 120_000) hit.second else
      viewModelScope.async { epgGate.withPermit { runCatching { portal.epg(id) }.getOrDefault(emptyList()) } }
        .also { epgCache[id] = System.currentTimeMillis() to it }
    return job.await()
  }

  suspend fun liveLink(ch: Channel) = portal.liveLink(ch.cmd)
}
