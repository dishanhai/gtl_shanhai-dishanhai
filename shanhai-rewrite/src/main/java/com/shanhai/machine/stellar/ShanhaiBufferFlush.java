package com.shanhai.machine.stellar;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * <b>星律 buffer → 网络的「免电搬运」核</b>（只依赖 fastutil 与 JDK；不碰 AE / MC / 日志，可离线单测）。
 *
 * <h2>一、为什么要有它</h2>
 * 上一版想的是"快溢出时先 {@code AEUtils#reFunds} 腾地方"。取证后<b>这条路本身走不通</b>：
 * <pre>
 *   AE2 入库要付电 ≈ 1 AE/件（{@code StorageHelper#poweredInsert}：额度 = min(磁盘收多少, 可用电×factor)）
 *   星律 buffer 里可以是 9.22e18 件 ⇒ 需要 ≈9.22e18 AE ⇒ 结构上永远退不完
 * </pre>
 * ⇒ 「先退再写」救不了；真正要让量出得去，只能让<b>这一条退货路径不付电</b>
 * （用户拍板：星律产物入库免电，<b>只在我们这条路径上</b>，不改 AE2 的电经济通用规则）。
 *
 * <h2>二、本类负责什么 / 不负责什么</h2>
 * <ul>
 *   <li><b>负责</b>：一轮搬运的<b>预算、顺序、去留、账目</b> —— 处理多少条（{@link #MAX_ENTRIES}）、
 *       连续失败几次就收手（{@link #MAX_CONSECUTIVE_FAILS}）、搬成功的条目怎么移除、
 *       只搬走一部分时余量怎么写回、优先键先搬、以及三行日志的<b>正文</b>；</li>
 *   <li><b>不负责</b>：真正的入库动作。那是 {@link Mover} 回调（生产侧 =
 *       {@code StorageHelper.poweredInsert(我们的无限电 IEnergySource, …)}）。</li>
 * </ul>
 *
 * <h2>三、🔴 不丢量的三条</h2>
 * <ol>
 *   <li>搬成功多少就减多少（{@code entry.setValue(amount - moved)}），<b>只有减到 0 才移除条目</b>
 *       —— 与上游 {@code AEUtils#reFunds} 偏移 144–177 同源；</li>
 *   <li>搬不动（{@code moved <= 0}）⇒ 条目<b>原样留着</b>，下轮再试；</li>
 *   <li><b>{@code value <= 0} 的条目一次都不许交给 {@link Mover}</b>（见 {@link #moveIfPositive}）——
 *       负值不搬、不删、不归零，只报一行 {@code bufflush BUFNEG}。</li>
 * </ol>
 *
 * <h2>四、为什么一轮最多 64 条 + 连续 5 次失败就停</h2>
 * 两条都是照抄上游 {@code AEUtils#reFunds} 的预算（字节码：{@code bipush 64}；失败计数到 5 就
 * {@code goto} 出循环），目的只有一个：<b>不许卡 tick</b>。多出来的条目留给下一轮。
 *
 * <h2>五、🔴 「追着写」（2026-11-01 实机日志驱动）</h2>
 * 实机读数：{@code bufclamp key=gtceu:lazurite_dust existing=9223372036854775807 want=1104
 * write=0 dropped=1104} —— 每 5 秒复现一次。原因是<b>两个</b>：
 * <ol>
 *   <li>旧节奏是"每 {@code MEPatternOutputMin}(5) tick 才退一轮"，而产出<b>每 tick 都在写</b>
 *       ⇒ 退完立刻又满；</li>
 *   <li>更关键的一条：一轮只有 64 条预算，而 buffer 里键集是<b>稳定</b>的（同一批产物反复重写）
 *       ⇒ fastutil 的遍历顺序也稳定 ⇒ 排在第 64 名之后的键<b>永远轮不到</b>
 *       （实机证据：{@code 条数=64 成功=64 失败=0} 每一轮都成功，可那个键的
 *       {@code existing} 一直停在 MAX ⇒ 它根本没被这一轮看见）。</li>
 * </ol>
 * ⇒ 本版两处改动：{@link #cadenceTicks()} 变成<b>每 tick</b>；以及 {@link #writeWithChase}
 * 在"这笔写不进去"时<b>就地追一轮</b>，并且那一轮把<b>卡住的那个键排在最前面</b>（{@code priority}），
 * 预算只给 {@link #CHASE_ENTRIES} 条 —— 它唯一的任务就是把那个键挪走。
 *
 * <h2>六、🔴 日志口径（用户 2026-11-01 拍板：<b>WARN 就是真的丢了</b>）</h2>
 * 旧口径下 {@code bufclamp … dropped=1104} 是在<b>追写之前</b>打的 ⇒ 追写明明救回来了，日志也照样 WARN
 * （实机 11 条里 10 条是救回来的）。这一版把"打哪一条、什么级别"收到<b>一个纯函数</b>
 * {@link #writeEvents}（离线可断言），判据只有 <b>{@link ChaseWrite#dropped}</b>（追写之后的最终读数）：
 * <pre>
 *   追写救回（dropped == 0）⇒ 一条 WARN 都不打；只留 {@code bufflush CHASE … rescued=1}（INFO）
 *   真丢了（dropped &gt; 0）   ⇒ {@code bufclamp … dropped=&lt;真丢&gt; rescue=failed}（WARN）
 *                              ＋{@code bufflush CHASE … rescued=0}（INFO，同一时刻的证据）
 *   没被夹（大多数）         ⇒ 一条都不打，而且<b>一次字符串都不拼</b>（供给器只在真要打时才被调用）
 * </pre>
 * 为什么 {@code bufclamp} 那行在被救回时<b>干脆不打</b>（而不是降成 DEBUG）：那个键、那三个读数
 * （{@code existing/want/write/dropped}）{@code CHASE} 行<b>全都有</b>，打出来纯属重复；
 * 而它正是"看起来像丢、其实没丢"的误导源。
 */
public final class ShanhaiBufferFlush {

    /** 日志/文案前缀（三行都以它开头，便于一次 grep 全捞出来）。 */
    public static final String PREFIX = "bufflush";

    /** 一轮最多处理多少条（= 上游 {@code AEUtils#reFunds} 的条目预算，字节码 {@code bipush 64}）。 */
    public static final int MAX_ENTRIES = 64;

    /** 连续这么多条搬不动就收手（= 上游 {@code reFunds} 的失败预算，字节码 {@code iconst_5}）。 */
    public static final int MAX_CONSECUTIVE_FAILS = 5;

    /** 搬运间隔的兜底值（tick）：上游读 {@code ConfigHolder.MEPatternOutputMin}，默认就是 5。 */
    public static final int FALLBACK_INTERVAL_TICKS = 5;

    /**
     * <b>当前的搬运节奏（tick）</b>：1 = 每 tick 一轮（"追着写"）。
     *
     * <p>⚠️ <b>不要把它当编译期常量用</b>（{@code static final int} 会被 javac 内联进调用点，
     * 负对照就改不动它）—— 统一走 {@link #cadenceTicks()}。
     */
    private static final int CADENCE_TICKS = 1;

    /**
     * <b>「追着写」那一轮的条目预算</b>：{@code 8}（不是 64）。
     *
     * <p>为什么小：追写只为一件事 —— 把"这笔写不进去"的那个键挪走。它排在最前面（{@code priority}），
     * 1 条就够；给 8 是留点余量（顺手捎走几条同病相怜的），同时把<b>每次追写的代价</b>钉死：
     * 一次追写最多 8 次入库 ⇒ 即使一 tick 里有几十个键同时被夹，代价也不会失控。
     * 大规模的清扫仍然交给"每 tick 一轮、64 条"的常规路径。
     */
    public static final int CHASE_ENTRIES = 8;

    /** {@code bufflush BUFNEG} 那行的动作口径：<b>跳过（不搬、不删、不归零）</b>，理由见 {@link #negLine}。 */
    public static final String NEG_ACTION = "skip";

    /**
     * <b>「状态类」日志的级别判据 —— 心跳/节奏/上限/构造期瞬态/版面提示 全部共用这一个</b>：
     * {@code false} = <b>INFO</b>（用户 2026-11-01 拍板：「节拍不是丢料」「整份日志里 WARN 只剩真丢料 / 真异常」）。
     *
     * <p>覆盖的行（生产侧 5 处，全走这里，不许各自写死）：
     * <ol>
     *   <li>{@code bufflush pos=…}（心跳）、{@code bufflush cadence=…}（节奏）——
     *       经 {@link #heartbeatEvents} 出去；</li>
     *   <li>{@code STOCKSUPPLY 放行上限 …}（活跃槽超上限，5 分钟 1 行）；</li>
     *   <li>{@code SUPPLYVIEW skipped=constructing …}（构造器没跑完就被问）；</li>
     *   <li>{@code STOCKCFG … 我们的 160 格还没建}（同一个构造期瞬态）；</li>
     *   <li>{@code 版面配置…可见槽数被设为…}（改页数配置时）。</li>
     * </ol>
     * <p>🔴 <b>不在</b>这里的：真丢料（{@link #lostLine}）、真异常（BUFNEG / unreadable / 抛异常 / 槽号不一致）、
     * 以及<b>真的数据截断</b>（{@code 库存输入迁移被截断} / {@code 流体库存输入迁移被截断} ——
     * 那是老存档的 32 条配置可能丢，用户 2026-11-01 明确要求<b>留 WARN</b>）。
     *
     * <p>为什么把级别放在纯核：级别要能被离线断言、也要能被负对照改红
     * （把这里改成 {@code true} ⇒ 自证 G7/G9 必须红）。机器那侧只做派发，不写死级别。
     *
     * <p>⚠️ <b>不要把它当编译期常量用</b>（{@code static final boolean} 会被 javac 内联）——
     * 调用点统一走 {@link #statusWarns()}。
     */
    private static final boolean STATUS_WARN = false;

    /**
     * 状态类日志的级别：{@code false} = INFO。调用点必须走这里，不要引用常量本身。
     *
     * <p>只有"真丢料"（{@link #lostLine}）与"真异常"（BUFNEG / unreadable / 抛异常 / 真数据截断）才允许是 WARN。
     */
    public static boolean statusWarns() {
        return STATUS_WARN;
    }

    /**
     * <b>日志节流窗口（毫秒）</b>：<b>两档都是 5 秒 1 行</b> —— WARN（真丢）与 INFO（只是证据）各算各的。
     *
     * <p>⚠️ <b>不要把它当编译期常量用</b>（{@code static final long} 会被 javac 内联进调用点，
     * 负对照就改不动它）—— 统一走 {@link #logWindowMs()}。
     */
    private static final long LOG_WINDOW_MS = 5_000L;

    /** 日志节流窗口（毫秒）：{@code 5000}。调用点必须走这里，不要引用常量本身。 */
    public static long logWindowMs() {
        return LOG_WINDOW_MS;
    }

    /**
     * <b>墙钟节流闸</b>（纯函数，无状态）：距上次打点满一个窗口才放行。
     *
     * <p>只要求"别刷屏"，不追求严格。放行时<b>调用方</b>负责把 {@code lastMs} 更新成 {@code nowMs}
     * （纯核不持有任何静态状态，这样它才能离线断言）。
     *
     * @param nowMs  当前墙钟（{@code System.currentTimeMillis()}）
     * @param lastMs 上一次打点的墙钟（{@code 0} = 从没打过 ⇒ 恒放行）
     */
    public static boolean windowDue(long nowMs, long lastMs) {
        return nowMs - lastMs >= logWindowMs();
    }

    /**
     * <b>「无限电」的那个返回值</b>（生产侧 {@code FreeEnergySource#extractAEPower} 返回的就是它）。
     *
     * <p>🔴 必须是"足够大"而不是"刚好够"：上游那一步是 {@code (long)(d * factor + 0.9)} ——
     * <b>double 截断成 long</b>。9.2e18 附近 double 的 ULP 是 1024，返回"刚好够"时会有**几个到几百件**
     * 被截掉（实测：{@code 9223372036854775000} 会少 <b>216</b> 件；{@code 2^53+1} 会少 1 件），
     * 于是 {@code min} 选中偏小的那个 ⇒ 一轮搬不完（不是丢量，余量留在 buffer 下轮再搬，但要多打一行日志）。
     * 返回"无限" ⇒ {@code d * factor} 溢出成 {@code Infinity} ⇒ 截断饱和成 {@code Long.MAX_VALUE}
     * ⇒ {@code min} 永远等于磁盘报价（= 原额，实测 <b>0 损失</b>）。
     *
     * <p>⚠️ <b>不要把它当编译期常量用</b>（{@code static final double} 会被 javac 内联进调用点，
     * 负对照改不动它）—— 统一走 {@link #freeEnergy()}。
     */
    private static final double FREE_ENERGY = Double.MAX_VALUE;

    private ShanhaiBufferFlush() {}

    /** 「无限电」的读数（单一真值来源；生产侧与离线自证都走这里，负对照才改得动）。 */
    public static double freeEnergy() {
        return FREE_ENERGY;
    }

    /** 当前的搬运节奏（tick）：{@code 1} = 每 tick 一轮。调用点必须走这里，不要引用常量本身。 */
    public static int cadenceTicks() {
        return CADENCE_TICKS;
    }

    /**
     * <b>正数饱和相加</b>（{@code a, b >= 0}；加不下就给 {@link Long#MAX_VALUE}，<b>永不为负</b>）。
     *
     * <p>给调用方累计窗口读数用（{@code bufflush cadence=… moved=} 那一栏）。为什么必须要它：
     * 一个 9.2e18 量级的条目连搬几轮就能把 {@code long} 加爆，加爆之后日志会显示成
     * <b>负数 = "本次入网量=-3.29e18"</b> —— 与"真的有负数被塞进网络"在日志上长得一模一样。
     * 读数必须非负，这是能不能一眼判死这件事的前提。
     */
    public static long satAdd(long a, long b) {
        final long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /**
     * <b>上游 {@code StorageHelper#poweredInsert} 的额度裁切</b>（逐行复刻字节码，离线可断言）。
     *
     * <pre>
     *   amount = inv.insert(key, amount, SIMULATE, src)                    // = diskAccepts
     *   amount &lt;= 0 ⇒ 0
     *   factor = max(1.0, key.getAmountPerOperation())                     // = amountPerOperation
     *   d      = source.extractAEPower(amount / factor, SIMULATE, CONFIG)   // = simulatedEnergy
     *   amount = min(amount, (long)(d * factor + 0.9))                     // ← 就是这一步
     * </pre>
     * 证据：{@code appliedenergistics2-forge-15.4.10.jar}，
     * {@code javap -c appeng.api.storage.StorageHelper} 偏移 27–93（SIMULATE 报价 / factor / d / min）。
     *
     * @param diskAccepts        磁盘 SIMULATE 报价（本盘恒等于请求额，永不满）
     * @param simulatedEnergy    {@code extractAEPower(..., SIMULATE, CONFIG)} 的返回值
     * @param amountPerOperation {@code AEKey#getAmountPerOperation()}（物品 1、流体 1000 量级）
     * @return 真正会入库的量
     */
    public static long poweredAmount(long diskAccepts, double simulatedEnergy, double amountPerOperation) {
        long amount = diskAccepts;
        if (amount <= 0L) {
            return 0L;
        }
        final double factor = Math.max(1.0, amountPerOperation);
        amount = Math.min(amount, (long) (simulatedEnergy * factor + 0.9));
        return amount <= 0L ? 0L : amount;
    }

    /**
     * <b>搬一条</b>：把 {@code amount} 件 {@code key} 送进网络。
     *
     * <p>生产侧实现就是 {@code StorageHelper.poweredInsert(我们的无限电 IEnergySource, 网格库存, key,
     * amount, actionSource, Actionable.MODULATE)} —— 一次 {@code amount ≤ Long.MAX}
     * （磁盘内部是 BigInteger 累加，单次 long 足够）。
     *
     * @return <b>真正入网的量</b>（0 = 这条一点都没搬动 ⇒ 条目原样留着）
     */
    @FunctionalInterface
    public interface Mover<K> {

        long move(K key, long amount);
    }

    /** <b>就地追一轮</b>的回调（返回这一轮真正入网的量；0 = 什么都没搬动）。 */
    @FunctionalInterface
    public interface Chaser {

        long chase();
    }

    // ================================================================ 🔴 入库闸门（负值绝不进网络）

    /**
     * <b>唯一允许触达入库的入口</b>：{@code amount <= 0} ⇒ <b>一次都不调</b> {@code mover}，直接 0。
     *
     * <h2>为什么这条闸门必须存在（实机读数驱动）</h2>
     * 用户那份日志里 {@code 本次入网量=-3288823178890723106} 是负数。两个后果里任一个都够糟：
     * <ul>
     *   <li>如果那个负值是<b>条目值</b>：{@code poweredInsert(…, 负数, …)} 在 AE2 里的语义是
     *       <b>从网络里反向抽走</b>（{@code insert} 一个负量 = {@code extract}）⇒ 直接扣别人的库存；</li>
     *   <li>如果只是<b>求和溢出</b>（本轮实测就是这个，见 {@link #Sweep#satMoved}）：日志读数会
     *       把"搬了多少"显示成一个负数，人和脚本都会读错。</li>
     * </ul>
     * ⇒ 两层各管一半：<b>这条闸门</b>保证任何 {@code ≤0} 的量都到不了 {@code poweredInsert}；
     * <b>饱和求和</b>保证日志读数永远不会因为溢出变成负数。
     *
     * <p>它<b>不是</b> {@link #sweep} 里那个 {@code amount <= 0 ⇒ continue} 的重复品：
     * <ul>
     *   <li>{@code sweep} 里那道是"跳过，连试都不试"，还会把负值记进 {@link Sweep#negatives}；</li>
     *   <li>这道是<b>贴着入库动作</b>的（生产侧 {@code sweepFree} 把它包在 {@code poweredInsert} 外面），
     *       万一纯核被人改坏/被第三种调用方绕过，它仍然能把 {@code ≤0} 挡在网络外面。</li>
     * </ul>
     *
     * @return 真正入网的量；{@code amount <= 0} 时恒为 0 且 {@code mover} 被调用 0 次
     */
    public static <K> long moveIfPositive(Mover<? super K> mover, K key, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        return mover == null ? 0L : mover.move(key, amount);
    }

    /**
     * 把一个 {@link Mover} 包成"带闸门"的版本（生产侧 {@code sweepFree} 用它包 {@code poweredInsert}）。
     *
     * <p>包装后的 mover 保证：<b>被包的那个 mover 只会收到 {@code amount > 0}</b>。
     * 离线自证第 1b 组就是拿一个"记录所有入参"的 mover 来断言这一条（负对照能抓）。
     */
    public static <K> Mover<K> guarded(Mover<? super K> mover) {
        return (key, amount) -> moveIfPositive(mover, key, amount);
    }

    // ================================================================ 节奏

    /**
     * <b>到点了吗</b>：距上次搬运是否已经够 {@code interval} tick。
     *
     * <p>用"不等"的写法照顾两个边界：{@code last == Long.MIN_VALUE}（从没搬过 ⇒ 立刻到点）
     * 与 tick 回绕/换维度后倒退（{@code now < last} ⇒ 也当作到点，宁可多搬一轮也别饿着）。
     *
     * <p>{@code interval = 1}（{@link #cadenceTicks()} 的当前值）⇒ 只要 {@code now > last} 就成立，
     * 也就是<b>每 tick 一轮</b>。
     */
    public static boolean due(long nowTick, long lastTick, int interval) {
        if (lastTick == Long.MIN_VALUE) {
            return true;
        }
        final int step = interval < 1 ? 1 : interval;
        if (nowTick < lastTick) {
            return true;
        }
        return nowTick - lastTick >= step;
    }

    // ================================================================ 读数

    /** 一轮搬运的读数（不可变；给日志与离线断言用）。 */
    public static final class Sweep {

        /** 本轮扫到并尝试搬的条目数（{@code amount > 0} 的那些；含失败）。 */
        public final int entries;

        /** 成功条数（{@code moved > 0}）。 */
        public final int ok;

        /** 失败条数（{@code moved <= 0}）。 */
        public final int fail;

        /** 本轮真正入网的总量（<b>饱和</b>：加不下就到 {@link Long#MAX_VALUE}，永不为负）。 */
        public final long moved;

        /** 本轮结束后 buffer 里还剩多少（<b>只累加正值</b>；饱和；负值单独由 {@link #negatives} 报）。 */
        public final long remaining;

        /** 负值条目数（不搬、不删、不归零，只报）。 */
        public final int negatives;

        /** 第一条负值的读数（{@code negatives == 0} 时是 null / 0）。 */
        public final Object negKey;
        public final long negValue;

        /** 是否因为连续失败提前收手。 */
        public final boolean aborted;

        /**
         * {@link #moved} 是不是"加爆了"的读数（真值 &gt; {@code Long.MAX_VALUE}，被截到这里）。
         *
         * <h2>为什么要这个标记（实机读数驱动）</h2>
         * 用户日志：{@code 本次入网量=-3288823178890723106}。那条 buffer 里有几个 9.2e18 量级的条目，
         * <b>正数相加</b>越过了 {@code Long.MAX_VALUE} ⇒ 有符号溢出 ⇒ 日志显示出负数。
         * 换算回真值：{@code 2^64 - 3288823178890723106 = 15157920894818828510}。
         * 但"显示成负数"与"真的有负数被搬进网络"在日志上<b>长得一模一样</b> —— 这正是最难查的那种假象
         * ⇒ 本版改成饱和并打上 {@link #satMoved} 标记，读数必须<b>非负</b>且<b>自带"爆了"的自白</b>。
         */
        public final boolean satMoved;

        /** {@link #remaining} 是否饱和（同上）。 */
        public final boolean satRemaining;

        private Sweep(int entries, int ok, int fail, long moved, long remaining,
                      int negatives, Object negKey, long negValue, boolean aborted,
                      boolean satMoved, boolean satRemaining) {
            this.entries = entries;
            this.ok = ok;
            this.fail = fail;
            this.moved = moved;
            this.remaining = remaining;
            this.negatives = negatives;
            this.negKey = negKey;
            this.negValue = negValue;
            this.aborted = aborted;
            this.satMoved = satMoved;
            this.satRemaining = satRemaining;
        }

        /** 饱和标记拼在读数后面（没有饱和 ⇒ 空串，正常日志一个字符都不多）。 */
        public String satSuffix() {
            if (!satMoved && !satRemaining) {
                return "";
            }
            final StringBuilder sb = new StringBuilder(" sat=");
            if (satMoved) {
                sb.append("moved");
            }
            if (satRemaining) {
                sb.append(satMoved ? "+remaining" : "remaining");
            }
            return sb.toString();
        }
    }

    /** 一单"写 buffer"的结果（{@link #writeWithChase} 的返回值）。 */
    public static final class ChaseWrite {

        /** 夹之前 buffer 里该键的现值。 */
        public final long existingBefore;
        /** 追一轮之后的现值。 */
        public final long existingAfter;
        /** 本来想写的量。 */
        public final long want;
        /** 实际写进去的量（<b>追写之后</b>的最终值）。 */
        public final long write;
        /** 夹掉多少（{@code want - write}，非负）—— <b>追写之后的最终读数</b>。 */
        public final long dropped;
        /** 追之前就丢了多少（{@code want - 第一次 write}，非负）；{@code dropped} 永远不会比它大。 */
        public final long droppedBefore;
        /** 这一单有没有触发过"就地追一轮"。 */
        public final boolean chased;
        /** 追那一轮真正入网的总量。 */
        public final long chaseMoved;

        private ChaseWrite(long existingBefore, long existingAfter, long want, long write,
                           long dropped, boolean chased, long chaseMoved, long droppedBefore) {
            this.existingBefore = existingBefore;
            this.existingAfter = existingAfter;
            this.want = want;
            this.write = write;
            this.dropped = dropped;
            this.droppedBefore = droppedBefore;
            this.chased = chased;
            this.chaseMoved = chaseMoved;
        }

        /**
         * <b>这一单最后是不是真丢了</b>（{@code dropped > 0}）—— <b>WARN 的唯一判据</b>（见 {@link #writeEvents}）。
         *
         * <p>⚠️ 判据是<b>追写之后</b>的 {@link #dropped}，不是 {@link #droppedBefore}：
         * 拿追之前的读数当判据，就会出现"救回来了也报 WARN"（这正是 2026-11-01 要治的那件事）。
         */
        public boolean lost() {
            return this.dropped > 0L;
        }

        /** 追写真正救回来的量（{@code droppedBefore - dropped}，非负；没追 / 没救着就是 0）。 */
        public long rescued() {
            final long r = this.droppedBefore - this.dropped;
            return r > 0L ? r : 0L;
        }
    }

    /** 一轮的<b>可变</b>账本（{@link #sweep} 内部用；饱和累加在这里做）。 */
    private static final class Round {

        int entries;
        int ok;
        int fail;
        int consecutiveFails;
        int negatives;
        long moved;
        boolean satMoved;
        long remaining;
        boolean satRemaining;
        Object negKey;
        long negValue;
        boolean aborted;

        void noteMoved(long amount) {
            final long sum = this.moved + amount;
            if (sum < 0L) {
                // 🔴 正数相加越界 ⇒ 饱和（绝不把负数当读数报出去）
                this.moved = Long.MAX_VALUE;
                this.satMoved = true;
            } else {
                this.moved = sum;
            }
        }

        void noteRemaining(long value) {
            final long sum = this.remaining + value;
            if (sum < 0L) {
                this.remaining = Long.MAX_VALUE;
                this.satRemaining = true;
            } else {
                this.remaining = sum;
            }
        }

        void noteNegative(Object key, long value) {
            this.negatives++;
            if (this.negKey == null) {
                this.negKey = key;
                this.negValue = value;
            }
        }

        Sweep toSweep() {
            return new Sweep(this.entries, this.ok, this.fail, this.moved, this.remaining,
                    this.negatives, this.negKey, this.negValue, this.aborted,
                    this.satMoved, this.satRemaining);
        }
    }

    /**
     * <b>搬一条的账</b>：{@code ≤0} 的条目<b>一次都不许交给 {@link Mover}</b>。
     *
     * <p>这里是<b>第一道</b>闸门（纯核里、贴着"要不要搬"的判断）；<b>第二道</b>是
     * {@link #moveIfPositive} / {@link #guarded}，在生产侧贴着 {@code poweredInsert} 包一层。
     * 两道是<b>故意分开</b>的：各自有一套离线断言，各自的负对照都能独立变红
     * （见 {@code FreeFlushSelfTest2} 第 1 组 / 第 1b 组）。
     *
     * <p>⚠️ 这里<b>不</b>用 Java 的 {@code assert}：MC 生产环境默认不开 {@code -ea}
     * ⇒ 断言会变成一句空语句，等于没有守卫。
     *
     * @return 真正入网的量（{@code ≤0} 与"搬不动"都返回 0）
     */
    private static <K> long moveOne(Round r, K key, long amount, Mover<? super K> mover) {
        // 🔴 负值/零值：不搬、不删、不归零；负的记一笔账（BUFNEG 那行要用）
        if (amount <= 0L) {
            if (amount < 0L) {
                r.noteNegative(key, amount);
            }
            return 0L;
        }
        long moved;
        try {
            moved = mover == null ? 0L : mover.move(key, amount);
        } catch (Throwable ignored) {
            // 搬一条失败绝不许把 tick 带崩：当"没搬动"处理，条目留着下轮再试
            moved = 0L;
        }
        r.entries++;
        if (moved > 0L) {
            r.ok++;
            r.noteMoved(moved);
            r.consecutiveFails = 0;
        } else {
            r.fail++;
            r.consecutiveFails++;
            if (r.consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                r.aborted = true;
            }
        }
        return moved;
    }

    /** 扫一遍 buffer 里<b>正值</b>的和（饱和）。 */
    private static <K> void scanRemaining(Object2LongOpenHashMap<K> buffer, Round r) {
        final ObjectIterator<Object2LongMap.Entry<K>> rest = buffer.object2LongEntrySet().fastIterator();
        while (rest.hasNext()) {
            final long value = rest.next().getLongValue();
            if (value > 0L) {
                r.noteRemaining(value);
            }
        }
    }

    /**
     * <b>搬一轮</b>：逐条 {@link Mover#move}，搬动了就减，减到 0 就移除。
     *
     * @param buffer     上游那张 buffer（真 {@code Object2LongOpenHashMap}）
     * @param mover      搬一条（生产侧是免电 {@code poweredInsert}）
     * @param maxEntries 本轮条目预算（{@code <= 0} ⇒ 用 {@link #MAX_ENTRIES}）
     */
    public static <K> Sweep sweep(Object2LongOpenHashMap<K> buffer, Mover<? super K> mover, int maxEntries) {
        return sweep(buffer, mover, maxEntries, null);
    }

    /**
     * <b>搬一轮（可指定优先键）</b>。
     *
     * <pre>
     *   1) priority != null 且 buffer 里有它 ⇒ 【先】搬它（它可能就排在 64 名之后，常规顺序永远轮不到）
     *   2) 再按 fastutil 的遍历顺序搬剩下的（跳过第 1 步已处理的那个键）
     *   3) 一轮最多 budget 条；连续 5 条搬不动就收手（aborted）
     *   4) 每条：value &lt;= 0 ⇒ 跳过（负数记账，一次都不许交给 mover）
     *            moved &gt; 0 ⇒ left = amount - moved；left &lt;= 0 ⇒ 移除；否则余量写回
     * </pre>
     *
     * @param priority 优先搬的键（{@code null} = 不指定，行为与三参数版<b>逐位相同</b>）
     */
    public static <K> Sweep sweep(Object2LongOpenHashMap<K> buffer, Mover<? super K> mover, int maxEntries,
                                  K priority) {
        if (buffer == null || buffer.isEmpty()) {
            return new Round().toSweep();
        }
        final int budget = maxEntries <= 0 ? MAX_ENTRIES : maxEntries;
        final Round r = new Round();

        // ---- 1) 优先键：先搬它（这一条就是"追着写"能治住 dropped 的原因）
        boolean priorityDone = false;
        if (priority != null && buffer.containsKey(priority)) {
            final long amount = buffer.getLong(priority);
            final long moved = moveOne(r, priority, amount, mover);
            if (amount > 0L && moved > 0L) {
                final long left = amount - moved;
                if (left <= 0L) {
                    buffer.removeLong(priority);
                } else {
                    // 🔴 只搬走一部分 ⇒ 余量写回，条目留着（一个都不许凭空消失）
                    buffer.put(priority, left);
                }
            }
            priorityDone = true;
        }

        // ---- 2) 常规顺序
        final ObjectIterator<Object2LongMap.Entry<K>> it = buffer.object2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            if (r.entries >= budget || r.aborted) {
                break;
            }
            final Object2LongMap.Entry<K> entry = it.next();
            final K key = entry.getKey();
            if (priorityDone && Objects.equals(key, priority)) {
                continue;
            }
            final long amount = entry.getLongValue();
            final long moved = moveOne(r, key, amount, mover);
            if (amount > 0L && moved > 0L) {
                final long left = amount - moved;
                if (left <= 0L) {
                    it.remove();
                } else {
                    entry.setValue(left);
                }
            }
        }

        scanRemaining(buffer, r);
        return r.toSweep();
    }

    /**
     * <b>🔴 写 buffer（带"追着写"）：写不进去 ⇒ 就地追一轮 ⇒ 再试写一次</b>。
     *
     * <pre>
     *   existing = buffer.getLong(key)
     *   write    = ShanhaiBufferClamp.allow(existing, want)
     *   write &lt; want ⇒ ① reporter.clamped(…)      ← 可选；⚠️ 这是【追之前】的读数（生产侧不传）
     *                  ② chase.chase()            ← 就地退一轮（生产侧 = 免电 sweep，且把这个键排最前）
     *                  ③ 追到东西了才重取 existing 并重算 write
     *   commit(buffer, key, want, write)
     *   → 返回账；日志由 {@link #writeEvents} 按【追之后】的 dropped 决定（见下方 §日志）
     * </pre>
     *
     * <h2>为什么"追完要重算"</h2>
     * {@code allow()} 的输入是 buffer 的<b>现量</b>；追一轮之后现量变了（可能整条被搬走了），
     * 拿旧 {@code existing} 算出来的 {@code write} 就作废了。重算一次是唯一正确的做法
     * （这也是实机 {@code dropped=1104} 能不能变 0 的关键）。
     *
     * <h2>为什么最多只追一次</h2>
     * 追一轮已经把所有能搬的都搬走了；同一 tick 里再追第二轮只会拿同样的局面再问一次网络
     * ⇒ 一次足够，且"绝不递归"（追写里的入库动作若再触发写入，闸门 {@code sweepFree} 那条
     * 不会再走回这里，见生产侧的重入标记）。
     *
     * <h2>🔴 打完这一单，日志由 {@link #writeEvents} 决定（不在这里打）</h2>
     * {@code reporter}（如果传了）拿到的必然是<b>追之前</b>的读数，而"到底丢没丢"要等追完才知道
     * ⇒ 所以本方法只<b>返回账</b>，由 {@link #writeEvents} 拿最终的 {@link ChaseWrite#dropped}
     * 决定"WARN 还是 INFO、打几行"。
     *
     * @param reporter 夹取<b>瞬间</b>的回调（可为 null）。⚠️ 生产侧产物路径<b>不传</b>：
     *                 它拿到的 {@code write} 会被随后的追写推翻，正是"看起来像丢、其实没丢"的来源
     *                 （见类注释 §六）；回灌路径（{@code commitAll}）没有追写，那一条仍用 reporter
     * @param chase    就地追一轮（可为 null = 不追，等价于老行为）
     */
    public static <K> ChaseWrite writeWithChase(Object2LongOpenHashMap<K> buffer, K key, long want,
                                                ShanhaiBufferClamp.Reporter<? super K> reporter,
                                                Chaser chase) {
        long existing = buffer.getLong(key);
        final long existingBefore = existing;
        long write = ShanhaiBufferClamp.allow(existing, want);
        // 追之前的丢弃量：只用于"救回来多少"的读数，绝不作为 WARN 的判据（判据见 ChaseWrite#lost）
        final long droppedBefore = want > write ? want - write : 0L;
        boolean chased = false;
        long chaseMoved = 0L;
        if (write < want) {
            if (reporter != null) {
                reporter.clamped(key, existing, want, write);
            }
            if (chase != null) {
                chased = true;
                try {
                    chaseMoved = chase.chase();
                } catch (Throwable ignored) {
                    // 追写失败绝不许把产出链带崩：当作没追到，照原 write 提交
                    chaseMoved = 0L;
                }
                if (chaseMoved > 0L) {
                    existing = buffer.getLong(key);
                    write = ShanhaiBufferClamp.allow(existing, want);
                }
            }
        }
        ShanhaiBufferClamp.commit(buffer, key, want, write);
        final long dropped = want > write ? want - write : 0L;
        return new ChaseWrite(existingBefore, existing, want, write, dropped, chased, chaseMoved,
                droppedBefore);
    }

    // ================================================================ 日志正文

    /**
     * <b>「搬了一轮」那行</b>（纯字符串，调用方原样交给日志）：
     * <pre>
     *   bufflush pos=&lt;维度@x,y,z&gt; dim=&lt;维度&gt; 条数=&lt;本轮尝试&gt; 成功=&lt;…&gt; 失败=&lt;…&gt; 本次入网量=&lt;…&gt; 剩余=&lt;…&gt; free=on
     * </pre>
     * <p>{@code free=on} 是这一族的固定标记：它说的是<b>这条路径不付电</b>（不是"这一轮免费"）。
     * <p>读数饱和时行尾会多一段 {@code sat=moved|remaining}（正常情况一个字符都不多）。
     *
     * @param pos 机器位置（本工程口径 {@code <维度id>@<x>,<y>,<z>}）
     * @param dim 维度 id（单独给一份，便于 {@code grep dim=}）
     */
    public static String logLine(String pos, String dim, Sweep s) {
        if (s == null) {
            return null;
        }
        return PREFIX + " pos=" + (pos == null ? "?" : pos)
                + " dim=" + (dim == null ? "?" : dim)
                + " 条数=" + s.entries
                + " 成功=" + s.ok
                + " 失败=" + s.fail
                + " 本次入网量=" + s.moved
                + " 剩余=" + s.remaining
                + " free=on"
                + s.satSuffix();
    }

    /**
     * <b>「每 tick 一轮」那行（5 秒 1 行）</b>：
     * <pre>
     *   bufflush cadence=everytick pos=&lt;维度@x,y,z&gt; rounds=&lt;窗口内跑了几轮&gt; entries=&lt;Σ尝试&gt;
     *           moved=&lt;Σ入网&gt; remaining=&lt;最后一轮的剩余&gt; upstreamMin=&lt;上游节奏&gt;
     * </pre>
     * <p>{@code rounds} 是这一族里唯一能<b>证明节奏</b>的读数：窗口 100 tick、{@code cadence=everytick} ⇒
     * 期望 {@code rounds≈100}（buffer 一直非空时）。
     *
     * @param cadenceTicks  当前节奏（{@code 1} ⇒ 写 {@code everytick}）
     * @param rounds        窗口内真正跑过的轮数
     * @param entries       窗口内 Σ 尝试条数
     * @param moved         窗口内 Σ 入网量（饱和）
     * @param remaining     窗口内最后一轮的剩余
     * @param upstreamMin   上游 {@code ConfigHolder.MEPatternOutputMin}（我们读它做对照，不再受它限速）
     */
    public static String cadenceLine(String pos, int cadenceTicks, long rounds, long entries,
                                     long moved, long remaining, int upstreamMin) {
        final String cadence = cadenceTicks <= 1 ? "everytick" : ("every" + cadenceTicks + "tick");
        return PREFIX + " cadence=" + cadence
                + " pos=" + (pos == null ? "?" : pos)
                + " rounds=" + rounds
                + " entries=" + entries
                + " moved=" + moved
                + " remaining=" + remaining
                + " upstreamMin=" + upstreamMin;
    }

    /**
     * <b>负值那行</b>（没有负值 ⇒ 返回 null，一次都不许误报）：
     * <pre>
     *   bufflush BUFNEG pos=&lt;维度@x,y,z&gt; key=&lt;键 id&gt; value=&lt;负值&gt; action=skip
     * </pre>
     *
     * <h2>为什么 {@code action=skip}（不删、不归零）</h2>
     * <ol>
     *   <li><b>删了就是"静默丢量"换了个地方发生</b>：那条负值是无符号回绕的残骸
     *       （真值 = 原值 + 2^64），把它抹掉等于把"曾经发生过一起回绕"的唯一证据也抹掉；</li>
     *   <li><b>跳过它不会卡住任何东西</b>：{@link #sweep} 对 {@code ≤0} 是 {@code continue}
     *       （不吃预算、不算失败、不进 {@code remaining}），后面的条目照搬；</li>
     *   <li><b>它还能自己长回来</b>：{@code ShanhaiBufferClamp#allow(existing<0, want)} 原样放行，
     *       {@code commit} 走 {@code addTo} ⇒ 继续写入会把它推回正数（无符号语义天然自愈）。
     *       删掉/归零就把这条路断了。</li>
     * </ol>
     * <p>格式按用户 2026-10 的口径（{@code pos / key / value} 三段）；
     * 负值条数不进这一行（多打一段只会让 grep 变难）。
     *
     * @param keyId 那条负值键的可读 id（调用方解析；{@code null} ⇒ 写 {@code ?}，但这一行照打 ——
     *              {@code value} 才是关键信息）
     */
    public static String negLine(String pos, Sweep s, String keyId) {
        if (s == null || s.negatives <= 0) {
            return null;
        }
        return PREFIX + " BUFNEG pos=" + (pos == null ? "?" : pos)
                + " key=" + (keyId == null ? "?" : keyId)
                + " value=" + s.negValue
                + " action=" + NEG_ACTION;
    }

    /**
     * <b>「真丢了」那行（WARN）</b>—— 只在<b>追写也救不回来</b>的时候才有这一行：
     * <pre>
     *   bufclamp key=… existing=&lt;夹之前&gt; want=&lt;本来要写&gt; write=&lt;最终写了&gt;
     *           dropped=&lt;最终丢了&gt; rescue=&lt;failed|none&gt; pos=&lt;维度@x,y,z&gt;
     * </pre>
     * <ul>
     *   <li>{@code rescue=failed}：追写<b>跑过</b>，但没救回来（{@code dropped > 0}）；</li>
     *   <li>{@code rescue=none}：这条路径<b>根本没有追写</b>（{@code chase == null}），
     *       {@code dropped} 就是净损失。</li>
     * </ul>
     *
     * <p>为什么 {@code dropped} 报的是<b>追之后</b>的读数：{@code dropped} 必须等于"真的没了多少"，
     * 否则这行和 {@code droppedBefore} 又是一对"看起来像丢、其实没丢"的读法。
     * <p>为什么行尾多了 {@code pos=}：真丢是要修的，不知道<b>哪台机</b>丢的就只能靠猜；
     * 前缀 {@code bufclamp key=} 没动（老习惯的 grep 照旧能捞到）。
     *
     * @return 没丢（{@code dropped <= 0}）或 {@code w == null} ⇒ {@code null}（一次都不许误报）
     */
    public static String lostLine(String pos, String keyId, ChaseWrite w) {
        if (w == null || !w.lost()) {
            return null;
        }
        return "bufclamp key=" + (keyId == null ? "?" : keyId)
                + " existing=" + w.existingBefore
                + " want=" + w.want
                + " write=" + w.write
                + " dropped=" + w.dropped
                + " rescue=" + (w.chased ? "failed" : "none")
                + " pos=" + (pos == null ? "?" : pos);
    }

    /**
     * <b>「写不进去 ⇒ 就地追一轮」那行</b>（{@code rescued=1} 就是"这一单救回来了"的直接读数）：
     * <pre>
     *   bufflush CHASE pos=… key=… existing=&lt;夹之前&gt; moved=&lt;追回来多少&gt; want=&lt;本来要写&gt;
     *           write=&lt;实际写了&gt; dropped=&lt;还丢了多少&gt; rescued=&lt;1|0&gt;
     * </pre>
     * <p>{@code rescued=1} ⇔ 最终 {@code dropped == 0}（整笔救回）；{@code rescued=0} ⇔ 还丢了
     * （这时同一时刻会有那行 WARN，{@code moved} 告诉你追写搬走了多少）。
     * <p>⚠️ 这一行是<b>证据</b>，不是告警 —— 它<b>不走 WARN</b>（用户口径：WARN 就是真的丢了）。
     */
    public static String chaseLine(String pos, String keyId, ChaseWrite w) {
        if (w == null || !w.chased) {
            return null;
        }
        return PREFIX + " CHASE pos=" + (pos == null ? "?" : pos)
                + " key=" + (keyId == null ? "?" : keyId)
                + " existing=" + w.existingBefore
                + " moved=" + w.chaseMoved
                + " want=" + w.want
                + " write=" + w.write
                + " dropped=" + w.dropped
                + " rescued=" + (w.dropped == 0L ? 1 : 0);
    }

    // ================================================================ 日志级别（唯一判据）

    /** <b>一条要打出去的日志</b>（级别 + 正文）。级别只有两档：{@code true} = WARN（真丢）。 */
    public static final class Event {

        /** {@code true} = WARN（<b>真的丢了</b>）；{@code false} = INFO（只是证据，不代表丢）。 */
        public final boolean warn;
        /** 正文（已含前缀；调用方原样交给日志）。 */
        public final String text;

        Event(boolean warn, String text) {
            this.warn = warn;
            this.text = text;
        }

        @Override
        public String toString() {
            return (this.warn ? "WARN " : "INFO ") + this.text;
        }
    }

    /**
     * <b>🔴 一单"写 buffer"该打哪些日志、什么级别</b>（用户 2026-11-01 拍板：<b>WARN 就是真的丢了</b>）。
     *
     * <pre>
     *   真丢了（dropped &gt; 0）   ⇒ [WARN bufclamp … rescue=failed] ＋（追过就）[INFO bufflush CHASE … rescued=0]
     *   救回来了（dropped == 0，且追过）⇒ [INFO bufflush CHASE … rescued=1]      ← 一条 WARN 都没有
     *   没被夹（大多数情况）     ⇒ []                                          ← 连字符串都不拼
     * </pre>
     *
     * <p><b>判据只有 {@link ChaseWrite#dropped}</b>（追写之后的最终读数）。拿 {@code droppedBefore}
     * 当判据就是旧口径的错：实机 11 条 {@code bufclamp} 里 10 条其实都救回来了，日志却是 WARN。
     *
     * <p>两个供给器（{@code keyIdOf} / {@code posOf}）是<b>惰性</b>的：99.99% 的产出走"没被夹"这条，
     * 一次 {@code AEKey#getId()} / 一次位置拼接都不会发生（热路径零代价）；取不到就给 {@code ?}。
     *
     * @param w        {@link #writeWithChase} 的返回值（{@code null} ⇒ 空表）
     * @param keyIdOf  键 → 可读 id（只在真要打行时才被调用；可为 null）
     * @param posOf    机器位置（同上；可为 null）
     * @return 按"WARN 在前、INFO 在后"排好的表（不可变；没事件时是空表）
     */
    public static List<Event> writeEvents(ChaseWrite w, Supplier<String> keyIdOf, Supplier<String> posOf) {
        if (w == null) {
            return List.of();
        }
        final boolean lost = w.lost();
        if (!lost && !w.chased) {
            // 没被夹 ⇒ 没丢、也没追 ⇒ 一条都不打（这里就 return，绝不碰下面两个供给器）
            return List.of();
        }
        final String keyId = safeGet(keyIdOf);
        final String pos = safeGet(posOf);
        final List<Event> out = new ArrayList<>(2);
        if (lost) {
            out.add(new Event(true, lostLine(pos, keyId, w)));
        }
        if (w.chased) {
            out.add(new Event(false, chaseLine(pos, keyId, w)));
        }
        return out;
    }

    /** 惰性供给器的取值：{@code null} / 抛异常 ⇒ {@code null}（打日志绝不许把产出链带崩）。 */
    private static String safeGet(Supplier<String> supplier) {
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * <b>一轮搬运之后的"心跳两行"</b>（心跳 {@link #logLine} ＋ 节奏 {@link #cadenceLine}），
     * 级别由 {@link #statusWarns()} 决定 —— <b>默认 INFO</b>（节拍不是丢料）。
     *
     * <p>为什么把这两行也做成 {@link Event}：用户要的口径是「<b>整份日志里 WARN 只剩真丢料 / 真异常</b>」，
     * 那么"心跳是什么级别"就必须和丢料一样，能在<b>同一张表</b>里被数出来、也能被负对照改红。
     * 生产侧（{@code StellarSampleAssemblerPartMachine#shanhaiTickFreeFlush}）拿到表后只做派发。
     *
     * <p>返回顺序固定：<b>心跳行在前、节奏行在后</b>（与旧日志顺序逐字一致）。
     *
     * @param sweep 这一轮的读数（{@code null} ⇒ 空表，调用方据此不打）
     */
    public static List<Event> heartbeatEvents(String pos, String dim, Sweep sweep, int cadenceTicks,
                                              long rounds, long entries, long moved, long remaining,
                                              int upstreamMin) {
        if (sweep == null) {
            return List.of();
        }
        final boolean warn = statusWarns();
        final List<Event> out = new ArrayList<>(2);
        out.add(new Event(warn, logLine(pos, dim, sweep)));
        out.add(new Event(warn, cadenceLine(pos, cadenceTicks, rounds, entries, moved, remaining, upstreamMin)));
        return out;
    }

    /**
     * <b>「心跳 / 状态类」日志的级别采样</b>：把一串已经拼好的正文交进来，返回带级别的表
     * （级别统一来自 {@link #statusWarns()}）。
     *
     * <p>⚠️ 生产侧那 4 处状态行（STOCKSUPPLY 上限 / SUPPLYVIEW 构造期 / STOCKCFG 未建 / 版面配置）
     * 在机器里用 slf4j 占位符拼正文，所以它们不经过本方法 —— 它们经过机器里那个
     * {@code shanhaiLogStatus(fmt, args)} 派发器，而派发器读的<b>就是</b> {@link #statusWarns()}。
     * 本方法存在只为让离线自证能把"状态类 ⇒ INFO"这件事<b>在真实的级别判据上</b>数出来。
     */
    public static List<Event> statusEvents(String... texts) {
        if (texts == null || texts.length == 0) {
            return List.of();
        }
        final boolean warn = statusWarns();
        final List<Event> out = new ArrayList<>(texts.length);
        for (String text : texts) {
            if (text != null) {
                out.add(new Event(warn, text));
            }
        }
        return out;
    }
}
