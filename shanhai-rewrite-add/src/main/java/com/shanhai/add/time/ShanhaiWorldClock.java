package com.shanhai.add.time;

/**
 * 山海重构 · <b>世界时钟（唯一状态持有者）</b>。
 *
 * <h2>1. 为什么要有这个类</h2>
 * 「暂停 / 加速 / 倒流 / 调制重力」四件事共用同一个旋钮面。如果让每个 mixin、每条指令、
 * 每个机器各自记一个 boolean，迟早会出现"服务端以为在暂停、客户端以为在跑"的半冻状态
 * （{@code temp\time-census\报告.md} A② 正是这个坑）。所以：
 * <ul>
 *   <li><b>状态只在这个类里</b>，别人<b>只读</b>（{@code getMode()/isPaused()/levelTicksPerOuterTick()}）；</li>
 *   <li>写入口只有 {@link #setMode} / {@link #setRate} / {@link #applySnapshot} 三个；</li>
 *   <li>服务端权威 = {@link #SERVER}；客户端只是只读镜像 = {@link #CLIENT}，
 *       由网络包写入（{@code ShanhaiClockNetwork}），客户端自己<b>永远不能</b>改 {@code mode}。</li>
 * </ul>
 *
 * <h2>2. 🔴 本类<b>零 Minecraft 依赖</b>（刻意的，别加 import）</h2>
 * 这样 {@code tools\clock-selfcheck} 可以用裸 {@code javac} 把本文件 + 自检类编出来跑，
 * 不需要 Forge/游戏类路径（本轮红线：不许构建、不许启动游戏）。
 * ⇒ 任何 {@code net.minecraft*} / {@code net.minecraftforge*} 的 import 都会让自检编不过，
 * 属于"破坏了可离线验证"的回归。
 *
 * <h2>3. 字段语义（对外契约）</h2>
 * <table border="1">
 *   <caption>状态字段</caption>
 *   <tr><th>字段</th><th>语义</th><th>谁用</th></tr>
 *   <tr><td>{@code mode}</td><td>三态互斥，见 {@link ShanhaiClockMode}</td><td>全部</td></tr>
 *   <tr><td>{@code rate}</td><td>世界时钟倍率，必须 {@code > 0}；
 *       {@code PAUSED}/{@code REPLAY} 下<b>不生效</b>（{@link #effectiveRate()} 返回 0）</td><td>加速（下一轮）</td></tr>
 *   <tr><td>{@code accumulatorNanos}</td><td>墙钟→tick 的累加器。<b>进入 PAUSED 时清零</b>
 *       —— 这是"恢复后不跳一大段时间"的关键（不能把暂停期间攒的墙钟补回来）</td><td>加速（下一轮）</td></tr>
 *   <tr><td>{@code anchorTick}</td><td>切换模式那一刻的世界 tick 号（诊断/自检用；
 *       恢复后世界从同一个 tick 继续 ⇒ 不跳时间）</td><td>status</td></tr>
 *   <tr><td>{@code clientMsPerTick}</td><td>客户端 {@code Timer} 应当使用的 ms/tick
 *       （{@code 1000/(20*rate)}）。<b>本轮只用于 status 显示</b>：暂停不是靠改它实现的
 *       （见 {@code ShanhaiClientClock} 的说明）</td><td>加速（下一轮）</td></tr>
 *   <tr><td>{@code replicaTick}</td><td>客户端镜像 tick（只读镜像自增，暂停时不增）</td><td>客户端</td></tr>
 *   <tr><td>{@code epoch}</td><td>每次 {@code mode} 变化 +1；客户端丢弃旧 epoch 的包，
 *       防「切模式瞬间跳变」</td><td>全部</td></tr>
 * </table>
 */
public final class ShanhaiWorldClock {

    /** 原版定拍：20 tick/秒。 */
    public static final int TICKS_PER_SECOND = 20;
    /** 原版一 tick 的墙钟长度（ns）。 */
    public static final long NANOS_PER_TICK = 50_000_000L;

