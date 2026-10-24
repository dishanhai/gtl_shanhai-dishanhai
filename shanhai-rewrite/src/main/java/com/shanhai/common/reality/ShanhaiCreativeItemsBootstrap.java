package com.shanhai.common.reality;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 「自己新增物品」的装配点（<b>两侧各跑一次</b>，在玩家进任何世界之前）。
 *
 * <h2>这里做四件事</h2>
 * <ol>
 *   <li>把表从 {@code config/shanhai/creative_items.json} 读进内存（没有文件 = 128 格全空，不报错）；</li>
 *   <li>把面板工厂注册进 LDLib 的 {@code UIFactory.FACTORIES}（客户端收到"开界面"包要靠它找工厂）；</li>
 *   <li>建那条网络通道（保存 / 整表同步）；</li>
 *   <li>打两条机器可判的读数：{@code reality_table_selftest N/N PASS=…} 与 {@code statsLine()}。</li>
 * </ol>
 *
 * <h2>为什么不用 {@code @Mod.EventBusSubscriber} 的静态内部类</h2>
 * 与既有 {@code ShanhaiRecipeEditorBootstrap} 同一条口径：{@code FMLCommonSetupEvent} 的回调可能并发，
 * 凡是要动全局静态表（{@code UIFactory.FACTORIES}）的都 {@code enqueueWork} 排进主线程。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiCreativeItemsBootstrap {

    private ShanhaiCreativeItemsBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                ShanhaiRealityItemTable.ensureLoaded();
                ShanhaiCreativeItemsFactory.registerOnce();
                ShanhaiCreativeItemsNetwork.register();
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiRealityItemTable.PREFIX,
                        ShanhaiRealityItemTable.selfCheck());
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiRealityItemTable.PREFIX,
                        ShanhaiRealityItemTable.statsLine());
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiRealityItemTable.PREFIX,
                        ShanhaiCreativeItemsPanel.layoutLine());
                // 🆕 2026-10-17：三页签容器（新增 / 修改 / 删除）的版式读数
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiRealityItemTable.PREFIX,
                        com.shanhai.common.reality.ShanhaiItemStudioPanel.layoutLine());
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiRealityItemTable.PREFIX,
                        ShanhaiCreativeIconPicker.layoutLine());
                ShanhaiMod.LOGGER.info("{} bootstrap_done factories={} channel_ready={} "
                                + "items_registered={}",
                        ShanhaiRealityItemTable.PREFIX,
                        com.lowdragmc.lowdraglib.gui.factory.UIFactory.FACTORIES.size(),
                        ShanhaiCreativeItemsNetwork.isReady(),
                        com.shanhai.item.ShanhaiItems.REALITY_ITEMS.length);
                logRegistryCount();
            } catch (Throwable t) {
                // 装配失败绝不能让服务端起不来：面板只是不可用，机器与配方还能跑
                ShanhaiMod.LOGGER.error("{} bootstrap_failed err={}", ShanhaiRealityItemTable.PREFIX,
                        t.toString(), t);
            }
        });
    }

    /**
     * 🔴 <b>机器可判的一条硬读数：128 个壳是不是真的都进注册表了。</b>
     *
     * <p>为什么必须有这一条：整个功能的地基是"128 个物品在加载期注册成功"。
     * 注册漏一个的话，其它一切（面板、图标、JEI）看起来都正常，只有那一个槽位永远不出现 ——
     * 而那是<b>最难靠肉眼发现</b>的失败形态（用户只会觉得"1 号能改名，7 号改了没反应"）。
     * ⇒ 开机就把 {@code BuiltInRegistries.ITEM} 里的真实条数数出来，<b>不是 128 就报 ERROR</b>。
     */
    private static void logRegistryCount() {
        int found = 0;
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < ShanhaiRealityItemTable.SLOT_COUNT; i++) {
            final net.minecraft.resources.ResourceLocation id =
                    new net.minecraft.resources.ResourceLocation("shanhai", "reality_item_" + (i + 1));
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                found++;
            } else if (missing.length() < 200) {
                missing.append(i + 1).append(',');
            }
        }
        final int want = ShanhaiRealityItemTable.SLOT_COUNT;
        if (found == want) {
            ShanhaiMod.LOGGER.info("{} items_registered_in_registry={}/{} PASS=true",
                    ShanhaiRealityItemTable.PREFIX, found, want);
        } else {
            ShanhaiMod.LOGGER.error("{} items_registered_in_registry={}/{} PASS=false missing_slots=[{}]",
                    ShanhaiRealityItemTable.PREFIX, found, want, missing);
        }
    }
}
