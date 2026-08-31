@file:Suppress("ktlint:standard:max-line-length")

package top.guozk.pipilot.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import top.guozk.pipilot.background.PendingSessionNavigation
import top.guozk.pipilot.di.AppGraph
import top.guozk.pipilot.sessions.ShareNavigationState
import top.guozk.pipilot.ui.chat.ChatRoute
import top.guozk.pipilot.ui.hosts.HostsRoute
import top.guozk.pipilot.ui.sessions.SessionsRoute
import top.guozk.pipilot.ui.settings.KEY_SHOW_EXTENSION_STATUS_STRIP
import top.guozk.pipilot.ui.settings.KEY_THEME_PREFERENCE
import top.guozk.pipilot.ui.settings.SETTINGS_PREFS_NAME
import top.guozk.pipilot.ui.settings.SettingsRoute
import top.guozk.pipilot.ui.theme.PiMobileTheme
import top.guozk.pipilot.ui.theme.ThemePreference

private data class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val EXPANDED_NAVIGATION_MIN_WIDTH = 840.dp

private val destinations =
    listOf(
        AppDestination(
            route = "hosts",
            label = "主机",
            icon = Icons.Default.Computer,
        ),
        AppDestination(
            route = "sessions",
            label = "会话",
            icon = Icons.Default.Storage,
        ),
        AppDestination(
            route = "chat",
            label = "聊天",
            icon = Icons.AutoMirrored.Filled.Chat,
        ),
        AppDestination(
            route = "settings",
            label = "设置",
            icon = Icons.Default.Settings,
        ),
    )

@Suppress("LongMethod")
@Composable
private fun DrawerDestinationItem(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val itemShape = RoundedCornerShape(14.dp)
    val itemColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            animationSpec = tween(durationMillis = 180),
            label = "drawer_item_color",
        )
    val dotColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            animationSpec = tween(durationMillis = 180),
            label = "drawer_dot_color",
        )
    val dotSize by
        animateDpAsState(
            targetValue = if (selected) 8.dp else 6.dp,
            animationSpec = tween(durationMillis = 180),
            label = "drawer_dot_size",
        )

    Surface(
        shape = itemShape,
        color = itemColor,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        NavigationDrawerItem(
            selected = selected,
            onClick = onClick,
            label = {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            icon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(dotSize)
                                .background(
                                    color = dotColor,
                                    shape = CircleShape,
                                ),
                    )
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                    )
                }
            },
            shape = itemShape,
            colors =
                NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = Color.Transparent,
                    unselectedContainerColor = Color.Transparent,
                ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "MaxLineLength", "CyclomaticComplexMethod")
