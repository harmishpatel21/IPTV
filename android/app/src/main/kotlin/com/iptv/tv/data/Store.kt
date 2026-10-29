package com.iptv.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Something the viewer watched, newest first. `type` is "live" for now; movies/episodes come later. */
data class Recent(val type: String, val id: String, val at: Long)

/** Favourites + watch history in SharedPreferences, channel list cached on disk. */
class Store(context: Context) {
  private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
  private val channelFile = File(context.filesDir, "channels.json")

  fun favs(): Set<String> = prefs.getStringSet("favs", emptySet())!!.toSet()
  fun saveFavs(favs: Set<String>) = prefs.edit().putStringSet("favs", favs).apply()

  fun recent(): List<Recent> = runCatching {
    val arr = JSONArray(prefs.getString("recent", "[]"))
    (0 until arr.length()).map { arr.getJSONObject(it) }
      .map { Recent(it.optString("type", "live"), it.getString("id"), it.optLong("at")) }
  }.getOrDefault(emptyList())

  fun saveRecent(list: List<Recent>) {
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().put("type", it.type).put("id", it.id).put("at", it.at)) }
    prefs.edit().putString("recent", arr.toString()).apply()
  }

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
