package com.iptv.tv.data

data class Genre(val id: String, val title: String)

data class Channel(val id: String, val name: String, val number: Int, val genre: String, val cmd: String)

data class Programme(val title: String, val descr: String, val start: Long, val end: Long)

/** Programme on now (with how far through it is, 0..1) and the one after. */
data class NowNext(val now: Programme?, val next: Programme?, val progress: Float)

fun List<Programme>.nowNext(at: Long = System.currentTimeMillis()): NowNext {
  val now = firstOrNull { it.start <= at && at < it.end }
  val next = firstOrNull { it.start >= (now?.end ?: at) }
  val progress = now?.let { ((at - it.start).toFloat() / (it.end - it.start)).coerceIn(0f, 1f) } ?: 0f
  return NowNext(now, next, progress)
}

// ---- Video Club (VOD): category → titles; a series has seasons → episodes ----

data class VodCategory(val id: String, val title: String)

data class VodItem(
  val id: String, val name: String, val year: String, val descr: String, val poster: String?,
  val rating: Double, val genres: String, val director: String, val actors: String, val country: String,
  val isSeries: Boolean, val cat: String, val cmd: String,
)

data class VodPage(val total: Int, val pages: Int, val page: Int, val items: List<VodItem>)

data class Season(val id: String, val number: Int, val name: String)

data class Episode(val id: String, val number: Int, val name: String)