@Composable
fun PipilotApp(appGraph: AppGraph) {
    val context = LocalContext.current
    val settingsPrefs =
        remember(context) {
            context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        }
    var themePreference by remember(settingsPrefs) {
        mutableStateOf(
            ThemePreference.fromValue(
                settingsPrefs.getString(KEY_THEME_PREFERENCE, null),
            ),
        )
    }
    var showExtensionStatusStrip by remember(settingsPrefs) {
        mutableStateOf(settingsPrefs.getBoolean(KEY_SHOW_EXTENSION_STATUS_STRIP, true))
    }

    DisposableEffect(settingsPrefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                when (key) {
                    KEY_THEME_PREFERENCE -> {
                        themePreference = ThemePreference.fromValue(prefs.getString(KEY_THEME_PREFERENCE, null))
                    }

                    KEY_SHOW_EXTENSION_STATUS_STRIP -> {
                        showExtensionStatusStrip = prefs.getBoolean(KEY_SHOW_EXTENSION_STATUS_STRIP, true)
                    }
                }
            }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    PiMobileTheme(themePreference = themePreference) {
        val navController = rememberNavController()
        val shareCoordinator = appGraph.shareNavigationCoordinator
        val shareNavigationState by shareCoordinator.state.collectAsStateWithLifecycle()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        fun navigateTo(route: String) {
            navController.navigate(route) {
                launchSingleTop = true
                restoreState = true
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
            }
        }

        LaunchedEffect(shareCoordinator) {
            shareCoordinator.state.collect { state ->
                if (state is ShareNavigationState.NavigateToChat) {
                    navigateTo("chat")
                    shareCoordinator.acknowledgeNavigation(state.generation)
                }
            }
        }

        // 通知点击直达会话：前台服务写入待处理目标，这里消费并导航
        LaunchedEffect(Unit) {
            val target = PendingSessionNavigation.consume()
            if (target != null) {
                navigateTo("chat")
            }
        }

        val hasConfiguredHost = remember(appGraph) { appGraph.hostProfileStore.list().isNotEmpty() }
        val startDestination = if (hasConfiguredHost) "sessions" else "hosts"
        val availableDestinations = destinations.filter { destination -> destination.route != "chat" }

        BoxWithConstraints {
            val isExpanded = maxWidth >= EXPANDED_NAVIGATION_MIN_WIDTH
            Row(modifier = Modifier.fillMaxSize()) {
                if (isExpanded) {
                    NavigationRail {
                        availableDestinations.forEach { destination ->
                            NavigationRailItem(
                                selected = currentRoute == destination.route,
                                onClick = { navigateTo(destination.route) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = drawerState.isOpen,
                        drawerContent = {
                            ModalDrawerSheet(
                                modifier = Modifier.widthIn(min = 220.dp, max = 270.dp),
                                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text = "导航",
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                text = "从左侧滑出，点击外部即可关闭。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    HorizontalDivider()

                                    Text(
                                        text = "工作区",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                    )

                                    availableDestinations.forEach { destination ->
                                        DrawerDestinationItem(
                                            destination = destination,
                                            selected = currentRoute == destination.route,
                                            onClick = {
                                                navigateTo(destination.route)
                                                scope.launch { drawerState.close() }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    ) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text(currentRoute?.replaceFirstChar(Char::uppercase) ?: "PiPilot") },
                                    navigationIcon = {
                                        if (!isExpanded) {
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Menu,
                                                    contentDescription = "打开导航",
                                                )
                                            }
                                        }
                                    },
                                )
                            },
                        ) { paddingValues ->
                            Box(
                                modifier = Modifier.fillMaxSize().padding(paddingValues),
                            ) {
                                if (shareNavigationState !is ShareNavigationState.Idle &&
                                    shareNavigationState !is ShareNavigationState.NavigateToChat
                                ) {
                                    Text(
                                        text = shareNavigationMessage(shareNavigationState),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                                NavHost(
                                    navController = navController,
                                    startDestination = startDestination,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    composable(route = "hosts") {
                                        HostsRoute(
                                            profileStore = appGraph.hostProfileStore,
                                            tokenStore = appGraph.hostTokenStore,
                                            diagnostics = appGraph.connectionDiagnostics,
                                            onHostSaved = {
                                                navigateTo("sessions")
                                            },
                                        )
                                    }
                                    composable(route = "sessions") {
                                        SessionsRoute(
                                            profileStore = appGraph.hostProfileStore,
                                            tokenStore = appGraph.hostTokenStore,
                                            repository = appGraph.sessionIndexRepository,
                                            sessionController = appGraph.sessionController,
                                            cwdPreferenceStore = appGraph.sessionCwdPreferenceStore,
                                            savedStateStore = appGraph.sessionSavedStateStore,
                                            shareRemoteDataSource = appGraph.sessionShareRemoteDataSource,
                                            onNavigateToChat = {
                                                navigateTo("chat")
                                            },
                                        )
                                    }
                                    composable(route = "chat") {
                                        ChatRoute(
                                            sessionController = appGraph.sessionController,
                                            showExtensionStatusStrip = showExtensionStatusStrip,
                                        )
                                    }
                                    composable(route = "settings") {
                                        SettingsRoute(sessionController = appGraph.sessionController)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shareNavigationMessage(state: ShareNavigationState): String =
    when (state) {
        ShareNavigationState.Resolving -> "正在解析共享会话…"
        ShareNavigationState.SetupRequired -> "请先检查并保存主机，再打开此链接。"
        ShareNavigationState.AuthenticationRequired -> "请为已配置的主机填写令牌后继续。"
        ShareNavigationState.AmbiguousHost -> "有多个已配置主机匹配此链接，请检查主机设置。"
        is ShareNavigationState.Failed -> state.message
        else -> ""
    }
