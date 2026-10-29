package com.iptv.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Something the viewer watched, newest first: a live channel (`type = "live"`, id = channel id) or a
 * movie/series (`type = "vod"`, id = title id) with the episode and position to resume from.
 */
data class Recent(
  val type: String, val id: String, val at: Long,
  val item: VodItem? = null,
  val seasonId: String? = null, val seasonNum: Int = 0,
  val epId: String? = null, val epNum: Int = 0, val epName: String = "",
  val pos: Long = 0, val dur: Long = 0,
) {
  val label: String get() = if (epId != null) "S$seasonNum:E$epNum" else ""
}

/** Resume position of one movie or episode, in ms. */
data class Progress(val pos: Long, val dur: Long, val at: Long)

/** Favourites, watch history and positions in SharedPreferences; channel list cached on disk. */
class Store(context: Context) {
  private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
  private val channelFile = File(context.filesDir, "channels.json")

  fun favs(): Set<String> = prefs.getStringSet("favs", emptySet())!!.toSet()
  fun saveFavs(favs: Set<String>) = prefs.edit().putStringSet("favs", favs).apply()

  var endpoint: String?
    get() = prefs.getString("endpoint", null)
    set(v) = prefs.edit().putString("endpoint", v).apply()

  var vodGroup: String
    get() = prefs.getString("vodGroup", "") ?: ""
    set(v) = prefs.edit().putString("vodGroup", v).apply()

  fun recent(): List<Recent> = readArray("recent").map { o ->
    Recent(
      type = o.optString("type", "live"), id = o.getString("id"), at = o.optLong("at"),
      item = o.optJSONObject("item")?.let(::itemFromJson),
      seasonId = o.optString("seasonId").ifEmpty { null }, seasonNum = o.optInt("seasonNum"),
      epId = o.optString("epId").ifEmpty { null }, epNum = o.optInt("epNum"), epName = o.optString("epName"),
      pos = o.optLong("pos"), dur = o.optLong("dur"),
    )
  }

  fun saveRecent(list: List<Recent>) = writeArray("recent", list.map { r ->
    JSONObject().put("type", r.type).put("id", r.id).put("at", r.at).apply {
      r.item?.let { put("item", itemToJson(it)) }
      r.epId?.let { put("seasonId", r.seasonId).put("seasonNum", r.seasonNum).put("epId", it).put("epNum", r.epNum).put("epName", r.epName) }
      if (r.dur > 0) put("pos", r.pos).put("dur", r.dur)
    }
  })

  fun vodList(): List<VodItem> = readArray("vodList").map(::itemFromJson)
  fun saveVodList(list: List<VodItem>) = writeArray("vodList", list.map(::itemToJson))

  fun progress(): Map<String, Progress> = runCatching {
    val o = JSONObject(prefs.getString("vodProgress", "{}")!!)
    o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { Progress(it.getLong(0), it.getLong(1), it.getLong(2)) } }
  }.getOrDefault(emptyMap())

  fun saveProgress(map: Map<String, Progress>) {
    // Keep the 500 most recent positions.
    val kept = map.entries.sortedByDescending { it.value.at }.take(500)
    val o = JSONObject()
    kept.forEach { (k, p) -> o.put(k, JSONArray().put(p.pos).put(p.dur).put(p.at)) }
    prefs.edit().putString("vodProgress", o.toString()).apply()
  }

  private fun readArray(key: String): List<JSONObject> = runCatching {
    val arr = JSONArray(prefs.getString(key, "[]"))
    (0 until arr.length()).map { arr.getJSONObject(it) }
  }.getOrDefault(emptyList())

  private fun writeArray(key: String, items: List<JSONObject>) =
    prefs.edit().putString(key, JSONArray().apply { items.forEach { put(it) } }.toString()).apply()

  private fun itemToJson(i: VodItem) = JSONObject()
    .put("id", i.id).put("name", i.name).put("year", i.year).put("descr", i.descr.take(500)).put("poster", i.poster ?: "")
    .put("rating", i.rating).put("genres", i.genres).put("isSeries", i.isSeries).put("cat", i.cat).put("cmd", i.cmd)

  private fun itemFromJson(o: JSONObject) = VodItem(
    id = o.optString("id"), name = o.optString("name"), year = o.optString("year"), descr = o.optString("descr"),
    poster = o.optString("poster").ifEmpty { null }, rating = o.optDouble("rating", 0.0), genres = o.optString("genres"),
    director = "", actors = "", country = "", isSeries = o.optBoolean("isSeries"), cat = o.optString("cat"), cmd = o.optString("cmd"),
  )

  // ---- Channel cache (the portal's list is 12 MB; ours is a compact array of arrays) ----

  val channelCacheAge: Long get() = if (channelFile.exists()) System.currentTimeMillis() - channelFile.lastModified() else Long.MAX_VALUE

  fun loadChannels(): Pair<List<Genre>, List<Channel>>? = runCatching {
    JsonReader(channelFile.bufferedReader()).use { r ->
      val genres = ArrayList<Genre>()
      val channels = ArrayList<Channel>(12_000)
      r.beginObject()
      while (r.hasNext()) {
        when (r.nextName()) {
          "genres" -> { r.beginArray(); while (r.hasNext()) { r.beginArray(); genres += Genre(r.str(), r.str()); r.endArray() }; r.endArray() }
          "channels" -> {
            r.beginArray()
            while (r.hasNext()) {
              r.beginArray()
              channels += Channel(r.str(), r.str(), r.str().toIntOrNull() ?: 0, r.str(), r.str())
              r.endArray()
            }
            r.endArray()
          }
          else -> r.skipValue()
        }
      }
      r.endObject()
      genres to channels
    }
  }.getOrNull()?.takeIf { it.second.isNotEmpty() }

  fun saveChannels(genres: List<Genre>, channels: List<Channel>) {
    val tmp = File(channelFile.path + ".tmp")
    JsonWriter(tmp.bufferedWriter()).use { w ->
      w.beginObject()
      w.name("genres").beginArray()
      genres.forEach { w.beginArray().value(it.id).value(it.title).endArray() }
      w.endArray()
      w.name("channels").beginArray()
      channels.forEach { w.beginArray().value(it.id).value(it.name).value(it.number.toString()).value(it.genre).value(it.cmd).endArray() }
      w.endArray()
      w.endObject()
    }
    tmp.renameTo(channelFile)
  }
}
