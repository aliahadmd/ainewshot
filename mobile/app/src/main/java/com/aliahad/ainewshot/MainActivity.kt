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
import com.aliahad.ainewshot.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val openTab = mutableIntStateOf(0)
    private val openEpisode = mutableStateOf<String?>(null)
    private val openItem = mutableStateOf<String?>(null)
    private val openStory = mutableStateOf<com.aliahad.ainewshot.ui.StoryRequest?>(null)
    private val openBriefing = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(this)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root(openTab, openEpisode, openItem, openStory, openBriefing) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: android.content.Intent?) {
        intent?.getIntExtra("tab", -1)?.takeIf { it >= 0 }?.let { openTab.intValue = it }
        intent?.getStringExtra("episode")?.let { openEpisode.value = it; openTab.intValue = 1 }
        // From the widget / ticker notification: open a story directly.
        intent?.getStringExtra("item")?.let { openItem.value = it; openTab.intValue = 0 }
        // A delivered briefing: open it in the in-app reader.
        if (intent?.getBooleanExtra("briefing", false) == true) { openTab.intValue = 2; openBriefing.value = true }
        intent?.getStringExtra("story")?.let { sid ->
            openStory.value = com.aliahad.ainewshot.ui.StoryRequest(sid, intent.getStringExtra("title") ?: "Story", "${AIHot.BASE}/story/$sid")
            openTab.intValue = 0
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), content = content)
}

@Composable
private fun Root(
    tabState: androidx.compose.runtime.MutableIntState,
    episodeState: androidx.compose.runtime.MutableState<String?>,
    itemIdState: androidx.compose.runtime.MutableState<String?>,
    storyState: androidx.compose.runtime.MutableState<com.aliahad.ainewshot.ui.StoryRequest?>,
    briefingState: androidx.compose.runtime.MutableState<Boolean>,
) {
    var tab by tabState
    var episode by episodeState
    var story by storyState
    val newsTab = remember { mutableIntStateOf(0) }
    var item by remember { mutableStateOf<NewsItem?>(null) }
    var doc by remember { mutableStateOf<DocRequest?>(null) }
    var setup by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var catchUp by remember { mutableStateOf(false) }

    // Opened from the widget: look the story up in the feed (or saved).
    val pendingItem = itemIdState.value
    if (pendingItem != null) {
        androidx.compose.runtime.LaunchedEffect(pendingItem) {
            item = (NewsRepo.recent.value + NewsRepo.saved.value).firstOrNull { it.id == pendingItem }
            itemIdState.value = null
        }
    }

    // From a briefing alert: open the delivered briefing in the reader.
    if (briefingState.value) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            NewsRepo.briefing.value?.let { doc = com.aliahad.ainewshot.ui.briefingDoc(it) }
            briefingState.value = false
        }
    }

    BackHandler(item != null || doc != null || episode != null || setup || story != null || search || catchUp) {
        when {
            setup -> setup = false
            item != null -> item = null
            story != null -> story = null
            doc != null -> doc = null
            catchUp -> catchUp = false
            search -> search = false
            else -> episode = null
        }
    }

    Scaffold(bottomBar = {
        Column {
            MiniPlayer(onOpen = { tab = 1; episode = it })
            NavigationBar {
                listOf(Triple("News", Icons.Default.Home, 0), Triple("Podcast", Icons.Default.PlayArrow, 1),
                    Triple("Briefings", Icons.AutoMirrored.Filled.List, 2), Triple("Settings", Icons.Default.Settings, 3)).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = {
                        tab = i; item = null; doc = null; setup = false; story = null; search = false; catchUp = false
                        if (i != 1) episode = null
                    }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                setup -> com.aliahad.ainewshot.ui.SetupScreen(onBack = { setup = false })
                item != null -> ItemScreen(item!!, onBack = { item = null })
                story != null -> com.aliahad.ainewshot.ui.StoryScreen(story!!, onBack = { story = null })
                doc != null -> DocScreen(doc!!, onBack = { doc = null })
                catchUp -> com.aliahad.ainewshot.ui.CatchUpScreen(onOpen = { item = it }, onBack = { catchUp = false })
                search -> com.aliahad.ainewshot.ui.SearchScreen(onOpen = { item = it }, onBack = { search = false })
                tab == 1 && episode != null -> EpisodeScreen(episode!!, onBack = { episode = null })
                tab == 0 -> FeedScreen(newsTab, onOpen = { item = it }, onSetup = { setup = true }, onStory = { story = it },
                    onSearch = { search = true }, onCatchUp = { catchUp = true }, onSettings = { tab = 3 })
                tab == 1 -> PodcastScreen(onOpen = { episode = it })
                tab == 2 -> com.aliahad.ainewshot.ui.BriefingsScreen(onOpen = { doc = it }, onTrending = { newsTab.intValue = 1; tab = 0 })
                else -> SettingsScreen(onSetup = { setup = true })
            }
        }
    }
}
