package gr.nikkpap.airseries.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.ui.detail.DetailScreen
import gr.nikkpap.airseries.ui.discover.DiscoverScreen
import gr.nikkpap.airseries.ui.home.HomeScreen
import gr.nikkpap.airseries.ui.library.LibraryScreen
import gr.nikkpap.airseries.ui.schedule.ScheduleScreen
import gr.nikkpap.airseries.ui.settings.AboutScreen
import gr.nikkpap.airseries.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

private enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Home("home", R.string.tab_home, Icons.Filled.PlayCircle),
    Schedule("schedule", R.string.tab_schedule, Icons.Filled.CalendarMonth),
    Discover("discover", R.string.tab_discover, Icons.Filled.Explore),
    Library("library", R.string.tab_library, Icons.Filled.Favorite),
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppNav(pendingShow: MutableStateFlow<Int?>) {
    val nav = rememberNavController()
    val pending by pendingShow.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate("show/$it")
            pendingShow.value = null
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val openShow: (Int) -> Unit = { nav.navigate("show/$it") }
    val openSettings: () -> Unit = { nav.navigate("settings") }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (Tab.entries.any { it.route == route }) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(pad).consumeWindowInsets(pad)) {
            composable(Tab.Home.route) {
                HomeScreen(onOpen = openShow, onSettings = openSettings, onDiscover = { nav.goTab(Tab.Discover.route) })
            }
            composable(Tab.Schedule.route) { ScheduleScreen(onOpen = openShow) }
            composable(Tab.Discover.route) { DiscoverScreen(onOpen = openShow) }
            composable(Tab.Library.route) {
                LibraryScreen(onOpen = openShow, onSettings = openSettings, onDiscover = { nav.goTab(Tab.Discover.route) })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onAbout = { nav.navigate("about") }) }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("show/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
                DetailScreen(showId = e.arguments?.getInt("id") ?: 0, onBack = { nav.popBackStack() })
            }
        }
    }
}
