package com.dishanhai.gt_shanhai

/**
 * Kotlin 运行时接入标记。
 *
 * 仅用于确认 Kotlin 源集可编译、kotlin-stdlib 已正确内嵌进 jar；
 * 首个正式 Kotlin 类加入后可直接删除本文件。
 */
object KotlinSupport {
    const val STDLIB_VERSION: String = "2.0.21"

    @JvmStatic
    fun isAvailable(): Boolean = true
}
