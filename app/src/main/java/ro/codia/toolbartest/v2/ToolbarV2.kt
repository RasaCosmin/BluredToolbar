package ro.codia.toolbartest.v2

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import ro.codia.toolbartest.AppIcons
import androidx.compose.animation.AnimatedContentTransitionScope

// =============================================================================================
// Timing / look
// =============================================================================================

private const val NAV_ANIM_MS = 300
private const val NAV_EXIT_FADE_MS = NAV_ANIM_MS * 2 / 3  // outgoing items are gone after ~2/3
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

// Toolbar background, as in Figma: linear gradient #EEEFF3 70% → 0% + progressive background
// blur 20 → 0, both top → bottom. (v1 used a stronger 28dp / EaseIn blur to match the reference video.)
private val ToolbarTint = Brush.verticalGradient(
    0f to ScreenBackground.copy(alpha = 0.7f),
    1f to ScreenBackground.copy(alpha = 0f),
)
private val ToolbarBlurStyle = HazeBlurStyle {
    blurEnabled(true)
    blurRadius(20.dp)
    progressive(HazeProgressive.verticalGradient(easing = LinearEasing, startIntensity = 1f, endIntensity = 0f))
    noiseFactor(0f)
}

// Shared-element keys. Equal keys on the two screens of a transition = the same element.
private const val ToolbarBackgroundKey = "toolbar-background"
private const val ToolbarSharedKey = "toolbar-shared-"   // + item key (sharedToolbarElement)
private const val HeaderKey = "app-screen-header"

// Overlay order during a transition (all of these are drawn above both screen bodies).
private const val ZBackground = 1f
private const val ZItems = 2f
private const val ZShared = 3f

private fun <T> navTween(durationMs: Int = NAV_ANIM_MS): FiniteAnimationSpec<T> =
    tween(durationMs, easing = Easing)

// =============================================================================================
// Host
// =============================================================================================

/**
 * Push or pop, per back stack entry. Written by the NavHost transition lambdas (they know,
 * including for the predictive-back gesture) right before a transition starts, and read lazily
 * by the slide offsets — so each screen knows which side it comes from / leaves to.
 */
internal class NavMotion {
    private val pops = HashMap<String, Boolean>()

    fun record(scope: AnimatedContentTransitionScope<NavBackStackEntry>, isPop: Boolean) {
        pops[scope.initialState.id] = isPop
        pops[scope.targetState.id] = isPop
    }

    fun isPop(entryId: String): Boolean = pops[entryId] ?: false

    fun forget(entryId: String) {
        pops.remove(entryId)
    }
}

/** Host-level things every screen needs: shared-element scope, push/pop, blur source state. */
@Stable
internal class AppChrome(
    val shared: SharedTransitionScope,
    val motion: NavMotion,
    val haze: HazeState,
)

private val LocalAppChrome = staticCompositionLocalOf<AppChrome> {
    error("screen { } / AppScreen must be used inside AppNavHost")
}

/**
 * Drop-in replacement for [NavHost] (Navigation 2). The NavHost itself moves nothing: each
 * [screen] slides its own body and animates its own toolbar items, while the toolbar
 * background is one shared element that stays put above both screens.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    val motion = remember { NavMotion() }
    // One blur state for the whole host: every screen body is a source, so mid-transition the
    // toolbar blurs both screens.
    val haze = rememberHazeState()

    SharedTransitionLayout(modifier.background(ScreenBackground)) {
        val chrome = remember(this, motion, haze) { AppChrome(this, motion, haze) }
        CompositionLocalProvider(LocalAppChrome provides chrome) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize(),
                // Exiting screens stay composed until their own (child) animations finish.
                enterTransition = { motion.record(this, isPop = false); EnterTransition.None },
                exitTransition = { motion.record(this, isPop = false); ExitTransition.None },
                popEnterTransition = { motion.record(this, isPop = true); EnterTransition.None },
                popExitTransition = { motion.record(this, isPop = true); ExitTransition.None },
                builder = builder,
            )
        }
    }
}

/** Use instead of `composable(route)` inside [AppNavHost]. */
fun NavGraphBuilder.screen(route: String, content: @Composable ScreenScope.() -> Unit) {
    composable(route) { entry ->
        val chrome = LocalAppChrome.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val scope = remember(this, chrome, entry.id, rtl) { ScreenScope(this, chrome, entry.id, rtl) }
        DisposableEffect(chrome, entry.id) {
            onDispose { chrome.motion.forget(entry.id) }
        }
        scope.content()
    }
}

