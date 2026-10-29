package com.iptv.tv.data

import com.iptv.tv.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.TimeZone

/** The account the app logs in with — the same details STBEmu asks for. */
data class PortalConfig(
  val portal: String,
  val mac: String,
  val timezone: String = TimeZone.getDefault().id,
  val model: String = "MAG250",
  val serial: String = "",
  val deviceId: String = "",
  val deviceId2: String = "",
  val signature: String = "",
) {
  val isComplete: Boolean get() = portal.isNotBlank() && mac.isNotBlank()

  /** Same account (portal + MAC)? Changing either means different channels, lists and history. */
  fun sameAccount(other: PortalConfig?) = other != null && portal == other.portal && mac == other.mac

  /** Tidy what was typed with a remote: add http://, upper-case the MAC and insert colons if they were skipped. */
  fun normalized(): PortalConfig {
    val url = portal.trim().let { if (it.isNotEmpty() && "://" !in it) "http://$it" else it }
    val hex = mac.trim().uppercase().filter { it.isLetterOrDigit() }
    val m = if (hex.length == 12) hex.chunked(2).joinToString(":") else mac.trim().uppercase()
    return copy(portal = url, mac = m, timezone = timezone.trim().ifEmpty { TimeZone.getDefault().id },
      model = model.trim().ifEmpty { "MAG250" }, serial = serial.trim(), deviceId = deviceId.trim(),
      deviceId2 = deviceId2.trim(), signature = signature.trim())
  }

  /** Problem with the typed details, or null if they look usable. */
  fun problem(): String? = when {
    portal.isBlank() -> "Enter the portal URL"
    portal.toHttpUrlOrNull() == null -> "That portal URL doesn't look right"
    !Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac) -> "The MAC address should look like 00:1A:79:12:34:56"
    else -> null
  }

  companion object {
    val MODELS = listOf("MAG250", "MAG254", "MAG256", "MAG322", "MAG324", "MAG349", "MAG351", "MAG420", "MAG424", "MAG520", "MAG524")

    /** Details baked in at build time (debug builds made with a config.json); empty in shareable release builds. */
    fun fromBuild() = PortalConfig(
      BuildConfig.PORTAL, BuildConfig.MAC.uppercase(), BuildConfig.TIMEZONE.ifEmpty { TimeZone.getDefault().id },
      BuildConfig.STB_MODEL.ifEmpty { "MAG250" }, BuildConfig.SERIAL, BuildConfig.DEVICE_ID, BuildConfig.DEVICE_ID2,
      BuildConfig.SIGNATURE,
    )
  }
}
