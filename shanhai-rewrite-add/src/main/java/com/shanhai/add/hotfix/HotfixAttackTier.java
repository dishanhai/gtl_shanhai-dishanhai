package com.shanhai.add.hotfix;

/**
 * <b>两级攻击手段</b>的唯一真源（纯 JDK，离线可断言）。
 *
 * <h2>用户口径（2026-11-20 拍板）</h2>
 * <pre>
 *   「废整个方法也要保留，只不过作为更强力的攻击，默认使用废那一行作为普通攻击手段」
 * </pre>
 * <table border="1">
 *   <tr><th>档</th><th>做什么</th><th>副作用</th><th>什么时候用</th></tr>
 *   <tr><td>{@link #LINE}（<b>默认／普通攻击</b>）</td>
 *       <td>只把方法里"写受保护状态的那一条指令"换成我们自己的静态调用
 *           （{@link HotfixLinePatch} ⇒ {@link HotfixShield}）；<b>其余逻辑照跑</b></td>
 *       <td>极小：那个方法里除了这一笔写血，别的事一样会做</td>
 *       <td>能改就默认用它</td></tr>
 *   <tr><td>{@link #METHOD}（<b>强力攻击</b>）</td>
 *       <td>整方法体换成"安全默认返回"（{@link HotfixMethodBodyPatch}）</td>
 *       <td>大：这个方法原本干的所有事一起停摆</td>
 *       <td>line 做不到／被绕过后自动升级</td></tr>
 * </table>
 *
 * <h2>升级判据（任一命中就升级到 {@link #METHOD}；见 {@link #shouldEscalate(String, int, String)}）</h2>
 * <ol>
 *   <li><b>line 改写在字节码里找不到目标指令</b>（{@code reason=line_no_site}）；
 *       同类还有 {@code line_unresolvable}／{@code method_ambiguous_name}／{@code forbidden_special_method}
 *       —— 它们都意味着"普通攻击在这一枪上用不出来"；</li>
 *   <li><b>连续 {@value #ESCALATE_HITS} 次</b>命中同一个 target（= 同一个方法又被摆到了违规栈上），
 *       说明废了那一行之后它还在作恶；</li>
 *   <li><b>line 改写之后我们仍在挨打</b>：由探针／存活哨兵那条线判出
 *       {@code patch_probe result=ineffective}（行为侧证据），见 {@code HotfixAutoCounter#actOn}。</li>
 * </ol>
 * 🔴 <b>只升不降</b>：一旦升到 {@link #METHOD} 就留在那一档 —— 降回 line 等于把"已经证明能被绕过"的
 * 攻击手段再试一遍。（要回退只有 {@code undo}/{@code undoall} 两条显式路径。）
 */
public final class HotfixAttackTier {

    /** 普通攻击：废掉那一行。 */
    public static final String LINE = "line";

    /** 强力攻击：整方法 RETURN。 */
    public static final String METHOD = "method";

    /** 两档（顺序＝"从轻到重"，日志列举用）。 */
    public static final String[] ALL = {LINE, METHOD};

    /** 连续命中同一个 target 多少次之后升级（用户建议 K=3）。 */
    public static final int ESCALATE_HITS = 3;

    /** 这些 {@code detail} 出现 ⇒ 普通攻击这一枪用不出来，直接上强力档。 */
    private static final String[] LINE_IMPOSSIBLE = {
            "line_no_site",
            "line_unresolvable",
            "method_ambiguous_name",
            "method_not_found",
            "method_desc_not_found",
            "forbidden_special_method",
            "no_code",
            "class_reader_failed",
            "bad_class_bytes",
            "empty_method_name"
    };

    private HotfixAttackTier() {
        // 纯静态
    }

    /** 合法的档位名吗。 */
    public static boolean isKnown(String tier) {
        return LINE.equals(tier) || METHOD.equals(tier);
    }

    /** 规范化：不认识的（含 {@code null}／空）一律当默认档 {@link #LINE}。 */
    public static String orDefault(String tier) {
        return isKnown(tier) ? tier : LINE;
    }

    /** 往上升一档（<b>只升不降</b>：任何档升上去都是 {@link #METHOD}）。 */
    public static String stronger(String tier) {
        return METHOD;
    }

    /**
     * "line 做不到"的那一批 reason 吗（出现即升级）。
     *
     * @param reason {@link HotfixLinePatch.Result#reason}
     */
    public static boolean lineImpossible(String reason) {
        if (reason == null || reason.isEmpty()) {
            return false;
        }
        // apply() 会把"主目标没套上"包成 patch_<reason>（见 HotfixAutoCounter#apply 的 ④ 段），
        // 这里把那一层前缀剥掉，两条形态都能认。
        String r = reason;
        if (r.startsWith("patch_")) {
            r = r.substring("patch_".length());
        }
        for (int i = 0; i < LINE_IMPOSSIBLE.length; i++) {
            if (LINE_IMPOSSIBLE[i].equals(r) || r.startsWith(LINE_IMPOSSIBLE[i] + ":")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该不该升级到强力档。
     *
     * @param currentTier 现在的档（{@code null} = 还没改过）
     * @param hits        这个 target 又被打到第几次（从 1 开始）
     * @param reason      line 那一步的失败 reason（成功给空串/{@code null}）
     * @return {@code true} = 这一次要用（或换成）强力档
     */
    public static boolean shouldEscalate(String currentTier, int hits, String reason) {
        if (METHOD.equals(currentTier)) {
            return false;          // 已经是最强档，没什么可升的
        }
        if (lineImpossible(reason)) {
            return true;
        }
        return hits >= ESCALATE_HITS;
    }
}