/**
 * Receiver of a [screen]: this screen's enter/exit transition + the host's shared-element scope.
 */
@Stable
class ScreenScope internal constructor(
    animated: AnimatedVisibilityScope,
    private val chrome: AppChrome,
    private val entryId: String,
    private val rtl: Boolean,
) : AnimatedVisibilityScope by animated, SharedTransitionScope by chrome.shared {

    internal val animatedScope: AnimatedVisibilityScope = animated
    internal val haze: HazeState get() = chrome.haze

    /** Side this screen comes from: +1 = end (right in LTR), -1 = start. Read it lazily. */
    internal fun enterSide(): Int {
        // push: enters from the end, pop: enters from the start
        val side = if (chrome.motion.isPop(entryId)) -1 else 1
        return if (rtl) -side else side
    }

    /** Side this screen leaves to (push: towards the start, pop: towards the end). */
    internal fun exitSide(): Int = -enterSide()
}

// =============================================================================================
// Transitions (offset lambdas run during layout, after the NavHost recorded push/pop)
// =============================================================================================

/** Full-width slide, for the screen body. */
private fun ScreenScope.bodyEnter(): EnterTransition =
    slideInHorizontally(navTween()) { width -> width * enterSide() }

private fun ScreenScope.bodyExit(): ExitTransition =
    slideOutHorizontally(navTween()) { width -> width * exitSide() }

/** Toolbar icons: optional short drift + grow/shrink + fade. */
private fun ScreenScope.iconEnter(travelPx: Int, drift: Boolean): EnterTransition {
    val base = fadeIn(navTween()) + scaleIn(navTween(), initialScale = IconMinScale)
    return if (drift) base + slideInHorizontally(navTween()) { travelPx * enterSide() } else base
}

private fun ScreenScope.iconExit(travelPx: Int, drift: Boolean): ExitTransition {
    val base = fadeOut(navTween(NAV_EXIT_FADE_MS)) + scaleOut(navTween(), targetScale = IconMinScale)
    return if (drift) base + slideOutHorizontally(navTween()) { travelPx * exitSide() } else base
}

/** Fade only, for things that stay in place (the toolbar title). */
private val FadeEnter = fadeIn(navTween())
private val FadeExit = fadeOut(navTween(NAV_EXIT_FADE_MS))

// =============================================================================================
// Screen + toolbar
// =============================================================================================

