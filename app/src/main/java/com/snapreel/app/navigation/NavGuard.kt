package com.snapreel.app.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * The navigation guard's rules, free of Android types so they can be checked on a modeled back stack.
 *
 * A tap acts only when the screen it came from is still the top back-stack entry. The rule is
 * "source is the top entry", not "source is RESUMED": picker results arrive while Home is only
 * STARTED, and a RESUMED check would drop them.
 */
object NavGuardPolicy {

    /** Forward navigation from [sourceId]: only from the top entry, and only once it is at least STARTED. */
    fun canNavigate(topId: String?, sourceId: String, sourceAtLeastStarted: Boolean): Boolean =
        topId == sourceId && sourceAtLeastStarted

    /** Back from [sourceId]: only from the top entry, and never the last one (Home is never popped). */
    fun canPop(topId: String?, sourceId: String, hasPrevious: Boolean): Boolean =
        topId == sourceId && hasPrevious
}

/** Whether [navigateFrom] would act for [source] right now. */
fun NavController.canNavigateFrom(source: NavBackStackEntry): Boolean =
    NavGuardPolicy.canNavigate(
        topId = currentBackStackEntry?.id,
        sourceId = source.id,
        sourceAtLeastStarted = source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
    )

/**
 * Navigates to [route] only if [source] is the current top entry and at least STARTED.
 * A second tap in the same frame comes from an entry that is no longer on top, so it is dropped.
 *
 * @return whether the navigation happened.
 */
fun NavController.navigateFrom(
    source: NavBackStackEntry,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
): Boolean {
    if (!canNavigateFrom(source)) return false
    navigate(route, builder)
    return true
}

/** Whether [popFrom] would act for [source] right now. */
fun NavController.canPopFrom(source: NavBackStackEntry): Boolean =
    NavGuardPolicy.canPop(
        topId = currentBackStackEntry?.id,
        sourceId = source.id,
        hasPrevious = previousBackStackEntry != null,
    )

/**
 * Pops [source] only if it is the current top entry and an entry sits below it.
 * [beforePop] runs first, with the entry that becomes the top, and only when the pop happens.
 *
 * @return whether the pop happened.
 */
fun NavController.popFrom(
    source: NavBackStackEntry,
    beforePop: (previous: NavBackStackEntry) -> Unit = {},
): Boolean {
    if (!canPopFrom(source)) return false
    previousBackStackEntry?.let(beforePop)
    return popBackStack()
}
