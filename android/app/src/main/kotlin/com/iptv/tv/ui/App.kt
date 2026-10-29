package com.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay

@Composable
fun App(vm: AppViewModel = viewModel()) {
  // One requester for the browse screens and one for the details overlay. Each is attached to whichever
  // element was focused last (vm.focusKey / vm.detailFocusKey), so focus returns there after an overlay closes.
  val restore = remember { FocusRequester() }
  val detailRestore = remember { FocusRequester() }
  val detail = vm.detail

  MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
    // No account yet (shareable build, first launch): setup is the whole app until it succeeds.
    if (vm.config == null) {
      Box(Modifier.fillMaxSize().background(Bg)) { SettingsScreen(vm, restore, firstRun = true) }
      LaunchedEffect(Unit) {
        repeat(15) { delay(100); if (runCatching { restore.requestFocus() }.isSuccess) return@LaunchedEffect }
      }
      return@MaterialTheme
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
      // Browse UI stays composed under overlays, so scroll position and focus survive.
      Column(Modifier.fillMaxSize()) {
        TopNav(vm)
        when (vm.tab) {
          Tab.Home -> HomeScreen(vm, restore)
          Tab.Search -> SearchScreen(vm, restore)
          Tab.Live -> LiveScreen(vm, restore)
          Tab.Movies -> MoviesScreen(vm, restore)
          Tab.MyList -> MyListScreen(vm, restore)
          Tab.Settings -> SettingsScreen(vm, restore, firstRun = false)
        }
      }
      vm.status?.let {
        Text(it, color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
      }
      if (detail != null) DetailScreen(vm, detail, detailRestore)
      vm.playing?.let { LivePlayer(vm, it) }
      vm.vodPlaying?.let { VodPlayer(vm, it) }
    }
  }

  // Back: players handle their own; then details → category grid → Home.
  val overlay = vm.overlayOpen
  BackHandler(enabled = !overlay && detail != null) { vm.closeDetail() }
  BackHandler(enabled = !overlay && detail == null && vm.tab == Tab.Movies && vm.vodCat != null) {
    vm.focusKey = "vrow:${vm.vodCat!!.id}:more"
    vm.vodCat = null
  }
  BackHandler(enabled = !overlay && detail == null && vm.vodCat == null && vm.tab != Tab.Home) {
    vm.tab = Tab.Home
    vm.focusKey = null
  }

  LaunchedEffect(overlay, detail?.id, vm.focusKey != null, vm.tab, vm.vodCat?.id) {
    if (overlay) return@LaunchedEffect
    val target = if (detail != null) detailRestore else if (vm.focusKey != null) restore else return@LaunchedEffect
    // The target may not be laid out yet (first launch, tab switch, overlay just closed): retry briefly.
    repeat(15) {
      delay(100)
      if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
    }
  }
}