@Immutable
data class ToolbarIcon(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/** Content of the toolbar's leading / trailing side: a row of items (8dp apart). */
typealias ToolbarSlot = @Composable ToolbarScope.() -> Unit

/** Receiver of a [ToolbarSlot]: a [RowScope], plus shared elements across screens. */
@Stable
interface ToolbarScope : RowScope {
    /**
     * Pairs this item with the item that has the same [key] on the other screen of a navigation
     * transition: it's then drawn once and stays still (e.g. back → back). Does nothing outside
     * an [AppScreen] (e.g. in a [PlainToolbar]).
     */
    @Composable
    fun Modifier.sharedToolbarElement(key: Any): Modifier
}

/** Ready-made slots: one or two icons, back, close. For anything else, write your own slot. */
object ToolbarDefaults {
    /** Back arrow. Stays still when the screen on the other side of the transition has one too. */
    fun back(onClick: () -> Unit, contentDescription: String = "Înapoi"): ToolbarSlot =
        { ToolbarIconButton(ToolbarIcon(AppIcons.ArrowBack, contentDescription, onClick), Modifier.sharedToolbarElement("back")) }

    /** Close (✕), e.g. for dialogs. Stays still like [back]. */
    fun close(onClick: () -> Unit, contentDescription: String = "Închide"): ToolbarSlot =
        { ToolbarIconButton(ToolbarIcon(CloseIcon, contentDescription, onClick), Modifier.sharedToolbarElement("close")) }

    /** One icon. */
    fun icon(icon: ToolbarIcon): ToolbarSlot = { ToolbarIconButton(icon) }

    /** Two icons, in order (start → end). */
    fun icons(first: ToolbarIcon, second: ToolbarIcon): ToolbarSlot = {
        ToolbarIconButton(first)
        ToolbarIconButton(second)
    }
}

/** The standard round toolbar button; use it in custom slots to keep the look. */
@Composable
fun ToolbarIconButton(icon: ToolbarIcon, modifier: Modifier = Modifier) {
    IconButton(
        onClick = icon.onClick,
        modifier = modifier.size(ToolbarIconSize),
        colors = IconButtonDefaults.iconButtonColors(containerColor = IconCircle, contentColor = IconTint),
    ) {
        Icon(icon.icon, contentDescription = icon.contentDescription, modifier = Modifier.size(20.dp))
    }
}

/**
 * Standard screen: toolbar on top (stays put, items animate), a lazy body below (slides) that
 * scrolls behind the toolbar (which blurs it).
 *
 * Toolbar: [leading] / [trailing] are slots — use [ToolbarDefaults] (one or two icons, back,
 * close) or any composable. The title stays centred on screen, clear of the wider side.
 *
 * Title modes:
 * - default: the body starts with a [header] — by default a large [title] — and the small toolbar
 *   title (+ optional [subtitle]) fades in once that header has scrolled completely off the screen.
 * - [inlineTitle] = true: the toolbar title is always shown, no scroll tracking. No large title by
 *   default; a custom [header] can still be passed and simply scrolls with the body.
 */
@Composable
fun ScreenScope.AppScreen(
    leading: ToolbarSlot? = null,
    trailing: ToolbarSlot? = null,
    title: String? = null,
    subtitle: String? = null,
    inlineTitle: Boolean = false,
    header: (@Composable () -> Unit)? = if (inlineTitle) null else title?.let { { LargeTitle(it) } },
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val tracksHeader = !inlineTitle && header != null
    val collapsed by remember(listState, tracksHeader) {
        derivedStateOf { !tracksHeader || listState.isHeaderOffScreen() }
    }
    val titleAlpha = animateFloatAsState(if (collapsed) 1f else 0f, tween(COLLAPSE_ANIM_MS), label = "titleAlpha")
    val bars = WindowInsets.systemBars.asPaddingValues()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Starts under the toolbar, scrolls up behind it.
            contentPadding = PaddingValues(
                top = bars.calculateTopPadding() + ToolbarHeight,
                bottom = bars.calculateBottomPadding(),
            ),
            modifier = Modifier
                .animateEnterExit(bodyEnter(), bodyExit())
                .fillMaxSize()
                // What the toolbar blurs (this body and, mid-transition, the other one).
                // The background must come after it, so it's part of the blurred copy: otherwise
                // the copy has transparent gaps and the sharp content shows through.
                .hazeSource(haze)
                .background(ScreenBackground),
        ) {
            header?.let { item(key = HeaderKey) { it() } }
            content()
        }

        AppToolbar(leading, trailing, title, subtitle, titleAlpha = { titleAlpha.value })
    }
}

/** The header item has left the viewport (status bar area included) entirely. */
private fun LazyListState.isHeaderOffScreen(): Boolean {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return false   // not laid out yet
    val header = info.visibleItemsInfo.firstOrNull { it.key == HeaderKey } ?: return true
    return header.offset + header.size <= info.viewportStartOffset
}

/** Animated toolbar of an [AppScreen]: [ToolbarLayout] + the navigation animations. */
@Composable
private fun ScreenScope.AppToolbar(
    leading: ToolbarSlot?,
    trailing: ToolbarSlot?,
    title: String?,
    subtitle: String?,
    titleAlpha: () -> Float,
) {
    val travelPx = with(LocalDensity.current) { IconTravel.roundToPx() }
    ToolbarLayout(
        leading = leading,
        trailing = trailing,
        title = title,
        subtitle = subtitle,
        scope = { row -> AnimatedToolbarScope(row, this) },
        // Background: the same shared element on every screen, so during a transition it's drawn
        // once, in place, above both bodies — the bodies slide under it.
        backgroundModifier = Modifier
            .sharedElement(
                rememberSharedContentState(ToolbarBackgroundKey),
                animatedVisibilityScope = animatedScope,
                zIndexInOverlay = ZBackground,
            )
            .hazeBlur(HazeInput.Sources(haze), ToolbarBlurStyle),
        // Items of both screens are drawn above both bodies too, each animating its own way.
        itemsModifier = Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = ZItems),
        // Leading side: short drift + shrink/grow + fade.
        leadingModifier = Modifier.animateEnterExit(
            iconEnter(travelPx, drift = true),
            iconExit(travelPx, drift = true),
        ),
        titleModifier = Modifier
            .animateEnterExit(FadeEnter, FadeExit)
            .graphicsLayer { alpha = titleAlpha() },
        // Trailing side: shrink/grow + fade in place.
        trailingModifier = Modifier.animateEnterExit(
            iconEnter(travelPx, drift = false),
            iconExit(travelPx, drift = false),
        ),
    )
}

