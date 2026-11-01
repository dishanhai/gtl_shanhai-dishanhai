package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * <b>「覆盖层写下的 fields」与「活配方」到底一不一致</b> —— 纯核判据，<b>逐字段</b>比。
 *
 * <h2>1. 这个类为什么存在（它修的是哪一条谎）</h2>
 * 2026-11 用户报「JEI 显示和实际配方不一样」「配方编辑器里看到的和 JEI 里的一样」，
 * 排查时那条日志把判断带偏了。原文（{@code ShanhaiRecipeEditorOps} 旧版）：
 * <pre>
 *   fp_source=cache id=… reason=not_applied
 *   (is_set=true fields_match_live=true) -> 用缓存里那份
 * </pre>
 * 而那个 {@code fields_match_live} 的表达式<b>只比了 {@code duration} 一个字段</b>：
 * <pre>
 *   fields != null &amp;&amp; fields.has("duration") &amp;&amp; fields.get("duration").getAsInt() == liveDuration
 * </pre>
 * ⇒ <b>输入/输出/条件完全不同也照报 {@code true}</b>。名字说"逐字段"，实际只比了一个数。
 * 这是一条"日志说谎"的缺陷：它不看配方也能成立，且只要有人信它，排查方向就整体跑偏。
 *
 * <h2>2. 判据只有三种结局（不许把"比不了"混进"一致"）</h2>
 * <ul>
 *   <li>{@link Kind#MATCH} —— 每一张表、每一个字段都一致；</li>
 *   <li>{@link Kind#MISMATCH} —— <b>指出是哪几个字段</b>（{@code MISMATCH[inputs,outputs]}）；</li>
 *   <li>{@link Kind#UNCOMPARABLE} —— <b>拿不到活配方</b>（或 fields 是空的）⇒ 比不了。
 *       🔴 这一支<b>绝不允许</b>被当成 MATCH：宁可说"比不了"，也不许说"一致"。</li>
 * </ul>
 *
 * <h2>3. 为什么它是"纯核"（这个要求是硬的）</h2>
 * 本类<b>不 import 任何 Minecraft / GT / KubeJS 的类</b>，只吃 {@code com.google.gson}。
 * 于是它可以<b>脱机编译、脱机跑自证</b>（{@code temp/zhuguang-bug/fielddiff/}），
 * 不需要开游戏、不需要服务端。GT 那一侧的适配（活配方 → 这份 JSON 快照）留在
 * {@code ShanhaiRecipeEditorOps#liveSnapshot}，那里才是唯一的 GT 依赖点。
 *
 * <h2>4. IO 的比较口径：<b>顺序无关的多重集</b></h2>
 * 覆盖层写下的 {@code inputs} 与活配方上那张表<b>条目顺序不保证相同</b>（同一个 id 可能被
 * 不同的写入路径重排），而"顺序不同"不是"内容不同"。所以：
 * <pre>
 *   判据签名 = 每条 entry 的【规范化后的整条 JSON】（对象键排序、数组保序）排好序的列表
 * </pre>
 * ⇒ 顺序无关 ✓；数量/流体 mB ✓；{@code chance/maxChance/tierChanceBoost} 这些也跟着比 ✓
 * （它们本来就属于"这条配方长什么样"，不比就会放过"概率被改过"）。
 * <p>⚠️ 与旧的 {@code normalize(a).equals(normalize(b))} 相比，本判据<b>只在"顺序"上更宽</b>，
 * 在其它任何一处都<b>不比它松</b> ⇒ 新判据说 MATCH 时，旧判据最多因为顺序不同而说"没套上"。
 *
 * <h2>5. {@code duration} 的口径差异用回调传进来（不写死在这里）</h2>
 * 文件里那份 {@code duration} 是<b>原始值</b>，活配方上那个是<b>实际值</b>（gtlcore 会乘一个
 * {@code duration_scale}，实测 {@code k=0.001}）。两者的换算住在
 * {@link ShanhaiRecipeDuration#toLive}，而它需要 {@code GTRecipeType} ⇒ <b>不是纯核</b>。
 * ⇒ 本类只收一个 {@link DurationCmp}：{@code (fields 里那个元素, 活配方 duration) -> 是否相等}。
 * 纯核默认用 {@link #DURATION_RAW}（两边都是同一个口径时），生产侧传
 * {@code ShanhaiRecipeEditorOps#durationCmpFor(type)}。
 */
public final class ShanhaiRecipeFieldDiff {

    /** 与其它编辑器类同一个前缀（日志可按它一次抓全）。 */
    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    // ------------------------------------------------------------ 字段名（与覆盖层脚本同口径）
    public static final String F_INPUTS = "inputs";
    public static final String F_OUTPUTS = "outputs";
    public static final String F_TICK_INPUTS = "tickInputs";
    public static final String F_TICK_OUTPUTS = "tickOutputs";
    public static final String F_DURATION = "duration";
    public static final String F_DATA = "data";
    /**
     * 额外条件那个键。
     * <p>⚠️ 这里写的是<b>字面量</b>而不是 {@code ShanhaiRecipeConditions.FIELD} —— 那个类 import 了
     * GT 的 {@code RecipeCondition}，引用它会把本类拖出"纯核"（脱机编译就过不了）。
     * 它必须与 {@code ShanhaiRecipeConditions.FIELD} 相等（都是 {@code "conditions"}）；
     * 判据在生产侧是拿 {@code ShanhaiRecipeConditions.encodeOf(live)} 比这一份的，
     * 两边的键名一旦写岔，条件就永远"比不了" ⇒ 这一行不许改着玩。
     */
    public static final String F_CONDITIONS = "conditions";

    /** 判据里被逐个比对的字段（顺序 = 日志与 detail 里的固定顺序）。 */
    public static final List<String> COMPARED_FIELDS = List.of(
            F_INPUTS, F_OUTPUTS, F_TICK_INPUTS, F_TICK_OUTPUTS, F_DURATION, F_DATA, F_CONDITIONS);

    private ShanhaiRecipeFieldDiff() {}

    // ================================================================== 三种结局

    public enum Kind { MATCH, MISMATCH, UNCOMPARABLE }

    /**
     * 一次比较的结论。
     *
     * @param kind   三种结局之一
     * @param fields {@code kind=MISMATCH} 时 = 不一致的那些字段名（固定顺序）；其它情况为空
     * @param reason {@code kind=UNCOMPARABLE} 时的机器可读原因（{@code no_live} / {@code no_fields} /
     *               {@code unknown_field:x} / {@code probe_threw}）
     * @param detail 人类可读的一句话（直接进日志，<b>不许空</b>）
     */
    public record Verdict(Kind kind, List<String> fields, String reason, String detail) {

        public Verdict {
            fields = fields == null ? List.of() : List.copyOf(fields);
            reason = reason == null ? "" : reason;
            detail = detail == null ? "" : detail;
        }

        public boolean isMatch() {
            return kind == Kind.MATCH;
        }

        /** 日志里那个值：{@code MATCH} / {@code MISMATCH[inputs,outputs]} / {@code UNCOMPARABLE(no_live)}。 */
        public String label() {
            return switch (kind) {
                case MATCH -> "MATCH";
                case MISMATCH -> "MISMATCH[" + String.join(",", fields) + "]";
                case UNCOMPARABLE -> "UNCOMPARABLE(" + reason + ")";
            };
        }

        @Override
        public String toString() {
            return label() + " detail=" + detail;
        }
    }

    /** {@code duration} 的相等判据（把"原始值 ↔ 实际值"的换算留给调用方）。 */
    @FunctionalInterface
    public interface DurationCmp {
        /**
         * @param fieldsDuration 覆盖层 {@code fields.duration} 那个元素（可能是 null / 非数字）
         * @param liveDuration   活配方上的 {@code GTRecipe.duration}（实际值，已含 duration_scale）
         */
        boolean matches(JsonElement fieldsDuration, int liveDuration);
    }

    /** 两边同一个口径时用它（{@code fields.duration} 与 {@code live.duration} 都是实际值）。 */
    public static final DurationCmp DURATION_RAW = (fieldsDuration, liveDuration) ->
            fieldsDuration != null && fieldsDuration.isJsonPrimitive()
                    && fieldsDuration.getAsJsonPrimitive().isNumber()
                    && fieldsDuration.getAsInt() == liveDuration;

    // ================================================================== 唯一入口

    /**
     * <b>逐字段</b>比较 {@code fields}（覆盖层条目里那份）与 {@code live}（活配方快照）。
     *
     * @param fields   覆盖层那条 entry 的 {@code fields}；null / 空 ⇒ {@code UNCOMPARABLE(no_fields)}
     * @param live     {@link #liveSnapshotShape()} 那张形状的 JSON；null / 空 ⇒ {@code UNCOMPARABLE(no_live)}
     * @param durationCmp {@code duration} 的相等判据；null ⇒ {@link #DURATION_RAW}
     */
    public static Verdict compare(JsonObject fields, JsonObject live, DurationCmp durationCmp) {
        if (fields == null || fields.size() == 0) {
            return new Verdict(Kind.UNCOMPARABLE, List.of(), "no_fields",
                    "覆盖层这条 entry 的 fields 是空的（没有可比的字段）");
        }
        if (live == null || live.size() == 0) {
            return noLive();
        }
        final DurationCmp cmp = durationCmp == null ? DURATION_RAW : durationCmp;
        final List<String> bad = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> unknown = new ArrayList<>();
        final List<String> compared = new ArrayList<>();

        for (String k : orderedKeys(fields)) {
            final JsonElement want = fields.get(k);
            switch (k) {
                case F_INPUTS, F_OUTPUTS, F_TICK_INPUTS, F_TICK_OUTPUTS -> {
                    compared.add(k);
                    final String note = tableNote(k, want, live.get(k));
                    if (note != null) {
                        bad.add(k);
                        notes.add(note);
                    }
                }
                case F_DURATION -> {
                    compared.add(k);
                    final int liveDuration = intOf(live.get(k), Integer.MIN_VALUE);
                    if (!cmp.matches(want, liveDuration)) {
                        bad.add(k);
                        notes.add("duration 覆盖层写 " + shortJson(want) + "，活配方上 " + liveDuration);
                    }
                }
                case F_DATA -> {
                    compared.add(k);
                    final String note = dataNote(want, live.get(k));
                    if (note != null) {
                        bad.add(k);
                        notes.add(note);
                    }
                }
                case F_CONDITIONS -> {
                    compared.add(k);
                    final String note = conditionsNote(want, live.get(k));
                    if (note != null) {
                        bad.add(k);
                        notes.add(note);
                    }
                }
                default -> unknown.add(k);
            }
        }

        if (!bad.isEmpty()) {
            return new Verdict(Kind.MISMATCH, bad, "",
                    String.join("；", notes) + "（已比对 " + compared.size() + " 个字段："
                            + String.join(",", compared) + "）");
        }
        if (!unknown.isEmpty()) {
            // 认不出的字段 ⇒ 绝不当成"一致"：我们连它该拿什么去比都不知道。
            return new Verdict(Kind.UNCOMPARABLE, List.of(), "unknown_field:" + String.join(",", unknown),
                    "字段 " + unknown + " 不在本判据的认识范围内 ⇒ 比不了（宁可说比不了，也不说一致）");
        }
        return new Verdict(Kind.MATCH, List.of(), "",
                "已逐字段比对 " + compared.size() + " 个（" + String.join(",", compared) + "）全部一致");
    }

    /** {@code live} 该长什么样（调用方照这个形状拍快照；纯核只认这个形状）。 */
    public static List<String> liveSnapshotShape() {
        return COMPARED_FIELDS;
    }

    /**
     * 拿不到活配方时的结论 —— 调用方（尤其是显示源裁决）必须原样用这一支，
     * <b>绝不许把它降级成 MATCH</b>。
     */
    public static Verdict noLive() {
        return new Verdict(Kind.UNCOMPARABLE, List.of(), "no_live",
                "服务端配方表里取不到这条 id 的活配方 ⇒ 比不了（不是「不一致」，是「拿不到」）");
    }

    /** 覆盖层里根本没有这条 id 的 fields ⇒ 没有可比的数据（<b>不是</b> MATCH）。 */
    public static Verdict noFields() {
        return new Verdict(Kind.UNCOMPARABLE, List.of(), "no_fields",
                "覆盖层没有这条 id 的 fields ⇒ 没有覆盖数据可比（显示源 = 活配方）");
    }

    // ================================================================== 显示源裁决（纯核）

    /**
     * 显示源裁决的结论。
     *
     * @param source  恒为 {@code "live"} —— 显示数据只能来自活配方
     * @param why     {@code match} / {@code overlay_mismatch} / {@code no_live} / {@code no_overlay}
     * @param verdict 三态判据
     * @param warn    🔴 <b>该不该打那条 WARN</b>。这个布尔量是"日志量"的唯一开关：
     *                {@code false} ⇒ 调用方<b>一行日志都不许打</b>（一致时回归逐字相同）
     */
    public record Display(String source, String why, Verdict verdict, boolean warn) {
        public Display {
            source = source == null ? "live" : source;
            why = why == null ? "" : why;
            verdict = verdict == null ? noFields() : verdict;
        }

        public String label() {
            return verdict.label();
        }

        public String detail() {
            return verdict.detail();
        }
    }

    /**
     * <b>显示源裁决（纯核版）</b>：给定"覆盖层 fields"与"活配方快照"，决定显示源与要不要打 WARN。
     *
     * <p>这张表就是全部语义（也就是自证里 D1-D5 那几行）：
     * <pre>
     *   live  == null              ⇒ source=live, why=no_live,          warn=true
     *   fields == null             ⇒ source=live, why=no_overlay,       warn=false
     *   compare ⇒ MATCH            ⇒ source=live, why=match,            warn=false
     *   compare ⇒ MISMATCH/UNCOMPAR ⇒ source=live, why=overlay_mismatch, warn=true
     * </pre>
     * 🔴 注意 {@code source} 恒为 {@code "live"}：<b>不提供"用覆盖层那份显示"这个选项</b>，
     * 因为那正是用户看到的病（两个界面显示同一份过期数据）。
     */
    public static Display displaySource(JsonObject fields, JsonObject live, DurationCmp durationCmp) {
        // ⚠️ 顺序有意义：先看"覆盖层有没有可比的数据"，再看"活配方在不在"。
        //    生产侧为了省掉"每条配方都拍一遍 IO 快照"的开销，会在 fields 为 null 时【不拍快照】、
        //    直接传 null 进来 ⇒ 这时必须落到 no_overlay（而不是 no_live）。
        if (fields == null || fields.size() == 0) {
            return new Display("live", "no_overlay", noFields(), false);
        }
        if (live == null || live.size() == 0) {
            return new Display("live", "no_live", noLive(), true);
        }
        final Verdict v = compare(fields, live, durationCmp);
        if (v.isMatch()) {
            return new Display("live", "match", v, false);
        }
        return new Display("live", "overlay_mismatch", v, true);
    }

    /**
     * <b>这一拍该打的那一行 WARN 全文</b>；{@code null} ⇒ <b>一个字都不许打</b>。
     *
     * <p>把日志文本也放进纯核，是为了让它可被脱机自证（"打开一致的配方 ⇒ 一行日志都不打"
     * 就变成 {@code warnLine(...) == null} 这一条断言，而不是靠人读代码）。
     * 生产侧（{@code ShanhaiRecipeEditorOps#displaySourceFor}）只负责把这一行交给 logger。
     */
    public static String warnLine(String id, Display d) {
        if (d == null || !d.warn()) {
            return null;
        }
        final String why = "no_live".equals(d.why()) ? "no_live" : "overlay_mismatch";
        final String tail = "no_live".equals(why)
                ? "（活配方取不到 ⇒ 显示源保持现状；这一支【不许】被当成「覆盖层与活配方一致」）"
                : "（编辑器显示的是【活配方】那一份；覆盖层 fields 与它对不上 ⇒ fields 不许当显示数据源）";
        return "editor_show_source=live id=" + id + " why=" + why
                + " diff=" + d.label() + " detail=" + d.detail() + " " + tail;
    }

    // ================================================================== 每一类字段的比法

    /** @return null = 一致；否则一句人话 */
    private static String tableNote(String fieldName, JsonElement wantEl, JsonElement liveEl) {
        if (wantEl == null || !wantEl.isJsonObject()) {
            return fieldName + " 覆盖层那份不是对象（" + shortJson(wantEl) + "）⇒ 判不一致";
        }
        final JsonObject a = wantEl.getAsJsonObject();
        final JsonObject b = liveEl != null && liveEl.isJsonObject() ? liveEl.getAsJsonObject() : new JsonObject();
        if (tableSigs(a).equals(tableSigs(b))) {
            return null;
        }
        final List<String> ta = tableTokens(a);
        final List<String> tb = tableTokens(b);
        final List<String> onlyA = minus(ta, tb);
        final List<String> onlyB = minus(tb, ta);
        final StringBuilder sb = new StringBuilder(fieldName).append(" 不一致");
        if (!onlyA.isEmpty()) {
            sb.append("；覆盖层多[").append(joinCapped(onlyA, 4)).append("]");
        }
        if (!onlyB.isEmpty()) {
            sb.append("；活配方多[").append(joinCapped(onlyB, 4)).append("]");
        }
        if (onlyA.isEmpty() && onlyB.isEmpty()) {
            sb.append("；条目 id×数量相同，差在 chance/其它附带键（这种差异也必须算不一致）");
        }
        return sb.toString();
    }

    /** {@code data}：只比我们确实写进去的那个键（{@code euTier}），其余键<b>如实缩小</b>不比。 */
    private static String dataNote(JsonElement wantEl, JsonElement liveEl) {
        if (wantEl == null || !wantEl.isJsonObject()) {
            return "data 覆盖层那份不是对象 ⇒ 判不一致";
        }
        final JsonObject w = wantEl.getAsJsonObject();
        if (!w.has("euTier")) {
            return null;                       // 没写 euTier = 这一条不主张电压档
        }
        final JsonObject l = liveEl != null && liveEl.isJsonObject() ? liveEl.getAsJsonObject() : null;
        final int liveTier = l == null ? 0 : intOf(l.get("euTier"), 0);
        final int wantTier = intOf(w.get("euTier"), Integer.MIN_VALUE);
        if (liveTier == wantTier) {
            return null;
        }
        return "data.euTier 覆盖层写 " + wantTier + "，活配方上 " + liveTier;
    }

    /** {@code conditions}：规范化之后逐字比（与 {@code ShanhaiRecipeConditions.sameAs} 同一口径）。 */
    private static String conditionsNote(JsonElement wantEl, JsonElement liveEl) {
        if (wantEl == null || !wantEl.isJsonArray()) {
            return F_CONDITIONS + " 覆盖层那份不是数组 ⇒ 判不一致";
        }
        final JsonArray w = wantEl.getAsJsonArray();
        final JsonArray l = liveEl != null && liveEl.isJsonArray() ? liveEl.getAsJsonArray() : new JsonArray();
        if (canonical(w).equals(canonical(l))) {
            return null;
        }
        return F_CONDITIONS + " 不一致（覆盖层 " + w.size() + " 条 vs 活配方 " + l.size() + " 条）";
    }

    // ================================================================== 多重集签名 / 人话 token

    /**
     * 一张 IO 表的判据签名：{@code "<cap>#<整条 entry 的规范化 JSON>"} 排好序。
     * <p>⚪ 排序 ⇒ 顺序无关；⚪ 整条 entry 参与 ⇒ 数量、mB、chance 全都跟着比。
     */
    private static List<String> tableSigs(JsonObject table) {
        final List<String> out = new ArrayList<>();
        if (table == null) {
            return out;
        }
        for (String cap : orderedCaps(table)) {
            final JsonElement el = table.get(cap);
            if (el == null || el.isJsonNull()) {
                continue;
            }
            if (!el.isJsonArray()) {
                // 形状不对 ⇒ 原样进签名（宁可判不等，也不放过）
                out.add(cap + "#!" + canonical(el));
                continue;
            }
            for (JsonElement c : el.getAsJsonArray()) {
                out.add(cap + "#" + canonical(c));
            }
        }
        Collections.sort(out);
        return out;
    }

    /** 人类可读的条目（日志 detail 用）：{@code item minecraft:circuit×7} / {@code fluid x×64000mB}。 */
    private static List<String> tableTokens(JsonObject table) {
        final List<String> out = new ArrayList<>();
        if (table == null) {
            return out;
        }
        for (String cap : orderedCaps(table)) {
            final JsonElement el = table.get(cap);
            if (el == null || el.isJsonNull()) {
                continue;
            }
            if (!el.isJsonArray()) {
                out.add(cap + " " + shortJson(el));
                continue;
            }
            for (JsonElement c : el.getAsJsonArray()) {
                out.add(tokenOf(cap, c));
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * 一条 entry 的人话 token。
     *
     * <p>真实形状有两种（都在本机覆写文件里见过），必须都认：
     * <pre>
     *   落盘 fields（{@code Content.codec} 原样）: {"content":{"item":"x","count":7},"chance":10000,…}
     *   配方 JSON（{@code op=add} 的 recipe）     : {"content":{"type":"gtceu:sized","count":1,
     *                                                          "ingredient":{"item":"x"}}}
     * </pre>
     * 认不出 id 时退回"整条 entry 的短 JSON" —— <b>只影响这句话好不好读，不影响判据</b>
     * （判据走 {@link #tableSigs}，整条 entry 参与比较）。
     */
    private static String tokenOf(String cap, JsonElement entry) {
        if (entry == null || !entry.isJsonObject()) {
            return cap + " " + shortJson(entry);
        }
        final JsonElement contentEl = entry.getAsJsonObject().get("content");
        final Id id = idOf(contentEl);
        if (id == null) {
            return cap + " " + shortJson(entry);
        }
        final long n = countOf(contentEl, 1L);
        final boolean fluid = "fluid".equals(cap) || id.fluid();
        return cap + " " + id.id() + "×" + n + (fluid ? "mB" : "");
    }

    /** {@code {id, 是不是流体}} —— 只为把这句话写清楚，判据不用它。 */
    private record Id(String id, boolean fluid) {}

    private static Id idOf(JsonElement el) {
        if (el == null) {
            return null;
        }
        if (el.isJsonArray()) {
            return new Id("any" + shortJson(el), false);
        }
        if (!el.isJsonObject()) {
            return null;
        }
        final JsonObject o = el.getAsJsonObject();
        if (o.has("item") && o.get("item").isJsonPrimitive()) {
            return new Id(o.get("item").getAsString(), false);
        }
        if (o.has("fluid") && o.get("fluid").isJsonPrimitive()) {
            return new Id(o.get("fluid").getAsString(), true);
        }
        if (o.has("tag") && o.get("tag").isJsonPrimitive()) {
            return new Id("#" + o.get("tag").getAsString(), false);
        }
        if (o.has("ingredient")) {
            return idOf(o.get("ingredient"));      // gtceu:sized 这类包装
        }
        return null;
    }

    private static long countOf(JsonElement el, long dflt) {
        if (el == null || !el.isJsonObject()) {
            return dflt;
        }
        final JsonObject o = el.getAsJsonObject();
        for (String k : List.of("amount", "count")) {
            if (o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber()) {
                return o.get(k).getAsLong();
            }
        }
        return o.has("ingredient") ? countOf(o.get("ingredient"), dflt) : dflt;
    }

    private static List<String> orderedCaps(JsonObject table) {
        final List<String> known = new ArrayList<>();
        for (String c : List.of("item", "fluid", "eu")) {
            if (table.has(c)) {
                known.add(c);
            }
        }
        final List<String> rest = new ArrayList<>();
        for (String k : new TreeMap<>(asMap(table)).keySet()) {
            if (!known.contains(k)) {
                rest.add(k);
            }
        }
        known.addAll(rest);
        return known;
    }

    private static TreeMap<String, JsonElement> asMap(JsonObject o) {
        final TreeMap<String, JsonElement> m = new TreeMap<>();
        for (var en : o.entrySet()) {
            m.put(en.getKey(), en.getValue());
        }
        return m;
    }

    private static List<String> orderedKeys(JsonObject fields) {
        final List<String> known = new ArrayList<>();
        for (String k : COMPARED_FIELDS) {
            if (fields.has(k)) {
                known.add(k);
            }
        }
        final List<String> rest = new ArrayList<>();
        for (String k : new TreeMap<>(asMap(fields)).keySet()) {
            if (!known.contains(k)) {
                rest.add(k);
            }
        }
        known.addAll(rest);
        return known;
    }

    // ================================================================== 小工具（都可脱机跑）

    /**
     * 递归规范化：<b>对象键按字典序排</b>，数组保持原序，再序列化成串。
     *
     * <p>口径与 {@code ShanhaiRecipeConditions#canonical} / {@code ShanhaiRecipeEditorOps#normalize}
     * <b>逐字相同</b>（本类那份是从它们搬过来的纯核版本；搬的理由是本类不许 import 带 GT 的类）。
     */
    public static String canonical(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "null";
        }
        if (el.isJsonArray()) {
            final StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonElement e : el.getAsJsonArray()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(canonical(e));
                first = false;
            }
            return sb.append(']').toString();
        }
        if (el.isJsonObject()) {
            final StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var en : asMap(el.getAsJsonObject()).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(en.getKey()).append("\":").append(canonical(en.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        return el.toString();
    }

    /** 短一点的 JSON 文本（日志用；太长就截断 —— 日志行必须能一眼读完）。 */
    public static String shortJson(JsonElement el) {
        final String s = el == null ? "null" : canonical(el);
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    private static int intOf(JsonElement el, int dflt) {
        try {
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                return el.getAsInt();
            }
        } catch (Throwable ignored) {
            // 非数字 ⇒ 用默认值（判据按"不相等"处理，见调用点）
        }
        return dflt;
    }

    private static List<String> minus(List<String> a, List<String> b) {
        final List<String> out = new ArrayList<>(a);
        for (String s : b) {
            out.remove(s);
        }
        return out;
    }

    private static String joinCapped(List<String> list, int cap) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < cap; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i));
        }
        if (list.size() > cap) {
            sb.append(" 等 ").append(list.size()).append(" 条");
        }
        return sb.toString();
    }
}
