package com.shanhai.common.recipe.editor;

import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;
import com.gregtechceu.gtceu.core.mixins.RecipeManagerInvoker;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「改完立刻生效」的落地 —— <b>三层都要动，缺一层就是假的</b>。
 *
 * <h2>1. 三层</h2>
 * <ol>
 *   <li><b>GT 输入索引</b>（{@code GTRecipeType.getLookup()} 那棵 {@code Branch} 树）—— GT 机器按输入查配方走它。
 *       修法 = <b>从底本集合</b>（{@link ShanhaiRecipeBase}）收全量 → {@code removeAllRecipes()} →
 *       逐条 {@code addRecipe()}。<b>只重建【那一个类型】</b>。</li>
 *   <li><b>原版 {@code RecipeManager} 的两张表</b>（{@code f_44007_} / {@code f_199900_}）—— 走
 *       {@code RecipeManager.replaceRecipes(Iterable)}；前一轮实测它<b>同时重写这两张</b>、<b>碰不到 GT 索引</b>
 *       （{@code temp/recipe-edit-probe/p6-raw-log-lines.txt} 第 97-102 行：
 *       {@code updates_vanilla_bytype=true updates_manager_recipes=true updates_gtindex=false}）。
 *       🔴 为什么非改不可：{@code RecipeManager.apply(...)} 的 TAIL（gtceu 的 {@code RecipeManagerMixin}）
 *       会用这张表<b>重建 GT 索引</b> ⇒ 只改索引不改表，一次重载就把编辑洗掉。</li>
 *   <li><b>覆盖文件</b> {@code config/shanhai/recipe_overrides.json} —— 让"重启不丢"成立。</li>
 * </ol>
 *
 * <h2>2. 🔴 重建【只从底本出发】，不从当前索引读回来改</h2>
 * 见 {@link ShanhaiRecipeBase} 类注释：从当前索引收集会累加、会出重复 ID。
 * 这里的纪律是：<b>台账（Ledger）说这条应该是什么样，就从底本造一个副本出来</b>，
 * 连改 N 次结果只取决于最后一次台账值。
 *
 * <h2>3. 🔴 重建循环里 null 必须显式跳过并记数（不许静默）</h2>
 * 上游的删除语义是"入口返回 {@code null}"，而 {@code GTRecipeLookup.addRecipe(null)} 直接
 * {@code return false} ⇒ <b>一条 null 混进循环就是"静默少一条"</b>，且"少一条"与"本来就没有"
 * 在日志上一模一样。本类的循环对 null 打 ERROR 并计数；{@code addRecipe} 返回 false 也计数。
 *
 * <h2>4. ⚠️ JEI 是【另一个独立缓存】—— 本刀<b>没做</b>（如实标注，不假装）</h2>
 * 上游踩过的坑（注释原文）：「避免同步包把全局配方再次 {@code addRecipes} 导致<b>成倍显示</b>」，
 * 它的做法是 {@code hideRecipes(旧)} → 清自己的 JEI 缓存 → {@code addRecipes(新)}。
 * 🔴 <b>只 add 不 hide ⇒ JEI 里会成倍显示。</b>
 * 本刀<b>不碰 JEI</b>（代价与风险都超出"最小可用闭环"，且这一层要客户端才能验，红线禁止开客户端）
 * ⇒ 后果如实交代：<b>服务端配方与 GT 机器查询立刻生效，但 JEI 里那一页在本次会话里可能仍是旧的
 * （或出现重复条目），要重启客户端才干净</b>。列为"下一版候选"，不当成已覆盖。
 *
 * <h2>5. 本刀<b>不做</b> EUt，且说清为什么</h2>
 * {@code EUt} 不是 {@code GTRecipe} 上的普通字段，它是 {@code EURecipeCapability.CAP} 的
 * {@code Content.content}；{@code GTRecipe.copy()} 之后那份 content <b>是不是共享引用没取证</b>
 * ⇒ 有"改副本污染原件"的真实风险。面板上也不给这个输入框。这是"不做"，不是"忘了做"。
 *
 * <h2>6. 一次 {@code /reload} 之后会怎样（船长点名要答的）</h2>
 * <b>结论（推断，未实测；依据是 gtceu 的 {@code RecipeManagerMixin} TAIL 与 KubeJS 的事件时机）：</b>
 * <ol>
 *   <li>{@code /reload} 会重新加载配方 ⇒ 原版两张表被换成新对象 ⇒ GT 索引被 TAIL 从新表重建
 *       ⇒ <b>本局内"只改了运行期"的那部分改动被洗掉</b>；</li>
 *   <li>但覆盖层脚本订阅的是 KubeJS 的 {@code ServerEvents.recipes}，<b>配方重载会再触发一次</b>
 *       ⇒ 它会重新读 {@code recipe_overrides.json} 并再套一遍 ⇒ <b>已经落盘的编辑会自己回来</b>；</li>
 *   <li>所以真正的行为是：<b>落过盘的编辑经得起 {@code /reload}；没落盘的（{@code persist=off} 或指纹取不到）
 *       会被洗掉</b>。</li>
 * </ol>
 * 这条是<b>推断</b>，红线禁止开客户端、且本刀的冒烟只有一局，所以没有实测。
 * 上游那条更优雅的替代方案（在 {@code GTRecipeLookup.addRecipe} 上挂
 * {@code @ModifyVariable(HEAD, argsOnly)}，配方进索引那一刻就烤好，重载后自动恢复、不依赖脚本重放）
 * 已列入"下一版候选"，<b>本刀不做</b>（要 mixin ＋ 抑制位 ＋ 身份章，重得多）。
 */
public final class ShanhaiRecipeEditorOps {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 一个动作的结果（面板状态行与自检共用）。 */
    public record Result(boolean ok, String message, String detail,
                         int indexMs, int vanillaMs, boolean indexRebuilt,
                         boolean persisted, String baseFp) {}

    private ShanhaiRecipeEditorOps() {}

    // ================================================================= 对外动作

    /**
     * 把一条配方的 {@code duration} 改成 {@code newValue}，并按需刷索引 / 落盘。
     *
     * @param rebuildIndex 负对照用：{@code false} = 只写台账与原版表、<b>不刷 GT 索引</b>
     *                     （应当<b>看不到</b>新值 ⇒ 证明"刷索引"这一步是必须的）
     */
    public static Result setDuration(MinecraftServer server, GTRecipe target, int newValue,
                                     boolean rebuildIndex, boolean persist) {
        if (target == null || target.id == null) {
            return new Result(false, "目标配方不存在", "target/id == null", 0, 0, false, false, null);
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final ResourceLocation id = target.id;
        final GTRecipeType type = target.getType();
        final int before = ShanhaiRecipeBase.effectiveDuration(id);

        // 🔴 指纹必须在【改动之前】取：它描述的是"改的时候这条配方长什么样"，
        //    而覆盖层下一局读到的是【源声明】那一份 —— 见 ShanhaiRecipeFingerprint 的三条事实，
        //    以及 resolveBaseFp 里那条"改第二次会写错指纹"的修正。
        //    ⚠️ 必须在【玩家自己的实例】里取（同一条配方在冒烟装置与用户实例里时长不同，
        //    照抄别处取到的指纹 ⇒ 覆盖层下一局必然 STALE）。
        final String fp = resolveBaseFp(server, id, type, before);

        ShanhaiRecipeBase.setDuration(id, newValue);
        ShanhaiRecipeBase.markTypeTouched(type);

        final long t0 = System.nanoTime();
        if (rebuildIndex) {
            rebuildTypeFromBase(server, type);
        }
        final int indexMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        final long t1 = System.nanoTime();
        syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t1) / 1_000_000L);

        ShanhaiRecipeReverseIndex.invalidate();

        final PersistOutcome po = persist ? persistSet(server, id, type, newValue, fp) : PersistOutcome.OFF;

        ShanhaiMod.LOGGER.info("{} edit_duration id={} from={} to={} rebuilt={} index_ms={} vanilla_ms={} "
                        + "persist={} base_fp_len={} fp={}",
                PREFIX, id, before, newValue, rebuildIndex, indexMs, vanillaMs, po.note(),
                fp.length(), ShanhaiRecipeFingerprint.truncate(fp));

