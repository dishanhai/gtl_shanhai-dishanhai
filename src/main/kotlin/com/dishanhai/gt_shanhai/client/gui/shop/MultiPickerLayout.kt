package com.dishanhai.gt_shanhai.client.gui.shop

import com.dishanhai.gt_shanhai.client.gui.glass.GuiRect

data class PickerGeometry(
    val sidebar: GuiRect,
    val grid: GuiRect,
    val selection: GuiRect,
    val search: GuiRect,
    val footer: GuiRect
)

data class PickerGridRow(
    val y: Int,
    val height: Int,
    val module: String,
    val header: Boolean,
    val indices: List<Int>
)

object MultiPickerLayout {
    private const val ITEM_HEIGHT = 22
    private const val HEADER_HEIGHT = 18

    @JvmStatic
    @JvmOverloads
    fun geometry(width: Int, height: Int, headerExtraHeight: Int = 0): PickerGeometry {
        val margin = 12f
        val gap = 8f
        val sidebarWidth = 104f
        val selectionWidth = 234f
        val baseContentTop = 72f
        val contentTop = baseContentTop + maxOf(0, headerExtraHeight)
        val footerTop = maxOf(baseContentTop + gap, height - margin - 22f)
        val contentHeight = maxOf(0f, footerTop - gap - contentTop)
        val gridLeft = margin + sidebarWidth + gap
        val selectionLeft = width - margin - selectionWidth
        val gridWidth = maxOf(0f, selectionLeft - gap - gridLeft)
        return PickerGeometry(
            GuiRect(margin, contentTop, sidebarWidth, contentHeight),
            GuiRect(gridLeft, contentTop, gridWidth, contentHeight),
            GuiRect(selectionLeft, contentTop, selectionWidth, contentHeight),
            GuiRect(gridLeft, 44f + maxOf(0, headerExtraHeight), gridWidth, 20f),
            GuiRect(margin, footerTop, maxOf(0f, width - margin * 2f), 22f)
        )
    }

    @JvmStatic
    @JvmOverloads
    fun gridRows(
        indices: List<Int>,
        modules: List<String>,
        moduleOrder: List<String>,
        columns: Int,
        grouped: Boolean,
        collapsed: Set<String>,
        headerHeights: Map<String, Int> = emptyMap()
    ): List<PickerGridRow> {
        val columnCount = maxOf(1, columns)
        if (!grouped) {
            return indices.chunked(columnCount).mapIndexed { rowIndex, entries ->
                PickerGridRow(rowIndex * ITEM_HEIGHT, ITEM_HEIGHT, "", false, entries)
            }
        }
        val byModule = indices.groupBy { modules[it] }
        val order = LinkedHashSet(moduleOrder)
        order.addAll(byModule.keys)
        val rows = ArrayList<PickerGridRow>()
        var position = 0
        for (module in order) {
            val entries = byModule[module] ?: continue
            val headerHeight = maxOf(HEADER_HEIGHT, headerHeights[module] ?: HEADER_HEIGHT)
            rows.add(PickerGridRow(position, headerHeight, module, true, emptyList()))
            position += headerHeight
            if (module in collapsed) continue
            for (chunk in entries.chunked(columnCount)) {
                rows.add(PickerGridRow(position, ITEM_HEIGHT, module, false, chunk))
                position += ITEM_HEIGHT
            }
        }
        return rows
    }

    @JvmStatic
    fun totalHeight(rows: List<PickerGridRow>): Int =
        rows.lastOrNull()?.let { it.y + it.height } ?: 0

    @JvmStatic
    fun rowAtY(rows: List<PickerGridRow>, y: Int): Int {
        val rowIndex = lastRowStartingAtOrBefore(rows, y)
        if (rowIndex < 0) return -1
        val row = rows[rowIndex]
        return if (y.toLong() < row.y.toLong() + row.height) rowIndex else -1
    }

    @JvmStatic
    fun stickyHeader(rows: List<PickerGridRow>, y: Int): Int {
        var rowIndex = lastRowStartingAtOrBefore(rows, y)
        while (rowIndex >= 0) {
            if (rows[rowIndex].header) return rowIndex
            rowIndex--
        }
        return -1
    }

    @JvmStatic
    fun rowStarts(heights: List<Int>): IntArray {
        var position = 0
        return IntArray(heights.size) { rowIndex ->
            val start = position
            position += maxOf(0, heights[rowIndex])
            start
        }
    }

    @JvmStatic
    fun selectionRowAt(starts: IntArray, heights: List<Int>, y: Int): Int {
        var lower = 0
        var upper = minOf(starts.size, heights.size)
        while (lower < upper) {
            val middle = lower + (upper - lower) / 2
            if (starts[middle] <= y) lower = middle + 1 else upper = middle
        }
        val rowIndex = lower - 1
        if (rowIndex < 0) return -1
        return if (y.toLong() < starts[rowIndex].toLong() + maxOf(0, heights[rowIndex])) rowIndex else -1
    }

    @JvmStatic
    fun insertionIndex(starts: IntArray, heights: List<Int>, y: Int): Int {
        var lower = 0
        var upper = minOf(starts.size, heights.size)
        while (lower < upper) {
            val middle = lower + (upper - lower) / 2
            val midpointTwice = starts[middle].toLong() * 2 + maxOf(0, heights[middle])
            if (y.toLong() * 2 < midpointTwice) upper = middle else lower = middle + 1
        }
        return lower
    }

    private fun lastRowStartingAtOrBefore(rows: List<PickerGridRow>, y: Int): Int {
        var lower = 0
        var upper = rows.size
        while (lower < upper) {
            val middle = lower + (upper - lower) / 2
            if (rows[middle].y <= y) lower = middle + 1 else upper = middle
        }
        return lower - 1
    }
}
