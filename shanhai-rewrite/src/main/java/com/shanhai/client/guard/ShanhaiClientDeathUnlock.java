package com.shanhai.client.guard;

import com.shanhai.common.guard.ShanhaiGuardCore;

/**
 * 🔴 <b>客户端「死亡态解锁」的判据 ＋ 那一行日志</b>（2026-11-08 新增）。
 *
 * <h2>为什么要加这两道门（用户原话，这是全部动因）</h2>
 * <pre>
 *   「因为每当我把猫清除的时候，都会左下角有一句自动把血补满的话」
 *   「我感觉我把猫放出来时，玩家可能已经死了，又或者没有血了，所以才打不开」
 * </pre>
 * 服务端那半边已经在补血（{@code [SHANHAI-GUARD] setHealth_guard value_was=-Infinity -> 20.0}），
 * 但客户端很可能<b>长期停在死亡态</b>：{@code getHealth() <= 0} ／ {@code isDeadOrDying()} ／ {@code deathTime > 0}
 * ⇒ 面板不显示、右键像失效、人僵住。客户端这一路就是来补这一环的。
 *
 * <h2>🔴 本类<b>不写第二份死亡判据</b>（只做「组合 ＋ 新增两道门」）</h2>
 * 三条既有判定全部<b>原样委托</b>给纯逻辑核（{@link ShanhaiGuardCore}，零 Forge / 零 Minecraft），
 * 本类一个字都不重复：
 * <ol>
 *   <li>{@link ShanhaiGuardCore#looksDeadDecision(float, boolean, int, int)} —— 交叉校验表，
 *       它<b>已经</b>包含「血 &lt;= 0」「血 = NaN ⇒ 按死处理」「isDeadOrDying=true」「deathTime &gt; 0」四条
 *       ⇒ <b>「客户端血量为 0」这一环就在这条里，不需要另写</b>；</li>
 *   <li>{@link ShanhaiGuardCore#deathFlowResetDecision(boolean, int, float, boolean)} ——
 *       再加一条更毒的信号：同步血量字段真值 &lt;= 0（{@code getHealth()} 带偏移时只有它可靠）。</li>
 * </ol>
 *
 * <h2>本类唯一的新东西：两道门 ＋ 一个日志行</h2>
 * <ul>
 *   <li>🔴 <b>「受保护」门</b>：没带模块、也没被登记过 ⇒ <b>一个字节都不动</b>（不许阻断正常死亡）；</li>
 *   <li>🔴 <b>「创造 / 旁观」门</b>：玩家真的在创造或旁观 ⇒ <b>不动</b>（这两类本来进不了死亡态，
 *       客户端不去碰它，免得把创造模式的真实行为遮住；血量那条腿仍由服务端负责）；</li>
 *   <li>{@link #logLine} —— {@code [SHANHAI-GUARD] client_death_unlock hp_before=… hp_after=…
 *       dead_before=… dead_after=… death_time=… screen=… via=…}，字段顺序就是核对顺序。</li>
 * </ul>
 *
 * <h2>🔴 为什么本类必须是纯 JDK</h2>
 * 只能 import {@code java.*} ＋ {@link ShanhaiGuardCore}（它自己也是纯 JDK）⇒
 * 判据可以在<b>离线、零 Minecraft、零 Forge</b> 的前提下被 {@code javac}/{@code java} 直接跑
 * （{@code temp\clientdeath\selfcheck\}），不用起游戏就能证明"死亡态要解锁、满血不动、创造不动、
 * NaN 视为死、deathTime&gt;0 但血满也清"。
 *
 * <p>⚠️ 本类在 {@code com.shanhai.client.guard} 包下，但<b>没有任何 Minecraft 引用</b>
 * ⇒ 也满足该包的两条既有自查门（{@code net\.minecraft\.client} 零命中 ／ 常量池无 client Class 条目）。
 */
public final class ShanhaiClientDeathUnlock {

    private ShanhaiClientDeathUnlock() {
    }

    /** 日志令牌（规格逐字：{@code [SHANHAI-GUARD] client_death_unlock …}）。 */
    public static final String TOKEN = "client_death_unlock";