        return new Result(true, "时长 " + before + " → " + newValue + (rebuildIndex ? "（已刷新索引）" : "（未刷索引·对照）"),
                "index_ms=" + indexMs + " vanilla_ms=" + vanillaMs + " " + po.note(),
                indexMs, vanillaMs, rebuildIndex, po.written(), fp);
    }

    /** 删掉一条配方（台账 + 索引 + 原版两表 + 落盘）。 */
    public static Result removeRecipe(MinecraftServer server, GTRecipe target, boolean persist) {
        if (target == null || target.id == null) {
            return new Result(false, "目标配方不存在", "target/id == null", 0, 0, false, false, null);
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final ResourceLocation id = target.id;
        final GTRecipeType type = target.getType();
        final String fp = resolveBaseFp(server, id, type, target.duration);

        ShanhaiRecipeBase.markRemoved(id);
        ShanhaiRecipeBase.markTypeTouched(type);

        final long t0 = System.nanoTime();
        rebuildTypeFromBase(server, type);
        final int indexMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        final long t1 = System.nanoTime();
        syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t1) / 1_000_000L);

        ShanhaiRecipeReverseIndex.invalidate();
        final PersistOutcome po = persist ? persistRemove(server, id, type, fp) : PersistOutcome.OFF;

        ShanhaiMod.LOGGER.info("{} edit_remove id={} index_ms={} vanilla_ms={} persist={}",
                PREFIX, id, indexMs, vanillaMs, po.note());
        return new Result(true, "已删除 " + id, "index_ms=" + indexMs + " vanilla_ms=" + vanillaMs + " " + po.note(),
                indexMs, vanillaMs, true, po.written(), fp);
    }

    /** 撤销某条 id 的全部编辑（还原拍用它）：台账清掉 ⇒ 索引与原版表都回到<b>底本原对象</b>。 */
    public static void restore(MinecraftServer server, GTRecipe target) {
        if (target == null || target.id == null) {
            return;
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        ShanhaiRecipeBase.clearEdit(target.id);
        rebuildTypeFromBase(server, target.getType());
        syncVanillaFromBase(server);
        ShanhaiRecipeReverseIndex.invalidate();
    }

    // ================================================================= 第二刀：IO / 电压

    /**
     * 改一条配方的<b>输入 / 输出</b>（整段替换语义：传 null 的表不动，传 {@code {}} 的表清空）。
     *
     * <p>三层同拍：GT 索引（只重建这一个类型）＋ 原版两张表 ＋ 覆盖文件。
     */
    public static Result setIo(MinecraftServer server, GTRecipe target,
                               JsonObject inputs, JsonObject outputs, JsonObject tickInputs,
                               boolean rebuildIndex, boolean persist) {
        if (target == null || target.id == null) {
            return new Result(false, "目标配方不存在", "target/id == null", 0, 0, false, false, null);
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final ResourceLocation id = target.id;
        final GTRecipeType type = target.getType();
        final String fp = resolveBaseFp(server, id, type, target.duration);

        ShanhaiRecipeBase.setIo(id, inputs, outputs, tickInputs);
        ShanhaiRecipeBase.markTypeTouched(type);

        final long t0 = System.nanoTime();
        if (rebuildIndex) {
            rebuildTypeFromBase(server, type);
        }
        final int indexMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        final long t1 = System.nanoTime();
        syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t1) / 1_000_000L);

        ShanhaiRecipeReverseIndex.invalidate();

        final PersistOutcome po = persist
                ? persistSetFields(server, id, type, fieldsForIo(target, inputs, outputs, tickInputs), fp)
                : PersistOutcome.OFF;

        ShanhaiMod.LOGGER.info("{} edit_io id={} type={} in={} out={} tickIn={} rebuilt={} index_ms={} "
                        + "vanilla_ms={} persist={}",
                PREFIX, id, typeId(type), countOf(inputs), countOf(outputs), countOf(tickInputs),
                rebuildIndex, indexMs, vanillaMs, po.note());
        return new Result(true, "输入输出已改（in=" + countOf(inputs) + " out=" + countOf(outputs) + "）",
                "index_ms=" + indexMs + " vanilla_ms=" + vanillaMs + " " + po.note(),
                indexMs, vanillaMs, rebuildIndex, po.written(), fp);
    }

    /**
     * 改一条配方的<b>电压</b>（EU/t）。
     *
     * <p>同拍写两处：{@code tickInputs/tickOutputs} 里的 EU ＋ {@code data.euTier}
     * （运行期实测：改 tickInputs <b>不会</b>自动更新 euTier，见 {@link ShanhaiRecipeIoApply}）。
     */
    public static Result setEut(MinecraftServer server, GTRecipe target, long eut,
                                boolean rebuildIndex, boolean persist) {
        if (target == null || target.id == null) {
            return new Result(false, "目标配方不存在", "target/id == null", 0, 0, false, false, null);
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final ResourceLocation id = target.id;
        final GTRecipeType type = target.getType();
        final long before = ShanhaiRecipeBase.effectiveEut(id);
        final String fp = resolveBaseFp(server, id, type, target.duration);

        ShanhaiRecipeBase.setEut(id, eut);
        ShanhaiRecipeBase.markTypeTouched(type);

        final long t0 = System.nanoTime();
        if (rebuildIndex) {
            rebuildTypeFromBase(server, type);
        }
        final int indexMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        final long t1 = System.nanoTime();
        syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t1) / 1_000_000L);

        ShanhaiRecipeReverseIndex.invalidate();

        final JsonObject tick = ShanhaiRecipeIoApply.tableJson(target, "tickInputs");
        if (tick.size() == 0) {
            final JsonObject gen = ShanhaiRecipeIoApply.tableJson(target, "tickOutputs");
            if (gen.size() > 0) {
                tick.add("eu", gen.get("eu"));
            }
        }
        // 🔴 同拍把 eu 也写进 fields.tickInputs —— 覆盖层下一局重放时才能一起回来。
        tick.addProperty("eu", eut);
        final JsonObject data = new JsonObject();
        data.addProperty("euTier", com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(Math.abs(eut)));
        final JsonObject fields = new JsonObject();
        fields.add("data", data);
        final PersistOutcome po = persist ? persistSetFields(server, id, type, fields, fp) : PersistOutcome.OFF;

        ShanhaiMod.LOGGER.info("{} edit_eut id={} from={} to={} euTier={} rebuilt={} index_ms={} vanilla_ms={} persist={}",
                PREFIX, id, before, eut, com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(Math.abs(eut)),
                rebuildIndex, indexMs, vanillaMs, po.note());
        return new Result(true, "电压 " + before + " → " + eut + " EU/t",
                "index_ms=" + indexMs + " vanilla_ms=" + vanillaMs + " " + po.note(),
                indexMs, vanillaMs, rebuildIndex, po.written(), fp);
    }

    private static JsonObject fieldsForIo(GTRecipe live, JsonObject inputs, JsonObject outputs,
                                          JsonObject tickInputs) {
        final JsonObject f = new JsonObject();
        if (inputs != null) {
            f.add("inputs", inputs);
        }
        if (outputs != null) {
            f.add("outputs", outputs);
        }
        if (tickInputs != null) {
            f.add("tickInputs", tickInputs);
        }
        return f;
    }

    private static int countOf(JsonObject table) {
        if (table == null) {
            return -1;
        }
        int n = 0;
        for (String k : new String[]{"item", "fluid", "eu"}) {
            if (table.has(k) && table.get(k).isJsonArray()) {
                n += table.getAsJsonArray(k).size();
            }
        }
        return n;
    }

    // ================================================================= 工作区面板：一次保存改三样

    /**
     * 面板「保存」用的<b>合并版</b>：一次调用里改完 <b>输入/输出 ＋ 电压 ＋ 耗时</b>。
     *
     * <h2>🔴 为什么不直接连着调 setIo / setEut / setDuration（这是本方法存在的唯一理由）</h2>
     * 三条各自都是"改台账 → 重建索引 → 同步原版两表 → 落盘"的<b>整条链</b>，连着调会有两个硬伤：
     * <ol>
     *   <li><b>慢三倍</b>：实测一次 IO 改动 {@code index_ms=31..94 + vanilla_ms=125..160}，
     *       三次就是三倍（用户明确要求"要快，不能跟 /reload 一样"）；</li>
     *   <li>🔴 <b>后写的那次会把先写的字段从覆盖文件里抹掉</b>：
     *       {@code ShanhaiRecipeOverrideStore.upsert} 是<b>按 id 整条替换</b>
     *       （{@code dropped_same_id=1}），而 {@code makeSetEntry(...fields...)} 用的是
     *       <b>调用方给的那一份 fields</b>。所以"先 setIo 再 setDuration"的落盘结果是
     *       {@code fields={"duration":…}} —— IO 改动在<b>下一局开机会消失</b>，
     *       而这一次会话里机器和 JEI 都是对的（＝最难发现的那种丢数据）。</li>
     * </ol>
     * ⇒ 三样在同一条链里做完，<b>只落一条 entry、只带一份合并后的 fields</b>。
     *
     * @param inputs/outputs/tickInputs 传 {@code null} = 这次不动那张表（整段替换语义见 {@link ShanhaiRecipeIoApply})
     * @param duration                  传 {@code null} = 不动耗时
     * @param eut                       传 {@code null} = 不动电压
     * @param conditions                🆕 B 组：GT 平铺形状的条件数组；{@code null} = 不动条件，
     *                                  {@code []} = 把条件清空（口径与 IO 一致）
     */
    public static Result applyEdits(MinecraftServer server, GTRecipe target,
                                    JsonObject inputs, JsonObject outputs, JsonObject tickInputs,
                                    Integer duration, Long eut,
                                    com.google.gson.JsonArray conditions,
                                    boolean rebuildIndex, boolean persist) {
        if (target == null || target.id == null) {
            return new Result(false, "目标配方不存在", "target/id == null", 0, 0, false, false, null);
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final ResourceLocation id = target.id;
        final GTRecipeType type = target.getType();

        final int durBefore = ShanhaiRecipeBase.effectiveDuration(id);
        final long eutBefore = ShanhaiRecipeBase.effectiveEut(id);
        // 🔴 条件那一份也必须【改动之前】取（口径与指纹一致）：先把"改之前有几条"读出来打日志。
        final com.google.gson.JsonArray condBefore = ShanhaiRecipeConditions.encodeOf(target);
        // 指纹必须【改动之前】取（口径与 setDuration/setIo/setEut 完全一致）。
        final String fp = resolveBaseFp(server, id, type, durBefore);

        if (inputs != null || outputs != null || tickInputs != null) {
            ShanhaiRecipeBase.setIo(id, inputs, outputs, tickInputs);
        }
        if (eut != null) {
            ShanhaiRecipeBase.setEut(id, eut);
        }
        if (duration != null) {
            ShanhaiRecipeBase.setDuration(id, duration);
        }
        if (conditions != null) {
            // 🔴 写台账之前先解一次：解不出来【整条拒收】，绝不落一条机器读不懂的条件
            //    （那会让下一局的配方 json 解析失败 —— 不是"条件没生效"，是整条配方崩）。
            final ShanhaiRecipeConditions.Decoded d = ShanhaiRecipeConditions.decode(conditions);
            if (!d.ok()) {
                ShanhaiMod.LOGGER.error("{} edit_apply_conditions_rejected id={} err={}", PREFIX, id, d.error());
                return new Result(false, "额外条件解不出来，整条没有保存", d.error(), 0, 0, false, false, fp);
            }
            ShanhaiRecipeBase.setConditions(id, conditions);
        }
        ShanhaiRecipeBase.markTypeTouched(type);

        final long t0 = System.nanoTime();
        if (rebuildIndex) {
            rebuildTypeFromBase(server, type);
        }
        final int indexMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        final long t1 = System.nanoTime();
        syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t1) / 1_000_000L);

        ShanhaiRecipeReverseIndex.invalidate();

        // ---- 合并后的 fields（三个来源合一份，顺序无所谓，键不重叠） ----
        final JsonObject fields = fieldsForIo(target, inputs, outputs, tickInputs);
        if (duration != null) {
            fields.addProperty("duration", duration);
        }
        if (eut != null) {
            final JsonObject data = new JsonObject();
            data.addProperty("euTier", com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(Math.abs(eut)));
            fields.add("data", data);
        }
        if (conditions != null) {
            fields.add(ShanhaiRecipeConditions.FIELD, conditions);
        }
        final PersistOutcome po = persist
                ? persistSetFields(server, id, type, fields, fp)
                : PersistOutcome.OFF;

        ShanhaiMod.LOGGER.info("{} edit_apply id={} type={} dur={}->{} eut={}->{} in={} out={} tickIn={} "
                        + "conds={}->{} rebuilt={} index_ms={} vanilla_ms={} persist={} fields={} base_fp_len={}",
                PREFIX, id, typeId(type), durBefore, duration == null ? "(untouched)" : duration,
                eutBefore, eut == null ? "(untouched)" : eut,
                countOf(inputs), countOf(outputs), countOf(tickInputs),
                ShanhaiRecipeConditions.summary(condBefore),
                conditions == null ? "(untouched)" : ShanhaiRecipeConditions.summary(conditions),
                rebuildIndex, indexMs, vanillaMs, po.note(), fields.keySet(), fp.length());

        return new Result(true,
                "已保存（耗时 " + (duration == null ? "未动" : durBefore + "→" + duration)
                        + " · 电压 " + (eut == null ? "未动" : eutBefore + "→" + eut + " EU/t")
                        + " · 条件 " + (conditions == null ? "未动" : conditions.size() + " 条") + "）",
                "index_ms=" + indexMs + " vanilla_ms=" + vanillaMs + " " + po.note(),
                indexMs, vanillaMs, rebuildIndex, po.written(), fp);
    }

    // ================================================================= 🆕 第 7 轮：新建配方（队列 #3）

    /**
     * <b>按一份 GT 配方 JSON 造一条新配方并让它立刻生效</b>（队列 #3「新建配方」的后端）。
     *
     * <h4>四步，缺一不可</h4>
     * <pre>
     *   ① {@code GTRecipeSerializer.SERIALIZER.fromJson(id, json)}  —— 用 GT 自己的反序列化器造对象
     *      🔴 这一步同时是<b>校验</b>：JSON 形状不对 / type 不认，它直接抛，我们就不往下走
     *      （绝不落一条机器读不懂的配方 —— 那会让下一局的配方 json 解析失败，不是"没生效"是"整条崩"）
     *   ② {@link ShanhaiRecipeBase#registerNew}  —— 登记进底本（否则下次重建索引时它会静默消失）
     *   ③ {@link #rebuildTypeFromBase} ＋ {@link #insertIntoVanilla}  —— 让它进 GT 索引与原版两表
     *   ④ 落盘由调用方做（写 {@code op=add} 条目）
     * </pre>
     *
     * @return 造出来的活配方；任何一步失败都返回 {@code null}（调用方据此报错，不静默）
     */
    public static GTRecipe addRecipeFromJson(MinecraftServer server, ResourceLocation id, JsonObject json) {
        if (server == null || id == null || json == null) {
            return null;
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final GTRecipe r;
        try {
            r = com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer.SERIALIZER.fromJson(id, json);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} add_recipe_parse_failed id={} err={} json={}",
                    PREFIX, id, t.toString(), json);
            return null;
        }
        if (r == null) {
            ShanhaiMod.LOGGER.error("{} add_recipe_parse_null id={}", PREFIX, id);
            return null;
        }
        r.id = id;
        final GTRecipeType type = r.getType();
        if (type == null) {
            ShanhaiMod.LOGGER.error("{} add_recipe_no_type id={}", PREFIX, id);
            return null;
        }
        if (!ShanhaiRecipeBase.registerNew(r)) {
            // 撞 id（底本里已经有）⇒ 明确失败，绝不覆盖
            return null;
        }
        ShanhaiRecipeBase.markTypeTouched(type);
        rebuildTypeFromBase(server, type);
        insertIntoVanilla(server, r);
        ShanhaiRecipeReverseIndex.invalidate();
        ShanhaiMod.LOGGER.info("{} add_recipe_applied id={} type={} dur={} eut={} index_size={} vanilla_has={}",
                PREFIX, id, type.registryName, r.duration, ShanhaiRecipeIoApply.euOf(r),
                indexSizeOf(type), readFromVanilla(server, type, id) != null);
        return r;
    }

    /**
     * 把一条新配方单独插进原版 {@code RecipeManager}。
     *
     * <p>🔴 为什么不能只靠 {@link #syncVanillaFromBase}：那个方法对 GT 配方只做<b>改/删</b>
     * （外加"被我们删过又撤销"的补插），<b>不会</b>把一条从没存在过的配方插进去。
     * 本方法是<b>纯新增</b>的一小段，不碰既有的任何分支（红线：上一轮那批读数不许变）。
     */
    private static void insertIntoVanilla(MinecraftServer server, GTRecipe recipe) {
        if (recipe == null || recipe.id == null) {
            return;
        }
        try {
            final net.minecraft.world.item.crafting.RecipeManager rm = server.getRecipeManager();
            final List<net.minecraft.world.item.crafting.Recipe<?>> all =
                    new ArrayList<>(rm.getRecipes());
            for (net.minecraft.world.item.crafting.Recipe<?> r : all) {
                if (r instanceof GTRecipe gt && recipe.id.equals(gt.id)) {
                    ShanhaiMod.LOGGER.info("{} add_recipe_vanilla_already_present id={}", PREFIX, recipe.id);
                    return;
                }
            }
            all.add(recipe);
            rm.replaceRecipes(all);
            ShanhaiMod.LOGGER.info("{} add_recipe_vanilla_inserted id={} total={}",
                    PREFIX, recipe.id, all.size());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} add_recipe_vanilla_insert_failed id={} err={}",
                    PREFIX, recipe.id, t.toString());
        }
    }

    // ================================================================= 一键恢复
    /**
     * <b>全部恢复</b>：清空覆盖文件里的 entries ＋ 清空台账 ＋ 把受影响的那几个类型重建回底本。
     *
     * <p>「受影响的那几个类型」来自 {@link ShanhaiRecipeBase#touchedTypes()}，
     * <b>不是</b>整包重建 —— 台账一清空就再也问不出"哪条属于哪一类"了，所以类型集合
     * 必须在清台账之前就记下来。
     */
    public static Result restoreAll(MinecraftServer server) {
        ShanhaiRecipeBase.captureIfAbsent(server);

        final var types = ShanhaiRecipeBase.touchedTypes();
        // 🔴🔴 2026-10-05（用户现场读数：「RESTORE_ALL … 整行没有 net_sent」）：
        //    **清台账之前先把"动过哪些 id ＋ 各自属于哪个类型"记下来**，恢复完再逐条发单条通知。
        //    为什么要先记：清完之后那条配方可能已经不在表里了（新建的那种），
        //    那时就没有对象可以查，只能靠这份快照。
        final java.util.Map<ResourceLocation, ResourceLocation> touched = new java.util.LinkedHashMap<>();
        for (var en : ShanhaiRecipeBase.ledgerSnapshot().entrySet()) {
            final GTRecipeType t = typeOfId(server, en.getKey());
            touched.put(en.getKey(), t == null || t.registryName == null ? null : t.registryName);
        }
        // 非 GT 那边动过的 id（覆盖文件里的条目）
        final java.util.List<ResourceLocation> vanillaTouched = new java.util.ArrayList<>();
        try {
            for (String s : ShanhaiRecipeOverrideStore.appliedIds()) {
                final ResourceLocation id = ResourceLocation.tryParse(s);
                if (id != null) {
                    vanillaTouched.add(id);
                }
            }
        } catch (Throwable ignored) {
            // 读不出来就当没有（下面照样会把 changed 的条目按活表补发）
        }
        final int ledgerBefore = ShanhaiRecipeBase.ledgerSnapshot().size();

        // 🔴🔴 2026-10-06 第二轮（用户现场读数：「/shanhai edit restore 之后还是可以搜得到，
        //    甚至还可以进行合成」；日志：cleared=1 entries_left=0／dropped=0／
        //    vanilla_created_dropped=0／net_sent changed=1 removed=0／客户端 jei_vanilla_synth_from_bytes）：
        //    **根因 = 下面第 ① 块与第 ⑤ 块的动作顺序自相矛盾** ——
        //      · 第 ① 块对 op=add 的条目调 ShanhaiVanillaRecipeTable.forgetNew(id)，
        //        而它的实现就是 `NEW.remove(id); BASE.remove(id);`（ShanhaiVanillaRecipeTable L408-414）；
        //      · 第 ⑤ 块却拿 newIdsSnapshot()（= NEW.keySet() 的快照）当"本局新建过哪些"的名单
        //        ⇒ 走到第 ⑤ 块时 NEW 已被第 ① 块掏空 ⇒ **那个循环一次都不进**
        //        ⇒ vanillaCreatedDropped=0、**一条 markRemoved 都没有** ⇒ 台账里没有"这条被删了"
        //        ⇒ applyLedger 的 `e.removed()` 分支不走（现场读数 replaced/reverted/dropped/reinserted/failed
        //          全 0 —— 走的是"动过、但底本里已经没有它"那条静默分支）⇒ 活表里那条配方原封不动
        //        ⇒ noticeRestored 里 readFromTable(...) != null ⇒ 发的是 changed
        //        ⇒ 客户端拿字节又把它合成出来（现场：jei_vanilla_synth_from_bytes id=…new_recipe_1）。
        //    修法 = **把名单在第 ① 块之前先取好**（一行快照，不发明任何新机制；与
        //    ShanhaiVanillaRecipeOps.restoreAll 同源 —— 那边也是"先取名单、再动集合"）。
        final java.util.Set<ResourceLocation> vanillaCreatedIds =
                ShanhaiVanillaRecipeTable.newIdsSnapshot();

        // 🔴 新建过的那几条 ⇒ 连底本一起抹掉（否则"清掉覆盖条目"之后它们会被底本重建出来，
        //    变成"这次会话还在、重启就没了、IO 还是空壳"的幽灵 —— 用户那张截图就是这个）。
        int forgot = 0;
        // 读数：第 ① 块命中了几条 op=add（防复发判据要用它，见第 ⑤ 块之后那段自检）
        int vanillaAddEntries = 0;
        try {
            for (String s : ShanhaiRecipeOverrideStore.appliedIds()) {
                final ResourceLocation id = ResourceLocation.tryParse(s);
                if (id == null) {
                    continue;
                }
                final com.google.gson.JsonObject e = ShanhaiRecipeOverrideStore.findEntry(id);
                final String op = e != null && e.has("op") ? e.get("op").getAsString() : "";
                if ("add".equals(op)) {
                    vanillaAddEntries++;
                    if (ShanhaiRecipeBase.forgetNew(id)) {
                        forgot++;
                    }
                    ShanhaiVanillaRecipeTable.forgetNew(id);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} restore_forget_new_failed err={}", PREFIX, t.toString());
        }

        // 第 3 层：先落盘（"恢复"必须先把账本擦掉，否则重启后又回来了）
        final int fileDropped = ShanhaiRecipeOverrideStore.clearAllEntries();
        final int ledgerDropped = ShanhaiRecipeBase.clearAllEditsKeepTypes();
        ShanhaiRecipeBase.clearEverRemoved();

        // 🔴🔴 2026-10-06（用户原话，逐字）：
        //   「A1-8通过，但是我发现通过/shanhai edit restore删除配方的话被删除的工作台配方还会出现」
        //
        //   根因就在这一段【只清了 GT 那一侧的账】：
        //     · GT   ：clearAllEditsKeepTypes() ＋ clearEverRemoved() ＋（op=add 的）forgetNew
        //              —— 而且 GT 的 applyLedger 还多一条"底本里没有它 ⇒ 从表里拿掉"（stale 分支）
        //     · 非 GT：**只** forgetNew（从底本里忘掉），台账（ShanhaiVanillaRecipeTable.LEDGER）
        //              【一条都没清】，也【没有 markRemoved】
        //   ⇒ 走到 ShanhaiVanillaRecipeOps.applyLedger 时，这条工作台配方同时满足
        //     「台账里有一条非删除的编辑」＋「底本里没有它（刚被 forgetNew 摘掉）」
        //     ⇒ 撞进那个 `base == null` 分支 ⇒ ERROR `vanilla_sync_missing_base … left as is`
        //     ⇒ **该删的没删，留在原版活表里** ⇒ JEI 与合成台都还看得到它。
        //     现场读数（用户 2026-10-06 的日志）：
        //       editor restore_all_ok … cleared=5 entries_left=0        ← 文件确实清了
        //       editor vanilla_sync_missing_base id=…new_recipe_2 -> left as is
        //       editor vanilla_tables_apply replaced=0 reverted=0 dropped=0 reinserted=0 failed=1
        //       editor RESTORE_ALL … net_changed=1 net_removed=0         ← 发的是 changed，不是 removed
        //
        //   修法＝把【删除那条工作时就已经在用的同一套招牌动作】补到这里（照抄
        //   ShanhaiVanillaRecipeOps.removeOne 与 ShanhaiVanillaRecipeOps.restoreAll，
        //   一行新逻辑都没有）：
        //     ① 非 GT 台账一起清（"恢复全部"的语义就是撤销所有编辑 —— 少了这一句，
        //        被改过的非 GT 配方在 restore 之后【还留着改过的值】，同族的第二个洞）；
        //     ② 本编辑器新建的那几条 ⇒ markRemoved（台账标"删除"）＋ forgetNew（从底本摘掉）。
        //        顺序必须在 clearAllEditsKeepTypes 之后（markRemoved 写的台账不许再被清掉）；
        //        也必须在 syncVanillaFromBase 之前（applyLedger 是读台账干活的那一拍）。
        //   ⇒ 于是 applyLedger 走的是**本来就有的** `e.removed()` 分支：all.remove(i) ⇒ dropped++ ✓
        ShanhaiVanillaRecipeTable.clearAllEditsKeepTypes();
        int vanillaCreatedDropped = 0;
        // 🔴 名单用的是**第 ① 块之前**取好的那份快照（vanillaCreatedIds），不是现读的 newIdsSnapshot()
        //    —— 现读的那一份到这一拍已经被第 ① 块的 forgetNew 掏空了（现场读数见上面那段注释）。
        for (ResourceLocation vid : vanillaCreatedIds) {
            ShanhaiVanillaRecipeTable.markRemoved(vid);
            ShanhaiVanillaRecipeTable.forgetNew(vid);
            vanillaCreatedDropped++;
        }
        // 🔴🔴 机器判据（防复发；**口径写在日志文案里**，不许只靠注释）：
        //    判据 = 【本局新建过几条】(vanillaCreatedIds) 与【真标了几条删除】(vanillaCreatedDropped) 必须相等。
        //    为什么这条判据成立：上面那个循环对名单里每一条都**无条件** markRemoved＋计数 ⇒ 不相等
        //    只可能是"名单在取快照与循环之间被谁掏空了"，也就是本 bug 的复发形态
        //    （先 forgetNew 再读 NEW.keySet()）。
        //    · created_ids>0 而 dropped==0 ⇒ 打 ERROR `vanilla_created_drop_EMPTY`（红）；
        //    · 0<dropped<created_ids        ⇒ 打 ERROR `vanilla_created_drop_MISMATCH`（红）。
        //    ⚠️ 判据**故意不用**"覆盖文件里有没有 op=add"去判红：上几局留下的 op=add 条目也会让那个
        //       读数为正，而那种情况本局 NEW 里压根没有它 ⇒ 没有"新建的那条"要删 ⇒ 拿它判会打出**假红**。
        if (vanillaCreatedDropped != vanillaCreatedIds.size()) {
            if (vanillaCreatedDropped == 0) {
                ShanhaiMod.LOGGER.error("{} vanilla_created_drop_EMPTY created_ids={} dropped=0 "
                                + "vanilla_add_entries={} -> 本局新建过的配方一条都没被标删除"
                                + "（第 ⑤ 块读到的那份名单是空的 ⇒ 活表里那几条还会被客户端 JEI 合成出来）"
                                + " · 口径：created_ids = 第 ① 块之前取好的 NEW 快照条数，"
                                + "dropped = 真调过 markRemoved 的条数，两者必须相等",
                        PREFIX, vanillaCreatedIds.size(), vanillaAddEntries);
            } else {
                ShanhaiMod.LOGGER.error("{} vanilla_created_drop_MISMATCH created_ids={} dropped={} "
                                + "vanilla_add_entries={} -> 名单里有 {} 条没走到 markRemoved"
                                + " · 口径同 vanilla_created_drop_EMPTY：created_ids 与 dropped 必须相等",
                        PREFIX, vanillaCreatedIds.size(), vanillaCreatedDropped, vanillaAddEntries,
                        vanillaCreatedIds.size() - vanillaCreatedDropped);
            }
        } else if (vanillaCreatedIds.isEmpty() && vanillaAddEntries > 0) {
            // 本条**不是红**：文件里那些 op=add 是上几局留下来的条目（本局 NEW 里没有它 ⇒
            // 没有"新建的那条"要删）。打一行 INFO 把口径摊开，免得下次有人拿它当异常。
            ShanhaiMod.LOGGER.info("{} vanilla_created_drop_none vanilla_add_entries={} "
                            + "created_ids=0 dropped=0 · 口径：文件里有 op=add 条目但它们不是本局新建的"
                            + " ⇒ 本条不是红，只有 created_ids>0 才判",
                    PREFIX, vanillaAddEntries);
        }

        final long t0 = System.nanoTime();
        int rebuilt = 0;
        for (GTRecipeType t : types) {
            rebuildTypeFromBase(server, t);
            rebuilt++;
        }
        final int indexMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        final long t1 = System.nanoTime();
        syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t1) / 1_000_000L);
        ShanhaiRecipeReverseIndex.invalidate();

        // 🔴 逐条通知（"只动那一条、不 reload、不整机重注册"）：活表里还在 ⇒ 发新值；没了 ⇒ 发删除。
        final int[] net = notifyRestored(server, touched, vanillaTouched);

        ShanhaiMod.LOGGER.info("{} RESTORE_ALL types={} rebuilt_types={} ledger_before={} ledger_cleared={} "
                        + "file_entries_cleared={} index_ms={} vanilla_ms={} "
                        + "net_sent changed={} removed={} vanilla_created_dropped={} "
                        + "vanilla_created_ids={} vanilla_add_entries={} "
                        + "（逐条单条通知：不整机重注册，与界面「删除这条」同一条路；"
                        + "口径：vanilla_created_dropped 必须等于 vanilla_created_ids —— 本局新建的那几条"
                        + "都要被标成删除；不等会在上一拍另打一行 ERROR，vanilla_add_entries = 覆盖文件里"
                        + "op=add 的条数，含上几局留下的、不必相等）",
                PREFIX, types.size(), rebuilt, ledgerBefore, ledgerDropped, fileDropped, indexMs, vanillaMs,
                net[0], net[1], vanillaCreatedDropped, vanillaCreatedIds.size(), vanillaAddEntries);
        return new Result(true, "已恢复全部（清掉 " + fileDropped + " 条覆盖记录，重建 " + rebuilt + " 个配方类型）",
                "index_ms=" + indexMs + " vanilla_ms=" + vanillaMs + " types=" + types.size()
                        + " net_changed=" + net[0] + " net_removed=" + net[1],
                indexMs, vanillaMs, true, fileDropped >= 0, null);
    }

    /**
     * 恢复之后<b>逐条</b>通知客户端（用户原话：「补上：restore 之后，对【每一条被还原/抹掉的配方】
     * 各发一次单条通知」）。
     *
     * <p>判据 = <b>活表里还读得到 ⇒ changed（带整条字节）；读不到 ⇒ removed</b>。
     * 返回值 {@code int[]{changed, removed}} 供日志与自检读数。
     */
    private static int[] notifyRestored(MinecraftServer server,
                                        java.util.Map<ResourceLocation, ResourceLocation> gtTouched,
                                        java.util.List<ResourceLocation> vanillaTouched) {
        int changed = 0;
        int removed = 0;
        for (var en : gtTouched.entrySet()) {
            final ResourceLocation id = en.getKey();
            final ResourceLocation typeId = en.getValue();
            try {
                final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, id);
                if (live != null) {
                    ShanhaiJeiBridge.broadcastRecipeChanged(server, live);
                    changed++;
                } else {
                    ShanhaiJeiBridge.broadcastRecipeRemoved(server, id, typeId);
                    removed++;
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} restore_notify_failed id={} err={}", PREFIX, id, t.toString());
            }
        }
        for (ResourceLocation id : vanillaTouched) {
            try {
                final net.minecraft.resources.ResourceLocation typeId =
                        ShanhaiVanillaRecipeTable.liveById(id) == null
                                ? typeOfVanillaId(server, id)
                                : ShanhaiVanillaRecipeTable.typeIdOf(
                                        ShanhaiVanillaRecipeTable.liveById(id));
                final boolean present = ShanhaiVanillaRecipeOps.readFromTable(server, id) != null;
                ShanhaiJeiBridge.broadcastVanillaNow(server, id, typeId);
                if (present) {
                    changed++;
                } else {
                    removed++;
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} restore_notify_vanilla_failed id={} err={}",
                        PREFIX, id, t.toString());
            }
        }
        return new int[]{changed, removed};
    }

    /** 这个 id（GT 的）属于哪个类型（恢复通知要用）。查不到返回 {@code null}。 */
    private static GTRecipeType typeOfId(MinecraftServer server, ResourceLocation id) {
        try {
            final GTRecipe r = ShanhaiRecipeReverseIndex.byId(server, id);
            return r == null ? null : r.getType();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这个 id（非 GT 的）属于哪个类型（活跃那条没了的时候用它的底本）。 */
    private static ResourceLocation typeOfVanillaId(MinecraftServer server, ResourceLocation id) {
        try {
            final net.minecraft.world.item.crafting.Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
            return base == null ? new ResourceLocation("minecraft", "crafting")
                    : ShanhaiVanillaRecipeTable.typeIdOf(base);
        } catch (Throwable t) {
            return new ResourceLocation("minecraft", "crafting");
        }
    }

    /** <b>只恢复一条</b>：把这条 id 的编辑从台账与覆盖文件里删掉，只重建它所属的那一个类型。 */
    public static Result restoreOne(MinecraftServer server, GTRecipe target, boolean rebuildIndex,
                                    boolean persist) {
        if (target == null || target.id == null) {
            return new Result(false, "目标配方不存在", "target/id == null", 0, 0, false, false, null);
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final ResourceLocation id = target.id;
        final GTRecipeType type = target.getType();

        ShanhaiRecipeBase.clearEdit(id);
        ShanhaiRecipeBase.markTypeTouched(type);

        // 🔴 "恢复原样"作用在【编辑器新建的那条】上是什么语义 ⇒ **等于删掉它**
        //    （底本里本来就没有"原样"可回；留着它就会变成重启就没、IO 空壳的幽灵）。
        //    🔴🔴 2026-10-06（与 restoreAll 那条顺序洞**同族**的单条版）：判据必须在动 NEW **之前**取 ——
        //    下面那条 op=add 分支会调 ShanhaiVanillaRecipeTable.forgetNew(id)（= 从 NEW 里摘掉），
        //    等走到下面 `isNew(id)` 时它已经是 false ⇒ 那一整块（clearEdit＋markRemoved＋forgetNew）
        //    永远进不去 ⇒ 单条恢复同样**只摘底本、不标删除**。取一份快照即修（与 restoreAll 同一手法）。
        final boolean vanillaWasNew = ShanhaiVanillaRecipeTable.isNew(id);
        boolean forgot = false;
        try {
            final com.google.gson.JsonObject e = ShanhaiRecipeOverrideStore.findEntry(id);
            final String op = e != null && e.has("op") ? e.get("op").getAsString() : "";
            if ("add".equals(op)) {
                forgot = ShanhaiRecipeBase.forgetNew(id) || forgot;
                ShanhaiVanillaRecipeTable.forgetNew(id);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} restore_one_forget_new_failed id={} err={}", PREFIX, id, t.toString());
        }

        // 🔴 2026-10-06：与 restoreAll 同一处洞的**单条版**（见那边整段注释与现场读数）。
        //    这里的 `forgetNew` 只摘底本、不标删除 ⇒ 若这条是"本编辑器新建出来的工作台配方"，
        //    applyLedger 同样会撞 `missing_base → left as is`（该删没删）。
        //    ⇒ 补齐同一条纪律：先清这一条的台账，再标删除，最后才忘掉底本。
        //    ⚠️ 判据用**内存里的 NEW**（`isNew`）而不是文件里的 op=add：文件可能已经被清过
        //    （restore 就是先清文件的），而"它是不是本编辑器新建的"只有内存知道。
        //    ⚠️ 而且必须用**上面第 ① 块之前取的那份快照**（vanillaWasNew）：op=add 分支自己就会
        //    把 id 从 NEW 里摘掉 ⇒ 现读 isNew 恒为 false ⇒ 这一段等于死代码（本 bug 的单条版）。
        //    ⚠️ 今天 `/shanhai edit restore <id>` 是按 GT 反查索引找配方的（非 GT 的 id 会读成
        //    "找不到这条配方"）⇒ 这一段对 GT 的 id 是 `vanillaWasNew == false` 的**空操作**，不改既有行为。
        if (vanillaWasNew) {
            ShanhaiVanillaRecipeTable.clearEdit(id);
            ShanhaiVanillaRecipeTable.markRemoved(id);
            ShanhaiVanillaRecipeTable.forgetNew(id);
            forgot = true;
        }

        final long t0 = System.nanoTime();
        if (rebuildIndex) {
            rebuildTypeFromBase(server, type);
        }
        final int indexMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        final long t1 = System.nanoTime();
        syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t1) / 1_000_000L);
        ShanhaiRecipeReverseIndex.invalidate();

        final int fileDropped = persist ? ShanhaiRecipeOverrideStore.removeEntryById(id) : -1;

        // 🔴🔴 2026-10-05（用户：「补上：restore 之后，对【每一条被还原/抹掉的配方】各发一次单条通知」）：
        //    "恢复原样"这颗按钮走的也是本方法 ⇒ 同一发单条通知（与界面「删除这条」同一条路，
        //    绝不整机重注册）。
        int netChanged = 0;
        int netRemoved = 0;
        try {
            final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, id);
            if (live != null) {
                ShanhaiJeiBridge.broadcastRecipeChanged(server, live);
                netChanged = 1;
            } else {
                ShanhaiJeiBridge.broadcastRecipeRemoved(server, id,
                        type == null ? null : type.registryName);
                netRemoved = 1;
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} restore_one_notify_failed id={} err={}", PREFIX, id, t.toString());
        }

        ShanhaiMod.LOGGER.info("{} RESTORE_ONE id={} type={} file_entries_dropped={} rebuilt={} "
                        + "index_ms={} vanilla_ms={} net_changed={} net_removed={} "
                        + "（单条通知：与界面「删除这条」同一条路）",
                PREFIX, id, type == null ? null : type.registryName, fileDropped, rebuildIndex,
                indexMs, vanillaMs, netChanged, netRemoved);

        return new Result(true, "已恢复 " + id,
                "index_ms=" + indexMs + " vanilla_ms=" + vanillaMs + " file_entries_dropped=" + fileDropped
                        + " net_changed=" + netChanged + " net_removed=" + netRemoved,
                indexMs, vanillaMs, rebuildIndex, fileDropped >= 0, null);
    }

    /** 🔴 负对照用：{@code rebuildIndex=false} 时索引里应当<b>读不到</b>恢复后的值。 */
    public static long readEutFromIndex(GTRecipeType type, ResourceLocation id) {
        final GTRecipe r = readFromIndex(type, id);
        return r == null ? Long.MIN_VALUE : ShanhaiRecipeIoApply.euOf(r);
    }

    // ================================================================= 第 1 层：GT 索引

    /**
     * <b>从底本 + 台账</b>重建【一个类型】的输入索引。
     *
     * <p>返回值仅供自检读数：{@code int[]{treeBefore, wanted, added, refused, nulls, dropped}}
     */
    public static int[] rebuildTypeFromBase(MinecraftServer server, GTRecipeType type) {
        if (type == null) {
            return new int[]{0, 0, 0, 0, 0, 0};
        }
        ShanhaiRecipeBase.captureIfAbsent(server);
        final GTRecipeLookup lookup = type.getLookup();
        final int treeBefore = lookup.getLookup().getRecipes(true).toList().size();

        // 🔴🔴 2026-10-06 P0（与 syncVanillaFromBase 同源、同一场事故的第二条破坏路径）：
        //    底本没抓到（空捕获已被 {@code ShanhaiRecipeBase#capture} 拒绝）时，
        //    {@link ShanhaiRecipeBase#finalRecipesOf} 会返回**空表**，而下面那句
        //    {@code lookup.removeAllRecipes()} 会把这个类型的**整棵索引树清空** ——
        //    运行期读数（用户 2026-10-06 的日志）：
        //      index_rebuild type=gtceu:zero_point_conversion tree_before=2 wanted=0 deduped=0 tree_after=0
        //    ⇒ 空底本下宁可**拒绝重建**（保住现状），也绝不静默删掉一整个类型的配方。
        //    健康局面下底本永远是抓到的（本方法第一句就是 captureIfAbsent），这条守卫不会触发。
        if (!ShanhaiRecipeBase.isCaptured()) {
            ShanhaiMod.LOGGER.error("{} index_rebuild_refused_no_base type={} tree_now={} "
                            + "（底本没抓到 ⇒ 拒绝按空底本重建，否则 removeAllRecipes 会清空这个类型的整棵树）",
                    PREFIX, type.registryName, treeBefore);
            return new int[]{treeBefore, 0, 0, 0, 0, treeBefore};
        }

        final List<GTRecipe> wanted = ShanhaiRecipeBase.finalRecipesOf(type);
        // 🔴 去重（按 id）：底本已经按 id 压过一份，这里再保一道，
        //    因为"同一条被多条 Branch 路径收集"是上游点名的病，宁可多一道断言。
        final Set<ResourceLocation> seenIds = new HashSet<>();
        final List<GTRecipe> deduped = new ArrayList<>(wanted.size());
        int dupInList = 0;
        for (GTRecipe r : wanted) {
            if (r == null) {
                continue;
            }
            if (r.id != null && !seenIds.add(r.id)) {
                dupInList++;
                continue;
            }
            deduped.add(r);
        }

        lookup.removeAllRecipes();
        int added = 0;
        int refused = 0;
        int nulls = 0;
        for (GTRecipe r : deduped) {
            if (r == null) {
                // 🔴 不许静默：null 混进来会让索引"少一条"，而少一条与本来就没有在日志上长得一样
                nulls++;
                ShanhaiMod.LOGGER.error("{} index_rebuild_null_entry type={} -> skipped explicitly",
                        PREFIX, type.registryName);
                continue;
            }
            if (lookup.addRecipe(r)) {
                added++;
            } else {
                refused++;
                ShanhaiMod.LOGGER.warn("{} index_rebuild_add_refused type={} id={}",
                        PREFIX, type.registryName, r.id);
            }
        }
        final int treeAfter = lookup.getLookup().getRecipes(true).toList().size();
        // 🔴🔴 2026-10-05（用户报「新建了 GT 配方，甚至我们的配方编辑器都没有即时刷新」）：
        //    **索引改完必须让反查索引失效**，否则：
        //      ① 第二屏那一列 id 是从反查索引的快照里捞的 ⇒ 新建的那条**根本不进列表**；
        //      ② 「获取途径 / 用处」那些查询结果也是从快照里捞的 ⇒ 新建/改过的看不到新值。
        //    这里懒失效（`invalidate` 只置标志），下一次面板读的时候重建一次
        //    （实测 build_ms ≈ 276ms，一次编辑一次，不是每次 tick）。
        ShanhaiRecipeReverseIndex.invalidate();
        // 🔴🔴 2026-10-06（用户实测："删掉的配方机器还能跑，必须重进存档才停；restore 一样"）：
        //    **索引重建完必须通知机器重新选配方** —— 否则它一直按缓存的 lastRecipe 跑 ✗
        //    （上游的 `markLastRecipeDirty` + `updateTickSubscription`；见 ShanhaiRecipeMachineNotify）
        ShanhaiRecipeMachineNotify.markDirtyFor(server, type);
        ShanhaiMod.LOGGER.info("{} index_rebuild type={} tree_before={} wanted={} deduped={} added={} "
                        + "refused={} nulls={} dup_in_list={} ledger_removed={} tree_after={} "
                        + "reverse_index=invalidated（面板下一次读会重建，否则列表/查询会停在旧快照）",
                PREFIX, type.registryName, treeBefore, wanted.size(), deduped.size(), added,
                refused, nulls, dupInList, ShanhaiRecipeBase.baseCountOf(type) - wanted.size(), treeAfter);
        return new int[]{treeBefore, wanted.size(), added, refused, nulls, treeAfter};
    }

    /** 从 GT 索引里按 id 读回来那一条（"立刻生效"的判据之一）。 */
    public static GTRecipe readFromIndex(GTRecipeType type, ResourceLocation id) {
        if (type == null || id == null) {
            return null;
        }
        for (GTRecipe r : type.getLookup().getLookup().getRecipes(true).toList()) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }

    /** 该类型索引树里的条目总数（判据用：应当等于底本里该类型的条数减去台账删掉的）。 */
    public static int indexSizeOf(GTRecipeType type) {
        return type == null ? 0 : type.getLookup().getLookup().getRecipes(true).toList().size();
    }

    // ================================================================= 第 2 层：原版两表

    /**
     * 按台账把原版 {@code RecipeManager} 的全量配方重新写一遍。
     *
     * <p>规则：非 GT 配方（原版合成台等）与<b>没有台账条目的 GT 配方一律原样保留</b>；
     * 有台账的按"从底本造副本"的规则替换/删除 ⇒ 同样不累加。
     *
     * <p>⚠️ {@code replaceRecipes} 是"整份替换"语义 ⇒ 必须传<b>全量</b>。前一轮实测全量 ≈ 65781 条、
     * 单次 ≈ 150 ms（{@code p6-raw-log-lines.txt} 第 96-98 行）。
     */
    public static void syncVanillaFromBase(MinecraftServer server) {
        // 🆕 本轮（只修"卡几秒"与"JEI 不显示"两件）：**把这一次保存的总耗时量出来**。
        //    为什么要量：用户报的是"每次删除或者新建一条配方就要卡好久"，而那个"好久"是
        //    客户端 JEI 全量重注册（8~15 秒）。摘掉整表广播之后，服务端这一侧的真实开销
        //    必须有个数字（原来是 64~150 ms 量级），报告里要写清"服务端多少毫秒、
        //    客户端那 8 秒已经不再发生"。
        final long syncT0 = System.nanoTime();
        final RecipeManager rm = server.getRecipeManager();
        // 🔴🔴 2026-10-06 P0（用户：「我修改配方之后…第一面中的配方数都会变成 0」）：
        //    **做这次全表重写之前，先保证【底本】确实抓到了。**
        //
        //    这条路会被"工作台 / 原版配方"的保存【直接】调用（{@code ShanhaiVanillaRecipeOps} 的
        //    4 处 + {@code ShanhaiRecipeEditorWorkspace#saveVanillaAndReturn}），
        //    而那些调用点在调本方法之前【没有】抓过底本 ⇒ 于是本方法对着一个【空底本】执行
        //    下面那句「底本里没有它 ⇒ 从两张表里拿掉」⇒ 一次把 54033 条 GT 配方全删掉。
        //    运行期读数（2026-10-06 用户的日志）：
        //      vanilla_tables_stale_gt_dropped count=54033
        //      table_hook_writeback all=15450 types=19      （正常应是 all=69483 types=223）
        //      reverse_index_built scanned=0 indexed_recipes=0
        //    先抓一次底本就把这条路堵死（抓的是"还没被任何编辑污染"的那份表，见 capture 的注释）。
        ShanhaiRecipeBase.captureIfAbsent(server);
        final boolean baseUsable = ShanhaiRecipeBase.isCaptured();
        final List<Recipe<?>> all = new ArrayList<>(rm.getRecipes());
        final Set<ResourceLocation> presentGtIds = new HashSet<>();
        // 底本不可用 ⇒ 被"保留"而不是被"删掉"的 GT 配方条数（非 0 一律报 ERROR，不许静默）
        int keptWithoutBase = 0;
        int replaced = 0;
        int dropped = 0;
        int nulls = 0;
        int reverted = 0;
        int stale = 0;   // 底本里已经没有、但仍留在原版两张表里的 GT 配方（恢复原样之后要清掉）
        for (int i = 0; i < all.size(); i++) {
            final Recipe<?> r = all.get(i);
            if (!(r instanceof GTRecipe gt) || gt.id == null) {
                continue;
            }
            final ShanhaiRecipeBase.Edit e = ShanhaiRecipeBase.editOf(gt.id);
            if (e == null) {
                // 🔴 没有台账 ≠ 可以原样留着：如果这条 id 被我们动过、台账又被撤销了
                //   （restore / 改回原值），这时表里那条还是上一拍的【改过副本】
                //   ⇒ 必须还原成底本原对象。2026-10-05 冒烟就是在这里抓到的真 bug
                //   （读数：case=apply_restored expect=100 index=100 vanilla=2100）。
                if (ShanhaiRecipeBase.everTouched(gt.id)) {
                    final GTRecipe base = ShanhaiRecipeBase.pristine(gt.id);
                    if (base != null && !ShanhaiRecipeBase.isRemoved(gt.id)) {
                        all.set(i, base);
                        presentGtIds.add(gt.id);
                        reverted++;
                        continue;
                    }
                }
                // 🔴🔴 2026-10-05（用户：「restore 之后卡片上还留着刚被抹掉的那条，
                //    <b>而且输入产物是空的</b> ⇒ 列表用旧快照、数据现读 ⇒ 剩一个空壳」）：
                //    **底本里已经没有它 ⇒ 原版两张表里也必须拿掉。**
                //    为什么必须在这一步：`restoreOne/restoreAll` 把"编辑器新建的那条"从底本里
                //    忘掉（{@code ShanhaiRecipeBase.forgetNew}）之后，GT 索引那一侧已经正确地
                //    不再收它；但原版 {@code RecipeManager} 这两张表里还留着 ⇒ 面板按 id 现读
                //    （活表优先）仍然读得到 ⇒ 那一格还在、数据却是空壳 ⇒ 正是用户截图里那一条。
                //    ⚠️ 判据只认"底本里没有它"（开机快照里本来就有全部 GT 配方）⇒ 不会误删别人的东西。
                if (ShanhaiRecipeBase.pristine(gt.id) == null) {
                    // 🔴🔴 2026-10-06 P0：**「底本里没有它」只有在底本确实抓到了的时候才有判别力。**
                    //    底本没抓到（空 / 被拒）时，这句话只能说明"底本没建"，
                    //    不能说明"这条配方该删"——2026-10-06 用户现场就是在这里把
                    //    54033 条 GT 配方当成"底本里没有的过时条目"一次删光的
                    //    （日志原文：vanilla_tables_stale_gt_dropped count=54033）。
                    //    ⇒ 底本不可用就【一律保留】，并报 ERROR（保留会让面板多一条，删掉会让整表归零；
                    //      宁可多一条，不可整表归零）。
                    if (!baseUsable) {
                        keptWithoutBase++;
                        presentGtIds.add(gt.id);
                        continue;
                    }
                    all.remove(i);
                    i--;
                    stale++;
                    continue;
                }
                presentGtIds.add(gt.id);
                continue;   // 从没动过 ⇒ 原样留着（引用同一性也保住）
            }
            if (e.removed()) {
                all.remove(i);
                i--;
                dropped++;
                continue;
            }
            final GTRecipe base = ShanhaiRecipeBase.pristine(gt.id);
            if (base == null) {
                nulls++;
                ShanhaiMod.LOGGER.error("{} vanilla_sync_missing_base id={} -> left as is", PREFIX, gt.id);
                presentGtIds.add(gt.id);
                continue;
            }
            final GTRecipe next = base.copy();
            // 🔴 与 ShanhaiRecipeBase.finalRecipesOf 同一行纪律：copy() 的 data 是【共享引用】，
            //    不先 copy 就会在写 euTier 时改到【底本】。运行期取证见 ShanhaiRecipeBase 里的注释。
            next.data = base.data.copy();
            ShanhaiRecipeBase.applyEditTo(next, e);
            next.id = base.id;
            all.set(i, next);
            presentGtIds.add(gt.id);
            replaced++;
        }

        // 「删掉再放回」这一拍：原版表是"改/删"语义，不会自己把条目插回来
        // ⇒ 只补【被我们删过、台帐已撤销、且表里确实没有】的那些，别的一律不碰。
        int reinserted = 0;
        for (ResourceLocation id : ShanhaiRecipeBase.allIds()) {
            if (presentGtIds.contains(id) || !ShanhaiRecipeBase.everRemoved(id) || ShanhaiRecipeBase.isRemoved(id)) {
                continue;
            }
            final GTRecipe base = ShanhaiRecipeBase.pristine(id);
            if (base != null) {
                all.add(base);
                reinserted++;
            }
        }

        // 🆕 2026-10-05（非 GT / 工作台与原版配方）：把【非 GT 的台账】套进同一份列表。
        //    🔴 为什么挂在这里而不是自己再调一次 replaceRecipes：见 ShanhaiVanillaRecipeOps 的类注释 ——
        //       一次保存只能有一次整表重写（每次 64~150 ms），分两次做就是白付一倍。
        //    ⚠️ 台账为空时 applyLedger 直接返回、一个元素都不碰 ⇒ "没人在编辑原版配方"时
        //       这一段与改动之前逐字节相同。
        final int[] vstats = ShanhaiVanillaRecipeOps.applyLedger(all);

        rm.replaceRecipes(all);
        // 🆕 第 12 刀：🔴 **把两张表钉死成我们刚写下去的那一份**。
        //    现场读数（冒烟第 3 局 19:38）同一个 id 在同一拍给出互相矛盾的答案：
        //      `byName_has=false getRecipes_has=0 合成台命中=[minecraft:stick]`
        //    —— 而 getRecipes() 按字节码就是从 byType 展平的 ⇒ 两者不可能不同
        //    ⇒ 说明"只靠 replaceRecipes"这一步没有把两张表都覆盖到（同一个现象在"新建"那侧也出现过：
        //      新建的配方 getRecipes() 找得到、getRecipesFor() 里没有）。
        //    ⇒ 这里再显式重建一次（去重后写入；GT 自己暴露了 byType 的写入点）。
        //    ⚠️ 失败只降级打 WARN，绝不影响保存这条链路。
        if (stale > 0) {
            // ⚠️ 引号必须用中文引号：Java 字符串里塞英文双引号会把这一行拆坏（编译期才发现）
            ShanhaiMod.LOGGER.info("{} vanilla_tables_stale_gt_dropped count={}（底本里已经没有的 GT 配方："
                    + "恢复原样把「编辑器新建的那条」忘掉之后，原版两张表里也得跟着拿掉，"
                    + "否则面板会留一个空壳卡片）", PREFIX, stale);
        }
        if (keptWithoutBase > 0) {
            // 🔴 2026-10-06 P0：底本没抓到 ⇒ 这些 GT 配方一律【保留】。非 0 一定是异常，
            //    ERROR 级别 + 明确计数，绝不静默（"少一条"与"本来就没有"长得一样是本工程最怕的失败）。
            ShanhaiMod.LOGGER.error("{} vanilla_sync_kept_no_base count={} —— 底本未抓到（空底本已被拒绝），"
                            + "这些 GT 配方一律保留而不是按「底本里没有它」删掉；"
                            + "成因见 base_capture_refused_empty，本次未清空原版表",
                    PREFIX, keptWithoutBase);
        }
        ShanhaiRecipeTableHook.forceWriteBack(server, all);
        ShanhaiMod.LOGGER.info("{} vanilla_tables_written total={} replaced={} reverted={} dropped={} reinserted={} missing_base={}",
                PREFIX, all.size(), replaced, reverted, dropped, reinserted, nulls);
        if (vstats[0] != 0 || vstats[1] != 0 || vstats[2] != 0 || vstats[3] != 0 || vstats[4] != 0) {
            ShanhaiMod.LOGGER.info("{} vanilla_tables_written_nonGt replaced={} reverted={} dropped={} "
                            + "reinserted={} failed={}（非 GT 那一段；全 0 时不会打这一行）",
                    PREFIX, vstats[0], vstats[1], vstats[2], vstats[3], vstats[4]);
        }
        // 🔴🔴 <b>本轮（只修"卡几秒"与"JEI 不显示"两件）：这里【不再】整表广播。</b>
        //
        // 现场读数（用户实例 logs\latest.log，逐字 + 我事后对时间轴的配对）：
        // <pre>
        //   20:36:06 recipe_resync_sent recipes=70277 players=1
        //   20:36:16 Optimized GTCEu JEI recipe registration: types=203, recipes=54828,
        //            wrapper_ms=6496, registration_ms=1123, elapsed_ms=7642      ← 卡 7.6 秒
        //   20:36:16 Starting JEI took 12.18 seconds
        // </pre>
        // 8 次保存 ⇒ 8 次这样的全量重注册（10.8~15.2 秒），逐条配对得上 ⇒ 用户那句
        // 「我现在每次删除或者新建一条配方就要卡好久」就是这一条造成的。
        //
        // 机制：`ClientboundUpdateRecipesPacket` 是【整张表】的包，客户端收到后
        // `ClientPacketListener` 会 `replaceRecipes` + 重建配方书，Forge 在尾巴上抛
        // `RecipesUpdatedEvent` ⇒ JEI 的 `StartEventObserver` 直接 `restart()`（全量重读，
        // 本整合包 5.4 万条 GT 配方全部重新注册）。
        //
        // ⇒ 改成"只动那一条"：每一次改动各自的路径上本来就发了**单条**补丁包
        //   （`ShanhaiJeiBridge.broadcastRecipeChanged/broadcastVanillaChanged/…Removed`），
        //   客户端按【分类】把这一条换掉/补进去/拿掉 ⇒ 不触发任何全量重载。
        //
        // ⚠️ 代价如实写：客户端自己的配方表（配方书、以及"本地那一次合成预览"）不再被这一发
        //    刷新；合成台的权威结果由服务端算（这边已经修过 FastSuite 的缓存），
        //    JEI 那一侧由单条补丁包负责。
        ShanhaiMod.LOGGER.info("{} resync_skipped reason=single_recipe_channel_only total={} sync_ms={} "
                        + "（本轮改成只发单条补丁：整表包会让客户端 JEI 全量重注册 5.4 万条，"
                        + "实测卡 10.8~15.2 秒 ⇒ 用户那句「每次删除或者新建一条配方就要卡好久」）",
                PREFIX, all.size(), (System.nanoTime() - syncT0) / 1_000_000L);
    }

    /** 整表广播被调用过几次（自检判据用：保存这条路必须是 0）。 */
    private static final java.util.concurrent.atomic.AtomicInteger FULL_RESYNC_SENT =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 读数：整表广播被调用的次数（"卡几秒"那条判据就落在这个 0 上）。 */
    public static int fullResyncCount() {
        return FULL_RESYNC_SENT.get();
    }

    /**
     * 🔴 <b>整表重推（重活）—— 现在【没有任何自动路径】会调它了。</b>
     *
     * <p>保留它只为两件事：① 自检里验"这条路本身是通的"；② 万一以后真需要一次全量重推
     * （例如客户端补丁表被清空），有一个现成的、走原版同一条包的入口。
     *
     * <h4>⚠️ 为什么它从"每次保存都发"改成"没人自动发"</h4>
     * 实测：一次整表广播 ⇒ 客户端 JEI `StartEventObserver.restart()` ⇒ GT 的 JEI 插件重新注册
     * 5.4 万条配方 ⇒ <b>卡 10.8~15.2 秒</b>（用户实例日志里 8 次保存 8 次配对得上）。
     * 用户原话：「我现在每次删除或者新建一条配方就要卡好久」。
     * <p>替代方案 = 每次改动各自的**单条补丁包**（{@code ShanhaiJeiBridge}），客户端按分类
     * 换掉/补进/拿掉那一条，不触发任何全量重载。
     */
    public static void syncRecipesToClients(MinecraftServer server, int total) {
        if (server == null) {
            return;
        }
        try {
            final var players = server.getPlayerList();
            if (players == null || players.getPlayers().isEmpty()) {
                ShanhaiMod.LOGGER.info("{} recipe_resync_skipped reason=no_player_online total={}",
                        PREFIX, total);
                return;
            }
            final List<Recipe<?>> all = new ArrayList<>(server.getRecipeManager().getRecipes());
            final long t0 = System.nanoTime();
            final net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket pkt =
                    new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(all);
            players.broadcastAll(pkt);
            FULL_RESYNC_SENT.incrementAndGet();
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            ShanhaiMod.LOGGER.info("{} recipe_resync_sent recipes={} players={} build_ms={} "
                            + "(客户端 RecipeManager ＋ 配方书 ＋ JEI 一起重读；"
                            + "这是「改完立刻能看见」在 JEI 那一侧的唯一可靠通道)",
                    PREFIX, all.size(), players.getPlayers().size(), ms);
        } catch (Throwable t) {
            // 同步失败绝不能让保存这条路炸掉（本局的表已经写好了，只是客户端要等下次进世界）
            ShanhaiMod.LOGGER.error("{} recipe_resync_failed err={} (本局服务端已生效；"
                    + "客户端要等下次进世界才会看到)", PREFIX, t.toString(), t);
        }
    }

    /** 从原版表里按 id 读回那一条（"立刻生效"的判据之二；走 gtceu 的 {@code RecipeManagerInvoker}）。 */
    public static GTRecipe readFromVanilla(MinecraftServer server, GTRecipeType type, ResourceLocation id) {
        if (type == null || id == null) {
            return null;
        }
        final RecipeManager rm = server.getRecipeManager();
        if (rm instanceof RecipeManagerInvoker invoker) {
            final var map = invoker.getRecipeFromType(type);
            if (map != null) {
                final Recipe<?> r = map.get(id);
                if (r instanceof GTRecipe gt) {
                    return gt;
                }
            }
        }
        for (Recipe<?> r : rm.getRecipes()) {
            if (r instanceof GTRecipe gt && id.equals(gt.id)) {
                return gt;
            }
        }
        return null;
    }

    /**
     * 🔴 <b>自检专用</b>：把私有的 {@link #fieldsAppliedTo} 暴露出来，让"B 组的第二次保存不会写错
     * {@code base_fp}"这条修复有<b>可机器判定的正/负对照</b>（否则它只能在真机上靠"存两次"发现）。
     *
     * @return true = 这份 fields 与底本当下的值一致（= 覆盖层这一局确实套上了 ⇒ 该沿用文件里的 fp）
     */
    public static boolean probeFieldsApplied(MinecraftServer server, ResourceLocation id, JsonObject fields) {
        return fieldsAppliedTo(server, id, fields);
    }

    // ================================================================= 第 3 层：落盘

    private record PersistOutcome(boolean written, String note) {
        static final PersistOutcome OFF = new PersistOutcome(false, "persist=off");
    }

    /** 多字段版（第二刀）：fields 里可以同时有 inputs / outputs / tickInputs / data / duration / conditions。 */
    private static PersistOutcome persistSetFields(MinecraftServer server, ResourceLocation id,
                                                   GTRecipeType type, JsonObject fields, String fp) {
        // 🔴🔴 2026-10-05 第 11 刀 P0（用户实测：「新建的配方第二次保存后就会永久消失」）：
        //    这条 id 如果是【本编辑器新建出来的】（覆盖文件里那条条目是 op=add），
        //    那么这次保存【必须继续沿用 op=add】—— 绝不能改写成 op=set。
        //    为什么（用户日志逐字给出的那一条链）：
        //      ① 点「新建配方」⇒ 文件里一条 op=add ✓
        //      ② 他再去编辑这条新配方（补输入/输出）⇒ 老代码看到"活配方表里有这条"
        //         （其实是本局 op=add 刚重放出来的，不是真的持久化过）⇒ 改写成 op=set ✗
        //      ③ upsert 按 id 去重 ⇒ 旧的 op=add 被顶掉（用户日志：dropped_same_id=1）✗
        //      ④ 下一局开机：op=set 找不到那条配方 ⇒ MISSING no such recipe id ⇒ **配方消失** ✗✗
        //    ⇒ 判据必须是「它在【持久层/覆盖文件】里存在吗」，而不是「它在【本局活配方表】里存在吗」。
        //    op=add 条目本来就带整份 recipe JSON ⇒ 把这次保存的字段并进那份 JSON 即可，
        //    下一局重放时那条配方会被【原样重建】（含这次的输入/输出）✓
        final JsonObject passthrough = existingAddEntry(id);
        if (passthrough != null) {
            return persistStillAdd(id, type, fields, passthrough);
        }
        if (fp == null || fp.isEmpty() || ShanhaiRecipeFingerprint.UNKNOWN.equals(fp)) {
            ShanhaiMod.LOGGER.error("{} persist_skipped id={} reason=base_fp_unavailable ({})",
                    PREFIX, id, ShanhaiRecipeFingerprint.lastDiagnosis());
            return new PersistOutcome(false, "persist=SKIPPED(fp_unavailable)");
        }
        // 🔴 2026-10-05（B 组顺带修）：{@code upsert} 是【按 id 整条替换】⇒ 只带一份 fields 会把
        //    上一次写下的、这次没提到的字段【整条抹掉】。以前只有 IO/duration/电压三样、
        //    而面板保存一次就把三样都带上，所以没暴露；B 组一加条件就立刻会炸
        //    （例如用命令行改耗时 ⇒ 文件里那条 entry 的条件消失 ⇒ 下一局条件丢了，本局却正常）。
        //    修法 = 把现有条目里【这次没提到】的键合并进来（口子只加不改：已有键一律以本次为准）。
        final java.util.List<String> merged = new java.util.ArrayList<>();
        try {
            final JsonObject existing = ShanhaiRecipeOverrideStore.findEntry(id);
            if (existing != null && existing.has("fields") && existing.get("fields").isJsonObject()) {
                final JsonObject old = existing.getAsJsonObject("fields");
                for (var en : old.entrySet()) {
                    if (!fields.has(en.getKey())) {
                        fields.add(en.getKey(), en.getValue());
                        merged.add(en.getKey());
                    }
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} persist_merge_failed id={} err={} (本次按原样只写本次的字段)",
                    PREFIX, id, t.toString());
        }
        if (!merged.isEmpty()) {
            ShanhaiMod.LOGGER.info("{} persist_merged id={} keys={} (这些字段是上一次写的、这次没提到 ⇒ 原样保留)"
                    , PREFIX, id, merged);
        }
        final JsonObject e = ShanhaiRecipeOverrideStore.makeSetEntry(
                ShanhaiRecipeOverrideStore.nextUid(), id.toString(), typeId(type), fields, fp);
        return new PersistOutcome(ShanhaiRecipeOverrideStore.upsert(e) >= 0, "json_written");
    }

    private static PersistOutcome persistSet(MinecraftServer server, ResourceLocation id,
                                             GTRecipeType type, int value, String fp) {
        if (ShanhaiRecipeFingerprint.UNKNOWN.equals(fp)) {
            // 🔴 宁可"没写"，也不写一条永远对不上的指纹 —— 那种条目会让覆盖层每局都打一条 STALE，
            //    而 STALE 看起来像"配方被人动过"，会把下一次排查带偏。
            ShanhaiMod.LOGGER.error("{} persist_skipped id={} reason=base_fp_unavailable ({})",
                    PREFIX, id, ShanhaiRecipeFingerprint.lastDiagnosis());
            return new PersistOutcome(false, "persist=SKIPPED(fp_unavailable)");
        }
        // 走多字段版：它会先把"上一次写下、这次没提到"的键合并回来（否则只写 duration
        // 会把这条配方的条件/IO 从文件里抹掉 —— 见 persistSetFields 里的注释）。
        final JsonObject fields = new JsonObject();
        fields.addProperty("duration", value);
        return persistSetFields(server, id, type, fields, fp);
    }

    /**
     * 🔴 <b>这条 id 是"本编辑器新建出来的"吗</b> —— 判据是<b>覆盖文件里那条条目</b>的 op。
     *
     * <p>为什么不能拿"活配方表里有没有它"当判据（这正是本次 P0 的病根）：
     * 新建出来的那条配方，在<b>本局</b>是覆盖层 {@code op=add} 刚重放出来的，
     * 它<b>从来没有被持久化过</b>。拿活配方表当判据 ⇒ 下一次保存就会把它改写成 {@code op=set}
     * ⇒ {@code upsert} 按 id 去重把 {@code op=add} 顶掉 ⇒ 下一局开机
     * {@code MISSING no such recipe id} ⇒ <b>配方消失</b>（用户实测就是这么消失的）。
     *
     * @return 现有的那条 {@code op=add} 条目（没有就 {@code null}）
     */
    private static JsonObject existingAddEntry(ResourceLocation id) {
        try {
            final JsonObject e = ShanhaiRecipeOverrideStore.findEntry(id);
            if (e != null && e.has("op") && "add".equals(e.get("op").getAsString())) {
                return e;
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} add_passthrough_probe_failed id={} err={} (本次按原路写 op=set)",
                    PREFIX, id, t.toString());
        }
        return null;
    }

    /**
     * 新建出来的配方再次保存 ⇒ <b>继续写 {@code op=add}</b>，把这次的字段并进那份 recipe JSON。
     *
     * <p>为什么是"并进 recipe JSON"而不是"再写一条 op=set"：{@link ShanhaiRecipeOverrideStore#upsert}
     * 是<b>按 id 去重</b>的（同 id 只保留一条）—— 这是刻意的（同 id 两条会得到"先 add 再 set"
     * 这种自相矛盾的结果）⇒ 只能有一条 ⇒ 那一条必须自己就把最终状态表达完整。
     * {@code op=add} 的条目带的是一份<b>完整配方 JSON</b>，重放时原样重建 ⇒ 正好是最终状态 ✓
     *
     * <p>本分支<b>不需要 base_fp</b>（{@code op=add} 的语义是"从无到有"，没有底本可对）。
     * ⚠️ 这一点也是必须的：新建配方的 {@code base_fp} 算出来是
     * {@code v3.sorted:json:{"type":"unknown"}}（用户文件里的真实读数）——
     * 走老的 op=set 分支会被写成一条永远对不上的指纹。
     */
    private static PersistOutcome persistStillAdd(ResourceLocation id, GTRecipeType type,
                                                  JsonObject fields, JsonObject existing) {
        final JsonObject recipe = existing.has("recipe") && existing.get("recipe").isJsonObject()
                ? existing.getAsJsonObject("recipe").deepCopy() : new JsonObject();
        final java.util.List<String> merged = new java.util.ArrayList<>();
        for (var en : fields.entrySet()) {
            recipe.add(en.getKey(), en.getValue());
            merged.add(en.getKey());
        }
        // type 永远以这条条目的 type 为准（它是重放时选哪个类型函数的依据）
        final String typeStr = existing.has("type") ? existing.get("type").getAsString() : typeId(type);
        if (typeStr != null && !recipe.has("type")) {
            recipe.addProperty("type", typeStr);
        }
        final String uid = existing.has("uid") ? existing.get("uid").getAsString()
                : ShanhaiRecipeOverrideStore.nextUid();
        final JsonObject e = ShanhaiRecipeOverrideStore.makeAddEntry(uid, id.toString(), typeStr, recipe);
        final int n = ShanhaiRecipeOverrideStore.upsert(e);
        ShanhaiMod.LOGGER.info("{} persist_kept_add id={} type={} merged_fields={} entries={} "
                        + "(这条配方是本编辑器【新建】出来的 ⇒ 继续沿用 op=add；"
                        + "改写成 op=set 会让它在下一局开机时被判定 MISSING 从而消失 —— P0)",
                PREFIX, id, typeStr, merged, n);
        return new PersistOutcome(n >= 0, "json_written_kept_add");
    }

    private static PersistOutcome persistRemove(MinecraftServer server, ResourceLocation id,
                                                GTRecipeType type, String fp) {
        // 🔴 新建出来的配方被"删除" ⇒ 正确动作是【把那条 op=add 抹掉】，不是写一条 op=remove。
        //    写 op=remove 的话，下一局开机那条配方压根不存在 ⇒ 覆盖层打 MISSING
        //    （一条吓人但无害的 ERROR），而且"删除"这个意图已经由"条目不存在"表达了。
        final JsonObject passthrough = existingAddEntry(id);
        if (passthrough != null) {
            final int n = ShanhaiRecipeOverrideStore.removeEntryById(id);
            ShanhaiMod.LOGGER.info("{} persist_dropped_add id={} type={} entries={} "
                            + "(这条是本编辑器新建的 ⇒ 删除 = 把那条 op=add 抹掉，不写 op=remove)",
                    PREFIX, id, typeId(type), n);
            return new PersistOutcome(n >= 0, "json_written_dropped_add");
        }
        if (ShanhaiRecipeFingerprint.UNKNOWN.equals(fp)) {
            ShanhaiMod.LOGGER.error("{} persist_skipped id={} reason=base_fp_unavailable ({})",
                    PREFIX, id, ShanhaiRecipeFingerprint.lastDiagnosis());
            return new PersistOutcome(false, "persist=SKIPPED(fp_unavailable)");
        }
        final JsonObject e = ShanhaiRecipeOverrideStore.makeRemoveEntry(
                ShanhaiRecipeOverrideStore.nextUid(), id.toString(), typeId(type), fp);
        return new PersistOutcome(ShanhaiRecipeOverrideStore.upsert(e) >= 0, "json_written");
    }

    private static String typeId(GTRecipeType type) {
        return type == null || type.registryName == null ? null : type.registryName.toString();
    }

    // ================================================================= base_fp 的口径修正

    /**
     * 🔴 <b>决定这次该写哪个 base_fp</b> —— 本刀最隐蔽的一条，冒烟第 4 局的自检抓到的。
     *
     * <h2>病是什么</h2>
     * KubeJS 在配方事件 {@code post()} 阶段（也就是 {@code injectRuntimeRecipes} 那个点）
     * 会把 {@code RecipeJS.json} <b>按改后的活配方刷新一遍</b>。而覆盖层脚本是在<b>它自己的脚本回调里</b>
     * 读 {@code recipe.json} 的 —— 那一刻覆盖层还没把自己那条 entry 套上去。
     * ⇒ 两边读到的是<b>同一份 JSON 的两个不同时刻</b>：
     * <pre>
     *   覆盖层下一局算的 fp   = "源声明长什么样"（apply 之前）
     *   我们缓存里那份 fp      = "覆盖层已经套用之后长什么样"（apply 之后）
     * </pre>
     * 第一次编辑某条配方时两者相同（本来就没 entry 可套）⇒ 能对上；
     * <b>同一条配方改第二次时两者不同</b> ⇒ 我们会写进一条永远对不上的指纹
     * ⇒ 覆盖层下一局打 STALE、<b>不生效</b>。实测读数见冒烟第 4 局的
     * {@code case=fp_vs_file id=… match=false}（文件里 {@code "duration":100} vs 缓存 {@code "duration":424342}）。
     *
     * <h2>修法</h2>
     * 「覆盖层这一局到底套没套上」是可判的：<b>它套上了 ⇔ 活配方当下的那个字段值 == entry.fields 里那个值</b>。
     * <ul>
     *   <li><b>套上了</b> ⇒ 文件里那条 entry 的 {@code base_fp} <b>就是</b>"源声明长什么样"（它当初能对上才被套上）
     *       ⇒ <b>沿用它</b>，不要用我们缓存里那份；</li>
     *   <li><b>没套上</b>（没有 entry，或那条 entry 的字段与活值不符 ⇒ 上一局是 STALE）
     *       ⇒ 活配方等于源声明 ⇒ <b>用缓存里那份</b>。</li>
     * </ul>
     */
    public static String baseFpFor(MinecraftServer server, ResourceLocation id,
                                   GTRecipeType type, int liveDuration) {
        return resolveBaseFp(server, id, type, liveDuration);
    }

    private static String resolveBaseFp(MinecraftServer server, ResourceLocation id,
                                        GTRecipeType type, int liveDuration) {
        return resolveBaseFp0(server, id, type, liveDuration);
    }

    private static String resolveBaseFp0(MinecraftServer server, ResourceLocation id,
                                         GTRecipeType type, int liveDuration) {        final String cached = ShanhaiRecipeFingerprint.forRecipeId(id);
        try {
            final JsonObject existing = ShanhaiRecipeOverrideStore.findEntry(id);
            if (existing != null && existing.has("base_fp")) {
                final String want = existing.get("base_fp").getAsString();
                final boolean isSet = existing.has("op") && "set".equals(existing.get("op").getAsString());
                final JsonObject fields = existing.has("fields") && existing.get("fields").isJsonObject()
                        ? existing.getAsJsonObject("fields") : null;
                final boolean applied = isSet && fields != null && fieldsAppliedTo(server, id, fields);
                if (applied && !want.isEmpty()) {
                    // 🔴 2026-10-05（B4）迁移：文件里那条 entry 是【旧版指纹】时，绝不能沿用它 ——
                    //    沿用它 = 把旧版串再写一遍 ⇒ 下一局又是一条 NEEDS_RESAVE。
                    //    正确做法：用本侧缓存里那份【新版】指纹（活配方 == 源声明，本来就是它）。
                    if (!ShanhaiRecipeFingerprint.isCurrentVersion(want)) {
                        ShanhaiMod.LOGGER.warn("{} fp_source=cache id={} reason=existing_entry_old_version"
                                        + " version={} -> 不沿用旧串（沿用它会把旧版指纹再写一遍）；"
                                        + "本次保存会把它升级到 {}",
                                PREFIX, id, ShanhaiRecipeFingerprint.versionOf(want),
                                ShanhaiRecipeFingerprint.FP_PREFIX_V3);
                    } else {
                        ShanhaiMod.LOGGER.info("{} fp_source=file id={} reason=overlay_already_applied "
                                        + "(entry.fields.duration(原始)={} -> 换算后实际={} == 底本实际={}) "
                                        + "-> 沿用文件里那份（它才是'源声明长什么样'）",
                                PREFIX, id, liveDuration,
                                ShanhaiRecipeDuration.toLive(type, liveDuration),
                                ShanhaiRecipeBase.pristine(id) == null ? -1 : ShanhaiRecipeBase.pristine(id).duration);
                        return want;
                    }
                } else {
                    ShanhaiMod.LOGGER.info("{} fp_source=cache id={} reason=not_applied "
                                    + "(is_set={} fields_match_live={}) -> 用缓存里那份",
                            PREFIX, id, isSet,
                            fields != null && fields.has("duration") && fields.get("duration").getAsInt() == liveDuration);
                }
            } else {
                ShanhaiMod.LOGGER.info("{} fp_source=cache id={} reason=no_prior_entry", PREFIX, id);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} fp_source=cache id={} reason=probe_threw {} ", PREFIX, id, t.toString());
        }
        return cached;
    }

    /**
     * <b>泛化后的"覆盖层这一局到底套没套上"判据</b>（第二刀）。
     *
     * <p>老版本只看 {@code fields.duration}，于是"只改了 IO / 只改了电压"的条目永远被判成"没套上"
     * ⇒ 第二次编辑会写错 {@code base_fp} ⇒ 下一局 STALE 不生效（第一刀在 duration 上踩过）。
     *
     * <p>新判据：{@code applied ⇔ fields 里【每一个】键都与底本当下的值相等}。
     * 对 {@code data} 只比它自己写明的键（我们只写 {@code euTier}）——
     * 这是<b>如实缩小</b>的判据，不是全等：{@code data} 里还可能有别的键，
     * 逐个比会因为它被别的东西改过而误判成"没套上"。
     */
    private static boolean fieldsAppliedTo(MinecraftServer server, ResourceLocation id, JsonObject fields) {
        if (fields == null || fields.size() == 0) {
            return false;
        }
        final GTRecipe base = ShanhaiRecipeBase.pristine(id);
        if (base == null) {
            return false;
        }
        for (var en : fields.entrySet()) {
            final String k = en.getKey();
            final com.google.gson.JsonElement want = en.getValue();
            switch (k) {
                case "duration" -> {
                    // 🔴 口径（2026-10-05）：文件里那份是【原始值】，底本那份是【实际值】
                    //    ⇒ 必须换算之后再比，否则"已经套上"永远判成 false（下一局 STALE）。
                    if (!want.isJsonPrimitive()
                            || ShanhaiRecipeDuration.toLive(base.getType(), want.getAsInt()) != base.duration) {
                        return false;
                    }
                }
                case "data" -> {
                    if (!want.isJsonObject()) {
                        return false;
                    }
                    final JsonObject w = want.getAsJsonObject();
                    if (w.has("euTier") && base.data.getInt("euTier") != w.get("euTier").getAsInt()) {
                        return false;
                    }
                }
                case "inputs", "outputs", "tickInputs" -> {
                    if (!want.isJsonObject()) {
                        return false;
                    }
                    final JsonObject liveTable = ShanhaiRecipeIoApply.tableJson(base, k);
                    if (!normalize(want).equals(normalize(liveTable))) {
                        return false;
                    }
                }
                case ShanhaiRecipeConditions.FIELD -> {
                    // 🆕 2026-10-05（B 组）：条件也必须在这里认出来。
                    //    🔴 漏了它会发生什么（这就是"顺带检查同类问题"抓到的那一条）：
                    //       走到下面的 default 分支 ⇒ 返回 false ⇒ "覆盖层这一局到底套没套上" 判成【没套上】
                    //       ⇒ 第二次保存会写错 base_fp ⇒ 下一局 STALE、不套用
                    //       ⇒ 用户看到的是「刚存的条件重启就没了」，而日志上一切正常。
                    if (!want.isJsonArray()) {
                        return false;
                    }
                    final com.google.gson.JsonArray wantArr = want.getAsJsonArray();
                    final ShanhaiRecipeConditions.Decoded wd = ShanhaiRecipeConditions.decode(wantArr);
                    if (!wd.ok()) {
                        // 文件里那份自己就解不出来 ⇒ 绝不当成"已经套上了"
                        ShanhaiMod.LOGGER.warn("{} fp_probe_conditions_undecodable id={} err={} -> not_applied",
                                PREFIX, id, wd.error());
                        return false;
                    }
                    if (!ShanhaiRecipeConditions.sameAs(wantArr, ShanhaiRecipeConditions.encodeOf(base))) {
                        return false;
                    }
                }
                default -> {
                    // 认不出的键 ⇒ 不许当成"套上了"（宁可走 cache 那一支，也不要写一个可能对不上的指纹）
                    ShanhaiMod.LOGGER.warn("{} fp_probe_unknown_field id={} key={} -> treated as not_applied",
                            PREFIX, id, k);
                    return false;
                }
            }
        }
        return true;
    }

    /** 递归按字典序排对象键后再序列化（数组保持原序）—— 两边同口径才能比。 */
    private static String normalize(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "null";
        }
        if (el.isJsonArray()) {
            final StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(normalize(e));
                first = false;
            }
            return sb.append(']').toString();
        }
        if (el.isJsonObject()) {
            final java.util.TreeMap<String, com.google.gson.JsonElement> sorted = new java.util.TreeMap<>();
            for (var en : el.getAsJsonObject().entrySet()) {
                sorted.put(en.getKey(), en.getValue());
            }
            final StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var en : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(en.getKey()).append("\":").append(normalize(en.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        return el.toString();
    }
}
