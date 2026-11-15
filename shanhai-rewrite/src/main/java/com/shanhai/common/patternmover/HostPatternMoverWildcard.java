package com.shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>样板读出来时的配方类型自动识别</b>（纯核，不 import 任何 MC/GT 类 ⇒ 裸 JVM 可跑断言）。
 *
 * <h2>一、用户原话（2026-11-01 第三刀）定的口径，逐条落在这里</h2>
 * <pre>
 *   通配符样板读出来时若自身没有类型：
 *     · 老主机（原始终焉引擎一脉）⇒ 用【它所属模块】的配方类型
 *     · 新主机（归墟视界枢核）    ⇒ 用【星律样板总成】里声明的类型
 *     · 都识别不到                ⇒ 不写类型（type=""），放置时照旧跳过
 * </pre>
 *
 * <h2>一·补、第五刀追加：<b>普通样板</b>没填类型时也补（只有老主机能补）</h2>
 * 用户原话（2026-11 第五刀）：「新增一个功能，若普通样板未填写配方类型，则尝试自动填写
 * （只有老主机上的样板才能这样做）」。
 * <pre>
 *   普通样板（非通配符）＋ 自身没类型：
 *     老主机 ＋ 该模块有效类型【恰好 1 个】 ⇒ 补上那一个（{@link Verdict#RESOLVED_PLAIN}）
 *     老主机 ＋ 【≥2 个】                  ⇒ 不补（{@link Verdict#AMBIGUOUS_PLAIN}，计 stampPlainSkip）
 *     归墟（跨配方种类并行）               ⇒ 一律不补（它本来就有多种类型，补了就是猜）
 * </pre>
 * 「恰好 1 个才补、≥2 个不补」与通配符那条<b>完全同一条判据</b>（就是 {@link #resolve} 里同一段），
 * 区别只是普通样板多一道"来源必须是老主机"的门。
 * <p>🔴 <b>2026-11-06 修正：「推断」与「落盘」是两件事</b>——
 * 上面那张表说的是<b>推断</b>（要不要替一块"没类型"的样板猜一个），只有老主机允许；
 * 而<b>落盘</b>（把<b>已经知道</b>的类型写进样板自己的 NBT，见 {@link #typeToStampPlain}）
 * 与来源主机<b>无关</b>，必须一律做。两者混在一起出过用户实测的 bug（黏贴后样板没有配方类型）。
 *
 * <h2>二、🔴 「多义不猜」是这一类的唯一红线</h2>
 * <pre>
 *   来源给出【恰好 1 个】类型 ⇒ 写进去（放置时的分配依据）
 *   来源给出【≥ 2 个】类型    ⇒ 一个字都不写（type=""），并计入「多义未写」/「未补」
 *   ⇒ 绝不取第一个：取第一个就是"猜"，猜错会把这批样板喂给错的配方类型（用户报过的形态）
 * </pre>
 *
 * <h2>三、来源怎么选（优先级，不是并集）</h2>
 * <table border="1">
 *   <tr><th>来源主机</th><th>主来源</th><th>回落</th></tr>
 *   <tr><td>新主机（归墟）</td><td>星律样板总成声明的类型（{@code machineRecipeTypeIds()}）</td>
 *       <td>模块的有效类型集</td></tr>
 *   <tr><td>老主机 / 认不出主机</td><td>模块的有效类型集（{@code getRecipeTypes} 或当前选中的那一个）</td>
 *       <td>星律样板总成声明的类型</td></tr>
 * </table>
 * 为什么是<b>优先级</b>而不是并集：并集会让「模块声明 5 个类型 + 星律只声明 1 个」变成 6 个候选
 * ⇒ 永远多义 ⇒ 新主机上这个功能就没用了。用户的原话是「新主机<b>则</b>识别星律样板总成中的」，
 * 那就是先认准那个来源；那个来源什么都没给出，才去看另一个（「都识别不到则不写」）。
 *
 * <p>⚠️ 传进来的 {@code moduleTypes} <b>必须</b>是「有效类型集」
 * （见 {@link HostPatternMoverRecipes#effective}）：老主机上的模块同一时刻只跑一个类型。
 * 拿「声明的全部类型」当候选会写出机器根本用不上的类型（E 那条实测反馈的同一个病根）。
 */
public final class HostPatternMoverWildcard {

    /** 这块样板是从哪种主机上读出来的。 */
    public enum Origin {
        /** 老主机（原始终焉引擎一脉，不授予跨配方种类并行）。 */
        OLD_HOST,
        /** 新主机（归墟视界枢核，授予跨配方种类并行）。 */
        NEW_HOST,
        /** 认不出（物品里的 fromHost 为空 / 未知值 / 这台机器不是主机）。 */
        UNKNOWN
    }

    /** 一块样板（或一次批量）的判定结果。 */
    public enum Verdict {
        /** 样板自己就带类型 ⇒ 原样保留（不管是不是通配符）。 */
        KEEP,
        /** 不自动识别（不是通配符样板，或普通样板但来源主机不是老主机 / 没给出候选）。 */
        NOT_WILDCARD,
        /** 通配符样板，来源恰好给出 1 个类型 ⇒ 写进去。 */
        RESOLVED,
        /** 通配符样板，来源给出 ≥2 个类型 ⇒ <b>不写</b>（多义未写）。 */
        AMBIGUOUS,
        /** 通配符样板，两个来源都没有给出类型 ⇒ 不写（识别不到）。 */
        NOT_IDENTIFIED,
        /** 第五刀：<b>普通样板</b>没填类型、老主机、模块的有效类型恰好 1 个 ⇒ 补上这一个。 */
        RESOLVED_PLAIN,
        /** 第五刀：<b>普通样板</b>没填类型、老主机，但模块的有效类型 ≥2 个 ⇒ <b>不补</b>（多义不猜）。 */
        AMBIGUOUS_PLAIN
    }

    /**
     * 一次识别的结果。
     *
     * @param verdict    结论
     * @param type       最终写进 {@code type} 字段的字符串（{@code ""} = 未识别 / 多义）
     * @param candidates 来源给出的候选（保序去重；{@code AMBIGUOUS} 时 ≥2 个，只用于日志与面板读数）
     */
    public record Resolution(Verdict verdict, String type, List<String> candidates) {

        public Resolution {
            type = type == null ? "" : type;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static final Resolution NOT_WILDCARD = new Resolution(Verdict.NOT_WILDCARD, "", List.of());
        public static final Resolution NOT_IDENTIFIED = new Resolution(Verdict.NOT_IDENTIFIED, "", List.of());

        /** 多义未写。 */
        public boolean ambiguous() {
            return this.verdict == Verdict.AMBIGUOUS;
        }

        /** 拿到了类型。 */
        public boolean resolved() {
            return this.verdict == Verdict.RESOLVED;
        }

        /** 一行式读数（日志用，格式稳定）。 */
        public String describe() {
            return "verdict=" + this.verdict + " type='" + this.type + "' candidates=" + this.candidates.size();
        }
    }

    /**
     * 一次读取的<b>三项通配符计数</b>（面板与日志都要看得到；「多义未写」是用户点名要的那一条）
     * ＋ 第五刀新增的<b>两项普通样板补类型计数</b>。
     *
     * <p>⚠️ 前三项<b>互斥</b>（一块样板只落进其中一项）；后两项<b>互斥</b>；两组之间也互斥
     * （一块样板要么是通配符、要么是普通样板）。
     *
     * <table border="1">
     *   <tr><th>字段</th><th>含义</th><th>用户口径</th></tr>
     *   <tr><td>{@code resolved}</td><td>通配符样板自动识别后写进类型的块数</td><td>面板「写入」</td></tr>
     *   <tr><td>{@code ambiguous}</td><td>通配符样板多义未写</td><td>面板「多义未写」</td></tr>
     *   <tr><td>{@code unidentified}</td><td>通配符样板两个来源都没给出类型</td><td>面板「识别不到」</td></tr>
     *   <tr><td>{@code stampPlain}</td><td><b>普通样板</b>补上类型的块数（老主机单类型模块）</td>
     *       <td>读取汇总行 {@code stampPlain=}</td></tr>
     *   <tr><td>{@code stampPlainSkip}</td><td><b>普通样板</b>因多义（模块有效类型 ≥2）没补的块数</td>
     *       <td>读取汇总行 {@code stampPlainSkip=}</td></tr>
     * </table>
     */
    public static final class Tally {

        private int resolved;
        private int ambiguous;
        private int unidentified;
        private int stampPlain;
        private int stampPlainSkip;

        /** 记一条（通配符与前四项、普通样板与后两项，各记各的）。 */
        public void add(Resolution resolution) {
            if (resolution == null) {
                return;
            }
            switch (resolution.verdict()) {
                case RESOLVED -> this.resolved++;
                case AMBIGUOUS -> this.ambiguous++;
                case NOT_IDENTIFIED -> this.unidentified++;
                case RESOLVED_PLAIN -> this.stampPlain++;
                case AMBIGUOUS_PLAIN -> this.stampPlainSkip++;
                default -> {
                    // KEEP / NOT_WILDCARD：不是"自动识别"这件事的结果，不计数
                }
            }
        }

        /** 并进另一份计数（读取路径里每台总成各记一份，最后合并）。 */
        public void merge(Tally other) {
            if (other == null) {
                return;
            }
            this.resolved += other.resolved;
            this.ambiguous += other.ambiguous;
            this.unidentified += other.unidentified;
            this.stampPlain += other.stampPlain;
            this.stampPlainSkip += other.stampPlainSkip;
        }

        /** 写进去了几块（通配符）。 */
        public int resolved() {
            return this.resolved;
        }

        /** 多义未写几块（通配符）。 */
        public int ambiguous() {
            return this.ambiguous;
        }

        /** 识别不到、不写几块（通配符）。 */
        public int unidentified() {
            return this.unidentified;
        }

        /** 第五刀：普通样板补上类型几块。 */
        public int stampPlain() {
            return this.stampPlain;
        }

        /** 第五刀：普通样板因多义（有效类型 ≥2）没补几块。 */
        public int stampPlainSkip() {
            return this.stampPlainSkip;
        }

        /** 五项全零（= 这次读取没有遇到"自身没类型"的样板）。 */
        public boolean empty() {
            return this.resolved == 0 && this.ambiguous == 0 && this.unidentified == 0
                    && this.stampPlain == 0 && this.stampPlainSkip == 0;
        }

        /** 一行式读数（日志里就是这几个字段名）。 */
        public String describe() {
            return "resolved=" + this.resolved + " ambiguous=" + this.ambiguous
                    + " unidentified=" + this.unidentified
                    + " stampPlain=" + this.stampPlain + " stampPlainSkip=" + this.stampPlainSkip;
        }
    }

    private HostPatternMoverWildcard() {}

    /**
     * 识别一块样板该写什么类型（<b>唯一的判定点</b>；自检第 17/18 组打的就是这里）。
     *
     * <h2>🔴 第五刀：普通样板也会被"补类型"（用户原话）</h2>
     * 「新增一个功能，若普通样板未填写配方类型，则尝试自动填写（只有老主机上的样板才能这样做）」。
     * <pre>
     *   普通样板（非通配符）＋ 自身没类型：
     *     来源主机 = 老主机（原始终焉引擎一脉、没有跨配方种类并行）
     *         · 该模块的有效类型恰好 1 个 ⇒ 补上那一个（{@link Verdict#RESOLVED_PLAIN}）
     *         · ≥2 个                   ⇒ 不补（{@link Verdict#AMBIGUOUS_PLAIN}，多义不猜）
     *         · 一个都没有              ⇒ 不补（{@link Verdict#NOT_WILDCARD}）
     *     来源主机 = 归墟（跨配方种类并行）⇒ <b>一律不补</b>（它本来就有多种类型，补了就是猜）
     * </pre>
     * ⇒ 普通样板与通配符走的是<b>同一个</b> {@code resolve}：判据一样（恰好 1 个才写、≥2 个不写），
     * 只是"能不能补"多了一道来源主机的门。落点也复用第三刀那套：读的时候记进搬运物品的 {@code type}，
     * 放置写槽时再由 {@link #typeToStampPlain} 判一次、落进样板自己的 NBT。
     *
     * @param wildcard       是不是通配符样板（{@code ShanhaiWildcardCompat#isWildcardPattern}）
     * @param ownType        样板自己带的类型（四来源去重后恰好 1 个才有值，否则 {@code ""}）
     * @param origin         来源主机种类
     * @param moduleTypes    该样板所属模块的<b>有效</b>配方类型集（见类注释 §三）
     * @param assemblerTypes 星律样板总成里声明的类型（{@code machineRecipeTypeIds()}）
     */
    public static Resolution resolve(boolean wildcard, String ownType, Origin origin,
                                     Collection<String> moduleTypes, Collection<String> assemblerTypes) {
        final String own = norm(ownType);
        if (!own.isEmpty()) {
            return new Resolution(Verdict.KEEP, own, List.of(own));
        }
        if (!wildcard) {
            // ★第五刀：普通样板没填类型 ⇒ 只有【老主机】来源才尝试补（归墟一律不补）
            if (origin != Origin.OLD_HOST) {
                return Resolution.NOT_WILDCARD;
            }
            final List<String> plainCandidates = new ArrayList<>(clean(moduleTypes));
            if (plainCandidates.isEmpty()) {
                return Resolution.NOT_WILDCARD; // 一个候选都没有 ⇒ 不补，也不计进"多义"
            }
            if (plainCandidates.size() == 1) {
                return new Resolution(Verdict.RESOLVED_PLAIN, plainCandidates.get(0), plainCandidates);
            }
            // 🔴 多义不猜：≥2 个候选 ⇒ 一个字都不填
            return new Resolution(Verdict.AMBIGUOUS_PLAIN, "", plainCandidates);
        }
        final Set<String> primary = (origin == Origin.NEW_HOST) ? clean(assemblerTypes) : clean(moduleTypes);
        final List<String> candidates = primary.isEmpty()
                ? new ArrayList<>((origin == Origin.NEW_HOST) ? clean(moduleTypes) : clean(assemblerTypes))
                : new ArrayList<>(primary);
        if (candidates.isEmpty()) {
            return Resolution.NOT_IDENTIFIED;
        }
        if (candidates.size() == 1) {
            return new Resolution(Verdict.RESOLVED, candidates.get(0), candidates);
        }
        // 🔴 多义：一个字都不写，也绝不取 candidates.get(0)
        return new Resolution(Verdict.AMBIGUOUS, "", candidates);
    }

    // ================================================================ ④ 放进母槽时"把类型落盘"（第四刀 ②）

    /**
     * ★<b>该不该把「已经识别出的类型」落到这块通配符样板上</b>（<b>唯一的判定点</b>；
     * 自检第 25 组打的就是这里）。
     *
     * <h2>用户原话（2026-11 第四刀）</h2>
     * 「通配符放入星律样板总成不会自动选择要执行的配方类型」。
     * 根因：{@link #resolve} 识别出的类型只写在<b>搬运物品</b>的 {@code type} 字段里（用来分配落槽），
     * 而星律那台机器重建时读的是<b>母样板自己 NBT</b> 上的 {@code gt_shanhai_recipe_type}
     * （{@code ShanhaiWildcardTypeNbt}，与手选那个键同一个）—— 那个键没被写过 ⇒ 机器看到的是「空 = 自动识别」
     * ⇒ 玩家还得在面板里手动选一次。
     *
     * <h2>三条"不写"（宁可不写，也不许写错）</h2>
     * <pre>
     *   ① 不是分区总成（没有母槽/普通槽之分）⇒ 不写
     *   ② 这块不是通配符样板            ⇒ 这里不写（普通样板走 {@link #typeToStampPlain}，第五刀那条）
     *   ③ 类型为空（多义未写 / 识别不到）⇒ 不写（空串=自动识别，照旧由机器自己判；🔴 绝不写空串覆盖）
     * </pre>
     * 槽号还必须落在<b>母槽区间</b> {@code [normalSlots, normalSlots + wildcardSlots)}：
     * 写进普通槽等于给一块普通样板塞了个它不认识的键。
     *
     * @param zoned        目标总成是否分区（星律样板总成 = true）
     * @param normalSlots  普通槽数（{@code zoned} 时有效；&lt;0 = 不知道 ⇒ 不写）
     * @param wildcardSlots 通配符母槽数（同上）
     * @param slot         目标槽号
     * @param wildcard     这块是不是通配符样板
     * @param type         已经识别出的类型（{@code ""} = 多义 / 识别不到）
     * @return 要写进样板 NBT 的类型；{@code ""} = <b>不写</b>（保持现状）
     */
    public static String typeToStamp(boolean zoned, int normalSlots, int wildcardSlots,
                                     int slot, boolean wildcard, String type) {
        return typeToStamp(zoned, normalSlots, wildcardSlots, slot, wildcard, false, type);
    }

    /**
     * 同上，多一个「<b>整台总成的槽全是通配符槽</b>」的口径（{@code wildcardOnly}）。
     *
     * <p>🔴 2026-11-06 补：原来这一支要求 {@code zoned=true}，而<b>超级通配符总成不是分区总成</b>
     * （它的 10 格全是通配符槽，没有普通槽区）⇒ 从别处搬过去的通配符样板会被写进槽、
     * <b>却不带类型</b>（又被"未标注类型不执行"拦死）。这和用户当天报的"黏贴过来没类型"是同一类 bug：
     * <b>「推断」可以受限制，「把已知类型落盘」不行</b>。
     *
     * @param wildcardOnly 这台总成的槽是不是全为通配符槽（超级通配符总成 = true）
     */
    public static String typeToStamp(boolean zoned, int normalSlots, int wildcardSlots,
                                     int slot, boolean wildcard, boolean wildcardOnly, String type) {
        if (!wildcard) {
            return "";
        }
        final String t = norm(type);
        if (t.isEmpty()) {
            return ""; // 多义 / 识别不到：维持现状（机器按"自动识别"处理）
        }
        if (wildcardSlots <= 0) {
            return ""; // 通配符槽数不知道 ⇒ 不猜
        }
        if (zoned) {
            if (normalSlots < 0) {
                return ""; // 分区边界不知道 ⇒ 不猜
            }
            if (slot < normalSlots || slot >= normalSlots + wildcardSlots) {
                return ""; // 不是母槽
            }
            return t;
        }
        if (wildcardOnly) {
            // 整台都是通配符槽 ⇒ 区间就是 [0, wildcardSlots)
            return slot < 0 || slot >= wildcardSlots ? "" : t;
        }
        return "";
    }

    /**
     * ★<b>落盘时该交给 {@link #typeToStamp} 的"槽数"是哪来的</b>（第七刀补的唯一口径；自检 C42f）。
     *
     * <h2>🔴 为什么必须单独有这一句（2026-11-06 实测踩到的坑）</h2>
     * {@code typeToStamp} 里有一条「{@code wildcardSlots <= 0} ⇒ 不猜」的早退，
     * 而<b>非分区总成</b>的 {@code wildcardSlotsOf(...)} 返回的是 {@code -1}：
     * <pre>
     *   HostPatternMoverTargets#wildcardSlotsOf(超级通配符) = -1
     *   ⇒ typeToStamp(false, -1, <b>-1</b>, 3, true, true, A) = ""   ← 整个新口径等于没开
     *   ⇒ 换成容器槽数：typeToStamp(false, -1, <b>10</b>, 3, true, true, A) = A
     * </pre>
     * （两条读数由 {@code temp/pattern-mover/probe/ProdArgsProbe.java} 真跑出来，不是推的。）
     * ⇒ 规则：<b>全通配符槽总成用容器槽数</b>（那台的"通配符槽"就是它的全部槽），
     * 分区总成仍用该区的格数，其它保持 {@code -1}（= 不写）。
     *
     * @param wildcardOnly 这台总成的槽是不是全为通配符槽（超级通配符总成 = true）
     * @param wildcardSlots 分区总成的通配符槽数（{@code wildcardSlotsOf(...)}；非分区 = {@code -1}）
     * @param containerSize 容器槽数（{@code getTerminalPatternInventory().size()}）
     * @return 要传给 {@link #typeToStamp} 的槽数；{@code <= 0} ⇒ 那边会早退、不写
     */
    public static int stampSlotCount(boolean wildcardOnly, int wildcardSlots, int containerSize) {
        if (wildcardOnly) {
            return Math.max(0, containerSize); // 全通配符槽：格数就是区间上界
        }
        return wildcardSlots;
    }

    // ================================================================ ⑤ 放进槽时"把普通样板的类型落盘"（第五刀）

    /**
     * ★<b>该不该把「给普通样板补上的类型」落到这块样板上</b>（<b>唯一的判定点</b>；
     * 自检 C29 打的就是这里）。
     *
     * <h2>用户原话（2026-11 第五刀）</h2>
     * 「新增一个功能，若普通样板未填写配方类型，则尝试自动填写（只有老主机上的样板才能这样做）」。
     * 读的时候补的类型只写在<b>搬运物品</b>的 {@code type} 字段里（用来分配落槽）——
     * 要让它真的对机器生效，还得在<b>写进槽的那一下</b>把类型落进样板自己的 NBT
     * （与第三刀通配符同一个键、同一个落笔处 {@code ShanhaiWildcardTypeNbt.write}）。
     *
     * <h2>四条"不写"（宁可不写，也不许写错）</h2>
     * <pre>
     *   ① 不是普通样板（通配符走 {@link #typeToStamp}）        ⇒ 不写
     *   ② 这块样板<b>自己 NBT 上已经写了类型</b>                 ⇒ 不写（尊重原有的 / 玩家手选的）
     *   ③ 类型为空（多义未补 / 没补）                          ⇒ 不写（🔴 绝不写空串覆盖）
     *   ④ 分区总成上不是普通槽（通配符母槽 / 展开槽）          ⇒ 不写
     * </pre>
     * 🔴 <b>2026-11-06 修正</b>：这里原来还有一条「② 来源主机不是老主机（归墟）⇒ 不写」，
     * 已删除 —— 那条把「推断该不该猜一个类型」（只老主机允许，在 {@link #resolve} 里管）
     * 和「把已知类型落进样板 NBT」混为一谈，结果是**归墟/多类型模块上搬过去的普通样板全是无类型**，
     * 被星律的"未标注类型不执行"闸门拦死（用户实测：黏贴来的样板没有配方类型却进了机器）。
     *
     * @param zoned      目标总成是否分区（星律样板总成 = true）
     * @param normalSlots 普通槽数（{@code zoned} 时有效；&lt;0 = 不知道 ⇒ 不写）
     * @param slot       目标槽号
     * @param wildcard   这块是不是通配符样板
     * @param ownHasType 样板自己 NBT 上是否已经写了类型（{@code ShanhaiPatternTypeReader#resolve} 认得出）
     * @param oldHost    目标总成的宿主是不是老主机（= <b>不</b>授予跨配方种类并行）
     * @param type       读的时候补出来的类型（{@code ""} = 多义 / 没补）
     * @return 要写进样板 NBT 的类型；{@code ""} = <b>不写</b>（保持现状）
     */
    public static String typeToStampPlain(boolean zoned, int normalSlots, int slot,
                                          boolean wildcard, boolean ownHasType, boolean oldHost,
                                          String type) {
        if (wildcard) {
            return ""; // ① 通配符有它自己那套判据（母槽区间）
        }
        // 🔴 2026-11-06 修正（用户实测：黏贴过来的样板"没有配方类型"却还是进了机器）：
        //   这里原来还有一条 `if (!oldHost) return "";` —— 「② 归墟一律不补」。
        //   那条把【推断】和【落盘】两件事混在了一起：
        //     · 推断（要不要替它猜一个类型）⇒ 只有老主机才允许，这条限制在 resolve() 里已经生效；
        //     · 落盘（把已经知道的类型写进这块样板自己的 NBT）⇒ 必须无条件做，
        //       否则机器读到的是"无类型" ⇒ 星律的"未标注类型不执行"闸门直接把它拦死。
        //   所以 oldHost 参数保留（调用方签名不变、自检仍传它），但**不再参与本判据**。
        if (ownHasType) {
            return ""; // ② 自己已经写了类型 ⇒ 不覆盖
        }
        final String t = norm(type);
        if (t.isEmpty()) {
            return ""; // ③ 多义 / 没补：维持现状
        }
        if (zoned && (normalSlots < 0 || slot < 0 || slot >= normalSlots)) {
            return ""; // ④ 不是普通槽
        }
        return slot < 0 ? "" : t;
    }

    /** 一条批量输入（自检与读取路径共用；纯数据）。 */
    public record Item(boolean wildcard, String ownType, Origin origin,
                       List<String> moduleTypes, List<String> assemblerTypes) {

        public Item {
            moduleTypes = moduleTypes == null ? List.of() : List.copyOf(moduleTypes);
            assemblerTypes = assemblerTypes == null ? List.of() : List.copyOf(assemblerTypes);
        }

        /** 造一条「通配符、自身没类型」的（读取时最常遇到的那种）。 */
        public static Item wildcard(Origin origin, Collection<String> moduleTypes,
                                    Collection<String> assemblerTypes) {
            return new Item(true, "", origin,
                    moduleTypes == null ? List.of() : List.copyOf(moduleTypes),
                    assemblerTypes == null ? List.of() : List.copyOf(assemblerTypes));
        }
    }

    /** 一批识别的结果（{@code types} 与 {@code items} 等长同序）。 */
    public record Batch(List<Resolution> resolutions, Tally tally) {

        public Batch {
            resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
            tally = tally == null ? new Tally() : tally;
        }

        public int size() {
            return this.resolutions.size();
        }

        public Resolution at(int index) {
            return index < 0 || index >= this.resolutions.size() ? Resolution.NOT_WILDCARD
                    : this.resolutions.get(index);
        }

        /** 第 {@code index} 块最终该写的类型。 */
        public String typeAt(int index) {
            return at(index).type();
        }

        public String describe() {
            return "n=" + size() + " " + this.tally.describe();
        }
    }

    /** 批量识别（读取路径与自检走同一条）。 */
    public static Batch resolveAll(List<Item> items) {
        final List<Resolution> out = new ArrayList<>();
        final Tally tally = new Tally();
        if (items != null) {
            for (Item item : items) {
                final Resolution r = item == null
                        ? Resolution.NOT_WILDCARD
                        : resolve(item.wildcard(), item.ownType(), item.origin(),
                        item.moduleTypes(), item.assemblerTypes());
                out.add(r);
                tally.add(r);
            }
        }
        return new Batch(out, tally);
    }

    // ------------------------------------------------------------------ 工具

    /** 去掉空白与空串，保序去重（linked：日志与写进去的值都可复现）。 */
    static Set<String> clean(Collection<String> values) {
        final Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                final String s = norm(v);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static String norm(String value) {
        return value == null ? "" : value.trim();
    }
}
