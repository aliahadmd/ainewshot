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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aliahad.ainewshot.ui.AppTheme
import com.aliahad.ainewshot.ui.ArticleScreen
import com.aliahad.ainewshot.ui.BottomTabs
import com.aliahad.ainewshot.ui.CatchUpScreen
import com.aliahad.ainewshot.ui.DailyScreen
import com.aliahad.ainewshot.ui.EpisodeScreen
import com.aliahad.ainewshot.ui.HotScreen
import com.aliahad.ainewshot.ui.LocalNav
import com.aliahad.ainewshot.ui.MeScreen
import com.aliahad.ainewshot.ui.MiniPlayer
import com.aliahad.ainewshot.ui.Nav
import com.aliahad.ainewshot.ui.PodcastScreen
import com.aliahad.ainewshot.ui.Route
import com.aliahad.ainewshot.ui.SavedScreen
import com.aliahad.ainewshot.ui.SearchScreen
import com.aliahad.ainewshot.ui.SelectedScreen
import com.aliahad.ainewshot.ui.SettingsScreen
import com.aliahad.ainewshot.ui.StoryScreen
import com.aliahad.ainewshot.ui.TAB_DAILY
import com.aliahad.ainewshot.ui.TAB_HOT
import com.aliahad.ainewshot.ui.TAB_ME
import com.aliahad.ainewshot.ui.TAB_PODCAST
import com.aliahad.ainewshot.ui.TAB_SELECTED
import com.aliahad.ainewshot.ui.TopicScreen
import com.aliahad.ainewshot.ui.TopicsScreen

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val openTab = mutableIntStateOf(TAB_SELECTED)
    private val openEpisode = mutableStateOf<String?>(null)
    /** A screen requested from outside (widget, alert, notification), pushed once the UI is up. */
    private val openRoute = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(this)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root(openTab, openEpisode, openRoute) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: android.content.Intent?) {
        intent ?: return
        intent.getIntExtra("tab", -1).takeIf { it >= 0 }?.let { openTab.intValue = it }
        intent.getStringExtra("episode")?.let { openEpisode.value = it; openTab.intValue = TAB_PODCAST }
        // From the widget / ticker notification: open the article.
        intent.getStringExtra("item")?.let { id ->
            openTab.intValue = TAB_SELECTED
            openRoute.value = Route.Article(id, (NewsRepo.recent.value + NewsRepo.saved.value).firstOrNull { it.id == id })
        }
        // A delivered briefing: the Daily tab shows today's issue.
        if (intent.getBooleanExtra("briefing", false)) openTab.intValue = TAB_DAILY
        intent.getStringExtra("story")?.let { sid ->
            openTab.intValue = TAB_HOT
            openRoute.value = Route.Story(sid, intent.getStringExtra("title") ?: "")
        }
    }
}

@Composable
private fun Root(
    tabState: androidx.compose.runtime.MutableIntState,
    episodeState: androidx.compose.runtime.MutableState<String?>,
    routeState: androidx.compose.runtime.MutableState<Route?>,
) {
    var tab by tabState
    var episode by episodeState
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember {
        Nav(push = { stack.add(it) }, back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
            tab = { stack.clear(); tabState.intValue = it })
    }
    val pending = routeState.value
    LaunchedEffect(pending) {
        if (pending != null) { stack.clear(); stack.add(pending); routeState.value = null }
    }

    BackHandler(stack.isNotEmpty() || (tab == TAB_PODCAST && episode != null) || tab != TAB_SELECTED) {
        when {
            stack.isNotEmpty() -> stack.removeAt(stack.lastIndex)
            tab == TAB_PODCAST && episode != null -> episode = null
            else -> tab = TAB_SELECTED
        }
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Scaffold(bottomBar = {
            Column {
                MiniPlayer(onOpen = { stack.clear(); tab = TAB_PODCAST; episode = it })
                // Full-screen pages (an article has its own reading toolbar) hide the tabs, like the site.
                if (stack.isEmpty()) BottomTabs(tab) { i -> stack.clear(); tab = i; if (i != TAB_PODCAST) episode = null }
            }
        }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (val r = stack.lastOrNull()) {
                    is Route.Article -> ArticleScreen(r.id, r.seed, onBack = nav.back)
                    is Route.Story -> StoryScreen(r.id, r.title, onBack = nav.back)
                    is Route.Topic -> TopicScreen(r.slug, onBack = nav.back)
                    Route.Topics -> TopicsScreen(onBack = nav.back)
                    Route.Saved -> SavedScreen(onBack = nav.back)
                    Route.Search -> SearchScreen(onBack = nav.back)
                    Route.CatchUp -> CatchUpScreen(onBack = nav.back)
                    Route.Setup -> com.aliahad.ainewshot.ui.SetupScreen(onBack = nav.back)
                    Route.Settings -> SettingsScreen(onSetup = { nav.go(Route.Setup) }, onBack = nav.back)
                    null -> when (tab) {
                        TAB_HOT -> HotScreen()
                        TAB_DAILY -> DailyScreen()
                        TAB_PODCAST -> if (episode != null) EpisodeScreen(episode!!, onBack = { episode = null }) else PodcastScreen(onOpen = { episode = it })
                        TAB_ME -> MeScreen()
                        else -> SelectedScreen()
                    }
                }
            }
        }
    }
}
