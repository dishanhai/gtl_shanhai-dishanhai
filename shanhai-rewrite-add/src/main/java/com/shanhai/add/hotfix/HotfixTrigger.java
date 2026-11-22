package com.shanhai.add.hotfix;

import java.util.Locale;

/**
 * <b>四类触发源</b>的唯一真源（纯 JDK，离线可断言）。
 *
 * <h2>用户口径（2026-11-20 收敛）</h2>
 * <pre>
 *   把触发源收敛成四类「对手正在改我们玩家」的事件，每类都能把栈喂给 auto_counter：
 *     hurt    伤害事件（有人在对我们的玩家造成伤害）
 *     health  写血量（setHealth ／ 直接写 DATA_HEALTH_ID 同步字段）
 *     die     判死（LivingEntity#die ／ ServerPlayer#die）
 *     remove  摘人（Entity#setRemoved）
 *   ⇒ 四类同一套入队与门槛，日志 why= 分别记为 hurt|health|die|remove
 * </pre>
 *
 * <h2>为什么要有这个类（而不是各写各的字符串）</h2>
 * {@code why=} 是玩家/日志判读的<b>唯一分类字段</b>。它只要有一处拼成 {@code attack}、
 * 另一处拼成 {@code hurt}，同一条链的读数就会被拆成两栏（"为什么 hurt 是 0" 会被误读成
 * "伤害事件没抓到"）。所以把"旧名 → 规范名"的映射<b>集中在这里</b>，别处一律用常量。
 *
 * <h2>旧名怎么处理（不丢证据）</h2>
 * <pre>
 *   attack        ⇒ hurt        （2026-11-20 之前"伤害事件被取消的那一拍"用的名字）
 *   health_write  ⇒ health      （规格里的写法）
 *   motion / motion_brake / pos  ⇒ 原样保留
 * </pre>
 * 后三个是<b>附加证据源</b>：它们同样走"同一套入队与门槛"（{@code HotfixTriageTap#submitViolation}），
 * 只是不属于那四类"改玩家"的核心事件 —— 把它们硬塞进四类会丢掉"这一条是动量还是血量"这个信息。
 * 所以规范化的口径是：<b>四类归到自己名下，其余原样透传</b>。
 */
public final class HotfixTrigger {

    /** 伤害事件（有人在打我们的玩家）。 */
    public static final String HURT = "hurt";

    /** 写血量（{@code setHealth} ／ {@code SynchedEntityData.set(DATA_HEALTH_ID, …)}）。 */
    public static final String HEALTH = "health";

    /** 判死。 */
    public static final String DIE = "die";

    /** 摘人（{@code setRemoved}）。 */
    public static final String REMOVE = "remove";

    /** 四类规范名（顺序＝日志里列举的顺序，别乱改）。 */
    public static final String[] CANONICAL = {HURT, HEALTH, DIE, REMOVE};

    /**
     * 🆕 2026-11-20：<b>只在证据侧使用、不参与选靶</b>的触发源。
     *
     * <p>用户口径（逐字）：「{@code motion} 降级为"证据，不参与选靶"」，并且
     * 「不要因此把 {@code motion} 的取证功能删掉」。
     *
     * <p>为什么是这两个而不是一个：
     * <ul>
     *   <li>{@code motion}＝{@code setDeltaMovement} 被外部写（一条<b>动量</b>证据）；</li>
     *   <li>{@code motion_brake}＝写入口把这一次动量<b>丢弃</b>（被取消的写入不会走到 RETURN
     *       那条路，所以它必须单独记一条）。</li>
     * </ul>
     * 两者都是"动量触发源"的不同落点。⚠️ {@code pos}（位置被写）<b>故意不在此列</b>：
     * 位置写是另一个攻击面（瞬移/传送），用户这轮只点名 {@code motion}；若要一起降级，
     * 往这个数组里加一项即可（一行改动）。
     */
    public static final String[] EVIDENCE_ONLY = {"motion", "motion_brake"};

    /**
     * 🆕 2026-11-20：<b>后果事件</b>（＝"因果的果"）。
     *
     * <p>判死与摘人是"凶手已经离开调用栈"的那一拍 —— 它们才是 {@code cause_id} 账本的消费者。
     * （{@code health}/{@code hurt} 是"因"，永远走栈上选靶。）
     */
    public static final String[] CONSEQUENCE = {DIE, REMOVE};

