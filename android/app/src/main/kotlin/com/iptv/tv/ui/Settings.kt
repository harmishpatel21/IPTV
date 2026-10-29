package com.iptv.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.BuildConfig
import com.iptv.tv.data.PortalConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Portal account setup. Shown full-screen on first launch (shareable builds have no details built in),
 * and as the Settings tab afterwards. Details are tested by logging in before they're saved.
 */
@Composable
fun SettingsScreen(vm: AppViewModel, restore: FocusRequester, firstRun: Boolean) {
  val scope = rememberCoroutineScope()
  val start = vm.config ?: PortalConfig("", "")
  var portal by remember { mutableStateOf(start.portal) }
  var mac by remember { mutableStateOf(start.mac) }
  var model by remember { mutableStateOf(start.model) }
  var serial by remember { mutableStateOf(start.serial) }
  var deviceId by remember { mutableStateOf(start.deviceId) }
  var deviceId2 by remember { mutableStateOf(start.deviceId2) }
  var signature by remember { mutableStateOf(start.signature) }
  var timezone by remember { mutableStateOf(start.timezone) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
  LaunchedEffect(Unit) { if (vm.focusKey == null) vm.focusKey = "cfg:portal" }

  fun save() {
    val c = PortalConfig(portal, mac, timezone, model, serial, deviceId, deviceId2, signature).normalized()
    c.problem()?.let { message = it to true; return }
    portal = c.portal; mac = c.mac
    busy = true
    message = "Connecting to the portal…" to false
    scope.launch {
      try {
        val tested = vm.testConfig(c)
        message = "Connected" to false
        vm.applyConfig(c, tested)
      } catch (e: CancellationException) { throw e } catch (e: Exception) {
        message = "Could not connect: ${e.message ?: "unknown error"}" to true
      } finally {
        busy = false
      }
    }
  }

  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = if (firstRun) 40.dp else 8.dp, bottom = 40.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Column(Modifier.padding(bottom = 8.dp)) {
        if (firstRun) Text("IPTV", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(if (firstRun) "Connect your IPTV subscription" else "Portal settings",
          fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.padding(top = 6.dp))
        Text(
          "Enter the details your provider gave you. If you use STBEmu, they're under Settings → Profiles → your profile " +
            "(STB Configuration for model and serial). Press OK on a field to type; hold the mic button to speak.",
          fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 6.dp).width(620.dp),
        )
      }
    }
    item { EditField(vm, restore, "cfg:portal", "Portal URL", portal, "http://example.com:8080/c/", KeyboardType.Uri) { portal = it } }
    item { EditField(vm, restore, "cfg:mac", "MAC address", mac, "00:1A:79:12:34:56", KeyboardType.Ascii) { mac = it } }
    item {
      Column {
        FieldLabel("STB model")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          items(PortalConfig.MODELS) { m -> Pill(m, m == model, Modifier.restorable(vm, "cfg:model:$m", restore)) { model = m } }
        }
      }
    }
    item { EditField(vm, restore, "cfg:serial", "Serial number (if your portal needs it)", serial, "optional", KeyboardType.Ascii) { serial = it } }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { EditField(vm, restore, "cfg:dev1", "Device ID", deviceId, "optional", KeyboardType.Ascii) { deviceId = it } }
        Box(Modifier.weight(1f)) { EditField(vm, restore, "cfg:dev2", "Device ID 2", deviceId2, "optional", KeyboardType.Ascii) { deviceId2 = it } }
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { EditField(vm, restore, "cfg:sig", "Signature", signature, "optional", KeyboardType.Ascii) { signature = it } }
        Box(Modifier.weight(1f)) { EditField(vm, restore, "cfg:tz", "Time zone (for the TV guide)", timezone, "e.g. America/Toronto", KeyboardType.Ascii) { timezone = it } }
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        BigButton(if (busy) "Connecting…" else "✓  Test & Save", primary = true, Modifier.restorable(vm, "cfg:save", restore)) { if (!busy) save() }
        message?.let { (text, isError) ->
          Text(text, fontSize = 12.sp, color = if (isError) Color(0xFFFF6B6B) else Color(0xFF46D369), modifier = Modifier.padding(start = 8.dp))
        }
      }
    }
    item {
      Text(
        "Changing the portal or MAC signs in as a different account and clears My List, Continue Watching and the channel list. " +
          "Many providers allow one device at a time, so close STBEmu while using this app.",
        fontSize = 10.sp, color = Dim, modifier = Modifier.padding(top = 10.dp).width(620.dp),
      )
    }
    if (!firstRun) item { Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 10.sp, color = Dim) }
  }
}

@Composable
private fun FieldLabel(text: String) =
  Text(text, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 5.dp))

/**
 * A text field that behaves like a button until OK is pressed; only then does the TV keyboard open.
 * (A plain text field pops the keyboard up whenever focus passes over it, trapping remote users.)
 */
@Composable
fun EditField(
  vm: AppViewModel, restore: FocusRequester, key: String, label: String, value: String, placeholder: String,
  keyboardType: KeyboardType, onChange: (String) -> Unit,
) {
  var editing by remember { mutableStateOf(false) }
  var wasEditing by remember { mutableStateOf(false) }
  val field = remember { FocusRequester() }
  val box = remember { FocusRequester() }
  var boxFocused by remember { mutableStateOf(false) }
  val keyboard = LocalSoftwareKeyboardController.current
  val shape = RoundedCornerShape(8.dp)

  Column(Modifier.fillMaxWidth()) {
    FieldLabel(label)
    if (editing) {
      var hadFocus by remember { mutableStateOf(false) }
      BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); editing = false }),
        modifier = Modifier.fillMaxWidth()
          .focusRequester(field)
          .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) editing = false }
          .background(Color(0xFF17171D), shape)
          .border(BorderStroke(2.dp, Accent), shape)
          .padding(horizontal = 14.dp, vertical = 10.dp),
      )
      LaunchedEffect(Unit) { wasEditing = true; field.requestFocus(); keyboard?.show() }
    } else {
      Surface(
        onClick = { vm.focusKey = key; editing = true },
        modifier = Modifier.fillMaxWidth().restorable(vm, key, restore).focusRequester(box).onFocusChanged { boxFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
          containerColor = Color(0xFF17171D), focusedContainerColor = Color(0xFF1F1F27),
          contentColor = Color.White, focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
          border = Border(BorderStroke(2.dp, Color(0xFF2A2A33)), shape = shape),
          focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
      ) {
        Text(value.ifEmpty { placeholder }, fontSize = 14.sp, color = if (value.isEmpty()) Dim else Color.White, maxLines = 1,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
      }
      // After typing, put focus back on this field (the text field that had it is gone).
      LaunchedEffect(Unit) {
        if (!wasEditing) return@LaunchedEffect
        // The keyboard is still closing: keep asking until focus actually lands here.
        repeat(20) { if (boxFocused) return@LaunchedEffect; runCatching { box.requestFocus() }; delay(50) }
      }
    }
  }
}
