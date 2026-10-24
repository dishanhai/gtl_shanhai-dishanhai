package com.shanhai.common.itemedit;

import com.shanhai.ShanhaiMod;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>「删除/抹除物品」的事件挂点（执行面 ①③④ ＋ 5 tick 扫）</b>。
 *
 * <h2>1. 每个面挂在哪、为什么是那里</h2>
 * <pre>
 *   ① JEI/创造栏隐藏 ⇒ Forge 的 BuildCreativeModeTabContentsEvent
 *      （每个创造栏构建时都会过它；把 hidden/removed 的 ItemStack 从 entries 里摘掉。
 *       🔴 JEI 的物品表就是从创造栏 displayItems 建的 —— 见 ShanhaiCreativeModeTabs 类注释里的取证，
 *       所以"创造栏没有 ⇒ JEI 也搜不到"，不需要注 JEI 内部类）
 *   ② 抹实例        ⇒ TickEvent.ServerTickEvent（每 5 tick 一次，低频；玩家/容器/掉落物）
 *   ④ 拾取即销毁    ⇒ EntityItemPickupEvent（捡起的那一刻拦掉并 discard；不需要每 tick 扫背包）
 * </pre>
 * ❌ 方块连带删：<b>本类一条都没有</b>（用户 2026-10-17 拍板拿掉：撤回不了）。
 *
 * <h2>2. 🔴 保守纪律</h2>
 * 每个入口整段 try/catch(Throwable) 自吞。表为空时（{@link ShanhaiItemOverrideTable#active()} 为假）
 * 三个入口都在第一句返回 ⇒ 没配删除时等于没挂钩。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiItemRemovalHooks {

    private static final String PREFIX = ShanhaiItemOverrideTable.PREFIX;

    /** 抹实例的扫描周期（tick）。用户规格：低频 5 tick。 */
    public static final int SCAN_PERIOD_TICKS = 5;

    private static int tickCounter;
    private static int lastWiped;
    private static int lastWipeTick = -1;

    private ShanhaiItemRemovalHooks() {}

    // ------------------------------------------------------------------ ① JEI / 创造栏

    /**
     * 创造栏构建时把 hidden/removed 的物品摘掉。
     *
     * <p>🔴 先收集再删：{@code MutableHashedLinkedMap} 在迭代中删除会打乱链表游标，
     * 所以遍历与删除分两拍（这也是 {@code remove()} 只接受 key 的原因）。
     */
    @SubscribeEvent
    public static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        try {
            ShanhaiItemOverrideTable.refresh();
            if (!ShanhaiItemOverrideTable.active()) {
                return;
            }
            final List<ItemStack> kill = new ArrayList<>();
            for (Map.Entry<ItemStack, CreativeModeTab.TabVisibility> en : event.getEntries()) {
                final ItemStack st = en.getKey();
                if (ShanhaiItemRemovalOps.shouldHide(st)) {
                    kill.add(st);
                }
            }
            for (ItemStack st : kill) {
                event.getEntries().remove(st);
            }
            if (!kill.isEmpty()) {
                ShanhaiMod.LOGGER.info("{} del_creative_filter tab={} removed={} ids={}",
                        PREFIX, event.getTabKey().location(),
                        kill.size(), preview(kill));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_creative_filter_failed err={} -> 本条创造栏保持原样",
                    PREFIX, t.toString());
        }
    }

    private static String preview(List<ItemStack> stacks) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < stacks.size() && i < 8; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stacks.get(i).getItem()));
        }
        if (stacks.size() > 8) {
            sb.append(", …");
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ ② 抹实例（5 tick）

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < SCAN_PERIOD_TICKS) {
            return;
        }
        tickCounter = 0;
        try {
            ShanhaiItemOverrideTable.refresh();
            if (!ShanhaiItemOverrideTable.active()) {
                return;
            }
            final net.minecraft.server.MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }
            final int n = ShanhaiItemRemovalOps.wipeInstances(server);
            if (n > 0) {
                lastWiped += n;
                lastWipeTick = server.getTickCount();
                ShanhaiMod.LOGGER.info("{} del_wipe tick={} wiped_now={} wiped_total={}",
                        PREFIX, server.getTickCount(), n, lastWiped);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_wipe_tick_failed err={}", PREFIX, t.toString());
        }
    }

    // ------------------------------------------------------------------ ④ 拾取即销毁

    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent event) {
        try {
            ShanhaiItemOverrideTable.refresh();
            if (!ShanhaiItemOverrideTable.active()) {
                return;
            }
            final net.minecraft.world.entity.item.ItemEntity drop = event.getItem();
            if (drop == null) {
                return;
            }
            final ItemStack st = drop.getItem();
            if (st.isEmpty() || !ShanhaiItemOverrideTable.isRemoved(st.getItem())) {
                return;
            }
            event.setCanceled(true);
            drop.discard();
            ShanhaiMod.LOGGER.info("{} del_pickup_destroy id={} count={} player={}",
                    PREFIX, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()),
                    st.getCount(),
                    event.getEntity() == null ? "?" : event.getEntity().getName().getString());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_pickup_guard_failed err={}", PREFIX, t.toString());
        }
    }

    // ------------------------------------------------------------------ 🆕 开机重放（配方就绪之后）

    /**
     * 服务端启动完成（{@code RecipeManager} 已就绪、数据包已加载）之后，把表里所有
     * {@code removed=true} 的条目重放一遍"摘配方产出 / 整条停用"。
     *
     * <p><b>为什么必须是这个事件</b>：{@code ServerStartedEvent} 是"配方表可用 + 世界已就绪"的最早
     * 稳定点（本工程 {@code ShanhaiJeiBridge.PlayerJoinHook#onServerStarted} 已经用它在同一拍做事，
     * 说明这个时机在本工程是验证过的）。
     *
     * <p>隐藏与抹实例不需要重放：它们每 tick / 每次建栏都读表。
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        try {
            final ShanhaiItemRemovalOps.ReplayOutcome ro =
                    ShanhaiItemRemovalOps.replayAll(event.getServer());
            if (!ro.ok()) {
                ShanhaiMod.LOGGER.error("{} del_replay_after_start ok=false entries={} stripped={} stopped={} "
                                + "（重启后功能可能只生效一部分 —— 看上面每条 ERROR）",
                        PREFIX, ro.entries(), ro.stripped(), ro.stopped());
            }
        } catch (Throwable t) {
            // 装错绝不许让服务端起不来：最坏情况是"重启后配方那两面没重放"
            ShanhaiMod.LOGGER.error("{} del_replay_hook_failed err={}", PREFIX, t.toString(), t);
        }
    }

    // ------------------------------------------------------------------ 读数（自检/日志）

    public static String statsLine() {
        return "scan_period=" + SCAN_PERIOD_TICKS + "t last_wiped=" + lastWiped
                + " last_wipe_tick=" + lastWipeTick
                + " suppressed=" + ShanhaiItemOverrideTable.suppressed().size()
                + " ledger=" + ShanhaiRemovedItemsLedger.size();
    }
}
