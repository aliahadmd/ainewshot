package com.aliahad.ainewshot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aliahad.ainewshot.ui.DocRequest
import com.aliahad.ainewshot.ui.DocScreen
import com.aliahad.ainewshot.ui.EpisodeScreen
import com.aliahad.ainewshot.ui.FeedScreen
import com.aliahad.ainewshot.ui.ItemScreen
import com.aliahad.ainewshot.ui.MiniPlayer
import com.aliahad.ainewshot.ui.PodcastScreen
import com.aliahad.ainewshot.ui.ReportsScreen
import com.aliahad.ainewshot.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val openTab = mutableIntStateOf(0)
    private val openEpisode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(this)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root(openTab, openEpisode) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: android.content.Intent?) {
        intent?.getIntExtra("tab", -1)?.takeIf { it >= 0 }?.let { openTab.intValue = it }
        intent?.getStringExtra("episode")?.let { openEpisode.value = it; openTab.intValue = 1 }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), content = content)
}

@Composable
private fun Root(tabState: androidx.compose.runtime.MutableIntState, episodeState: androidx.compose.runtime.MutableState<String?>) {
    var tab by tabState
    var episode by episodeState
    var item by remember { mutableStateOf<NewsItem?>(null) }
    var doc by remember { mutableStateOf<DocRequest?>(null) }

    BackHandler(item != null || doc != null || episode != null) {
        when {
            item != null -> item = null
            doc != null -> doc = null
            else -> episode = null
        }
    }

    Scaffold(bottomBar = {
        Column {
            MiniPlayer(onOpen = { tab = 1; episode = it })
            NavigationBar {
                listOf(Triple("News", Icons.Default.Home, 0), Triple("Podcast", Icons.Default.PlayArrow, 1),
                    Triple("Reports", Icons.AutoMirrored.Filled.List, 2), Triple("Settings", Icons.Default.Settings, 3)).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; item = null; doc = null; if (i != 1) episode = null },
                        icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                item != null -> ItemScreen(item!!, onBack = { item = null })
                doc != null -> DocScreen(doc!!, onBack = { doc = null })
                tab == 1 && episode != null -> EpisodeScreen(episode!!, onBack = { episode = null })
                tab == 0 -> FeedScreen(onOpen = { item = it }, onSettings = { tab = 3 })
                tab == 1 -> PodcastScreen(onOpen = { episode = it })
                tab == 2 -> ReportsScreen(onOpen = { doc = it })
                else -> SettingsScreen()
            }
        }
    }
}
