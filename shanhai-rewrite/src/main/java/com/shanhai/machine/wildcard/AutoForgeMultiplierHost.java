package com.shanhai.machine.wildcard;

import java.util.List;

/**
 * <b>「自动倍率」两台机器共用的宿主接口</b>（唯一实现的两半之一 —— 另一半是
 * {@link ShanhaiAutoForgeMultiplierDriver}）。
 *
 * <h2>🔴 为什么要有这个接口（2026-10-04 用户追加需求 B）</h2>
 * 用户逐字：「<b>为什么超级样板总成没有</b>」→ 追问后他选「<b>要，给上游那台也加上</b>」。
 * 「超级样板总成」= gtladditions 的
 * {@code com.gtladd.gtladditions.common.machine.multiblock.part.MESuperPatternBufferPartMachine}
 * —— <b>那台不是我们的类</b>，只能靠 mixin 给它加字段/方法。
 *
 * <h2>🔴 硬要求：不许出现第二份实现</h2>
 * 用户逐字：「要加的东西<b>与我们自己那台完全一致（同一套 reader ＋ 同一套判定/文案 ✓
 * 不许出现第二份实现 ✗）</b>」。
 * ⇒ 本接口把「两台机器各自不同」的部分（字段在哪、怎么读控制器、生效值怎么落地）抽出来，
 * 而<b>全部判定、文案、限流日志都只有一份</b>，住在 {@link ShanhaiAutoForgeMultiplierDriver}
 * 与 {@link ShanhaiAutoForgeMultiplier} 里。两个实现方：
 * <pre>
 *   ① 本工程那台  ： {@code SuperWildcardPatternBufferPartMachine}（直接 implements，零新增方法）
 *   ② 上游那台    ： {@code ShanhaiSuperPatternBufferAutoMultiplierMixin}（mixin 里 @Unique 实现）
 * </pre>
 * <p>面板那边同理：{@link AutoForgeMultiplierPanel} 是唯一的装配实现，两台机器都走它。
 */
public interface AutoForgeMultiplierHost {

    // ═══════════════ 一、面板要读写的那几个旋钮（两台机器语义完全相同） ═══════════════

    /** 「神锻样板模式」开关当前状态。 */
    boolean isForgePatternModeEnabled();

    /** 切换「神锻样板模式」。 */
    void setForgePatternModeEnabled(boolean enabled);

    /** <b>手填倍率</b>（输入框里那个数；自动开着时不参与运算）。 */
    int getForgePatternMultiplier();

    /** 设置手填倍率。 */
    void setForgePatternMultiplier(int multiplier);

    /** 「自动倍率」开关当前状态（默认开）。 */
    boolean isAutoForgeMultiplierEnabled();

    /** 切换「自动倍率」；实现方负责让生效值立刻落地（与玩家手改倍率同一条路径）。 */
    void setAutoForgeMultiplierEnabled(boolean enabled);

    // ═══════════════ 二、同步状态字段（两台机器各自持有，语义相同） ═══════════════

    /** 最近一次读到的倍率；{@link ShanhaiAutoForgeMultiplier#NO_READ} = 没读到。 */
    int shanhaiAutoForgeMultiplierRead();

    /** 读到时是来源（{@code SOURCE_*}），读不到时是原因（{@code REASON_*}）。 */
    String shanhaiAutoForgeMultiplierNote();

    /** 把「读数 + 标注」写进同步字段（<b>驱动是唯一的写点</b>）。 */
    void shanhaiStoreAutoForgeMultiplier(int read, String note);

    // ═══════════════ 三、面板两行文字（两台机器同一份实现，见 Driver） ═══════════════

    /** 面板第一行：手填 N / 读到 N / 读不到 -> ×1。 */
    String shanhaiAutoForgeMultiplierText();

    /** 面板第二行：来源 / 原因。 */
    String shanhaiAutoForgeMultiplierReasonText();

    /** 本机那份驱动（持有日志闸门与节流计数；每台机器一个）。 */
    ShanhaiAutoForgeMultiplierDriver shanhaiAutoForgeMultiplierDriver();

    // ═══════════════ 四、两台机器不一样的那几件事（A：我们是仓室，B：上游也是仓室） ═══════════════

    /** 是不是客户端侧的那份（{@link com.gregtechceu.gtceu.api.machine.MetaMachine#isRemote()}）。 */
    boolean shanhaiIsRemote();

    /** 本仓室所在多方块的控制器列表（{@code MultiblockPartMachine#getControllers()}）。 */
    List<?> shanhaiControllers();

    /**
     * <b>当前已经落地的生效倍率</b>（用于「变了才动手」的变化检测）。
     *
     * <pre>
     *   本工程那台 ： 由已同步的读数算出来的生效值（{@code getEffectiveForgePatternMultiplier()}）
     *   上游那台   ： 它自己那个 {@code foaPatternOutputMultiplier} 字段（读数落地就写进去）
     * </pre>
     */
    int shanhaiAppliedForgeMultiplier();

    /**
     * <b>把生效倍率真正落到机器上</b>。
     *
     * <pre>
     *   本工程那台 ： {@code rebuildPatterns()}（它按已同步的读数重算，入参只用于日志）
     *   上游那台   ： {@code setFOAPatternOutputMultiplier(n)}（上游自己的公开写入口，
     *                模式开着时它会自己 {@code refreshAllByProduct()} —— 既有行为一个字没动）
     * </pre>
     */
    void shanhaiApplyForgeMultiplier(int multiplier);

    /** 日志前缀（两台机器分得清是谁在说话）。 */
    String shanhaiLogTag();
}
