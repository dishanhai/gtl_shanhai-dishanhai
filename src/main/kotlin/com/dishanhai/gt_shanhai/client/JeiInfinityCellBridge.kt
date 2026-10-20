package com.dishanhai.gt_shanhai.client

import mezz.jei.common.Internal
import mezz.jei.gui.util.CommandUtil
import mezz.jei.gui.util.GiveAmount
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.apache.logging.log4j.LogManager

/**
 * JEI 无限盘发放桥。
 *
 * 放在 mixin 之外：Mixin 会把 mixin 类的所有字段/方法（含 Kotlin companion 生成的
 * 静态字段与 lambda 助手）合并进目标类，能不小就不小。
 */
object JeiInfinityCellBridge {

    private val LOGGER = LogManager.getLogger("gt_shanhai_jei_infinity")

    /** 走 JEI 自己的作弊发放通道（单机创造 / 装了 JEI 服务的服务器都适用）。 */
    @JvmStatic
    fun giveInfinityCell(infinityCell: ItemStack, origin: String) {
        if (infinityCell.isEmpty) return
        val configs = Internal.getJeiClientConfigs()
        CommandUtil(configs.clientConfig, Internal.getServerConnection())
            .giveStack(infinityCell, GiveAmount.MAX)
        LOGGER.debug("[{}] infinity cell giveStack: {}", origin, infinityCell.tag)
    }

    @JvmStatic
    fun notifyGiven(infinityCell: ItemStack) {
        val player = Minecraft.getInstance().player ?: return
        player.displayClientMessage(
            Component.literal("[山海JEI] 已发送无限盘请求: ")
                .withStyle(ChatFormatting.GRAY)
                .append(infinityCell.hoverName.copy().withStyle(ChatFormatting.YELLOW)),
            true
        )
    }
}