    /**
     * 🆕 2026-11-20：<b>写作类事件</b>（＝"因果的因"，能改死）。
     *
     * <p>只有这两类写入值得记进 {@code cause_id} 的 pending 表：把血写成 0/-∞ 或者打掉一大截，
     * 才可能让原版下一拍 {@code isDeadOrDying()} 为真 → {@code die} → {@code remove}。
     */
    public static final String[] CAUSE = {HEALTH, HURT};


    /** 旧名 → 规范名（只收"确实是同一件事"的）。 */
    private static final String[] ALIAS_FROM = {"attack", "attack_cancelled", "health_write", "healthdata", "death"};
    private static final String[] ALIAS_TO = {HURT, HURT, HEALTH, HEALTH, DIE};

    private HotfixTrigger() {
        // 纯静态
    }

    /**
     * 规范化一个 {@code where}/{@code why} 值。
     *
     * @param where 原始值（可为 {@code null}）
     * @return 规范名；{@code null}/空 ⇒ {@code unknown}
     */
    public static String canonical(String where) {
        if (where == null || where.isEmpty()) {
            return "unknown";
        }
        final String low = where.toLowerCase(Locale.ROOT);
        for (int i = 0; i < ALIAS_FROM.length; i++) {
            if (ALIAS_FROM[i].equals(low)) {
                return ALIAS_TO[i];
            }
        }
        return low;
    }

    /** 是不是四类之一（规范化之后判）。 */
    public static boolean isCanonical(String where) {
        final String c = canonical(where);
        for (int i = 0; i < CANONICAL.length; i++) {
            if (CANONICAL[i].equals(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一条触发源<b>值不值得</b>立刻入队（四类全要；附加证据源也全要）。
     *
     * <p>留这个方法是为了让"哪些源必须被抓"这件事<b>可判</b>：将来若有人把某一类删掉，
     * 自检里 {@code requiredFour()} 一断言就红。
     *
     * @return 永远 {@code true}（现在四类都要）；单列一个方法是为了让"要不要"这件事有落点
     */
    public static boolean wanted(String where) {
        return where != null && !where.isEmpty();
    }

    /** 四类是否都在（离线自检用：给一组见过的 where，判四类齐不齐）。 */
    public static int requiredFour() {
        return CANONICAL.length;
    }

    /**
     * 这一条触发源是不是<b>只当证据</b>（不参与选靶、不产生 patch 目标）。
     *
     * <p>⚠️ 它<b>不</b>影响取证：违规照旧以 {@code where=motion} 记一条 {@code violation_stack}。
     *
     * @param where 原始 {@code why}/{@code where}
     */
    public static boolean isEvidenceOnly(String where) {
        final String c = canonical(where);
        for (int i = 0; i < EVIDENCE_ONLY.length; i++) {
            if (EVIDENCE_ONLY[i].equals(c)) {
                return true;
            }
        }
        return false;
    }

    /** 这一条是不是<b>后果事件</b>（{@code die}/{@code remove}）——账本的消费者。 */
    public static boolean isConsequence(String where) {
        final String c = canonical(where);
        for (int i = 0; i < CONSEQUENCE.length; i++) {
            if (CONSEQUENCE[i].equals(c)) {
                return true;
            }
        }
        return false;
    }

    /** 这一条是不是<b>写作类事件</b>（{@code health}/{@code hurt}）——账本的记录者。 */
    public static boolean isCause(String where) {
        final String c = canonical(where);
        for (int i = 0; i < CAUSE.length; i++) {
            if (CAUSE[i].equals(c)) {
                return true;
            }
        }
        return false;
    }

    /** 一行读数：把见过的 {@code where} 归到四类上（命令/日志用）。 */
    public static String tallyLine(java.util.Map<String, ? extends Number> byWhere) {
        final StringBuilder sb = new StringBuilder(96);
        sb.append("trigger4");
        for (int i = 0; i < CANONICAL.length; i++) {
            long sum = 0L;
            if (byWhere != null) {
                for (java.util.Map.Entry<String, ? extends Number> e : byWhere.entrySet()) {
                    if (CANONICAL[i].equals(canonical(e.getKey())) && e.getValue() != null) {
                        sum += e.getValue().longValue();
                    }
                }
            }
            sb.append(' ').append(CANONICAL[i]).append('=').append(sum);
        }
        return sb.toString();
    }
}
