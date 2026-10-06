package com.shanhai.machine.wildcard;

import com.mojang.logging.LogUtils;
import com.shanhai.common.log.ShanhaiLogThrottle;
import org.slf4j.Logger;

/**
 * <b>「自动倍率」的唯一驱动</b> —— 探测控制器、算生效值、限流打日志、并按变化落地。
 *
 * <h2>🔴 本类是「不许出现第二份实现」那条要求的落点（用户 2026-10-04 逐字）</h2>
 * 两台机器（本工程那台仓室 / 上游的「超级样板总成」）各自持有一个本类实例，
 * <b>判定、文案、日志全在这一份代码里</b>；机器之间的差异全部由
 * {@link AutoForgeMultiplierHost} 那几个「谁去读控制器 / 生效值怎么落地」的方法吸收。
 *
 * <h2>语义（2026-10-04 两轮口径的最终版）</h2>
 * <pre>
 *   自动开 ⇒ 生效倍率 = 从本仓室所在多方块的【控制器】上读到的额外产出倍率
 *                        （向下取整、夹到 [1,30]）；
 *            读不到 ⇒ 按「这台机器不给额外产出」算 = ×1（用户修正④）；
 *   自动关 ⇒ 手填值。
 * </pre>
 * 四种来源（用户修正① + 追加 A）：控制器（伪神之锻炉 / 原始终焉引擎）
 * → 模块（伪神之锻炉模块 / 原始终焉引擎模块）→ 都不是 ⇒ ×1。
 *
 * <h2>周期复读（为什么不是只读一次）</h2>
 * 伪神之锻炉那个数随它自己的运行时长从 1 爬到 15（4 小时），引擎/模块那个数随专属槽变化
 * ⇒ 只在开面板时读一次的话，玩家会看到「开关开着、数却永远停在旧值」。
 * 三条安全阀：探测每 {@value #SYNC_INTERVAL_TICKS} tick（5 秒）、
 * <b>只有生效值真的变了才动手</b>、动过手之后 {@value #SYNC_AFTER_APPLY_TICKS} tick 内不再动。
 */
public final class ShanhaiAutoForgeMultiplierDriver {

    /** 探测间隔（tick）：5 秒一次 —— 上游那个数十几分钟才变一档，绰绰有余。 */
    private static final int SYNC_INTERVAL_TICKS = 100;

    /** 落地过一次之后的冷却（tick）：10 秒内最多动一次（防震荡的安全阀）。 */
    private static final int SYNC_AFTER_APPLY_TICKS = 200;

    /** 「读不到」的复述间隔（毫秒）：只有「伪神锻但签名变了」这一档会按它复述。 */
    private static final long FALLBACK_REPEAT_MILLIS = 300_000L;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AutoForgeMultiplierHost host;

    /**
     * 读到的 / 退回的日志闸门：<b>同一形态只落一次</b>
     * （形态 = 「读到 &lt;来源&gt; N」或「无额外产出·原因」）。
     */
    private final ShanhaiLogThrottle.Gate gate = new ShanhaiLogThrottle.Gate();

    /** 节流计数（tick）：> 0 时这一 tick 不探测。 */
    private int cooldown;

    public ShanhaiAutoForgeMultiplierDriver(AutoForgeMultiplierHost host) {
        this.host = host;
    }

    // ═════════════════════════════ 面板那两行（唯一实现） ═════════════════════════════

    /** 面板第一行（手填 N / 读到 N / 读不到 -&gt; ×1）。 */
    public String text() {
        return ShanhaiAutoForgeMultiplier.describe(
                this.host.isAutoForgeMultiplierEnabled(),
                this.host.shanhaiAutoForgeMultiplierRead(),
                this.host.getForgePatternMultiplier(),
                ShanhaiForgePatternMode.MIN_MULTIPLIER,
                ShanhaiForgePatternMode.MAX_MULTIPLIER);
    }

    /** 面板第二行（来源 / 读不到的原因）。 */
    public String reasonText() {
        return ShanhaiAutoForgeMultiplier.reasonLine(
                this.host.isAutoForgeMultiplierEnabled(),
                this.host.shanhaiAutoForgeMultiplierRead(),
                ShanhaiForgePatternMode.MIN_MULTIPLIER,
                ShanhaiForgePatternMode.MAX_MULTIPLIER,
                this.host.shanhaiAutoForgeMultiplierNote());
    }

