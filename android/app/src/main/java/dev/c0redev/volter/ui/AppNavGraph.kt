package dev.c0redev.volter.ui

import android.app.Activity
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.c0redev.volter.R
import dev.c0redev.volter.ui.screens.ConfigsScreen
import dev.c0redev.volter.ui.screens.ClusterScreen
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
        NavItem("configs", R.string.nav_configs) { cd -> Icon(Icons.Outlined.Dns, cd) },
        NavItem("cluster", R.string.nav_cluster) { cd -> Icon(Icons.Outlined.Lan, cd) },
        NavItem("logs", R.string.nav_logs) { cd -> Icon(Icons.AutoMirrored.Outlined.ReceiptLong, cd) },
        NavItem("mesh", R.string.nav_mesh) { cd -> Icon(Icons.Outlined.Security, cd) },
        NavItem("protection", R.string.nav_protection) { cd -> Icon(Icons.Outlined.Security, cd) },
        NavItem("settings", R.string.nav_settings) { cd -> Icon(Icons.Outlined.Settings, cd) },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!useRail) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    GlassBottomBar {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
                            tonalElevation = 0.dp,
                            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
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
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
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
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
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
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
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

private const val glassShaderSrc = """
uniform shader background;
uniform float2 resolution;
uniform float time;
uniform float4 tint;
uniform float4 accent;

half4 main(float2 fragCoord) {
  float2 uv = fragCoord / max(resolution, float2(1.0));
  float wave = sin((uv.x * 5.0) + time * 0.35) * 0.0018;
  float wave2 = cos((uv.y * 7.0) - time * 0.25) * 0.0012;
  float2 p = uv + float2(wave, wave2);
  half4 base = background.eval(p * resolution);
  float edge = smoothstep(0.0, 1.0, 1.0 - abs(uv.y - 0.08) * 8.0) * 0.08;
  half3 tone = mix(tint.rgb, accent.rgb, 0.12);
  half4 glass = mix(base, half4(tone, 1.0), 0.18);
  glass.rgb += edge * 0.6;
  glass.a = 0.86;
  return glass;
}
"""

@Composable
private fun GlassBottomBar(content: @Composable () -> Unit) {
  val shape = RoundedCornerShape(28.dp)
  val tint = MaterialTheme.colorScheme.surfaceContainerHigh
  val accent = MaterialTheme.colorScheme.primaryContainer
  val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
    Surface(
      shape = shape,
      color = tint.copy(alpha = 0.78f),
      border = BorderStroke(1.dp, outline),
      tonalElevation = 6.dp,
      shadowElevation = 14.dp,
      content = { content() },
    )
    return
  }
  val shader = remember {
    try {
      RuntimeShader(glassShaderSrc)
    } catch (_: Throwable) {
      null
    }
  }
  if (shader == null) {
    Surface(
      shape = shape,
      color = tint.copy(alpha = 0.78f),
      border = BorderStroke(1.dp, outline),
      tonalElevation = 6.dp,
      shadowElevation = 14.dp,
      content = { content() },
    )
    return
  }
  val inf = rememberInfiniteTransition(label = "glassBar")
  val t by inf.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(
      animation = tween(22_000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "glassTime",
  )
  val tintArgb by rememberUpdatedState(tint.toArgb())
  val accentArgb by rememberUpdatedState(accent.toArgb())
  Surface(
    shape = shape,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
    border = BorderStroke(1.dp, outline),
    tonalElevation = 6.dp,
    shadowElevation = 14.dp,
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .clip(shape)
          .graphicsLayer {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("time", t)
            shader.setFloatUniform(
              "tint",
              ((tintArgb shr 16) and 0xFF) / 255f,
              ((tintArgb shr 8) and 0xFF) / 255f,
              (tintArgb and 0xFF) / 255f,
              ((tintArgb ushr 24) and 0xFF) / 255f,
            )
            shader.setFloatUniform(
              "accent",
              ((accentArgb shr 16) and 0xFF) / 255f,
              ((accentArgb shr 8) and 0xFF) / 255f,
              (accentArgb and 0xFF) / 255f,
              ((accentArgb ushr 24) and 0xFF) / 255f,
            )
            val blur = RenderEffect.createBlurEffect(8f, 8f, android.graphics.Shader.TileMode.CLAMP)
            val rt = RenderEffect.createRuntimeShaderEffect(shader, "background")
            renderEffect = RenderEffect.createChainEffect(rt, blur).asComposeRenderEffect()
          },
      )
      Box(modifier = Modifier.fillMaxWidth()) {
        content()
      }
    }
  }
}
