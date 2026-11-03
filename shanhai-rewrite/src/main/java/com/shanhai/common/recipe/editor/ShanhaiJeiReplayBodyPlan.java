package com.shanhai.common.recipe.editor;

/**
 * 山海重构 · <b>「重放补丁要不要带完整配方正文」的纯核</b>（不 import MC / GT / JEI ⇒ 可离线跑断言）。
 *
 * <h2>1. 它修的是哪一个缺口（现场读数，逐字）</h2>
 * <pre>
 *   16:43:39.903 [SHANHAI-JEIPATCH] jei_runtime_hide_skipped id=shanhai:primordial_causal_weaving/new_recipe_4 reason=cannot_rebuild
 *   16:43:39.904 [SHANHAI-JEIPATCH] jei_runtime_hide_skipped id=shanhai:matter_flow_condensation/new_recipe_4     reason=cannot_rebuild
 * </pre>
 * 这两条的补丁是<b>开机重放那一拍</b>发的：{@code ShanhaiJeiBridge} 的重放路只送 {@code conditionsJson}，
 * {@code inputs/outputs/recipeBytes} 全是 {@code null} ⇒ 客户端 {@code buildRuntimeObject} 见
 * {@code recipeBytes == null} 直接返回 {@code null} ⇒ 走 {@code cannot_rebuild}（不藏不加）⇒
 * 替换完全由查询出口那层做，而那一层是"<b>拿 JEI 手里那条原件、只换条件</b>"的写法
 * ⇒ <b>JEI 上输入/输出仍是覆盖层改之前那一份</b>。
 * 用户照 JEI 抄的样板 = 旧输入 ⇒ 机器按新配方要料 ⇒ 永远匹配不到。
 *
 * <h2>2. 判据：哪几条"确实需要替换显示"（= 必须送正文）</h2>
 * 只看<b>覆盖层账本</b>里这一条对配方做了什么（{@code config/shanhai/recipe_overrides.json}）：
 * <table border="1">
 *   <caption>三条判据</caption>
 *   <tr><th>覆盖层</th><th>要不要正文</th><th>为什么</th></tr>
 *   <tr>
 *     <td>{@code op=set}，{@code fields} 里有 {@code inputs} / {@code outputs} /
 *         {@code tickInputs} / {@code data}</td>
 *     <td><b>要</b>（{@code io_changed}）</td>
 *     <td>客户端那条原件是"覆盖层改之前"的配方；只贴条件贴不出新输入/新产物/新电压 ⇒
 *         必须把服务端<b>活着的那一条</b>整编过去</td>
 *   </tr>
 *   <tr>
 *     <td>{@code op=add}（编辑器新建的整条配方）</td>
 *     <td><b>要</b>（{@code new_recipe}）</td>
 *     <td>同族：JEI 里那条是<b>底本</b>（KJS 脚本那一份 / 客户端配方表那一份），
 *         不是覆盖层落下去的那一条</td>
 *   </tr>
 *   <tr>
 *     <td>{@code op=set} 但只动 {@code conditions}（其余字段都没有）</td>
 *     <td><b>不要</b>（{@code conditions_only}）</td>
 *     <td>输入/输出本来就没变 ⇒ "只送条件"那条老路<b>显示结果已经是对的</b>，
 *         维持现状（{@code cannot_rebuild} 在这条路上是<b>允许的保守行为</b>）。<br>
 *         ⚠️ 不送正文 ⇒ 不加/不藏 ⇒ 也不会有"两份"</td>
 *   </tr>
 * </table>
 *
 * <h3>2.1 {@code duration} 为什么<b>故意不在</b>判据里</h3>
 * 用户 2026-11-01 拍板：「只修 1，2 是正常现象」 —— 2 = 耗时被 {@code duration_scale}
 * 压成 0.05 秒（{@code [SHANHAI-EDIT] editor duration_scale … k=0.001000}）。
 * 那是<b>刻意的</b>，JEI 那边显示的也是同一个 live 值（实机 {@code jei_patch_synth … dur=1}），
 * 所以"耗时看起来和编辑器里的 200 不一样"不是缺陷。<b>本类一个字都不碰 duration。</b>
 *
 * <h2>3. {@code reason=cannot_rebuild why=…} 的词表（唯一产出点）</h2>
 * <pre>
 *   why=no_bytes                     包里没有正文（= 只送条件那一支 / 老客户端老服务端）
 *   why=unknown_type                 有正文，但不知道它属于哪个 JEI 分类（类型是 unknown/空/解不出）
 *   why=serialize_failed:&lt;异常类&gt;    有正文，但重建这一步抛了（客户端是解码那半边抛）
 *   why=no_live_recipe               同上，但发生在<b>服务端</b>：活表里按 id 找不到这条 ⇒ 编不出正文
 *   why=bad_recipe_id / no_overlay_entry / entry_removed   同上，服务端侧的其它两种"编不出"
 * </pre>
 * ⇒ 日志不再是"只有一个原因名"，而是<b>能直接定位到哪一段</b>（发的那半 / 收的那半 / 分类不认识）。
 *
 * <h2>4. 为什么单独一个类</h2>
 * 这个判据在<b>两侧都要用到</b>（服务端决定发不发正文，客户端决定补丁更不更代），
 * 而两侧都跑不到一起；把它抽成纯 Java 之后，才能在离线驱动里把 A/B/C + 负对照一次跑完
 * （见 {@code temp\jei-replay-body\src\ShanhaiJeiReplayBodySelfTest.java}）。
 */