    /** 此刻应当生效的倍率（全机唯一口径）。 */
    public int effective() {
        return ShanhaiAutoForgeMultiplier.effective(
                this.host.isAutoForgeMultiplierEnabled(),
                this.host.shanhaiAutoForgeMultiplierRead(),
                this.host.getForgePatternMultiplier(),
                ShanhaiForgePatternMode.MIN_MULTIPLIER,
                ShanhaiForgePatternMode.MAX_MULTIPLIER);
    }

    // ═════════════════════════════ 唯一写点：刷新读数 ═════════════════════════════

    /**
     * <b>把控制器/模块上的倍率刷新进同步字段</b>（并打限流日志）。这是唯一的写点。
     *
     * <p>自动关着时不探测：读数清成 {@code NO_READ}、标注写
     * {@link ShanhaiAutoForgeMultiplier#REASON_AUTO_OFF} ⇒ 面板显示「手动·倍率 N」，
     * 生效倍率随之等于手填值。
     *
     * <p>⚠️ 客户端侧（{@code isRemote()}）一个字都不动：服务端那份才是权威。
     *
     * @return 本次的读取结果
     */
    public ShanhaiForgeMultiplierReader.Result refresh() {
        if (this.host.shanhaiIsRemote()) {
            return ShanhaiForgeMultiplierReader.Result.none(this.host.shanhaiAutoForgeMultiplierNote());
        }
        final ShanhaiForgeMultiplierReader.Result result;
        if (this.host.isAutoForgeMultiplierEnabled()) {
            result = this.probe();
        } else {
            result = ShanhaiForgeMultiplierReader.Result.none(ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF);
        }
        this.host.shanhaiStoreAutoForgeMultiplier(result.value, result.note);
        this.log(result);
        return result;
    }

    // ═════════════════════════════ 周期复读 + 按变化落地 ═════════════════════════════

    /**
     * 每 tick 调一次（内部自带节流）：<b>变了才动手</b>。
     *
     * <p>⚠️ {@code before} 必须在 {@link #refresh()} <b>之前</b>取 —— 刷新会把读数写进同步字段，
     * 而本工程那台的「已生效值」正是从那个字段算出来的，取晚了就永远看不到变化。
     */
    public void tick() {
        if (this.host.shanhaiIsRemote() || !this.host.isAutoForgeMultiplierEnabled()) {
            return;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }
        this.cooldown = SYNC_INTERVAL_TICKS;
        final int before = this.host.shanhaiAppliedForgeMultiplier();
        this.refresh();
        final int after = this.effective();
        if (after == before) {
            return;     // 没变 ⇒ 一个字节都不动
        }
        this.cooldown = SYNC_AFTER_APPLY_TICKS;
        LOGGER.info("{} 自动倍率：生效倍率 {} → {} —— 按新值落地一次（与玩家手改倍率同一条路径）",
                this.host.shanhaiLogTag(), before, after);
        this.host.shanhaiApplyForgeMultiplier(after);
    }

    // ═════════════════════════════ 探测（四种来源的顺序） ═════════════════════════════

    /**
     * 探测本仓室所在多方块的控制器；<b>逐个试</b>，谁能读出额外产出倍率就用谁。
     *
     * <p>四种来源与顺序见 {@link ShanhaiForgeMultiplierReader}：
     * 控制器（伪神之锻炉 → 原始终焉引擎）→ 模块（伪神之锻炉模块 → 原始终焉引擎模块）→ ×1。
     */
    private ShanhaiForgeMultiplierReader.Result probe() {
        final java.util.List<?> controllers = this.host.shanhaiControllers();
        if (controllers == null || controllers.isEmpty()) {
            return ShanhaiForgeMultiplierReader.Result.none(
                    ShanhaiAutoForgeMultiplier.REASON_NO_CONTROLLER);
        }
        ShanhaiForgeMultiplierReader.Result firstFailure = null;
        for (Object controller : controllers) {
            if (controller == null) {
                continue;
            }
            final ShanhaiForgeMultiplierReader.Result result = ShanhaiForgeMultiplierReader.read(
                    controller,
                    ShanhaiForgePatternMode.MIN_MULTIPLIER,
                    ShanhaiForgePatternMode.MAX_MULTIPLIER);
            if (result.value != ShanhaiAutoForgeMultiplier.NO_READ) {
                return result;
            }
            if (firstFailure == null) {
                firstFailure = result;
            }
        }
        return firstFailure != null
                ? firstFailure
                : ShanhaiForgeMultiplierReader.Result.none(ShanhaiAutoForgeMultiplier.REASON_NO_CONTROLLER);
    }

