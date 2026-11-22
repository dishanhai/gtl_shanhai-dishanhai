package com.shanhai.add.guard.state;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · <b>「判据层是否还归我们」的运行期自愈判据</b>（2026-11-21）。
 *
 * <h2>这一条日志是唯一机器判据 —— 交付/验收就看它</h2>
 * <pre>
 *   [SHANHAI-GUARD] guard_judge_lost field=&lt;字段真值&gt; dead_or_dying=true action=reinstall reinstall=none reason=no_reinstall_mechanism side=client cause=death_screen openings=&lt;n&gt; probe=&lt;n&gt; judge_suppressed=&lt;n&gt; retry_in=1200t
 *   [SHANHAI-GUARD] guard_judge_dead_field field=&lt;字段真值&gt; side=server probe=&lt;n&gt;     ← 信息级，5 秒一次
 *   [SHANHAI-GUARD] guard_judge_unavailable field=NaN side=client probe=&lt;n&gt;            ← 字段读不到（accessor 没就绪）
 * </pre>
 *
 * <h2>🔴 {@code cause=} 只有五个取值，而且只准由读数推（2026-11-21 修正的一处自相矛盾）</h2>
 * <pre>
 *   现场原样（改之前）：guard_judge_lost field=20.0 dead_or_dying=true … cause=field_dead
 *                       ↑ field 明明是 20.0，却挂着邻居那一支的名字 ⇒ 判读的人只能判断"日志坏了"
 *   现在（{@link #causeOf}，唯一来源）：
 *     field 是 NaN                     ⇒ cause=unavailable      （读不到，测不了）
 *     field &lt;= 0                      ⇒ cause=field_dead       （只有这一支配叫 field_dead）
 *     field &gt; 0 且 dead_or_dying=true  ⇒ cause=judge_overridden （有血却判死 = 判据层被盖住）
 *     field &gt; 0 且 消费侧仍在提议开界面 ⇒ cause=death_screen     （界面这一侧没拦住）
 *     其余                            ⇒ cause=none
 *   ⇒ 离线断言：temp\p2-judge-cause\run.ps1（把下面「纯判据」段原样切出来跑）
 * </pre>
 *
 * <h2>判据为什么改成「消费侧」</h2>
 * <pre>
 *   以前问的是「isDeadOrDying() 的返回值对不对」—— 但对手在那两个方法上是 HEAD-cancel，
 *   我们连"它返回错值"这件事都能看出来却拦不住它（那是被调方法的战场）。
 *   现在问的是「消费点还在不在我们手里」：
 *     ① 客户端 {@code ScreenEvent.Opening} 里是否仍然出现 {@code DeathScreen}（受保护玩家）
 *        ⇒ 出现 = 消费侧没拦住（这一条最能说明"谁的实现赢了"）；
 *     ② 服务端没有界面可看 ⇒ 用「字段真值 > 0 而 isDeadOrDying() 仍为 true」这一对读数。
 * </pre>
 *
 * <h2>🔴 重装：全仓没有现成机制（所以只记日志，不许自己发明 ASM 重装）</h2>
 * <pre>
 *   grep -rn "reApplyMixins|reapply|reinstall|applyMixins|MixinEnvironment" 整个工程 ⇒ 0 命中
 *   ⇒ {@link #REINSTALL_MECHANISM_AVAILABLE} = false
 *   ⇒ 日志里 action=reinstall 后面紧跟 reinstall=none reason=no_reinstall_mechanism（不撒谎）
 *   ⇒ 并且每 {@value #RELOST_INTERVAL_TICKS} tick 再报一次（"还在丢"这件事必须重复可见，
 *      否则一条日志会被当成"当时的一次抖动"）。
 * </pre>
 *
 * <h2>节流（这条路径每 200 tick 才探一次，但读数会重复）</h2>
 * <pre>
 *   guard_judge_lost       同一侧首次立刻报；之后每 {@value #RELOST_INTERVAL_TICKS} tick 最多一条
 *   guard_judge_dead_field 同一侧每 {@value #DEAD_FIELD_INTERVAL_MS} ms 最多一条（信息级）
 *   guard_judge_unavailable 同一侧每 5 分钟最多一条（那是启动期一瞬间的状态，不该刷屏）
 * </pre>
 *
 * <h2>只读纪律</h2>
 * 本类<b>不写任何玩家字段</b>：只读 {@code DATA_HEALTH_ID}（{@link ShanhaiGuardValueLayer#readHealthField}）、
 * 只读一次 {@code isDeadOrDying()}、只数一个界面事件。补血/复位仍然只有
 * {@code ShanhaiGuardValueLayer#resetDeathFlow} 一份（本类不碰）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiJudgeAuthority {

    /** 日志前缀（与守卫同一套，历史 grep 口径不失效）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** 多少 tick 探一次（规格：200 tick）。 */
    public static final int PROBE_EVERY_TICKS = 200;

    /** 判据判定丢失后，隔多少 tick 再报一次（规格：1200 tick）。 */
    public static final int RELOST_INTERVAL_TICKS = 1_200;

    /** {@code guard_judge_dead_field} 的节流窗口（毫秒；规格：5 秒一次）。 */
    public static final long DEAD_FIELD_INTERVAL_MS = 5_000L;

    /** {@code guard_judge_unavailable} 的节流窗口（毫秒）。 */
    public static final long UNAVAILABLE_INTERVAL_MS = 5L * 60L * 1_000L;

    /**
     * 🔴 <b>有没有现成的"重装注入"机制</b>。
     *
     * <p>实测：全仓 grep {@code reApplyMixins|reapply|reinstall|applyMixins|MixinEnvironment} 命中 <b>0</b>。
     * 现成的 {@code ShanhaiHookSelfCheck} 只<b>判</b>注入在不在（并置 data_only 降级），<b>不重装</b>。
     * ⇒ 按任务书：<b>没有就只打日志 ＋ 每 1200 tick 再报一次</b>，不许自己发明 ASM 重装逻辑。
     */
    public static final boolean REINSTALL_MECHANISM_AVAILABLE = false;

    // 🔴 {@code Verdict} 那张枚举从这一版起搬进了下面的「纯判据」段：那一段被
    //    temp\p2-judge-cause\run.ps1 原样切出来单独编译，枚举不在里面就编不过（离线断言会当场红）。

    /** 服务端 tick 计数（只在 {@code ServerTickEvent.END} 上加）。 */
    private static int serverTicks;

    /** 服务端已经跑过多少 tick（给判定用，绝对值）。 */
    private static long serverTickNow;

    /** 一共探了几轮（两侧合计；进日志的 {@code probe=}）。 */
    private static final AtomicLong PROBES = new AtomicLong();

    /** 两侧各自的节流锚点（key = {@code "server"} / {@code "client"}）。 */
    private static final ConcurrentHashMap<String, Long> LAST_LOST_TICK = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Long> LAST_DEAD_FIELD_AT = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Long> LAST_UNAVAILABLE_AT = new ConcurrentHashMap<>();

    /** 两侧"死亡界面被提议"的累计次数（由消费侧观察者 {@code noteDeathScreenOpening} 累加，探针取走）。 */
    private static final ConcurrentHashMap<String, AtomicLong> DEATH_SCREEN_OPENINGS = new ConcurrentHashMap<>();

    private static final AtomicLong LOST_REPORTS = new AtomicLong();

    private ShanhaiJudgeAuthority() {}

    // ═══════════════════════════════ 纯判据（可离线验） ═══════════════════════════════

    // >>> JUDGE-PURE-BEGIN
    //     🔴 本段【只碰 java.*】：temp\p2-judge-cause\run.ps1 会把这两条标记之间的源码**原样切出来**，
    //     编成一个独立类跑断言（不 mock、不复制、不重写）。往这一段里加任何 MC／Forge 类型
    //     （LivingEntity／LOGGER／ShanhaiGuardValueLayer…）都会让那条离线断言当场编不过。

    /** {@code cause=} 的五个取值（**唯一**真源；日志里逐字出现这五个字面量）。 */
    public static final String CAUSE_FIELD_DEAD = "field_dead";

    public static final String CAUSE_JUDGE_OVERRIDDEN = "judge_overridden";

    public static final String CAUSE_UNAVAILABLE = "unavailable";

    public static final String CAUSE_DEATH_SCREEN = "death_screen";

    public static final String CAUSE_NONE = "none";

    /** 一次探针的结论（纯判据，可离线验）。 */
    public enum Verdict {

        /** 字段还有血、{@code isDeadOrDying()} 也说没死 ⇒ 一切正常（不记日志）。 */
        OK,

        /** 🔴 字段还有血，但 {@code isDeadOrDying()} 仍说"死了" ⇒ 注入没起作用（判据层丢了）。 */
        LOST,

        /** 字段真值本身 {@code <=0 / -∞}（真被打坏）⇒ 不算"丢失"，但要留一条信息级读数。 */
        DEAD_FIELD,

        /** 字段读不到（{@code DATA_HEALTH_ID} 反射没就绪 / 读取抛异常）⇒ 明确"测不了"，不当 0。 */
        UNAVAILABLE
    }

    /**
     * 🔴 <b>判定本体</b>（纯函数，无 MC 依赖）。
     *
     * <pre>
     *   fieldHealth 是 NaN                      ⇒ UNAVAILABLE（读不到 ≠ 0，不准当"死"）
     *   fieldHealth &gt; 0 且 deadOrDying = true   ⇒ LOST      （字段有血却判死 ⇒ 判据层不归我们）
     *   fieldHealth &lt;= 0 / -∞ / 0               ⇒ DEAD_FIELD（真被打坏 ⇒ 信息级，不算丢失）
     *   其余（字段 &gt; 0 且没判死）               ⇒ OK
     * </pre>
     *
     * @param fieldHealth  {@code DATA_HEALTH_ID} 的字段真值（读不到 = {@code Float.NaN}）
     * @param deadOrDying  {@code isDeadOrDying()} 这一次的返回值
     */
    public static Verdict decide(float fieldHealth, boolean deadOrDying) {
        if (Float.isNaN(fieldHealth)) {
            return Verdict.UNAVAILABLE;
        }
        if (!(fieldHealth > 0.0F)) {
            return Verdict.DEAD_FIELD;      // 0 / 负数 / -∞（NaN 已在上面挡掉）
        }
        return deadOrDying ? Verdict.LOST : Verdict.OK;
    }

    /**
     * 🔴 <b>{@code cause=} 的唯一来源</b>（纯函数）。
     *
     * <h2>为什么必须是纯函数、而且只准从读数推</h2>
     * <p>2026-11-21 现场出过一条自相矛盾的日志：
     * <pre>
     *   guard_judge_lost field=20.0 dead_or_dying=true … cause=field_dead
     * </pre>
     * 成因很蠢：{@code Verdict.LOST} 那一支<b>硬编码</b>了 {@code "field_dead"}（那是隔壁
     * {@code DEAD_FIELD} 这一支的名字）。{@code field=20.0} 与 {@code cause=field_dead}
     * 同时出现在一行里，判读的人只会得出"这日志本身坏了"这个结论。
     * ⇒ 从这一版起：<b>{@code cause} 一律由 {@code (field, dead_or_dying, openings)} 推出来</b>，
     * 调用点<b>不许</b>再传字符串（{@code reportLost} 也没有那个参数了）。
     *
     * <h2>映射（一一对应，不重叠）</h2>
     * <pre>
     *   field 是 NaN                     ⇒ unavailable    （读不到，测不了）
     *   field &lt;= 0                      ⇒ field_dead     （只有这一支配叫 field_dead）
     *   field &gt; 0 且 dead_or_dying=true  ⇒ judge_overridden（有血却判死 = 判据层被盖住）
     *   field &gt; 0 且 消费侧仍在提议开界面 ⇒ death_screen   （界面这一侧没拦住）
     *   其余                            ⇒ none
     * </pre>
     * <p>顺序是有意的：{@code field<=0} 优先于 {@code dead}（血都没了，判死不算"被盖住"）；
     * {@code dead} 优先于 {@code death_screen}（字段判据更硬，且 {@code openings=} 本来就在行里）。
     *
     * @param fieldHealth {@code DATA_HEALTH_ID} 的字段真值（读不到 = {@code Float.NaN}）
     * @param deadOrDying {@code isDeadOrDying()} 这一次的返回值
     * @param openings    这一拍消费侧"提议开死亡界面"的次数（{@code <=0} = 没有）
     * @return 五个之一（永不为 {@code null}）
     */
    public static String causeOf(float fieldHealth, boolean deadOrDying, long openings) {
        if (Float.isNaN(fieldHealth)) {
            return CAUSE_UNAVAILABLE;
        }
        if (!(fieldHealth > 0.0F)) {
            return CAUSE_FIELD_DEAD;        // 🔴 只有 field<=0 才允许这个名字
        }
        if (deadOrDying) {
            return CAUSE_JUDGE_OVERRIDDEN;
        }
        if (openings > 0L) {
            return CAUSE_DEATH_SCREEN;
        }
        return CAUSE_NONE;
    }

    // <<< JUDGE-PURE-END

    // ═══════════════════════════════ 入口①：服务端每 200 tick ═══════════════════════════════

    /**
     * 服务端 tick 末：只数 tick，每 {@value #PROBE_EVERY_TICKS} tick 探<b>一个</b>受保护玩家。
     *
     * <p>🔴 探针自己坏了<b>绝不许</b>影响任何拦截：整段 try/catch，失败只吞掉。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        serverTickNow++;
        if (++serverTicks < PROBE_EVERY_TICKS) {
            return;
        }
        serverTicks = 0;
        try {
            probeServer(event.getServer());
        } catch (Throwable ignored) {
            // 自愈判据坏了不许影响游戏（下一轮还会来）
        }
    }

    /**
     * 对<b>第一个</b>受保护玩家做一次探针（规格点名"对一个受保护玩家"）。
     *
     * <p>为什么只探一个：这条判据问的是"判据层还归不归我们"，那是<b>全局属性</b>，
     * 不是每个玩家一份；每 200 tick 扫一遍全部玩家纯属白烧。
     */
    public static void probeServer(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null) {
                continue;
            }
            if (!isGuardedQuietly(player)) {
                continue;
            }
            probe("server", player, serverTickNow);
            return;
        }
    }

    // ═══════════════════════════════ 入口②：一次探针（两侧共用同一份判据） ═══════════════════════════════

    /**
     * 一次探针：读两个读数 → 判 → 该报就报。
     *
     * @param side    {@code "server"} / {@code "client"}（只进日志与节流 key）
     * @param entity  受保护玩家（调用方已判；本方法再判一次也不贵）
     * @param tickNow 这一侧的绝对 tick（节流用；客户端用自己数的 tick）
     */
    public static void probe(String side, LivingEntity entity, long tickNow) {
        if (side == null || entity == null) {
            return;
        }
        final String key = side;
        PROBES.incrementAndGet();
        final float field = ShanhaiGuardValueLayer.readHealthField(entity);
        boolean dead = false;
        try {
            dead = entity.isDeadOrDying();      // 这一次读数本身就可能是"被别的注入强制成 true"的那个
        } catch (Throwable ignored) {
            // 读侧抛异常 ⇒ 保持 false，下面的判据会落到 DEAD_FIELD / OK
        }
        final Verdict verdict = decide(field, dead);
        final long openings = drainDeathScreenOpenings(key);

        // 🔴 分支顺序 = cause 的来源（与 {@link #causeOf} 一一对应；调用点不再手写 cause）
        //   ⚠️ 两条【互不排斥】：字段被打坏(dead_field) 与 有人还在提议开死亡界面(lost) 可以同拍发生，
        //      所以这里【不 return】—— 少打一行 lost 就等于把"消费侧没拦住"这条最硬的证据丢了。
        //   ① 读不到字段 ⇒ 明确"测不了"（只有 NaN 才算 unavailable）
        if (verdict == Verdict.UNAVAILABLE) {
            reportUnavailable(side);
            return;
        }
        //   ② 字段真值 <= 0 ⇒ 字段本身被打坏（只有这一支叫 field_dead，走另一条信息级日志）
        if (verdict == Verdict.DEAD_FIELD) {
            reportDeadField(side, field);
        }
        //   ③ 字段 > 0（或字段已坏）时：有血却判死 ⇒ judge_overridden；
        //      字段没坏但界面还在被提议 ⇒ death_screen；字段已坏 ⇒ field_dead。
        if (dead || openings > 0L) {
            reportLost(side, entity, field, dead, tickNow, openings);
        }
    }

    // ═══════════════════════════════ 消费侧观察（客户端 ScreenEvent.Opening 调） ═══════════════════════════════

    /**
     * 记一次"有人要给受保护玩家开死亡界面"（观察者只数、不拦）。
     *
     * <p>调用方是客户端的 {@code ScreenEvent.Opening} 监听（{@code receiveCanceled = true}）：
     * <b>被别人取消的那些也算</b> —— 我们要的是"消费侧有没有被提议过死"，不是"最后拦没拦住"。
     */
    public static void noteDeathScreenOpening(String side) {
        try {
            DEATH_SCREEN_OPENINGS
                    .computeIfAbsent(side == null ? "client" : side, k -> new AtomicLong())
                    .incrementAndGet();
        } catch (Throwable ignored) {
            // 计数坏了不许影响任何界面行为
        }
    }

    /** 取走一侧的计数（探针每 200 tick 取一次；取走后归零，下次再数）。 */
    private static long drainDeathScreenOpenings(String side) {
        final AtomicLong counter = DEATH_SCREEN_OPENINGS.get(side);
        return counter == null ? 0L : counter.getAndSet(0L);
    }

    // ═══════════════════════════════ 三条日志（逐字口径） ═══════════════════════════════

    /**
     * 🔴 {@code guard_judge_lost} —— <b>交付/验收唯一要看的那一行</b>。
     *
     * <p>字段顺序是规格逐字：{@code field=} / {@code dead_or_dying=} / {@code action=reinstall}。
     * 后面追加的都是<b>事实字段</b>（{@code reinstall=none} / {@code reason=} / {@code side=} /
     * {@code cause=} / {@code openings=} / {@code probe=} / {@code judge_suppressed=} / {@code retry_in=}）——
     * 不撒谎地说清"谁在丢、丢几次、我们有没有真的重装"。
     *
     * <p>🔴 {@code cause=} <b>不再由调用方传入</b>（2026-11-21 修正）：它由
     * {@link #causeOf(float, boolean, long)} 从<b>同一批读数</b>推出来 —— 这样"日志里
     * {@code field=20.0} 旁边写着 {@code cause=field_dead}"这种自相矛盾在结构上就不可能再出现。
     */
    private static void reportLost(String side, LivingEntity entity, float field, boolean dead,
                                   long tickNow, long openings) {
        final Long last = LAST_LOST_TICK.get(side);
        if (last != null && tickNow - last.longValue() < RELOST_INTERVAL_TICKS) {
            return;                              // 同一侧 1200 tick 内已经报过 ⇒ 不刷屏
        }
        LAST_LOST_TICK.put(side, Long.valueOf(tickNow));
        LOST_REPORTS.incrementAndGet();
        final String cause = causeOf(field, dead, openings);      // ← 唯一来源（见方法注释）
        try {
            ShanhaiMod.LOGGER.warn(PREFIX + "guard_judge_lost"
                    + " field=" + field
                    + " dead_or_dying=" + dead
                    + " action=reinstall"
                    + " reinstall=" + (REINSTALL_MECHANISM_AVAILABLE ? "attempted" : "none")
                    + " reason=" + (REINSTALL_MECHANISM_AVAILABLE ? "attempted" : "no_reinstall_mechanism")
                    + " side=" + side
                    + " cause=" + cause
                    + " openings=" + openings
                    + " probe=" + PROBES.get()
                    + " lost_reports=" + LOST_REPORTS.get()
                    + " " + safeJudgeStatusLine()
                    + " retry_in=" + RELOST_INTERVAL_TICKS + "t");
        } catch (Throwable ignored) {
            // 日志失败不许影响游戏
        }
    }

    /** {@code guard_judge_dead_field}：字段真值本身已经死了（真被打坏，不是判据丢了）。信息级。 */
    private static void reportDeadField(String side, float field) {
        final long now = System.currentTimeMillis();
        final Long last = LAST_DEAD_FIELD_AT.get(side);
        if (last != null && now - last.longValue() < DEAD_FIELD_INTERVAL_MS) {
            return;                              // 5 秒内已经报过
        }
        LAST_DEAD_FIELD_AT.put(side, Long.valueOf(now));
        try {
            ShanhaiMod.LOGGER.info(PREFIX + "guard_judge_dead_field field=" + field
                    + " side=" + side + " probe=" + PROBES.get()
                    + " note=field_le_0_not_judge_loss");
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /** {@code guard_judge_unavailable}：字段读不到（第二通道没就绪 / 读取抛异常）。5 分钟一条。 */
    private static void reportUnavailable(String side) {
        final long now = System.currentTimeMillis();
        final Long last = LAST_UNAVAILABLE_AT.get(side);
        if (last != null && now - last.longValue() < UNAVAILABLE_INTERVAL_MS) {
            return;
        }
        LAST_UNAVAILABLE_AT.put(side, Long.valueOf(now));
        try {
            ShanhaiMod.LOGGER.warn(PREFIX + "guard_judge_unavailable field=NaN side=" + side
                    + " probe=" + PROBES.get() + " reason=field_unreadable");
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ═══════════════════════════════ 读数（命令 / 报告用） ═══════════════════════════════

    /** 一行读数（命令 / 报告用）。 */
    public static String statusLine() {
        return "judge_probes=" + PROBES.get()
                + " judge_lost_reports=" + LOST_REPORTS.get()
                + " reinstall_mechanism=" + (REINSTALL_MECHANISM_AVAILABLE ? "present" : "none")
                + " probe_every=" + PROBE_EVERY_TICKS + "t"
                + " retry_in=" + RELOST_INTERVAL_TICKS + "t"
                + " " + safeJudgeStatusLine();
    }

    /** {@code isGuarded} 包一层：探针不许因为它的异常而中断。 */
    private static boolean isGuardedQuietly(ServerPlayer player) {
        try {
            return ShanhaiGuardEvents.isGuarded(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 判据层自己的读数（本体的计数器）；本体抛异常也不许打断日志。 */
    private static String safeJudgeStatusLine() {
        try {
            return ShanhaiGuardValueLayer.judgeStatusLine();
        } catch (Throwable t) {
            return "judge_suppressed=?";
        }
    }
}
