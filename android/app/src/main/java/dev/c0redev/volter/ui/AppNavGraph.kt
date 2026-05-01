package dev.c0redev.volter.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.c0redev.volter.R
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.ui.components.VolterGlassDialogDefaults
import dev.c0redev.volter.ui.screens.ClusterScreen
import dev.c0redev.volter.ui.screens.ConfigsScreen
import dev.c0redev.volter.ui.screens.HomeScreen
import dev.c0redev.volter.ui.screens.LogsScreen
import dev.c0redev.volter.ui.screens.MeshScreen
import dev.c0redev.volter.ui.screens.ProtectionScreen
import dev.c0redev.volter.ui.screens.SettingsScreen

private data class NavItem(
    val route: String,
    val labelRes: Int,
    val icon: @Composable (cd: String) -> Unit,
)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AppNavGraph(vm: ConnectionViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val vpnPrep by vm.vpnPermissionIntent.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val activity = context as Activity
    val windowSizeClass = calculateWindowSizeClass(activity = activity)
    val useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.confirmVpnPermission() else vm.cancelPendingVpnConnect()
        vm.consumeVpnPermissionIntent()
    }

    LaunchedEffect(vpnPrep) {
        val intent = vpnPrep ?: return@LaunchedEffect
        vpnLauncher.launch(intent)
    }

    LaunchedEffect(Unit) {
        vm.uiMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        vm.navToQuickTilesSettings.collect {
            nav.navigate("settings") {
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val navItems = listOf(
        NavItem("home", R.string.nav_home) { cd -> Icon(Icons.Outlined.Home, cd) },
        NavItem("configs", R.string.nav_configs) { cd -> Icon(Icons.AutoMirrored.Outlined.ViewList, cd) },
        NavItem("cluster", R.string.nav_cluster) { cd -> Icon(Icons.Outlined.Lan, cd) },
        NavItem("logs", R.string.nav_logs) { cd -> Icon(Icons.AutoMirrored.Outlined.Article, cd) },
        NavItem("mesh", R.string.nav_mesh) { cd -> Icon(Icons.Outlined.AccountTree, cd) },
        NavItem("protection", R.string.nav_protection) { cd -> Icon(Icons.Outlined.VerifiedUser, cd) },
        NavItem("settings", R.string.nav_settings) { cd -> Icon(Icons.Outlined.Settings, cd) },
    )

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { data ->
                    val scheme = MaterialTheme.colorScheme
                    Snackbar(
                        snackbarData = data,
                        shape = VolterGlassDialogDefaults.shape(),
                        containerColor = VolterGlassDialogDefaults.containerColor(),
                        contentColor = scheme.onSurface,
                        actionColor = scheme.primary,
                        dismissActionContentColor = scheme.onSurfaceVariant,
                    )
                },
            )
        },
        bottomBar = {
            if (!useRail) {
                val barShape = RoundedCornerShape(VolterSpacing.bottomBarGlassRadius)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    GlassPanel(shape = barShape) {
                        NavigationBar(
                            modifier = Modifier.height(64.dp),
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            windowInsets = NavigationBarDefaults.windowInsets,
                        ) {
                            navItems.forEach { item ->
                                val cd = stringResource(R.string.common_cd_nav, stringResource(item.labelRes))
                                NavigationBarItem(
                                    selected = currentRoute == item.route,
                                    onClick = {
                                        nav.navigate(item.route) {
                                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { item.icon(cd) },
                                    label = { Text(stringResource(item.labelRes)) },
                                    alwaysShowLabel = false,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize()) {
            if (useRail) {
                val railShape = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                    RoundedCornerShape(
                        topStart = VolterSpacing.bottomBarGlassRadius,
                        bottomStart = VolterSpacing.bottomBarGlassRadius,
                    )
                } else {
                    RoundedCornerShape(
                        topEnd = VolterSpacing.bottomBarGlassRadius,
                        bottomEnd = VolterSpacing.bottomBarGlassRadius,
                    )
                }
                val scheme = MaterialTheme.colorScheme
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    GlassPanel(shape = railShape) {
                        NavigationRail(
                            containerColor = Color.Transparent,
                        ) {
                            navItems.forEach { item ->
                                val cd = stringResource(R.string.common_cd_nav, stringResource(item.labelRes))
                                NavigationRailItem(
                                    selected = currentRoute == item.route,
                                    onClick = {
                                        nav.navigate(item.route) {
                                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { item.icon(cd) },
                                    label = { Text(stringResource(item.labelRes)) },
                                    alwaysShowLabel = true,
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = scheme.primary,
                                        selectedTextColor = scheme.primary,
                                        indicatorColor = scheme.primary.copy(alpha = 0.18f),
                                        unselectedIconColor = scheme.onSurfaceVariant,
                                        unselectedTextColor = scheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable("home") {
                        HomeScreen(vm, padding) { route ->
                            nav.navigate(route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                    composable("configs") { ConfigsScreen(vm, padding) }
                    composable("cluster") { ClusterScreen(vm, padding) }
                    composable("logs") { LogsScreen(vm, padding) }
                    composable("mesh") { MeshScreen(vm, padding) }
                    composable("protection") { ProtectionScreen(vm, padding) }
                    composable("settings") { SettingsScreen(vm, padding) }
                }
            }
        }
    }
}

@Composable
private fun GlassPanel(
    shape: Shape,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = scheme.surfaceContainerHigh.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        content()
    }
}