/** [ToolbarScope] inside an [AppScreen]: shared items become real shared elements. */
private class AnimatedToolbarScope(row: RowScope, private val screen: ScreenScope) :
    ToolbarScope, RowScope by row {

    @Composable
    override fun Modifier.sharedToolbarElement(key: Any): Modifier = with(screen) {
        this@sharedToolbarElement.sharedElement(
            rememberSharedContentState(ToolbarSharedKey + key),
            animatedVisibilityScope = animatedScope,
            zIndexInOverlay = ZShared,
        )
    }
}

/** [ToolbarScope] outside navigation (dialogs): nothing to share with. */
private class StaticToolbarScope(row: RowScope) : ToolbarScope, RowScope by row {
    @Composable
    override fun Modifier.sharedToolbarElement(key: Any): Modifier = this
}

/**
 * Static toolbar with the same look as the [AppScreen] one, but no navigation animations — for
 * dialogs (or any screen outside [AppNavHost]).
 *
 * @param hazeState the state of the content to blur behind the toolbar (put `hazeSource` on it);
 *   null = gradient tint only.
 * @param insets top insets to pad by: status bar for a full-screen dialog, none for a floating one.
 */
@Composable
fun PlainToolbar(
    modifier: Modifier = Modifier,
    leading: ToolbarSlot? = null,
    trailing: ToolbarSlot? = null,
    title: String? = null,
    subtitle: String? = null,
    hazeState: HazeState? = null,
    insets: WindowInsets = WindowInsets.statusBars,
) {
    ToolbarLayout(
        modifier = modifier,
        leading = leading,
        trailing = trailing,
        title = title,
        subtitle = subtitle,
        scope = { row -> StaticToolbarScope(row) },
        insets = insets,
        backgroundModifier = hazeState?.let { Modifier.hazeBlur(HazeInput.Sources(it), ToolbarBlurStyle) }
            ?: Modifier,
    )
}

/**
 * The toolbar's look, without any animation: background (gradient tint, plus whatever
 * [backgroundModifier] adds, e.g. blur), the leading / trailing slots and the centred title.
 * The modifiers are hooks for [AppToolbar]'s animations; [PlainToolbar] leaves them empty.
 */
@Composable
private fun ToolbarLayout(
    leading: ToolbarSlot?,
    trailing: ToolbarSlot?,
    title: String?,
    subtitle: String?,
    scope: (RowScope) -> ToolbarScope,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.statusBars,
    backgroundModifier: Modifier = Modifier,
    itemsModifier: Modifier = Modifier,
    leadingModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    trailingModifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        // Background, behind the insets too (status bar).
        Box(
            Modifier
                .matchParentSize()
                .then(backgroundModifier)
                .drawBehind { drawRect(ToolbarTint) },
        )
        CenteredTitleLayout(
            modifier = itemsModifier
                .fillMaxWidth()
                .windowInsetsPadding(insets)
                .height(ToolbarHeight)
                .padding(horizontal = 16.dp),
            leading = leading?.let { slot -> { ToolbarRow(leadingModifier) { slot(scope(this)) } } },
            title = title?.let { text -> { ToolbarTitle(text, subtitle, titleModifier) } },
            trailing = trailing?.let { slot -> { ToolbarRow(trailingModifier) { slot(scope(this)) } } },
        )
    }
}

