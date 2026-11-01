package com.shanhai.client.jei;

import com.shanhai.ShanhaiMod;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
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
     * <p>与"按 id 去 JEI 里找当前那份"（要猜）的区别：这个是
     * <b>按我们自己记下来的实例</b>精确撤掉 —— 改同一条时 JEI 里那份正是我们上次加进去的，
     * 按实例表撤更稳、也绝不误伤别人 ✓
     * <p>⚠️ JEI 的 {@code hiddenRecipes} 是 {@code IdentityHashMap} 支撑的集合
     * （{@code RecipeTypeData} 构造器字节码实测）⇒ <b>传错实例 = 静默无效</b>，
     * 所以调用方只许传 {@code createRecipeLookup(...).includeHidden()} 或我们台账里拿到的**同一份实例**。
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

    /** 被 JEI 静默拒收（复核没进去）过几次（读数：>0 就是"addRecipes 白调了"的证据）。 */
    private static int registerRejected;

    /**
     * 🔴🔴 <b>把这一条【登记进 JEI 自己的表】</b>（2026-11-01：本条路升级成"会更新物品索引"的那条）。
     *
     * <h4>2026-11-01 的字节码取证（{@code jei-1.20.1-forge-15.49.0.188.jar}，{@code javap -p -c}）</h4>
     * <pre>
     *   IRecipeManager#addRecipes(RecipeType&lt;T&gt;, List&lt;T&gt;)                       ← 公开运行时 API（唯一一条能加的）
     *     → RecipeManagerInternal#addRecipes（偏移 12–122）
     *         → 逐条 addRecipe(category, recipe, hiddenRecipes)（偏移 0–170）
     *             偏移 8–15    hiddenRecipes.contains(recipe) ⇒ 拒收（debug: "Recipe not added because it is hidden"）
     *             偏移 55–62   !category.isHandled(recipe)    ⇒ 拒收
     *             偏移 102–119 造不出 ingredient supplier      ⇒ 拒收
     *             偏移 120–166 <b>for (RecipeMap m : recipeMaps.values()) m.addRecipe(type, recipe, supplier)</b>
     *                          ↑↑ 这一步就是【物品索引】—— 输入键、输出键、催化剂各一张表，全部更新
     *         → RecipeTypeData#addRecipes = recipes.addAll(...)（普通 ArrayList，**不去重**）
     *   ⇒ 「addRecipes 会不会更新物品索引」= <b>会</b>（这是本轮"点物品也能查到改过的那条"的地基）
     * </pre>
     *
     * <h4>🔴 但它的返回类型是 {@code void} ⇒ 会【静默拒收】</h4>
     * 被拒时既没有异常、也没有返回值，只有一个 {@code debug} 级日志（正式日志里看不见）
     * ⇒ 如果照着"调用成功"就往下走，就会出现最糟的组合：<b>我们把原稿 hide 了、而这一份压根没进去</b>
     * ⇒ 点物品恒 0 条（正是本轮要修的那个现象，换一层又回来）。
     * <p>⇒ 本方法<b>必须复核</b>：加完之后再用 {@code createRecipeLookup(cat).includeHidden()} 查一遍，
     * <b>按实例同一性</b>确认这一份真的躺进 JEI 的表里了，才返回 {@code true}。
     * （{@code includeHidden} 是刻意的：它证明的是"进了表"，不是"此刻可见"；
     *   我们这一份刚加进去、没被 hide，两个口径等价，但"进表"才是 addRecipes 的语义。）
     *
     * @return true = <b>复核通过</b>（这一份现在确实在 JEI 的表里）；false = 被拒 / 没就绪 / 分类不存在
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
            // ★★ 复核（见方法注释的"静默拒收"那一段）：按实例同一性确认它真的进了 JEI 的表。
            boolean present = false;
            try {
                for (Object v : rm.createRecipeLookup(cat).includeHidden().get().toList()) {
                    if (v == recipe) {
                        present = true;
                        break;
                    }
                }
            } catch (Throwable t) {
                // 复核本身挂了 ⇒ 当作"没进去"（宁可这一次不 hide 原稿，也不要"藏了又没加"）
                ShanhaiMod.LOGGER.warn("{} jei_runtime_register_verify_failed uid={} id={} err={}",
                        PREFIX, categoryUid, recipeId, t.toString());
                present = false;
            }
            if (present) {
                registeredInJei++;
            } else {
                registerRejected++;
            }
            ShanhaiMod.LOGGER.info("{} jei_runtime_registered id={} category={} ok={} total={} rejected={} "
                            + "（★addRecipes 会更新 JEI 的物品索引 ⇒ 「按产物/按原料点物品」那条路也认得这条；"
                            + "ok 是**复核出来的**，不是「调用没抛异常」⇒ ok=false 时调用方【绝不 hide 原稿】）",
                    PREFIX, recipeId, categoryUid, present, registeredInJei, registerRejected);
            return present;
        } catch (Throwable t) {
            registerRejected++;
            ShanhaiMod.LOGGER.warn("{} jei_runtime_register_failed uid={} id={} err={}",
                    PREFIX, categoryUid, recipeId, t.toString());
            return false;
        } finally {
            IN_OFFICIAL_CALL.set(Boolean.FALSE);
        }
    }

    /**
     * 🆕 2026-11-01：<b>这个分类里、这个完整 recipe id、此刻【可见】的全部实例</b>
     * （{@code createRecipeLookup(type).get()}，<b>不带</b> {@code includeHidden}）。
     *
     * <h4>为什么"不带 includeHidden"就是"只要可见的"（字节码）</h4>
     * <pre>
     *   RecipeLookup#get() 偏移 0–19：RecipeManagerInternal#getRecipesStream(type, focusGroup, <b>this.includeHidden</b>)
     *                                     （字段默认 false ⇒ IncludeHidden() 才置 true）
     *   PluginManager#getRecipes 偏移 36–74：if (!includeHidden) stream = stream.filter(hidden::contains).negate()
     *   ⇒ includeHidden=false ⇒ 只给"没被 hide 的" ✓
     * </pre>
     * <p>用途：{@code ShanhaiJeiReplaceOrder.applyChanged} 第 ③ 步要 hide 的是
     * <b>"JEI 自己那份原稿、且此刻还在显示"</b>；已经藏过的就不必再藏（这一步让"改一条"的
     * <b>hide 原稿次数恒为 1</b>，而不是每保存一次就多藏一次）。
     *
     * @return 可见实例（拿不到 / 没就绪 ⇒ 空表，绝不抛）
     */
    @SuppressWarnings("unchecked")
    public static List<Object> visibleInstancesOf(String categoryUid, String recipeId) {
        final IJeiRuntime rt = runtime;
        if (rt == null || categoryUid == null || recipeId == null) {
            return List.of();
        }
        if (inOfficialCall()) {
            noteReentryBlocked();
            return List.of();
        }
        IN_OFFICIAL_CALL.set(Boolean.TRUE);
        try {
            final ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
            if (uid == null) {
                return List.of();
            }
            final IRecipeManager rm = rt.getRecipeManager();
            final java.util.Optional<RecipeType<?>> catOpt = rm.getRecipeType(uid);
            if (catOpt.isEmpty()) {
                return List.of();
            }
            final RecipeType<Object> cat = (RecipeType<Object>) catOpt.get();
            final List<Object> out = new ArrayList<>();
            rm.createRecipeLookup(cat).get()
                    .filter(v -> recipeId.equals(ShanhaiJeiRecipePatches.idOf(v)))
                    .forEach(out::add);
            return out;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} jei_runtime_visible_failed uid={} id={} err={}",
                    PREFIX, categoryUid, recipeId, t.toString());
            return List.of();
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
     * 🔴🔴 <b>把之前被藏掉的那一份【还回去】</b>（2026-10-06 现场读数逼出来的）。
     *
     * <h4>为什么必须有这一半（用户看到的"整个类型在 JEI 里没了"）</h4>
     * 现场：服务端那一局把补丁判成"没套用"（{@code jei_patch_dropped … reason=not_in_force}、
     * {@code jei_reconcile in_force=0 kept=0 dropped=3 patches_now=0}）⇒ 客户端把补丁表清空 ✓
     * —— 清空本身是对的（服务端不套用了，客户端也不该继续套）。
     * <p>⚠️ <b>但我们此前只做过"藏"，从没做过"还"</b>：被 {@code hideRecipes} 藏进 JEI hidden 集合的
     * 那一条，<b>不会因为补丁被丢掉而自动回来</b> ✗ ⇒ 表现出来就是"那条配方再也看不见"。
     * <p>⇒ 丢补丁时必须把藏过的还回去（{@code IRecipeManager#unhideRecipes}，
     * 与 hide 同一套实例来源：<b>必须用 JEI 自己给的那份</b>）。
     * <p>🔴 2026-11-01：原稿现在是由 {@link ShanhaiJeiReplaceOrder#applyChanged} 第 ③ 步藏掉的
     * （老的 {@code hideOwnCopies} 已删，因为它那条"只 hide、不 add"就是本轮缺陷本身）。
     * 还回去这一半照旧 —— 判据仍是"补丁被丢掉 ⇒ 用户还能看见原来的那条"。
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
