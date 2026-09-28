package com.snapreel.app.ui.viewer

import android.os.Build
import android.view.HapticFeedbackConstants

/**
 * Whether a settle deserves a haptic tick: the setting is on and the pager settled on a different
 * item than before. The initial settle ([previous] = null) never ticks.
 */
fun <T> shouldTick(previous: T?, current: T, enabled: Boolean): Boolean =
    enabled && previous != null && previous != current

/** The tick constant: `SEGMENT_TICK` on API 34+, `CLOCK_TICK` below. */
fun hapticTickConstant(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }
