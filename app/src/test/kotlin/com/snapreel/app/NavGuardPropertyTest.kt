package com.snapreel.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.navigation.NavGuardPolicy
import com.snapreel.app.navigation.Routes
import com.snapreel.app.navigation.SnapReelNavGraph
import com.snapreel.app.navigation.navigateFrom
import com.snapreel.app.navigation.popFrom
import com.snapreel.app.testsupport.Adapters
import com.snapreel.app.testsupport.FakeScreens
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Property 6: Bug Condition - Navigation guard is idempotent.
 *
 * _For any_ back stack rooted at Home and any burst of k ≥ 1 identical taps (tile, play button,
 * back arrow) from the same screen before the next frame, guarded navigation SHALL change the back
 * stack at most once, leave at most one viewer entry, and never remove Home.
 *
 * Three levels: the pure [NavGuardPolicy] over modeled back stacks; `navigateFrom`/`popFrom` on a
 * real `TestNavHostController`; and Compose taps through `SnapReelNavGraph(testNavController)` with
 * [FakeScreens] (bursts fired within one click, and a real double tap with the frame clock paused).
 *
 * **Validates: Requirements 2.9**
 */
@RunWith(AndroidJUnit4::class)
class NavGuardPropertyTest {

    @get:Rule
    val compose = createComposeRule()

    private val folder: Uri = Uri.parse("content://com.snapreel.test.documents/tree/root")
    private val otherFolder: Uri = Uri.parse("content://com.snapreel.test.documents/tree/other")

    // ─── Pure model ─────────────────────────────────────────────────────────────────────

    private enum class Tap { OPEN_GRID, TILE, PLAY, SETTINGS, BACK }

    private data class Entry(val id: String, val route: String)

    /** A back stack modeled after NavController's: forward pushes, back pops, both via [NavGuardPolicy]. */
    private class ModelStack(initial: List<String>) {
        private var nextId = 0
        val entries: MutableList<Entry> = (listOf(Routes.HOME) + initial).map { Entry("e${nextId++}", it) }.toMutableList()
        val top: Entry get() = entries.last()

        fun tap(tap: Tap, source: Entry, sourceStarted: Boolean): Boolean = when (tap) {
            Tap.BACK -> {
                if (NavGuardPolicy.canPop(top.id, source.id, hasPrevious = entries.size > 1)) {
                    entries.removeAt(entries.lastIndex); true
                } else false
            }
            else -> {
                if (NavGuardPolicy.canNavigate(top.id, source.id, sourceStarted)) {
                    entries += Entry("e${nextId++}", routeFor(tap)); true
                } else false
            }
        }

        private fun routeFor(tap: Tap) = when (tap) {
            Tap.OPEN_GRID -> Routes.GRID
            Tap.TILE, Tap.PLAY -> Routes.VIEWER
            Tap.SETTINGS -> Routes.SETTINGS
            Tap.BACK -> error("not a forward tap")
        }
    }

    private fun isViewer(route: String) = route == Routes.VIEWER || route == Routes.LANDSCAPE_VIEWER

    @Test
    fun policy_burstChangesStackAtMostOnce() = runTest {
        val routes = Arb.choice(Arb.constant(Routes.GRID), Arb.constant(Routes.VIEWER), Arb.constant(Routes.LANDSCAPE_VIEWER), Arb.constant(Routes.SETTINGS))
        checkAll(
            1_000,
            Arb.list(routes, 0..5),
            Arb.enum<Tap>(),
            Arb.int(1..8),
            Arb.int(0..20), // which entry the taps come from (mostly the top)
            Arb.boolean(),
        ) { initial, tap, k, sourcePick, started ->
            val stack = ModelStack(initial)
            val before = stack.entries.toList()
            // 2 in 3 bursts come from the top entry; the rest from a stale screen further down.
            val source = if (sourcePick % 3 != 0) stack.top else before[sourcePick % before.size]
            val sourceIsTop = source == stack.top
            val viewersBefore = before.count { isViewer(it.route) }

            val acted = (1..k).map { stack.tap(tap, source, started) }
            val after = stack.entries.toList()

            assertTrue("burst of $k $tap changed the stack ${acted.count { it }} times", acted.count { it } <= 1)
            assertTrue("only the first tap of a burst may act", acted.drop(1).none { it })
            assertEquals("Home must stay at the bottom", Routes.HOME, after.first().route)
            assertTrue("at most one new viewer entry", after.count { isViewer(it.route) } <= viewersBefore + 1)
            val expected = when (tap) {
                Tap.BACK -> sourceIsTop && before.size > 1
                else -> sourceIsTop && started
            }
            assertEquals("burst of $k $tap from ${if (sourceIsTop) "top" else "stale"} entry (started=$started) on $before", expected, acted.first())
            assertEquals(
                "the stack changed by exactly the first tap",
                if (!expected) before.size else if (tap == Tap.BACK) before.size - 1 else before.size + 1,
                after.size,
            )
        }
    }

