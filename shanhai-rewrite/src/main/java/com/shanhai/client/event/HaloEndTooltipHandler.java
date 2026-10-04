package com.shanhai.client.event;

import com.shanhai.ShanhaiMod;
import com.shanhai.item.ShanhaiItems;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * 终末之环的饰品说明行。
 *
 * <h2>为什么不用 {@code appendHoverText} / 不是"键体系"问题</h2>
 * 本工程<b>没有</b>针对物品的 tooltip 键体系：{@code MachineTooltips.DESC_KEY_PREFIX}
 * （{@code shanhai.tooltip.desc.}）只服务于 <b>GTCEu 机器</b>（由 {@code MachineTooltips}
 * 自己驱动），物品那边全工程 grep {@code ItemTooltipEvent|appendHoverText} 为 <b>0 命中</b>。
 * 所以任务书担心的"加到不生效的死键上"这里确实成立 —— 直接往 lang 里塞键<b>不会</b>显示。
 * 本类就是补上这条通路：客户端挂 {@link ItemTooltipEvent}，只对我们这一枚物品追加一行，
 * 键 = {@code shanhai.tooltip.halo_end.curio}（沿用 {@code shanhai.tooltip.*} 前缀，
 * 与机器那条同族，zh_cn / en_us 两份都补齐）。
 *
 * <p>守卫：Curios 不在 ⇒ 不加这一行（否则会提示一个根本用不了的功能）。
 * 类里没有任何 Curios 类型引用，所以 Curios 缺席时本类也能安全加载。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HaloEndTooltipHandler {

    private static final String CURIOS_MODID = "curios";
    private static final String KEY = "shanhai.tooltip.halo_end.curio";

    private HaloEndTooltipHandler() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!ModList.get().isLoaded(CURIOS_MODID)) {
            return;
        }
        if (!event.getItemStack().is(ShanhaiItems.HALO_END.get())) {
            return;
        }
        event.getToolTip().add(Component.translatable(KEY));
    }
}
