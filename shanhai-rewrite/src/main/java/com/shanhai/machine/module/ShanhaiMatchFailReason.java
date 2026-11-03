package com.shanhai.machine.module;

/**
 * 「<b>样板/材料对不上</b>」这条原因的<b>判定核</b>（纯 JDK，零 Minecraft/AE 依赖，可离线单跑 {@link #selfTest()}）。
 *
 * <h2>1. 为什么需要它（现场证据 ＋ 用户反馈原话）</h2>
 * 用户 2026-11-01 追加原话（逐字）：
 * <blockquote>
 *   「<b>『匹配不到』这条路现在一条原因都不写</b>」<br>
 *   「用户那台 -88,130,83 长时间 match=null / 全 IDLE」<br>
 *   「⇒ 它卡在 checkRecipe 第一行 RecipeRunnerHelper.matchRecipe（PrimordialModuleRecipeLogic.java:1142）」
 * </blockquote>
 * 那一行<b>原来只有 {@code return false}</b>：机器确实"没跑"，但
 * {@code shanhai$pendingFailReason} 一直是 {@code null} ⇒ 两条显示通道（Jade 的
 * {@code shanhaiIdleReason} 与 gtlcore 的 {@code RecipeResult}）都一个字都写不出来
 * ⇒ 玩家看到的是"机器不动、抬头上一行原因都没有"。
 *
 * <h2>2. 只允许说两句话（用户点单的口径，必须如实区分）</h2>
 * <pre>
 *   样板总成里一块样板都没有          ⇒ {@link #LANG_NO_PATTERN}「没有样板（样板总成是空的）」
 *   有样板但这一轮没匹配上            ⇒ {@link #LANG_NO_MATCH}「样板/材料对不上（可能是样板是旧版，或输入料不齐）」
 *   判不出来（摸不到样板总成 / 读异常） ⇒ {@code null} ⇒ <b>一个字都不写</b>
 * </pre>
 *
 * <h2>3. 🔴 为什么"判不出来"必须是 {@code null} 而不是「未知原因」</h2>
 * 本工程红线：<b>宁可缺，不可假</b>。写一句"未知原因"等于在活界面上放一条没有信息量的假数据，
 * 玩家会以为系统已经知道了只是不肯说 —— 比什么都不写更糟。
 * 所以 {@link #langKey} 对 {@code containers <= 0}（连 {@code ICraftingProvider} 仓室都摸不到）返回 {@code null}，
 * 这与改动前的行为<b>逐字一致</b>（那时永远不写）。
 *
 * <h2>4. 判据的唯一来源（不是推测）</h2>
 * <ul>
 *   <li>{@code containers} = 本结构里 {@code instanceof appeng.api.networking.crafting.ICraftingProvider}
 *       的仓室个数 —— 样板总成就是靠这个接口把样板交出去的
 *       （{@code javap}：{@code org.gtlcore...MEPatternBufferPartMachineBase implements ICraftingProvider}；
 *       我们的 {@code StellarSampleAssemblerPartMachine} / {@code SuperWildcardPatternBufferPartMachine} 同）；</li>
 *   <li>{@code totalPatterns} = 这些仓室 {@code getAvailablePatterns().size()} 之和
 *       （只读缓存字段；星律那边是 {@code StellarSampleAssemblerPartMachine:4852} 的缓存 getter）。</li>
 * </ul>
 * ⇒ 两个数都来自<b>仓室自己的公开只读 API</b>，本类只做"该说哪句话"的判断。
 */
public final class ShanhaiMatchFailReason {

    /** 样板总成是空的（一块样板都没有）。 */
    public static final String LANG_NO_PATTERN = "shanhai.recipe.fail.no_pattern";

    /** 有样板，但这一轮没匹配上（旧版样板 / 输入料不齐都可能）。 */
    public static final String LANG_NO_MATCH = "shanhai.recipe.fail.pattern_no_match";

    private ShanhaiMatchFailReason() {}