    /** {@code via=} 的取值①：第一条腿 {@code setHealth(max)} 落地。 */
    public static final String VIA_SET_HEALTH = "setHealth";

    /** {@code via=} 的取值②：第二条腿直写同步血量字段 {@code DATA_HEALTH_ID} 落地。 */
    public static final String VIA_DATA = "data";

    /** {@code via=} 的取值③：两条腿都没落地 —— <b>绝不假装成功</b>（规格要求"写失败要记录"）。 */
    public static final String VIA_FAILED = "failed";

    /** {@code screen=} 的取值：这一拍没有屏幕。 */
    public static final String SCREEN_NONE = "none";

    /**
     * 🔴 <b>这一 tick 要不要做「客户端死亡态解锁」</b>（判据本体）。
     *
     * <pre>
     *   开关关                            ⇒ false（一个字节都不动）
     *   不是受保护玩家                    ⇒ false（不许阻断正常死亡）
     *   创造 / 旁观                       ⇒ false（这两类不碰；🔴 这两道门排在死亡判据之前）
     *   looksDead（含 hp &lt;= 0 ／ hp=NaN ／ isDeadOrDying ／ deathTime &gt; 0）⇒ true
     *   deathFlowReset（字段真值 &lt;= 0）                                ⇒ true
     *   其余                              ⇒ false（血满 ＋ 计时器归零 ＋ 字段真值 &gt; 0 ⇒ 不动）
     * </pre>
     *
     * @param hp                  {@code player.getHealth()}（可能被别人加了偏移；NaN = 读不到）
     * @param deadOrDying         {@code player.isDeadOrDying()}
     * @param deathTime           {@code player.deathTime}
     * @param hurtTime            {@code player.hurtTime}（只用来"排除"：受击不算法死）
     * @param fieldHealth         同步血量字段真值 {@code DATA_HEALTH_ID}（NaN = 第二通道读不到）
     * @param guarded             {@code ShanhaiGuardEvents.isGuarded(player)}（带模块 ／ 登记过）
     * @param creativeOrSpectator {@code player.isCreative() || player.isSpectator()}
     * @param enabledFlag         {@link ShanhaiGuardCore#enabled}
     */
    public static ShanhaiGuardCore.Decision decide(float hp, boolean deadOrDying, int deathTime, int hurtTime,
                                                   float fieldHealth, boolean guarded,
                                                   boolean creativeOrSpectator, boolean enabledFlag) {
        if (!enabledFlag) {
            return ShanhaiGuardCore.Decision.of(false, "开关关闭（enabled=false）⇒ 客户端不动死亡态");
        }
        if (!guarded) {
            return ShanhaiGuardCore.Decision.of(false,
                    "不是受保护玩家（没带模块、也没登记过）⇒ 一个字节都不动（不许阻断正常死亡）");
        }
        if (creativeOrSpectator) {
            return ShanhaiGuardCore.Decision.of(false,
                    "玩家在创造 / 旁观 ⇒ 不动（这两类不碰；血量那条腿归服务端，客户端不遮真实行为）");
        }
        // ① 交叉校验表（含「血 <= 0」「血 = NaN ⇒ 按死处理」「isDeadOrDying」「deathTime > 0」）
        final ShanhaiGuardCore.Decision looks = ShanhaiGuardCore.looksDeadDecision(hp, deadOrDying, deathTime, hurtTime);
        if (looks.yes) {
            return ShanhaiGuardCore.Decision.of(true, "客户端判死｜" + looks.reason);
        }
        // ② 更毒的一条（字段真值 <= 0）—— 与既有客户端每 tick 那条判据逐字同一份
        final ShanhaiGuardCore.Decision flow = ShanhaiGuardCore.deathFlowResetDecision(
                deadOrDying, deathTime, fieldHealth, enabledFlag);
        if (flow.yes) {
            return ShanhaiGuardCore.Decision.of(true, "客户端判死｜" + flow.reason);
        }
        return ShanhaiGuardCore.Decision.of(false, "客户端不需要解锁｜" + flow.reason);
    }

