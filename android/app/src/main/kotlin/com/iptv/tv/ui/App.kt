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
  // One requester, attached to whichever card was focused last, so focus returns there after the player.
  val restore = remember { FocusRequester() }
  val playing = vm.playing

  MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
    Box(Modifier.fillMaxSize().background(Bg)) {
      // The browse UI stays composed under the player, so scroll position and focus survive.
      Column(Modifier.fillMaxSize()) {
        TopNav(vm)
        when (vm.tab) {
          Tab.Home -> HomeScreen(vm, restore)
          Tab.Live -> LiveScreen(vm, restore)
          Tab.Movies -> ComingSoon("Movies & Shows")
          Tab.MyList -> MyListScreen(vm, restore)
        }
      }
      vm.status?.let {
        Text(it, color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
      }
      if (playing != null) LivePlayer(vm, playing)
    }
  }

  BackHandler(enabled = playing == null && vm.tab != Tab.Home) { vm.tab = Tab.Home; vm.focusKey = null }

  LaunchedEffect(playing == null, vm.focusKey != null, vm.tab) {
    if (playing == null && vm.focusKey != null) {
      // The target card may not be laid out yet (first launch, tab switch): retry briefly.
      repeat(15) {
        delay(100)
        if (runCatching { restore.requestFocus() }.isSuccess) return@LaunchedEffect
      }
    }
  }
}

