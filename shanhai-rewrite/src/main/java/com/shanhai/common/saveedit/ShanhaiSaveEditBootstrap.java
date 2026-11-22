package com.shanhai.common.saveedit;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 「存档直改」的装配点（<b>两侧各跑一次</b>）。
 *
 * <h2>做三件事</h2>
 * <ol>
 *   <li>把面板工厂注册进 LDLib 的 {@code UIFactory.FACTORIES}（客户端收到"开界面"包要靠它找工厂）；</li>
 *   <li>打两条机器可判的读数：窗口真值表（{@code window_true_for=1/10}）与版式；</li>
 *   <li>把关键常量折进日志，方便事后对"我改的是哪个字段"。</li>
 * </ol>
 *
 * <h2>为什么 {@code enqueueWork}</h2>
 * 与既有 {@code ShanhaiCreativeItemsBootstrap} 同一条口径：{@code FMLCommonSetupEvent} 的回调可能并发，
 * 凡是要动全局静态表（{@code UIFactory.FACTORIES}）的都排进主线程。
 *
 * <h2>🔴 装配失败不许把游戏弄挂</h2>
 * 整个功能只是"一个面板"，挂了它，世界照样能进、能玩。所以这里把异常全吞进日志。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiSaveEditBootstrap {

    private ShanhaiSaveEditBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                ShanhaiSaveEditFactory.registerOnce();
                int admit = 0;
                for (ShanhaiSaveEditFlow.Phase p : ShanhaiSaveEditFlow.Phase.values()) {
                    if (ShanhaiSaveEditFlow.windowOpen(p)) {
                        admit++;
                    }
                }
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX,
                        "window_true_for=" + admit + "/" + ShanhaiSaveEditFlow.Phase.values().length
                                + " (must be 1/, and it must be STOPPED)");
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX, ShanhaiSaveEditLayout.layoutLine());
                // 第四期：维度映射表（10 条样例逐条进日志 —— 它就是判据 c9 的那张表）
                for (String row : ShanhaiSaveEditDimension.mappingRows()) {
                    ShanhaiMod.LOGGER.info("{} dim_map {}", ShanhaiSaveEditFlow.PREFIX, row);
                }
                ShanhaiMod.LOGGER.info("{} bootstrap_done pages={} page1_fields=12 page2_fields={} "
                                + "page3_fields=7 page4_fields=5 page5_fields=1 page6_fields=3 "
                                + "factories={} main_slots={} armor_slots={} armor_base={} "
                                + "offhand_slot={}",
                        ShanhaiSaveEditFlow.PREFIX,
                        ShanhaiSaveEditLayout.PAGE_COUNT,
                        ShanhaiLevelDataSpec.FIELDS.size(),
                        com.lowdragmc.lowdraglib.gui.factory.UIFactory.FACTORIES.size(),
                        ShanhaiPlayerDataEdit.MAIN_SLOTS,
                        ShanhaiPlayerDataEdit.ARMOR_SLOTS,
                        ShanhaiPlayerDataEdit.ARMOR_BASE,
                        ShanhaiPlayerDataEdit.OFFHAND_SLOT);
                ShanhaiMod.LOGGER.info("{} fourth_phase pages=[{}] lines_per_page={} sub_dirs=[{} {} {}]",
                        ShanhaiSaveEditFlow.PREFIX, String.join(", ", ShanhaiSaveEditLayout.tabNames()),
                        ShanhaiSaveEditFactory.Holder.LINES,
                        ShanhaiSaveEditDimension.REGION_DIR, ShanhaiSaveEditDimension.ENTITIES_DIR,
                        ShanhaiSaveEditDimension.DIMENSIONS_DIR);
                // 第 ⑦ 页（存档体检 · 白名单之外一律算异常）的版式读数 —— 这一行就是"面板页真的接上了"的判据
                ShanhaiMod.LOGGER.info("{} fifth_phase audit_page widgets={} rows={}x{}@{} h={} "
                                + "filter_y={} summary_y={} pager_y={}/{} sel_y={} "
                                + "btns=[rescan({},{}) drop({},{})] slots={} tabs={} tab_w={}",
                        ShanhaiSaveEditFlow.PREFIX,
                        ShanhaiSaveEditLayout.pageWidgetCount(ShanhaiSaveEditLayout.PAGE_AUDIT),
                        ShanhaiSaveEditLayout.AU_ROWS, ShanhaiSaveEditLayout.AU_ROW_STEP,
                        ShanhaiSaveEditLayout.AU_ROW_Y0, ShanhaiSaveEditLayout.AU_ROW_H,
                        ShanhaiSaveEditLayout.AU_FILTER_Y, ShanhaiSaveEditLayout.AU_SUMMARY_Y,
                        ShanhaiSaveEditLayout.AU_PAGER_Y, ShanhaiSaveEditLayout.AU_PAGER_H,
                        ShanhaiSaveEditLayout.AU_SEL_Y,
                        ShanhaiSaveEditLayout.AU_RESCAN_X, ShanhaiSaveEditLayout.AU_RESCAN_W,
                        ShanhaiSaveEditLayout.AU_DROP_X, ShanhaiSaveEditLayout.AU_DROP_W,
                        ShanhaiSaveEditLayout.auditSlots().size(),
                        ShanhaiSaveEditLayout.PAGE_COUNT,
                        ShanhaiSaveEditLayout.TAB_W);
                if (!ShanhaiSaveEditLayout.layoutProblems().isEmpty()) {
                    // 版式自检不通过 = "一定会画坏"的预警：进日志，但不许因为它把功能弄挂
                    ShanhaiMod.LOGGER.error("{} layout_problems={}", ShanhaiSaveEditFlow.PREFIX,
                            ShanhaiSaveEditLayout.layoutProblems());
                }
                // 二期每个字段"为什么能活过重开"逐条进日志（事后对账用）
                for (ShanhaiLevelDataSpec.Field f : ShanhaiLevelDataSpec.FIELDS) {
                    ShanhaiMod.LOGGER.info("{} level_field id={} kind={} range={} why={}",
                            ShanhaiSaveEditFlow.PREFIX, f.id(), f.kind(), f.rangeText(), f.why());
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} bootstrap_failed err={}", ShanhaiSaveEditFlow.PREFIX,
                        t.toString(), t);
            }
        });
    }
}