    @Test
    fun policy_randomSessionsKeepHomeAndOneViewer() = runTest {
        checkAll(1_000, Arb.list(Arb.enum<Tap>(), 1..15), Arb.list(Arb.int(1..6), 15..15)) { taps, bursts ->
            val stack = ModelStack(emptyList())
            taps.forEachIndexed { i, tap ->
                // Only taps the top screen offers: viewers and Settings only go back.
                val topRoute = stack.top.route
                val offered = when (topRoute) {
                    Routes.HOME -> tap in setOf(Tap.OPEN_GRID, Tap.PLAY, Tap.SETTINGS)
                    Routes.GRID -> tap in setOf(Tap.TILE, Tap.BACK)
                    else -> tap == Tap.BACK
                }
                if (!offered) return@forEachIndexed
                val source = stack.top
                val sizeBefore = stack.entries.size
                repeat(bursts[i]) { stack.tap(tap, source, sourceStarted = true) }
                assertTrue(kotlin.math.abs(stack.entries.size - sizeBefore) <= 1)
                assertEquals(Routes.HOME, stack.entries.first().route)
                assertTrue("viewers: ${stack.entries}", stack.entries.count { isViewer(it.route) } <= 1)
            }
        }
    }

    // ─── NavController level ────────────────────────────────────────────────────────────

