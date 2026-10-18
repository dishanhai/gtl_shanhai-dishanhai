package com.shanhai.common.itemedit;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.lang.reflect.Method;

/**
 * 「改写任意已存在物品」的装配点（<b>两侧各跑一次</b>，在玩家进任何世界之前）。
 *
 * <h2>这里做三件事</h2>
 * <ol>
 *   <li>把表 {@code config/shanhai/item_overrides.json} 读进内存（没有文件 = 空表，不报错）；</li>
 *   <li>打两条<b>机器可判</b>的读数：{@code item_override_selftest N/N PASS=…} 与 {@code statsLine()}；</li>
 *   <li>打一条<b>「mixin 到底挂上没有」的实证</b>（{@link #armedLine()}）——
 *       反射看目标类里有没有我们那个 {@code shanhai$…} 回调方法。
 *       这一条很关键：mixin 的 {@code require=1} 只在<b>注入失败时炸</b>，
 *       它<b>不会</b>告诉你"注入成功且逻辑对"；而"看着像挂上了、其实没挂"是本工程最怕的失败形态。</li>
 * </ol>
 *
 * <h2>为什么用 {@code enqueueWork}</h2>
 * 与 {@code ShanhaiCreativeItemsBootstrap} / {@code ShanhaiRecipeEditorBootstrap} 同一口径：
 * {@code FMLCommonSetupEvent} 的回调可能并发，凡是动全局静态表的一律排进主线程。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiItemOverrideBootstrap {

    private static final String PREFIX = ShanhaiItemOverrideTable.PREFIX;

    /** 目标类里应当出现的回调方法名片段（mixin 可能给它加 {@code handler$xxx$} 前缀，所以只匹配片段）。 */
    private static final String MARKER = "shanhai$";

    private ShanhaiItemOverrideBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                ShanhaiItemOverrideTable.ensureLoaded();
                ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiItemOverrideTable.selfCheck());
                ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiItemOverrideTable.statsLine());
                // 🆕 删除/抹除：台账（撤回证据）+ 事件挂点读数
                ShanhaiRemovedItemsLedger.reloadFromDisk();
                ShanhaiMod.LOGGER.info("{} del_bootstrap {} entries_hidden_or_removed={}",
                        PREFIX, ShanhaiItemRemovalHooks.statsLine(),
                        ShanhaiItemOverrideTable.suppressed().size());
                final String armed = armedLine();
                if (armed.contains("=false")) {
                    ShanhaiMod.LOGGER.error("{} hooks_armed {} （期望全是 true）", PREFIX, armed);
                } else {
                    ShanhaiMod.LOGGER.info("{} hooks_armed {}", PREFIX, armed);
                }
            } catch (Throwable t) {
                // 装错绝不许让服务端起不来：最坏情况就是"覆盖不生效"，机器和配方照旧
                ShanhaiMod.LOGGER.error("{} bootstrap_failed err={}", PREFIX, t.toString(), t);
            }
        });
    }

    /**
     * 反射证明四条注入真的落在目标类里。
     *
     * <p>判据：{@code ItemStack} 里应当有 3 个、{@code Item} 里应当有 1 个名字含 {@code shanhai$} 的方法。
     * 这是**唯一**一条"挂了没有"的机器实证（日志可 grep {@code hooks_armed}）。
     */
    public static String armedLine() {
        final int[] stack = count(net.minecraft.world.item.ItemStack.class);
        final int[] item = count(net.minecraft.world.item.Item.class);
        final boolean stackOk = stack[0] >= 3;
        final boolean itemOk = item[0] >= 1;
        return "ItemStack=" + stackOk + "(found=" + stack[0] + "/want>=3)"
                + " Item=" + itemOk + "(found=" + item[0] + "/want>=1)";
    }

    /** @return {@code [命中数, 该类的声明方法总数]} */
    private static int[] count(Class<?> target) {
        int hit = 0;
        int total = 0;
        try {
            for (Method m : target.getDeclaredMethods()) {
                total++;
                if (m.getName().contains(MARKER)) {
                    hit++;
                }
            }
        } catch (Throwable ignored) {
            // 反射被挡 ⇒ 当作没挂上（宁可报红的）
        }
        return new int[]{hit, total};
    }
}
