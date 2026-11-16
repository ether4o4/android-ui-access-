package com.ether4o4.odcm.control

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.ether4o4.odcm.core.ScreenElement
import com.ether4o4.odcm.core.ScreenFormatter
import com.ether4o4.odcm.core.ScreenSnapshot

/**
 * Walks the accessibility tree once and keeps only what a person could see and use.
 * The node list is kept alongside the snapshot so an index maps straight back to a node.
 */
class ScreenReader(private val maxElements: Int = 512) {

    class Result(val snapshot: ScreenSnapshot, val nodes: List<AccessibilityNodeInfo>)

    fun read(root: AccessibilityNodeInfo?, width: Int, height: Int, ownPackage: String,
             extraRoots: List<AccessibilityNodeInfo> = emptyList()): Result {
        val elements = ArrayList<ScreenElement>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        var truncated = false
        val pkg = root?.packageName?.toString() ?: "unknown"
        if (root == null || pkg == ownPackage) {
            return Result(ScreenSnapshot(pkg, width, height, emptyList(), false), emptyList())
        }
        val screen = Rect(0, 0, width, height)
        val bounds = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        extraRoots.asReversed().filter { it.packageName?.toString() != ownPackage }.forEach(stack::addLast)
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty || !Rect.intersects(bounds, screen)) continue

            val label = labelOf(node)
            val useful = node.isClickable || node.isEditable || node.isScrollable ||
                node.isCheckable || label.isNotEmpty()
            if (useful) {
                if (elements.size >= maxElements) {
                    truncated = true
                    break
                }
                elements += ScreenElement(
                    index = elements.size,
                    role = ScreenFormatter.roleOf(node.className),
                    text = label,
                    viewId = node.viewIdResourceName,
                    left = bounds.left.coerceAtLeast(0),
                    top = bounds.top.coerceAtLeast(0),
                    right = bounds.right.coerceAtMost(width),
                    bottom = bounds.bottom.coerceAtMost(height),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    password = node.isPassword,
                    packageName = node.packageName?.toString().orEmpty(),
                )
                nodes += node
            }
            // Children pushed in reverse so the list reads top-to-bottom like the screen.
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }
        }
        return Result(ScreenSnapshot(pkg, width, height, elements, truncated), nodes)
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        if (node.isPassword) return node.hintText?.toString().orEmpty()
        val parts = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            if (node.isEditable && node.text.isNullOrEmpty()) node.hintText?.toString() else null,
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return parts.joinToString(" · ")
    }
}