public final class ShanhaiJeiReplayBodyPlan {

    /** 覆盖层账本里那一条"动作 + 改过哪些字段"的最小视图（由调用方从 JSON 里读出来，本类不碰 Gson 之外的东西）。 */
    public record Overlay(String op, java.util.Set<String> fieldKeys) {

        /** 账本里根本没这条 id。 */
        public static final Overlay NONE = new Overlay(null, java.util.Set.of());

        public boolean isSet() {
            return "set".equals(op);
        }

        public boolean isAdd() {
            return "add".equals(op);
        }

        public boolean touches(String key) {
            return fieldKeys != null && fieldKeys.contains(key);
        }
    }

    /** 判据结论。 */
    public enum Body {
        /** 不需要正文（只送条件那一支）。 */
        NONE,
        /** 覆盖层改过输入/输出/电压档 ⇒ 要正文。 */
        IO_CHANGED,
        /** 覆盖层 {@code op=add} 整条新建 ⇒ 要正文。 */
        NEW_RECIPE
    }

    /** 一条条目的判据 + 可 grep 的理由串。 */
    public record Plan(Body body, String reason) {

        public boolean needsBody() {
            return body != null && body != Body.NONE;
        }
    }

    /** 判据认的"要正文"字段（改动其中任何一个 ⇒ 客户端照原件贴不出来）。⚠️ 没有 duration，理由见 §2.1。 */
    public static final java.util.List<String> BODY_FIELDS = java.util.List.of("inputs", "outputs", "tickInputs", "data");

    /** 客户端 {@code Patch.signature()} 用的分隔符（选一个不会出现在 id/JSON 里的字符）。 */
    private static final char SEP = '\u0001';

    private ShanhaiJeiReplayBodyPlan() {}

