package com.shanhai.machine.stellar;

import appeng.api.crafting.PatternDetailsHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 样板物品的 tooltip 提示：<b>没填配方类型 / 类型歧义 ⇒ 明确写出「搭载后不会执行」</b>。
 *
 * <p>用户要求（阶段七）：「Jade 在样板物品上（tooltip）：没填类型时给一行提示，例如
 * 『未填写配方类型：搭载后不会执行』」。
 *
 * <h2>为什么挂在 {@link ItemTooltipEvent} 而不是 Jade</h2>
 * 样板是<b>物品</b>（在物品栏/样板终端/终端里的样板槽），Jade 只画<b>方块/实体</b>的抬头；
 * 物品的成就行本来就走 Forge 的 {@link ItemTooltipEvent}（JEI/终端/物品栏都覆盖）。
 * <p>只对 {@link PatternDetailsHelper#isEncodedPattern(ItemStack)} 为真的栈动手 ⇒
 * <b>别的物品一个字都不加</b>（包括通配符样板以外的所有东西）。
 *
 * <h2>🔴 与「闸」的关系</h2>
 * 这里只是<b>说明</b>，不做判断、不改状态：真正拦不拦只由
 * {@link ShanhaiPatternTypeReader#gateBlocks} 一处决定（闸关时机器照跑，但提示仍然存在 ——
 * 因为「这块样板没写类型」是物品自身的事实，与某台机器开没开闸无关）。
 */
@Mod.EventBusSubscriber(modid = "shanhai", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ShanhaiPatternTooltipWatch {

    /** 未标注类型（红字）。 */
    private static final String LANG_UNTYPED = "shanhai.machine.stellar_sample_assembler.tooltip.untyped";

    /** 类型歧义（黄字）。 */
    private static final String LANG_AMBIGUOUS = "shanhai.machine.stellar_sample_assembler.tooltip.ambiguous";

    private ShanhaiPatternTooltipWatch() {}

    /** 物品 tooltip 回调（客户端）。 */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        final ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        // 只认「已编码的 AE 样板」；不是样板的物品直接返回（零影响）
        if (!PatternDetailsHelper.isEncodedPattern(stack)) {
            return;
        }
        final ShanhaiPatternTypeReader.Resolution resolution = ShanhaiPatternTypeReader.resolve(stack);
        switch (resolution.verdict()) {
            case NONE -> event.getToolTip().add(
                    Component.translatable(LANG_UNTYPED).withStyle(ChatFormatting.RED));
            case AMBIGUOUS -> event.getToolTip().add(
                    Component.translatable(LANG_AMBIGUOUS, resolution.ids()).withStyle(ChatFormatting.YELLOW));
            default -> {
                // UNIQUE：已标注，什么都不加（正常样板不该多一行噪音）
            }
        }
    }
}
