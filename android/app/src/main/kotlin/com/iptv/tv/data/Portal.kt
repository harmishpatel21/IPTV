package com.iptv.tv.data

import android.util.JsonReader
import android.util.JsonToken
import com.iptv.tv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

const val MAG_USER_AGENT =
  "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

/**
 * Stalker/Ministra portal client, ported from the web player's server.js.
 * Auth is by MAC + STB identity; tokens expire silently, so every call re-handshakes and retries once.
 */
class Portal(
  private val portalUrl: String = BuildConfig.PORTAL,
  private val mac: String = BuildConfig.MAC.uppercase(),
  private val timezone: String = BuildConfig.TIMEZONE,
  private val model: String = BuildConfig.STB_MODEL,
  private val serial: String = BuildConfig.SERIAL,
  private val deviceId: String = BuildConfig.DEVICE_ID,
  private val deviceId2: String = BuildConfig.DEVICE_ID2.ifEmpty { BuildConfig.DEVICE_ID },
  private val signature: String = BuildConfig.SIGNATURE,
) {
  val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

  private val loginLock = Mutex()
  @Volatile private var token: String? = null
  @Volatile private var endpoint: String? = null

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
    val text = resp.body.string()
    val obj = try { JSONObject(text) } catch (e: JSONException) {
      throw IOException("Portal returned non-JSON (HTTP ${resp.code}): ${text.take(80)}")
    }
    return obj.opt("js").takeUnless { it == JSONObject.NULL }
  }

  /** Re-login unless another caller already replaced the token we saw fail. */
  private suspend fun login(stale: String?) = loginLock.withLock { if (token == null || token == stale) doLogin() }

  private suspend fun doLogin() {
    token = null
    var lastErr: Exception? = null
    for (ep in endpoint?.let { listOf(it) } ?: candidates()) {
      try {
        val t = (raw(ep, mapOf("type" to "stb", "action" to "handshake", "token" to ""), ::js) as? JSONObject)?.optString("token")
        if (!t.isNullOrEmpty()) { endpoint = ep; token = t; break }
        lastErr = IOException("No token from $ep")
      } catch (e: CancellationException) { throw e } catch (e: Exception) { lastErr = e }
    }
    val ep = endpoint ?: throw IOException("Handshake failed: ${lastErr?.message}")
    if (token == null) throw IOException("Handshake failed: ${lastErr?.message}")

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
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
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

  private fun playable(cmd: String): String? =
    cmd.replace(Regex("^(ffmpeg|ffrt\\d*|auto)\\s+", RegexOption.IGNORE_CASE), "").trim()
      .takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/** Read any scalar as a string (portals mix numbers and strings freely). */
internal fun JsonReader.str(): String = when (peek()) {
  JsonToken.NULL -> { nextNull(); "" }
  JsonToken.STRING, JsonToken.NUMBER -> nextString()
  JsonToken.BOOLEAN -> nextBoolean().toString()
  else -> { skipValue(); "" }
}