    /**
     * <b>这一条重放补丁要不要带完整配方正文</b>（服务端发之前问一次）。
     *
     * @param recipeId 完整 recipe id（只用于日志/判据的可读性；本方法不看内容）
     * @param overlay  覆盖层账本里这一条（{@link Overlay#NONE} = 账本里没有）
     */
    public static Plan of(String recipeId, Overlay overlay) {
        if (overlay == null || overlay.op() == null || overlay.op().isEmpty()) {
            // 账本里没有这条：判不出"改过什么" ⇒ 保守（只送条件）。不可能静默：调用方会把它打进日志。
            return new Plan(Body.NONE, "no_overlay_entry");
        }
        if ("remove".equals(overlay.op()) || "disable".equals(overlay.op())) {
            // 删/禁用走的是另一条路（ACTION_REMOVED）；这条路上"送正文"没有意义。
            return new Plan(Body.NONE, "entry_removed");
        }
        if (overlay.isAdd()) {
            return new Plan(Body.NEW_RECIPE, "new_recipe");
        }
        if (!overlay.isSet()) {
            return new Plan(Body.NONE, "op_" + overlay.op() + "_not_supported");
        }
        final StringBuilder hits = new StringBuilder();
        for (String k : BODY_FIELDS) {
            if (overlay.touches(k)) {
                if (hits.length() > 0) {
                    hits.append(',');
                }
                hits.append(k);
            }
        }
        if (hits.length() > 0) {
            return new Plan(Body.IO_CHANGED, "io_changed:" + hits);
        }
        return new Plan(Body.NONE, "conditions_only");
    }

    /**
     * {@code reason=cannot_rebuild why=…} 的<b>唯一</b>产出点（词表见类注释 §3）。
     *
     * @param bytesPresent  包里到底有没有正文
     * @param typeId        这条补丁记的类型（JEI 分类 uid）
     * @param failureClass  重建那一步抛的异常类简单名（没抛 = null）
     */
    public static String whyFor(boolean bytesPresent, String typeId, String failureClass) {
        if (!bytesPresent) {
            return "no_bytes";
        }
        if (typeId == null || typeId.isEmpty() || "unknown".equals(typeId)) {
            return "unknown_type";
        }
        if (failureClass != null && !failureClass.isEmpty()) {
            return "serialize_failed:" + failureClass;
        }
        return "unknown_type";
    }

