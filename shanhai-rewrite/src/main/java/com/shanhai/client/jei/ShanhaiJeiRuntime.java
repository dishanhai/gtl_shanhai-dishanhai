package com.shanhai.client.jei;

import com.shanhai.ShanhaiMod;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 🔴🔴 <b>2026-10-05（用户原话：「你去看看老山海是怎么解决的」）：把 JEI 的运行时拿到手。</b>
 *
 * <h2>为什么非要有它（现场对照，两边逐条对上）</h2>
 * 用户实测：「我新建了 GT 配方，<b>很显然 jei 又没有及时刷新</b>」。
 * 而他自己那份日志里，我们这一侧其实<b>已经把配方补进去了</b>：
 * <pre>
 *   jei_append_built id=…new_recipe_1 type=gtceu:zero_point_conversion bytes=140 class=GTRecipe
 *   jei_sync APPENDED matched=0 hidden=0 added=1 visible_expected=1 expected=1 PASS=true channel=category
 * </pre>
 * ⇒ 数据进去了，<b>屏幕上却没变</b>。差别在这里（老山海的源码，本地克隆
 * {@code originals/upstream/gtl_shanhai-dishanhai}）：
 * <pre>
 *   ShanhaiJEIPlugin.java:11   @JeiPlugin
 *   ShanhaiJEIPlugin.java:14   private static IJeiRuntime jeiRuntime;
 *   ShanhaiJEIPlugin.java:26   public void onRuntimeAvailable(IJeiRuntime runtime) { jeiRuntime = runtime; }
 *   RecipeSyncPacket.java      jeiRuntime.getRecipeManager() → createRecipeLookup(cat).includeHidden().get()
 *                              → recipeManager.hideRecipes(jeiType, oldWrappers)
 *                              → recipeManager.addRecipes(jeiType, newWrappers)
 *   （出处：{@code handoff/outbound/参考-上游配方控制API.md} §5.2 / §5.3，行 546-588）
 * </pre>
 * ⇒ <b>老山海调的是 JEI 自己的运行时 API</b>（hide 旧的 + add 新的）—— 那会更新 JEI 内部的配方表
 * 并让它重算界面；而我们此前<b>只在"查询出口"过滤</b>（{@code PluginManager#getRecipes} 的注入点），
 * 数据是对的、<b>但没通知 JEI 自己那份表和界面</b> ⇒ 用户正看着的那一页不会重排。
 *
 * <h2>本类做什么</h2>
 * 一个标准的 JEI 插件，<b>只做一件事</b>：把 JEI 就绪时交给我们的 {@code IJeiRuntime} 存下来，
 * 供 {@link ShanhaiJeiRecipePatches} 用官方 API 把那一条换掉/补进去。
 * <b>不注册任何分类、不注册任何配方、不碰任何别人的分类</b>（{@code getPluginUid} 只占一个位置）。
 *
 * <h2>⚠️ 边界（如实写）</h2>
 * <ul>
 *   <li>JEI 没装 / 还没就绪 ⇒ {@link #runtime()} 返回 {@code null}，调用方<b>退回原来的查询出口那条路</b>
 *       （那条路本来就是通的：{@code added=1 PASS=true}）⇒ <b>没有它也不会更差</b>；</li>
 *   <li>本类<b>只被 JEI 的插件扫描器加载</b> ⇒ 专服上不会被碰（JEI 在专服不存在）；</li>
 *   <li>类名/包名改了会让 JEI 找不到插件 ⇒ 这个位置<b>不许挪</b>。</li>
 * </ul>
 */
@JeiPlugin
public final class ShanhaiJeiRuntime implements IModPlugin {

    private static final String PREFIX = "[SHANHAI-JEIPATCH]";

    /** JEI 就绪时给我们的那一份（没就绪 = null）。 */
    private static volatile IJeiRuntime runtime;

    /** 已经用 JEI 官方 API 贴过的补丁数（读数用）。 */
    private static int appliedViaRuntime;

    /** 还回去过几次（读数）。 */
    private static int unhiddenTotal;

    /** 按实例精确撤掉过几个（读数）。 */
    private static int hiddenGivenTotal;

    /**
     * 🔴🔴 <b>把"我们指定的一批实例"藏掉</b>（用户："新建之后再改同一条 ⇒ JEI 里两份"的修法）。
     *
     * <p>与 {@link #hideOwnCopies} 的区别：那个是"按 id 去 JEI 里找当前那份"（要猜），
     * 这个是"**按我们自己记下来的实例**精确撤掉" —— 改同一条时 JEI 里那份**正是我们上次加进去的**，
     * 按实例表撤更稳、也绝不误伤别人 ✓
     */
    @SuppressWarnings("unchecked")
    public static boolean hideGiven(String categoryUid, java.util.List<Object> instances) {
        final IJeiRuntime rt = runtime;
        if (rt == null || categoryUid == null || instances == null || instances.isEmpty()) {
            return false;
        }
        if (inOfficialCall()) {
            noteReentryBlocked();
            return false;
        }
        IN_OFFICIAL_CALL.set(Boolean.TRUE);
        try {
            final ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
            if (uid == null) {
                return false;
            }
            final IRecipeManager rm = rt.getRecipeManager();
            final java.util.Optional<RecipeType<?>> catOpt = rm.getRecipeType(uid);
            if (catOpt.isEmpty()) {
                return false;
            }
            rm.hideRecipes((RecipeType<Object>) catOpt.get(), new ArrayList<>(instances));
            hiddenGivenTotal += instances.size();
            ShanhaiMod.LOGGER.info("{} jei_runtime_hide_given uid={} count={} total={} "
                            + "（★把我们上一次 addRecipes 进去的那份精确撤掉 ⇒ 同一条 id 只会有一份）",
                    PREFIX, categoryUid, instances.size(), hiddenGivenTotal);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} jei_runtime_hide_given_failed uid={} err={}", PREFIX, categoryUid,
                    t.toString());
            return false;
        } finally {
            IN_OFFICIAL_CALL.set(Boolean.FALSE);
        }
    }

    /** 登记进 JEI 自己结构过几次（读数）。 */
    private static int registeredInJei;

    /**
     * 🔴🔴 <b>把这一条【登记进 JEI 自己的结构里】</b>（2026-10-06 用户给出的最小现象逼出来的）。
     *
     * <h4>现象（用户逐条实测）</h4>
     * <pre>
     * ❌ 用【产物】去找那条新配方 ⇒ 找不到
     * ❌ 用【原料】右键"能做什么" ⇒ 找不到
     * ✅ 左键原料 ⇒ 能找到新增的那条         ← 说明它确实在 JEI 里（我们在列表里补过）
     * </pre>
     * ⇒ **缺的是"输出 → 配方"那条键**：我们此前只在 {@code PluginManager.getRecipes} 的**返回值**上
     * 追加了一条，**没有把它登记进 JEI 自己的结构**（{@code IRecipeManager#addRecipes} 做的正是这件事）
     * ⇒ 按【产物】查找（走的正是 JEI 内部那条索引）就找不到 ✗
     * <p>⚠️ 以前不敢调 addRecipes：怕和我们的追加**变成两份**。现在不会了 ——
     * 我们的追加条件是 <b>{@code p.matched == 0}</b>（JEI 自己没给这条才补）；一旦登记进 JEI 自己的结构，
     * 下一次 {@code getRecipes} 的 {@code matched} 就 ≥1 ⇒ **我们自动不再补** ✓ 天然防重 ✓
     *
     * @return true = 登记进去了
     */
    @SuppressWarnings("unchecked")
    public static boolean registerInJei(String categoryUid, String recipeId, Object recipe) {
        final IJeiRuntime rt = runtime;
        if (rt == null || categoryUid == null || recipeId == null || recipe == null) {
            return false;
        }
        if (inOfficialCall()) {
            noteReentryBlocked();
            return false;
        }
        IN_OFFICIAL_CALL.set(Boolean.TRUE);
        try {
            final ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
            if (uid == null) {
                return false;
            }
            final IRecipeManager rm = rt.getRecipeManager();
            final java.util.Optional<RecipeType<?>> catOpt = rm.getRecipeType(uid);
            if (catOpt.isEmpty()) {
                return false;
            }
            final RecipeType<Object> cat = (RecipeType<Object>) catOpt.get();
            rm.addRecipes(cat, List.of(recipe));
            registeredInJei++;
            ShanhaiMod.LOGGER.info("{} jei_runtime_registered uid={} id={} total={} "
                            + "（★把这条登记进 JEI 自己的结构 ⇒ 「按产物查找」那条路也认得它；"
                            + "我们那层的追加条件是 matched==0，所以不会再补一份 ⇒ 不会两份）",
                    PREFIX, categoryUid, recipeId, registeredInJei);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} jei_runtime_register_failed uid={} id={} err={}",
                    PREFIX, categoryUid, recipeId, t.toString());
            return false;
        } finally {
            IN_OFFICIAL_CALL.set(Boolean.FALSE);
        }
    }

    /**
     * 🔴🔴 <b>防重入闸（2026-10-05 P0：无限递归把游戏卡死过）。</b>
     *
     * <h4>那个闭环（用户实测的栈，逐行）</h4>
     * <pre>
     * hideOwnCopies(ShanhaiJeiRuntime.java:133)       ← 我们在查 JEI 的表
     *  ← RecipeLookup.get → RecipeManagerInternal.getRecipesStream
     *  ← PluginManager.getRecipes                     ← 我们的 mixin 就挂在这里
     *  ← patched() → tryRuntimeHide() → hideOwnCopies ← 又回到起点 ⇒ 无限递归 ⇒ 卡死
     * </pre>
     * ⇒ 语义：<b>"我正在用官方 API 查/改这张表，这期间我们自己的出口直接透传、不许再加工"</b> ✓
     * <p>用 {@link ThreadLocal}：JEI 的查询都在客户端主线程，但用 ThreadLocal 更保险
     * （将来若有别的线程查配方，不会把主线程的闸误关）。
     */
    private static final ThreadLocal<Boolean> IN_OFFICIAL_CALL =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 重入被拦下的次数（读数：能 grep、能机器判）。 */
    private static final java.util.concurrent.atomic.AtomicInteger REENTRY_BLOCKED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 现在是不是正在"用官方 API 查/改 JEI 的表"。 */
    public static boolean inOfficialCall() {
        return Boolean.TRUE.equals(IN_OFFICIAL_CALL.get());
    }

    /** 重入被拦下的次数（判据用）。 */
    public static int reentryBlockedCount() {
        return REENTRY_BLOCKED.get();
    }

    /** 出口那一层用它记一次"拦下了重入"（只记数，不打日志 —— 那是每帧的路径）。 */
    public static void noteReentryBlocked() {
        REENTRY_BLOCKED.incrementAndGet();
    }

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation("shanhai", "recipe_patch");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        ShanhaiMod.LOGGER.info("{} jei_runtime_available plugin={} "
                        + "（拿到 JEI 运行时 ⇒ 可以用官方 hideRecipes/addRecipes 只换那一条；"
                        + "与老山海 ShanhaiJEIPlugin#onRuntimeAvailable 同一条路）",
                PREFIX, getPluginUid());
    }

    /** JEI 运行时（没装 JEI / 还没就绪 = {@code null}）。 */
    public static IJeiRuntime runtime() {
        return runtime;
    }

    public static String statsLine() {
        return "runtime=" + (runtime != null) + " applied_via_runtime=" + appliedViaRuntime;
    }

    /**
     * 🔴 <b>只做一件事：把 JEI 自己那份里同 id 的旧条目 hide 掉</b>（用 JEI 官方 API）。
     *
     * <h4>为什么【只 hide、绝不 add】（这是本轮改过一轮之后的结论）</h4>
     * 用户实测的三条规律：<b>改能刷、插不刷、删不刷</b> ——「把列表里那条换成新的」能做，
     * 「插一条 / 拿掉一条」做不到。而我们的"查询出口"那条通道（
     * {@code ShanhaiJeiRecipeOrderMixin} 注在 {@code PluginManager.getRecipes} 的 RETURN）
     * 对这三件事的日志<b>全是 PASS</b>（{@code matched/hidden/added} 记账正确）。
     * <p>⇒ 一旦我们<b>又</b>用官方 API 去 {@code addRecipes}，同一个 id 就会在 JEI 里存在两份
     * （JEI 的 {@code distinct()} 只按 {@code equals} 去重，而配方对象没重写 equals ⇒ 两个新实例
     * 都留下）—— 那是本工程点名过的坑。
     * <p>⇒ 所以分工定死：
     * <ul>
     *   <li><b>加 / 换</b> ⇒ 只由我们那条查询出口通道做（唯一真源，已验证 PASS）；</li>
     *   <li><b>删 / 换掉旧的</b> ⇒ 这里调一次 {@code hideRecipes}（用 <b>JEI 自己给的那份实例</b>，
     *       不自己造 —— 传错实例会静默无效，见类注释里那条 IdentityHashMap 的取证），
     *       顺便让 JEI 重算一次它自己的配方表与界面。</li>
     * </ul>
     *
     * @param categoryUid JEI 分类 uid（GT = 配方类型 id；原版 = {@code minecraft:crafting} 这种）
     * @param recipeId    完整 recipe id（去重口径）
     * @return true = 真的藏掉了（至少一份）；false = 没找到/没藏成（调用方无需做别的，我们那层会兜住）
     */
    @SuppressWarnings("unchecked")
    public static boolean hideOwnCopies(String categoryUid, String recipeId) {
        final IJeiRuntime rt = runtime;
        if (rt == null || categoryUid == null || recipeId == null) {
            return false;
        }
        // 🔴🔴 防重入：已经在官方调用里了 ⇒ 绝不再进来（否则就是那个无限递归闭环）。
        if (inOfficialCall()) {
            noteReentryBlocked();
            return false;
        }
        IN_OFFICIAL_CALL.set(Boolean.TRUE);
        try {
            final ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
            if (uid == null) {
                return false;
            }
            final IRecipeManager rm = rt.getRecipeManager();
            final java.util.Optional<RecipeType<?>> catOpt = rm.getRecipeType(uid);
            if (catOpt.isEmpty()) {
                ShanhaiMod.LOGGER.warn("{} jei_runtime_no_category uid={} id={}（我们那层照样会处理它）",
                        PREFIX, categoryUid, recipeId);
                return false;
            }
            final RecipeType<Object> cat = (RecipeType<Object>) catOpt.get();
            final List<Object> old = new ArrayList<>();
            final IRecipeLookup<Object> lookup = rm.createRecipeLookup(cat);
            lookup.includeHidden().get()
                    .filter(v -> recipeId.equals(ShanhaiJeiRecipePatches.idOf(v)))
                    .forEach(old::add);
            if (old.isEmpty()) {
                return false;                 // 本来就不在 ⇒ 没什么可藏（不是失败）
            }
            rm.hideRecipes(cat, old);
            appliedViaRuntime++;
            ShanhaiMod.LOGGER.info("{} jei_runtime_hidden uid={} id={} hidden={} total={} "
                            + "（用 JEI 自己给的那份实例 hide —— 传错实例会静默无效，那条坑有取证）",
                    PREFIX, categoryUid, recipeId, old.size(), appliedViaRuntime);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} jei_runtime_hide_failed uid={} id={} err={}（我们那层照样会处理它）",
                    PREFIX, categoryUid, recipeId, t.toString(), t);
            return false;
        } finally {
            // 🔴 无论成败都必须落闸（否则一次异常就把出口永久关掉 = 补丁层整层失效）
            IN_OFFICIAL_CALL.set(Boolean.FALSE);
        }
    }

    /**
     * 🔴🔴 <b>把 {@link #hideOwnCopies} 藏掉的那一份【还回去】</b>（2026-10-06 现场读数逼出来的）。
     *
     * <h4>为什么必须有这一半（用户看到的"整个类型在 JEI 里没了"）</h4>
     * 现场：服务端那一局把补丁判成"没套用"（{@code jei_patch_dropped … reason=not_in_force}、
     * {@code jei_reconcile in_force=0 kept=0 dropped=3 patches_now=0}）⇒ 客户端把补丁表清空 ✓
     * —— 清空本身是对的（服务端不套用了，客户端也不该继续套）。
     * <p>⚠️ <b>但我们此前只做过"藏"，从没做过"还"</b>：被 {@code hideRecipes} 藏进 JEI hidden 集合的
     * 那一条，<b>不会因为补丁被丢掉而自动回来</b> ✗ ⇒ 表现出来就是"那条配方再也看不见"。
     * <p>⇒ 丢补丁时必须把藏过的还回去（{@code IRecipeManager#unhideRecipes}，
     * 与 hide 同一套实例来源：<b>必须用 JEI 自己给的那份</b>）。
     *
     * @return true = 真的还回去了（至少一份）
     */
    @SuppressWarnings("unchecked")
    public static boolean unhideOwnCopies(String categoryUid, String recipeId) {
        final IJeiRuntime rt = runtime;
        if (rt == null || categoryUid == null || recipeId == null) {
            return false;
        }
        if (inOfficialCall()) {
            noteReentryBlocked();
            return false;
        }
        IN_OFFICIAL_CALL.set(Boolean.TRUE);
        try {
            final ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
            if (uid == null) {
                return false;
            }
            final IRecipeManager rm = rt.getRecipeManager();
            final java.util.Optional<RecipeType<?>> catOpt = rm.getRecipeType(uid);
            if (catOpt.isEmpty()) {
                return false;
            }
            final RecipeType<Object> cat = (RecipeType<Object>) catOpt.get();
            final List<Object> back = new ArrayList<>();
            rm.createRecipeLookup(cat).includeHidden().get()
                    .filter(v -> recipeId.equals(ShanhaiJeiRecipePatches.idOf(v)))
                    .forEach(back::add);
            if (back.isEmpty()) {
                return false;
            }
            rm.unhideRecipes(cat, back);
            unhiddenTotal++;
            ShanhaiMod.LOGGER.info("{} jei_runtime_unhidden uid={} id={} unhidden={} total={} "
                            + "（补丁被丢掉 ⇒ 把之前藏掉的那份还回去，否则它会永远看不见）",
                    PREFIX, categoryUid, recipeId, back.size(), unhiddenTotal);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} jei_runtime_unhide_failed uid={} id={} err={}", PREFIX, categoryUid,
                    recipeId, t.toString());
            return false;
        } finally {
            IN_OFFICIAL_CALL.set(Boolean.FALSE);
        }
    }
}
