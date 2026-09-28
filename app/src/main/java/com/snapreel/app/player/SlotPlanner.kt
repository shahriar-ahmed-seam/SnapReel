package com.snapreel.app.player

import android.net.Uri
import com.snapreel.app.data.model.MediaItem

/**
 * Pure slot assignment for [ReelPlayerPool] (design › Playback).
 *
 * A slot is one pooled player; `bound[i]` is the item URI slot `i` is bound to, or null when free.
 */
object SlotPlanner {

    const val MAX_CAPACITY = 3

    /**
     * The pages that should hold a player, highest priority first: the current page, then the
     * next, then the previous one. Only in-range videos count, truncated to [capacity] (1..3).
     */
    fun wanted(items: List<MediaItem>, current: Int, capacity: Int, exclude: Set<Uri> = emptySet()): List<Uri> {
        val cap = capacity.coerceIn(1, MAX_CAPACITY)
        val currentUri = items.getOrNull(current)?.takeIf { it.isVideo }?.uri
        return listOf(current, current + 1, current - 1)
            .mapNotNull { items.getOrNull(it)?.takeIf { item -> item.isVideo }?.uri }
            .distinct()
            // Excluded neighbors (e.g. ones that just failed) give their place to the next candidate
            // before the capacity cut, so a free slot never goes unused. The current page is never excluded.
            .filter { it == currentUri || it !in exclude }
            .take(cap)
    }

    /**
     * The new binding for every slot (same size as [bound]).
     * - A slot already bound to a wanted URI keeps it (no re-prepare).
     * - Remaining wanted URIs fill free slots first, then slots bound to unwanted URIs, in priority order.
     * - Every other slot becomes free.
     */
    fun plan(
        items: List<MediaItem>,
        current: Int,
        capacity: Int,
        bound: List<Uri?>,
        exclude: Set<Uri> = emptySet(),
    ): List<Uri?> {
        val wanted = wanted(items, current, capacity.coerceAtMost(bound.size.coerceAtLeast(1)), exclude)
            .take(bound.size)
        val result = bound.map { uri -> uri?.takeIf { it in wanted } }.toMutableList()
        // A URI bound to two slots (never produced by the pool) keeps only its first slot.
        val seen = HashSet<Uri>()
        for (i in result.indices) {
            val uri = result[i] ?: continue
            if (!seen.add(uri)) result[i] = null
        }
        val missing = wanted.filter { it !in seen }.toMutableList()
        val freeFirst = result.indices.filter { result[it] == null }
            .sortedBy { if (bound[it] == null) 0 else 1 }
        for (slot in freeFirst) {
            if (missing.isEmpty()) break
            result[slot] = missing.removeAt(0)
        }
        return result
    }
}
