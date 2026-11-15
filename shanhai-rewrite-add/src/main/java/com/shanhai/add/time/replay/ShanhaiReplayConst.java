package com.shanhai.add.time.replay;

/**
 * 山海重构 · <b>时间倒流的内核常量</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <p>口径来源：{@code temp\reversal\设计.md} §3 / §9。本类只放"数字"，不放逻辑，
 * 这样离线自检（{@code tools\replay-selfcheck}）和运行期看到的是<b>同一组数字</b>。
 *
 * <table border="1">
 *   <caption>与设计稿的对应</caption>
 *   <tr><th>常量</th><th>设计稿</th><th>用途</th></tr>
 *   <tr><td>{@link #WINDOW_TICKS}</td><td>§2 窗口 W = 1200 tick</td><td>默认把"最近 60 秒"倒完</td></tr>
 *   <tr><td>{@link #MAX_RECORDS}</td><td>§3 总条数上限 200k</td><td>环形缓冲容量（满了丢最老）</td></tr>
 *   <tr><td>{@link #MAX_PER_TICK}</td><td>§3 单 tick 上限 4096（熔断）</td><td>防爆炸/拆塔把内存顶穿</td></tr>
 *   <tr><td>{@link #MAX_WRITES_PER_STEP}</td><td>§4.1 每 tick 写入预算 2048</td><td>P1 只做分桶，不做跨 tick 预算切分（见 {@code ShanhaiReplayService} 类注释）</td></tr>
 * </table>
 */
public final class ShanhaiReplayConst {

    /** 倒流素材窗口：1200 tick = 60 秒（1:1 速度下观感也是 60 秒）。 */
    public static final int WINDOW_TICKS = 1200;

    /** 原版定拍：20 tick/秒（倒流的"秒数"就靠它换算）。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 环形缓冲总条数上限：满了<b>丢最老</b>（设计 §10-⑤ 推荐 A）。 */
    public static final int MAX_RECORDS = 200_000;

    /**
     * 倒流速度倍率默认值：<b>1.0</b> = 与录制等速（倒流 N 秒就花 N 秒）。
     *
     * <p>🔴 用户口径：「回溯时恰好根据那个数相反的样子回溯」⇒ 默认必须 1:1，
     * 逐 tick 一片地反向播放。倍率只作为<b>可调</b>项存在（{@code >1} = 每外层 tick 退多片），
     * 任何"一口气跳回去"的实现都属于回归（自检 I 组的负对照专抓这个）。
     */
    public static final double REPLAY_SPEED_DEFAULT = 1.0D;

    /** 倍率上限（再快就等于瞬移了，也会把服务端憋住）。 */
    public static final double REPLAY_SPEED_MAX = 8.0D;

    /** 实体事件上限（出现/消失各算一条）。 */
    public static final int MAX_ENTITY_RECORDS = 20_000;

    /**
     * 实体<b>运动</b>采样上限（第 2 层；与实体出现/消失层同量级）。
     *
     * <p>量级估算：只有"真的动了"才记 ⇒ 128 格内 ~40 只生物 × 1200 tick 的最坏情况是 48000 条，
     * 每条 ≈ 40 B ⇒ ≈ 2 MB。这里给到 20 万，是为了"实体特别多 + 都在跑"的基地也不丢窗口。
     */
    public static final int MAX_ENTITY_MOTION_RECORDS = 200_000;

    /**
     * <b>生物血量</b>采样上限（"倒流之后血量要回来"这一层）。
     *
     * <p>用户原话：「倒流之后，生物血量没有恢复，而且回血我要也要每 tick 那样记录」。
     * 量级与位置层同阶（只有"血量真的变了"才记）⇒ 给同一档预算。
     */
    public static final int MAX_HEALTH_RECORDS = 200_000;

    /** 机器进度上限（瘦日志：只记变化的那几台）。 */
    public static final int MAX_PROGRESS_RECORDS = 120_000;

    /** 方块实体最小差上限（只记 {@code setChanged} 打标记的那些）。 */
    public static final int MAX_BLOCK_ENTITY_RECORDS = 120_000;

    /** 世界级标量上限（每维度每秒最多几十条，这个量级已经过剩）。 */
    public static final int MAX_SCALAR_RECORDS = 8_000;

    /** 单 tick 记录上限：超出部分丢弃并计数（{@code overflow}）。 */
    public static final int MAX_PER_TICK = 4_096;

    /** 每外层 tick 的回退写入预算（P1 用它做"分批"的度量口径，不做跨 tick 切分）。 */
    public static final int MAX_WRITES_PER_STEP = 2_048;

    /** 状态索引容量上限（超过就停止记录并计数，见 {@code ShanhaiStateIndex}）。 */
    public static final int MAX_STATES = 200_000;

    /** "这一笔没有方块实体载荷"。 */
    public static final int NO_PAYLOAD = -1;

    /**
     * <b>旧语义</b>（{@code /shanhai_add time rewind <秒>}）的窗口口径：整秒 × {@link #TICKS_PER_SECOND}，
     * 再与"环形里实际可用的 tick 数"和 {@link #WINDOW_TICKS} 取小。
     *
     * <p>为什么把它抽成一个纯函数：加了锚点语义之后，最容易被顺手改坏的就是这条旧路
     * （"顺手把 rewind 全都改成回锚点"）。抽出来之后，离线自检可以断言
     * {@code planTicks(5, 5000) == 100}、{@code planTicks(0, 5000) == 1200}
     * ⇒ 旧语义一旦被改动就当场红（判据 ⑤）。
     *
     * @param seconds   {@code <= 0} = 不按秒数限制（倒完整个窗口）
     * @param available 环形里实际还能回退的 tick 数（{@code <= 0} ⇒ 0）
     * @return 本次倒流的 tick 数（{@code 0} = 拒绝：没有可回退的）
     */
    public static int planTicks(int seconds, int available) {
        int plan = Math.min(WINDOW_TICKS, Math.max(0, available));
        if (seconds > 0) {
            plan = Math.min(plan, seconds * TICKS_PER_SECOND);
        }
        return Math.max(0, plan);
    }

    private ShanhaiReplayConst() {
    }
}