    /**
     * 一条补丁的<b>内容指纹</b>（客户端用来判"这条消息和手里那份是不是逐字相同"）。
     *
     * <p>为什么必须逐字而不是"差不多"：只要差一个字节（例如条件里换了个档位、字节里换了个数量），
     * 就必须换代并重贴，否则 JEI 会停在上一版 —— 那正是本类要修的病的形态。
     * <p>为什么不做成 32 位哈希：碰撞会<b>静默少刷新一次</b>，代价比多存几个字符串大得多。
     */
    public static String signature(String recipeId, String typeId, boolean removed,
                                   String inputsJson, String outputsJson, String conditionsJson,
                                   boolean hasDuration, int duration, boolean hasEut, long eut,
                                   String vanillaJson, byte[] recipeBytes) {
        final StringBuilder sb = new StringBuilder(320 + (recipeBytes == null ? 0 : recipeBytes.length * 2));
        sb.append(recipeId).append(SEP).append(typeId).append(SEP).append(removed ? 'R' : 'C')
                .append(SEP).append(hasDuration ? Integer.toString(duration) : "-")
                .append(SEP).append(hasEut ? Long.toString(eut) : "-")
                .append(SEP).append(inputsJson).append(SEP).append(outputsJson)
                .append(SEP).append(conditionsJson).append(SEP).append(vanillaJson)
                .append(SEP);
        if (recipeBytes != null) {
            for (byte b : recipeBytes) {
                final int v = b & 0xFF;
                sb.append(Character.forDigit(v >>> 4, 16)).append(Character.forDigit(v & 0xF, 16));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 离线判据

    /**
     * 纯核自检（离线驱动调；<b>覆盖层/客户端那两侧的真实流程</b>由驱动自己按本类结论跑）。
     *
     * @return {@code {通过数, 总数, 失败明细}}
     */
    public static Object[] selfCheck() {
        int pass = 0;
        int total = 0;
        final StringBuilder bad = new StringBuilder();

        // ① 只改条件 ⇒ 不要正文（= 现状，A 组）
        total++;
        final Plan a = of("shanhai:t/new_recipe_4", new Overlay("set", java.util.Set.of("conditions")));
        if (!a.needsBody() && "conditions_only".equals(a.reason())) {
            pass++;
        } else {
            bad.append(" A(").append(a.body()).append('/').append(a.reason()).append(')');
        }

        // ② 改了 inputs ⇒ 要正文（B 组）
        total++;
        final Plan b = of("shanhai:t/new_recipe_4", new Overlay("set", java.util.Set.of("inputs", "outputs", "conditions")));
        if (b.needsBody() && b.body() == Body.IO_CHANGED && b.reason().contains("inputs")) {
            pass++;
        } else {
            bad.append(" B(").append(b.body()).append('/').append(b.reason()).append(')');
        }

        // ③ op=add ⇒ 要正文（现场第二条 broken 就是这一类）
        total++;
        final Plan c = of("shanhai:t/new_recipe_4", new Overlay("add", java.util.Set.of()));
        if (c.needsBody() && c.body() == Body.NEW_RECIPE) {
            pass++;
        } else {
            bad.append(" C(").append(c.body()).append(')');
        }

        // ④ 只改 duration ⇒ 不要正文（§2.1：那是正常现象，一个字都不许动）
        total++;
        final Plan d = of("shanhai:t/new_recipe_4", new Overlay("set", java.util.Set.of("duration")));
        if (!d.needsBody()) {
            pass++;
        } else {
            bad.append(" D(").append(d.body()).append(')');
        }

        // ⑤ 负对照：账本里没有 / entry 被删 ⇒ 一律不要正文（保守），且理由可读
        total++;
        final Plan e = of("shanhai:t/x", Overlay.NONE);
        final Plan f = of("shanhai:t/x", new Overlay("remove", java.util.Set.of("inputs")));
        if (!e.needsBody() && "no_overlay_entry".equals(e.reason()) && !f.needsBody()
                && "entry_removed".equals(f.reason())) {
            pass++;
        } else {
            bad.append(" E(").append(e.reason()).append('/').append(f.reason()).append(')');
        }

        // ⑥ why= 词表三个词都得出来（含"异常类要带进去"）
        total++;
        final String w1 = whyFor(false, "gtceu:assembler", null);
        final String w2 = whyFor(true, "unknown", null);
        final String w3 = whyFor(true, "gtceu:assembler", "NullPointerException");
        if ("no_bytes".equals(w1) && "unknown_type".equals(w2)
                && "serialize_failed:NullPointerException".equals(w3)) {
            pass++;
        } else {
            bad.append(" F(").append(w1).append('/').append(w2).append('/').append(w3).append(')');
        }

        // ⑦ 指纹：内容相同 ⇒ 相同；改一个字节 / 一个数量 ⇒ 必须不同（否则 JEI 会停在上一版）
        total++;
        final byte[] x = {1, 2, 3};
        final byte[] y = {1, 2, 4};
        final String s1 = signature("id", "gtceu:assembler", false, "{\"item\":[]}", "{}", "[]",
                true, 20, true, 30L, null, x);
        final String s2 = signature("id", "gtceu:assembler", false, "{\"item\":[]}", "{}", "[]",
                true, 20, true, 30L, null, x);
        final String s3 = signature("id", "gtceu:assembler", false, "{\"item\":[]}", "{}", "[]",
                true, 20, true, 30L, null, y);
        final String s4 = signature("id", "gtceu:assembler", false, "{\"item\":[{\"content\":1}]}", "{}", "[]",
                true, 20, true, 30L, null, x);
        if (s1.equals(s2) && !s1.equals(s3) && !s1.equals(s4)) {
            pass++;
        } else {
            bad.append(" G(same=").append(s1.equals(s2)).append(" bytes_diff=").append(!s1.equals(s3))
                    .append(" io_diff=").append(!s1.equals(s4)).append(')');
        }

        return new Object[]{pass, total, bad.toString()};
    }
}
