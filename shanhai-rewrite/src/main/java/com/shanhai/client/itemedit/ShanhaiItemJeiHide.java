package com.shanhai.client.itemedit;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.jei.ShanhaiJeiRuntime;
import com.shanhai.common.itemedit.ShanhaiItemOverrideTable;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 🔴 <b>「删除/抹除物品」的 JEI 物品列表那一半（客户端）</b>。
 *
 * <h2>1. 用户实测暴露的根因（我上一版的错）</h2>
 * 用户原话（2026-10-18）：「<b>抹除之后 jei 里面怎么还在</b>」。
 * 我上一版只做了 `BuildCreativeModeTabContentsEvent` ⇒ 那只影响<b>创造栏</b>；
 * <b>JEI 的 ingredient 列表是 JEI 自己启动时按创造栏建的一份私有副本</b>，之后不会自己重扫
 * ⇒ 创造栏没了、JEI 里还在。JEI 给了官方增量口，本类走的就是它：
 * <pre>
 *   IIngredientManager#removeIngredientsAtRuntime(IIngredientType, Collection)   ← 藏
 *   IIngredientManager#addIngredientsAtRuntime   (IIngredientType, Collection)   ← 撤回时加回
 * </pre>
 * 这两句与 {@code ShanhaiRealityClientRefresh}（128 个壳物品那条线）用的是<b>同一对 API</b>，
 * 不是新通道、也不注入 JEI 内部类。
 *
 * <h2>2. 为什么不需要网络通道</h2>
 * 单人存档下客户端与服务端<b>共用同一个</b> {@code config/shanhai/item_overrides.json}
 * ⇒ 客户端自己读表就够了（本类每 {@link #POLL_TICKS} tick 读一次，表的读侧本来就有 mtime 节流）。
 * 服务端/别的玩家不参与，也不该参与：JEI 只在客户端存在。
 *
 * <h2>3. 幂等怎么保证（不是"每次全量重发"）</h2>
 * 记一份 {@link #APPLIED}「我已经从 JEI 里拿掉了哪些 id」的台账，每拍只做<b>差集</b>：
 * <ul>
 *   <li>表里新变成 hidden/removed 的 id ⇒ 从 JEI 移除（并记进 APPLIED）；</li>
 *   <li>表里不再 hidden/removed、但 APPLIED 里有的 id ⇒ 加回 JEI（并从 APPLIED 删掉）。</li>
 * </ul>
 * ⇒ 连跑 N 拍不会重复移除/重复加回；{@code restore} 之后下一拍就会加回。
 *
 * <h2>4. ⚠️ 已知边界（如实写）</h2>
 * <ul>
 *   <li>JEI <b>还没就绪</b>（{@code IJeiRuntime} 为 null）时不动，下一拍再试 —— 不会漏，只是晚一点；</li>
 *   <li>JEI 的<b>收藏夹</b>里若已经收藏了这一格，JEI 自己的收藏表不归我们管（与既有的壳物品那条线同一边界）；</li>
 *   <li>**只处理物品**（{@code VanillaTypes.ITEM_STACK}）；流体/其它 ingredient 类型不涉及本功能；</li>
 *   <li>撤回到"加回 JEI"这一步时，如果那一格本来就<b>不在</b> JEI 里（比如它从来不在任何创造栏），
 *       加回会把它塞进 JEI。这是 {@code addIngredientsAtRuntime} 的固有语义，不是本类能分辨的。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiItemJeiHide {

    private static final String PREFIX = ShanhaiItemOverrideTable.PREFIX;

    /** 轮询周期（tick）。20 = 每秒一次，够快；表的读侧自带 mtime 节流，代价可忽略。 */
    public static final int POLL_TICKS = 20;

    /** 已经<b>从 JEI 里移除过</b>的 id（= 我们欠 JEI 的"该拿掉了"）。 */
    private static final Set<String> APPLIED = new LinkedHashSet<>();

    /** 最近一次成功拿到 {@link IJeiRuntime} 的那个对象（换了 = JEI 重建过 ⇒ APPLIED 作废）。 */
    private static volatile Object lastRuntime;

    private static int tickCounter;
    private static int totalRemoved;
    private static int totalRestored;
    private static String lastLine = "jei_hide_init（还没跑过）";

    private ShanhaiItemJeiHide() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < POLL_TICKS) {
            return;
        }
        tickCounter = 0;
        try {
            sync();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} jei_hide_sync_failed err={}（下一拍再试）", PREFIX, t.toString());
        }
    }

    /** 一拍：读表 → 算差集 → 对 JEI 移除/加回。 */
    private static void sync() {
        ShanhaiItemOverrideTable.refreshNow();
        final IJeiRuntime rt = ShanhaiJeiRuntime.runtime();
        if (rt == null) {
            return;                                  // JEI 没装 / 还没就绪
        }
        if (rt != lastRuntime) {
            // JEI 重建过（换世界 / 资源重载）⇒ 它那份列表是全新的，之前移除的不存在了 ⇒ 台账作废
            if (lastRuntime != null && !APPLIED.isEmpty()) {
                ShanhaiMod.LOGGER.info("{} jei_hide_runtime_changed -> 台账作废（JEI 重建过，之前移除的不在了）"
                        + " old_applied={}", PREFIX, APPLIED.size());
            }
            lastRuntime = rt;
            APPLIED.clear();
        }
        final IIngredientManager im;
        try {
            im = rt.getIngredientManager();
        } catch (Throwable t) {
            return;
        }

        // 目标集合：表里 hidden 或 removed 的 id
        final Set<String> want = new LinkedHashSet<>();
        for (ShanhaiItemOverrideTable.Entry e : ShanhaiItemOverrideTable.entries()) {
            if (e.hiddenEffective()) {
                want.add(e.id());
            }
        }

        final List<String> toRemove = new ArrayList<>();
        for (String id : want) {
            if (!APPLIED.contains(id)) {
                toRemove.add(id);
            }
        }
        final List<String> toRestore = new ArrayList<>();
        for (String id : APPLIED) {
            if (!want.contains(id)) {
                toRestore.add(id);
            }
        }
        if (toRemove.isEmpty() && toRestore.isEmpty()) {
            return;                                  // 稳态：一次 JEI API 都不调
        }

        int removed = 0;
        final List<String> removedIds = new ArrayList<>();
        for (String id : toRemove) {
            final ItemStack st = stackOf(id);
            if (st == null) {
                continue;
            }
            try {
                im.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, List.of(st));
                APPLIED.add(id);
                removed++;
                removedIds.add(id);
            } catch (Throwable t) {
                // 单条失败不拖累别的；下一拍还会再试（APPLIED 没加进去）
                ShanhaiMod.LOGGER.warn("{} jei_hide_remove_failed id={} err={}（下一拍再试）",
                        PREFIX, id, t.toString());
            }
        }
        int restored = 0;
        final List<String> restoredIds = new ArrayList<>();
        for (String id : toRestore) {
            final ItemStack st = stackOf(id);
            if (st == null) {
                APPLIED.remove(id);
                continue;
            }
            try {
                im.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, List.of(st));
                APPLIED.remove(id);
                restored++;
                restoredIds.add(id);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} jei_hide_restore_failed id={} err={}（下一拍再试）",
                        PREFIX, id, t.toString());
            }
        }

        totalRemoved += removed;
        totalRestored += restored;
        if (removed > 0) {
            lastLine = "jei_hide_removed=" + removed + " ids=" + removedIds
                    + " applied_total=" + APPLIED.size() + " all_removed_total=" + totalRemoved;
            ShanhaiMod.LOGGER.info("{} jei_hide_removed={} ids={} applied_total={}",
                    PREFIX, removed, removedIds, APPLIED.size());
        }
        if (restored > 0) {
            lastLine = "jei_hide_restored=" + restored + " ids=" + restoredIds
                    + " applied_total=" + APPLIED.size() + " all_restored_total=" + totalRestored;
            ShanhaiMod.LOGGER.info("{} jei_hide_restored={} ids={} applied_total={}",
                    PREFIX, restored, restoredIds, APPLIED.size());
        }
    }

    private static ItemStack stackOf(String id) {
        final ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return null;
        }
        final Item item = BuiltInRegistries.ITEM.get(rl);
        return item == null ? null : new ItemStack(item);
    }

    /** 一行读数（诊断用；日志里 grep {@code jei_hide_}）。 */
    public static String statsLine() {
        return "client_jei_hide applied=" + APPLIED.size() + " removed_total=" + totalRemoved
                + " restored_total=" + totalRestored + " last={" + lastLine + "}";
    }
}
