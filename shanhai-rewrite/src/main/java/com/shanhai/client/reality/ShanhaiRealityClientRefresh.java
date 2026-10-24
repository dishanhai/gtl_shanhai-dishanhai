package com.shanhai.client.reality;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.jei.ShanhaiJeiGuiRefresh;
import com.shanhai.client.jei.ShanhaiJeiRuntime;
import com.shanhai.common.reality.ShanhaiRealityItemTable;
import com.shanhai.item.ShanhaiCreativeModeTabs;
import com.shanhai.item.ShanhaiItems;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 🔴 <b>「改了表 ⇒ 界面立刻看得见」的客户端那一半</b>（创造栏 + JEI）。
 *
 * <h2>为什么需要它（"表变了"和"界面变了"是两件事）</h2>
 * <ol>
 *   <li><b>创造栏</b>：内容在 {@code CreativeModeTab#displayItems} 里<b>缓存</b>，并且
 *       {@code CreativeModeTabs.tryRebuildTabContents(...)} 在"参数没变"时<b>直接返回 false</b>
 *       （{@code javap -c} 证据见报告）⇒ 只能显式调 {@code CreativeModeTab#buildContents(...)}
 *       （public 且无缓存判断）。这一句在 {@link ShanhaiCreativeModeTabs#refreshContents()}。</li>
 *   <li><b>JEI</b>：物品表是 JEI 启动时按创造栏建的，之后<b>不会自己重扫</b>。
 *       JEI 给了官方增量口：{@code IIngredientManager#addIngredientsAtRuntime /
 *       removeIngredientsAtRuntime}（{@code javap} 证据见报告）。本类按
 *       "每个槽位的签名（名字/图标/堆叠/稀有度/提示）"做增量：
 *       变了就 <b>先 remove 再 add</b> ⇒ JEI 的搜索索引也跟着重建（<b>改名之后按新名字也搜得到</b>）。</li>
 * </ol>
 *
 * <h2>为什么放在客户端 tick 上，而不是直接在那条网络消息里干</h2>
 * 那条 S2C 消息的处理是 {@code enqueueWork}（主线程），但此刻可能正处在
 * "界面/资源"的某个中间态（例如 JEI 正在算布局）。推迟到<b>下一个客户端 tick</b> 做，
 * 与既有 {@code ShanhaiJeiGuiRefresh} 的写法一致（它也是 tick 驱动）。
 *
 * <h2>⚠️ 已知边界（如实写）</h2>
 * <ul>
 *   <li>创造栏那一屏如果<b>正开着</b>，它已经播过的那一页不会自己重排（只在下次打开/切页时取新表）；
 *       我们的面板开着的时候创造栏不可能同时开着 ⇒ 正常路径不受影响。</li>
 *   <li>JEI 的 {@code remove+add} 对"改名"有效，但 JEI <b>收藏夹</b>里若已经收藏了那一格，
 *       收藏项是旧名字（JEI 自己的收藏表不归我们管）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiRealityClientRefresh {

    private static final String PREFIX = "[SHANHAI-REALITY]";

    /** 有一拍待刷新（由 S2C 同步消息 / 保存回执置位）。 */
    private static volatile boolean pending;

    /** 最近一次刷新的原因（日志用）。 */
    private static volatile String pendingWhy = "";

    /** 已经同步进 JEI 的"签名"（槽位 → 签名）。基线在客户端 setup 时抓。 */
    private static final Map<Integer, String> JEI_SIG = new HashMap<>();

    /** 基线抓过没有。 */
    private static boolean baselineDone;

    /** 读数（排查用）。 */
    private static int refreshes;
    private static int jeiAdded;
    private static int jeiRemoved;

    private ShanhaiRealityClientRefresh() {}

    /** 由 S2C 同步消息调（客户端主线程）。 */
    public static void markDirty(String why) {
        pendingWhy = why == null ? "" : why;
        pending = true;
    }

    /**
     * 抓"启动时 JEI 里已经有什么"的基线。
     *
     * <p>为什么必须有：JEI 启动时是按创造栏建表的 —— 那一刻<b>已经定义</b>的槽位早就在 JEI 里了。
     * 不抓基线的话，第一次保存会把它们再 {@code add} 一次（JEI 会报"已存在"）或者把它们误 remove。
     */
    public static void captureBaseline() {
        if (baselineDone) {
            return;
        }
        baselineDone = true;
        JEI_SIG.clear();
        for (int i = 0; i < ShanhaiRealityItemTable.SLOT_COUNT; i++) {
            if (ShanhaiRealityItemTable.isDefined(i)) {
                JEI_SIG.put(i, ShanhaiRealityItemTable.entry(i).signature());
            }
        }
        ShanhaiMod.LOGGER.info("{} jei_baseline_captured defined={}（JEI 启动时已经有的那批）",
                PREFIX, JEI_SIG.size());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!pending) {
            return;
        }
        pending = false;
        final String why = pendingWhy;
        captureBaseline();
        boolean tab = false;
        try {
            tab = ShanhaiCreativeModeTabs.refreshContents();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} creative_tab_refresh_threw err={}", PREFIX, t.toString(), t);
        }
        final int[] jei = syncJei();
        refreshes++;
        ShanhaiMod.LOGGER.info("{} refresh why={} tab_rebuilt={} jei_added={} jei_removed={} "
                        + "defined={} version={} total_refreshes={}",
                PREFIX, why, tab, jei[0], jei[1],
                ShanhaiRealityItemTable.definedCount(), ShanhaiRealityItemTable.version(), refreshes);
        if (jei[0] + jei[1] > 0) {
            // 让正开着的 JEI 界面重算一次（既有设施；没开着就留成待办）
            try {
                ShanhaiJeiGuiRefresh.notifyPatched();
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} jei_gui_notify_failed err={}", PREFIX, t.toString());
            }
        }
    }

    /** 把"新定义的 / 变了签名的 / 被清空的"增量同步进 JEI。返回 {@code [added, removed]}。 */
    private static int[] syncJei() {
        final IJeiRuntime rt = ShanhaiJeiRuntime.runtime();
        if (rt == null) {
            return new int[]{0, 0};   // 没装 JEI / 还没就绪 ⇒ 什么都不用做（它启动时自然会读创造栏）
        }
        final IIngredientManager im;
        try {
            im = rt.getIngredientManager();
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
        final List<ItemStack> add = new ArrayList<>();
        final List<ItemStack> remove = new ArrayList<>();
        for (int i = 0; i < ShanhaiRealityItemTable.SLOT_COUNT; i++) {
            final String now = ShanhaiRealityItemTable.isDefined(i)
                    ? ShanhaiRealityItemTable.entry(i).signature() : null;
            final String old = JEI_SIG.get(i);
            if (Objects.equals(now, old)) {
                continue;
            }
            final Item item = ShanhaiItems.realityItemValue(i);
            if (item == null) {
                continue;
            }
            if (old != null) {
                remove.add(new ItemStack(item));
            }
            if (now != null) {
                add.add(new ItemStack(item));
            }
            if (now == null) {
                JEI_SIG.remove(i);
            } else {
                JEI_SIG.put(i, now);
            }
        }
        if (!remove.isEmpty()) {
            try {
                im.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, remove);
                jeiRemoved += remove.size();
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} jei_remove_failed count={} err={}（界面要等重开一次才准）",
                        PREFIX, remove.size(), t.toString());
            }
        }
        if (!add.isEmpty()) {
            try {
                im.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, add);
                jeiAdded += add.size();
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} jei_add_failed count={} err={}（界面要等重开一次才准）",
                        PREFIX, add.size(), t.toString());
            }
        }
        return new int[]{add.size(), remove.size()};
    }

    /** 一行读数（开机日志用）。 */
    public static String statsLine() {
        return "client_refresh refreshes=" + refreshes + " jei_added=" + jeiAdded
                + " jei_removed=" + jeiRemoved + " baseline=" + JEI_SIG.size()
                + " pending=" + pending;
    }
}