    /**
     * {@code matchRecipe} 失败时该说的话。
     *
     * @param patternContainers 本结构里 {@code ICraftingProvider} 仓室个数；{@code <= 0} = 摸不到（判不出）
     * @param totalPatterns     这些仓室的样板总数（{@code >= 0}；{@code < 0} = 读失败）
     * @return lang 键，或 {@code null} = <b>判不出 ⇒ 一个字都不许写</b>
     */
    public static String langKey(int patternContainers, int totalPatterns) {
        if (patternContainers <= 0) {
            return null;            // 连样板总成在哪都不知道 ⇒ 不许猜"空的"
        }
        if (totalPatterns < 0) {
            return null;            // 读样板数失败 ⇒ 同样不许猜
        }
        return totalPatterns == 0 ? LANG_NO_PATTERN : LANG_NO_MATCH;
    }

    /** 这条原因是不是"最高优先级不许被覆盖"的那种 —— 本通道<b>永远是最低优先级</b>。 */
    public static boolean isLowestPriority() {
        return true;
    }

    // ═══════════════════════════════ 离线自检 ═══════════════════════════════

    /**
     * 🔴 自检（本项目纪律「检查器先证明自己对」的落点）。
     *
     * <p>覆盖 4 组：① 空样板 ⇒ A；② 有样板但 0 命中 ⇒ B；③ 判不出（摸不到仓室 / 读失败）⇒ {@code null}
     * —— <b>这一条就是"不许写未知原因"的红线</b>；④【负对照】把"判不出也写"打开必须让断言变红
     * —— 由离线脚本做变异体（把 {@code containers <= 0} 那条 return 改成返回 A），
     * 本方法只负责让那条断言真的失败。
     *
     * @return 一行机器可判的读数（{@code PASS=true}）
     * @throws IllegalStateException 任一条不成立
     */
    public static String selfTest() {
        int checks = 0;

        // ① 样板总成是空的
        require(LANG_NO_PATTERN.equals(langKey(1, 0)), "① 有样板总成、0 块样板 ⇒ 必须说「没有样板」");
        require(LANG_NO_PATTERN.equals(langKey(3, 0)), "① 多台样板总成、合计 0 块 ⇒ 同样是「没有样板」");
        checks += 2;

        // ② 有样板但没匹配上
        require(LANG_NO_MATCH.equals(langKey(1, 1)), "② 1 块样板没匹配上 ⇒ 必须说「样板/材料对不上」");
        require(LANG_NO_MATCH.equals(langKey(2, 1080)), "② 大量样板没匹配上 ⇒ 同一条");
        checks += 2;

        // ③ 判不出 ⇒ 一个字都不许写（红线：不许写「未知原因」）
        require(langKey(0, 0) == null, "③ 摸不到样板总成 ⇒ 必须 null（不许写成「空的」）");
        require(langKey(-1, 5) == null, "③ 仓室数非法 ⇒ 必须 null");
        require(langKey(1, -1) == null, "③ 样板数读失败 ⇒ 必须 null");
        checks += 3;

        // ④ 两句话不是同一句（否则区分没意义）
        require(!LANG_NO_PATTERN.equals(LANG_NO_MATCH), "④ A/B 两条必须是不同的键");
        require(isLowestPriority(), "④ 本通道必须自认最低优先级（谁都能覆盖它）");
        checks += 2;

        return "[SHANHAI-MATCH-REASON] 自检通过（" + checks + " 条断言，纯 JDK、可离线单跑）："
                + "① 样板总成空 ⇒ " + LANG_NO_PATTERN + "；② 有样板没对上 ⇒ " + LANG_NO_MATCH
                + "；③ 判不出 ⇒ null（一个字都不写）；④ 两条不同键、且本通道是最低优先级。";
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("[SHANHAI-MATCH-REASON] 自检失败：" + what
                    + " ⇒ 这条原因本身不可信，它在真实机器上说的话没有信息量。");
        }
    }
}
