package com.dishanhai.gt_shanhai

/**
 * Kotlin 运行时接入标记。
 *
 * 编译和运行都认 libs/kotlinforforge-4.12.0-all.jar（stdlib 2.2.21）。
 * 整包走 Jar-in-Jar，不要把 kotlin.* 解进本 mod jar。
 */
object KotlinSupport {
    const val STDLIB_VERSION: String = "2.2.21"

    @JvmStatic
    fun isAvailable(): Boolean = true
}
