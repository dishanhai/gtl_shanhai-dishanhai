package com.dishanhai.gt_shanhai.common.shop

/**
 * 跳转显示名（山海署名，纯函数）：其他商品跳到本商品时显示的名字。
 * 填了称呼（linkKey）就用称呼，留空用商品名称（stub 的 displayName，即 ShopEntry#goodsDisplayName）；
 * 两个都空才写「(未命名商品)」。
 */
object ShopLinkNames {

    const val UNNAMED = "(未命名商品)"

    @JvmStatic
    fun label(linkKey: String?, displayName: String?): String {
        val alias = linkKey?.trim().orEmpty()
        if (alias.isNotEmpty()) return alias
        val name = displayName?.trim().orEmpty()
        return name.ifEmpty { UNNAMED }
    }
}