    public static final double DEFAULT_RATE = 1.0D;
    /** 倍率下限（>0，防止 0/负数把 {@code clientMsPerTick} 算成 Infinity）。 */
    public static final double MIN_RATE = 0.05D;
    /** 倍率上限（防手滑输入 1e9 把服务端打死）。 */
    public static final double MAX_RATE = 256.0D;
    /** 单次外层 tick 最多补几次 world tick（加速路径的保险丝，本轮用不到）。 */
    public static final int MAX_TICKS_PER_OUTER_TICK = 10;

    /** 服务端权威时钟（专用服务端 = 那一份；单机 = 集成服务端那一份）。 */
    public static final ShanhaiWorldClock SERVER = new ShanhaiWorldClock("SERVER");
    /**
     * 客户端只读镜像。
     *
     * <p>🔴 它<b>只能</b>被 {@link #applySnapshot} 写 —— 客户端不许自己决定 mode，
     * 否则两端各自跑表就是普查里那个"服务端冻了、天空还在走"的坑。
     */
    public static final ShanhaiWorldClock CLIENT = new ShanhaiWorldClock("CLIENT");

    private final String name;

    private ShanhaiClockMode mode = ShanhaiClockMode.LIVE;
    private double rate = DEFAULT_RATE;
    private double accumulatorNanos;
    private long anchorTick;
    private float clientMsPerTick = 50.0F;
    private long replicaTick;
    private long epoch;
    private long rejectedWrites;

    /**
     * @param name 仅用于日志/status（{@code SERVER} / {@code CLIENT} / 自检用的自定义名）
     */
    public ShanhaiWorldClock(String name) {
        this.name = name;
    }

    public String name() {
        return this.name;
    }

    // ------------------------------------------------------------------ 读

    public ShanhaiClockMode getMode() {
        return this.mode;
    }

    public boolean isPaused() {
        return this.mode == ShanhaiClockMode.PAUSED;
    }

    public boolean isLive() {
        return this.mode == ShanhaiClockMode.LIVE;
    }

    /** 倒流占位：本轮恒为 {@code false}（没有任何代码会把它设成 REPLAY）。 */
    public boolean isReplay() {
        return this.mode == ShanhaiClockMode.REPLAY;
    }

    /** 配置的倍率（即使处于 PAUSED 也照原样返回，方便 status 打印"暂停前的倍率"）。 */
    public double getRate() {
        return this.rate;
    }

    /**
     * <b>实际生效</b>的倍率：{@code PAUSED}/{@code REPLAY} 下恒为 {@code 0.0}。
     *
     * <p>「PAUSED 时 rate 无效」就是这一行：不是拒绝写 {@code rate}，而是让它在暂停期间
     * 算出来是 0（这样所有按倍率算 tick 的地方<b>天然</b>得到 0，不需要各自判断 mode）。
     */
    public double effectiveRate() {
        return this.mode == ShanhaiClockMode.LIVE ? this.rate : 0.0D;
    }

    public double getAccumulatorNanos() {
        return this.accumulatorNanos;
    }

    public long getAnchorTick() {
        return this.anchorTick;
    }

    public float getClientMsPerTick() {
        return this.clientMsPerTick;
    }

    public long getReplicaTick() {
        return this.replicaTick;
    }

    public long getEpoch() {
        return this.epoch;
    }

    /** 被拒绝的非法写入次数（自检与 status 用；只增不减）。 */
    public long getRejectedWrites() {
        return this.rejectedWrites;
    }