    private class ResumedOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun newNavController(context: Context): TestNavHostController =
        TestNavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setLifecycleOwner(ResumedOwner())
            setViewModelStore(ViewModelStore())
            graph = Adapters.appNavGraph(this)
        }

    private fun TestNavHostController.routes(): List<String> =
        backStack.mapNotNull { it.destination.route }.filter { it != graph.route }

    @Test
    fun navController_burstsActOnceAndKeepHome() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        checkAll(100, Arb.list(Arb.enum<Tap>(), 1..12), Arb.list(Arb.int(1..5), 12..12)) { taps, bursts ->
            val nav = newNavController(context)
            taps.forEachIndexed { i, tap ->
                val source: NavBackStackEntry = nav.currentBackStackEntry!!
                val route = source.destination.route
                val target = when {
                    route == Routes.HOME && tap == Tap.OPEN_GRID -> Routes.grid(folder.toString())
                    route == Routes.HOME && tap == Tap.PLAY -> Routes.viewer(folder.toString(), 2, fresh = true)
                    route == Routes.HOME && tap == Tap.SETTINGS -> Routes.SETTINGS
                    route == Routes.GRID && tap == Tap.TILE -> Routes.landscapeViewer(folder.toString(), 1, fresh = false)
                    tap == Tap.BACK -> null
                    else -> return@forEachIndexed
                }
                val before = nav.routes()
                val acted = (1..bursts[i]).map {
                    if (target == null) nav.popFrom(source) else nav.navigateFrom(source, target)
                }
                val after = nav.routes()
                assertTrue("${bursts[i]}× $tap on $before → $after acted $acted", acted.count { it } <= 1)
                assertEquals("first tap acts unless it is back on Home", !(target == null && before.size == 1), acted.first())
                assertEquals(Routes.HOME, after.first())
                assertTrue("one viewer at most: $after", after.count(::isViewer) <= 1)
            }
        }
    }

    // ─── Compose: SnapReelNavGraph(testNavController) ───────────────────────────────────

    private lateinit var nav: TestNavHostController
    private lateinit var screens: FakeScreens

    private fun setUpGraph() {
        screens = FakeScreens(folder, otherFolder)
        compose.setContent {
            val context = LocalContext.current
            nav = remember {
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                    navigatorProvider.addNavigator(DialogNavigator())
                }
            }
            SnapReelNavGraph(navController = nav, screens = screens)
        }
        compose.waitForIdle()
    }

    private fun routes(): List<String> = nav.routes()

    private fun burstClick(tag: String, k: Int) {
        screens.burst = k
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    /** A real double tap: two clicks with the frame clock paused, so the source screen is still composed. */
    private fun doubleTap(tag: String) {
        screens.burst = 1
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun compose_burstsOnTilePlayAndBackActOnce() {
        setUpGraph()
        assertEquals(listOf(Routes.HOME), routes())

        for (k in listOf(2, 3, 5)) {
            // Home folder card → one grid.
            burstClick(FakeScreens.Tags.HOME_OPEN, k)
            assertEquals("k=$k open", listOf(Routes.HOME, Routes.GRID), routes())

            // Grid tile → one viewer (grid tap: fresh = false).
            burstClick(FakeScreens.Tags.GRID_TILE, k)
            assertEquals("k=$k tile", listOf(Routes.HOME, Routes.GRID, Routes.VIEWER), routes())
            assertEquals(false, nav.currentBackStackEntry!!.arguments!!.getBoolean("fresh"))
            val gridEntry = nav.previousBackStackEntry!!
            val homeEntry = nav.backStack.first { it.destination.route == Routes.HOME }
            val homeIndexBefore = homeEntry.savedStateHandle.get<Int>(LAST_VIEWED_INDEX)

            // Viewer back → the grid, with the returned index; the dropped taps write nothing to Home.
            screens.viewerReturnIndex = 10 + k
            burstClick(FakeScreens.Tags.VIEWER_BACK, k)
            assertEquals("k=$k viewer back", listOf(Routes.HOME, Routes.GRID), routes())
            assertEquals(10 + k, gridEntry.savedStateHandle.get<Int>(LAST_VIEWED_INDEX))
            assertEquals(homeIndexBefore, homeEntry.savedStateHandle.get<Int>(LAST_VIEWED_INDEX))

            // Grid back → Home, kept.
            burstClick(FakeScreens.Tags.GRID_BACK, k)
            assertEquals("k=$k grid back", listOf(Routes.HOME), routes())

            // Home play button → one viewer (fresh = true); back → Home, kept.
            burstClick(FakeScreens.Tags.HOME_PLAY, k)
            assertEquals("k=$k play", listOf(Routes.HOME, Routes.VIEWER), routes())
            assertEquals(true, nav.currentBackStackEntry!!.arguments!!.getBoolean("fresh"))
            burstClick(FakeScreens.Tags.VIEWER_BACK, k)
            assertEquals("k=$k play back", listOf(Routes.HOME), routes())
        }
    }

    private companion object {
        /** `NavGraph.kt`'s key for the index a viewer returns to the entry below it. */
        const val LAST_VIEWED_INDEX = "lastViewedIndex"
    }

    @Test
    fun compose_doubleTapsActOnce() {
        setUpGraph()
        doubleTap(FakeScreens.Tags.HOME_OPEN)
        assertEquals(listOf(Routes.HOME, Routes.GRID), routes())
        doubleTap(FakeScreens.Tags.GRID_TILE)
        assertEquals(listOf(Routes.HOME, Routes.GRID, Routes.VIEWER), routes())
        doubleTap(FakeScreens.Tags.VIEWER_BACK)
        assertEquals(listOf(Routes.HOME, Routes.GRID), routes())
        doubleTap(FakeScreens.Tags.GRID_BACK)
        assertEquals(listOf(Routes.HOME), routes())
        doubleTap(FakeScreens.Tags.HOME_SETTINGS)
        assertEquals(listOf(Routes.HOME, Routes.SETTINGS), routes())
        doubleTap(FakeScreens.Tags.SETTINGS_BACK)
        assertEquals(listOf(Routes.HOME), routes())
    }
}
