package com.dishanhai.gt_shanhai.client.gui.shop

import java.util.function.BiPredicate

interface PickerItem {
    fun getCount(): Long

    fun setCount(count: Long)
}

class PickerSelection<T : PickerItem> {
    private val items = mutableListOf<T>()

    fun getItems(): MutableList<T> = items

    fun add(item: T, same: BiPredicate<T, T>): T {
        val existing = items.firstOrNull { same.test(it, item) }
        if (existing != null) return existing
        items.add(item)
        return item
    }

    fun remove(item: T): Boolean = items.remove(item)

    fun toggle(item: T, same: BiPredicate<T, T>): Boolean {
        val index = items.indexOfFirst { same.test(it, item) }
        if (index >= 0) {
            items.removeAt(index)
            return false
        }
        items.add(item)
        return true
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices) return false
        val target = to.coerceIn(0, items.lastIndex)
        if (from == target) return false
        items.add(target, items.removeAt(from))
        return true
    }

    fun step(item: T, delta: Long): Long {
        val count = addClamp(item.getCount(), delta)
        item.setCount(count)
        return count
    }

    fun setAll(count: Long) {
        val clamped = clampCount(count)
        items.forEach { it.setCount(clamped) }
    }

    companion object {
        @JvmStatic
        fun addClamp(value: Long, delta: Long): Long {
            if (delta > 0 && value > Long.MAX_VALUE - delta) return Long.MAX_VALUE
            if (delta < 0 && value < Long.MIN_VALUE - delta) return 1L
            return clampCount(value + delta)
        }

        @JvmStatic
        fun mulClamp(value: Long, factor: Long): Long {
            if (factor <= 0 || value <= 0) return clampCount(value)
            if (value > Long.MAX_VALUE / factor) return Long.MAX_VALUE
            return clampCount(value * factor)
        }

        @JvmStatic
        fun clampCount(value: Long): Long = value.coerceAtLeast(1L)
    }
}