    /**
     * 这一次外层 tick，服务端该跑几次 {@code ServerLevel#tick}。
     *
     * <p>本轮：{@code PAUSED}/{@code REPLAY} ⇒ <b>0</b>，{@code LIVE} ⇒ <b>1</b>（原版定拍）。
     * 加速（下一轮）会把它变成 {@code rate} 的整数倍，调用点不用改
     * —— 这就是"骨架留好接口"的意思。
     */
    public int levelTicksPerOuterTick() {
        switch (this.mode) {
            case PAUSED:
            case REPLAY:
                return 0;
            case LIVE:
            default:
                return 1;
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 切模式。<b>唯一</b>会 bump {@code epoch} 的地方。
     *
     * @param newMode 新模式，{@code null} 视为非法（拒绝，不改状态）
     * @return 是否真的发生了变化
     */
    public boolean setMode(ShanhaiClockMode newMode) {
        if (newMode == null) {
            ++this.rejectedWrites;
            return false;
        }
        if (newMode == this.mode) {
            return false;
        }
        this.mode = newMode;
        ++this.epoch;
        // 🔴 关键：暂停/回放期间不攒墙钟 ⇒ 恢复后不会"补回一大段时间"（验收判据 3）。
        this.accumulatorNanos = 0.0D;
        // clientMsPerTick 跟随有效倍率；effectiveRate()==0 时不重算（否则 1000/0 = Infinity）。
        double eff = this.effectiveRate();
        if (eff > 0.0D) {
            this.clientMsPerTick = (float) (1000.0D / (TICKS_PER_SECOND * eff));
        }
        return true;
    }

    /**
     * 按名字切模式；<b>非法名 ⇒ 拒绝 + 不改变现有模式</b>（命令路径用这个）。
     *
     * @param raw 用户输入
     * @return 是否成功切换
     */
    public boolean applyModeName(String raw) {
        ShanhaiClockMode parsed = ShanhaiClockMode.byName(raw);
        if (parsed == null) {
            ++this.rejectedWrites;
            return false;
        }
        return setMode(parsed);
    }

    /**
     * 解析模式名，<b>非法时回退到 {@link ShanhaiClockMode#LIVE}</b>。
     *
     * <p>🔴 只给<b>网络/反序列化</b>路径用：一个坏包绝不能把世界留在"看起来已暂停"的状态里
     * （那就是永久卡死 + 玩家以为 mod 坏了）。<b>失败方向必须是 LIVE</b>。
     */
    public static ShanhaiClockMode modeOrFallback(String raw) {
        ShanhaiClockMode parsed = ShanhaiClockMode.byName(raw);
        return parsed == null ? ShanhaiClockMode.LIVE : parsed;
    }

    /**
     * 设倍率。{@code NaN}/{@code Infinity}/{@code <=0}/越界 ⇒ <b>拒绝</b>（不改值）。
     *
     * <p>注意：暂停期间<b>允许</b>设置（值会留着，恢复后生效），但 {@link #effectiveRate()}
     * 在此之前一直是 0 —— "rate 在 PAUSED 下无效"指的是生效值为 0，不是拒绝写入。
     *
     * @return 是否真的发生了变化
     */
    public boolean setRate(double newRate) {
        if (Double.isNaN(newRate) || Double.isInfinite(newRate)
                || newRate < MIN_RATE || newRate > MAX_RATE) {
            ++this.rejectedWrites;
            return false;
        }
        if (Math.abs(newRate - this.rate) < 1.0E-9D) {
            return false;
        }
        this.rate = newRate;
        double eff = this.effectiveRate();
        if (eff > 0.0D) {
            this.clientMsPerTick = (float) (1000.0D / (TICKS_PER_SECOND * eff));
        }
        return true;
    }

    /**
     * 记下"当前世界 tick 号"（切模式时调用）。
     *
     * <p>它只用于诊断：恢复后世界从同一个 {@code anchorTick} 继续 ⇒ 时间不跳。
     */
    public void noteAnchorTick(long worldTick) {
        this.anchorTick = worldTick;
    }

    /**
     * 每个外层 tick 喂一次墙钟增量，维护 {@code accumulatorNanos}。
     *
     * <p>本轮的用途只有两个：① 保证"暂停中不攒、恢复不补"这条不变量可被自检断言；
     * ② 给下一轮的加速路径留好累加器。返回值可直接当"该跑几次 world tick"用。
     *
     * @param deltaNanos 距上一次外层 tick 的墙钟增量（ns，&lt;=0 视为"未知/异常"，不累加）
     * @return 本次应当执行的 world tick 次数
     */
    public int advanceWorld(long deltaNanos) {
        if (this.mode != ShanhaiClockMode.LIVE) {
            // 非 LIVE：累加器必须保持 0（不许把暂停期间的墙钟存起来）
            this.accumulatorNanos = 0.0D;
            return 0;
        }
        if (deltaNanos > 0L) {
            this.accumulatorNanos += deltaNanos * this.rate;
            // 保险丝：任何情况下都不许攒出超过一个 tick 的预算（否则恢复瞬间会连跑）
            if (this.accumulatorNanos > (double) NANOS_PER_TICK) {
                this.accumulatorNanos = (double) NANOS_PER_TICK;
            }
        }
        return this.levelTicksPerOuterTick();
    }

    /**
     * 客户端镜像：每个客户端 tick 调一次。
     *
     * <p>暂停/回放时 {@code replicaTick} 不增（这就是"客户端三处时钟钉住"里 tick 计数那一处的口径）。
     */
    public void clientTick() {
        if (this.mode == ShanhaiClockMode.LIVE) {
            ++this.replicaTick;
        }
    }

    // ------------------------------------------------------------------ 镜像/序列化

    /**
     * 不可变快照，用于网络下发与 status 打印。<b>纯基础类型</b>，方便离线自检。
     */
    public record Snapshot(ShanhaiClockMode mode, double rate, long epoch, long anchorTick,
                           long replicaTick, float clientMsPerTick, double accumulatorNanos) {

        /** 编码成一行文本（status 用；字段顺序稳定，便于 grep）。 */
        public String toLine() {
            return "mode=" + this.mode
                    + " rate=" + String.format(java.util.Locale.ROOT, "%.3f", this.rate)
                    + " epoch=" + this.epoch
                    + " anchorTick=" + this.anchorTick
                    + " replicaTick=" + this.replicaTick
                    + " clientMsPerTick=" + String.format(java.util.Locale.ROOT, "%.2f", this.clientMsPerTick);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.mode, this.rate, this.epoch, this.anchorTick,
                this.replicaTick, this.clientMsPerTick, this.accumulatorNanos);
    }

    /**
     * 用服务端快照覆盖本地镜像（客户端）。
     *
     * <p>🔴 <b>epoch 更旧的包直接丢弃</b>（乱序/延迟包是常态）：否则一次延迟到达的
     * "LIVE"包会让已经暂停的客户端自己跑起来。
     *
     * @return 是否采纳
     */
    public boolean applySnapshot(Snapshot snap) {
        if (snap == null) {
            ++this.rejectedWrites;
            return false;
        }
        if (snap.epoch() < this.epoch && this.epoch != 0L) {
            ++this.rejectedWrites;
            return false;
        }
        boolean modeChanged = snap.mode() != this.mode;
        this.mode = snap.mode() == null ? ShanhaiClockMode.LIVE : snap.mode();
        if (snap.rate() >= MIN_RATE && snap.rate() <= MAX_RATE
                && !Double.isNaN(snap.rate()) && !Double.isInfinite(snap.rate())) {
            this.rate = snap.rate();
        } else {
            ++this.rejectedWrites;
        }
        this.epoch = Math.max(this.epoch, snap.epoch());
        this.anchorTick = snap.anchorTick();
        this.clientMsPerTick = snap.clientMsPerTick() > 0.0F ? snap.clientMsPerTick() : 50.0F;
        if (modeChanged) {
            // 切模式时镜像 tick 不许"跳"：以服务端给的为准
            this.replicaTick = snap.replicaTick();
        } else if (snap.replicaTick() > this.replicaTick) {
            this.replicaTick = snap.replicaTick();
        }
        this.accumulatorNanos = 0.0D;
        return true;
    }

    @Override
    public String toString() {
        return "ShanhaiWorldClock[" + this.name + " " + snapshot().toLine() + "]";
    }
}
