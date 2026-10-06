package com.shanhai.client.jei;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor.ShanhaiJeiBridge;
import com.shanhai.common.recipe.editor.ShanhaiJeiSyncPlan;
import com.shanhai.common.recipe.editor.ShanhaiRecipeConditions;
import com.shanhai.common.recipe.editor.ShanhaiRecipeIoApply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海重构 · <b>JEI 热生效的客户端落地（补丁表）</b>。
 *
 * <h2>1. 走的是哪条路，为什么不走 hide/add</h2>
 * 侦察线给的两条路里选的是<b>候选 A</b>：把补丁贴在<b>已经在跑的那个注入点</b>
 * （{@code ShanhaiJeiRecipeOrderMixin} → {@code PluginManager#getRecipes(RecipeTypeData, IFocusGroup, boolean)}
 * 的 {@code @At("RETURN")}）上，在那个出口处<b>按完整 recipe id 过滤/替换/追加</b>。
 * 相对 hide/add 的好处（都是"少一个会静默失败的环节"）：
 * <ul>
 *   <li><b>不需要拿到 JEI 手里那几个一模一样的实例</b> —— 而
 *       {@code RecipeTypeData.hiddenRecipes} 是 {@code Collections.newSetFromMap(new IdentityHashMap<>())}，
 *       也就是说 {@code hideRecipes} 传错实例会【静默无效】（不报错、不写日志）；</li>
 *   <li><b>不往 JEI 内部列表里塞东西</b> —— 我们造的那一份只活在这一次 {@code getRecipes} 的返回值里，
 *       既不会成倍增长，也不会污染"下一次替换"的底本（每次都从 JEI 原来那条真配方重算）；</li>
 *   <li>不需要 {@code @JeiPlugin}、不需要在客户端自己查 GT 索引（
 *       🔴 专用服 + 远程客户端下客户端的 GT 索引是<b>空的</b> ——
 *       {@code handleUpdateRecipes} 走 {@code replaceRecipes}，而灌 GT 索引的地方挂在 datapack {@code apply} 上
 *       ⇒ 任何"客户端自己重算"的写法都会拿到空，表现为那一页变空）。</li>
 * </ul>
 *
 * <h2>2. 🔴 去重口径 = 完整 recipe id（不是"找一条就换一条"）</h2>
 * 同一条配方在 JEI 里可能是<b>两条</b>：GTCEu/gtlcore 会分批注册；而且同一 id 在一个分类里出现两次
 * 都不稀奇。所以这段循环的规则是：
 * <pre>
 *   遇到第一个匹配 id 的原件 ⇒ 用它做底本（它身上带的是客户端那份真 GTRecipe）
 *   同一 id 的后续原件      ⇒ 全部丢掉（seen 集合）
 *   被替换掉的 id           ⇒ 追加【一份】我们造的（缓存住，不每次重造）
 * </pre>
 * ⇒ 界面上这一条永远是 <b>1</b> 条。这条算术由纯核 {@link ShanhaiJeiSyncPlan} 给出，
 * 并在运行时把"算出来的"和"实际发生的"一起打进日志（{@code jei_sync} 那行）。
 *
 * <h2>3. ⚠️ 如实交代：这条路的边界</h2>
 * <ul>
 *   <li>覆盖到的是 <b>JEI 分类页</b>（打开某个配方类型看列表）；</li>
 *   <li><b>U 键按物品反查</b>那一头是否也走同一个出口，<b>本机验不了</b>（红线禁止开客户端）
 *       ⇒ 如实标成"未验"，不吹成"全覆盖"；</li>
 *   <li>本类只在客户端被加载（由 {@link ShanhaiJeiBridge.RecipeSyncMessage#handle} 经
 *       {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, …)} 调进来）。</li>
 * </ul>
 */
public final class ShanhaiJeiRecipePatches {

    public static final String PREFIX = "[SHANHAI-JEIPATCH]";

    private static final Map<String, Patch> PATCHES = new ConcurrentHashMap<>();

    private ShanhaiJeiRecipePatches() {}

    /** 一条配方要贴成什么样。 */
    public static final class Patch {
        final String recipeId;
        final String typeId;
        final boolean removed;
        final String inputsJson;
        final String outputsJson;
        /** 🆕 第 5 轮：额外条件的平铺 JSON（{@code null} = 这一条不动条件）。 */
        final String conditionsJson;
        final boolean hasDuration;
        final int duration;
        final boolean hasEut;
        final long eut;

        /**
         * 🆕 2026-10-05（工作台 / 原版配方）：非 GT 的那一份载荷。
         *
         * <p>形状 = {@code {"result":{…},"cookingtime":N,"experience":X}}。
         * 非 {@code null} ⇒ 这条补丁走<b>原版分支</b>（{@link #synthesize}）：拿 JEI 手里那条
         * {@code net.minecraft…Recipe} 当底本，用原版 public 构造器重建一条同类新实例。
         */
        final String vanillaJson;

        /**
         * 🆕 修复②：这条配方的 GT 网络编码字节。
         *
         * <p>用途<b>只有一个</b>：当这一条<b>压根不在 JEI 那一页里</b>时
         * （{@code matched == 0}，正是"刚新建的配方"那一拍），把解出来的
         * {@code GTRecipeWrapper} <b>补进列表</b>。
         * <p>已经存在的那条<b>不走它</b> —— 那条继续走"按 id 换成我们造的那一份"，
         * 保留第 5 轮那套"每次都从原件重算"的纪律（不许在改动结果上再改）。
         */
        final byte[] recipeBytes;

        /** 「补进列表」那一支造出来的那一份（与 {@link #synth} 分开存，口径不同）。 */
        volatile Object synthAppended;
        /** 「补进列表」这一支的判据行只打一次。 */
        volatile boolean appendLogged;

        /** 🔴 已经用 JEI 官方 API（hideRecipes/addRecipes）贴过了 ⇒ 查询出口那一层跳过它，免得出两份。 */
        volatile boolean runtimeApplied;

        /** 🔴 试过官方 API 这条路（但可能没成）⇒ 查询出口那层会再补一次（见 tryRuntimeApply）。 */
        volatile boolean runtimeTried;
        /** 🔴 还没成 ⇒ 在查询出口那里接着试（收包那一刻 JEI 可能还没就绪）。 */
        volatile boolean runtimePending;
        /** 重试了几次（读数用）。 */
        volatile int runtimeRetries;
        /** ★一把刀切开用的读数：这条补丁【被交出去】过几次（关掉 JEI 再打开后还在涨 = 数据层没问题）。 */
        volatile int deliveredTimes;

        /** 🔴 已经登记进 JEI 自己的结构（"输出 → 配方"那条索引）了 ⇒ 只做一次。 */
        volatile boolean registered;

        /**
         * 🔴🔴 2026-10-06（用户抓到的"自己藏自己"）：**这条 id 在 JEI 里本来就有旧的**吗？
         *
         * <p>判据 = 我们在 {@code getRecipes} 交出来的那份列表里**看到过**它，且那次**不是**我们自己
         * 登记进去的（{@code !registered}）。
         * <ul>
         *   <li>{@code hadOriginal == false} ⇒ **新建的配方**：JEI 里没有旧的 ⇒
         *       <b>只 addRecipes、绝不 hideRecipes</b>（hide 会把我们刚登记进去的那份藏掉 ⇒
         *       add 与 hide 互相抵消 ⇒ 用户完全看不到 ✗✗ 现场读数：{@code jei_runtime_registered total=1}
         *       紧接着 {@code jei_runtime_hidden hidden=1}）；</li>
         *   <li>{@code hadOriginal == true} ⇒ 改/删一条已有的：藏掉 JEI 那份旧的 ✓（我们那层负责补新的）。</li>
         * </ul>
         */
        volatile boolean hadOriginal;

        /**
         * 🔴🔴 2026-10-06（用户："新建之后再改产物 ⇒ JEI 里同一条出现两份"）：
         * <b>我们自己 {@code addRecipes} 进去的那几个实例</b>（正常最多 1 个）。
         *
         * <p>为什么要自己记：**不能靠"JEI 现在交出来的是哪份"去猜** —— 新建之后再改同一条时，
         * JEI 交出来的那份**正是我们上次加进去的** ⇒ {@code hadOriginal} 判不出来 ⇒
         * 结果又是"只 add、不撤旧的" ⇒ 同 id 两份并存 ✗（用户截图为证：×1 与 ×3 并排）
         * <p>用法：改/删同一条时**先用这张表把旧的实例 hide 掉（按实例同一性，精确），再上新的一份** ✓
         * <p>⚠️ 这张表必须**跟着补丁一起换代**：每次改同一条都会 new 一个 Patch
         * ⇒ {@code accept} 里必须把旧 Patch 的这张表**搬过来**，否则引用丢了就永远撤不掉旧的 ✗
         */
        final java.util.List<Object> addedInstances = new java.util.ArrayList<>();

        /** 我们造出来的那一份（只活在本表的返回值里；下一次改同一条会重造）。 */
        volatile Object synth;

        /** 过滤器读数：这一条补丁在每个通道里各自被看到几次（三个计数器，口径见类注释 §2）。 */
        int matched;
        int hidden;
        int added;

        /** 造不出替代品 ⇒ 老老实实显示原件（这一格<b>不算 hidden</b>，否则读数会自己骗自己）。 */
        int keptOriginal;

        /** {@code jei_sync} 那行日志只打一次（每 tick 都会调 {@code getRecipes}，不能每次都打）。 */
        volatile boolean logged;

        /** 收藏夹那条通道（{@code RecipesGui#showRecipes}）的判据行也只打一次。 */
        volatile boolean bookmarkLogged;

        Patch(String recipeId, String typeId, boolean removed, String inputsJson, String outputsJson,
              String conditionsJson, boolean hasDuration, int duration, boolean hasEut, long eut) {
            this(recipeId, typeId, removed, inputsJson, outputsJson, conditionsJson,
                    hasDuration, duration, hasEut, eut, null, null);
        }

        Patch(String recipeId, String typeId, boolean removed, String inputsJson, String outputsJson,
              String conditionsJson, boolean hasDuration, int duration, boolean hasEut, long eut,
              String vanillaJson) {
            this(recipeId, typeId, removed, inputsJson, outputsJson, conditionsJson,
                    hasDuration, duration, hasEut, eut, vanillaJson, null);
        }

        Patch(String recipeId, String typeId, boolean removed, String inputsJson, String outputsJson,
              String conditionsJson, boolean hasDuration, int duration, boolean hasEut, long eut,
              String vanillaJson, byte[] recipeBytes) {
            this.recipeId = recipeId;
            this.typeId = typeId;
            this.removed = removed;
            this.inputsJson = inputsJson;
            this.outputsJson = outputsJson;
            this.conditionsJson = conditionsJson;
            this.hasDuration = hasDuration;
            this.duration = duration;
            this.hasEut = hasEut;
            this.eut = eut;
            this.vanillaJson = vanillaJson;
            this.recipeBytes = recipeBytes;
        }
    }

    // ------------------------------------------------------------------ 收包

    /** 收到一条同步包（客户端主线程）。 */
    public static void accept(ShanhaiJeiBridge.RecipeSyncMessage msg) {
        try {
            if (msg.action == ShanhaiJeiBridge.ACTION_RECONCILE) {
                reconcile(msg.inForce);         // 防呆：对账包不走"存补丁"这条路
                return;
            }
            final boolean removed = msg.action == ShanhaiJeiBridge.ACTION_REMOVED;
            final Patch stored = new Patch(msg.recipeId, msg.typeId, removed,
                    msg.inputsJson, msg.outputsJson, msg.conditionsJson,
                    msg.hasDuration, msg.duration, msg.hasEut, msg.eut, msg.vanillaJson, msg.recipeBytes);
            // 🔴🔴 2026-10-06（用户："新建之后再改同一条 ⇒ JEI 里两份"）：
            //    **改同一条 = 换一个补丁对象** ⇒ 必须把上一版的"我们自己加过的实例表"搬过来，
            //    否则引用一丢就永远撤不掉旧的那一份 ⇒ 同 id 两份并存 ✗（用户截图为证）
            final Patch prev = PATCHES.get(msg.recipeId);
            if (prev != null) {
                stored.addedInstances.addAll(prev.addedInstances);
                stored.hadOriginal = prev.hadOriginal;
                stored.registered = prev.registered;
            }
            PATCHES.put(msg.recipeId, stored);
            ShanhaiMod.LOGGER.info("{} jei_patch_stored id={} type={} action={} patches={} cond_n={} kind={} "
                            + "recipe_bytes={} "
                            + "(去重口径=完整 recipe id；旧的那份会在下一次 getRecipes 里被丢掉)",
                    PREFIX, msg.recipeId, msg.typeId, removed ? "removed" : "changed", PATCHES.size(),
                    condCount(msg.conditionsJson), msg.vanillaJson == null ? "gt" : "vanilla",
                    msg.recipeBytes == null ? 0 : msg.recipeBytes.length);
            // 🔴🔴 2026-10-05 第二轮（用户实测三条规律：**改能刷、插不刷、删不刷**）：
            //    分工定死 ——
            //      · **加 / 换** 只由我们这条查询出口通道做（{@code patched}，日志里三条全 PASS）；
            //      · 官方 API **只 hide、绝不 add**（hide 掉 JEI 自己那份旧条目 + 让 JEI 重算一次表），
            //        绝不 add：add 会让同一个 id 在 JEI 里存在两份（distinct 只按 equals 去重，
            //        而配方对象没重写 equals ⇒ 两个新实例都留下），那是本工程点名过的坑。
            tryRuntimeHide(stored);
            // 🔴🔴 2026-10-05（用户："插不刷、删不刷"）：**让开着的 JEI 界面重算一次**。
            //    为什么必须单独做这一步：javap 实测 JEI 的 hideRecipes/addRecipes 里
            //    一个 notify / Listener 都没有 ⇒ 它们不通知界面 ⇒ 界面不知道自己该重算。
            //    这一层只在"界面正开着"时动作，且不 reload、不整机重注册。
            ShanhaiJeiGuiRefresh.notifyPatched();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} jei_patch_store_failed id={} err={}", PREFIX, msg.recipeId, t.toString(), t);
        }
    }

    /**
     * 🔴 <b>试一次"把 JEI 自己那份旧条目 hide 掉"；没成就记成待办，下一次查询再试。</b>
     *
     * <h4>为什么必须能重试（不是只试一次）</h4>
     * 收包那一刻 JEI 可能<b>还没就绪</b>（{@code onRuntimeAvailable} 还没被调 ⇒ runtime==null），
     * 或者那个分类当时还查不到 ⇒ 原来的"只试一次"写法会让这条补丁<b>永远走不到官方 API 那条路</b>
     * —— 表现出来就是用户说的"有时候刷、有时候不刷"。
     * <p>而 {@code PluginManager#getRecipes}（我们挂的那个查询出口）<b>每次查询都会跑</b>
     * ⇒ 在那里补做一次，代价只是几个判断，而且一旦成功就不再重试。
     * <p>⚠️ 这里<b>只 hide、不 add</b>（见 {@code accept} 里的分工说明）。
     */
    private static void tryRuntimeHide(Patch p) {
        if (p == null || p.runtimeApplied) {
            return;
        }
        if (p.runtimeTried) {
            p.runtimeRetries++;
        }
        p.runtimeTried = true;
        try {
            // 🔴 安全闸（很关键）：**只有"我们这层能把这条再交出去"时才去藏**。
            //    为什么：藏掉 JEI 自己那份之后，`patched` 那层就看不到原件了（matched=0），
            //    于是完全靠"从字节重建"把新那份补进输出。**重建不出来还去藏 ⇒ 这条配方直接消失** ✗
            //    ⇒ 两条判据：① 删除类不需要重建（本来就不该在）；② 其余必须先证明能重建。
            if (!p.removed && runtimeAddable(p) == null) {
                p.runtimePending = false;             // 不再重试：藏了会更糟，交给查询出口那条路
                ShanhaiMod.LOGGER.info("{} jei_runtime_hide_skipped id={} reason=cannot_rebuild"
                                + "（重建不出新那份 ⇒ 不敢藏：藏了就真没了；查询出口那层会按 id 替换它）",
                        PREFIX, p.recipeId);
                return;
            }
            // 🔴🔴 2026-10-06（用户："左键原料能查到、用产物查不到"）：**把这条登记进 JEI 自己的结构。**
            //    我们此前只在 `getRecipes` 的**返回值**上追加了一条 ⇒ 那只是"显示列表"，
            //    而 JEI 的"按物品查找"（左键=怎么获得 / 右键=能做什么）走的是**它自己内部那条索引**
            //    ⇒ 不登记进去，按【产物】就永远查不到 ✗
            //    ⚠️ 只对【新建的】登记（JEI 里本来没有旧的）：改/删已有那条走"藏旧的 + 我们补新的"，
            //      重复登记会在 JEI 内部多留一份同 id 的对象 ✗
            if (!p.removed && !p.hadOriginal && !p.registered) {
                final Object reg = runtimeAddable(p);
                if (reg != null && ShanhaiJeiRuntime.registerInJei(p.typeId, p.recipeId, reg)) {
                    p.registered = true;
                }
            }
            // 🔴🔴🔴 2026-10-06 用户抓到的【自己藏自己】（现场读数：
            //    `jei_runtime_registered total=1` 紧接着 `jei_runtime_hidden hidden=1`）：
            //    **新建的配方没有"旧的那份"可藏** —— 藏下去藏掉的正是我们刚登记进去的那一份
            //    ⇒ add 与 hide 互相抵消 ⇒ 用户完全看不到 ✗✗
            //    ⇒ 判据：`hadOriginal`（JEI 里本来就有这条 id 吗）为 false ⇒ **只 add、绝不 hide** ✓
            if (!p.removed && !p.hadOriginal) {
                // 🔴🔴 2026-10-06（用户："新建之后再改同一条 ⇒ JEI 里两份"）：
                //    **每一条新内容都先把"我们上一次加进去的那份"精确撤掉，再上新的那一个** ✓
                //    为什么不能只做一次：改同一条时 JEI 交出来的那份**正是我们上次加的**
                //    ⇒ 按 id 是判不出"谁是旧的"的 ⇒ 只能靠我们自己的实例表（不能猜）✓
                final Object reg = runtimeAddable(p);
                if (reg != null) {
                    boolean alreadyMine = false;
                    for (Object o : p.addedInstances) {
                        if (o == reg) {
                            alreadyMine = true;     // 同一份对象 ⇒ 不用重复登记
                            break;
                        }
                    }
                    if (!alreadyMine) {
                        if (!p.addedInstances.isEmpty()) {
                            ShanhaiJeiRuntime.hideGiven(p.typeId, p.addedInstances);
                            p.addedInstances.clear();
                        }
                        if (ShanhaiJeiRuntime.registerInJei(p.typeId, p.recipeId, reg)) {
                            p.addedInstances.add(reg);
                            p.registered = true;
                        }
                    }
                }
                p.runtimeApplied = false;           // 不走"藏 JEI 自己那份"这条路
                p.runtimePending = false;           // 也不再重试
                ShanhaiMod.LOGGER.info("{} jei_runtime_hide_skipped id={} reason=new_recipe_no_old_copy "
                                + "mine_now={}（★新建的配方在 JEI 里本来就没有旧的 ⇒ 只登记我们自己的那一份；"
                                + "改同一条时先把我们上一份精确撤掉 ⇒ 同 id 只会有一份）",
                        PREFIX, p.recipeId, p.addedInstances.size());
                return;
            }
            final boolean ok = ShanhaiJeiRuntime.hideOwnCopies(p.typeId, p.recipeId);
            p.runtimeApplied = ok;
            p.runtimePending = !ok;
            // 🔴 重试次数上限（P0 同一族）：藏不成（例如"本来就不在"）时不许每帧无限重试 ——
            //    每次重试都会去查一次 JEI 的表，虽然已由防重入闸挡住递归，但白查也是白烧。
            if (!ok && p.runtimeRetries >= MAX_RUNTIME_RETRIES) {
                p.runtimePending = false;
                ShanhaiMod.LOGGER.info("{} jei_runtime_hide_gave_up id={} retries={} "
                                + "（试够了就不再试；查询出口那层照样会给客户端正确结果）",
                        PREFIX, p.recipeId, p.runtimeRetries);
            }
            if (ok && p.runtimeRetries > 0) {
                ShanhaiMod.LOGGER.info("{} jei_runtime_hidden_after_retry id={} retries={} "
                                + "（收包那一刻 JEI 还没就绪，这次补上了）",
                        PREFIX, p.recipeId, p.runtimeRetries);
            }
        } catch (Throwable t) {
            p.runtimePending = true;
            ShanhaiMod.LOGGER.warn("{} jei_runtime_hook_failed id={} err={}（我们那层照样会处理它）",
                    PREFIX, p.recipeId, t.toString());
        }
    }

    /** "我们这层能不能把这条重建出来补进输出"（能 ⇒ 返回那一份对象，顺便缓存）。 */
    private static Object runtimeAddable(Patch p) {
        if (p == null) {
            return null;
        }
        if (p.synthAppended != null) {
            return p.synthAppended;
        }
        final Object made = buildRuntimeObject(p);
        if (made != null) {
            p.synthAppended = made;
        }
        return made;
    }

    /** 给 JEI 官方 API 用的那一份对象（非 GT ⇒ 原版 Recipe 本身；GT ⇒ GTRecipeWrapper）。 */
    private static Object buildRuntimeObject(Patch p) {
        if (p.recipeBytes == null || p.recipeBytes.length == 0) {
            return null;                      // 没字节 ⇒ 交给查询出口那条路（它知道怎么按 id 换）
        }
        return isVanillaBranch(p) ? decodeVanilla(p) : buildAppended(p);
    }

    /** 🔴 "用官方 API 藏一份"最多重试几次（P0 同一族：不许每帧无限重试）。 */
    private static final int MAX_RUNTIME_RETRIES = 3;

    /** 这份条件 JSON 里有几条（读不出来返回 -1，绝不假报成 0）。 */
    private static int condCount(String json) {
        if (json == null) {
            return -1;
        }
        try {
            final var el = JsonParser.parseString(json);
            return el != null && el.isJsonArray() ? el.getAsJsonArray().size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 补丁表空不空（空 ⇒ 注入点完全不动，开销为 0）。 */
    public static boolean isEmpty() {
        return PATCHES.isEmpty();
    }

    public static int size() {
        return PATCHES.size();
    }

    public static String statsLine() {
        return "patches=" + PATCHES.size();
    }

    // ------------------------------------------------------------------ 注入点出口

    /**
     * 把 JEI 交出来的那份列表按补丁表<b>过滤/替换/追加</b>。
     *
     * @param recipes       JEI 原本要显示的那一份（只读；我们<b>不</b>改它）
     * @param recipeTypeUid 当前分类的 RecipeType uid（用来判断补丁属不属于这一页）
     * @return 处理后的列表；<b>没有补丁时原样返回同一个对象</b>（等价于这条路径不存在）
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> patched(List<T> recipes, String recipeTypeUid) {
        if (PATCHES.isEmpty() || recipes == null || recipes.isEmpty()) {
            return recipes;
        }
        // 🔴🔴 2026-10-05 P0（用户："打 /shanhai edit restore 之后卡 1 秒，再点 JEI 的物品直接卡死"）：
        //    **防重入**。闭环是：
        //      hideOwnCopies → RecipeLookup.get → PluginManager.getRecipes（本 mixin 就在这）
        //      → patched → tryRuntimeHide → hideOwnCopies → … 无限递归 ⇒ 栈溢出 ⇒ 卡死。
        //    语义：**我正在用官方 API 查/改 JEI 这张表 ⇒ 期间本出口原样透传、一个补丁都不加工** ✓
        //    （这不是"少做一次"，而是"这期间压根不该做事"：那次查询只是我们查旧实例用的内部动作。）
        if (ShanhaiJeiRuntime.inOfficialCall()) {
            ShanhaiJeiRuntime.noteReentryBlocked();
            return recipes;
        }
        try {
            final List<T> out = new ArrayList<>(recipes.size() + 2);
            final Map<String, Boolean> seen = new LinkedHashMap<>();
            // 🔴 先补做"藏掉 JEI 自己那份"（收包那一刻 JEI 可能还没就绪 ⇒ 那时没成，这里再试）。
            //    ⚠️ 每帧都跑这个方法，所以这里只做几个布尔判断；成功一次就不再试。
            for (Patch p : PATCHES.values()) {
                if (!p.runtimeApplied && p.runtimePending) {
                    tryRuntimeHide(p);
                }
            }
            for (T r : recipes) {
                final String id = idOf(r);
                final Patch p = id == null ? null : PATCHES.get(id);
                // 🔴🔴 2026-10-05 第二轮纪律：**这一层的补丁永远生效，绝不因为"官方 API 也做过"而跳过**。
                //    上一轮我在这里加了 `if (p.runtimeApplied) { out.add(r); continue; }`
                //    ⇒ 结果是：官方 API 一旦返回成功，我们这层就撒手，而 hide 传错实例会静默无效
                //    ⇒ 用户看到的现象正是「改还能看、插和删不生效」（他自己总结的那条规律）。
                //    ⇒ 现在改成：**我们这层是唯一真源**（加/换/删都在这里做定），官方 API 只额外藏一份。
                if (p == null) {
                    out.add(r);
                    continue;
                }
                p.matched++;
                // 🔴🔴 2026-10-06（用户抓到的"自己藏自己"）：**"这条在 JEI 里本来就有旧的"必须在
                //    我们还没登记过它的时候判定** —— 登记之后 JEI 自己也会把它交出来，
                //    那时再判就会把"我们自己加进去的"误当成"原本就有的" ⇒ 又会去 hide 掉自己 ✗
                if (!p.registered) {
                    p.hadOriginal = true;
                }
                if (seen.put(id, Boolean.TRUE) != null) {
                    // 🔴 同一个完整 id 的第二条 ⇒ 丢掉（这就是"按完整 id 去重"）
                    p.hidden++;
                    continue;
                }
                if (p.removed) {
                    p.hidden++;
                    continue;
                }
                Object synth = p.synth;
                if (synth == null) {
                    synth = synthesize(r, p);
                    p.synth = synth;
                }
                if (synth == null) {
                    out.add(r);              // 造不出来 ⇒ 老老实实显示原件（不许凭空消失）
                    p.keptOriginal++;        // ⚠️ 这一格【不算 hidden】：原件还在输出里
                } else {
                    out.add((T) synth);
                    p.hidden++;              // 🔴 原件被我们替换掉了 = 它确实不在了
                    p.added++;               // 补上的是我们造的那一份
                }
            }
            // 🆕 修复②：把"本来就不在列表里"的那条【补进去】。
            //
            // 现场（用户实例 logs\latest.log 原文，逐字）：
            //   jei_sync matched=0 hidden=0 added=0 id=shanhai:worldline_probability_cracking/new_recipe_1 … visible_expected=0 expected=1 PASS=false
            //   jei_sync_ACCOUNTING_MISMATCH … visible=0 expected=1
            // ⇒ matched=0 就是"这条在 JEI 那一页里根本没有" ⇒ 上面那个循环一次都碰不到它。
            // 用户原话：「修改存在的配方 jei 是可以实时更新的，删除也可以，但是添加就不会了」。
            //
            // ⚠️ 只对【分类页】做（recipeTypeUid != null）。收藏夹那条通道进来的列表是
            //    "收藏的那一条"，往里补一条别的等于篡改用户的收藏 ⇒ 那里坚决不补。
            if (recipeTypeUid != null) {
                for (Patch p : PATCHES.values()) {
                    if (p.matched != 0 || p.removed) {
                        continue;
                    }
                    if (!recipeTypeUid.equals(p.typeId)) {
                        continue;
                    }
                    Object made = p.synthAppended;
                    if (made == null) {
                        made = buildAppended(p);
                        p.synthAppended = made;
                    }
                    if (made == null) {
                        continue;               // 造不出来 ⇒ 不补（并已在 buildAppended 里报过）
                    }
                    if (seen.containsKey(p.recipeId)) {
                        continue;               // 这一拍已经处理过这个 id（原件那条）
                    }
                    seen.put(p.recipeId, Boolean.TRUE);
                    p.added++;
                    out.add((T) made);
                    if (!p.appendLogged) {
                        p.appendLogged = true;
                        final ShanhaiJeiSyncPlan.Plan plan = ShanhaiJeiSyncPlan.of(p.matched, p.hidden, p.added);
                        ShanhaiMod.LOGGER.info("{} jei_sync APPENDED matched=0 hidden={} added={} id={} type={} "
                                        + "visible_expected={} expected=1 PASS={} channel=category "
                                        + "(这条是【新建的配方】：它本来就不在 JEI 那一页里 ⇒ 按 id 覆盖那条路走不到它，"
                                        + "只能把服务端编好的那一份补进去)",
                                PREFIX, p.hidden, p.added, p.recipeId, p.typeId,
                                plan.visibleAfter(), plan.unique());
                    }
                    // 🔴🔴 2026-10-05（用户给的"一刀切开"判别法）：**每次查询都记一笔"确实交出去了"**（前 10 次）。
                    //    为什么必须逐次记、不能只记一次：用户的操作是【新建 → 关掉 JEI → 再打开 → 看那条在不在】
                    //    ⇒ 关掉再打开之后 times 还在涨 ⇒ **数据层已经交出去了** ⇒ 缺口 100% 在【界面层】；
                    //      关掉再打开之后 times 不再涨 ⇒ **数据层就没交出去** ⇒ 回去查出口那一段。
                    //    这比"我觉得应该是界面问题"硬得多，而且直接决定下一步往哪挖。
                    p.deliveredTimes++;
                    if (p.deliveredTimes <= 10) {
                        ShanhaiMod.LOGGER.info("{} jei_append_delivered times={} id={} type={} in={} out={} "
                                        + "（★一刀切开的数据层判据：关掉 JEI 再打开后这行还在涨 = 数据层已交出 ⇒ "
                                        + "不显示是【界面层】；不再涨 = 数据层没交出去）",
                                PREFIX, p.deliveredTimes, p.recipeId, p.typeId, recipes.size(), out.size());
                    }
                }
            }
            logOnce(recipeTypeUid);
            return out;
        } catch (Throwable t) {
            // 🔴 补丁出任何问题都只降级为"原样显示"，绝不把 JEI 弄崩
            ShanhaiMod.LOGGER.error("{} jei_patch_failed uid={} err={} (降级为原列表)", PREFIX, recipeTypeUid, t.toString(), t);
            return recipes;
        }
    }

    /** 每一条补丁（属于这一页的）打一行判据日志，只打一次。 */
    private static void logOnce(String uid) {
        for (Patch p : PATCHES.values()) {
            if (p.logged || uid == null || !uid.equals(p.typeId)) {
                continue;
            }
            p.logged = true;
            final ShanhaiJeiSyncPlan.Plan plan = ShanhaiJeiSyncPlan.of(p.matched, p.hidden, p.added);
            final boolean ok = p.removed ? plan.gone() : plan.unique();
            ShanhaiMod.LOGGER.info("{} jei_sync matched={} hidden={} added={} kept_original={} id={} type={} "
                            + "dedupe=ok_by_full_recipe_id visible_expected={} expected={} PASS={} channel=category "
                            + "(三个计数器各自独立：matched − hidden + added 才是可见条数)",
                    PREFIX, p.matched, p.hidden, p.added, p.keptOriginal, p.recipeId, p.typeId,
                    plan.visibleAfter(), p.removed ? 0 : 1, ok);
            if (!ok) {
                ShanhaiMod.LOGGER.error("{} jei_sync_ACCOUNTING_MISMATCH id={} matched={} hidden={} added={} "
                                + "visible={} expected={} ⇒ 补丁的记账与期望不符（这一行是判红的）",
                        PREFIX, p.recipeId, p.matched, p.hidden, p.added, plan.visibleAfter(),
                        p.removed ? 0 : 1);
            }
        }
    }

    // ---------------------------------------------------------------- ② 收藏夹那条通道

    /**
     * <b>收藏夹通道</b>的补丁入口（用户点单的 E3）。
     *
     * <h4>为什么收藏夹需要单独一个入口</h4>
     * 分类页走的是 {@code PluginManager#getRecipes}（本类 {@link #patched} 已覆盖），
     * 而<b>收藏夹</b>走的是另一条：{@code mezz.jei.gui.bookmarks.RecipeBookmark} 在收藏那一刻
     * <b>把配方对象本身存下来了</b>（构造器签名 {@code (IRecipeCategory, Object recipe, …)}，
     * 字节码实测），点它的时候调的是
     * {@code IRecipesGui#showRecipes(category, List.of(bookmark.getRecipe()), focuses)}
     * ⇒ <b>根本不经过 {@code getRecipes}</b>。所以分类页刷新了、收藏夹仍然显示旧值
     * （用户原话：「通过收藏的通道查看这个配方，那它是不会刷新的」）。
     * <p>本方法挂在 {@code mezz.jei.gui.recipes.RecipesGui#showRecipes} 上（见
     * {@code ShanhaiJeiBookmarkMixin}），按<b>完整 recipe id</b> 把那份列表换成补丁后的版本。
     * <p>⚠️ 这一条<b>本机验不了</b>（红线禁止开客户端）⇒ 判据只能是"这行日志有没有出现"，
     * 报告里如实标成"只能他进游戏看"。
     */
    public static <T> List<T> patchedForBookmark(List<T> recipes) {
        if (PATCHES.isEmpty() || recipes == null || recipes.isEmpty()) {
            return recipes;
        }
        final List<T> out = patched(recipes, null);
        // 🔴🔴 2026-10-05 闪退修复：**空列表绝不许往下传**。
        //
        // 现场（用户实例 crash-reports\crash-2026-10-05_17.22.21-client.txt，原文）：
        //   java.lang.IllegalArgumentException: recipes must not be empty.
        //     at mezz.jei.common.util.ErrorUtil.checkNotEmpty(ErrorUtil.java:113)
        //     at mezz.jei.gui.recipes.RecipesGui.showRecipes(RecipesGui.java:496)
        //        {pl:mixin:APP:shanhai.mixin.json:ShanhaiJeiBookmarkMixin}
        //     at mezz.jei.gui.overlay.elements.RecipeBookmarkElement.show(...)
        // 根因：用户点的是收藏夹里【一条已经被删掉的配方】。收藏夹这条通道进来的是
        //   List.of(bookmark.getRecipe()) —— 恰好 1 条；而补丁表里它是 action=removed
        //   ⇒ patched() 把它丢掉 ⇒ 返回空列表 ⇒ JEI 的 checkNotEmpty 抛异常 ⇒ 游戏闪退。
        //
        // 修法（用户拍板："列表为空 ⇒ 根本不介入，让它走 JEI 原路"）：
        //   还回原件。语义取舍也写清楚 —— 收藏夹里会继续显示那条**已被删除**的配方；
        //   这是刻意的：显示一条过期数据远好于整局游戏崩掉。而且它**不静默**：
        //   这里打一条可 grep 的 WARN，并给玩家一次可见提示。
        if (ShanhaiJeiSyncPlan.mustKeepOriginal(recipes.size(), out == null ? 0 : out.size())) {
            logEmptyFallback(recipes);
            return recipes;
        }
        int matched = 0;
        int hidden = 0;
        int added = 0;
        String id = null;
        for (Patch p : PATCHES.values()) {
            matched += p.matched;
            hidden += p.hidden;
            added += p.added;
            if (id == null) {
                id = p.recipeId;
            }
        }
        if (out != recipes && !loggedBookmarkOnce()) {
            final ShanhaiJeiSyncPlan.Plan plan = ShanhaiJeiSyncPlan.of(matched, hidden, added);
            ShanhaiMod.LOGGER.info("{} jei_bookmark_sync in={} out={} matched={} hidden={} added={} "
                            + "visible_expected={} id_sample={} channel=favorites "
                            + "(收藏夹那条通道被命中了 ⇒ 收藏夹也会显示改过的配方)",
                    PREFIX, recipes.size(), out.size(), matched, hidden, added, plan.visibleAfter(), id);
        }
        return out;
    }

    /**
     * 🆕 修复②：<b>把"本来就不在 JEI 那一页里"的那条造出来</b>（新建配方那一拍）。
     *
     * <h4>为什么要单独一条路，而不是复用 {@link #synthesize}</h4>
     * {@code synthesize} 的底本是 <b>JEI 手里那条原件</b>；而这一支的前提恰恰是
     * <b>没有原件</b>（{@code matched == 0}）。所以底本只能来自服务端：
     * 包里带着 {@code GTRecipeSerializer.SERIALIZER.toNetwork} 编出来的字节，
     * 这里解回一个 {@link GTRecipe} 再包成 {@link GTRecipeWrapper}。
     *
     * <p>⚠️ 边界，如实写：<b>服务端与客户端必须是同一份 GT 版本</b>
     * （本来就是这样 —— 同一个 mod 包），否则反序列化会抛；抛了就<b>不补</b>并打 ERROR，
     * 绝不留一条半截的配方在 JEI 里。
     */
    private static Object buildAppended(Patch p) {
        if (p.recipeBytes == null || p.recipeBytes.length == 0) {
            ShanhaiMod.LOGGER.warn("{} jei_append_skipped id={} reason=no_recipe_bytes "
                            + "(服务端没能把这条配方编成字节 ⇒ JEI 里这一条要等重载才会出现，"
                            + "但日志上已经有 net_encode_recipe_failed 交代原因)",
                    PREFIX, p.recipeId);
            return null;
        }
        // 🆕 本轮：非 GT（原版）那一支 —— 解出来就是 JEI 原版分类要的那个 Recipe 对象本身。
        if (isVanillaBranch(p)) {
            final Object made = decodeVanilla(p);
            if (made != null) {
                ShanhaiMod.LOGGER.info("{} jei_append_built kind=vanilla id={} type={} bytes={} class={}",
                        PREFIX, p.recipeId, p.typeId, p.recipeBytes.length, made.getClass().getSimpleName());
            }
            return made;
        }
        try {
            final net.minecraft.resources.ResourceLocation id =
                    net.minecraft.resources.ResourceLocation.tryParse(p.recipeId);
            if (id == null) {
                ShanhaiMod.LOGGER.error("{} jei_append_bad_id id={}", PREFIX, p.recipeId);
                return null;
            }
            final io.netty.buffer.ByteBuf bb = io.netty.buffer.Unpooled.wrappedBuffer(p.recipeBytes);
            final net.minecraft.network.FriendlyByteBuf in = new net.minecraft.network.FriendlyByteBuf(bb);
            final GTRecipe r = com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer.SERIALIZER
                    .fromNetwork(id, in);
            if (r == null) {
                ShanhaiMod.LOGGER.error("{} jei_append_decode_null id={}", PREFIX, p.recipeId);
                return null;
            }
            if (r.id == null) {
                r.id = id;
            }
            ShanhaiMod.LOGGER.info("{} jei_append_built id={} type={} bytes={} class={}",
                    PREFIX, p.recipeId, p.typeId, p.recipeBytes.length, r.getClass().getSimpleName());
            return new GTRecipeWrapper(r);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} jei_append_build_failed id={} err={}", PREFIX, p.recipeId, t.toString(), t);
            return null;
        }
    }

    /** 这条补丁是"非 GT（原版）"那一支吗（判据 = 载荷里有原版那一份字段）。 */
    private static boolean isVanillaBranch(Patch p) {
        return p != null && p.vanillaJson != null;
    }

    /**
     * 🆕 本轮：<b>把服务端带来的原版配方字节解回一条真正的 {@code Recipe}</b>。
     *
     * <p>用的是原版自己那一对：服务端 {@code ClientboundUpdateRecipesPacket.toNetwork}，
     * 客户端 {@code ClientboundUpdateRecipesPacket.fromNetwork} —— 严格对称，
     * 也就是原版每次进世界推整表时用的同一对函数 ⇒ 解不出来的可能性极低；
     * 真解不出来就返回 {@code null} 并打 ERROR（<b>不补</b>，绝不放一条半截的进 JEI）。
     *
     * <p>⚠️ 解出来的对象<b>不进客户端自己的配方表</b>（那是另一条线）：JEI 原版分类里放的
     * 就是这个 {@code Recipe} 对象本身（{@code idOf} 里有字节码取证），所以我们直接把它
     * 交给 JEI 的注入点就够了。
     */
    private static Object decodeVanilla(Patch p) {
        try {
            final io.netty.buffer.ByteBuf bb = io.netty.buffer.Unpooled.wrappedBuffer(p.recipeBytes);
            final net.minecraft.network.FriendlyByteBuf in = new net.minecraft.network.FriendlyByteBuf(bb);
            final net.minecraft.world.item.crafting.Recipe<?> r =
                    net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket.fromNetwork(in);
            if (r == null) {
                ShanhaiMod.LOGGER.error("{} jei_vanilla_decode_null id={}", PREFIX, p.recipeId);
                return null;
            }
            return r;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} jei_vanilla_decode_failed id={} bytes={} err={}",
                    PREFIX, p.recipeId, p.recipeBytes == null ? 0 : p.recipeBytes.length, t.toString(), t);
            return null;
        }
    }

    /**
     * 「补丁把收藏夹那条丢光了」这一拍的<b>可见痕迹</b>（闪退修复①的配套）。
     *
     * <p>它<b>不是</b>一条静默的降级：日志里有可 grep 的 WARN（每条 recipe id 只打一次），
     * 玩家在游戏里也会看到一句提示（同样每条 id 只提示一次，不会刷屏）。
     * 判据（那条算术）在 {@code ShanhaiJeiSyncPlan.mustKeepOriginal} 上，自检里跑正负对照。
     */
    private static void logEmptyFallback(List<?> recipes) {
        String culprit = null;
        Patch hit = null;
        for (Object r : recipes) {
            final String id = idOf(r);
            if (id == null) {
                continue;
            }
            final Patch p = PATCHES.get(id);
            if (p != null) {
                culprit = id;
                hit = p;
                break;
            }
        }
        final String key = String.valueOf(culprit);
        if (EMPTY_FALLBACK_LOGGED.add(key)) {
            ShanhaiMod.LOGGER.warn("{} jei_bookmark_kept_original id={} patches={} "
                            + "→ 这条收藏的配方在我们的补丁表里是【已删除】({})，"
                            + "而 JEI 的 showRecipes 要求列表非空（空的会抛 IllegalArgumentException 直接闪退）"
                            + "⇒ 本次【不接管】，原样显示 JEI 收藏夹里那一份（数据是旧的，但游戏不会崩）。"
                            + "修复动作：把这条从收藏夹里删掉（它对应的配方已经不存在了）。",
                    PREFIX, culprit, PATCHES.size(), hit == null ? "?" : "action=" + hit.typeId);
        }
        // 玩家可见的一次性提示（每条 id 只提示一次；玩家不在线就只留日志）
        if (EMPTY_FALLBACK_NOTIFIED.add(key)) {
            try {
                final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.player != null) {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§e[JEI 收藏夹] 这条配方已经被删掉了，面板不再接管它；"
                                    + "请把这条收藏删掉（旧数据不会崩游戏，只是显示过期）"), false);
                }
            } catch (Throwable ignored) {
                // 提示失败没关系：日志那条 WARN 已经把事实交代清楚了
            }
        }
    }

    /** 「空列表兜底」那件事的日志只打一次 / 每 id 一次（避免每帧刷屏）。 */
    private static final java.util.Set<String> EMPTY_FALLBACK_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> EMPTY_FALLBACK_NOTIFIED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean loggedBookmarkOnce() {
        for (Patch p : PATCHES.values()) {
            if (!p.bookmarkLogged) {
                p.bookmarkLogged = true;
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- ③ 客户端补丁表的对账

    /**
     * <b>服务端说"这几条才是在生效的"</b> ⇒ 客户端把不在名单里的补丁全部丢掉。
     *
     * <h4>这条修的是哪个症状</h4>
     * 用户原话：「一重进存档就 KJS 报错（覆盖层不套用）／机器跑的是老配方／<b>但 JEI 里还看得见改过的</b>」
     * —— 第三条是<b>客户端内存里的补丁表还在</b>（重进存档不重开客户端）造成的假象：
     * 服务端那份覆盖层判了 MISSING（没套用），客户端却还在按补丁表显示。
     * ⇒ 每次开面板时服务端把"文件里在生效的条目 id"发下来，客户端据此对账：
     * <b>不在名单里的一律清掉</b>，两边就不可能再不一致。
     */
    public static void reconcile(java.util.Collection<String> inForce) {
        final java.util.Set<String> keep = new java.util.HashSet<>(inForce == null ? List.of() : inForce);
        int dropped = 0;
        int kept = 0;
        int unhidden = 0;
        for (String id : new ArrayList<>(PATCHES.keySet())) {
            if (keep.contains(id)) {
                kept++;
                continue;
            }
            final Patch gone = PATCHES.remove(id);
            dropped++;
            ShanhaiMod.LOGGER.info("{} jei_patch_dropped id={} reason=not_in_force (服务端这次启动没套用它)", PREFIX, id);
            // 🔴🔴 2026-10-06（用户现场："整个类型在 JEI 里没了，包括其他配方"，时间窗正好落在
            //    `jei_reconcile in_force=0 kept=0 dropped=3 patches_now=0` 那一段）：
            //    **丢补丁时必须把"之前用官方 API 藏掉的那份还回去"**。
            //    为什么：hide 是往 JEI 的 hidden 集合里塞实例，**它不会因为我们丢补丁而自动消失** ✗
            //    ⇒ 只藏不还 = 那条配方从此永远看不见（用户看到的正是"不见了"）✓
            //    ⚠️ 这也解释了他那句"整个类型看着不对"：被藏的若是底本原有的那条
            //      （`test/zero_point_conversion` 就是底本原有的），少一条看上去就像整页不对 ✓
            if (gone != null && gone.runtimeApplied) {
                try {
                    if (ShanhaiJeiRuntime.unhideOwnCopies(gone.typeId, gone.recipeId)) {
                        unhidden++;
                    }
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.warn("{} jei_unhide_on_drop_failed id={} err={}", PREFIX, id, t.toString());
                }
            }
        }
        ShanhaiMod.LOGGER.info("{} jei_reconcile in_force={} kept={} dropped={} unhidden={} patches_now={}",
                PREFIX, keep.size(), kept, dropped, unhidden, PATCHES.size());
    }

    /** 一条 JEI 配方元素的完整 recipe id（认不出来 = null ⇒ 不参与补丁）。 */
    public static String idOf(Object recipe) {
        if (recipe instanceof GTRecipeWrapper w && w.recipe != null && w.recipe.id != null) {
            return w.recipe.id.toString();
        }
        // 🆕 2026-10-05（工作台 / 原版配方）：<b>JEI 原版分类里放的就是那个 Recipe 对象本身</b>
        //    （javap 实证：mezz.jei.library.plugins.vanilla.crafting.VanillaRecipes 直接拿
        //     客户端自己的 RecipeManager.byType(type).values()，没有再包一层 wrapper）
        //    ⇒ 这里加一条通用分支就够了。
        if (recipe instanceof net.minecraft.world.item.crafting.Recipe<?> r && r.getId() != null) {
            return r.getId().toString();
        }
        return null;
    }

    /**
     * 用 {@code original}（<b>JEI 原本那条</b>，它身上的 {@code recipe} 就是客户端那份真 GTRecipe）
     * 造一份贴好补丁的新包装。
     *
     * <p>🔴 底本必须取原件，不能取我们上一次造的那一份 —— 否则第二次改动会叠在第一次的结果上，
     * 而他看到的"恢复原样"就再也回不去了。
     *
     * <h4>🔴 2026-10-05 第 5 轮修 #4：这里原来<b>完全没碰 conditions</b></h4>
     * 用户原话（逐字）：
     * <blockquote>「JEI 没生效指的是jei里面没有写新条件，我删除条件了jei里面也没删除，
     * jei一直显示的是老条件」</blockquote>
     * 读数与根因：这条包原来只带 inputs/outputs/duration/eut，
     * 而本方法造替换品时也<b>只贴这四样</b> ⇒
     * <b>条件是原件自带的那些（= 老条件）</b>，加了新条件看不出来、删了也还在
     * —— 与他描述的三个现象逐条对上。
     * <p>修法 = 载荷带 {@code conditionsJson}（GT 平铺形状，由服务端 {@code RecipeCondition.CODEC} 编出来），
     * 这里用 {@code ShanhaiRecipeConditions.decodeList} 解回条件对象再<b>整段替换</b>副本的
     * {@code conditions}（{@code copy()} 给的是新 ArrayList，换掉不会碰原件，见 {@code ShanhaiRecipeBase} 的实测注释）。
     * <p>⚠️ 解不出来时<b>不贴</b>并打 ERROR（宁可显示老条件，也不凭空造一条机器读不懂的条件）。
     */
    private static Object synthesize(Object original, Patch p) {
        // 🆕 2026-10-05（工作台 / 原版配方）：非 GT 那一支。
        //    🔴 底本同样必须取【JEI 手里那条原件】，不能取我们上一次造的那一份
        //       （否则第二次改动会叠在第一次的结果上，"恢复原样"再也回不去）。
        if (p.vanillaJson != null) {
            // 🆕 本轮：**优先用服务端带过来的整条字节**。
            //    为什么要改：`synthesizeVanilla` 只能贴 result/cookingtime/experience 三样，
            //    "只改了输入"的那次编辑在 JEI 上仍然显示老材料（用户那句「JEI 不显示」的另一种形态）。
            //    字节那一份是**服务端刚保存的那条配方本身** ⇒ 输入/产物/数量一个不差。
            if (p.recipeBytes != null && p.recipeBytes.length > 0) {
                final Object made = decodeVanilla(p);
                if (made != null) {
                    ShanhaiMod.LOGGER.info("{} jei_vanilla_synth_from_bytes id={} jei_uid={} class={} "
                                    + "（客户端这一份就是 JEI 上要显示的那一条；输入也一起换了）",
                            PREFIX, p.recipeId, p.typeId, made.getClass().getSimpleName());
                    return made;
                }
                ShanhaiMod.LOGGER.warn("{} jei_vanilla_bytes_undecodable id={} -> 退回按字段重建",
                        PREFIX, p.recipeId);
            }
            return synthesizeVanilla(original, p);
        }
        if (!(original instanceof GTRecipeWrapper w) || w.recipe == null) {
            return null;
        }
        try {
            final GTRecipe base = w.recipe;
            final GTRecipe fresh = base.copy();
            // 🔴 P0 修法：copy() 里的 data 是【共享引用】，不 copy 会污染原件（运行期实测过 7→9999）
            fresh.data = base.data.copy();

            if (p.inputsJson != null) {
                ShanhaiRecipeIoApply.applyTable(fresh, "inputs", parse(p.inputsJson));
            }
            if (p.outputsJson != null) {
                ShanhaiRecipeIoApply.applyTable(fresh, "outputs", parse(p.outputsJson));
            }
            if (p.conditionsJson != null) {
                final com.google.gson.JsonElement el = JsonParser.parseString(p.conditionsJson);
                final java.util.List<com.gregtechceu.gtceu.api.recipe.RecipeCondition> list =
                        ShanhaiRecipeConditions.decodeList(el);
                if (list == null) {
                    ShanhaiMod.LOGGER.error("{} jei_patch_conditions_undecodable id={} json={} "
                                    + "-> 这一条【不贴条件】，JEI 上仍然显示原件那份（宁可显示老的，也不造假）",
                            PREFIX, p.recipeId, p.conditionsJson);
                } else {
                    fresh.conditions.clear();
                    fresh.conditions.addAll(list);
                }
            }
            if (p.hasDuration) {
                fresh.duration = p.duration;
            }
            if (p.hasEut) {
                ShanhaiRecipeIoApply.applyEut(fresh, p.eut);
            }
            ShanhaiMod.LOGGER.info("{} jei_patch_synth id={} cond_n={} cond_types={} dur={} eu={} "
                            + "(客户端这一份就是 JEI 上要显示的那一条)",
                    PREFIX, p.recipeId, fresh.conditions == null ? -1 : fresh.conditions.size(),
                    fresh.conditions == null ? "[]" : String.valueOf(fresh.conditions.size()),
                    fresh.duration, ShanhaiRecipeIoApply.euOf(fresh));
            return new GTRecipeWrapper(fresh);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} jei_patch_synthesize_failed id={} err={}", PREFIX, p.recipeId, t.toString(), t);
            return null;
        }
    }

    private static JsonObject parse(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /**
     * 🆕 2026-10-05（工作台 / 原版配方）：<b>原版那一支的替代品</b>。
     *
     * <h4>做的是什么</h4>
     * JEI 原版分类里那个元素<b>就是 {@code net.minecraft…Recipe} 对象本身</b>（javap 实证，
     * 见 {@code idOf} 的注释）⇒ 把载荷里的 {@code result} / {@code cookingtime} / {@code experience}
     * 解出来，交给 {@code ShanhaiVanillaRecipeRebuild} 用原版 public 构造器
     * <b>重建一条同类新实例</b>，直接把新对象还给 JEI。
     *
     * <h4>🔴 为什么不自己在客户端改字段 / 写包装</h4>
     * ① 原版配方字段全 {@code final}，就地改不了；② 写包装会被别的 mod 的 {@code instanceof} 判掉
     * （Polymorph / KubeJS）。重建同类实例两个坑都没有。
     *
     * <h4>⚠️ 代价（如实交代，别当没有）</h4>
     * 这个注入点 {@code PluginManager#getRecipes} <b>每次查询都会跑到</b>
     * ⇒ 这里只允许"O(1) 解一小段 JSON + new 一个对象"，<b>绝不做全表遍历</b>。
     * 重建结果按补丁缓存（{@code p.synth}），同一条配方在一次会话里最多造一遍。
     */
    private static Object synthesizeVanilla(Object original, Patch p) {
        if (!(original instanceof net.minecraft.world.item.crafting.Recipe<?> base)) {
            ShanhaiMod.LOGGER.error("{} jei_vanilla_synth_type_mismatch id={} class={} -> 显示原件",
                    PREFIX, p.recipeId, original == null ? "null" : original.getClass().getName());
            return null;
        }
        try {
            final JsonObject f = JsonParser.parseString(p.vanillaJson).getAsJsonObject();
            final net.minecraft.world.item.ItemStack result = f.has("result")
                    ? com.shanhai.common.recipe.editor.ShanhaiVanillaRecipeOps
                    .parseResult(f.getAsJsonObject("result"))
                    : null;
            final Integer cookTime = f.has("cookingtime") ? f.get("cookingtime").getAsInt() : null;
            final Double xp = f.has("experience") ? f.get("experience").getAsDouble() : null;
            final net.minecraft.world.item.crafting.Recipe<?> fresh =
                    com.shanhai.common.recipe.editor.ShanhaiVanillaRecipeRebuild
                            .rebuild(base, result, cookTime, xp);
            if (fresh == null) {
                ShanhaiMod.LOGGER.error("{} jei_vanilla_synth_failed id={} -> 显示原件（宁可显示老的，也不造假）",
                        PREFIX, p.recipeId);
                return null;
            }
            ShanhaiMod.LOGGER.info("{} jei_vanilla_synth id={} jei_uid={} class={} "
                            + "（客户端这一份就是 JEI 上要显示的那一条）",
                    PREFIX, p.recipeId, p.typeId, fresh.getClass().getSimpleName());
            return fresh;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} jei_vanilla_synth_threw id={} err={}", PREFIX, p.recipeId, t.toString(), t);
            return null;
        }
    }
}
