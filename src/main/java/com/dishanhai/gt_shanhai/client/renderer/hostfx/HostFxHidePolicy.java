package com.dishanhai.gt_shanhai.client.renderer.hostfx;

/**
 * 归墟视界枢核 · <b>「藏块台账必须能还原」的判定规则（纯逻辑，零 MC / 零 GL）</b>。
 *
 * <h2>为什么单独一个类（2026-10-24 傍晚，用户第 ① 条）</h2>
 * 用户原话：「<b>破坏主机结构之后，被隐藏的方块没有重新渲染回实体</b>」
 * —— 他补了一句"这最严重：等于结构被打掉后世界缺块"。
 *
 * <p>旧实现的还原判定只有<b>一条</b>：渲染器走到 {@code not_formed} 那道闸门时调
 * {@code HostFxHide.restore(...)}，且要<b>连续 100 tick（5 秒）</b>未成型才写回。
 * 于是有三条路会让"藏了却永远不还"：
 * <ol>
 *   <li><b>渲染器不再被调用</b>：控制器方块实体没了 / 区块卸载 / 机器对象变了
 *       ⇒ {@code render()} 整个不进 ⇒ 还原代码一行都跑不到；</li>
 *   <li><b>控制器的三条早退没接还原</b>：{@code no_machine} / {@code no_controller} /
 *       {@code not_client_level} 都是 {@code return}，台账原样留着；</li>
 *   <li><b>换维度/退出世界</b>：{@code clearAll()} 只丢表、<b>不写回</b>。</li>
 * </ol>
 *
 * <h2>新判据：两个时钟，任一超时就还原</h2>
 * <ul>
 *   <li><b>{@code lastFormedMs}（心跳）</b>：上一次<b>确认结构成型、且这一帧真的算过它</b>的时刻。
 *       超过 {@link #STALE_HEARTBEAT_MS} 没有任何心跳 ⇒ "我们已经看不见这台主机了" ⇒
 *       <b>立刻还原</b>（不赌它还活着）。这条专治上面 ①②③；</li>
 *   <li><b>{@code unformedSinceMs}</b>：连续未成型的起点。超过
 *       {@link #RESTORE_DEBOUNCE_MS} ⇒ 还原（保留去抖，是为了不吃 {@code isFormed()} 的抖动）。</li>
 * </ul>
 * 这两条<b>都在</b>一个独立的 client tick 驱动（{@code HostFxHideTicker}）里跑，
 * <b>不依赖 BER 有没有被调用</b> —— 这是"改前红"的根因，也是"改后绿"的关键。
 *
 * <h2>离线可跑</h2>
 * 本类<b>不 import 任何 Minecraft 类型</b>，所以 {@code temp/host-fx/probe/HideRestoreProbe.java}
 * 能在没有游戏的情况下逐条跑 {@link #needsRestore} 的真值表（判据见报告 §①）。
 */
public final class HostFxHidePolicy {

    private HostFxHidePolicy() {}

    /**
     * 连续未成型多少 tick 才还原（去抖）。
     *
     * <p>🔴 2026-10-24 傍晚：{@code 100 → 40}（5 秒 → 2 秒）。用户口径是"结构失型就要还原回实体"，
     * 5 秒的世界缺块已经能被看出；而 40 tick 仍然足够盖住 {@code isFormed()} 的抖动
     * （部件区块卸载后是每 4 tick 异步重查一次，重查出结果通常 < 1 秒）。
     */
    public static final int RESTORE_DEBOUNCE_TICKS = 40;

    /** 去抖对应的毫秒数（1 tick = 50 ms，与 {@code RESTORE_DEBOUNCE_TICKS} 同源）。 */
    public static final long RESTORE_DEBOUNCE_MS = RESTORE_DEBOUNCE_TICKS * 50L;

    /**
     * 心跳超时（毫秒）：超过这么久没"确认成型"过，就认为我们<b>已经看不见</b>这台主机
     * （方块实体没了 / 区块卸载 / 玩家走远）⇒ 台账必须立刻还原。
     *
     * <p>为什么是 2000 ms：正常一帧一次心跳（≈16 ms），2 秒 = 120 帧的余量，
     * 足够跨过"切屏/暂停/一次 GC"，又短到用户几乎察觉不到。
     */
    public static final long STALE_HEARTBEAT_MS = 2_000L;

    /** 对账周期（tick）：{@code HostFxHideTicker} 每这么多 tick 跑一次。 */
    public static final int RECONCILE_TICKS = 20;

    /**
     * <b>唯一判定</b>：这一台主机的台账现在必须被还原吗。
     *
     * @param hasLedger       台账还有格子吗（空表 = 没事可做）
     * @param lastFormedMs    上次心跳的墙钟毫秒；{@code null} = 本次会话从没确认过成型
     * @param unformedSinceMs 连续未成型的起点；{@code null} = 目前没有"未成型"这件事
     * @param nowMs           墙钟毫秒
     * @return true ⇒ 立刻按台账把每一格写回原方块
     */
    public static boolean needsRestore(boolean hasLedger, Long lastFormedMs, Long unformedSinceMs,
                                       long nowMs) {
        if (!hasLedger) {
            return false;                       // 没有台账：不需要做任何事
        }
        if (lastFormedMs == null) {
            return true;                        // 有台账却从没确认过成型 ⇒ 台账不可信，立刻还
        }
        if (nowMs - lastFormedMs > STALE_HEARTBEAT_MS) {
            return true;                        // 心跳断了：我们看不见它了 ⇒ 不赌，还回去
        }
        return unformedSinceMs != null && nowMs - unformedSinceMs >= RESTORE_DEBOUNCE_MS;
    }

    /**
     * 逐格对账：这一格<b>是否仍是我们藏的那个样子</b>。
     *
     * <p>我们藏块的写法是"写 AIR"，所以"还是我们藏的样子" = <b>现在仍是空气</b>。
     * <ul>
     *   <li>仍是空气 ⇒ 继续由我们负责（留在台账里，等还原那次一起写回）；</li>
     *   <li>不是空气 ⇒ 别人（服务端同步 / 另一个 mod / 玩家放置）已经动过这一格
     *       ⇒ <b>从台账里摘掉</b>，还原时不再覆盖它（这是"绝不把别人的方块盖掉"的那道保险）。</li>
     * </ul>
     *
     * @param currentlyAir 世界里这一格现在是空气吗
     * @return true = 这一格要留在台账里
     */
    public static boolean keepInLedger(boolean currentlyAir) {
        return currentlyAir;
    }

    /** 一行判定依据（日志用，便于回读"为什么这次还原了"）。 */
    public static String why(boolean hasLedger, Long lastFormedMs, Long unformedSinceMs, long nowMs) {
        if (!hasLedger) {
            return "ledger_empty";
        }
        if (lastFormedMs == null) {
            return "never_formed";
        }
        if (nowMs - lastFormedMs > STALE_HEARTBEAT_MS) {
            return "heartbeat_stale_" + (nowMs - lastFormedMs) + "ms";
        }
        if (unformedSinceMs != null && nowMs - unformedSinceMs >= RESTORE_DEBOUNCE_MS) {
            return "unformed_" + (nowMs - unformedSinceMs) + "ms";
        }
        return "still_ok";
    }
}
