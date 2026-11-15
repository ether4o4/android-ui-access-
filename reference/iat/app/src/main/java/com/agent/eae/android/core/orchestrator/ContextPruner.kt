package com.agent.eae.android.core.orchestrator

import com.agent.eae.android.core.Role
import com.agent.eae.android.core.Turn

/**
 * Layer 5's pruner. Everything it removes is already durable somewhere else — tool output in a log
 * file, facts in the memory vault — so pruning costs recall, not information.
 */
class ContextPruner(private val maxChars: Int) {
    data class Result(val turns: List<Turn>, val charsRemoved: Int)

    /**
     * Drops turns flagged prunable (superseded failures), then slides a window over what is left.
     * The opening user turn is always kept: it is the task, and losing it derails the run.
     */
    fun prune(turns: List<Turn>): Result {
        val before = turns.sumOf { it.content.length }

        val survivors = turns.filterNot { it.prunable }.toMutableList()

        val anchorIndex = survivors.indexOfFirst { it.role == Role.USER }
        val anchor = if (anchorIndex >= 0) survivors.removeAt(anchorIndex) else null

        val kept = ArrayDeque<Turn>()
        var budget = maxChars - (anchor?.content?.length ?: 0)

        for (turn in survivors.asReversed()) {
            if (budget <= MIN_TAIL_CHARS) break
            val turnCost = turn.content.length
            if (turnCost <= budget) {
                kept.addFirst(turn)
                budget -= turnCost
            } else {
                kept.addFirst(turn.copy(content = clip(turn.content, budget)))
                budget = 0
            }
        }

        val out = buildList {
            anchor?.let { add(it) }
            addAll(kept)
        }
        val after = out.sumOf { it.content.length }
        return Result(out, (before - after).coerceAtLeast(0))
    }

    /**
     * Marks the failed attempt/observation pair that preceded a success as prunable. Keeping dead
     * tracebacks in context is the single biggest source of small-model confusion over long loops.
     */
    fun markResolvedFailures(turns: MutableList<Turn>) {
        for (i in turns.indices) {
            val turn = turns[i]
            if (turn.role != Role.TOOL || turn.prunable) continue
            if (!looksLikeFailure(turn.content)) continue
            val laterSuccess = turns.drop(i + 1).any { it.role == Role.TOOL && !looksLikeFailure(it.content) }
            if (laterSuccess) {
                turns[i] = turn.copy(
                    content = "[earlier failed attempt pruned after it was resolved]",
                    prunable = true,
                )
                val assistantBefore = i - 1
                if (assistantBefore >= 0 && turns[assistantBefore].role == Role.ASSISTANT) {
                    turns[assistantBefore] = turns[assistantBefore].copy(prunable = true)
                }
            }
        }
    }

    private fun looksLikeFailure(text: String): Boolean =
        text.contains("[error", ignoreCase = true) ||
            text.contains("Traceback", ignoreCase = true) ||
            text.startsWith("Refused:", ignoreCase = true)

    private fun clip(text: String, budget: Int): String {
        if (budget <= ELLIPSIS.length + 40) return ELLIPSIS
        val keep = budget - ELLIPSIS.length
        val head = keep * 2 / 3
        val tail = keep - head
        return text.take(head) + ELLIPSIS + text.takeLast(tail)
    }

    private companion object {
        const val MIN_TAIL_CHARS = 200
        const val ELLIPSIS = "\n…[pruned]…\n"
    }
}
