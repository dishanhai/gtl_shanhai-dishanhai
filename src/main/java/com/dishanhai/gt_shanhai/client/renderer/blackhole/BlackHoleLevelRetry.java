package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/**
 * 山海重构 · <b>「降级之后允许重试一级」的节流器</b>（纯逻辑：零 MC / 零 GL）。
 *
 * <h2>0. 🔴 它修的是什么（任务书第 1 条："降级必须可恢复"）</h2>
 * 实机日志（{@code GTL山海9.10test\logs\latest.log}）里只有<b>一行</b>降级：
 * <pre>
 *   [SHANHAI-BH] fallback=2 reason=bh_view_bad:bn_3.01
 * </pre>
 * 而它<b>之后每一帧</b>都是 {@code path=fallback-quad … level=2}（本次会话 1673 行 BH 日志里，
 * 这一行之后 450+ 行读数<b>没有一帧</b>回到 {@code path=lens-after-composite}），
 * 直到退出游戏。原因不是"条件没重新满足"，而是
 * {@link BlackHoleFallbackGuard#degradeTo} <b>只降不升</b>
 * （{@code if (newLevel <= this.level) return false;}），
 * 而唯一能抬回去的 {@link BlackHoleFallbackGuard#reset()} 只被<b>资源重载</b>和两条命令调用。
 * <p>用户视角就是："一次瞬移 / 一次走动，这一局的黑洞就永远变贴图了"。
 *
 * <h2>1. 口径：指数退避地"试探"一级</h2>
 * <ul>
 *   <li>刚降级：先等 {@value #FIRST_FRAMES} 帧（≈1 s @60fps）再试；</li>
 *   <li>每失败一次，等待翻倍（{@value #FACTOR} 倍），封顶 {@value #MAX_FRAMES} 帧（60 s）；</li>
 *   <li>成功一次 ⇒ {@link #disarm()}，等待回到初始值（下次降级立刻可用）；</li>
 *   <li><b>三级不复活</b>（{@link BlackHoleFallbackGuard#reset()} 自己也拒），与既有口径一致。</li>
 * </ul>
 * ⇒ 一次性的失败（瞬移那一帧、走动 bob、一次 GL 抖动）1 秒后就自愈；
 * 真故障（驱动不给编译、贴图上传必炸）最多每 60 s 付一次尝试的代价，不会把帧率拖死。
 *
 * <h2>2. 为什么要"重试"不只是"把 level 抬回去"</h2>
 * 降级点在失败时通常也会把<b>资源旗标</b>关掉（{@code shaderTried=true}、{@code copyVerified=true}），
 * 光抬 level 没用：{@code ensureShader()} 会直接返回 {@code null}。
 * ⇒ 调用方（{@code BlackHoleLensRenderer.renderFrame}）在 {@link #tick} 返回 true 的那一帧
 * 必须同时把那些旗标清掉。这条注释是给下一个人的：<b>只 reset guard 是没用的</b>。
 *
 * <h2>3. 纯逻辑</h2>
 * 没有时间、没有线程、只吃"帧号/level"两个整数 ⇒ 离线复现器可以按帧推进，
 * 断言"第 60 帧试一次、第 180 帧再试、第 420 帧再试…"。
 */
public final class BlackHoleLevelRetry {

    /** 第一次重试前等多少帧（≈1 s @60fps）。 */
    public static final int FIRST_FRAMES = 60;
    /** 失败一次之后等待翻几倍。 */
    public static final int FACTOR = 2;
    /** 等待上限（帧）：{@value} 帧 ≈ 60 s @60fps。 */
    public static final int MAX_FRAMES = 3600;

    /** 当前等待窗口（帧）；{@code 0} = 还没武装。 */
    private int waitFrames;
    /** 当前窗口已经过了几帧。 */
    private int elapsed;
    /** 本窗口内已经试过几次（进日志的 {@code try=}）。 */
    private int attempts;

    /**
     * 每帧调一次。
     *
     * @param level 当前 guard 的降级级别（{@link BlackHoleFallbackGuard#level()}）
     * @return {@code true} = <b>这一帧</b>该把 level 抬回一级试一次（调用方负责 reset + 清资源旗标 + 打日志）
     */
    public boolean tick(int level) {
        if (level <= BlackHoleFallbackGuard.LEVEL_FULL
                || level >= BlackHoleFallbackGuard.LEVEL_NONE) {
            disarm();                                   // 已经是一级（不必重试）／三级（不复活）
            return false;
        }
        if (waitFrames <= 0) {
            waitFrames = FIRST_FRAMES;                   // 刚降到二级 ⇒ 武装
            elapsed = 0;
            attempts = 0;
        }
        if (++elapsed < waitFrames) {
            return false;
        }
        elapsed = 0;
        attempts++;
        final long next = (long) waitFrames * FACTOR;
        waitFrames = (int) Math.min(next, MAX_FRAMES);
        return true;
    }

    /** 立刻在下一帧重试一次（相机瞬移 / 换维度 / 用户敲 reload 这类"条件真的变了"的时机）。 */
    public void forceNext() {
        if (waitFrames <= 0) {
            waitFrames = FIRST_FRAMES;
            attempts = 0;
        }
        elapsed = waitFrames;                            // 下一帧 tick 就命中
    }

    /** 恢复成功 / 退出一级以外的情况 ⇒ 清零。 */
    public void disarm() {
        waitFrames = 0;
        elapsed = 0;
        attempts = 0;
    }

    /** 当前等待窗口（帧）；0 = 未武装。 */
    public int waitFrames() {
        return waitFrames;
    }

    /** 当前窗口内已试过几次。 */
    public int attempts() {
        return attempts;
    }

    /** 是不是正在等下一次重试（{@code /shanhai bh status} 可用）。 */
    public boolean armed() {
        return waitFrames > 0;
    }

    /**
     * 那一行日志（格式固定、值无空白）：
     * <pre>
     *   [SHANHAI-BH] level_retry from=2 to=1 why=auto_backoff try=3 wait_frames=240
     * </pre>
     * 任务书要求的前缀逐字保留：{@code [SHANHAI-BH] level_retry from=2 to=1 why=…}。
     */
    public String line(int from, int to, String why) {
        return BlackHoleFallbackGuard.PREFIX + " level_retry from=" + from + " to=" + to
                + " why=" + BlackHoleFallbackGuard.sanitize(why)
                + " try=" + attempts + " wait_frames=" + waitFrames;
    }
}
