package com.shanhai.common.reality;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 「65 件事件物品的登记面板」的装配点（<b>两侧各跑一次</b>，在玩家进任何世界之前）。
 *
 * <h2>这里做四件事</h2>
 * <ol>
 *   <li>把登记簿从 {@code config/shanhai/mc_bottom_inputs.json} 读进内存（没有文件 = 空表，不报错）；</li>
 *   <li>把面板工厂注册进 LDLib 的 {@code UIFactory.FACTORIES}（客户端收到「开界面」包要靠它找工厂）；</li>
 *   <li>打两行<b>机器可判</b>的读数：{@code selfcheck 4/4 PASS=true wired=5 entries=N …}
 *       与 {@code panel 320x136 …}（版式）。</li>
 *   <li>顺手打一行"已接线 / 未接线"的条数 —— 判据 {@code wired=5} / {@code not_wired=60}。</li>
 * </ol>
 *
 * <h2>为什么用 {@code @Mod.EventBusSubscriber} 而不是改主类</h2>
 * 与 {@code ShanhaiCreativeItemsBootstrap} / {@code HostPatternMoverBootstrap} 同一条口径
 * （这个注解由 Forge 扫 jar 自动登记，不需要在 {@code ShanhaiMod} 里加一行）。
 * 又：{@code FMLCommonSetupEvent} 的回调<b>可能并发</b>，凡是要动全局静态表
 * （{@code UIFactory.FACTORIES}）的都 {@code enqueueWork} 排进主线程。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiRealityInputBootstrap {

    /** 面板服务的一批物品数（65 件 {@code mc_*}；判据用）。 */
    public static final int PANEL_ITEM_COUNT = 65;

    private ShanhaiRealityInputBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                ShanhaiRealityInputStore.ensureLoaded();
                ShanhaiRealityInputFactory.registerOnce();
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiRealityInputStore.PREFIX,
                        ShanhaiRealityInputStore.selfCheck());
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiRealityInputStore.PREFIX,
                        ShanhaiRealityInputPanel.layoutLine());
                ShanhaiMod.LOGGER.info("{} bootstrap_done factories={} wired={} not_wired={} items={} ids={}",
                        ShanhaiRealityInputStore.PREFIX,
                        com.lowdragmc.lowdraglib.gui.factory.UIFactory.FACTORIES.size(),
                        ShanhaiRealityInputStore.wiredCount(),
                        PANEL_ITEM_COUNT - ShanhaiRealityInputStore.wiredCount(),
                        PANEL_ITEM_COUNT,
                        ShanhaiRealityInputStore.wiredList());
            } catch (Throwable t) {
                // 装配失败绝不能让服务端起不来：面板不可用，但 65 件物品与登记文件都还能用
                ShanhaiMod.LOGGER.error("{} bootstrap_failed err={}", ShanhaiRealityInputStore.PREFIX,
                        t.toString(), t);
            }
        });
    }
}
