package com.iptv.tv.data

import android.util.JsonReader
import android.util.Log
import android.util.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "Portal"

const val MAG_USER_AGENT =
  "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

/**
 * Stalker/Ministra portal client, ported from the web player's server.js.
 * Auth is by MAC + STB identity; tokens expire silently, so every call re-handshakes and retries once.
 */
class Portal(
  cfg: PortalConfig,
  /** API address that worked last time (saved by the app), so we don't probe candidates on every start. */
  knownEndpoint: String? = null,
  private val onEndpoint: (String) -> Unit = {},
) {
  private val portalUrl = cfg.portal
  private val mac = cfg.mac
  private val timezone = cfg.timezone
  private val model = cfg.model
  private val serial = cfg.serial
  private val deviceId = cfg.deviceId
  private val deviceId2 = cfg.deviceId2.ifEmpty { cfg.deviceId }
  private val signature = cfg.signature

  val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

  private val loginLock = Mutex()
  @Volatile private var token: String? = null
  @Volatile private var endpoint: String? = knownEndpoint

  val origin: String get() = portalUrl.toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}" }

  // Portals expose the API at different paths; derive candidates from the /c/ URL.
  private fun candidates(): List<String> {
    val prefix = portalUrl.toHttpUrl().encodedPath.removeSuffix("/").removeSuffix("/c").removeSuffix("/")
    return buildList {
      if (prefix.isNotEmpty()) add("$origin$prefix/server/load.php")
      add("$origin/portal.php")
      add("$origin/stalker_portal/server/load.php")
      add("$origin/server/load.php")
    }.distinct()
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  private fun request(ep: String, params: Map<String, String>): Request {
    val url = ep.toHttpUrl().newBuilder().apply {
      params.forEach { (k, v) -> addQueryParameter(k, v) }
      addQueryParameter("JsHttpRequest", "1-xml")
    }.build()
    return Request.Builder().url(url)
      .header("User-Agent", MAG_USER_AGENT)
      .header("X-User-Agent", "Model: $model; Link: WiFi")
      .header("Referer", portalUrl)
      .header("Accept", "*/*")
      .header("Cookie", "mac=${enc(mac)}; stb_lang=en; timezone=${enc(timezone)}")
      .apply { token?.let { header("Authorization", "Bearer $it") } }
      .build()
  }

  private suspend fun <T> raw(ep: String, params: Map<String, String>, parse: (Response) -> T): T =
    withContext(Dispatchers.IO) { http.newCall(request(ep, params)).execute().use(parse) }

  private fun js(resp: Response): Any? {
    if (resp.code == 429) throw RateLimited()
    val text = resp.body.string()
    val obj = try { JSONObject(text) } catch (e: JSONException) {
      val gist = text.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(60)
      throw IOException("Portal returned HTTP ${resp.code}${if (gist.isNotEmpty()) " ($gist)" else ""}")
    }
    return obj.opt("js").takeUnless { it == JSONObject.NULL }
  }

  /** Re-login unless another caller already replaced the token we saw fail. */
  private suspend fun login(stale: String?) = loginLock.withLock { if (token == null || token == stale) doLogin() }

  private suspend fun doLogin() {
    token = null
    var firstErr: Exception? = null // the first failure is the informative one; later candidates usually just 404
    val tries = endpoint?.let { listOf(it) + candidates().filter { c -> c != it } } ?: candidates()
    for (ep in tries) {
      // The portal answers rapid logins with HTTP 429: back off and retry the same address.
      for (attempt in 0..2) {
        try {
          val t = (raw(ep, mapOf("type" to "stb", "action" to "handshake", "token" to ""), ::js) as? JSONObject)?.optString("token")
          if (!t.isNullOrEmpty()) { token = t; break }
          firstErr = firstErr ?: IOException("No token from $ep")
          break
        } catch (e: CancellationException) { throw e } catch (e: RateLimited) {
          Log.w(TAG, "handshake $ep: rate limited")
          firstErr = firstErr ?: e
          delay(3000L * (attempt + 1))
        } catch (e: Exception) { Log.w(TAG, "handshake $ep failed", e); firstErr = firstErr ?: e; break }
      }
      if (token != null) {
        if (ep != endpoint) { endpoint = ep; onEndpoint(ep) }
        break
      }
    }
    val ep = endpoint
    if (token == null || ep == null) throw IOException("Could not log in to the portal: ${firstErr?.message}")

    val profile = raw(ep, mapOf(
      "type" to "stb", "action" to "get_profile", "hd" to "1", "stb_type" to model, "sn" to serial,
      "device_id" to deviceId, "device_id2" to deviceId2, "signature" to signature, "num_banks" to "2",
      "image_version" to "218", "hw_version" to "1.7-BD-00", "not_valid_token" to "0", "auth_second_step" to "1",
      "api_signature" to "262",
    ), ::js) as? JSONObject
    if (profile != null && (profile.optString("blocked") == "1" || profile.optInt("status") == 1)) {
      token = null
      throw IOException("Portal rejected this device: ${profile.optString("msg")}")
    }
  }

  /** Authenticated call; on any failure re-login once (tokens expire silently). */
  suspend fun <T> call(params: Map<String, String>, parse: (Response) -> T): T {
    if (token == null) login(null)
    val seen = token
    return try {
      raw(endpoint!!, params, parse)
    } catch (e: CancellationException) { throw e } catch (e: RateLimited) {
      delay(3000) // busy, not logged out: logging in again would only make it worse
      raw(endpoint!!, params, parse)
    } catch (e: Exception) {
      login(seen)
      raw(endpoint!!, params, parse)
    }
  }

  suspend fun call(params: Map<String, String>): Any = call(params) { js(it) ?: throw IOException("Empty response") }

  // ---- Live TV ----

  suspend fun genres(): List<Genre> {
    val arr = call(mapOf("type" to "itv", "action" to "get_genres")) as JSONArray
    return (0 until arr.length()).map { arr.getJSONObject(it) }
      .filter { it.optString("id") != "*" }
      .map { Genre(it.optString("id"), it.optString("title").trim()) }
  }

  /** ~11k channels / 12 MB on this portal: stream-parse instead of building a JSON tree. */
  suspend fun channels(): List<Channel> =
    call(mapOf("type" to "itv", "action" to "get_all_channels")) { resp -> parseChannels(resp) }
      .sortedBy { it.number }

  private fun parseChannels(resp: Response): List<Channel> {
    val out = ArrayList<Channel>(12_000)
    JsonReader(resp.body.charStream()).use { r ->
      r.beginObject()
      while (r.hasNext()) {
        if (r.nextName() != "js") { r.skipValue(); continue }
        r.beginObject()
        while (r.hasNext()) {
          if (r.nextName() != "data") { r.skipValue(); continue }
          r.beginArray()
          while (r.hasNext()) out += readChannel(r)
          r.endArray()
        }
        r.endObject()
      }
      r.endObject()
    }
    if (out.isEmpty()) throw IOException("Portal returned no channels")
    return out
  }

  private fun readChannel(r: JsonReader): Channel {
    var id = ""; var name = ""; var number = 0; var genre = ""; var cmd = ""
    r.beginObject()
    while (r.hasNext()) {
      when (r.nextName()) {
        "id" -> id = r.str()
        "name" -> name = r.str()
        "number" -> number = r.str().toIntOrNull() ?: 0
        "tv_genre_id" -> genre = r.str()
        "cmd" -> cmd = r.str()
        else -> r.skipValue()
      }
    }
    r.endObject()
    return Channel(id, name, number, genre, cmd)
  }

  suspend fun liveLink(cmd: String): String {
    val js = call(mapOf("type" to "itv", "action" to "create_link", "cmd" to cmd, "series" to "",
      "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0")) as JSONObject
    return playable(js.optString("cmd")) ?: throw IOException("Portal did not return a playable link")
  }

  suspend fun epg(channelId: String): List<Programme> {
    val arr = call(mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to channelId, "size" to "4")) as? JSONArray
      ?: return emptyList()
    return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
      Programme(it.optString("name"), it.optString("descr"),
        it.optString("start_timestamp").toLongOrNull()?.times(1000) ?: 0,
        it.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: 0)
    }
  }

  // ---- Video Club (VOD). Series live in the same API: title → seasons → episodes → files. ----

  suspend fun vodCategories(): List<VodCategory> {
    val arr = call(mapOf("type" to "vod", "action" to "get_categories")) as JSONArray
    return (0 until arr.length()).map { arr.getJSONObject(it) }
      .filter { it.optString("id") != "*" }
      .map { VodCategory(it.optString("id"), it.optString("title").replace(Regex("\\s+"), " ").trim()) }
  }

  private fun vodItem(v: JSONObject): VodItem {
    val pic = v.optString("screenshot_uri")
    return VodItem(
      id = v.optString("id"), name = v.optString("name"), year = v.optString("year"),
      descr = v.optString("description"),
      poster = pic.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("http")) it else origin + it },
      rating = listOf(v.optDouble("rating_imdb"), v.optDouble("rating_kinopoisk")).firstOrNull { !it.isNaN() && it > 0 } ?: 0.0,
      genres = v.optString("genres_str"), director = v.optString("director"), actors = v.optString("actors"),
      country = v.optString("country"), isSeries = v.optString("is_series") == "1",
      cat = v.optString("category_id"), cmd = v.optString("cmd"),
    )
  }

  suspend fun vodList(cat: String?, page: Int, search: String = "", sort: String = "added"): VodPage {
    val js = call(mapOf("type" to "vod", "action" to "get_ordered_list", "category" to (cat ?: "*"), "genre" to "*",
      "sortby" to sort, "search" to search, "p" to page.toString())) as JSONObject
    val total = js.optInt("total_items")
    val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
    val data = js.optJSONArray("data") ?: JSONArray()
    return VodPage(total, (total + per - 1) / per, page, (0 until data.length()).map { vodItem(data.getJSONObject(it)) })
  }

  /** Seasons/episodes/files are paged like everything else; fetch every page. */
  private suspend fun vodAll(params: Map<String, String>): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    for (p in 1..50) {
      val js = call(mapOf("type" to "vod", "action" to "get_ordered_list", "genre" to "*") + params + ("p" to p.toString())) as JSONObject
      val data = js.optJSONArray("data") ?: break
      (0 until data.length()).forEach { out += data.getJSONObject(it) }
      val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
      if (data.length() == 0 || p * per >= js.optInt("total_items")) break
    }
    return out
  }

  suspend fun seasons(item: VodItem): List<Season> =
    vodAll(mapOf("movie_id" to item.id, "season_id" to "0", "episode_id" to "0", "category" to item.cat.ifEmpty { "*" }))
      .filter { it.optBoolean("is_season") }
      .map { Season(it.optString("id"), it.optString("season_number").toIntOrNull() ?: 0, it.optString("name")) }
      .sortedBy { it.number }

  suspend fun episodes(item: VodItem, season: Season): List<Episode> =
    vodAll(mapOf("movie_id" to item.id, "season_id" to season.id, "episode_id" to "0", "category" to item.cat.ifEmpty { "*" }))
      .filter { it.optBoolean("is_episode") }
      .map { Episode(it.optString("id"), it.optString("series_number").toIntOrNull() ?: 0,
        it.optString("series_name").ifEmpty { it.optString("name") }) }
      .sortedBy { it.number }

  /** Movies and episodes resolve the same way: list the title's files, then create_link on a file's cmd. */
  suspend fun vodLink(item: VodItem, season: Season? = null, episode: Episode? = null): String {
    val files = vodAll(mapOf("movie_id" to item.id, "season_id" to (season?.id ?: "0"),
      "episode_id" to (episode?.id ?: "0"), "category" to item.cat.ifEmpty { "*" }))
      .filter { it.optBoolean("is_file") && it.optString("cmd").isNotEmpty() }
      .map { it.optString("cmd") }
    for (c in files + listOfNotNull(item.cmd.takeIf { episode == null && it.isNotEmpty() })) {
      val js = call(mapOf("type" to "vod", "action" to "create_link", "cmd" to c, "series" to "",
        "forced_storage" to "", "disable_ad" to "0", "download" to "0")) as JSONObject
      playable(js.optString("cmd"))?.let { return it }
    }
    throw IOException("Portal did not return a playable link for this title")
  }

  private fun playable(cmd: String): String? =
    cmd.replace(Regex("^(ffmpeg|ffrt\\d*|auto)\\s+", RegexOption.IGNORE_CASE), "").trim()
      .takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

class RateLimited : IOException("The portal is busy (too many requests) — try again in a minute")

/** Read any scalar as a string (portals mix numbers and strings freely). */
internal fun JsonReader.str(): String = when (peek()) {
  JsonToken.NULL -> { nextNull(); "" }
  JsonToken.STRING, JsonToken.NUMBER -> nextString()
  JsonToken.BOOLEAN -> nextBoolean().toString()
  else -> { skipValue(); "" }
}
