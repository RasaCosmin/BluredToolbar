package ro.codia.toolbartest

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
// =============================================================================================
// Timing / look
// =============================================================================================

private const val NAV_ANIM_MS = 300
private val IconTravel = 24.dp           // toolbar icons drift this much (screen body slides full width)
private const val IconMinScale = 0.4f     // icons shrink to / grow from this scale
private val ToolbarHeight = 64.dp
private val ToolbarIconSize = 40.dp
private val ToolbarIconSpacing = 8.dp
private const val COLLAPSE_ANIM_MS = 150  // toolbar title fade when the large title scrolls away
private val Easing = FastOutSlowInEasing

private val ScreenBackground = Color(0xFFEEEFF3)
private val IconCircle = Color.White
private val IconTint = Color(0xFF1C1C1E)
private val TextSecondary = Color(0xFF6E6E73)

// Toolbar background (Figma): #EEEFF3 70% → 0% gradient + progressive background blur, top → bottom.
// Stronger than Figma's 20 → 0 linear, to match the reference video: bigger radius, and EaseIn keeps
// the blur near full strength through most of the toolbar, dropping off only near its bottom edge.
private val ToolbarBlur = 28.dp
private val ToolbarTint = Brush.verticalGradient(
    0f to ScreenBackground.copy(alpha = 0.7f),
    1f to ScreenBackground.copy(alpha = 0f),
)
private val ToolbarBlurStyle = HazeBlurStyle {
    // Real blur everywhere: RenderEffect on Android 12+, RenderScript on 8–11
    // (Haze's default would be a plain scrim below 12).
    blurEnabled(true)
    blurRadius(ToolbarBlur)
    progressive(HazeProgressive.verticalGradient(easing = EaseIn, startIntensity = 1f, endIntensity = 0f))
    noiseFactor(0f)
}

private object Routes {
    const val Profile = "profile"
    const val Settings = "settings"
    const val Notifications = "notifications"
}

// =============================================================================================
// Navigation direction (push vs pop)
// =============================================================================================

/**
 * Written by the NavHost transition lambdas (they know if it's a push or a pop — including the
 * predictive-back gesture) and read by every screen inside graphicsLayer, i.e. during draw.
 * It's a plain var on purpose: the layer blocks re-run every frame while progress animates.
 */
class NavDirection {
    var isPop: Boolean = false
        internal set
}

val LocalNavDirection = staticCompositionLocalOf { NavDirection() }

// =============================================================================================
// Shared toolbar icons
// =============================================================================================

/**
 * The screens currently composed (two during a transition) and their leading icon. Lets a screen
 * check whether the screen on the other side of the transition shows the same leading icon
 * (e.g. back → back): then that icon stays put instead of drifting/scaling/fading.
 */
@Stable
class ToolbarRegistry {
    private class Entry(val nav: NavTransitionScope, val leading: ImageVector?)

    private val entries = mutableStateListOf<Entry>()

    internal fun register(nav: NavTransitionScope, leading: ImageVector?): () -> Unit {
        val entry = Entry(nav, leading)
        entries += entry
        return { entries -= entry }
    }

    /** Read it only inside graphicsLayer / draw. */
    internal fun sharesLeading(nav: NavTransitionScope, leading: ImageVector): Boolean =
        entries.any { it.nav !== nav && it.nav.isEntering != nav.isEntering && it.leading == leading }
}

val LocalToolbarRegistry = staticCompositionLocalOf { ToolbarRegistry() }

