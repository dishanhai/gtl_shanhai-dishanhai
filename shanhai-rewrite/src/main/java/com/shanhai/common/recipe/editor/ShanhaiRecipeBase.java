package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>底本快照 + 编辑台账</b> —— 「改完立刻生效」这件事的正确数据模型。
 *
 * <h2>🔴 为什么必须有底本（这条是被上游的注释直接判过死刑的写法换来的）</h2>
 * 上游那套（{@code DShanhaiRecipeModifierAPI}，反编译注释原文）：
 * <blockquote>
 * 「旧版本快照可能已由多个 Branch 路径收集出重复 ID；重建前统一压成一份」
 * </blockquote>
 * 它的纪律是：<b>留一份"底本"（第一次见到配方时的原样），重建只从底本集合出发</b>。
 * 从"当前已经被改过的索引"里收集会同时得两个病：
 * <ol>
 *   <li>🔴 <b>把上一次的改动再套一遍</b> —— 连改两次就累加（本刀第一版正是这么写的，已改掉）；</li>
 *   <li>🔴 <b>同一条配方被多条 Branch 路径收集 ⇒ 重复 ID</b> ⇒ 索引里同一条出现两次。</li>
 * </ol>
 *
 * <h2>模型</h2>
 * <pre>
 *   BASE   : id → 第一次从 RecipeManager 见到的那个 GTRecipe 对象（原样，永不修改）
 *   LEDGER : id → Edit{ duration | removed }
 *   重建   : for 每条 BASE 里属于该类型的配方:
 *              removed  → 跳过（显式记数）
 *              有 duration → BASE.copy() 后改 duration（不是"在当前值上再改"）
 *              没有编辑 → 直接用 BASE 里那个原对象
 * </pre>
 * ⇒ 连改 N 次，结果永远只取决于<b>最后一次</b>台账值 ⇒ 不累加。
 * ⇒ 引用同一性也保住了：没被编辑过的配方，索引里拿到的还是原来那个对象。
 *
 * <h2>⚠️ 边界（如实写清，不当成"已覆盖"）</h2>
 * 底本只在<b>第一次</b>建立时抓一次。{@code /reload} 之后 {@code RecipeManager} 里换成了新对象，
 * 底本会变旧 —— 本类为此提供 {@link #recapture}（显式调用），**不自动触发**：
 * 自动重抓会在"用户刚改完、还没重启"的时候把台账和底本一起换掉，
 * 那才是真正的数据丢失。详见 {@link ShanhaiRecipeEditorOps} 类注释里关于 {@code /reload} 的那一节。
 */
public final class ShanhaiRecipeBase {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /**
     * 台账条目。
     *
     * <p><b>第二刀扩容</b>：从 {@code (duration, removed)} 扩成 {@code duration + io + eut + removed}。
     * 关键约定：<b>{@code null} 与"空"是两件事</b> —— {@code inputs == null} 表示"这次不动输入"，
     * 而 {@code inputs = {}} 表示"把输入清空"（用户右键删光了）。写成 {@code Map} 会因为无法区分这两者
     * 而把"没改"读成"清空"。
     *
     * <p>{@code hasDuration} 是必需的：只有 IO 被改过时 {@code duration} 没有意义，
     * 沿用老的 {@code duration == -1} 哨兵会让 {@link #effectiveDuration} 返回 -1。
     */
    public record Edit(int duration, boolean removed, boolean hasDuration,
                       com.google.gson.JsonObject inputs,
                       com.google.gson.JsonObject outputs,
                       com.google.gson.JsonObject tickInputs,
                       boolean hasEut, long eut,
                       boolean hasConditions,
                       com.google.gson.JsonArray conditions) {

        public static final Edit EMPTY =
                new Edit(-1, false, false, null, null, null, false, 0L, false, null);

        public boolean hasIo() {
            return inputs != null || outputs != null || tickInputs != null;
        }

        public Edit withDuration(int d) {
            return new Edit(d, removed, true, inputs, outputs, tickInputs, hasEut, eut,
                    hasConditions, conditions);
        }

        public Edit withoutDuration() {
            return new Edit(-1, removed, false, inputs, outputs, tickInputs, hasEut, eut,
                    hasConditions, conditions);
        }

        /** 只覆盖传入的那几张表（null = 不动那一张）。 */
        public Edit withIo(com.google.gson.JsonObject in, com.google.gson.JsonObject out,
                           com.google.gson.JsonObject tickIn) {
            return new Edit(duration, removed, hasDuration,
                    in != null ? in : inputs,
                    out != null ? out : outputs,
                    tickIn != null ? tickIn : tickInputs,
                    hasEut, eut, hasConditions, conditions);
        }

        public Edit withEut(long v) {
            return new Edit(duration, removed, hasDuration, inputs, outputs, tickInputs, true, v,
                    hasConditions, conditions);
        }

        public Edit withoutEut() {
            return new Edit(duration, removed, hasDuration, inputs, outputs, tickInputs, false, 0L,
                    hasConditions, conditions);
        }

        /**
         * 🆕 2026-10-05（B 组）：额外条件那一份（{@code null} = 这次不动条件）。
         *
         * <p>口径与 IO 一致：{@code null} 与"空数组"是两件事 —— 空数组的语义是
         * <b>"把这条配方的条件清空"</b>（用户删光了），{@code hasConditions=false} 才是"没改"。
         */
        public Edit withConditions(com.google.gson.JsonArray arr) {
            return new Edit(duration, removed, hasDuration, inputs, outputs, tickInputs, hasEut, eut,
                    true, arr);
        }

        public Edit withoutConditions() {
            return new Edit(duration, removed, hasDuration, inputs, outputs, tickInputs, hasEut, eut,
                    false, null);
        }

        public Edit asRemoved() {
            return new Edit(-1, true, hasDuration, inputs, outputs, tickInputs, hasEut, eut,
                    hasConditions, conditions);
        }

        public Edit asPresent() {
            return new Edit(duration, false, hasDuration, inputs, outputs, tickInputs, hasEut, eut,
                    hasConditions, conditions);
        }
    }

    /** 🔴 深拷贝：台账里存的 JSON 是"我给的值"，绝不能与调用方后续修改共享一份对象。 */
    private static com.google.gson.JsonObject copyJson(com.google.gson.JsonObject o) {
        return o == null ? null : o.deepCopy();
    }

    private static com.google.gson.JsonArray copyJson(com.google.gson.JsonArray a) {
        return a == null ? null : a.deepCopy();
    }

    private static final Map<ResourceLocation, GTRecipe> BASE = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Edit> LEDGER = new LinkedHashMap<>();

    /**
     * "被<b>我们</b>删过"的 id 集合 —— 只在"放回去"时用来判断该不该重新插回原版表。
     *
     * <p>为什么需要它：{@code restore} 时那条配方已经不在原版表里了，
     * 而"把原版表按台账重写一遍"这个动作只会<b>改</b>表里已有的条目、不会<b>插</b>回去
     * ⇒ 没有这个集合，"删掉再放回"会静默失败（索引里回来了、原版表里没回来）。
     * 之所以不能简单地"凡是底本里有、表里没有的就插回去"：底本可能已经过时
     * （{@code /reload} 之后有些配方会被别的来源整体删掉，插回去就是复活不该存在的东西）。
     */
    private static final java.util.Set<ResourceLocation> EVER_REMOVED = new java.util.LinkedHashSet<>();

    /**
     * "被<b>我们</b>动过"的 id（改过时长或被删过）—— 台账<b>撤销</b>之后靠它把原版表里那条
     * 还原成<b>底本原对象</b>。
     *
     * <p>🔴 没有这个集合就会出现一个真 bug（2026-10-05 冒烟实测到）：
     * {@code syncVanillaFromBase} 只处理"台账里有条目"的 id，而 {@code restore} 会把台账<u>清空</u>
     * ⇒ 那条 id 落进"没有台账 ⇒ 原样留着"的分支 ⇒ <b>原版表里留着的还是上一拍的改过副本</b>。
     * 读数现场：{@code case=apply_restored ok=false expect=100 index=100 vanilla=2100}。
     * 索引路是对的（它由底本重建），原版路是错的 —— <b>正是"两处配方表会分裂"那个病的活体样本。</b>
     */
    private static final java.util.Set<ResourceLocation> EVER_TOUCHED = new java.util.LinkedHashSet<>();

    private static boolean captured = false;
    private static int dupIds = 0;
    private static int capturedCount = 0;
    private static String capturedAt = "(never)";

    private ShanhaiRecipeBase() {}

    // ------------------------------------------------------------------ 抓底本

    /** 第一次见到配方时抓一次底本；之后再调是空操作（<b>这是"不累加"的前提</b>）。 */
    public static synchronized void captureIfAbsent(MinecraftServer server) {
        if (captured) {
            return;
        }
        capture(server);
    }

    /**
     * 显式重抓（{@code /shanhai edit recapture}）：丢掉台账、重新抓底本。
     *
     * <p>用在"确实发生了 {@code /reload}、底本已旧"的场合。<b>它会丢掉本局的编辑台账</b>
     * —— 这是如实的，不是 bug：{@code /reload} 本来就已经把那些改动从表里洗掉了。
     */
    public static synchronized int recapture(MinecraftServer server) {
        LEDGER.clear();
        // 🆕 2026-10-05：台账清了 ⇒ duration 那张表也回开机快照（跟 clearEdit/clearAllEdits 同一条纪律）
        ShanhaiRecipeDuration.resetAllOriginals();
        // 🆕 第 5 轮（#6）：条件那张表同理（"当前生效值"回滚到开机那一份，见 COND_CURRENT 的注释）
        resetAllCurrentConditions();
        captured = false;
        capture(server);
        ShanhaiMod.LOGGER.warn("{} base_recaptured entries={} dup_ids={} (台账已清空)",
                PREFIX, capturedCount, dupIds);
        return capturedCount;
    }

    private static void capture(MinecraftServer server) {
        BASE.clear();
        dupIds = 0;
        int n = 0;
        for (var r : server.getRecipeManager().getRecipes()) {
            if (!(r instanceof GTRecipe gt) || gt.id == null) {
                continue;
            }
            if (BASE.containsKey(gt.id)) {
                // 🔴 这条就是上游注释里点名的病：同一条 id 被多条路径/多个来源收集。
                //    这里【记数并跳过】，绝不静默。
                dupIds++;
                continue;
            }
            BASE.put(gt.id, gt);
            n++;
        }
        // 🔴🔴 2026-10-06 P0（用户报：「我修改配方之后，或者重新 edit restore 之后，
        //    配方编辑器的第一面中的配方数都会变成 0（除了工作台那些特殊配方）」）：
        //    **空底本绝不许被记成"抓住了"**。
        //
        // <h4>为什么要在这里拦</h4>
        // 抓底本是【懒】的（第一次编辑时才做）。如果这一次 {@code getRecipes()} 里一条 GTRecipe
        // 都没有，原来那句 {@code captured = true} 会把"底本 = 0 条"钉成【本进程的既成事实】：
        // {@link #pristine} 从此恒返回 null，而两处消费底本的地方都把"底本里没有它"
        // 读成"这条配方该删"——
        //   · {@code ShanhaiRecipeEditorOps#syncVanillaFromBase} ⇒ 把整张原版表里的 GT 配方删光
        //     （运行期读数：{@code vanilla_tables_stale_gt_dropped count=54033}）；
        //   · {@code ShanhaiRecipeEditorOps#rebuildTypeFromBase} ⇒ 把一个类型的 GT 索引树清空
        //     （运行期读数：{@code index_rebuild type=gtceu:zero_point_conversion tree_before=2 wanted=0 tree_after=0}）。
        // ⇒ 不置 captured：下一次还有机会在表完好时再抓一次（代价只是多扫一遍配方表）。
        if (n == 0) {
            capturedCount = 0;
            capturedAt = java.time.LocalTime.now().withNano(0).toString();
            ShanhaiMod.LOGGER.error("{} base_capture_refused_empty 这一次 getRecipes() 里一条 GTRecipe 都没有"
                            + " ⇒ 不把「空底本」记成既成事实（否则 pristine() 恒 null，"
                            + "两处「底本里没有它就删」会把整张表清空）。captured 仍为 false，下次会重抓。at={}",
                    PREFIX, capturedAt);
            return;
        }
        capturedCount = n;
        captured = true;
        capturedAt = java.time.LocalTime.now().withNano(0).toString();
        ShanhaiMod.LOGGER.info("{} base_captured count={} dup_ids_skipped={} at={}",
                PREFIX, n, dupIds, capturedAt);
        // 🔴 2026-10-05 第 5 轮（#6）：底本建好那一拍，把"开机那一刻的条件"也存成只读快照
        //    （与 duration 的 ORIG_BOOT 同一拍、同一个理由）。
        try {
            installBootConditions();
            ShanhaiMod.LOGGER.info("{} conditions_boot_installed snapshot_ids={}",
                    PREFIX, COND_BOOT.size());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} conditions_boot_install_failed {}", PREFIX, t.toString());
        }
        // 🔴 2026-10-05（duration 原始值）：底本一建立就【顺手】把"原始 → 实际"的映射实测出来。
        //    时机是必需的：底本是唯一同时握着"实际值"的那份快照，而原始值来自 KubeJS 窗口，
        //    两者只有在【底本建好、且还没被任何编辑污染】的那一刻才配得上。
        try {
            ShanhaiRecipeDuration.calibrateIfNeeded(BASE);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} duration_scale calibrate_failed {}", PREFIX, t.toString());
        }
        if (dupIds > 0) {
            ShanhaiMod.LOGGER.warn("{} base_duplicate_ids={} 已按 id 压成一份（后面的那份被丢弃，没有被静默保留）",
                    PREFIX, dupIds);
        }
    }

    // ------------------------------------------------------------------ 查询

    public static synchronized boolean isCaptured() {
        return captured;
    }

    public static synchronized int size() {
        return BASE.size();
    }

    public static synchronized int dupIdsSkipped() {
        return dupIds;
    }

    public static synchronized String statsLine() {
        return "base_count=" + capturedCount + " dup_ids_skipped=" + dupIds
                + " ledger_size=" + LEDGER.size() + " captured_at=" + capturedAt;
    }

    /** 那条配方的<b>原样</b>对象（没有就 null）。 */
    public static synchronized GTRecipe pristine(ResourceLocation id) {
        return id == null ? null : BASE.get(id);
    }

    /**
     * 🆕 2026-10-05 第 7 轮（队列 #3「新建配方」）：把一条<b>新造出来的</b>配方登记进底本。
     *
     * <h4>🔴 为什么必须登记进底本，而不是只往活索引里塞</h4>
     * "编辑立刻生效"这条路是 {@link ShanhaiRecipeEditorOps#rebuildTypeFromBase} 把 GT 的
     * {@code GTRecipeLookup} <b>按底本 ＋ 台账整棵重建</b>。
     * <b>不在底本里</b>的新配方 ⇒ <b>用户下一次改这个类型的任何一条配方时，新配方就静默消失了</b>
     * —— 而"少一条"与"本来就没有"在界面上长得一样（本工程最怕的那一类失败）。
     *
     * <p>⚠️ 本方法<b>只登记</b>，不重建索引、不写原版表、不落盘 —— 那三件事由
     * {@link ShanhaiRecipeEditorOps#addRecipeFromJson} 按顺序做。
     */
    public static synchronized boolean registerNew(GTRecipe recipe) {
        if (recipe == null || recipe.id == null) {
            return false;
        }
        if (BASE.containsKey(recipe.id)) {
            // 🔴 撞 id 一律拒收并留痕，绝不静默覆盖既有配方
            ShanhaiMod.LOGGER.error("{} base_register_new_refused id={} -> 底本里已经有这个 id（拒绝覆盖）",
                    PREFIX, recipe.id);
            return false;
        }
        BASE.put(recipe.id, recipe);
        ShanhaiMod.LOGGER.info("{} base_register_new id={} type={} base_size={}",
                PREFIX, recipe.id,
                recipe.getType() == null ? "?" : recipe.getType().registryName, BASE.size());
        return true;
    }

    /** 该配方当前的台账（没有编辑就 null）。 */
    public static synchronized Edit editOf(ResourceLocation id) {
        return id == null ? null : LEDGER.get(id);
    }

    /**
     * 🔴🔴 <b>把"编辑器新建的那条"从底本里彻底去掉</b>（{@code /shanhai edit restore} 与「恢复原样」用它）。
     *
     * <h4>为什么必须有（用户 2026-10-05 的截图与判据）</h4>
     * 用户原话：「{@code /shanhai edit restore} 之后，【编辑器自己的列表】与【卡片数据】要一起重建……
     * 卡片上还留着刚被抹掉的 {@code new_recipe_1}，但它的<b>输入/产物是空的</b>
     * ⇒ <b>列表用旧快照、数据现读 ⇒ 剩一个空壳</b>」。
     *
     * <p>根因在两层：① 面板那份 id 列表是旧快照（已由
     * {@code ShanhaiRecipeEditorWorkspace#refreshListIfTableChanged} 修掉）；
     * ② <b>更根本的</b>：新建那条被 {@link #registerNew} 记进了底本 ⇒
     * "清掉覆盖条目"之后 {@code rebuildTypeFromBase} 仍会把它从底本里重建出来 ⇒
     * <b>它变成一条"这次会话里还在、重启就没了、而且 IO 是空壳"的幽灵配方</b>。
     *
     * <p>语义（与"恢复原样 = 当作从没编辑过"同一条）：那条 {@code op=add} 的覆盖条目被抹掉 ⇒
     * 这条配方就等于<b>从来没被创建过</b> ⇒ 底本、台账里都不许再留它。
     *
     * @return true = 底本里确实有这样一条（被去掉了）
     */
    public static synchronized boolean forgetNew(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        final GTRecipe removed = BASE.remove(id);
        LEDGER.remove(id);
        if (removed != null) {
            ShanhaiMod.LOGGER.info("{} base_forget_new id={} type={} base_size={} "
                            + "（恢复原样：新建的那条连底本一起抹掉，否则它会变成重启就没、且 IO 空壳的幽灵）",
                    PREFIX, id, removed.getType() == null ? "?" : removed.getType().registryName, BASE.size());
        }
        return removed != null;
    }

    /** 该配方<b>此刻应有</b>的时长（台账优先，否则底本；都没有则 -1）。<b>口径 = 原始值</b>。 */
    public static synchronized int effectiveDuration(ResourceLocation id) {
        final Edit e = LEDGER.get(id);
        if (e != null) {
            if (e.removed()) {
                return -1;
            }
            if (e.hasDuration()) {
                return e.duration();
            }
        }
        final GTRecipe b = BASE.get(id);
        // 🔴 2026-10-05（duration 原始值）：台账与覆盖文件里存的都是【原始值】，
        //    所以这里也必须用原始值口径回报 —— 否则调用方拿到一个被 gtlcore 乘过的数，
        //    再拿它当"原值"写回去，就会乘两次（机器耗时差 1000 倍）。
        return b == null ? -1 : ShanhaiRecipeDuration.originalOf(b);
    }

    /**
     * 这条配方的时长，在 {@code GTRecipe.duration} 上<b>应该是多少</b>
     * （= 把"原始值"按实测出来的映射换算成"实际值"）。
     *
     * <p>映射判不出来（IDENTITY / UNFITTED）时原样返回 ⇒ 与改动之前逐字节相同。
     */
    public static int liveDurationOf(GTRecipeType type, int original) {
        return ShanhaiRecipeDuration.toLive(type, original);
    }

    /** 该配方<b>此刻应有</b>的 EU/t（台账优先，否则底本；都没有则 -1）。 */
    public static synchronized long effectiveEut(ResourceLocation id) {
        final Edit e = LEDGER.get(id);
        if (e != null && !e.removed() && e.hasEut()) {
            return e.eut();
        }
        final GTRecipe b = BASE.get(id);
        return b == null ? -1L : ShanhaiRecipeIoApply.euOf(b);
    }

    /**
     * 该类型<b>重建索引时应当逐条放进去</b>的清单（底本 + 台账，已按 id 去重）。
     *
     * <p>返回的列表里<b>不会</b>有 null：被删掉的条目直接不进来（显式记数在日志里）。
     * 之所以强调这一条：上游的删除语义是"入口返回 null"，而 null 混进重建循环会
     * <b>让它静默少一条</b> —— 少掉的那条与"本来就没有"在日志上长得一样。
     */
    public static synchronized List<GTRecipe> finalRecipesOf(GTRecipeType type) {
        final List<GTRecipe> out = new ArrayList<>();
        int removed = 0;
        int replaced = 0;
        for (Map.Entry<ResourceLocation, GTRecipe> en : BASE.entrySet()) {
            final GTRecipe b = en.getValue();
            if (type != null && b.getType() != type) {
                continue;
            }
            final Edit e = LEDGER.get(en.getKey());
            if (e == null) {
                out.add(b);
                continue;
            }
            if (e.removed()) {
                removed++;
                continue;
            }
            if (!e.hasDuration() && !e.hasIo() && !e.hasEut() && !e.hasConditions()) {
                out.add(b);
                continue;
            }
            if (e.hasDuration() && !e.hasIo() && !e.hasEut() && !e.hasConditions()
                    && liveDurationOf(b.getType(), e.duration()) == b.duration) {
                out.add(b);
                continue;
            }
            // 🔴 从【底本】造副本，绝不在"当前值"上再改 ⇒ 连改 N 次不累加
            final GTRecipe copy = b.copy();
            // 🔴🔴 2026-10-05 P0 运行期取证：GTRecipe.copy() 把 `data` 这个 CompoundTag
            //      【按引用】传给新对象（conditions / ingredientActions 都是 new ArrayList，
            //      只有 data 直接传引用）。而 euTier 就存在 data 里 ⇒ 不先 data.copy()
            //      就写副本的 euTier = 把【底本】也一起改了。现场读数：
            //        P0-NOFIX 写到副本后读原件 data.euTier = 9999 (was 7)  VERDICT=RED
            //        P0-FIX   先 data.copy() 后读原件 = 7              VERDICT=GREEN
            //      ⇒ 这一行是本刀最贵的一行，它不是防御性代码，是实测出来的必需品。
            copy.data = b.data.copy();
            if (e.hasDuration()) {
                // 🔴 台账里那份是【原始值】；落到 GTRecipe 上的必须是【实际值】（= 按实测映射换算）。
                copy.duration = liveDurationOf(b.getType(), e.duration());
            }
            applyIoTo(e, copy);
            applyEutTo(e, copy);
            applyConditionsTo(e, copy);
            copy.id = b.id;
            out.add(copy);
            replaced++;
        }
        ShanhaiMod.LOGGER.info("{} base_final type={} base_of_type={} removed_by_ledger={} replaced_by_ledger={} out={}",
                PREFIX, type == null || type.registryName == null ? "?" : type.registryName,
                out.size() + removed, removed, replaced, out.size());
        return out;
    }

    /** 该类型在底本里的条数（判据用：重建后应等于 out + removed）。 */
    public static synchronized int baseCountOf(GTRecipeType type) {        int n = 0;
        for (GTRecipe b : BASE.values()) {
            if (type == null || b.getType() == type) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 台账

    public static synchronized void setDuration(ResourceLocation id, int value) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final GTRecipe b = BASE.get(id);
        final Edit cur = LEDGER.get(id);
        // 🔴 口径：value 是【原始值】。判"改回原值"要拿【实际值】去比（见 effectiveDuration 的注释）。
        if (b != null && liveDurationOf(b.getType(), value) == b.duration) {
            // 改回原值 = 撤掉【时长那一条】编辑（IO/EU 那部分要保持不动）
            // 🆕 2026-10-05：这条公式成立 ⇔ value 就是这条配方在源声明里的那个数
            //   ⇒ 把 duration 那张表也跟回【开机快照】（用户点单：恢复原样时表要跟着回原始值）。
            ShanhaiRecipeDuration.resetOriginal(id);
            if (cur == null) {
                return;
            }
            if (cur.hasIo() || cur.hasEut() || cur.hasConditions()) {
                LEDGER.put(id, cur.withoutDuration());
            } else {
                LEDGER.remove(id);
            }
            return;
        }
        // 🆕 2026-10-05（第 8 局修的显示 bug）：写台账的同一拍把【id → 原始值】那张表同步成新值。
        //    不同步 = 面板重开时读到的是开机那一份 ⇒ 用户看到「改完重开又变回 1200」。
        //    判据行：duration_orig_updated id=… 1200 -> 5000
        ShanhaiRecipeDuration.setCurrentOriginal(id, value);
        LEDGER.put(id, (cur == null ? Edit.EMPTY : cur).withDuration(value));
    }

    /**
     * 🆕 2026-10-05（B 组）：记下这条配方的<b>额外条件</b>（GT 平铺形状的 JsonArray，整段替换语义）。
     *
     * <p>{@code arr} 传 {@code null} = 这次不动条件；传 {@code []} = 把条件清空。
     * 与 IO 那条纪律一致（见 {@link Edit} 的注释）。
     */
    public static synchronized void setConditions(ResourceLocation id, com.google.gson.JsonArray arr) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final Edit cur = LEDGER.get(id);
        LEDGER.put(id, (cur == null ? Edit.EMPTY : cur).asPresent().withConditions(copyJson(arr)));
        // 🔴 2026-10-05 第 5 轮（#6）：与 duration 的 ORIG 同一条纪律 ——
        //    「当前生效的那一份条件」也必须【写台账的同一拍同步】，否则面板读到的永远是开机那一份。
        setCurrentConditions(id, arr);
    }

    /**
     * 🔴 <b>「当前生效的条件」那张表</b> —— 与 {@code ShanhaiRecipeDuration.ORIG / ORIG_BOOT} 同构。
     *
     * <h4>为什么必须再存一张（这是 #6 的修法本体）</h4>
     * 用户原话：「6：额外条件重进存档会丢失」。实测真相是数据没丢（文件里有、重放也 APPLIED），
     * 而是<b>面板读的那一份</b>与"当前生效值"不是同一份 —— 与上一轮"耗时改完重开变回旧值"同一个病。
     * duration 那条是靠"新加一张只读开机快照 {@code ORIG_BOOT} ＋ 写台账同拍同步当前值"修好的，
     * 这里照抄同一条思路：
     * <pre>
     *   COND_CURRENT : id → 当前生效的那一份（写台账同拍同步；恢复原样时回滚）
     *   COND_BOOT    : id → 开机那一份（任何编辑路径都不写；"恢复原样"回落到它）
     * </pre>
     */
    private static final Map<ResourceLocation, com.google.gson.JsonArray> COND_CURRENT = new LinkedHashMap<>();
    private static final Map<ResourceLocation, com.google.gson.JsonArray> COND_BOOT = new LinkedHashMap<>();

    /** 抓底本时顺手把"开机那一刻的条件"存成只读快照（与 duration 的 ORIG_BOOT 同一拍）。 */
    private static void installBootConditions() {
        COND_BOOT.clear();
        COND_CURRENT.clear();
        for (Map.Entry<ResourceLocation, GTRecipe> e : BASE.entrySet()) {
            final com.google.gson.JsonArray a = ShanhaiRecipeConditions.encodeOf(e.getValue());
            COND_BOOT.put(e.getKey(), a.deepCopy());
            COND_CURRENT.put(e.getKey(), a.deepCopy());
        }
    }

    /** 写台账同拍同步"当前生效的条件"（判据行 {@code conditions_current_updated}）。 */
    public static synchronized boolean setCurrentConditions(ResourceLocation id,
                                                            com.google.gson.JsonArray arr) {
        if (id == null) {
            return false;
        }
        final com.google.gson.JsonArray flat = arr == null
                ? new com.google.gson.JsonArray() : arr.deepCopy();
        final com.google.gson.JsonArray old = COND_CURRENT.put(id, flat);
        final boolean changed = old == null || !ShanhaiRecipeConditions.sameAs(old, flat);
        ShanhaiMod.LOGGER.info("{} conditions_current_updated id={} {} -> {} changed={} boot={} "
                        + "（这张表是面板重开时读的那一份；同步之后重开显示的就是新值）",
                PREFIX, id,
                old == null ? "(absent)" : ShanhaiRecipeConditions.summary(old),
                ShanhaiRecipeConditions.summary(flat), changed,
                COND_BOOT.get(id) == null ? "(absent)"
                        : ShanhaiRecipeConditions.summary(COND_BOOT.get(id)));
        return changed;
    }

    /** 恢复原样：这张表跟回开机那一份（与 {@code resetOriginal} 同一条纪律）。 */
    public static synchronized boolean resetCurrentConditions(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        final com.google.gson.JsonArray boot = COND_BOOT.get(id);
        if (boot == null) {
            final boolean removed = COND_CURRENT.remove(id) != null;
            ShanhaiMod.LOGGER.info("{} conditions_current_reset id={} boot_absent removed_key={}",
                    PREFIX, id, removed);
            return removed;
        }
        final com.google.gson.JsonArray old = COND_CURRENT.put(id, boot.deepCopy());
        final boolean changed = old == null || !ShanhaiRecipeConditions.sameAs(old, boot);
        ShanhaiMod.LOGGER.info("{} conditions_current_reset id={} {} -> {} changed={}",
                PREFIX, id, old == null ? "(absent)" : ShanhaiRecipeConditions.summary(old),
                ShanhaiRecipeConditions.summary(boot), changed);
        return changed;
    }

    /** 一键恢复全部：整张表回滚到开机快照。 */
    public static synchronized int resetAllCurrentConditions() {
        int n = 0;
        for (Map.Entry<ResourceLocation, com.google.gson.JsonArray> e : COND_BOOT.entrySet()) {
            final com.google.gson.JsonArray cur = COND_CURRENT.get(e.getKey());
            if (cur == null || !ShanhaiRecipeConditions.sameAs(cur, e.getValue())) {
                COND_CURRENT.put(e.getKey(), e.getValue().deepCopy());
                n++;
            }
        }
        ShanhaiMod.LOGGER.info("{} conditions_current_reset_all rolled_back={} of_boot_snapshot={}",
                PREFIX, n, COND_BOOT.size());
        return n;
    }

    /** 这条 id 在<b>开机那一份</b>里的条件（不可变快照；没有则 null）。面板与自检的对照读数用它。 */
    public static synchronized com.google.gson.JsonArray bootConditions(ResourceLocation id) {
        final com.google.gson.JsonArray a = id == null ? null : COND_BOOT.get(id);
        return a == null ? null : a.deepCopy();
    }

    public static synchronized int currentConditionsCount() {
        return COND_CURRENT.size();
    }

    /** 台账里这条 id 的条件表（没改过就是 null；空数组代表"改成了没有条件"）。 */
    public static synchronized com.google.gson.JsonArray conditionsOf(ResourceLocation id) {
        final Edit e = id == null ? null : LEDGER.get(id);
        return e == null || !e.hasConditions() ? null : e.conditions();
    }

    /**
     * 这条配方<b>此刻应有</b>的条件（台账优先，否则<b>「当前生效」那张表</b>，最后才是底本）。
     *
     * <p>🔴 第 5 轮（#6）：中间那一层（{@link #COND_CURRENT}）就是修法的关键 ——
     * 面板读的是"当前生效值"，不是"开机那一刻的底本"。{@code null} 永远不返回。
     */
    public static synchronized com.google.gson.JsonArray effectiveConditions(ResourceLocation id) {
        final Edit e = LEDGER.get(id);
        if (e != null && !e.removed() && e.hasConditions()) {
            return copyJson(e.conditions() == null ? new com.google.gson.JsonArray() : e.conditions());
        }
        final com.google.gson.JsonArray cur = id == null ? null : COND_CURRENT.get(id);
        if (cur != null) {
            return cur.deepCopy();
        }
        final GTRecipe b = BASE.get(id);
        return b == null ? new com.google.gson.JsonArray()
                : ShanhaiRecipeConditions.encode(b.conditions);
    }

    /**
     * 记下这条配方的 IO 表（GT 形状的 JSON，整段替换语义）。
     * 传 null 的那一张表<b>不动</b>；传 {@code {}} 表示"清空"。
     */
    public static synchronized void setIo(ResourceLocation id,
                                          com.google.gson.JsonObject inputs,
                                          com.google.gson.JsonObject outputs,
                                          com.google.gson.JsonObject tickInputs) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final Edit cur = LEDGER.get(id);
        LEDGER.put(id, (cur == null ? Edit.EMPTY : cur).asPresent()
                .withIo(copyJson(inputs), copyJson(outputs), copyJson(tickInputs)));
    }

    /** 记下这条配方的 EU/t（同拍 {@code data.euTier} 由 {@code applyEutTo} 负责）。 */
    public static synchronized void setEut(ResourceLocation id, long eut) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final Edit cur = LEDGER.get(id);
        LEDGER.put(id, (cur == null ? Edit.EMPTY : cur).asPresent().withEut(eut));
    }

    /** 台账里这条 id 的 IO 表（没改过就是 null）。 */
    public static synchronized Edit ioOf(ResourceLocation id) {
        final Edit e = id == null ? null : LEDGER.get(id);
        return e == null || !e.hasIo() ? null : e;
    }

    private static void applyIoTo(Edit e, GTRecipe copy) {
        if (!e.hasIo()) {
            return;
        }
        if (e.inputs() != null) {
            ShanhaiRecipeIoApply.applyTable(copy, "inputs", e.inputs());
        }
        if (e.outputs() != null) {
            ShanhaiRecipeIoApply.applyTable(copy, "outputs", e.outputs());
        }
        if (e.tickInputs() != null) {
            ShanhaiRecipeIoApply.applyTable(copy, "tickInputs", e.tickInputs());
        }
    }

    /**
     * 🆕 2026-10-05（B 组）：把台账里那一份条件<b>装到副本上</b>。
     *
     * <p>🔴 安全性依据（{@code javap -c GTRecipe.copy()} 实测，不是推断）：
     * {@code copy()} 对 {@code conditions} 走的是 {@code new ArrayList<>(this.conditions)}
     * ⇒ <b>列表本身是新的</b>，所以"整个换掉副本的 conditions"不会碰到底本那一份。
     * （对照：{@code data} 是<b>直接传引用</b> ⇒ 那一个必须先 {@code copy.data = b.data.copy()}，
     * 见 {@link #finalRecipesOf} 里那条已取证的纪律。）
     */
    private static void applyConditionsTo(Edit e, GTRecipe copy) {
        if (!e.hasConditions()) {
            return;
        }
        final java.util.List<com.gregtechceu.gtceu.api.recipe.RecipeCondition> list =
                ShanhaiRecipeConditions.decodeList(e.conditions());
        if (list == null) {
            ShanhaiMod.LOGGER.error("{} conditions_apply_failed id={} -> 台账里那份条件解析不出来，"
                    + "本次【不套用】（宁可不动，也不装一半）", PREFIX, copy.id);
            return;
        }
        copy.conditions.clear();
        copy.conditions.addAll(list);
    }

    private static void applyEutTo(Edit e, GTRecipe copy) {
        if (e.hasEut()) {
            ShanhaiRecipeIoApply.applyEut(copy, e.eut());
        }
    }

    /**
     * 把一条台账条目整体套到 {@code copy} 上（duration ＋ IO ＋ EU）。
     *
     * <p>⚠️ <b>调用方必须先自己做过 {@code copy.data = base.data.copy()}</b> —— 本方法刻意不做，
     * 因为"先 copy data 再套"这个顺序本身就是那条已取证的纪律，藏进方法里会让它再次变成
     * 一条谁都能忘掉的隐形前提。
     */
    public static void applyEditTo(GTRecipe copy, Edit e) {
        if (copy == null || e == null || e.removed()) {
            return;
        }
        if (e.hasDuration()) {
            copy.duration = liveDurationOf(copy.getType(), e.duration());
        }
        applyIoTo(e, copy);
        applyEutTo(e, copy);
        applyConditionsTo(e, copy);
    }

    /**
     * 清空台账，但<b>保留</b>"动过哪些类型"的集合 —— 一键恢复要用它来只重建受影响的那几类。
     *
     * @return 被清掉的条目数
     */
    public static synchronized int clearAllEditsKeepTypes() {
        final int n = LEDGER.size();
        LEDGER.clear();
        // 🆕 2026-10-05：一键恢复 ⇒ duration 那张表也回开机快照（否则"恢复原样"之后面板仍显示改过的值）
        ShanhaiRecipeDuration.resetAllOriginals();
        // 🆕 第 5 轮（#6）：条件那张表同理
        resetAllCurrentConditions();
        return n;
    }

    /** 恢复用：在台账清空之后，让那些"被我们动过"的 id 仍然会被还原成底本原对象。 */
    public static synchronized java.util.Set<ResourceLocation> everTouchedSnapshot() {
        return new java.util.LinkedHashSet<>(EVER_TOUCHED);
    }

    /** 清掉"被我们删过"的记录（恢复之后原版表已经放回去了，这条记录会误导后续还原）。 */
    public static synchronized void clearEverRemoved() {
        EVER_REMOVED.clear();
    }

    /** 这条 id 是否被我们动过（撤销台账之后要不要把原版表还原成底本，看它）。 */
    public static synchronized boolean everTouched(ResourceLocation id) {
        return id != null && EVER_TOUCHED.contains(id);
    }

    public static synchronized void markRemoved(ResourceLocation id) {
        if (id != null) {
            final Edit cur = LEDGER.get(id);
            LEDGER.put(id, (cur == null ? Edit.EMPTY : cur).asRemoved());
            EVER_REMOVED.add(id);
        }
    }

    /**
     * 「这次动过哪些配方类型」—— 一键恢复时<b>只重建这几类</b>，不整包重建。
     *
     * <p>为什么必须有：恢复的语义是"把台账清掉、让表回到原样"，而重建是<b>按类型</b>做的
     * （{@code GTRecipeLookup} 是一类一棵树）。台账清空之后就没有"这条属于哪一类"的信息了
     * ⇒ 必须在清空<b>之前</b>把类型集合记下来，否则恢复只能退化成"重建全部 600 多个类型"。
     */
    private static final java.util.Set<GTRecipeType> TOUCHED_TYPES = new java.util.LinkedHashSet<>();

    public static synchronized void markTypeTouched(GTRecipeType type) {
        if (type != null) {
            TOUCHED_TYPES.add(type);
        }
    }

    public static synchronized java.util.Set<GTRecipeType> touchedTypes() {
        return new java.util.LinkedHashSet<>(TOUCHED_TYPES);
    }

    public static synchronized int touchedTypeCount() {
        return TOUCHED_TYPES.size();
    }

    /** 这条 id 是否被<b>我们</b>删过（放回去时要靠它判断该不该重新插回原版表）。 */
    public static synchronized boolean everRemoved(ResourceLocation id) {
        return id != null && EVER_REMOVED.contains(id);
    }

    /** 是否仍处于"被删"状态。 */
    public static synchronized boolean isRemoved(ResourceLocation id) {
        final Edit e = id == null ? null : LEDGER.get(id);
        return e != null && e.removed();
    }

    /** 底本里全部 id 的快照（放回去那一拍按它遍历）。 */
    public static synchronized java.util.Set<ResourceLocation> allIds() {
        return new java.util.LinkedHashSet<>(BASE.keySet());
    }

    /** 撤销某条 id 的编辑（还原拍用它）。 */
    public static synchronized void clearEdit(ResourceLocation id) {
        if (id != null) {
            LEDGER.remove(id);
            // 🆕 2026-10-05：「恢复原样」= 这条配方回到源声明那一份 ⇒ duration 那张表也必须跟回去
            //   （用户点单：「若该配方被"恢复原样"，表里也要跟着回到原始值」）。
            ShanhaiRecipeDuration.resetOriginal(id);
            // 🆕 第 5 轮（#6）：条件那张表同理（"恢复原样"之后面板必须显示开机那一份）
            resetCurrentConditions(id);
        }
    }

    public static synchronized void clearAllEdits() {
        LEDGER.clear();
        ShanhaiRecipeDuration.resetAllOriginals();
        // 🆕 第 5 轮（#6）
        resetAllCurrentConditions();
    }

    public static synchronized Map<ResourceLocation, Edit> ledgerSnapshot() {
        return new LinkedHashMap<>(LEDGER);
    }
}