@Composable
private fun ToolbarRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ToolbarIconSpacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ToolbarTitle(title: String, subtitle: String?, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Leading at the start, trailing at the end, title centred on the whole width. The title gets
 * the same room on both sides — what's left after the wider of the two slots (+ a gap) — so it
 * stays centred on screen and never overlaps them; a too-long title ellipsizes.
 */
@Composable
private fun CenteredTitleLayout(
    modifier: Modifier,
    leading: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    Layout(
        contents = listOf(leading ?: {}, title ?: {}, trailing ?: {}),
        modifier = modifier,
    ) { (leadingMeasurables, titleMeasurables, trailingMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceable = leadingMeasurables.firstOrNull()?.measure(loose)
        val trailingPlaceable = trailingMeasurables.firstOrNull()?.measure(loose)

        val side = maxOf(leadingPlaceable?.width ?: 0, trailingPlaceable?.width ?: 0)
        val gap = if (side > 0) ToolbarIconSpacing.roundToPx() else 0
        val titleMaxWidth = (constraints.maxWidth - 2 * (side + gap)).coerceAtLeast(0)
        val titlePlaceable = titleMeasurables.firstOrNull()?.measure(loose.copy(maxWidth = titleMaxWidth))

        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            // placeRelative: start/end flip in RTL.
            leadingPlaceable?.placeRelative(0, (height - leadingPlaceable.height) / 2)
            trailingPlaceable?.placeRelative(width - trailingPlaceable.width, (height - trailingPlaceable.height) / 2)
            titlePlaceable?.placeRelative((width - titlePlaceable.width) / 2, (height - titlePlaceable.height) / 2)
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

// =============================================================================================
// Demo (replace with your own screens)
// =============================================================================================

private object Routes {
    const val Profile = "profile"
    const val Settings = "settings"
    const val Notifications = "notifications"
    const val Support = "support"
    const val Terms = "terms"   // a dialog destination
}

@Composable
fun AppNavigationV2(navController: NavHostController = rememberNavController()) {
    AppNavHost(navController, startDestination = Routes.Profile, modifier = Modifier.fillMaxSize()) {
        screen(Routes.Profile) {
            // dropUnlessResumed: ignores taps while the screen is animating in/out (no double push).
            val openSettings = dropUnlessResumed { navController.navigate(Routes.Settings) }
            val openSupport = dropUnlessResumed { navController.navigate(Routes.Support) }
            AppScreen(
                // Defaults: one icon on each side.
                leading = ToolbarDefaults.icon(ToolbarIcon(AppIcons.Settings, "Setări", openSettings)),
                trailing = ToolbarDefaults.icon(ToolbarIcon(AppIcons.Info, "Suport", openSupport)),
                title = "John Doe",
                subtitle = "+40 731 482 905",
                header = { ProfileHeader("JD", "John Doe", "+40 731 482 905") },
            ) {
                demoRows("Setări →", openSettings)
            }
        }
        screen(Routes.Settings) {
            val openNotifications = dropUnlessResumed { navController.navigate(Routes.Notifications) }
            val openTerms = dropUnlessResumed { navController.navigate(Routes.Terms) }
            AppScreen(
                leading = back(navController),
                trailing = ToolbarDefaults.icon(ToolbarIcon(AppIcons.Info, "Termeni", openTerms)),
                title = "Setări",
            ) {
                demoRows("Notificări →", openNotifications)
            }
        }
        screen(Routes.Notifications) {
            val openSupport = dropUnlessResumed { navController.navigate(Routes.Support) }
            AppScreen(
                leading = back(navController),
                // Default: two icons on the right.
                trailing = ToolbarDefaults.icons(
                    ToolbarIcon(AppIcons.Settings, "Setări notificări") { /* open notification settings */ },
                    ToolbarIcon(AppIcons.Info, "Suport", openSupport),
                ),
                title = "Notificări",
                inlineTitle = true,   // title straight in the toolbar, no large title
            ) {
                demoRows("Suport →", openSupport)
            }
        }
        screen(Routes.Support) {
            AppScreen(
                leading = back(navController),
                // Custom slot: any composable. The title stays centred on screen anyway.
                trailing = {
                    TextButton(onClick = { /* send */ }) { Text("Trimite", fontWeight = FontWeight.SemiBold) }
                },
                title = "Suport",
                inlineTitle = true,
            ) {
                demoRows()
            }
        }
        // Dialogs open in their own window, outside the screen transitions: no AppScreen here,
        // just the static PlainToolbar.
        dialog(
            Routes.Terms,
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            TermsDialog(onClose = { navController.popBackStack() })
        }
    }
}

/** Full-screen dialog: content scrolls under a [PlainToolbar] that blurs it. */
@Composable
private fun TermsDialog(onClose: () -> Unit) {
    val haze = rememberHazeState()
    val bars = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = bars.calculateTopPadding() + ToolbarHeight,
                bottom = bars.calculateBottomPadding(),
            ),
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(haze)
                .background(ScreenBackground),
        ) {
            demoRows()
        }
        PlainToolbar(
            leading = ToolbarDefaults.close(onClose),
            title = "Termeni și condiții",
            hazeState = haze,
        )
    }
}

private val CloseIcon: ImageVector by lazy {
    ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}

@Composable
private fun back(navController: NavHostController): ToolbarSlot =
    ToolbarDefaults.back(dropUnlessResumed { navController.popBackStack() })

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

private fun LazyListScope.demoRows(next: String? = null, onNext: () -> Unit = {}) {
    if (next != null) {
        item {
            Button(onClick = onNext, modifier = Modifier.padding(16.dp)) { Text(next) }
        }
    }
    // Filler so there is something to scroll.
    items(20) { i ->
        Box(
            Modifier
                .padding(horizontal = 16.dp)
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