// =============================================================================================
// Host
// =============================================================================================

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val direction = remember { NavDirection() }
    val toolbars = remember { ToolbarRegistry() }

    CompositionLocalProvider(
        LocalNavDirection provides direction,
        LocalToolbarRegistry provides toolbars,
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.Profile,
            // Window background shows behind the (transparent) toolbars.
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground),
            // The NavHost itself does NOT move anything. Each screen animates its own parts
            // (body slides full width, toolbar icons drift/scale/fade) from the same transition.
            // Exiting screens stay composed until their child animations finish.
            enterTransition = { direction.isPop = false; EnterTransition.None },
            exitTransition = { direction.isPop = false; ExitTransition.None },
            popEnterTransition = { direction.isPop = true; EnterTransition.None },
            popExitTransition = { direction.isPop = true; ExitTransition.None },
        ) {
            composable(Routes.Profile) {
                AppScreen(
                    leading = ToolbarIcon(AppIcons.Settings, "Setări") { navController.navigate(Routes.Settings) },
                    trailing = listOf(ToolbarIcon(AppIcons.Info, "Suport") { /* open support */ }),
                    title = "John Doe",
                    subtitle = "+40 731 482 905",
                    header = { ProfileHeader("JD", "John Doe", "+40 731 482 905") },
                ) {
                    DemoBody("Setări →") { navController.navigate(Routes.Settings) }
                }
            }
            composable(Routes.Settings) {
                AppScreen(
                    leading = backIcon(navController),
                    title = "Setări",
                ) {
                    DemoBody("Notificări →") { navController.navigate(Routes.Notifications) }
                }
            }
            composable(Routes.Notifications) {
                AppScreen(
                    leading = backIcon(navController),
                    trailing = listOf(   // two icons on the right
                        ToolbarIcon(AppIcons.Settings, "Setări notificări") { /* open notification settings */ },
                        ToolbarIcon(AppIcons.Info, "Suport") { /* open support */ },
                    ),
                    title = "Notificări",
                ) {
                    DemoBody()
                }
            }
        }
    }
}

private fun backIcon(navController: NavHostController) =
    ToolbarIcon(AppIcons.ArrowBack, "Înapoi") { navController.popBackStack() }

// =============================================================================================
// Per-screen transition helper
// =============================================================================================

/**
 * Gives a screen modifiers driven by its own enter/exit transition.
 * progress: 0 = fully off-screen, 1 = fully shown (both for entering and exiting screens).
 */
@Stable
class NavTransitionScope internal constructor(
    private val transition: Transition<EnterExitState>,
    private val progress: State<Float>,
    private val direction: NavDirection,
    private val rtl: Boolean,
) {
    /** False while this screen is animating in/out → its buttons ignore taps. */
    val isInteractive: Boolean
        get() = !transition.isRunning && transition.targetState == EnterExitState.Visible

    internal val isEntering: Boolean
        get() = transition.targetState == EnterExitState.Visible

    /** +1 = offset toward the end (right in LTR), -1 = toward the start. */
    internal fun sign(): Float {
        // push-enter: from right, push-exit: to left, pop-enter: from left, pop-exit: to right
        val s = if (isEntering != direction.isPop) 1f else -1f
        return if (rtl) -s else s
    }

    /** 0 = off-screen, 1 = fully shown. Read it only inside graphicsLayer / draw. */
    internal val progressValue: Float get() = progress.value

    // Outgoing content is gone after ~2/3 of the animation, incoming fades in over all of it.
    internal fun alphaFor(p: Float): Float =
        if (isEntering) p else ((p - 1f / 3f) / (2f / 3f)).coerceIn(0f, 1f)
}

/** Full-width slide, for the screen body. */
fun Modifier.navSlide(nav: NavTransitionScope): Modifier = graphicsLayer {
    translationX = (1f - nav.progressValue) * size.width * nav.sign()
}

/**
 * Short drift + shrink/grow + fade, for toolbar icons.
 * [animated] is read during draw; when it returns false the icon is drawn fully shown, in place.
 */
fun Modifier.navIcon(
    nav: NavTransitionScope,
    drift: Boolean = true,
    animated: () -> Boolean = { true },
): Modifier = graphicsLayer {
    val p = if (animated()) nav.progressValue else 1f
    translationX = if (drift) (1f - p) * IconTravel.toPx() * nav.sign() else 0f
    val scale = IconMinScale + (1f - IconMinScale) * p
    scaleX = scale
    scaleY = scale
    alpha = nav.alphaFor(p)
}

/** Fade only, for things that stay in place (e.g. the toolbar title). */
fun Modifier.navFade(nav: NavTransitionScope, extraAlpha: () -> Float = { 1f }): Modifier = graphicsLayer {
    alpha = nav.alphaFor(nav.progressValue) * extraAlpha()
}

