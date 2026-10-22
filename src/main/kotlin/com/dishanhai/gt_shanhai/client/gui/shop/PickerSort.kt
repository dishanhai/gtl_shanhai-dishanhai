package com.dishanhai.gt_shanhai.client.gui.shop

import java.text.Collator
import java.util.Locale

data class PickerSortEntry(
    val index: Int,
    val name: String,
    val modId: String,
    val modName: String,
    val id: String,
    val count: Long
)

object PickerSort {
    @JvmStatic
    fun order(entries: List<PickerSortEntry>, sort: String, inventory: Boolean, language: String): List<Int> {
        val comparator: Comparator<PickerSortEntry>? = when (sort) {
            "name" -> {
                val collator = collator(language)
                Comparator { first, second -> collator.compare(first.name, second.name) }
            }
            "mod" -> moduleComparator(language)
            "id" -> compareBy { it.id }
            "count" -> if (inventory) compareByDescending { it.count } else null
            else -> null
        }
        val sorted = if (comparator == null) entries else entries.sortedWith(comparator)
        return sorted.map { it.index }
    }

    @JvmStatic
    fun modules(entries: List<PickerSortEntry>, language: String): List<String> =
        entries.distinctBy { it.modId }.sortedWith(moduleComparator(language)).map { it.modId }

    private fun moduleComparator(language: String): Comparator<PickerSortEntry> {
        val collator = collator(language)
        return Comparator { first, second ->
            when {
                first.modId == "minecraft" -> if (second.modId == "minecraft") 0 else -1
                second.modId == "minecraft" -> 1
                else -> collator.compare(first.modName.ifBlank { first.modId }, second.modName.ifBlank { second.modId })
            }
        }
    }

    private fun collator(language: String): Collator =
        Collator.getInstance(if (language.startsWith("zh_", ignoreCase = true)) Locale.CHINA else Locale.ROOT)
}
