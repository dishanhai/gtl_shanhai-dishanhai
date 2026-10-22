package com.dishanhai.gt_shanhai.common.shop

/**
 * 旧式跳转称呼 → 稳定引用的一次性迁移（山海署名，纯函数）。
 *
 * 旧数据里 linkTo 填的是目标的称呼（linkKey），目标一改称呼就断；新数据存目标的 stableId。
 * 三张表同下标描述每个条目，返回「下标 → 新 linkTo」，只含需要改写的条目。
 * 称呼重名时先出现的条目胜（与客户端 ClientShopCatalog 的 putIfAbsent 一致）；
 * 已经是某个条目的 stableId、或哪个称呼都对不上的 linkTo 保持原样。
 * 身份清理沿用 Java String.trim，只剥离 U+0020 及以下字符，保留全形空白等称呼内容。
 */
object ShopLinkMigration {

    @JvmStatic
    fun rewrites(stableIds: List<String?>, linkKeys: List<String?>, linkTos: List<String?>): Map<Int, String> {
        val n = minOf(stableIds.size, linkKeys.size, linkTos.size)
        val ids = HashSet<String>()
        val byAlias = HashMap<String, String>()
        for (i in 0 until n) {
            val id = stableIds[i]?.trim { it <= ' ' }.orEmpty()
            if (id.isEmpty()) continue
            ids.add(id)
            val alias = linkKeys[i]?.trim { it <= ' ' }.orEmpty()
            if (alias.isNotEmpty()) byAlias.putIfAbsent(alias, id)
        }
        val out = LinkedHashMap<Int, String>()
        for (i in 0 until n) {
            val to = linkTos[i]?.trim { it <= ' ' }.orEmpty()
            if (to.isEmpty() || to in ids) continue
            out[i] = byAlias[to] ?: continue
        }
        return out
    }
}