    /**
     * 只要结论的薄委托（规格点名的那 5 个入参：{@code hp / dead / deathTime / guarded / creative}）。
     *
     * <p>离线自证就是拿这个跑矩阵：{@code hurtTime = 0}、{@code fieldHealth = NaN}（= 第二通道读不到，这一条不参与）、
     * {@code enabled = true}。
     */
    public static boolean shouldUnlock(float hp, boolean deadOrDying, int deathTime, boolean guarded,
                                       boolean creativeOrSpectator) {
        return decide(hp, deadOrDying, deathTime, 0, Float.NaN, guarded, creativeOrSpectator, true).yes;
    }

    /**
     * {@code getMaxHealth()} 能不能用来补血。
     *
     * <p>NaN / 无限 / 非正 一律 {@code false}：这时候硬写会把血量字段写成 NaN（比 0 更糟），
     * 所以这一步<b>一步都不做</b>，只打日志（{@code via=failed}），留给下一 tick / 服务端那条腿。
     */
    public static boolean maxUsable(float max) {
        return max > 0f && !Float.isInfinite(max);   // NaN > 0 == false ⇒ 被这一条兜住
    }

    /**
     * 两条腿补血的通道 ⇒ 规格里的 {@code via=}（{@code setHealth} ／ {@code data} ／ {@code failed}）。
     *
     * <p>为什么 {@code DIRECT} 映射成 {@code data} 而不是 {@code direct}：规格逐字写的是
     * {@code via=setHealth|data}，而 {@link ShanhaiGuardCore.HealChannel#label()} 里的 {@code direct}
     * 是核内部给日志用的名字，两边别混。
     */
    public static String viaOf(ShanhaiGuardCore.HealChannel channel) {
        if (channel == null) {
            return VIA_FAILED;
        }
        switch (channel) {
            case SET_HEALTH:
                return VIA_SET_HEALTH;
            case DIRECT:
                return VIA_DATA;
            default:
                return VIA_FAILED;
        }
    }

    /**
     * 屏幕类名归一化：全名 ⇒ 简单名（{@code net.minecraft.client.gui.screens.DeathScreen} ⇒ {@code DeathScreen}）；
     * 空 ⇒ {@link #SCREEN_NONE}。
     */
    public static String screenName(String className) {
        if (className == null) {
            return SCREEN_NONE;
        }
        final String trimmed = className.trim();
        if (trimmed.isEmpty()) {
            return SCREEN_NONE;
        }
        final int dot = trimmed.lastIndexOf('.');
        return dot < 0 ? trimmed : trimmed.substring(dot + 1);
    }

    /**
     * 🔴 <b>规格那一行</b>（字段顺序就是核对顺序，别调换）：
     *
     * <pre>
     *   [SHANHAI-GUARD] client_death_unlock hp_before=&lt;n&gt; hp_after=&lt;n&gt; dead_before=&lt;b&gt; dead_after=&lt;b&gt;
     *                                        death_time=&lt;n&gt; screen=&lt;类名|none&gt; via=setHealth|data
     * </pre>
     *
     * @param hpBefore     动作【前】的 {@code getHealth()}
     * @param hpAfter      动作【后】的 {@code getHealth()}（= 这一次到底把血写回来了没有）
     * @param deadBefore   动作【前】的 {@code isDeadOrDying()}
     * @param deadAfter    动作【后】的 {@code isDeadOrDying()}
     * @param deathTime    动作【前】的 {@code deathTime}（= 触发这一次的那个计时器读数）
     * @param screenClass  动作【前】的屏幕类全名（无屏幕传 {@code null}）
     * @param via          {@link #VIA_SET_HEALTH} ／ {@link #VIA_DATA} ／ {@link #VIA_FAILED}
     */
    public static String logLine(float hpBefore, float hpAfter, boolean deadBefore, boolean deadAfter, int deathTime,
                                 String screenClass, String via) {
        return ShanhaiGuardCore.LOG_PREFIX + TOKEN
                + " hp_before=" + hpBefore
                + " hp_after=" + hpAfter
                + " dead_before=" + deadBefore
                + " dead_after=" + deadAfter
                + " death_time=" + deathTime
                + " screen=" + screenName(screenClass)
                + " via=" + via;
    }
}