    // ═════════════════════════════ 限流日志 ═════════════════════════════

    /**
     * 自动倍率的日志（<b>限流</b>）：三态可分、<b>不回刷屏</b>。
     * <ul>
     *   <li><b>自动关着</b> ⇒ 一行 INFO（同形只一次）——这一档手填值才算数；</li>
     *   <li><b>读到了</b> ⇒ 一行 INFO，形态串含<b>来源 + 数值</b> ⇒ 每变一档一条；</li>
     *   <li><b>读不到 ⇒ ×1</b> ⇒ 一行 INFO，形态串含<b>原因</b>；同因只打一次，
     *       静默次数附在下一条真正落盘的行末；</li>
     *   <li>🔴 <b>「伪神锻但签名变了」</b> ⇒ 这一档是 ERROR 并按 5 分钟复述。</li>
     * </ul>
     * <p>⚠️ 措辞里<b>不出现「退回手填」</b>：自动开着时手填值根本不参与运算。
     */
    private void log(ShanhaiForgeMultiplierReader.Result result) {
        final long now = System.currentTimeMillis();
        final int min = ShanhaiForgePatternMode.MIN_MULTIPLIER;
        final int max = ShanhaiForgePatternMode.MAX_MULTIPLIER;
        final String tag = this.host.shanhaiLogTag();
        if (!this.host.isAutoForgeMultiplierEnabled()) {
            if (this.gate.changed("自动倍率·关闭", false, now, 0L)) {
                LOGGER.info("{} 自动倍率：开关是【关】的 ⇒ 生效倍率 = 手填值 {}（不再读控制器）{}",
                        tag,
                        ShanhaiAutoForgeMultiplier.clamp(this.host.getForgePatternMultiplier(), min, max),
                        this.suppressedSuffix());
            }
            return;
        }
        if (result.value != ShanhaiAutoForgeMultiplier.NO_READ) {
            if (this.gate.changed("自动倍率·读到 " + result.note + " " + result.value, false, now, 0L)) {
                LOGGER.info("{} 自动倍率：从【{}】读到 {}（已向下取整并夹到 {}-{}）；生效倍率 = {}"
                                + "（手填值 {} 在自动开着时不参与）{}",
                        tag, result.note, result.value, min, max, result.value,
                        ShanhaiAutoForgeMultiplier.clamp(this.host.getForgePatternMultiplier(), min, max),
                        this.suppressedSuffix());
            }
            return;
        }
        final boolean upstreamBroken = ShanhaiAutoForgeMultiplier.REASON_SIGNATURE_BROKEN.equals(result.note);
        if (!this.gate.changed("自动倍率·无额外产出·" + result.note, upstreamBroken, now, FALLBACK_REPEAT_MILLIS)) {
            return;
        }
        final String line = "{} 自动倍率：本次【读不到】额外产出倍率（原因：{}）⇒ 按「无额外产出」算，"
                + "生效倍率 = ×{}（夹到 {}-{}）。⚠️ 手填值 {} 在自动开着时【不参与运算】。"
                + "本次不是静默失效：本条就是证据。{}";
        final Object[] args = {tag, result.note,
                ShanhaiAutoForgeMultiplier.clamp(ShanhaiAutoForgeMultiplier.NO_EXTRA, min, max),
                min, max,
                ShanhaiAutoForgeMultiplier.clamp(this.host.getForgePatternMultiplier(), min, max),
                this.suppressedSuffix()};
        if (upstreamBroken) {
            LOGGER.error(line, args);
        } else {
            LOGGER.info(line, args);
        }
    }

    /**
     * 「自上次落盘以来同形重复 N 次已静默」—— 有静默过才附在行末。
     *
     * <p>用 {@code takeSuppressed()}（取走并清零）而不是 {@code suppressed()}：
     * 后者是跨形态累计值，写成「同形重复 N 次」会是一条不准的数。
     */
    private String suppressedSuffix() {
        final long n = this.gate.takeSuppressed();
        return n <= 0L ? "" : ("（自上次落盘以来同形重复 " + n + " 次已静默）");
    }
}