@Composable
fun AnimatedVisibilityScope.rememberNavTransitionScope(): NavTransitionScope {
    val progress = transition.animateFloat(
        transitionSpec = { tween(NAV_ANIM_MS, easing = Easing) },
        label = "navProgress",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    val direction = LocalNavDirection.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(transition, direction, rtl) {
        NavTransitionScope(transition, progress, direction, rtl)
    }
}

// =============================================================================================
// Screen + toolbar
// =============================================================================================

@Immutable
data class ToolbarIcon(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/**
 * Standard screen: toolbar on top (stays put, icons animate), body below (slides) and scrolls
 * behind the toolbar (which blurs it). The body starts with a [header] — by default a large
 * [title] — and the small toolbar title (+ optional [subtitle]) fades in only once that header
 * has scrolled completely off the screen.
 * Call it directly inside `composable(...) { }` — that block's receiver is the
 * AnimatedVisibilityScope this needs.
 */
@Composable
fun AnimatedVisibilityScope.AppScreen(
    leading: ToolbarIcon? = null,
    trailing: List<ToolbarIcon> = emptyList(),   // right side, in order (start → end)
    title: String? = null,
    subtitle: String? = null,
    header: (@Composable () -> Unit)? = title?.let { { LargeTitle(it) } },
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = rememberNavTransitionScope()

    val toolbars = LocalToolbarRegistry.current
    val leadingIcon = leading?.icon
    DisposableEffect(toolbars, nav, leadingIcon) {
        val unregister = toolbars.register(nav, leadingIcon)
        onDispose { unregister() }
    }

    val density = LocalDensity.current
    val toolbarBottom = WindowInsets.statusBars.getTop(density) + with(density) { ToolbarHeight.toPx() }
    val currentToolbarBottom by rememberUpdatedState(toolbarBottom)

    val scroll = rememberScrollState()
    var headerHeight by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // The header starts right under the toolbar (status bar + toolbar height), so it has left the
    // screen entirely — past its top edge — once we've scrolled by both.
    val collapsed by remember {
        derivedStateOf { scroll.value >= currentToolbarBottom + headerHeight }
    }
    val titleAlpha = animateFloatAsState(if (collapsed) 1f else 0f, tween(COLLAPSE_ANIM_MS), label = "titleAlpha")
    val hazeState = rememberHazeState()

    // No background on the root: during a transition two screens are stacked and their
    // toolbars must both stay visible. The host paints the background behind them.
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navSlide(nav)
                .drawWithContent {
                    // Opaque only below the toolbar, so the other screen's toolbar stays visible.
                    drawRect(ScreenBackground, topLeft = Offset(0f, toolbarBottom))
                    clipRect(top = toolbarBottom) { this@drawWithContent.drawContent() }
                    // Content scrolled under our toolbar fades with the toolbar during a transition,
                    // otherwise it would slide over the other screen's toolbar icons.
                    val alpha = nav.alphaFor(nav.progressValue)
                    if (alpha > 0f) {
                        clipRect(bottom = toolbarBottom) {
                            drawIntoCanvas { canvas ->
                                canvas.saveLayer(Rect(0f, 0f, size.width, toolbarBottom), Paint().also { it.alpha = alpha })
                                this@drawWithContent.drawContent()
                                canvas.restore()
                            }
                        }
                    }
                }
                // What the toolbar blurs: this screen's scrolling content only.
                .hazeSource(hazeState)
                .verticalScroll(scroll),
        ) {
            Spacer(Modifier.statusBarsPadding().height(ToolbarHeight))
            header?.let {
                Box(Modifier.fillMaxWidth().onSizeChanged { size -> headerHeight = size.height }) { it() }
            }
            content()
            Spacer(Modifier.navigationBarsPadding())
        }

        AppToolbar(
            nav = nav,
            leading = leading,
            trailing = trailing,
            title = title,
            subtitle = subtitle,
            titleAlpha = { titleAlpha.value },
            hazeState = hazeState,
        )
    }
}

