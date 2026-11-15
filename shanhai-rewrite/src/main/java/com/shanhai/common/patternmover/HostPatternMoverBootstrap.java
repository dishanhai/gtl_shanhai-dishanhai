package com.shanhai.common.patternmover;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 「主机样板搬运工具」的装配点（<b>两侧各跑一次</b>，在玩家进世界之前）。
 *
 * <h2>这里做三件事</h2>
 * <ol>
 *   <li>把 {@link HostPatternMoverLog} 的日志后端换成真的 {@code ShanhaiMod.LOGGER}
 *       （默认是 no-op，好让纯核在裸 JVM 里跑断言 —— 见那个类的注释）；</li>
 *   <li>把面板工厂注册进 LDLib 的 {@code UIFactory.FACTORIES}
 *       （客户端收到「开界面」包要靠它找工厂；开面板时也会再兜一次 {@code registerOnce()}）；</li>
 *   <li>跑一遍离线自证并把读数打进日志（{@code SELFCHECK 34/34 PASS=true}）——
 *       这条是<b>机器可判</b>的：装机之后 grep 一行就知道字节闸/去重/只读还在不在。</li>
 * </ol>
 *
 * <h2>为什么用 {@code @Mod.EventBusSubscriber} 而不是改主类</h2>
 * 与 {@code ShanhaiCreativeItemsBootstrap} / {@code ShanhaiRecipeEditorBootstrap} 同一条口径
 * （这个注解由 Forge 扫 jar 自动登记，不需要在 {@code ShanhaiMod} 里加一行）。
 * 又：{@code FMLCommonSetupEvent} 的回调<b>可能并发</b>，凡是要动全局静态表
 * （{@code UIFactory.FACTORIES}）的都 {@code enqueueWork} 排进主线程。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HostPatternMoverBootstrap {

    private HostPatternMoverBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                HostPatternMoverLog.install(new HostPatternMoverLog.Sink() {
                    @Override
                    public void info(String format, Object... args) {
                        ShanhaiMod.LOGGER.info(format, args);
                    }

                    @Override
                    public void warn(String format, Object... args) {
                        ShanhaiMod.LOGGER.warn(format, args);
                    }
                });
                HostPatternMoverFactory.registerOnce();
                HostPatternMoverSelfCheck.log();
                HostPatternMoverLog.info("{} bootstrap_done factories={} limit={}B panel={}x{}",
                        HostPatternMoverLog.PREFIX,
                        com.lowdragmc.lowdraglib.gui.factory.UIFactory.FACTORIES.size(),
                        HostPatternMoverData.LIMIT_BYTES,
                        HostPatternMoverPanel.W, HostPatternMoverPanel.H);
            } catch (Throwable t) {
                // 装配失败绝不能让服务端起不来：面板不可用，但物品与读取路径还能跑
                ShanhaiMod.LOGGER.error("{} bootstrap_failed err={}", HostPatternMoverLog.PREFIX,
                        t.toString(), t);
            }
        });
    }
}
