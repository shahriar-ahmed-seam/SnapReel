package com.snapreel.app

import android.view.HapticFeedbackConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.ui.viewer.hapticTickConstant
import com.snapreel.app.ui.viewer.shouldTick
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Property 12: Bug Condition - Haptic tick gating.
 *
 * _For any_ sequence of settled pages and any haptic setting, each viewer SHALL emit exactly one
 * tick per settle on a different item when the setting is on. It SHALL emit none on the initial
 * settle and none when the setting is off.
 *
 * The loop below is the viewers' settled-page effect: `shouldTick(lastSettled, settled, enabled)`,
 * then `lastSettled = settled` (reset to null whenever the pager content is recreated).
 *
 * **Validates: Requirements 2.16**
 */
@RunWith(AndroidJUnit4::class)
class HapticTickPropertyTest {

    /** Ticks the viewers' settled-page effect emits for [settles], starting from a fresh pager. */
    private fun ticks(settles: List<Int>, enabled: (Int) -> Boolean): List<Int> {
        var last: Int? = null
        val ticked = mutableListOf<Int>()
        settles.forEachIndexed { i, item ->
            if (shouldTick(last, item, enabled(i))) ticked += i
            last = item
        }
        return ticked
    }

    @Test
    fun oneTickPerSettleOnADifferentItem() = runTest {
        // Few distinct items so repeated settles on the same item (a swipe that snaps back) are common.
        checkAll(1_000, Arb.list(Arb.int(0..4), 0..40), Arb.boolean()) { settles, enabled ->
            val ticked = ticks(settles) { enabled }
            val expected = if (!enabled) emptyList() else settles.indices.filter { i -> i > 0 && settles[i] != settles[i - 1] }
            assertEquals("settles=$settles enabled=$enabled", expected, ticked)
            assertFalse("never on the initial settle", 0 in ticked)
        }
    }

    @Test
    fun settingIsReadAtEachSettle() = runTest {
        // The setting can change while a viewer is open: each settle uses the value at that moment.
        checkAll(1_000, Arb.list(Arb.int(0..4), 0..40), Arb.list(Arb.boolean(), 40..40)) { settles, setting ->
            val ticked = ticks(settles) { setting[it] }
            val expected = settles.indices.filter { i -> i > 0 && settles[i] != settles[i - 1] && setting[i] }
            assertEquals(expected, ticked)
        }
    }

    @Test
    fun pureFunctionCases() = runTest {
        checkAll(1_000, Arb.int(0..3).orNull(0.3), Arb.int(0..3), Arb.boolean()) { previous, current, enabled ->
            assertEquals(enabled && previous != null && previous != current, shouldTick(previous, current, enabled))
        }
    }

    @Test
    fun tickConstantOnThisSdk() {
        // Robolectric runs at sdk=35 (robolectric.properties): API 34+ uses SEGMENT_TICK.
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticTickConstant())
    }
}
