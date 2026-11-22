package com.shanhai.common.itemedit;

import com.shanhai.ShanhaiMod;

import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · 「删除/抹除物品」在 <b>MOD 总线</b>上的唯一挂点。
 *
 * <h2>为什么必须单独一个类</h2>
 * <p>{@code BuildCreativeModeTabContentsEvent} 是 <b>MOD 总线</b>事件
 * （{@code net.minecraftforge.event.BuildCreativeModeTabContentsEvent implements IModBusEvent}，
 * 2026-11-22 用 {@code javap} 核实），而
 * {@link ShanhaiItemRemovalHooks} 挂在 <b>FORGE 总线</b>（为了 {@code ServerTickEvent} /
 * {@code EntityItemPickupEvent} / {@code ServerStartedEvent}）。
 *
 * <p>🔴 两件事必须记住：
 * <ul>
 *   <li>把 MOD 总线事件挂到 FORGE 总线上<b>不会崩</b>，只是<b>永不触发</b> —— 正是这一个，
 *       让「被删物品从创造栏隐藏」从上线起一直是死代码（2026-11-22 抓到）；</li>
 *   <li>反过来（FORGE 总线事件挂在 MOD 总线类上）会<b>直接崩</b>：Forge 在
 *       {@code EventBus.registerListener} 抛
 *       {@code IllegalArgumentException: ... not a subtype of IModBusEvent}，整个 mod 加载失败。
 *       静态尺子在 {@code temp\bus-check\check-bus.mjs}，改事件相关代码后跑一遍。</li>
 * </ul>
 *
 * <p>真正的摘除逻辑仍在 {@link ShanhaiItemRemovalHooks#onBuildTabContentsImpl} 里，本类只做转发，
 * 保证"哪里算摘除"只有一个实现。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiItemRemovalModHooks {

    private ShanhaiItemRemovalModHooks() {}

    /** MOD 总线：创造栏构建时摘掉 hidden/removed 的物品（JEI 物品表就是从创造栏建的）。 */
    @SubscribeEvent
    public static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        try {
            ShanhaiItemRemovalHooks.onBuildTabContentsImpl(event);
        } catch (Throwable ignored) {
            // 创造栏构建失败会让整个物品表炸掉 —— 宁可这一条不隐藏
        }
    }
}
