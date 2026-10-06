package com.shanhai.client.jei;

import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🔴🔴 <b>2026-10-05（用户实测三条规律：改能刷、插不刷、删不刷）：让【开着的 JEI 界面】重算一次。</b>
 *
 * <h2>为什么非要有这一层（不是猜的，是字节码取证过的）</h2>
 * 我们的补丁挂在 {@code PluginManager#getRecipes(...)} 的 RETURN
 * （见 {@code ShanhaiJeiRecipeOrderMixin}）—— <b>JEI 每取一次这份列表，我们就能给它正确的答案</b> ✓。
 * 但"取"只发生在<b>它算布局的那一刻</b>（开界面 / 翻页 / 换分类 / 换搜索词）。
 * <pre>
 * javap -c mezz.jei.library.recipes.RecipeManagerInternal 实测：
 *   hideRecipes / addRecipes 里【一个 notify / Listener 调用都没有】（两词出现 0 次）
 * ⇒ JEI 官方那两个 API 只改它内部那张表，<b>根本不会通知界面重画</b> ✓
 * ⇒ 所以"新增不刷"的最后一段不是数据问题，而是<b>界面不知道自己该重算</b>。
 * </pre>
 *
 * <h2>用法：JEI 自己给自己的那个口子（⚠️ 注释已按字节码复核更正）</h2>
 * {@code javap -p mezz.jei.gui.recipes.RecipesGui} 实测它自己声明了：
 * <pre>
 *   private void reopenIfOpen();   ←  {@code isOpen() → setScreen(parentScreen); open();}
 *                                     ★ 它<b>不是</b>"JEI 检测到配方变了就调它":
 *                                       javap 复核：它的调用方是【居中搜索栏】与【界面最大高度】
 *                                       两个配置项监听器，<b>与配方表变化毫无关系</b>。
 *                                       （这里原来写着"JEI 自己就是这么用的"—— 那句是错的，
 *                                        已按"引用必须能落到字节码"的纪律改掉；功能照样能用，
 *                                        但**不许再拿它当"官方设计的刷新口"来评估风险**。）
 *                                     它是<b>唯一能逼 JEI 重建布局的公开可达入口</b>（会重新算配方页），
 *                                     ⚠️ 已知副作用：<b>页码必重置</b>。
 *   public  void m_86600_();       ←  tick() 覆盖（每帧都跑）
 * </pre>
 * ⇒ 我们在客户端 tick 上，<b>只在"这一拍有新补丁 + JEI 界面正开着 + 刚刚才发生"</b>三条同时成立时，
 * 反射调它一次。整条路：<b>不动 JEI 内部表、不 reload、不整机重注册</b> ✓。
 * <p>⚠️ 备选（未验证，留档）：{@code RecipesGui#updateLayout()}（private，{@code :1139}）——
 * 若将来嫌"页码重置"讨厌，可以试它；本机没有客户端，没验过，不许当结论用。
 *
 * <h2>判据（客户端 logs\latest.log）</h2>
 * <pre>
 * [SHANHAI-JEIREFRESH] jei_gui_reopened version=3 opened=1 opened_total=2
 *                     （补丁到了 ⇒ 让开着的 JEI 界面自己重算一次；不 reload）
 * [SHANHAI-JEIREFRESH] jei_gui_refresh_skipped reason=no_jei_screen version=3
 *                     （JEI 界面没开着 ⇒ 不需要做任何事：它下次自己会取到新数据）
 * </pre>
 * <b>打不出来 = 这一层没被触发</b>；打出来 {@code opened=0} 而界面没变 = 反射调不动（把这一行发我）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiJeiGuiRefresh {

    private static final String PREFIX = "[SHANHAI-JEIREFRESH]";

    /** 补丁版本号：每收到一条补丁 +1。 */
    private static final AtomicInteger VERSION = new AtomicInteger();

    /** 上一次"已经处理过"的版本（处理 = 界面开着就重算，没开着就什么都不做）。 */
    private static int handledVersion;

    /** 最近一次上补丁的 tick（客户端 tick 计数）——只在"刚刚才发生"时才去动界面。 */
    private static long lastPatchTick = Long.MIN_VALUE;

    /** 自己数的客户端 tick（与 ShanhaiRecipeStats 同一写法：不依赖任何需要猜语义的延迟机制）。 */
    private static long clientTicks;

    private static int openedTotal;
    private static int skippedNoScreen;

    /** {@code RecipesGui#reopenIfOpen()}（私有，缓存反射结果）。 */
    private static Method reopenIfOpen;

    /** 🔴 防重入（P0 那次无限递归的同一个教训）：重建过程中不许再触发重建。 */
    private static final ThreadLocal<Boolean> REBUILDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 这一版补丁已经重建过几次。 */
    private static int rebuildsThisVersion;

    /** 每版补丁最多重建几次（兜底硬上限，绝不会每帧重建）。 */
    private static final int MAX_REBUILDS_PER_VERSION = 3;

    /** 重入被拦下的次数（读数）。 */
    private static int reentryBlocked;

    /** 上一次"因为 JEI 配方页没开着而记过日志"的版本号（只记一次，不刷屏；**不消耗版本号**）。 */
    private static int skippedLoggedVersion = -1;

    // ── JEI 那一页的布局缓存（反射句柄，清一次就够）──
    private static java.lang.reflect.Field fLogic;
    private static java.lang.reflect.Field fCachedCategory;
    private static java.lang.reflect.Field fCachedLayouts;
    private static java.lang.reflect.Field fCachedContainer;
    private static int cacheClearedTotal;

    /**
     * 🔴 <b>清掉 JEI 那一页的布局缓存</b>（{@code RecipeGuiLogic#cachedRecipeLayoutsWithButtons} 等三个字段）。
     *
     * @return true = 清成功了（JEI 下一帧必须重算这一页）；false = 字段找不到/拿不到 ⇒ 调用方退回"关掉再打开"
     */
    private static boolean clearJeiLayoutCache(Object recipesGui) {
        try {
            if (fLogic == null) {
                fLogic = recipesGui.getClass().getDeclaredField("logic");
                fLogic.setAccessible(true);
            }
            final Object logic = fLogic.get(recipesGui);
            if (logic == null) {
                return false;
            }
            final Class<?> lc = logic.getClass();
            if (fCachedCategory == null) {
                fCachedCategory = lc.getDeclaredField("cachedRecipeCategory");
                fCachedCategory.setAccessible(true);
            }
            if (fCachedLayouts == null) {
                fCachedLayouts = lc.getDeclaredField("cachedRecipeLayoutsWithButtons");
                fCachedLayouts.setAccessible(true);
            }
            if (fCachedContainer == null) {
                fCachedContainer = lc.getDeclaredField("cachedContainerId");
                fCachedContainer.setAccessible(true);
            }
            fCachedCategory.set(logic, null);
            fCachedLayouts.set(logic, null);
            fCachedContainer.set(logic, -1);
            cacheClearedTotal++;
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} jei_layout_cache_clear_failed err={} "
                            + "（字段名对不上 ⇒ 退回「关掉再打开」那条路；把这一行发我）", PREFIX, t.toString());
            return false;
        }
    }

    private ShanhaiJeiGuiRefresh() {
    }

    /** 收到一条补丁时调（客户端主线程）。 */
    public static void notifyPatched() {
        VERSION.incrementAndGet();
        lastPatchTick = clientTicks;
        rebuildsThisVersion = 0;   // 🔴 新的一版 ⇒ 允许重建（否则上一版用完上限就永远不重建）
    }

    /** 读数（排查用）。 */
    public static String statsLine() {
        return "version=" + VERSION.get() + " handled=" + handledVersion
                + " opened=" + openedTotal + " skipped_no_screen=" + skippedNoScreen;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTicks++;
        final int v = VERSION.get();
        if (v == 0 || v == handledVersion) {
            return;
        }
        // 🔴🔴 2026-10-06（用户给的"一刀切开"读数落地之后）：**去掉那个"只在两秒内"的窗口**。
        //    为什么原来加它：怕"拖到很久以后再动界面会把用户正在翻的页顶掉"。
        //    为什么现在必须去掉（现场事实）：
        //      · 用户在**配方编辑器那个屏**上新建/删除/恢复 ⇒ 那一刻 JEI 的配方页**不是当前屏**
        //        （JEI 在编辑器里只是右侧那个物品overlay）⇒ 原写法把这一版标记成"已处理" ⇒
        //        **等用户真去打开 JEI 配方页时，我们什么都不会再做** ✗
        //      · 而"已经开着的那一页 + 补丁到了"这种情况，恰恰是最需要重建的 ⇒ 不能被时间窗挡掉。
        //    ⇒ 新口径：**每一版补丁，只要当前屏是 JEI 配方页，就重建一次**（版本号保证只做一次，
        //      不会每帧重建；重建次数另有硬上限兜底）。
        if (rebuildsThisVersion >= MAX_REBUILDS_PER_VERSION) {
            handledVersion = v;
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Screen screen = mc == null ? null : mc.screen;
        if (!(screen instanceof mezz.jei.gui.recipes.RecipesGui)) {
            // 🔴🔴 2026-10-06（用户现场读数：10 个版本**全部** `jei_gui_refresh_skipped reason=no_jei_screen`
            //    ⇒ `jei_layout_cache_cleared` 一次都没出现）：**跳过【不许消耗版本号】！**
            //    原写法在这里就 `handledVersion = v` ⇒ 用户在**配方编辑器那个屏**上新建/删除时，
            //    JEI 配方页确实没开着 ⇒ 每个版本都被"吃掉" ⇒ **等他按 R 打开 JEI 时版本号已不再涨
            //    ⇒ 没有任何待刷新** ⇒ 界面层永远不动 ✗✗
            //    这就是"我把缓存清理写好了却一次都没执行"的原因 ✓
            //    ⇒ 新口径：**留成待办**（没开着就等着，等他真打开那一帧再清缓存）；
            //      日志只在这一版第一次跳过时打一条（不是每帧刷屏）。
            if (skippedLoggedVersion != v) {
                skippedLoggedVersion = v;
                skippedNoScreen++;
                ShanhaiMod.LOGGER.info("{} jei_gui_refresh_pending reason=no_jei_screen version={} "
                                + "（★不消耗版本号：等用户真的打开 JEI 配方页那一帧再清布局缓存 —— "
                                + "以前在这里把版本号吃掉了，所以他按 R 打开时已经没有任何待刷新）", PREFIX, v);
            }
            return;
        }
        // 🔴 防重入（与 P0 那次同一个教训）：重建过程中 JEI 会再走一遍查询/构建，
        //    那期间绝不能再触发一次重建 —— 否则就是我们自己造出来的死循环。
        if (Boolean.TRUE.equals(REBUILDING.get())) {
            reentryBlocked++;
            return;
        }
        REBUILDING.set(Boolean.TRUE);
        try {
            // 🔴🔴 2026-10-06（用户给的读数：`jei_append_delivered times=1` ⇒ "关掉再打开"没再走那条路
            //    ⇒ **JEI 侧把那一页缓存住了**）：先清 JEI 自己的【布局缓存】。
            //
            //    取证（`javap -p mezz.jei.gui.recipes.RecipeGuiLogic`）：
            //      private IRecipeCategory<?> cachedRecipeCategory;            ← 缓存的是哪个分类
            //      private IRecipeLayoutList  cachedRecipeLayoutsWithButtons;  ← 【整页布局缓存】
            //      private int                cachedContainerId;
            //      private Set<RecipeSorterStage> cachedSorterStages;
            //    ⇒ 布局缓存里躺的是**补丁之前的配方对象**；重开时分类没变就直接复用 ⇒ **不重查**
            //      ⇒ 这正是"关掉再打开也看不到新配方"的机制 ✓
            //    ⇒ 清掉它们 ⇒ JEI 下一帧必须重新算这一页（重新走 getRecipes ⇒ 拿到我们那份）✓
            //    ⚠️ 比 `reopenIfOpen()` 好：**不会把页码重置**。
            final boolean cleared = clearJeiLayoutCache(screen);
            if (cleared) {
                openedTotal++;
                rebuildsThisVersion++;
                if (rebuildsThisVersion >= MAX_REBUILDS_PER_VERSION) {
                    handledVersion = v;
                }
                ShanhaiMod.LOGGER.info("{} jei_layout_cache_cleared version={} times={} cleared_total={} "
                                + "（★界面层：清掉 JEI 那一页的布局缓存 ⇒ 它下一帧必须重算这一页、"
                                + "重新走 getRecipes 拿到我们那份；**不重载、不整机重注册、不动页码**）",
                        PREFIX, v, rebuildsThisVersion, openedTotal);
                return;
            }
            // 清不动（字段名变了？）⇒ 退回"关掉再打开"那条路（已知副作用：页码重置）
            if (reopenIfOpen == null) {
                reopenIfOpen = screen.getClass().getDeclaredMethod("reopenIfOpen");
                reopenIfOpen.setAccessible(true);
            }
            reopenIfOpen.invoke(screen);
            openedTotal++;
            rebuildsThisVersion++;
            if (rebuildsThisVersion >= MAX_REBUILDS_PER_VERSION) {
                handledVersion = v;      // 这一版做够了 ⇒ 封口
            }
            ShanhaiMod.LOGGER.info("{} jei_gui_reopened version={} opened={} opened_total={} "
                            + "（补丁到了 ⇒ 让开着的 JEI 配方页自己重算一次；没有 reload、没有整机重注册；"
                            + "副作已知：页码会重置）",
                    PREFIX, v, rebuildsThisVersion, openedTotal);
        } catch (Throwable t) {
            handledVersion = v;          // 调不动就封口，免得每帧都试
            ShanhaiMod.LOGGER.warn("{} jei_gui_reopen_failed version={} err={} "
                            + "（反射调不动 ⇒ 界面要等你翻一页/重开一次才看到新配方；把这一行发我）",
                    PREFIX, v, t.toString());
        } finally {
            REBUILDING.set(Boolean.FALSE);
        }
    }
}