@Composable
private fun AppToolbar(
    nav: NavTransitionScope,
    leading: ToolbarIcon?,
    trailing: List<ToolbarIcon>,
    title: String?,
    subtitle: String?,
    titleAlpha: () -> Float,
    hazeState: HazeState,
) {
    val toolbars = LocalToolbarRegistry.current

    Box(Modifier.fillMaxWidth()) {
        // Background (status bar included): progressive blur of the content behind + gradient tint.
        // Fades with the transition so it never washes over the other screen's toolbar.
        Box(
            Modifier
                .matchParentSize()
                .navFade(nav)
                .hazeBlur(HazeInput.Sources(hazeState), ToolbarBlurStyle)
                .drawBehind { drawRect(ToolbarTint) },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(ToolbarHeight)
                .padding(horizontal = 16.dp),
        ) {
            ToolbarContent(nav, toolbars, leading, trailing, title, subtitle, titleAlpha)
        }
    }
}

@Composable
private fun BoxScope.ToolbarContent(
    nav: NavTransitionScope,
    toolbars: ToolbarRegistry,
    leading: ToolbarIcon?,
    trailing: List<ToolbarIcon>,
    title: String?,
    subtitle: String?,
    titleAlpha: () -> Float,
) {
    leading?.let {
        CircleIconButton(
            icon = it,
            enabled = nav.isInteractive,
            modifier = Modifier
                .align(Alignment.CenterStart)
                // Same icon on both screens (back → back) → it stays still.
                .navIcon(nav, drift = true, animated = { !toolbars.sharesLeading(nav, it.icon) }),
        )
    }

    title?.let {
        // Keep the title centred on screen, clear of the wider side (e.g. two icons on the right).
        val sideIcons = maxOf(if (leading != null) 1 else 0, trailing.size)
        val sideInset = if (sideIcons == 0) 0.dp else
            ToolbarIconSize * sideIcons + ToolbarIconSpacing * sideIcons
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = sideInset)
                .navFade(nav, titleAlpha),
        ) {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (trailing.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ToolbarIconSpacing),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            trailing.forEach {
                CircleIconButton(
                    icon = it,
                    enabled = nav.isInteractive,
                    modifier = Modifier.navIcon(nav, drift = false),   // right icons only scale/fade in place
                )
            }
        }
    }
}

/** Default [AppScreen] header: the screen title, large. */
@Composable
private fun LargeTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CircleIconButton(icon: ToolbarIcon, enabled: Boolean, modifier: Modifier = Modifier) {
    IconButton(
        onClick = icon.onClick,
        enabled = enabled,
        modifier = modifier
            .size(ToolbarIconSize),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = IconCircle,
            contentColor = IconTint,
            // Same look while disabled during the transition — only taps are blocked.
            disabledContainerColor = IconCircle,
            disabledContentColor = IconTint,
        ),
    ) {
        Icon(icon.icon, contentDescription = icon.contentDescription, modifier = Modifier.size(20.dp))
    }
}

// =============================================================================================
// Icons — built in code so no icons library is needed. Replace with your own drawables:
// ImageVector.vectorResource(R.drawable.ic_settings)
// =============================================================================================

object AppIcons {
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", autoMirror = true)
    }
    val Settings: ImageVector by lazy {
        icon(
            "Settings",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
                    "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96" +
                    "c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84" +
                    "c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33" +
                    "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58" +
                    "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
                    "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
                    "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54" +
                    "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
                    "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94z" +
                    "M12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
        )
    }
    val Info: ImageVector by lazy {
        icon(
            "Info",
            "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                    "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
        )
    }

    private fun icon(name: String, pathData: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}

// =============================================================================================
// Demo bodies (replace with your own)
// =============================================================================================

@Composable
private fun ProfileHeader(initials: String, name: String, phone: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        Box(
            Modifier
                .size(88.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextSecondary)
        }
        Text(
            name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(phone, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun DemoBody(next: String? = null, onNext: () -> Unit = {}) {
    Column(Modifier.padding(16.dp)) {
        if (next != null) {
            Button(onClick = onNext, modifier = Modifier.padding(bottom = 16.dp)) { Text(next) }
        }
        // Filler so there is something to scroll.
        repeat(20) { i ->
            Box(
                Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Rând ${i + 1}")
            }
        }
    }
}
