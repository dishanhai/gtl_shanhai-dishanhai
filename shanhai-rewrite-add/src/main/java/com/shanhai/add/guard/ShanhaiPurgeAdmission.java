package com.shanhai.add.guard;

import com.shanhai.common.guard.ShanhaiGuardCore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 山海 · <b>攻击端「要不要抹」的纯判据核 ＋ 每类型条数上限 ＋ 播报合并</b>（纯 JDK，零 Forge / 零 Minecraft）。
 *
 * <h2>它为什么存在（2026-11-01 实测）</h2>
 * 一局日志 191 行<b>「拦截 抹除」</b>、186 次播报，每秒一行；被抹的类型里 82 只是发光鱿鱼、18 只鱿鱼、……
 * —— 全是<b>没打中 / 被动生物</b>，而且"同一类一秒抹几十只"，像一眼清场。
 * 于是判据必须变成<b>能被机器逐条验</b>的东西：把"要不要抹"抽成 {@link #judge(Attack)}、
 * 把"同一类型每秒最多几只"抽成 {@link Quota}、把"每 5 秒最多一行、行内按类型合并 {@code 类型×N}"抽成
 * {@link Announcer}，三者都<b>零 MC 依赖</b> ⇒ 可以空 classpath 单跑
 * （离线自证在 {@code temp\purge-tune\selfcheck\}）。
 *
 * <h2>🔴 判据口径（用户原话「只抹真的造成过伤害的攻击者」）</h2>
 * <b>不能</b>拿"是否真的掉了血"当判据 —— 我们的守卫会把伤害全部取消，那样<b>永远都不会抹</b>。
 * 所以近似成「<b>这一次攻击如果没被取消，本来会不会疼</b>」：
 * <pre>
 *   amount &gt; 0（伤害源本身有数值，不是 0 伤害的"碰一下"）
 *   ＆（攻击者把受害者当目标（{@code Mob#getTarget() == victim}）或 攻击者是敌对实体）
 * </pre>
 * 「敌对实体」由游戏侧换算成一个 boolean 喂进来（本类不认任何 mod 名/类名/id）：
 * {@code attacker instanceof Enemy}（= {@code Monster} / 末影龙 / 凋灵 / 按惯例实现 {@code Enemy} 的模组敌对生物）
 * 或 {@code attacker.getType().getCategory() == MobCategory.MONSTER}。
 *
 * <h2>⚠️ 与"字面原话"的唯一分叉（待用户确认）</h2>
 * 原话是「{@code Mob} 但 {@code getTarget() != 受害者} ⇒ 不抹」。<b>逐字</b>执行的话，
 * 贴脸自爆的苦力怕（自爆时没有 target）与"顺手打了玩家一下的僵尸"都会被放过 ⇒ 与
 * 「只抹真的造成过伤害的攻击者」自相矛盾。所以这里留一条<b>敌对豁免</b>：
 * <pre>
 *   Mob ＋ 目标不是受害者 ＋ 不是敌对 ⇒ 不抹（{@link Reason#PASSIVE}；专治发光鱿鱼 / 鱿鱼）
 * </pre>
 *
 * <h2>三条既有语义一个字都没改</h2>
 * 玩家攻击者不处理（{@link Reason#PLAYER}）／召唤物与投射物不处理（{@link Reason#SUMMON}）／
 * 只抹攻击者本人 —— 本类只是<b>多加</b> {@link Reason#PASSIVE}、{@link Reason#ZERO_AMOUNT}、
 * {@link Reason#DUP} 三张闸门与一张配额表。
 */
public final class ShanhaiPurgeAdmission {

    private ShanhaiPurgeAdmission() {}

    /** 日志前缀（与守卫其它路径同一个，避免两套前缀）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** 同一类型在一个配额窗口里最多抹几只（规格：N=4，防"一眼清场"）。 */
    public static final int MAX_PER_TYPE_PER_WINDOW = 4;

    /** 配额窗口（毫秒）：1 秒 ≥ 20 tick ⇒ "同一 tick"与"同一秒"两种口径一起盖住。 */
    public static final long QUOTA_WINDOW_MS = 1_000L;

    /** 播报合并窗口（毫秒）：{@code purge_announce} 每 5 秒最多一行（原来是每秒一行 ⇒ 刷屏）。 */
    public static final long ANNOUNCE_WINDOW_MS = 5_000L;

    /** 跳过日志的合并窗口（毫秒）：{@code skip reason=…} 也是每 5 秒最多一行。 */
    public static final long SKIP_WINDOW_MS = 5_000L;

    /** 日志里的分隔（与守卫其它行同一个形状）。 */
    public static final String SEP = " ｜ ";

    // ═══════════════════════════════ ① 「要不要抹」的纯判据 ═══════════════════════════════

    /**
     * 判定结果。<b>顺序即优先级</b>（{@link #judge} 返回第一个不满足的那一条）。
     *
     * <p>{@link #code} 就是日志里 {@code skip reason=<code>} 的那个值，可 grep、可稳定断行。
     */
    public enum Reason {

        /** 放行（排队抹除）。 */
        OK("ok", "放行"),
        /** 没有攻击者（掉虚空/熔岩/毒…）。游戏侧提前静默短路，不记日志（环境伤害太频）。 */
        NO_ATTACKER("no_attacker", "没有攻击者（环境伤害）"),
        /** 受害者不是受保护玩家。游戏侧提前静默短路（与原来一样，一个字都不改）。 */
        NOT_GUARDED("not_guarded", "受害者不是受保护玩家"),
        /** 召唤物 / 投射物（{@code direct != attacker}）—— <b>既有语义，一字未改</b>。 */
        SUMMON("summon", "召唤物或投射物"),
        /** 玩家攻击者 —— <b>既有语义，一字未改</b>。 */
        PLAYER("player", "玩家攻击者"),
        /** 攻击者不是生物。 */
        NOT_LIVING("not_living", "攻击者不是生物"),
        /** 攻击事件 {@code amount <= 0}（含 NaN）⇒ 这一下本来就不疼。 */
        ZERO_AMOUNT("zero_amount", "amount<=0（这一下本来不疼）"),
        /** 攻击者与受害者不在同一个维度（跨维度的那一只不归这里管）。 */
        OTHER_LEVEL("other_level", "与受害者不在同一维度"),
        /** 被动 / 中立生物：是 {@code Mob}，但没把受害者当目标，也不是敌对实体。 */
        PASSIVE("passive", "被动或中立生物且没把受害者当目标"),
        /** 同一次攻击已经进过排队 / 这个类型已经在压制窗口里。 */
        DUP("dup", "同一次攻击已经进过排队或压制");

        /** 日志里 {@code skip reason=} 后面的那个值。 */
        public final String code;

        /** 人话说明（只进日志细节，不做判据）。 */
        public final String text;

        Reason(String code, String text) {
            this.code = code;
            this.text = text;
        }

        /** 要不要为它落一行 {@code skip reason=…}（环境伤害与非受保护玩家仍然完全静默）。 */
        public boolean logsSkip() {
            return this != NO_ATTACKER && this != NOT_GUARDED;
        }
    }

    /**
     * 一次攻击的全部判据输入（<b>字段全部由调用方喂进来</b> ⇒ 离线可用假输入复现每一条判据）。
     *
     * <p>只有 {@link #amount} 是数值，其余都是布尔：纯核不认识 Minecraft 的任何类型，
     * 换算（{@code instanceof Enemy} / {@code getTarget()} / {@code instanceof Mob} …）在游戏侧做。
     */
    public static final class Attack {

        /** 受害者是受保护玩家吗。 */
        public boolean victimGuarded;
        /** 有没有攻击者（环境伤害 = 没有）。 */
        public boolean hasAttacker;
        /** {@code directEntity} 就是攻击者本人（{@code false} = 召唤物/投射物）。 */
        public boolean directIsAttacker = true;
        /** 攻击者是玩家。 */
        public boolean attackerIsPlayer;
        /** 攻击者是生物（{@code LivingEntity}）。 */
        public boolean attackerIsLiving;
        /** 这一下的原始伤害值（{@code LivingAttackEvent#getAmount()}）。 */
        public double amount;
        /** 攻击者与受害者同一个维度。 */
        public boolean sameLevel = true;
        /** 攻击者是 {@code Mob}。 */
        public boolean attackerIsMob;
        /** 攻击者的 {@code getTarget()} 就是受害者本人。 */
        public boolean mobTargetsVictim;
        /** 攻击者是敌对实体（{@code Enemy} 或 {@code MobCategory.MONSTER}）。 */
        public boolean attackerIsHostile;
        /** 这一只已经在排队表里。 */
        public boolean alreadyQueued;
        /** 这个类型已经在持续压制窗口里。 */
        public boolean typeSuppressed;

        /** 链式写法（自检里一行摊一条用例，读数对得上）。 */
        public Attack victimGuarded(boolean v) {
            this.victimGuarded = v;
            return this;
        }

        /** 见 {@link #hasAttacker}。 */
        public Attack hasAttacker(boolean v) {
            this.hasAttacker = v;
            return this;
        }

        /** 见 {@link #directIsAttacker}。 */
        public Attack directIsAttacker(boolean v) {
            this.directIsAttacker = v;
            return this;
        }

        /** 见 {@link #attackerIsPlayer}。 */
        public Attack attackerIsPlayer(boolean v) {
            this.attackerIsPlayer = v;
            return this;
        }

        /** 见 {@link #attackerIsLiving}。 */
        public Attack attackerIsLiving(boolean v) {
            this.attackerIsLiving = v;
            return this;
        }

        /** 见 {@link #amount}。 */
        public Attack amount(double v) {
            this.amount = v;
            return this;
        }

        /** 见 {@link #sameLevel}。 */
        public Attack sameLevel(boolean v) {
            this.sameLevel = v;
            return this;
        }

        /** 见 {@link #attackerIsMob}。 */
        public Attack attackerIsMob(boolean v) {
            this.attackerIsMob = v;
            return this;
        }

        /** 见 {@link #mobTargetsVictim}。 */
        public Attack mobTargetsVictim(boolean v) {
            this.mobTargetsVictim = v;
            return this;
        }

        /** 见 {@link #attackerIsHostile}。 */
        public Attack attackerIsHostile(boolean v) {
            this.attackerIsHostile = v;
            return this;
        }

        /** 见 {@link #alreadyQueued}。 */
        public Attack alreadyQueued(boolean v) {
            this.alreadyQueued = v;
            return this;
        }

        /** 见 {@link #typeSuppressed}。 */
        public Attack typeSuppressed(boolean v) {
            this.typeSuppressed = v;
            return this;
        }
    }

    /**
     * 🔴 <b>判定：要不要把这只攻击者排进抹除队列</b>（纯函数，无副作用、无时间、无全局状态）。
     *
     * <p>顺序 = 优先级，返回<b>第一个</b>不满足的原因；全过 ⇒ {@link Reason#OK}。
     * 顺序本身是判据的一部分：先挡"根本不该处理的"（玩家/召唤物/非生物），
     * 再挡"本来不疼的"（amount），最后挡"已经处理过的"（重复），
     * 这样日志里的 {@code skip reason=} 永远指向最根本的那一条。
     */
    public static Reason judge(Attack attack) {
        if (attack == null || !attack.hasAttacker) {
            return Reason.NO_ATTACKER;
        }
        if (!attack.victimGuarded) {
            return Reason.NOT_GUARDED;
        }
        if (!attack.directIsAttacker) {
            return Reason.SUMMON;          // 🔴 既有语义：召唤物/投射物一律不处理
        }
        if (attack.attackerIsPlayer) {
            return Reason.PLAYER;          // 🔴 既有语义：玩家攻击者一律不处理
        }
        if (!attack.attackerIsLiving) {
            return Reason.NOT_LIVING;
        }
        if (!(attack.amount > 0.0D)) {
            return Reason.ZERO_AMOUNT;     // 0 / 负数 / NaN 全进这里（fail-closed）
        }
        if (!attack.sameLevel) {
            return Reason.OTHER_LEVEL;
        }
        if (attack.attackerIsMob && !attack.mobTargetsVictim && !attack.attackerIsHostile) {
            return Reason.PASSIVE;         // 🆕 专治发光鱿鱼 / 鱿鱼这类被动生物
        }
        if (attack.alreadyQueued || attack.typeSuppressed) {
            return Reason.DUP;             // 🆕 同一次攻击不重复排队 / 已在压制窗口里
        }
        return Reason.OK;
    }

    // ═══════════════════════════════ ② 每类型条数上限（≤4 / 秒） ═══════════════════════════════

    /**
     * <b>同一类型、同一个 {@link #QUOTA_WINDOW_MS} 窗口里最多放行 {@value #MAX_PER_TYPE_PER_WINDOW} 次</b>。
     *
     * <p>两条自动抹除路径（挨打反击排队 ＋ 持续压制扫描）<b>共用同一份配额</b> ⇒
     * 同一秒里两条路加起来也不会超过 4 只，堵住"一秒抹几十只、像一眼清场"。
     * 超出的一次都不抹，只在自己的表里记一笔 {@code capped}（由播报那一行打成 {@code capped=<n>}）。
     *
     * <p>{@code K} 在游戏侧就是 {@code EntityType<?>}（注册表单例 ⇒ 身份语义正好），自检里用 {@code String}。
     * <p>🔴 fail-closed：{@code key == null} ⇒ 一律不给额度。
     */
    public static final class Quota<K> {

        /** 一个类型在一个窗口里的用量。 */
        private static final class Cell {

            long windowStart;
            int used;
        }

        private final Map<K, Cell> cells = new LinkedHashMap<>();
        private final Map<K, Integer> capped = new LinkedHashMap<>();
        private int cappedTotal;

        /**
         * 申请一次额度。
         *
         * @return {@code true} = 这一秒这个类型还有额度（已扣掉 1）；{@code false} = 已达上限（记一笔 capped）
         */
        public boolean tryAcquire(K key, long nowMillis) {
            if (key == null) {
                return false;
            }
            Cell cell = cells.get(key);
            // 时钟回拨（now < windowStart）也当作新窗口：宁可多给一次，也不要把表卡死
            if (cell == null || nowMillis - cell.windowStart >= QUOTA_WINDOW_MS || nowMillis < cell.windowStart) {
                cell = new Cell();
                cell.windowStart = nowMillis;
                cells.put(key, cell);
            }
            if (cell.used >= MAX_PER_TYPE_PER_WINDOW) {
                capped.merge(key, 1, Integer::sum);
                cappedTotal++;
                return false;
            }
            cell.used++;
            return true;
        }

        /** 退还一次额度（阶梯这一轮其实没抹掉时用）：只有"真抹掉"才占额度。 */
        public void refund(K key, long nowMillis) {
            final Cell cell = key == null ? null : cells.get(key);
            if (cell == null) {
                return;
            }
            if (nowMillis - cell.windowStart >= QUOTA_WINDOW_MS) {
                return;   // 已经换窗口了，旧额度退不回来
            }
            if (cell.used > 0) {
                cell.used--;
            }
        }

        /** 这个类型在当前窗口里用掉几次（读数用）。 */
        public int usedInWindow(K key, long nowMillis) {
            final Cell cell = key == null ? null : cells.get(key);
            if (cell == null || nowMillis - cell.windowStart >= QUOTA_WINDOW_MS) {
                return 0;
            }
            return cell.used;
        }

        /** 有没有因为超上限而拒过（播报决定要不要打 {@code capped=<n>} 那一行）。 */
        public boolean hasCapped() {
            return cappedTotal > 0;
        }

        /** 取走并清空"这段时间被上限挡掉"的计数（键 = 类型）。 */
        public Map<K, Integer> drainCapped(long nowMillis) {
            prune(nowMillis);
            final Map<K, Integer> out = new LinkedHashMap<>(capped);
            capped.clear();
            cappedTotal = 0;
            return out;
        }

        /** 摘掉已经过窗的类型（表只保留"这一秒真用过"的）。 */
        public int prune(long nowMillis) {
            final int before = cells.size();
            cells.entrySet().removeIf(e -> e.getValue() == null
                    || nowMillis - e.getValue().windowStart >= QUOTA_WINDOW_MS);
            return before - cells.size();
        }

        /** 清空（换世界 = 新的一局）。 */
        public void clear() {
            cells.clear();
            capped.clear();
            cappedTotal = 0;
        }

        /** 表里现在有几个类型（读数用）。 */
        public int size() {
            return cells.size();
        }
    }

    // ═══════════════════════════════ ③ 播报合并（每 5 秒最多一行，行内 类型×N） ═══════════════════════════════

    /**
     * <b>播报合并器</b>：窗口内的次数按"类型的人话名字"累加，<b>到点才吐一行</b>，
     * 行内按 {@code 类型×N} 合并（原来是每秒一行、一个类型一句 ⇒ 一秒一行刷屏）。
     *
     * <p>时间由调用方喂（{@code System.currentTimeMillis()} 在游戏侧）⇒ 离线可以用假时钟逐帧复现。
     */
    public static final class Announcer {

        private final long windowMs;
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private int total;
        private long lastFlush = Long.MIN_VALUE / 2L;

        /** 用规格窗口 {@value ShanhaiPurgeAdmission#ANNOUNCE_WINDOW_MS} 毫秒。 */
        public Announcer() {
            this(ANNOUNCE_WINDOW_MS);
        }

        /**
         * @param windowMs 合并窗口（毫秒）；{@code <= 0} ⇒ 退回规格窗口
         */
        public Announcer(long windowMs) {
            this.windowMs = windowMs <= 0L ? ANNOUNCE_WINDOW_MS : windowMs;
        }

        /** 窗口长度（毫秒），日志里那个 {@code window_ms=} 就是它。 */
        public long windowMs() {
            return windowMs;
        }

        /**
         * 攒一条播报。
         *
         * @param typeName 类型的人话名字（"发光鱿鱼" 这种）；空 ⇒ 记成 {@code ?}
         * @param n        这一条代表几只（同一类型同一窗口累加）
         */
        public void note(String typeName, int n, long nowMillis) {
            if (n <= 0) {
                return;
            }
            counts.merge(typeName == null || typeName.isEmpty() ? "?" : typeName, n, Integer::sum);
            total += n;
        }

        /** 窗口到了吗（调用方先问它，避免"窗口没到就把 capped 那些也一起发出去"）。 */
        public boolean due(long nowMillis) {
            return nowMillis - lastFlush >= windowMs;
        }

        /** 这一窗口一条都没攒到吗（读数用）。 */
        public boolean isEmpty() {
            return total <= 0;
        }

        /** 这一窗口攒了几条（读数用）。 */
        public int total() {
            return total;
        }

        /**
         * 到点就<b>取走并清空</b>，同时把窗口推走；没到点 / 这一窗口没内容 ⇒ {@code null}。
         *
         * <p>🔴 没内容时也推进窗口：保证 {@code capped=<n>} 那一行同样"每 5 秒最多一行"。
         */
        public Batch drain(long nowMillis) {
            if (nowMillis - lastFlush < windowMs) {
                return null;
            }
            lastFlush = nowMillis;
            if (total <= 0) {
                return null;
            }
            final Batch batch = new Batch(windowMs, total, counts);
            counts.clear();
            total = 0;
            return batch;
        }

        /** 清空（换世界 = 新的一局）。 */
        public void reset() {
            counts.clear();
            total = 0;
            lastFlush = Long.MIN_VALUE / 2L;
        }
    }

    /** 一个播报窗口合并出来的结果（<b>不可变快照</b>；{@link #logLine()} 就是日志那一行）。 */
    public static final class Batch {

        private final long windowMs;
        private final int total;
        private final Map<String, Integer> counts;

        Batch(long windowMs, int total, Map<String, Integer> counts) {
            this.windowMs = windowMs;
            this.total = total;
            this.counts = new LinkedHashMap<>(counts);   // 拷一份：counts 随后就被 clear
        }

        /** 窗口长度（毫秒）。 */
        public long windowMs() {
            return windowMs;
        }

        /** 本窗口一共几只（各类相加）。 */
        public int total() {
            return total;
        }

        /** 本窗口「类型 ⇒ 只数」（只读；顺序 = 首次出现顺序）。 */
        public Map<String, Integer> counts() {
            return Collections.unmodifiableMap(counts);
        }

        /** 日志那一行（{@code [SHANHAI-GUARD] purge_announce window_ms=5000 total=… ｜ 类型：…×N}）。 */
        public String logLine() {
            return announceLogLine(windowMs, counts);
        }
    }

    /**
     * <b>跳过原因限流器</b>：窗口内按原因累加，<b>到点才吐一行</b>
     * {@code [SHANHAI-GUARD] purge_skip window_ms=5000 total=… passive=… ｜ 末次：skip reason=passive …}。
     */
    public static final class SkipLog {

        private final long windowMs;
        private final Map<String, Integer> byReason = new LinkedHashMap<>();
        private int total;
        private String lastReason;
        private String lastDetail;
        private long lastFlush = Long.MIN_VALUE / 2L;

        /** 用规格窗口 {@value ShanhaiPurgeAdmission#SKIP_WINDOW_MS} 毫秒。 */
        public SkipLog() {
            this(SKIP_WINDOW_MS);
        }

        /**
         * @param windowMs 合并窗口（毫秒）；{@code <= 0} ⇒ 退回规格窗口
         */
        public SkipLog(long windowMs) {
            this.windowMs = windowMs <= 0L ? SKIP_WINDOW_MS : windowMs;
        }

        /** 记一次跳过（原因 + 那一只/那一对的细节）。 */
        public void note(Reason reason, String detail, long nowMillis) {
            final Reason r = reason == null ? Reason.NO_ATTACKER : reason;
            byReason.merge(r.code, 1, Integer::sum);
            total++;
            lastReason = r.code;
            lastDetail = detail;
        }

        /** 这一窗口记了几次（读数用）。 */
        public int total() {
            return total;
        }

        /** 到点就吐一行（并清空、推走窗口）；没到点 ⇒ {@code null}（窗口不推进）。 */
        public String takeLine(long nowMillis) {
            if (nowMillis - lastFlush < windowMs) {
                return null;
            }
            // 到点就把窗口推走（哪怕这一窗口一条都没有）：这样"每 5 秒最多一行"是硬上限
            lastFlush = nowMillis;
            if (total <= 0) {
                return null;
            }
            final String line = skipLogLine(windowMs, byReason, lastReason, lastDetail);
            byReason.clear();
            total = 0;
            lastDetail = null;
            return line;
        }

        /** 清空（换世界 = 新的一局）。 */
        public void reset() {
            byReason.clear();
            total = 0;
            lastReason = null;
            lastDetail = null;
            lastFlush = Long.MIN_VALUE / 2L;
        }
    }

    // ═══════════════════════════════ ④ 三行文案（纯字符串，逐字可断言） ═══════════════════════════════

    /**
     * {@code purge_announce} 那一行（<b>每 5 秒最多一行</b>，行内按类型合并）。
     *
     * <pre>[SHANHAI-GUARD] purge_announce window_ms=5000 total=39 ｜ 类型：发光鱿鱼×34 僵尸×5</pre>
     */
    public static String announceLogLine(long windowMs, Map<String, Integer> byType) {
        final StringBuilder sb = new StringBuilder(PREFIX)
                .append("purge_announce window_ms=").append(windowMs)
                .append(" total=").append(sum(byType))
                .append(SEP).append("类型：");
        appendCounts(sb, byType);
        return sb.toString();
    }

    /**
     * {@code purge_capped} 那一行（同一份播报窗口；只有真被上限挡过才打）。
     *
     * <pre>[SHANHAI-GUARD] purge_capped window_ms=5000 capped=7 quota_window_ms=1000 ｜ 类型：minecraft:zombie×7</pre>
     */
    public static String cappedLogLine(long windowMs, long quotaWindowMs, Map<String, Integer> byType) {
        final StringBuilder sb = new StringBuilder(PREFIX)
                .append("purge_capped window_ms=").append(windowMs)
                .append(" capped=").append(sum(byType))
                .append(" quota_window_ms=").append(quotaWindowMs)
                .append(SEP).append("类型：");
        appendCounts(sb, byType);
        return sb.toString();
    }

    /**
     * {@code purge_skip} 那一行（限流：每 5 秒最多一行；行内按原因合并，末尾带末次那一只的细节）。
     *
     * <pre>[SHANHAI-GUARD] purge_skip window_ms=5000 total=12 passive=8 zero_amount=3 dup=1 ｜ 末次：skip reason=passive attacker=… victim=…</pre>
     */
    public static String skipLogLine(long windowMs, Map<String, Integer> byReason, String lastReason,
                                     String lastDetail) {
        final StringBuilder sb = new StringBuilder(PREFIX)
                .append("purge_skip window_ms=").append(windowMs)
                .append(" total=").append(sum(byReason));
        if (byReason != null) {
            for (final Map.Entry<String, Integer> e : byReason.entrySet()) {
                if (e == null || e.getValue() == null || e.getValue() <= 0) {
                    continue;
                }
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        sb.append(SEP).append("末次：skip reason=")
                .append(lastReason == null || lastReason.isEmpty() ? "?" : lastReason);
        if (lastDetail != null && !lastDetail.isEmpty()) {
            sb.append(' ').append(lastDetail);
        }
        return sb.toString();
    }

    private static void appendCounts(StringBuilder sb, Map<String, Integer> counts) {
        boolean first = true;
        if (counts != null) {
            for (final Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e == null || e.getValue() == null || e.getValue() <= 0) {
                    continue;
                }
                if (!first) {
                    sb.append(' ');
                }
                first = false;
                sb.append(e.getKey()).append('×').append(e.getValue());
            }
        }
        if (first) {
            sb.append("（无）");
        }
    }

    private static int sum(Map<String, Integer> counts) {
        int n = 0;
        if (counts != null) {
            for (final Integer v : counts.values()) {
                if (v != null && v > 0) {
                    n += v;
                }
            }
        }
        return n;
    }

    // ═══════════════════════════════ ⑤ 安全清场通道（HurtOnly）的纯判据 ═══════════════════════════════

    /**
     * 骗过 {@code isDeadOrDying()} 前置的那口血：<b>极小正数</b>（规格 0.1）。
     *
     * <p>为什么必须写成正数：原版 {@code LivingEntity#hurt} 的第二条前置就是
     * {@code if (this.isDeadOrDying()) return false;}（= {@code getHealth() <= 0}）⇒
     * 血已经是 0 的实体<b>永远打不进伤害</b>。先写 0.1 让它"还活着"，下一记才落得下去。
     */
    public static final float PRIME_HEALTH = 0.1F;

    /**
     * 单次 hurt 的伤害上界：{@code 1.0E30F}（<b>不是</b> {@code Float.MAX_VALUE}）。
     *
     * <p>依据三条：
     * <ol>
     *   <li>原版最高血量 1024（凋灵 300 / 末影龙 200），模组里"极端 boss"也就百万级 ⇒
     *       1e30 比它高 <b>24 个数量级</b>，对任何靠 {@code hurt} 结算的实体都必然致死；</li>
     *   <li>{@code float} 上限 3.4028235E38 ⇒ 1e30 还留着<b>约 8 个数量级</b>余量：原版 hurt 里的
     *       中间量（护甲/附魔各乘一次 &lt;1 的系数、吸收血量相减、{@code getHealth() - damage}）都不会
     *       溢出成 {@code Infinity}，也就不会出现 {@code Infinity - Infinity = NaN}；</li>
     *   <li>规格明确禁止直接写 {@code Float.MAX_VALUE} 这类极值：它一乘 2 就是 Inf，别的 mod 拿它做
     *       比值/统计还会把整条血条算成 NaN。</li>
     * </ol>
     *
     * <p>⚠️ 代价：血量 &gt; 2.5e29 的实体拿到的是"1e30 而不是 4 倍血量"，若它还有很高的护甲减伤，
     * 可能一次没打死 —— 这种实体本来就不该靠 hurt 结算（见 {@code stuck=}）。
     */
    public static final float SAFE_DAMAGE_MAX = 1.0E30F;

    /** 伤害下限：普通生物（≤1024 血）一枪够用，不必每次都抬到上界。 */
    public static final float MIN_LETHAL_DAMAGE = 1_000.0F;

    /** 伤害余量倍数：护甲/附魔/吸收最多削掉大部分 ⇒ 按 4 倍血量打。 */
    public static final float DAMAGE_HEADROOM = 4.0F;

    /** {@code purge_kill} 一行里每个分块最多列几个类型（超出写 {@code …+N种}，防一行爆长）。 */
    public static final int KILL_TYPES_PER_LINE = 8;

    /**
     * <b>要不要先把血写成 {@link #PRIME_HEALTH}</b> —— 规格：只在"当前已 {@code <=0} 或 {@code NaN}"时写，
     * 正常血量<b>不要动</b>（动了就是把一只健康生物改成残血）。
     *
     * <p>判据刻意写成 {@code !(current > 0)}：{@code 0} / 负数 / {@code NaN} 全进"要写"；
     * 任何正数（含 {@code +Infinity}）都进"不要写" —— 无限血本来就 {@code isDeadOrDying()==false}。
     */
    public static boolean shouldPrimeHealth(float currentHealth) {
        return !(currentHealth > 0.0F);
    }

    /**
     * <b>这一只打多少伤害</b>：{@code max(1000, 4 × 最大血量)}，再夹到 {@link #SAFE_DAMAGE_MAX}。
     *
     * <p>为什么不用固定值：模组 boss 的血量差着几个数量级（20 / 300 / 100 万），固定 1000 打不死大血量，
     * 固定上界又每次都把中间量抬到极限 ⇒ 按血量算，再夹一次上限。
     * <p>读不到 / 不合法（{@code NaN}、{@code <=0}、{@code Infinity}）⇒ 直接用安全上界。
     */
    public static float damageFor(float maxHealth) {
        if (!(maxHealth > 0.0F) || Float.isInfinite(maxHealth)) {
            return SAFE_DAMAGE_MAX;
        }
        final float wanted = Math.max(MIN_LETHAL_DAMAGE, maxHealth * DAMAGE_HEADROOM);
        if (!(wanted > 0.0F) || Float.isInfinite(wanted)) {
            return SAFE_DAMAGE_MAX;   // 4 倍已溢出 ⇒ 退回上界（对超大血量也别写 Inf）
        }
        return Math.min(wanted, SAFE_DAMAGE_MAX);
    }

    /** 复查：算不算"真的没了"（原版口径 {@code isRemoved() || isDeadOrDying()}）。 */
    public static boolean isGone(boolean removed, boolean deadOrDying) {
        return removed || deadOrDying;
    }

    /** 复查：算不算"删不掉"（hurt 之后它还在 ⇒ 记 {@code stuck=}，<b>留在原地不处理</b>）。 */
    public static boolean isStuck(boolean removed, boolean deadOrDying) {
        return !isGone(removed, deadOrDying);
    }

    /** 一只候选实体在安全通道里的结局。 */
    public enum KillOutcome {

        /** 复查确认没了（{@code isRemoved() || isDeadOrDying()}）。 */
        KILLED("killed"),
        /** 打完还在（例如替身的 {@code hurt} 恒 false、或血量被锁）⇒ 留在原地。 */
        STUCK("stuck"),
        /** 不是 {@code LivingEntity} 或拿不到血 ⇒ 跳过（日志里就是 {@code reason=not_living}）。 */
        SKIPPED_NOT_LIVING("not_living");

        /** 日志里 {@code reason=} 用的值。 */
        public final String code;

        KillOutcome(String code) {
            this.code = code;
        }
    }

    /**
     * <b>一次清场的读数（可合并）</b>：{@code swept} 恒等于 {@code killed + stuck + skipped}
     * （<b>派生的，不允许两个来源对不上</b>），逐类型计数分别记 killed / stuck / skipped。
     */
    public static final class KillTally {

        private int killed;
        private int stuck;
        private int skipped;
        private int skippedPlayers;
        private String lastSkipReason = "none";
        private final Map<String, Integer> killedTypes = new LinkedHashMap<>();
        private final Map<String, Integer> stuckTypes = new LinkedHashMap<>();
        private final Map<String, Integer> skippedTypes = new LinkedHashMap<>();

        /** 记一只的结果（{@code typeId} = 注册名，如 {@code minecraft:zombie}）。 */
        public void note(String typeId, KillOutcome outcome) {
            final String key = typeId == null || typeId.isEmpty() ? "?" : typeId;
            if (outcome == KillOutcome.KILLED) {
                killed++;
                killedTypes.merge(key, 1, Integer::sum);
            } else if (outcome == KillOutcome.STUCK) {
                stuck++;
                stuckTypes.merge(key, 1, Integer::sum);
            } else {
                skipped++;
                skippedTypes.merge(key, 1, Integer::sum);
                lastSkipReason = outcome == null ? "not_living" : outcome.code;
            }
        }

        /** 跳过的玩家数（<b>不进 swept</b>：玩家永远不是候选）。 */
        public void notePlayers(int n) {
            if (n > 0) {
                skippedPlayers += n;
            }
        }

        /** 把另一份读数并进来（5 秒窗口里可能连着两次清场）。 */
        public void merge(KillTally other) {
            if (other == null) {
                return;
            }
            killed += other.killed;
            stuck += other.stuck;
            skipped += other.skipped;
            skippedPlayers += other.skippedPlayers;
            if (other.lastSkipReason != null && !"none".equals(other.lastSkipReason)) {
                lastSkipReason = other.lastSkipReason;
            }
            mergeMap(killedTypes, other.killedTypes);
            mergeMap(stuckTypes, other.stuckTypes);
            mergeMap(skippedTypes, other.skippedTypes);
        }

        private static void mergeMap(Map<String, Integer> into, Map<String, Integer> from) {
            for (final Map.Entry<String, Integer> e : from.entrySet()) {
                final Integer v = e.getValue();
                if (v != null && v > 0) {
                    into.merge(e.getKey(), v, Integer::sum);
                }
            }
        }

        /** 扫到的候选只数（派生：{@code killed + stuck + skipped}）。 */
        public int swept() {
            return killed + stuck + skipped;
        }

        /** 复查确认没了的只数。 */
        public int killed() {
            return killed;
        }

        /** 打完还在的只数。 */
        public int stuck() {
            return stuck;
        }

        /** 跳过的只数（不是生物 / 拿不到血）。 */
        public int skipped() {
            return skipped;
        }

        /** 跳过的玩家数。 */
        public int skippedPlayers() {
            return skippedPlayers;
        }

        /** 最近一次跳过的原因（没有跳过 ⇒ {@code none}）。 */
        public String lastSkipReason() {
            return lastSkipReason;
        }

        /** 逐类型：真的没了。 */
        public Map<String, Integer> killedTypes() {
            return Collections.unmodifiableMap(killedTypes);
        }

        /** 逐类型：打完还在。 */
        public Map<String, Integer> stuckTypes() {
            return Collections.unmodifiableMap(stuckTypes);
        }

        /** 逐类型：跳过。 */
        public Map<String, Integer> skippedTypes() {
            return Collections.unmodifiableMap(skippedTypes);
        }

        /** 一只都没记过吗。 */
        public boolean isEmpty() {
            return swept() <= 0;
        }

        /** 清空。 */
        public void clear() {
            killed = 0;
            stuck = 0;
            skipped = 0;
            skippedPlayers = 0;
            lastSkipReason = "none";
            killedTypes.clear();
            stuckTypes.clear();
            skippedTypes.clear();
        }
    }

    /**
     * <b>清场日志的 5 秒窗口</b>（沿用 {@link #ANNOUNCE_WINDOW_MS}）：窗口内多次清场合并成一行，
     * 到点才落；没内容 ⇒ 一行都不打，但窗口照样推走（保证"每 5 秒最多一行"是硬上限）。
     */
    public static final class KillWindow {

        private final long windowMs;
        private final KillTally pending = new KillTally();
        private long lastFlush = Long.MIN_VALUE / 2L;

        /** 用规格窗口 {@value ShanhaiPurgeAdmission#ANNOUNCE_WINDOW_MS} 毫秒。 */
        public KillWindow() {
            this(ANNOUNCE_WINDOW_MS);
        }

        /** @param windowMs 合并窗口（毫秒）；{@code <=0} ⇒ 退回规格窗口 */
        public KillWindow(long windowMs) {
            this.windowMs = windowMs <= 0L ? ANNOUNCE_WINDOW_MS : windowMs;
        }

        /** 窗口长度（毫秒）。 */
        public long windowMs() {
            return windowMs;
        }

        /** 窗口到了吗。 */
        public boolean due(long nowMillis) {
            return nowMillis - lastFlush >= windowMs;
        }

        /** 这一窗口里有没有攒到东西。 */
        public boolean isEmpty() {
            return pending.isEmpty();
        }

        /** 把一次清场的读数并进当前窗口。 */
        public void add(KillTally one) {
            if (one != null) {
                pending.merge(one);
            }
        }

        /** 到点且有内容 ⇒ 一行（清空并推走窗口）；没到点 / 空窗口 ⇒ {@code null}。 */
        public String takeLine(long nowMillis) {
            if (nowMillis - lastFlush < windowMs) {
                return null;
            }
            lastFlush = nowMillis;
            if (pending.isEmpty()) {
                return null;
            }
            final String line = killLogLine(windowMs, pending);
            pending.clear();
            return line;
        }

        /** 清空（换世界 = 新的一局）。 */
        public void reset() {
            pending.clear();
            lastFlush = Long.MIN_VALUE / 2L;
        }
    }

    /**
     * {@code purge_kill} 那一行（<b>每 5 秒最多一行</b>，逐类型合并成 {@code 类型×N}）。
     *
     * <pre>[SHANHAI-GUARD] purge_kill window_ms=5000 swept=120 killed=98 stuck=3 skipped=19 reason=not_living skipped_players=0 ｜ killed：minecraft:zombie×60 ｜ stuck：examplemod:x×3 ｜ skipped：minecraft:item×19</pre>
     */
    public static String killLogLine(long windowMs, KillTally tally) {
        final StringBuilder sb = new StringBuilder(PREFIX)
                .append("purge_kill window_ms=").append(windowMs)
                .append(" swept=").append(tally.swept())
                .append(" killed=").append(tally.killed())
                .append(" stuck=").append(tally.stuck())
                .append(" skipped=").append(tally.skipped())
                .append(" reason=").append(tally.lastSkipReason())
                .append(" skipped_players=").append(tally.skippedPlayers());
        appendTypeBlock(sb, "killed", tally.killedTypes());
        appendTypeBlock(sb, "stuck", tally.stuckTypes());
        appendTypeBlock(sb, "skipped", tally.skippedTypes());
        return sb.toString();
    }

    /** 一个分块：` ｜ label：a×3 b×2 …+N种`（最多列 {@link #KILL_TYPES_PER_LINE} 个类型）。 */
    private static void appendTypeBlock(StringBuilder sb, String label, Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        sb.append(SEP).append(label).append('：');
        int shown = 0;
        int types = 0;
        for (final Map.Entry<String, Integer> e : counts.entrySet()) {
            final Integer v = e.getValue();
            if (v == null || v <= 0) {
                continue;
            }
            types++;
            if (shown >= KILL_TYPES_PER_LINE) {
                continue;
            }
            if (shown > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('×').append(v);
            shown++;
        }
        if (types > shown) {
            sb.append(" …+").append(types - shown).append("种");
        }
    }
}
