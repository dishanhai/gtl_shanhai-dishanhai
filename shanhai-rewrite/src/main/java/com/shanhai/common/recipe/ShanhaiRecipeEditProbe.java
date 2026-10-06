package com.shanhai.common.recipe;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.lookup.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.event.server.ServerStartedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 「配方动态更新编辑器」的<b>地基探针</b> ——
 * 证明<b>「运行期改一条已注册配方 ⇒ 不重启、不重跑 KJS、不重载资源包，立刻生效」这条路成立</b>，
 * 并把它变成<b>一行行可 grep、可判真假</b>的日志读数。
 *
 * <h2>1. 这条路是什么（不是猜的，是从老 jar 的真实实现里抄出来的机制）</h2>
 * 老山海那套在 {@code 山海_配方控制API.js} 第 1923-1945 行写的是：
 * <pre>
 *   global.shanhaiRecipeLayerDirty = true;                       // 配方层缓存失效
 *   javaAPI.removeAndSync(recipe.type, fullId);                  // ① 从 GTCEu 索引里摘掉旧的那条
 *   var m = global._shanhaiGTR[recipe.type](fullId);
 *   DShanhaiRecipeEngine.applyAll(m, recipe);  m.save();         // ② 用新数据重建并重新注册
 * </pre>
 * 反编译 {@code gt_shanhai.jar} 得到的一手证据（本类注释 §6 给了逐条取证命令）：
 * <ul>
 *   <li>{@code DShanhaiRecipeModifierAPI.removeAndSync}（:2148-2191）实际做的是
 *       <b>{@code branch.getRecipes(true)} 收全量 → 去掉目标 → {@code lookup.removeAllRecipes()} →
 *       逐条 {@code lookup.addRecipe(r)} → {@code invalidatePatternCaches(...)} → {@code RecipeSyncPacket.syncToAll()}</b>；</li>
 *   <li>也就是说：<b>「即时」不是靠某种魔法缓存失效，而是靠【把整张输入索引重建一遍】</b>
 *       —— 索引是<b>从当前这些配方对象现推出来</b>的，对象一换，索引跟着换，下一个 tick 的机器立刻查到新值。</li>
 * </ul>
 *
 * <h2>2. 🔴 「即时」要动哪几层 —— 本工程的核实结论（每一层都带证据）</h2>
 * <h3>① 配方管理器按输入/输出的查找索引 ⇒ <b>必须动，且这就是「即时」的唯一硬要求</b></h3>
 * GTCEu 的输入索引是 {@code GTRecipeType.getLookup()} 返回的那个 {@code GTRecipeLookup}，
 * 内部是一棵 {@code Branch} 树（{@code nodes}/{@code specialNodes} 两张 {@code Object2ObjectOpenHashMap}）。
 * <b>关键性质（反编译 {@code gtceu-1.20.1-1.4.4.jar} 得）</b>：
 * <pre>
 *   GTRecipeLookup.fromRecipe(recipe)      // 索引的键【完全】从 recipe.inputs + recipe.tickInputs 现推
 *   GTRecipeLookup.removeAllRecipes()      // = lookup.getNodes().clear(); lookup.getSpecialNodes().clear();
 *   GTRecipeLookup.addRecipe(recipe)       // = fromRecipe(recipe) → recurseIngredientTreeAdd(...)
 * </pre>
 * ⇒ 树里存的是<b>配方对象本身</b>（{@code Either.left(recipe)}），键是当场算出来的。
 * ⇒ <b>换掉对象 = 换掉索引里的值</b>；不重建 = 索引继续交回老对象。
 * <p>
 * ⚠️ <b>另一条同样重要的事实</b>：{@code RecipeManager.getRecipesFor(...)} 走的是<b>另一条路</b>
 * —— 它对 {@code byType} 里的 {@code ImmutableList} <b>线性扫</b>（1.20.1 的 {@code byType} 是 private），
 * <b>根本没有输入索引</b>。所以"按输入查"在 GT 机器上真正走的只有 {@code GTRecipeLookup}。
 * 两个桶的关系（反编译 {@code com.gregtechceu.gtceu.core.mixins.RecipeManagerMixin} 得）：
 * <pre>
 *   RecipeManager.apply(...) 的 TAIL：
 *     gtRecipeType.getLookup().removeAllRecipes();
 *     f_44007_.get(gtRecipeType).values() ... .forEach(r -> gtRecipeType.getLookup().addRecipe(r));
 * </pre>
 * ⇒ <b>GTRecipeLookup 是从「原版配方表 {@code byType}」派生出来的索引，每次配方重载都会被重建。</b>
 * 这条推论有直接后果，本类用 {@code by=manager} 那条读数把它<b>实测出来</b>：
 * <b>只改索引、不改 {@code byType} ⇒ 一次 {@code /reload} 会把这次修改整个抹掉</b>。
 * (⇒ 要"持久且即时"，得把 {@code byType} 那一份也换掉，或改完立刻重放。本类只证明"即时"，
 * 见类注释 §5 的"还没验到的那部分"。)
 *
 * <h3>② 正在跑该配方的机器缓存 ⇒ <b>老那套【没覆盖】，本类也没做</b></h3>
 * 机器侧真正持有配方对象的是 GTCEu 的 {@code RecipeLogic}（{@code lastRecipe} 字段，
 * 命中 {@code matchRecipe} 就复用，不再回索引查）。老 {@code gt_shanhai.jar} 全库
 * <b>没有任何一处碰 {@code RecipeLogic}</b>（取证：反编译产物里 {@code grep -i RecipeLogic} 命中 0），
 * 它只失效"样板总成"那一层的缓存（见 ③）。
 * ⇒ <b>对"正在跑这条配方的机器"：这次改动的生效时机是"它这一轮跑完之后"</b>，不是当场打断。
 * 这是老那套的真实边界，本类如实照记。
 *
 * <h3>③ AE / 样板总成的样板缓存 ⇒ <b>老那套覆盖了（走 revision 号）</b></h3>
 * <pre>
 *   DShanhaiRecipeModifierAPI.invalidatePatternCaches(reason)   // :1591-1611
 *     → DShanhaiRuntimeRecipeCache.clear()                      // 清 GTRecipeLookup 结果的缓存
 *     → PATTERN_CACHE_REVISION.incrementAndGet()                 // 全局 rev++
 *     → 对每个登记过的 owner：clearPatternCacheOwner(owner)
 *         · 反射清 cacheRecipe(boolean[]) / recipeMultipleCacheMap
 *         · 反射调 refreshAllByProduct()
 *         · 把 owner 上的 gtShanhai$recipeCacheRevision 同步成当前 rev
 *   MEPatternBufferRecipeCacheRevisionMixin.hasRecipeCacheInSlot(HEAD)：
 *     owner 自己的 rev != 全局 rev ⇒ invalidatePatternCacheOwner(this, "revision-check:"+slot)
 * </pre>
 * ⇒ 老那套的"即时"在这里是<b>靠一个全局版本号 + 两个 mixin 钩子</b>实现的，不是靠遍历。
 * 本工程<b>没有</b>这一步（我们没有 gtlcore 样板总成的 rev 钩子）⇒ 见 §5。
 *
 * <h3>④ JEI 配方列表 ⇒ <b>老那套只在【客户端】覆盖，且靠"两边各自重算"而不是靠传数据</b></h3>
 * <pre>
 *   JEIRecipeListMixin  @Redirect GTRecipeTypeCategory.registerRecipes → addRecipes
 *       每条 wrapper 都 recipe.copy() 后 applyStripByType/applyReplaceByType 再交给 JEI
 *   RecipeSyncPacket.encode(...) 是【空的】，decode 返回 new RecipeSyncPacket()
 *       handle(...) 在客户端拿【客户端自己那份】GTRecipeLookup 重算一遍，然后
 *       recipeManager.hideRecipes(old) + addRecipes(new)
 * </pre>
 * 🔴 ⇒ <b>这个包一个字节的数据都不带</b>。它成立的前提是"客户端能从自己手里那份配方，
 * 用同一张静态规则表（strip/replace 规则）算出同一个结果"。
 * ⇒ 对<b>按规则整体改写</b>（老那套的主用法）成立；对<b>只改了服务端一条配方的字段</b>（本类做的这件事）
 * <b>不成立</b> —— 客户端手里那份根本没变，重算出来还是老值。
 *
 * <h3>⑤ 客户端那份配方表 ⇒ <b>老那套【没覆盖】（本工程也没做）</b></h3>
 * 客户端表由原版 {@code ClientboundUpdateRecipesPacket} → {@code ClientPacketListener.handleUpdateRecipes}
 * → 客户端 {@code RecipeManager} 填。老 jar 里<b>没有任何地方重发这个包</b>
 * （取证：反编译产物里 {@code grep -n 'UpdateRecipes\|handleUpdateRecipes'} 命中 0），
 * {@code RecipeSyncPacket} 只碰 JEI 的 {@code IRecipeManager}。
 * ⇒ 所以：<b>"客户端那份配方表"这一层，老那套压根没管</b>。
 *
 * <h2>3. 本类做的最小实现（就是 ① 那一层）</h2>
 * 一次「编辑」= <b>造一个新对象（{@code copy()} 之后改 {@code duration}）→ 把索引里那条换成它 →
 * 立刻用两条路读回来</b>：
 * <ol>
 *   <li>{@code by=id}：把索引树里所有配方捞出来，按 id 找 → 读 {@code duration}；</li>
 *   <li>{@code by=input}：<b>按输入查</b> —— 用目标配方自己的输入造一个合成
 *       {@link IRecipeCapabilityHolder}，走 {@code GTRecipeLookup.find(holder, predicate)}
 *       <b>真正从树根往下走一遍</b>，看它交回来的是哪个对象、值是多少。</li>
 * </ol>
 * 外加一条<b>不同口径的旁证</b> {@code by=manager}：从服务器 {@code RecipeManager.getRecipes()} 读同一个 id。
 * <b>它【不计入 PASS/FAIL】</b> —— 它是一个"另一份拷贝有没有变"的读数，不是这件事的判据。
 *
 * <h2>4. 🔴 判据（{@code selftest} 的 PASS 条件，逐条可机器判）</h2>
 * 自检跑 6 拍，全部打在日志里（{@link #PREFIX} 前缀）：
 * <pre>
 *   case=baseline                 : 改之前 → by=id / by=input 都必须 = duration0
 *   case=B_positive_edit_refresh  : 改成 d1 + 刷索引 → 两条路都必须 = d1，且索引交回的对象【换了】
 *   case=C_negative_control_*     : 改成 d2 但【不刷索引】→ 两条路都必须 = d1（旧值！）  ← 负对照
 *   case=C2_same_edit_with_refresh: 同一个 d2 + 刷索引 → 两条路都必须 = d2  ← 排除"d2 这个值本身有问题"
 *   case=D_restore_original       : 改回 d0 + 刷索引 → 两条路都必须 = d0
 * 末行：selftest done cases=6 fail=&lt;n&gt; EDITPROBE=PASS|FAIL restored=yes|no
 * </pre>
 * <b>负对照为什么有判别力</b>：C 拍与 C2 拍<b>唯一的差别就是"有没有调 removeAllRecipes+addRecipe"</b>
 * （同一个输入对象、同一个新值 d2、同一段代码、同一次进程运行）⇒ 结果不同只能是这一步造成的。
 *
 * <h2>5. ⚠️ 还没验到的（如实标注，不许当成"已验证"）</h2>
 * <ul>
 *   <li><b>机器侧</b>（{@code RecipeLogic.lastRecipe}）：无头专服里没有机器实例，<b>没验</b>。</li>
 *   <li><b>AE/样板总成</b>：需要 gtlcore 的 {@code MEPatternBufferPartMachine} 实例与玩家网络，<b>没验</b>。</li>
 *   <li><b>JEI / 客户端显示</b>：红线禁止启动客户端，<b>没验</b>；且 §2 已论证老那套的包不带数据，
 *       对"只改服务端一条字段"这条路<b>理论上也不成立</b>（那是分析，不是实测）。</li>
 *   <li><b>持久性</b>：{@code by=manager} 那条读数会显示原版配方表<b>没变</b> ⇒ 下一次
 *       {@code /reload} 会把这次编辑抹掉（这是 {@code RecipeManagerMixin} 的 TAIL 重建索引的直接后果）。</li>
 *   <li><b>性能</b>：{@code rewrite} 是<b>全类型重建</b>（O(该类型的配方数)），日志里的
 *       {@code rewrite kept=/added=/ms=} 就是它的成本读数 —— 单条编辑也付全量代价。</li>
 * </ul>
 *
 * <h2>6. 本类依据的取证命令（都能复跑）</h2>
 * <pre>
 * # 反编译老 jar（本机 tools\vineflower-1.11.1.jar）
 * java -jar tools\vineflower-1.11.1.jar -dgs=1 --silent &lt;gt_shanhai.jar&gt; &lt;out&gt;
 * # 反编译 gtceu 的两个关键类
 * jar xf gtceu-1.20.1-1.4.4.jar com/gregtechceu/gtceu/api/recipe/lookup/GTRecipeLookup.class \
 *                                   com/gregtechceu/gtceu/api/recipe/lookup/Branch.class \
 *                                   com/gregtechceu/gtceu/core/mixins/RecipeManagerMixin.class
 * # 方法表（确认 removeAllRecipes/addRecipe/find 都是 public）
 * javap -p -cp gtceu-1.20.1-1.4.4.jar com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup
 * javap -p -cp gtceu-1.20.1-1.4.4.jar com.gregtechceu.gtceu.api.recipe.lookup.Branch
 * </pre>
 */
public final class ShanhaiRecipeEditProbe {

    /** 本类所有日志行的前缀。grep 这一个词就能把所有读数捞出来。 */
    public static final String PREFIX = "[SHANHAI-EDITPROBE]";

    /** 机器判定行的键名（与 {@code ShanhaiRecipeStats.STATS_LOG_KEY} 同款口径）。 */
    public static final String LOG_KEY = "recipe_editprobe";

    /** 子命令字面量：{@code /shanhai editprobe …}。 */
    public static final String COMMAND_ARG = "editprobe";

    /** 自动自检的开关环境变量（值 {@code 1} 即开）。 */
    public static final String ENV_ENABLE = "SHANHAI_EDITPROBE";

    /**
     * 自动自检的<b>开关文件</b>（相对服务器工作目录）。
     *
     * <p>为什么除了环境变量还要有它：冒烟装置
     * （{@code temp\smoke-rig\run_smoke.ps1}）用 {@code ProcessStartInfo} 起 JVM，
     * 环境块是继承的，但"继承到底成不成"本身不该成为一次实验的成败变量
     * ⇒ 两个门都给，任一为真即开。文件的存在由 {@link #enableReason()} 打进日志，可判。
     */
    public static final String FLAG_FILE = "config/shanhai-editprobe.flag";

    /** 支持的字段名（当前只有这一个真的做了）。 */
    public static final String FIELD_DURATION = "duration";

    /**
     * 自检最多试几条候选配方（挑一条"既在配方表里、又在索引里、并且真的能按输入查出来"的）。
     *
     * <p>为什么给到 2 万：许多配方的输入是 <b>tag 类键</b>（{@code MapItemTagIngredient} /
     * {@code MapFluidTagIngredient}），而这两个类的 {@code equals} 用的是
     * {@code this.tag == other.tag} <b>引用相等</b>（反编译取证：两个类都只写
     * {@code return super.equals(obj) ? this.tag == ((Map…TagIngredient)obj).tag : false;}
     * —— 注意是 {@code ==} 不是 {@code .equals}）⇒ 只要 TagKey 实例不是同一个，
     * 键就"明明在树里却 get 不到"。本探针<b>不跟这些病态键缠斗</b>：扫过去、换下一条。
     */
    private static final int MAX_CANDIDATES = 20000;

    private ShanhaiRecipeEditProbe() {}

    // ================================================================== 挂载与自检

    /**
     * Forge 入口：服务器完全起来之后，若开关打开就跑一遍自检。
     *
     * <p>为什么挂在 {@code ServerStartedEvent}：配方（含 KubeJS 那批）此时已经全部落进
     * {@code RecipeManager} 与 {@code GTRecipeLookup}；再晚就没有必要，再早则拿不到完整索引。
     * <p>🔴 <b>默认不开</b>：本探针会<b>真的改写</b>运行期索引（读完立刻还原），
     * 不该在用户正常玩的时候自己跑 ⇒ 必须显式给 {@link #ENV_ENABLE} 或 {@link #FLAG_FILE}。
     * 开关判定结果无条件打一行日志 —— "探针没跑"与"探针跑了但没出数"必须能分开。
     */
    public static void onServerStarted(ServerStartedEvent event) {
        final String reason = enableReason();
        ShanhaiMod.LOGGER.info("{} hook key={} enable_source={} will_run={}",
                PREFIX, LOG_KEY, reason, !reason.isEmpty());
        if (reason.isEmpty()) {
            return;
        }
        try {
            selfTest(event.getServer());
        } catch (Throwable t) {
            // 探针不许把服务端拖下水：任何异常都就地收成一行可判的日志。
            ShanhaiMod.LOGGER.error("{} selftest done cases=0 fail=1 EDITPROBE=FAIL reason=threw:{}",
                    PREFIX, t);
        }
    }

    /** 开关判定：返回"为什么开着"，空串 = 没开。 */
    private static String enableReason() {
        try {
            final String env = System.getenv(ENV_ENABLE);
            if (env != null && !env.isBlank() && !"0".equals(env.trim()) && !"false".equalsIgnoreCase(env.trim())) {
                return "env:" + ENV_ENABLE + "=" + env.trim();
            }
        } catch (Throwable ignored) {
            // 读环境变量不该失败；真失败了就当没设。
        }
        try {
            if (Files.exists(Path.of(FLAG_FILE))) {
                return "file:" + FLAG_FILE;
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return "";
    }

    // ================================================================== 命令：/shanhai editprobe

    /**
     * {@code /shanhai editprobe …} 那一支。
     *
     * <pre>
     *   /shanhai editprobe                                   → 跑一遍自检（与自动自检同一段代码）
     *   /shanhai editprobe &lt;配方id&gt; &lt;字段&gt; &lt;值&gt;                → 改一条并立刻读回（默认刷索引）
     *   /shanhai editprobe &lt;配方id&gt; &lt;字段&gt; &lt;值&gt; norefresh    → 【负对照】不刷索引地改
     * </pre>
     * <p>参数用 {@code string()} 不用 {@code word()}：{@code word()} 的字符集 {@code [a-zA-Z0-9_.+-]}
     * <b>不含冒号</b>，而配方 id 一定带 {@code namespace:path}。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .executes(ctx -> runSelfTestCommand(ctx.getSource()))
                .then(Commands.argument("recipe", StringArgumentType.string())
                        .then(Commands.argument("field", StringArgumentType.string())
                                .then(Commands.argument("value", StringArgumentType.string())
                                        .executes(ctx -> runEditCommand(ctx, true))
                                        .then(Commands.literal("refresh")
                                                .executes(ctx -> runEditCommand(ctx, true)))
                                        .then(Commands.literal("norefresh")
                                                .executes(ctx -> runEditCommand(ctx, false))))));
    }

    private static int runSelfTestCommand(CommandSourceStack source) {
        final MinecraftServer server = source.getServer();
        final int fails = selfTest(server);
        reply(source, "editprobe selftest fail=" + fails + " (明细见 " + PREFIX + " 日志)");
        return fails == 0 ? 1 : 0;
    }

    private static int runEditCommand(CommandContext<CommandSourceStack> ctx, boolean refresh) {
        final CommandSourceStack source = ctx.getSource();
        final MinecraftServer server = source.getServer();
        final String id = StringArgumentType.getString(ctx, "recipe");
        final String field = StringArgumentType.getString(ctx, "field");
        final String value = StringArgumentType.getString(ctx, "value");
        final String result = runManual(server, id, field, value, refresh);
        reply(source, result);
        return result.startsWith("FAIL") ? 0 : 1;
    }

    private static void reply(CommandSourceStack source, String text) {
        try {
            source.sendSuccess(() -> Component.literal(text), false);
        } catch (Throwable ignored) {
            // 发不出去不影响日志那条判据
        }
    }

    // ================================================================== 手动改一条

    /**
     * 手动路径：改一条指定的配方字段并立刻读回（命令与自检共用 {@link #rewrite} 这一套）。
     *
     * <p>返回一行人可读的结果串；<b>判据仍在日志里</b>（{@code by=id} / {@code by=input} 两行）。
     */
    public static String runManual(MinecraftServer server, String recipeId, String field, String value, boolean refresh) {
        try {
            if (server == null) {
                return "FAIL server 还没起来";
            }
            if (!FIELD_DURATION.equals(field)) {
                return "FAIL 字段 " + field + " 还没实现（当前只有 " + FIELD_DURATION + "）";
            }
            final int newValue;
            try {
                newValue = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return "FAIL 值 '" + value + "' 不是整数";
            }
            final Target t = resolve(server, recipeId);
            if (t == null) {
                return "FAIL 找不到「既在配方表里、又能按输入查出来」的配方 " + recipeId;
            }
            final GTRecipe cur = t.indexObj();
            final int old = cur.duration;
            final GTRecipe next = cur.copy();
            next.id = t.id();
            next.duration = newValue;

            ShanhaiMod.LOGGER.info("{} manual field={} type={} id={} from={} to={} refresh={}",
                    PREFIX, field, t.type().registryName, t.id(), old, newValue, refresh ? "yes" : "no");

            if (refresh) {
                final Rewrite rw = rewrite(t.type(), cur, next);
                ShanhaiMod.LOGGER.info("{} rewrite reason=manual {}", PREFIX, rewriteText(rw));
            }
            final Read byId = readByIndex(t);
            final Read byVanilla = readByVanillaPath(t);
            final Read byGt6 = readByInput(t, KeySource.TREE_PATH);
            final Read byHolder = readByInput(t, KeySource.HOLDER);
            final Read byDerived = readByInput(t, KeySource.DERIVED);
            final Read byMg = readByManager(server, t.id());
            logRead("manual", "id", byId, newValue);
            logRead("manual", "input(vanilla_tree)", byVanilla, newValue);
            logRead("manual", "input(gt6_tree)", byGt6, newValue);
            logRead("manual", "input(holder)", byHolder, newValue);
            logRead("manual", "input(derived_key)", byDerived, newValue);
            logRead("manual", "vanilla_bytype", readByVanillaType(server, t.type(), t.id()), newValue);
            logRead("manual", "manager", byMg, newValue);
            return "editprobe id=" + t.id() + " " + field + ": " + old + " -> " + newValue
                    + " | by=id " + byId.summary() + " | by=input(vanilla) " + byVanilla.summary()
                    + " (refresh=" + (refresh ? "yes" : "no") + ")";
        } catch (Throwable e) {
            ShanhaiMod.LOGGER.error("{} manual FAIL reason={}", PREFIX, e);
            return "FAIL 抛异常：" + e;
        }
    }

    // ================================================================== 自检（PASS/FAIL 的来源）

    /**
     * 六拍自检。返回<b>失败拍数</b>（0 ⇒ {@code EDITPROBE=PASS}）。
     *
     * <p>每一拍都只做一件事，且都留一行 {@code read case=… by=…} 的原始读数。
     * <b>无论中间出什么事，最后都要把 duration 还原成原值</b>（{@code restored=} 那一行就是它的核验）。
     */
    public static int selfTest(MinecraftServer server) {
        ShanhaiMod.LOGGER.info("{} selftest begin key={} env={} flag_file={}",
                PREFIX, LOG_KEY, safeGetenv(ENV_ENABLE), FLAG_FILE);
        if (server == null) {
            ShanhaiMod.LOGGER.error("{} selftest done cases=0 fail=1 EDITPROBE=FAIL reason=server_null", PREFIX);
            return 1;
        }

        // 🔴 2026-10-04 本轮新增：先做【只读解剖】再做改写实验。
        //    顺序理由：解剖不许被"我们自己的改写"污染，而且它必须无条件跑完
        //    —— 上一轮的教训是"选靶失败 ⇒ 读数信息量为 0"，一轮冒烟白烧。
        //    三段全部只读（不重建索引、不换对象、不动配方表）。
        ShanhaiRecipeIndexDiag.diagnose(server);
        ShanhaiRecipeIndexDiag.survey(server);
        ShanhaiRecipeIndexDiag.reverseLookupProbe(server);

        final Target t = resolve(server, "");
        if (t == null) {
            ShanhaiMod.LOGGER.error("{} selftest done cases=0 fail=1 EDITPROBE=FAIL "
                            + "reason=no_target（配方表里没有一条 GT 配方能同时满足：在 GTRecipeLookup 里 + 有可作索引键的输入）",
                    PREFIX);
            return 1;
        }

        final int d0 = t.indexObj().duration;
        final int indexRecipes = countIndexRecipes(t.type());
        ShanhaiMod.LOGGER.info("{} selftest target type={} id={} duration0={} query_inputs={} "
                        + "index_recipes={} managerObj_is_indexObj={}",
                PREFIX, t.type().registryName, t.id(), d0, inputKeyCount(t.holder()),
                indexRecipes, t.managerObj() == t.indexObj());

        // 🔴 两处配方表【各报一次规模与身份】—— 这是"分裂"的直接读数，不能只报 GT 索引那一边。
        //    ① GT 侧 = GTRecipeType.getLookup() 那棵树（GT 机器走它）；
        //    ② 原版侧 = RecipeManager 的 recipes 表（= 按配方类型那张表，原版/别的 mod 走它）。
        ShanhaiMod.LOGGER.info("{} two_tables gt_index_recipes={} vanilla_bytype_recipes={} "
                        + "vanilla_byName_hint=via_invoker gtindex_obj={} vanilla_obj={} same_object={}",
                PREFIX, indexRecipes, countVanillaTypeRecipes(server, t.type()),
                hex(t.indexObj()), hex(t.managerObj()), t.managerObj() == t.indexObj());

        int fails = 0;
        GTRecipe current = t.indexObj();

        // ① 改之前
        final Read bId = readByIndex(t);
        logRead("baseline", "id", bId, d0);
        logRead("baseline", "vanilla_bytype", readByVanillaType(server, t.type(), t.id()), d0);
        logRead("baseline", "manager", readByManager(server, t.id()), d0);
        fails += logInputReads("baseline", t, d0);
        fails += judge("baseline", bId, d0, "id");

        // ②③④ 正向：改 → 刷索引 → 立刻读回
        final int d1 = d0 + 12345;
        EditOutcome e1 = editAndRead(server, t, "B_positive_edit_refresh", current, d1, true, d1);
        fails += e1.fails();
        if (e1.applied()) {
            current = e1.registered();
        }

        // ⑤ 负对照：同值的改动，但【不刷索引】
        final int d2 = d0 + 54321;
        EditOutcome e2 = editAndRead(server, t, "C_negative_control_norefresh", current, d2, false, d1);
        fails += e2.fails();

        // 补：同一个值 + 刷索引 ⇒ 必须成功（排除"d2 本身非法的替代解释"）
        EditOutcome e3 = editAndRead(server, t, "C2_control_same_edit_with_refresh", current, d2, true, d2);
        fails += e3.fails();
        if (e3.applied()) {
            current = e3.registered();
        }

        // 收尾：还原
        EditOutcome e4 = editAndRead(server, t, "D_restore_original", current, d0, true, d0);
        fails += e4.fails();

        final int finalById = readByIndex(t).duration();
        final boolean restored = finalById == d0;
        if (!restored) {
            fails++;
            ShanhaiMod.LOGGER.error("{} restore FAILED：最终 by=id duration={} 期望={} ⇒ 服务端仍处于被改过的状态",
                    PREFIX, finalById, d0);
        }

        ShanhaiMod.LOGGER.info("{} selftest done cases=6 fail={} EDITPROBE={} restored={} "
                        + "target={}#{} duration0={}",
                PREFIX, fails, fails == 0 ? "PASS" : "FAIL", restored ? "yes" : "no",
                t.type().registryName, t.id(), d0);

        // 🔴 判据先落地、再量成本：成本普查有副作用（对每个类型原地重建一次索引），
        //    排在 PASS/FAIL 那几行【之后】⇒ 它出事也不会污染判据。
        sweepAllTypes(server);
        // ② 顺带实测那条线索：RecipeManager.replaceRecipes(Iterable) 到底能重写哪几张表。
        probeReplaceRecipes(server, t, d0);
        return fails;
    }

    /**
     * <b>实测线索</b>：{@code RecipeManager.replaceRecipes(Iterable)} 能不能"一次搞定两处"。
     *
     * <h4>先给字节码结论（不靠猜）</h4>
     * {@code javap -p -c net.minecraft.world.item.crafting.RecipeManager}（forge 1.20.1-47.4.16
     * mapped_parchment）的方法体是：
     * <pre>
     *   public void replaceRecipes(Iterable&lt;Recipe&lt;?&gt;&gt;);
     *     0: aload_0  1: iconst_0  2: putfield hasErrors
     *     5: invokestatic Maps.newHashMap                        // 局部 map：type → (id → recipe)
     *     9: invokestatic ImmutableMap.builder                   // 局部 builder：id → recipe
     *    16: invokedynamic lambda$replaceRecipes$11               // 逐条：type 分桶 + byName 收集
     *    26: putfield  Field recipes                ← 按配方类型那张表
     *    34: putfield  Field byName                 ← id → recipe 那张表
     *    42: return
     * </pre>
     * ⇒ <b>它确实同时重写两张表</b>（船长的线索成立）。而 {@code RecipeManager} 这个类里
     * <b>一共只有这两个 map</b>（{@code recipes: Map&lt;RecipeType&lt;?&gt;, Map&lt;ResourceLocation, Recipe&lt;?&gt;&gt;&gt;}
     * 与 {@code byName}；证据 = 上面的 {@code javap} 字段表），而
     * {@code getRecipes()} 读的正是 {@code this.recipes}（字节码 {@code getfield #66 Field recipes}）。
     *
     * <h4>但那不构成"一次搞定"</h4>
     * GT 侧那棵树（{@code GTRecipeLookup}）<b>不由 RecipeManager 持有</b>，gtceu 只在
     * {@code RecipeManager.apply(...)} 的 TAIL 上（{@code RecipeManagerMixin}）重建它
     * ⇒ {@code replaceRecipes} <b>不会</b>碰 GT 索引。本方法就是去实测这一条：
     * 换掉原版表里那一条，然后<b>分别</b>读原版表与 GT 索引，看谁变了。
     *
     * <h4>安全性</h4>
     * 进来先把 {@code getRecipes()} 的全量抓下来当"原样"，结束时原样写回；
     * 不论中间发生什么都在 {@code finally} 里恢复。全程只动内存表、不发包、不碰只读脚本。
     */
    private static void probeReplaceRecipes(MinecraftServer server, Target t, int restoreDuration) {
        List<Recipe<?>> original = null;
        try {
            final var rm = server.getRecipeManager();
            original = new ArrayList<>(rm.getRecipes());
            final int beforeSize = original.size();

            final GTRecipe vanillaObj = findVanillaObject(server, t.id());
            if (vanillaObj == null) {
                ShanhaiMod.LOGGER.warn("{} replaceRecipes_probe skipped reason=target_not_in_vanilla_table", PREFIX);
                return;
            }
            final int d3 = restoreDuration + 77777;
            final GTRecipe next = vanillaObj.copy();
            next.id = t.id();
            next.duration = d3;

            final List<Recipe<?>> mutated = new ArrayList<>(original.size());
            boolean swapped = false;
            for (Recipe<?> r : original) {
                if (r == vanillaObj) {
                    mutated.add(next);
                    swapped = true;
                } else {
                    mutated.add(r);
                }
            }
            if (!swapped) {
                ShanhaiMod.LOGGER.warn("{} replaceRecipes_probe skipped reason=vanilla_object_not_found_in_list", PREFIX);
                return;
            }

            ShanhaiMod.LOGGER.info("{} replaceRecipes_probe begin from={} to={} vanilla_obj={} new_obj={} "
                            + "list_size={}", PREFIX, vanillaObj.duration, d3, hex(vanillaObj), hex(next), beforeSize);
            rm.replaceRecipes(mutated);

            final Read vType = readByVanillaType(server, t.type(), t.id());
            final Read vAll = readByManager(server, t.id());
            final Read gtId = readByIndex(t);
            final int afterSize = rm.getRecipes().size();
            logRead("E_replaceRecipes", "vanilla_bytype", vType, d3);
            logRead("E_replaceRecipes", "manager", vAll, d3);
            logRead("E_replaceRecipes", "id", gtId, d3);
            logRead("E_replaceRecipes", inputLabel(KeySource.TREE_PATH), readByInput(t, KeySource.TREE_PATH), d3);
            logRead("E_replaceRecipes", inputLabel(KeySource.HOLDER), readByInput(t, KeySource.HOLDER), d3);

            final boolean updatesVanilla = vType.found() && vType.recipe() == next
                    && vAll.found() && vAll.recipe() == next;
            final boolean updatesGtIndex = gtId.found() && gtId.recipe() == next;
            ShanhaiMod.LOGGER.info("{} replaceRecipes_probe result updates_vanilla_bytype={} "
                            + "updates_manager_recipes={} updates_gtindex={} list_size={}→{} "
                            + "⇒ 结论：原版两张表={}，GT 索引={}",
                    PREFIX, vType.found() && vType.recipe() == next, vAll.found() && vAll.recipe() == next,
                    updatesGtIndex, beforeSize, afterSize,
                    updatesVanilla ? "会更新" : "没更新", updatesGtIndex ? "会更新" : "没更新");
        } catch (Throwable e) {
            ShanhaiMod.LOGGER.error("{} replaceRecipes_probe FAIL reason={}", PREFIX, e);
        } finally {
            try {
                if (original != null) {
                    server.getRecipeManager().replaceRecipes(original);
                    final Read back = readByVanillaType(server, t.type(), t.id());
                    ShanhaiMod.LOGGER.info("{} replaceRecipes_probe restored=yes duration_back={} expect={} ok={}",
                            PREFIX, back.duration(), restoreDuration, back.duration() == restoreDuration ? "yes" : "no");
                }
            } catch (Throwable e) {
                ShanhaiMod.LOGGER.error("{} replaceRecipes_probe restore FAILED reason={} ⇒ 原版配方表停在被改过的状态",
                        PREFIX, e);
            }
        }
    }

    /**
     * 一拍：造新对象 → 决定要不要刷索引 → 三条路读回 → 逐条判定。
     *
     * @param caseName 这一拍的名字（进日志）
     * @param current  索引里<b>现在</b>那条（新对象由它 {@code copy()} 出来）
     * @param newDur   这拍要写的 duration
     * @param refresh  {@code true} = 调 {@code removeAllRecipes()+addRecipe(…)}；{@code false} = 负对照
     * @param expect   这拍结束<b>应该</b>读到的值（负对照这一拍等于"上一拍的旧值"）
     */
    private static EditOutcome editAndRead(MinecraftServer server, Target t, String caseName,
                                           GTRecipe current, int newDur, boolean refresh, int expect) {
        final GTRecipe next = current.copy();
        next.id = t.id();
        next.duration = newDur;

        String rewriteText = "refresh=no index_untouched=yes";
        boolean applied = false;
        if (refresh) {
            final Rewrite rw = rebuild(t.type(), current, next);
            applied = rw.replaced();
            rewriteText = "refresh=yes " + rewriteText(rw);
        }
        ShanhaiMod.LOGGER.info("{} edit case={} field={} from={} to={} {} "
                        + "newobj_id={} newobj_identity={} oldobj_identity={}",
                PREFIX, caseName, FIELD_DURATION, current.duration, newDur, rewriteText,
                next.id, hex(next), hex(current));

        final Read byId = readByIndex(t);
        final Read byMg = readByManager(server, t.id());
        logRead(caseName, "id", byId, expect);
        logRead(caseName, "vanilla_bytype", readByVanillaType(server, t.type(), t.id()), expect);
        logRead(caseName, "manager", byMg, expect);
        int fails = logInputReads(caseName, t, expect);
        fails += judge(caseName, byId, expect, "id");

        // 身份判据：刷了索引 ⇒ 索引交回的对象必须是【新的那个】；没刷 ⇒ 必须是【老的那个】
        final boolean handedBackNew = byId.found() && byId.recipe() == next;
        final boolean handedBackOld = byId.found() && byId.recipe() == current;
        final boolean identityOk = refresh ? handedBackNew : handedBackOld;
        if (!identityOk) {
            fails++;
        }
        ShanhaiMod.LOGGER.info("{} identity case={} expect={} handed_back_new={} handed_back_old={} ok={}",
                PREFIX, caseName, refresh ? "new_obj" : "old_obj", handedBackNew, handedBackOld,
                identityOk ? "yes" : "no");

        ShanhaiMod.LOGGER.info("{} verdict case={} ok={} fails={}", PREFIX, caseName,
                fails == 0 ? "yes" : "no", fails);
        return new EditOutcome(fails, applied, next);
    }

    // ================================================================== ① 层的实现：换对象 + 重建索引

    /**
     * 一次索引重建的<b>成本与结果读数</b>（全部是实测，不是推算）。
     *
     * @param kept      重建时处理的<b>去重后</b>配方数（= 这一拍的成本规模）
     * @param added     {@code addRecipe} 返回 {@code true} 的条数
     * @param dropped   {@code kept - added}：被 {@code addRecipe} 拒收的条数（输入冲突时的静默丢条）
     * @param replaced  目标那条是否被真的换成了新对象
     * @param collectUs 阶段 ①：<b>遍历整棵树 + 身份去重</b>耗时（= 一次完整树遍历的代价）
     * @param clearUs   阶段 ②：<b>清空查找表</b>（{@code removeAllRecipes}）耗时
     * @param addUs     阶段 ③：<b>逐条重新加入</b>耗时
     * @param totalUs   三阶段合计
     */
    private record Rewrite(int kept, int added, int dropped, boolean replaced,
                           long collectUs, long clearUs, long addUs, long totalUs) {}

    /** 一次 {@link #rewrite} 的便捷包装。 */
    private static Rewrite rewrite(GTRecipeType type, GTRecipe old, GTRecipe next) {
        return rebuild(type, old, next);
    }

    /**
     * <b>把索引里 {@code old} 那一条换成 {@code next}</b>（{@code old == null} 时只做原地重建，
     * 用于 {@link #sweepAllTypes} 的纯测量）—— 本类唯一真正写东西的地方。
     *
     * <p>步骤照老那套（{@code DShanhaiRecipeModifierAPI.removeAndSync} :2173-2177 /
     * {@code replaceInRecipes} :638-642 / {@code updateLookupRecipes} :1309-1313 三处同款写法）：
     * <pre>
     *   ① 收全量（去重）→ 过滤掉 old → 放上 next   ② lookup.removeAllRecipes()   ③ 逐条 addRecipe
     * </pre>
     *
     * <h4>🔴 为什么【必须去重】</h4>
     * {@code Branch.getRecipes(true)} 是一棵多叉树的遍历：一条配方在每一组输入键下都会被挂一次
     * （{@code recurseIngredientTreeAdd} 对每一组 ingredients 都挂）
     * ⇒ <b>同一个对象会从流里出来多次</b>。不去重直接 rebuild 会把重复的也 add 进去；
     * 本类用 {@link IdentityHashMap} 做身份去重（不是 {@code equals} —— {@code GTRecipe} 覆写了它）。
     *
     * <h4>⚠️ 全量重建的代价与风险（都变成读数，不藏着）</h4>
     * <ul>
     *   <li>三个阶段的耗时<b>分开计</b>（{@code collect_us}/{@code clear_us}/{@code add_us}），
     *       这样"贵在哪一步"是可判的，而不是只知道一个总数；</li>
     *   <li>{@code dropped} = {@code addRecipe} 返回 false 的条数。
     *       {@code addRecipe} 在"叶子上已经有别的配方、且不是同一个对象"时会<b>返回 false</b>
     *       （{@code recurseIngredientTreeAdd} :374-377）⇒ <b>重建索引有可能改变"输入冲突的几条里谁胜出"</b>。
     *       {@code dropped > 0} 就是这件事发生的机器证据。</li>
     * </ul>
     */
    private static Rewrite rebuild(GTRecipeType type, GTRecipe old, GTRecipe next) {
        final long tA = System.nanoTime();

        final GTRecipeLookup lookup = type.getLookup();
        final Branch branch = lookup.getLookup();
        final Set<GTRecipe> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<GTRecipe> keep = new ArrayList<>();
        // lambda 只能捕获"事实上 final"的局部变量 ⇒ 用一个单元素数组当可变槽。
        final boolean[] replacedSlot = {false};
        branch.getRecipes(true).forEach(r -> {
            if (r == null) {
                return;
            }
            if (!seen.add(r)) {
                return;                                  // 同一对象的多条路径 → 只留一条
            }
            if (r == old) {
                keep.add(next);
                replacedSlot[0] = true;
                return;
            }
            keep.add(r);
        });
        boolean replaced = replacedSlot[0];
        if (old != null && !replaced) {
            // 老对象没在索引里（例如传进来的 current 已经过期）⇒ 就地把 next 挂上。
            // ⚠️ 用 boolean 而不是 keep.contains(next)：GTRecipe 覆写了 equals，contains 不是身份判据。
            keep.add(next);
            replaced = true;
        }
        final long tB = System.nanoTime();

        lookup.removeAllRecipes();
        final long tC = System.nanoTime();

        int added = 0;
        for (GTRecipe r : keep) {
            if (lookup.addRecipe(r)) {
                added++;
            }
        }
        final long tD = System.nanoTime();

        return new Rewrite(keep.size(), added, keep.size() - added, replaced,
                (tB - tA) / 1_000L, (tC - tB) / 1_000L, (tD - tC) / 1_000L, (tD - tA) / 1_000L);
    }

    // ================================================================== 三条读路

    /** 一次读取的结果。{@code found=false} ⇒ 那条路上<b>根本没查到</b>（与"查到但值不对"是两件事）。 */
    private record Read(boolean found, GTRecipe recipe) {
        int duration() {
            return found && recipe != null ? recipe.duration : Integer.MIN_VALUE;
        }

        String idText() {
            return found && recipe != null && recipe.id != null ? recipe.id.toString() : "-";
        }

        String summary() {
            return found ? ("duration=" + duration() + " obj=" + hex(recipe)) : "NOT_FOUND";
        }
    }

    /** 读路 ①：<b>索引树本身</b>（按 id 找）。 */
    private static Read readByIndex(Target t) {
        try {
            final Set<GTRecipe> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            final List<GTRecipe> all = new ArrayList<>();
            t.type().getLookup().getLookup().getRecipes(true).forEach(r -> {
                if (r != null && seen.add(r)) {
                    all.add(r);
                }
            });
            for (GTRecipe r : all) {
                if (r.id != null && r.id.equals(t.id())) {
                    return new Read(true, r);
                }
            }
        } catch (Throwable ignored) {
            // 读不到就按"没查到"报
        }
        return new Read(false, null);
    }

    /**
     * 读路 ②：<b>按输入查</b> —— 这条路才是"机器会不会看见新值"的真判据。
     *
     * <p>用 {@link Target#holder()} 这个由目标配方自身输入造出来的合成 holder，
     * 走 {@code GTRecipeLookup.find(holder, predicate)}：它会
     * {@code prepareRecipeFind} → {@code fromHolder} → 从 {@code Branch} 树根往下递归
     * （反编译取证：{@code GTRecipeLookup.java:72-75, 116-179}）。
     * <p>谓词只按 id 收 —— 于是"树交回来的是哪个对象"被完好地暴露出来：
     * <b>索引没重建 ⇒ 交回老对象（旧值）；重建过 ⇒ 交回新对象（新值）</b>。
     *
     * <p>⚠️ 若 {@link Target#holderPathWorks()} 为 {@code false}（合成 holder 这条路在选靶时实测走不通），
     * 这里退到"配方自身输入键"那条路（{@link #findRecipeByInputKeys}），
     * 并且<b>在日志的 {@code by=} 字段上如实标明走的是哪条</b>（{@code input(holder)} / {@code input(recipekey)}）。
     * 两条路都读的是同一棵树、同一套键，判据强度等价；差别是"机器用的那条路通不通"，
     * 那件事由选靶日志的 {@code holder_path=} 单独报。
     */
    /** 按输入查时"钥匙从哪来"—— 三条读法用的是<b>同一棵树、同一个谓词</b>，差别只有钥匙的形状。 */
    private enum KeySource {
        /** 🔴 本轮判据：钥匙 = <b>树自己给的键路径</b>（键实例就是树上那两个）。 */
        TREE_PATH,
        /** 机器走的那条：钥匙 = 由目标配方输入合成的 holder（{@code GTRecipeLookup.find}）。 */
        HOLDER,
        /** 上一轮的读法：钥匙 = 从配方现推的键（⚠️ 不做 interning，tag 类 {@code equals} 是 {@code ==}）。 */
        DERIVED
    }

    /**
     * 读路 ②：<b>按输入查</b> —— 这条路才是"机器会不会看见新值"的真判据。
     *
     * <h4>🔴 本轮（第 4 版）改了什么</h4>
     * 上一轮用"从配方现推的键"（{@link #findByKeys}）去查，4 次冒烟全灭；而同一批配方
     * {@code by=id} 一读一个准。本轮把钥匙换成{@link KeySource#TREE_PATH
     * "树自己给的键路径"}：先走一遍树、把通向这个对象的键实例收割下来，再用它查。
     * <br>为什么这不是"作弊"：GT 的查询本来就是"手里有什么键就去树里找同名槽位"，
     * 键的<b>实例</b>归树管（{@code fromRecipe} 里那段 {@code retrieveCachedIngredient} interning）；
     * 现推一个新实例、再用 {@code ==} 类 equals 去比，本来就不是 GT 自己的做法。
     *
     * <p>三种读法<b>同时打读数</b>：判据只认 {@code input(tree_path)}，
     * 另外两条是"机器那条路通不通"的如实记录（不许拿判据那条的绿灯去替它背书）。
     */
    private static Read readByInput(Target t, KeySource src) {
        try {
            final java.util.function.Predicate<GTRecipe> pred =
                    r -> r != null && r.id != null && r.id.equals(t.id());
            final GTRecipe hit;
            switch (src) {
                case TREE_PATH -> hit = ShanhaiRecipeIndexDiag.queryByPath(t.type(), t.pathKeys(), pred);
                case HOLDER -> hit = t.holder() == null ? null : t.type().getLookup().find(t.holder(), pred);
                default -> hit = findByKeys(t.type(), t.queryKeys(), pred);
            }
            return new Read(hit != null, hit);
        } catch (Throwable ignored) {
            return new Read(false, null);
        }
    }

    /** 判据那条读法（= {@link KeySource#TREE_PATH}）的便捷入口。 */
    private static Read readByInput(Target t) {
        return readByInput(t, KeySource.TREE_PATH);
    }

    /**
     * 读路 ④：<b>原版那张「按配方类型」的表</b>，直接取该类型的子表。
     *
     * <p>原版 {@code RecipeManager} 的 {@code recipes} 字段是 {@code private}，
     * 但 <b>gtceu 自己给它加了一个公开的 invoker</b>：
     * {@code com.gregtechceu.gtceu.core.mixins.RecipeManagerInvoker#getRecipeFromType(RecipeType)}
     * （取证：{@code javap -p ...RecipeManagerInvoker} → 只有一个方法，
     * 返回 {@code Map<ResourceLocation, Recipe<C>>}）⇒ 不必自己反射。
     *
     * <p>这条路与 {@code by=manager}（走 {@code getRecipes()} 的全量拉平）读的是同一张表，
     * 区别是这里<b>只取目标类型那一个子表</b> ⇒ 能回答"这个类型在原版那边到底有几条"。
     */
    private static Read readByVanillaType(MinecraftServer server, GTRecipeType type, ResourceLocation id) {
        try {
            final var rm = server.getRecipeManager();
            if (rm instanceof com.gregtechceu.gtceu.core.mixins.RecipeManagerInvoker invoker) {
                final java.util.Map<ResourceLocation, ?> byType = invoker.getRecipeFromType(type);
                if (byType != null) {
                    final Object r = byType.get(id);
                    if (r instanceof GTRecipe gr) {
                        return new Read(true, gr);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到就按"没查到"报
        }
        return new Read(false, null);
    }

    /** 原版那张「按配方类型」的表里，这个类型有几条。 */
    private static int countVanillaTypeRecipes(MinecraftServer server, GTRecipeType type) {
        try {
            final var rm = server.getRecipeManager();
            if (rm instanceof com.gregtechceu.gtceu.core.mixins.RecipeManagerInvoker invoker) {
                final java.util.Map<ResourceLocation, ?> byType = invoker.getRecipeFromType(type);
                return byType == null ? -1 : byType.size();
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return -1;
    }

    /** 原版表里现在那条目标配方<b>对象</b>（{@code replaceRecipes} 实验要拿它做替换基准）。 */
    private static GTRecipe findVanillaObject(MinecraftServer server, ResourceLocation id) {
        try {
            for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                if (r instanceof GTRecipe gr && gr.id != null && gr.id.equals(id)) {
                    return gr;
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return null;
    }

    /**
     * 读路 ③：从原版 {@code RecipeManager} 的全量表读同一个 id
     * （{@code getRecipes()} = {@code this.recipes} 那张按类型表的拉平，字节码 {@code getfield #66 Field recipes}）。
     *
     * <p>它量的是<b>另一份拷贝</b>：{@code RecipeManager} 的 {@code recipes} 表。
     * 本类从来不动它 ⇒ 这一路会一直显示旧值 —— 这正是"索引是从配方表派生的、
     * {@code /reload} 会重建索引"的直接读数，也是"两处配方表会分裂"的机器证据。
     */
    private static Read readByManager(MinecraftServer server, ResourceLocation id) {
        try {
            for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                if (r instanceof GTRecipe gr && gr.id != null && gr.id.equals(id)) {
                    return new Read(true, gr);
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return new Read(false, null);
    }

    // ================================================================== 目标解析 & 合成 holder

    /**
     * 被选中做实验的那条配方。
     *
     * @param type            它的 {@code GTRecipeType}
     * @param id              它的 id
     * @param managerObj      {@code RecipeManager} 里那条（目标不在原版表里时回落成索引里那条，如实打日志）
     * @param indexObj        <b>索引里</b>那条（真正的实验对象 —— 机器拿到的是它）
     * @param holder          由 {@code indexObj} 的输入造出来的"查询钥匙"（合成 holder，= 机器那条路）
     * @param queryKeys       <b>现推的输入查询键</b>：由 {@code indexObj} 的输入按建索引的同一套算法算出来的
     *                        "每一组输入键"（⚠️ 与 GT 的差别 = <b>不做 interning</b>，见 §5.4）。
     * @param holderPathWorks {@code true} = 合成 holder 这条路（机器走的那条）真的查得通；
     *                        {@code false} = 只有"配方自身输入键"那条路查得通（⇒ 如实报，不静默兜底）
     * @param pathKeys        🔴 <b>本轮修法</b>：<b>从树里收割到的、通向这个配方对象的真实键路径</b>
     *                        （键实例就是树里那两个被 GT interning 过的实例）。
     *                        {@code null} = 这个对象根本不在树里。
     *                        为什么必须有它：{@code MapFluidTagIngredient}/{@code MapItemTagIngredient}
     *                        的 {@code equals} 用的是 {@code tag == other.tag}（<b>引用相等</b>），
     *                        而 GT 建索引时会把键<b>换成 interning 表里先前那个实例</b>
     *                        ⇒ <b>现推出来的新实例与树上的实例"同哈希但 equals 为假"</b>，
     *                        {@code map.get} 于是返回 null —— 这就是"键明明在树里却查不到"。
     */
    private record Target(GTRecipeType type, ResourceLocation id, GTRecipe managerObj, GTRecipe indexObj,
                          IRecipeCapabilityHolder holder, List<List<AbstractMapIngredient>> queryKeys,
                          boolean holderPathWorks, List<List<AbstractMapIngredient>> pathKeys) {}

    /**
     * 挑一条配方做靶子。
     *
     * <h4>🔴 选中靶子的方式（第三版；前两版都错在这里，留档别再犯）</h4>
     * <ol>
     *   <li><b>第一版</b>：从 5 万多条里按字典序取前 96 条直接试 ⇒ 96/96 全灭，
     *       而且失败计数把"不在索引里"和"键算错了"混成一个数 ⇒ <b>读数信息量为 0</b>。</li>
     *   <li><b>第二版</b>：先按类型分组、只在"表 ∩ 索引"的交集里挑，并且要求查询真的查得到。
     *       结果 <b>20000/20000 全灭</b>。定位读数给出了真原因（冒烟日志原文）：
     *       <pre>
     *   diag holder_miss id=gtceu:advanced_hyper_reactor/concentration_mixing_hyper_fuel_1
     *     sets=1 keys=1 root_nodes=2 special_nodes=1 in_nodes=1 in_special=0
     *     chosen_map_leaf=1 chosen_map_branch=0 holder_sizes=1 recipekey_path=notfound
     *     keys_sample=[MapFluidTagIngredient[special=false,inNodes=true,inSpecial=false,entry=leaf]]
     *   diag holder_miss id=thetornproductionline:advanced_hyper_reactor/hyper_excitation_3_1
     *     sets=3 keys=3 in_nodes=0 in_special=1 chosen_map_leaf=0 chosen_map_branch=1
     *     keys_sample=[MapItemStackNBTIngredient[…,entry=branch] MapItemStackIngredient[…,entry=null] MapFluidIngredient[…,entry=null]]
     *       </pre>
     *       ⇒ 键<b>确实在树里、而且挂的是叶子</b>（第 1 条），但"按键找 + 按 id 过滤"仍然返回 null；
     *       第 2 条更直接：3 个键里<b>只有 1 个有内容、另外 2 个根本不在树里</b>
     *       ⇒ <b>GTCEu 的输入索引对一部分配方是有损的（按输入查不到它）</b>。
     *       <br>🔴 而第二版的契约"靶子必须能被按 id 过滤的查询查回来"在<b>有键冲突的类型里天然不成立</b>
     *       （键相同 ⇒ 树上只留得下一条，另一条按输入永远查不到）。</li>
     *   <li><b>第三版（现在这个）</b>：<b>把因果反过来</b> ——
     *       拿候选配方的输入键去问树，<b>树交回谁，谁就当靶子</b>。
     *       于是"按输入查"这条路<b>由构造保证走得通</b>，而"键冲突谁胜出"这件事本身变成了
     *       {@code key_leads_to=} 那条读数（它同时也是编辑器必须知道的一条真实约束）。</li>
     * </ol>
     */
    private static Target resolve(MinecraftServer server, String wantId) {
        // 表侧：id → GTRecipe（来源 = 服务器自己的配方表）
        final java.util.Map<String, GTRecipe> table = new java.util.HashMap<>();
        try {
            for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                if (r instanceof GTRecipe gr && gr.recipeType != null && gr.id != null) {
                    table.put(gr.id.toString(), gr);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} resolve FAIL reason=recipes_scan:{}", PREFIX, t);
            return null;
        }
        if (table.isEmpty()) {
            ShanhaiMod.LOGGER.error("{} resolve FAIL reason=recipe_table_has_no_GTRecipe", PREFIX);
            return null;
        }

        if (!wantId.isEmpty()) {
            final GTRecipe c = table.get(wantId);
            if (c == null) {
                ShanhaiMod.LOGGER.error("{} resolve FAIL reason=id_not_in_recipe_table id={} table={}",
                        PREFIX, wantId, table.size());
                return null;
            }
            final Target t = targetFor(c, table);
            if (t == null) {
                ShanhaiMod.LOGGER.error("{} resolve FAIL reason=recipe_not_reachable_by_input id={}", PREFIX, wantId);
            }
            return t;
        }

        final java.util.Map<GTRecipeType, List<GTRecipe>> byType = new IdentityHashMap<>();
        for (GTRecipe gr : table.values()) {
            byType.computeIfAbsent(gr.recipeType, k -> new ArrayList<>()).add(gr);
        }
        final List<GTRecipeType> types = new ArrayList<>(byType.keySet());
        types.sort(Comparator.comparing(t -> String.valueOf(t.registryName)));

        int tried = 0;
        int noKey = 0;
        int unreachable = 0;
        int keysOnlyOk = 0;
        int loggedTypes = 0;
        Target firstKeysOnly = null;
        for (GTRecipeType type : types) {
            final Set<String> indexIds = indexIdsOf(type);
            final List<GTRecipe> cands = byType.get(type);
            cands.sort(Comparator.comparing(x -> x.id.toString()));
            int intersect = 0;
            for (GTRecipe c : cands) {
                if (indexIds.contains(c.id.toString())) {
                    intersect++;
                }
            }
            if (loggedTypes < 8) {
                ShanhaiMod.LOGGER.info("{} resolve_scan type={} table={} index={} intersect={}",
                        PREFIX, type.registryName, cands.size(), indexIds.size(), intersect);
                loggedTypes++;
            }
            if (intersect == 0) {
                continue;
            }
            for (GTRecipe c : cands) {
                if (!indexIds.contains(c.id.toString())) {
                    continue;
                }
                if (tried++ >= MAX_CANDIDATES) {
                    break;
                }
                final Target t = targetFor(c, table);
                if (t == null) {
                    unreachable++;
                    continue;
                }
                if (t.holderPathWorks()) {
                    ShanhaiMod.LOGGER.info("{} resolve target={}#{} holder_path=ok key_leads_to={} table={} "
                                    + "types={} tried={} keys_only={} not_reachable_by_input={} skipped_no_input_key={}",
                            PREFIX, type.registryName, t.id(), t.indexObj().id, table.size(), types.size(),
                            tried, keysOnlyOk, unreachable, noKey);
                    return t;
                }
                keysOnlyOk++;
                if (firstKeysOnly == null) {
                    firstKeysOnly = t;
                }
                // 🔴 第 4 版：现在 targetFor 只在该对象【不在树里】时才返回 null
                //    ⇒ "树里查得到、只是机器那条 holder 路走不通"会成为常态。
                //    再扫下去没有新信息（每一条都会落进同一支），所以够 200 条就收。
                if (keysOnlyOk >= 200) {
                    ShanhaiMod.LOGGER.info("{} resolve stop reason=holder_path_never_works tried={} "
                                    + "keys_only={} ⇒ 机器那条 holder 路在扫过的 {} 条里一条都没通",
                            PREFIX, tried, keysOnlyOk, keysOnlyOk);
                    break;
                }
            }
            if (keysOnlyOk >= 200) {
                break;
            }
        }
        if (firstKeysOnly != null) {
            ShanhaiMod.LOGGER.warn("{} resolve target={}#{} holder_path=miss(fallback=pathkeys) key_leads_to={} "
                            + "tried={} keys_only={} not_reachable_by_input={} ⇒ "
                            + "【机器那条按输入查的 holder 路，在扫过的靶子里一条都没走通；"
                            + "本轮的判据走的是『树自己给的键路径』那条】",
                    PREFIX, firstKeysOnly.type().registryName, firstKeysOnly.id(),
                    firstKeysOnly.indexObj().id, tried, keysOnlyOk, unreachable);
            return firstKeysOnly;
        }
        ShanhaiMod.LOGGER.error("{} resolve FAIL reason=no_usable_target table={} types={} tried={} "
                        + "skipped_no_input_key={} not_reachable_by_input={}",
                PREFIX, table.size(), types.size(), tried, noKey, unreachable);
        return null;
    }

    /**
     * 由一条候选配方求出真正的靶子。
     *
     * <h4>🔴 本轮（第 4 版）换成【收割键路径】，不再"拿现推的键去问树"</h4>
     * 上一轮（第 3 版）是"拿现推的键去问树，树交回谁谁当靶子"。那条路的读数长这样
     * （4 次冒烟原始日志）：
     * <pre>
     *   resolve FAIL reason=no_usable_target table=52140 types=202 tried=20145
     *     skipped_no_input_key=0 not_reachable_by_input=20000
     * </pre>
     * 而同一批配方在 {@code by=id} 那条路上一读一个准 ⇒ 问题只可能出在
     * <b>"现推的键"与"树上的键"不是同一把</b>这一类事情上。
     * <br>⇒ 现在改成：<b>先 {@link ShanhaiRecipeIndexDiag#pathOf} 走一遍树，把通向这个对象的
     * 那条键路径（键实例本身）收割下来</b>，再拿这条路径去查。
     * 这样"能不能查到"就不再取决于 {@code equals} 的实现细节（tag 类用的是 {@code ==}）。
     *
     * <p>返回 {@code null} 表示<b>这个配方对象根本不在 GT 索引树里</b>
     * （原版表里有、但树里没有 —— 例如被 {@code addRecipe} 的输入冲突挤掉的那一条）。
     */
    private static Target targetFor(GTRecipe c, java.util.Map<String, GTRecipe> table) {
        final List<List<AbstractMapIngredient>> keys = ingredientSetsOf(c);
        final com.gregtechceu.gtceu.api.recipe.lookup.Branch root =
                c.recipeType.getLookup().getLookup();
        final List<List<AbstractMapIngredient>> path = ShanhaiRecipeIndexDiag.pathOf(root, c);
        if (path == null) {
            return null;
        }
        final IRecipeCapabilityHolder holder = holderFor(c);
        final GTRecipe viaHolder = holder == null ? null : c.recipeType.getLookup().find(holder,
                x -> x != null && x.id != null && x.id.equals(c.id));
        final boolean holderOk = viaHolder == c;
        if (!holderOk) {
            diagHolderMiss(c, holder, true);
        }
        return new Target(c.recipeType, c.id, table.getOrDefault(c.id.toString(), c), c, holder, keys, holderOk, path);
    }

    /** 定位读数最多打几条（再多就是刷屏，浪费的是别人读日志的时间）。 */
    private static int diagBudget = 3;

    /**
     * 合成 holder 那条路查不到时的<b>定位读数</b>。
     *
     * <p>把四件事一次打全：①我算出来的键在不在树的根表里；②树在那个键上挂的是叶子还是子树；
     * ③{@code determineRootNodes} 会选哪张表（{@code isSpecialIngredient()}）；④配方自身键那条路通不通。
     * 有了这四项，"键不等" / "进了错误的表" / "挂的是子树" 三种病当场可判。
     */
    private static void diagHolderMiss(GTRecipe c, IRecipeCapabilityHolder holder, boolean keysPathFound) {
        if (diagBudget-- <= 0) {
            return;
        }
        try {
            final Branch root = c.recipeType.getLookup().getLookup();
            final List<List<AbstractMapIngredient>> sets = ingredientSetsOf(c);
            int keyCount = 0;
            int inNodes = 0;
            int inSpecial = 0;
            int leafHits = 0;
            int branchHits = 0;
            final StringBuilder sample = new StringBuilder();
            for (List<AbstractMapIngredient> set : sets) {
                for (AbstractMapIngredient ing : set) {
                    keyCount++;
                    final boolean special = ing.isSpecialIngredient();
                    final boolean n = root.getNodes().containsKey(ing);
                    final boolean sp = root.getSpecialNodes().containsKey(ing);
                    if (n) {
                        inNodes++;
                    }
                    if (sp) {
                        inSpecial++;
                    }
                    final var entry = special ? root.getSpecialNodes().get(ing) : root.getNodes().get(ing);
                    if (entry != null) {
                        if (entry.left().isPresent()) {
                            leafHits++;
                        } else {
                            branchHits++;
                        }
                    }
                    if (sample.length() < 200) {
                        sample.append(ing.getClass().getSimpleName())
                                .append("[special=").append(special)
                                .append(",inNodes=").append(n)
                                .append(",inSpecial=").append(sp)
                                .append(",entry=").append(entry == null ? "null"
                                        : (entry.left().isPresent() ? "leaf" : "branch"))
                                .append("] ");
                    }
                }
            }
            ShanhaiMod.LOGGER.warn("{} diag holder_miss id={} type={} sets={} keys={} root_nodes={} "
                            + "special_nodes={} in_nodes={} in_special={} chosen_map_leaf={} chosen_map_branch={} "
                            + "holder_sizes={} recipekey_path={} keys_sample=[{}]",
                    PREFIX, c.id, c.recipeType.registryName, sets.size(), keyCount,
                    root.getNodes().size(), root.getSpecialNodes().size(), inNodes, inSpecial,
                    leafHits, branchHits, inputKeyCount(holder), keysPathFound ? "FOUND" : "notfound",
                    sample.toString().trim());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} diag holder_miss id={} type={} diag_failed={}", PREFIX, c.id,
                    c.recipeType.registryName, t);
        }
    }

    /** 每个 {@code GTRecipeType} 的索引 id 集合（走一次树，缓存）。 */
    private static final java.util.Map<GTRecipeType, Set<String>> INDEX_IDS = new IdentityHashMap<>();

    /**
     * 走这个类型的索引树，把里面所有配方的 id 收成一个集合（缓存）。
     *
     * <p>缓存安全的前提：<b>id 不会因为改 duration 而变</b>（本探针只改字段，不换 id）
     * ⇒ 一旦算过就是有效的。这个前提写在 {@link #editAndRead} 的契约里（{@code next.id = t.id()}）。
     */
    private static Set<String> indexIdsOf(GTRecipeType type) {
        return INDEX_IDS.computeIfAbsent(type, k -> {
            final Set<String> ids = new java.util.HashSet<>();
            final Set<GTRecipe> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                k.getLookup().getLookup().getRecipes(true).forEach(r -> {
                    if (r != null && seen.add(r) && r.id != null) {
                        ids.add(r.id.toString());
                    }
                });
            } catch (Throwable ignored) {
                // 读不到就当空集合
            }
            return ids;
        });
    }

    /** 把一条配方的输入按<b>建索引时的同一套算法</b>算成"每一组输入键"（照 {@code GTRecipeLookup.fromRecipe}）。 */
    private static List<List<AbstractMapIngredient>> ingredientSetsOf(GTRecipe r) {
        final List<List<AbstractMapIngredient>> out = new ArrayList<>();
        out.addAll(deriveSets(r.inputs));
        out.addAll(deriveSets(r.tickInputs));
        return out;
    }

    private static List<List<AbstractMapIngredient>> deriveSets(java.util.Map<RecipeCapability<?>, List<Content>> source) {
        final List<List<AbstractMapIngredient>> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (java.util.Map.Entry<RecipeCapability<?>, List<Content>> e : source.entrySet()) {
            final RecipeCapability<?> cap = e.getKey();
            final List<Content> contents = e.getValue();
            if (cap == null || contents == null || contents.isEmpty() || !cap.isRecipeSearchFilter()) {
                continue;
            }
            final List<Object> raw = new ArrayList<>(contents.size());
            for (Content c : contents) {
                if (c != null && c.getContent() != null) {
                    raw.add(c.getContent());
                }
            }
            if (raw.isEmpty()) {
                continue;
            }
            for (Object compressed : cap.compressIngredients(raw)) {
                out.add(cap.convertToMapIngredient(compressed));
            }
        }
        return out;
    }

    /**
     * <b>用配方自身的输入键</b>去索引树里查 —— 只走 {@code GTRecipeLookup} 的<b>公开</b> API
     * （双参数版的 {@code recurseIngredientTreeFindRecipe} 是 private，六参数版是 public，
     * 取证：{@code javap -p ...GTRecipeLookup} 的方法表）。
     *
     * <p>它与 {@code find(holder, …)} 的差别只有一处：键的来源是<b>配方</b>而不是<b>机器</b>。
     * GTCEu 自己就是这么建索引的（{@code fromRecipe}）⇒ 这条路<b>必然与索引键一致</b>。
     * 所以它通不代表机器那条路通 —— 两条路的结果都记在日志里。
     */
    /**
     * 用<b>一组固定的输入键</b>去索引树里查（只走 {@code GTRecipeLookup} 的<b>公开</b> API：
     * 双参数版 {@code recurseIngredientTreeFindRecipe} 是 private，六参数版是 public，
     * 取证：{@code javap -p …GTRecipeLookup} 的方法表）。
     *
     * <p>它与 {@code find(holder, …)} 的差别只有一处：键的来源是<b>配方</b>而不是<b>机器</b>。
     * GTCEu 自己就是这么建索引的（{@code fromRecipe}）⇒ 这条路<b>必然与索引键一致</b>。
     */
    private static GTRecipe findByKeys(GTRecipeType type, List<List<AbstractMapIngredient>> sets,
                                       java.util.function.Predicate<GTRecipe> canHandle) {
        try {
            if (sets == null || sets.isEmpty()) {
                return null;
            }
            for (int i = 0; i < sets.size(); i++) {
                final GTRecipe r = type.getLookup().recurseIngredientTreeFindRecipe(
                        sets, type.getLookup().getLookup(), canHandle, i, 0, 1L << i);
                if (r != null) {
                    return r;
                }
            }
        } catch (Throwable t) {
            // ⚠️ 不许再静默吞异常：上一版就是吞掉之后、"查询返回 null"看起来像"树里没有"，
            //    凭空少掉一整轮定位。异常至少要露一次头。
            if (queryExceptionBudget-- > 0) {
                ShanhaiMod.LOGGER.error("{} query_failed path=recipekey type={} reason={}",
                        PREFIX, type == null ? null : type.registryName, t);
            }
        }
        return null;
    }

    /**
     * 用一条配方自身的输入键去查（{@link #findByKeys} 的便捷入口）。
     * @deprecated 换成显式传键的 {@link #findByKeys}，这样自检六拍复用的是<b>同一份</b>键。
     */
    @Deprecated
    private static GTRecipe findRecipeByInputKeys(GTRecipeType type, GTRecipe template,
                                                 java.util.function.Predicate<GTRecipe> canHandle) {
        return findByKeys(type, ingredientSetsOf(template), canHandle);
    }

    /** 查询异常最多打几条（防止一次全量扫描刷爆日志）。 */
    private static int queryExceptionBudget = 3;

    /**
     * 用一条配方<b>自己的输入</b>造一个合成的 {@link IRecipeCapabilityHolder}（"查询钥匙"）。
     *
     * <h4>为什么能这么造（不是想当然，是照着反编译的实现对齐的）</h4>
     * {@code GTRecipeLookup.fromHolder(holder)}（{@code GTRecipeLookup.java:301-316}）只做三件事：
     * <pre>
     *   holder.getCapabilitiesProxy().row(IO.IN).forEach((cap, handlers) -> {
     *      if (cap.isRecipeSearchFilter() &amp;&amp; !handlers.isEmpty())
     *         for (IRecipeHandler handler : handlers)
     *            if (!handler.isProxy())
     *               for (Object c : cap.compressIngredients(handler.getContents()))
     *                  list.add(cap.convertToMapIngredient(c));
     *   });
     * </pre>
     * ⇒ 只需要：{@code row(IO.IN)} 里有东西、{@code isRecipeSearchFilter()} 为真、
     * {@code isProxy()} 为假、{@code getContents()} 交回内容。<b>不需要机器、不需要世界。</b>
     * <p>🔴 <b>一个不写就静默失效的坑</b>：{@code prepareRecipeFind} 用
     * {@code if (entry.getSize() != -1) size += entry.getSize();} 累加总量，
     * 而 {@code IRecipeHandler.getSize()} 的<b>默认实现返回 -1</b>
     * ⇒ 不覆写它的话 {@code totalSize} 恒为 0 ⇒ {@code prepareRecipeFind} 直接返回 null
     * ⇒ <b>{@code find} 永远返回 null，看起来就像"索引是空的"</b>。本类覆写成实际条数。
     *
     * @return 没有任何可作索引键的输入 ⇒ {@code null}（调用方据此跳过这条配方）
     */
    private static IRecipeCapabilityHolder holderFor(GTRecipe r) {
        try {
            final Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> table = HashBasedTable.create();
            int keys = 0;
            keys += fillCapabilityTable(table, r.inputs);
            keys += fillCapabilityTable(table, r.tickInputs);
            if (keys == 0) {
                return null;
            }
            return new IRecipeCapabilityHolder() {
                @Override
                public Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> getCapabilitiesProxy() {
                    return table;
                }
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把一张 {@code cap → contents} 表按"索引键"的口径塞进 holder 的能力表。返回塞进去的内容条数。 */
    private static int fillCapabilityTable(Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> table,
                                          java.util.Map<RecipeCapability<?>, List<Content>> source) {
        int keys = 0;
        if (source == null) {
            return 0;
        }
        for (java.util.Map.Entry<RecipeCapability<?>, List<Content>> e : source.entrySet()) {
            final RecipeCapability<?> cap = e.getKey();
            final List<Content> contents = e.getValue();
            if (cap == null || contents == null || contents.isEmpty() || !cap.isRecipeSearchFilter()) {
                continue;
            }
            final List<Object> raw = new ArrayList<>(contents.size());
            for (Content c : contents) {
                if (c != null && c.getContent() != null) {
                    raw.add(c.getContent());
                }
            }
            if (raw.isEmpty()) {
                continue;
            }
            final List<IRecipeHandler<?>> handlers = new ArrayList<>(1);
            handlers.add(new ConstHandler(cap, raw));
            table.put(IO.IN, cap, handlers);
            keys += raw.size();
        }
        return keys;
    }

    /**
     * 一个"内容恒定"的配方处理器 —— 只为了 {@code fromHolder} 能算出索引键。
     * <p>它<b>不参与任何真实的配方匹配</b>（{@code handleRecipeInner} 原样返回剩余量，永不消耗）。
     */
    private static final class ConstHandler implements IRecipeHandler<Object> {

        private final RecipeCapability<?> capability;
        private final List<Object> contents;

        ConstHandler(RecipeCapability<?> capability, List<Object> contents) {
            this.capability = capability;
            this.contents = contents;
        }

        @Override
        public List<Object> handleRecipeInner(IO io, GTRecipe recipe, List<Object> left, String slotName, boolean simulate) {
            return left;
        }

        @Override
        public List<Object> getContents() {
            return contents;
        }

        @Override
        public double getTotalContentAmount() {
            return 0.0D;
        }

        /** 🔴 必须覆写：{@code IRecipeHandler} 的默认值是 {@code -1}，会让 {@code prepareRecipeFind} 直接放弃。 */
        @Override
        public int getSize() {
            return contents.size();
        }

        @Override
        public boolean isProxy() {
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public RecipeCapability<Object> getCapability() {
            return (RecipeCapability<Object>) capability;
        }
    }

    // ================================================================== 日志与判定的小工具

    private static int inputKeyCount(IRecipeCapabilityHolder holder) {
        try {
            int n = 0;
            for (List<IRecipeHandler<?>> hs : holder.getCapabilitiesProxy().row(IO.IN).values()) {
                for (IRecipeHandler<?> h : hs) {
                    n += Math.max(0, h.getSize());
                }
            }
            return n;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 该类型索引里有多少条<b>去重后</b>的配方（重建索引的规模）。 */
    private static int countIndexRecipes(GTRecipeType type) {
        try {
            final Set<GTRecipe> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            type.getLookup().getLookup().getRecipes(true).forEach(r -> {
                if (r != null) {
                    seen.add(r);
                }
            });
            return seen.size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void logRead(String caseName, String via, Read r, int expect) {
        ShanhaiMod.LOGGER.info("{} read case={} by={} found={} id={} duration={} obj={} expect={} result={}",
                PREFIX, caseName, via, r.found(), r.idText(), r.duration(), hex(r.recipe()),
                expect, classify(r, expect));
    }

    /** 「按输入查」这一步走的是哪条路 —— 直接写进日志的 {@code by=} 字段，三条互不覆盖。 */
    private static String inputLabel(Target t) {
        return inputLabel(KeySource.TREE_PATH);
    }

    private static String inputLabel(KeySource src) {
        switch (src) {
            case TREE_PATH:
                return "input(tree_path)";
            case HOLDER:
                return "input(holder)";
            default:
                return "input(derived_key)";
        }
    }

    /**
     * 一次编辑拍里四条按输入查的读数。
     *
     * <h4>🔴 本轮（第 5 版）判据换成 {@code input(vanilla_tree)}</h4>
     * 2026-10-04 定案：本整合包里 {@code GTRecipeLookup.recurseIngredientTreeFindRecipe} 被
     * <b>gtlcore 的 {@code GTRecipeLookupMixin} {@code @Overwrite} 掉了</b>，新实现依赖一个只有
     * "机器自己发起查询"时才会被赋值的 {@code @Unique} 字段
     * ⇒ 外部直接调那个方法<b>恒返回 null（不抛异常）</b>，合成的 holder 也会被
     * {@code prepareRecipeFind} 判成"不认识"而交回空键表。
     * <br>⇒ 判据改用 {@link ShanhaiRecipeIndexDiag#vanillaFind}(原版算法逐行复刻，同一棵树、同一把键)，
     * 另外三条作为对比读数一并打出（**不许因为判据那条绿了就替它们背书**）。
     *
     * @return 1 = 判据那条没到期望值
     */
    private static int logInputReads(String caseName, Target t, int expect) {
        final Read byVanilla = readByVanillaPath(t);
        final Read byGt6 = readByInput(t, KeySource.TREE_PATH);
        final Read byHolder = readByInput(t, KeySource.HOLDER);
        final Read byDerived = readByInput(t, KeySource.DERIVED);
        logRead(caseName, "input(vanilla_tree)", byVanilla, expect);
        logRead(caseName, "input(gt6_tree)", byGt6, expect);
        logRead(caseName, "input(holder)", byHolder, expect);
        logRead(caseName, "input(derived_key)", byDerived, expect);
        return judgeInput(caseName, byVanilla, expect);
    }

    /** 判据那条：原版算法的本地复刻 + 树上收割来的键路径。 */
    private static Read readByVanillaPath(Target t) {
        try {
            final java.util.function.Predicate<GTRecipe> pred =
                    r -> r != null && r.id != null && r.id.equals(t.id());
            final GTRecipe hit = ShanhaiRecipeIndexDiag.vanillaFind(t.type(), t.pathKeys(), pred);
            return new Read(hit != null, hit);
        } catch (Throwable ignored) {
            return new Read(false, null);
        }
    }

    /**
     * 只判"树自己给的键路径"那一条；另两条的 MISS <b>不构成 FAIL</b>
     * （它们是事实记录 —— "机器那条 holder 路通不通"是另一件事，由 {@code resolve} 的
     * {@code holder_path=} 与 {@code p4 query} 那两行单独报）。
     *
     * @return 1 = 这一条没到期望值（调用方累加进 {@code selftest done fail=}）
     */
    private static int judgeInput(String caseName, Read r, int expect) {
        if (classify(r, expect).equals("MATCH")) {
            return 0;
        }
        ShanhaiMod.LOGGER.error("{} FAIL case={} by=input(vanilla_tree) got={} expect={} found={}",
                PREFIX, caseName, r.duration(), expect, r.found());
        return 1;
    }

    private static String classify(Read r, int expect) {
        if (!r.found()) {
            return "NOT_FOUND";
        }
        return r.duration() == expect ? "MATCH" : "MISMATCH";
    }

    /** 判定并打 {@code verdict sub=} 行；只判 {@code by=id} 与 {@code by=input}（{@code manager} 不是判据）。 */
    private static int judge(String caseName, Read r, int expect, String via) {
        if (classify(r, expect).equals("MATCH")) {
            return 0;
        }
        ShanhaiMod.LOGGER.error("{} FAIL case={} by={} got={} expect={} found={}",
                PREFIX, caseName, via, r.duration(), expect, r.found());
        return 1;
    }

    private static String hex(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    /**
     * 一次重建的<b>单行、可解析</b>读数。时间一律用<b>整数微秒</b>（不用 {@code %.3f} 毫秒：
     * 那种写法会带 locale 的小数点，机器解析多一个坑）。
     * <pre>
     *   kept=&lt;n&gt; added=&lt;n&gt; dropped=&lt;n&gt; replaced=&lt;yes|no&gt;
     *   collect_us=&lt;n&gt; clear_us=&lt;n&gt; add_us=&lt;n&gt; total_us=&lt;n&gt; per_recipe_us=&lt;n&gt;
     * </pre>
     * 拆开量的意义：{@code collect_us} = 一次完整树遍历（去重也要哈希），
     * {@code clear_us} = 只清两张根表，{@code add_us} = 逐条重新推输入键并插树。
     * {@code per_recipe_us} = {@code add_us / kept}（单条插入的平均成本，也是"增量插一条"的直接读数）。
     */
    private static String rewriteText(Rewrite rw) {
        final long per = rw.kept() > 0 ? rw.addUs() / rw.kept() : -1L;
        return "kept=" + rw.kept()
                + " added=" + rw.added()
                + " dropped=" + rw.dropped()
                + " replaced=" + (rw.replaced() ? "yes" : "no")
                + " collect_us=" + rw.collectUs()
                + " clear_us=" + rw.clearUs()
                + " add_us=" + rw.addUs()
                + " total_us=" + rw.totalUs()
                + " per_recipe_us=" + per;
    }

    // ================================================================== 成本普查：整包每个类型各重建一次

    /**
     * <b>把「全量重建一次」的代价量到底</b> —— 对配方表里出现的每一个 {@code GTRecipeType}
     * 各做一次原地重建（同一批对象，没有换任何东西），把三个阶段分开累计。
     *
     * <h4>测量条件（写清楚才能读数）</h4>
     * <ul>
     *   <li><b>时机</b>：服务器已经 {@code Done (}、本类的自检跑完之后（自检先跑 ⇒
     *       本次测量的副作用不会污染 PASS/FAIL 那几行读数）；</li>
     *   <li><b>负载</b>：<b>空载</b> —— 无头专服、没有玩家、没有机器在跑配方、主线程独占；</li>
     *   <li><b>次数</b>：<b>每个类型只量一次</b>（不是取 N 次的最好/平均）。
     *       JIT 预热因此<b>不充分</b>：前面的类型会偏慢。所以每个类型的绝对数字只能当量级读，
     *       而"总耗时 / 总条数"这个比值更有参考价值；</li>
     *   <li><b>取哪一次</b>：取唯一那一次；日志里同时给出条数与三阶段拆分，便于自行判断。</li>
     * </ul>
     *
     * <h4>⚠️ 这个普查本身会做的事</h4>
     * 它对每个类型调一次 {@code removeAllRecipes()} + 逐条 {@code addRecipe()}，
     * 用的还是原来那批对象 ⇒ <b>语义上应当与原来等价</b>；但若某个类型里存在"输入键完全相同的两条配方"，
     * 重建后<b>谁胜出可能因迭代顺序而改变</b> ⇒ 这就是 {@code dropped} 那一列（非 0 即为发生了）。
     * 本方法<b>只读日志、不改配方数据</b>，且是本类最后一个动作。
     */
    private static void sweepAllTypes(MinecraftServer server) {
        try {
            final Set<GTRecipeType> types = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                if (r instanceof GTRecipe gr && gr.recipeType != null) {
                    types.add(gr.recipeType);
                }
            }
            if (types.isEmpty()) {
                ShanhaiMod.LOGGER.info("{} sweep types=0 note=配方表里没有任何 GTRecipe ⇒ 不量", PREFIX);
                return;
            }
            long collectSum = 0L;
            long clearSum = 0L;
            long addSum = 0L;
            long totalSum = 0L;
            int recipeSum = 0;
            int droppedSum = 0;
            final List<String> perType = new ArrayList<>();
            for (GTRecipeType t : types) {
                final Rewrite rw = rebuild(t, null, null);
                collectSum += rw.collectUs();
                clearSum += rw.clearUs();
                addSum += rw.addUs();
                totalSum += rw.totalUs();
                recipeSum += rw.kept();
                droppedSum += rw.dropped();
                perType.add(t.registryName + "=" + rw.kept() + "条/" + rw.totalUs() + "us");
            }
            perType.sort((a, b) -> Long.compare(parseUs(b), parseUs(a)));
            ShanhaiMod.LOGGER.info("{} sweep types={} recipes={} collect_us={} clear_us={} add_us={} "
                            + "total_us={} per_recipe_us={} dropped_total={} 口径=空载单次(未预热)",
                    PREFIX, types.size(), recipeSum, collectSum, clearSum, addSum, totalSum,
                    recipeSum > 0 ? (addSum / recipeSum) : -1L, droppedSum);
            final int show = Math.min(5, perType.size());
            for (int i = 0; i < show; i++) {
                ShanhaiMod.LOGGER.info("{} sweep_top rank={} {}", PREFIX, i + 1, perType.get(i));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} sweep FAIL reason={}", PREFIX, t);
        }
    }

    /** 从 {@code "<name>=<n>条/<us>us"} 里把 us 抠出来排序用（只为日志排序，不参与任何判定）。 */
    private static long parseUs(String s) {
        final int slash = s.lastIndexOf('/');
        final int us = s.lastIndexOf("us");
        if (slash < 0 || us <= slash) {
            return -1L;
        }
        try {
            return Long.parseLong(s.substring(slash + 1, us));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String safeGetenv(String key) {
        try {
            final String v = System.getenv(key);
            return v == null ? "(unset)" : v;
        } catch (Throwable ignored) {
            return "(error)";
        }
    }

    /** {@link #editAndRead} 的返回值。 */
    private record EditOutcome(int fails, boolean applied, GTRecipe registered) {}
}
