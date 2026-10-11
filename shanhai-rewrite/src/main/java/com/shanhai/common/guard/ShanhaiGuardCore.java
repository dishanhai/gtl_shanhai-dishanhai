package com.shanhai.common.guard;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 山海 · 死亡守卫（"持有【创始现实修改模块】的玩家不会死"）<b>★纯逻辑核★</b>
 *
 * <h2>本轮范围：就这一个文件</h2>
 * <b>只 import {@code java.*}</b> —— 零 Forge、零 Minecraft ⇒ 空 classpath 也能 {@code javac} 过，
 * 也能离线单跑（见文件末尾 {@link #main}）。<b>Forge 胶水（事件订阅）由下一条线做，本类一个事件都不订阅。</b>
 *
 * <h2>为什么判定要住在纯 JDK 类里</h2>
 * 判定若写进带 Minecraft 依赖的类，离线复现脚本就只能"照抄一遍判定"，
 * 于是"脚本抄错了还是生产代码错了"分不清。本类零依赖，<b>游戏里跑的是同一份字节码</b>，不存在第二份判定。
 *
 * <h2>数据谁取</h2>
 * 本类<b>不取任何数据</b>：物品 id、血量、屏名、计时器全部由 Forge 侧取好当参数传进来。
 *
 * <h2>三条硬口径（材料已查实）</h2>
 * <ol>
 *   <li>{@link Decision}：每次判定都给出<b>原因串</b>（"伤害被取消（持有模块）" / "血量被外部设为 X ⇒ 回满"），
 *       不是只返回 boolean ⇒ 日志有话可说。</li>
 *   <li>{@link #healVerified(float, float)} ＋ {@link #healFailureCount()}：<b>回血要能自证</b> ——
 *       对方可能 mixin 掉 {@code setHealth}/{@code heal}，我们的回血会<b>静默失效</b>，必须自己抓着。
 *       第二通道（直写同步血量）{@link SecondChannelHeal} <b>先只留接口</b>。</li>
 *   <li>{@link #looksDead(float, boolean, int, int)}：<b>读血不可全信</b> ——
 *       {@code getHealth()} 可能已被改写/假血 ⇒ 用死亡标志与计时器交叉校验。</li>
 * </ol>
 */
public final class ShanhaiGuardCore {

    private ShanhaiGuardCore() {}

    // ═══════════════════════════════ 常量 ═══════════════════════════════

    /** 那一件物品的 id（命名空间）。 */
    public static final String MODULE_NAMESPACE = "shanhai";
    /** 那一件物品的 id（路径）。 */
    public static final String MODULE_PATH = "genesis_reality_modification_module";
    /** {@code shanhai:genesis_reality_modification_module}。 */
    public static final String MODULE_ID = MODULE_NAMESPACE + ":" + MODULE_PATH;

    /** 🔴 1.20.1 的死亡界面（全名）。 */
    public static final String DEATH_SCREEN_FQN = "net.minecraft.client.gui.screens.DeathScreen";
    /** 🔴 1.20.1 的死亡界面（简名，Forge 侧喂 {@code getSimpleName()} 也认）。 */
    public static final String DEATH_SCREEN_SIMPLE = "DeathScreen";
    /** 🔴 {@code GuiGameOver} 是 1.12.2 的名字 —— 1.20.1 上不会出现，出现也<b>不认</b>。 */
    public static final String DEATH_SCREEN_LEGACY_SIMPLE = "GuiGameOver";

    /** 要复位的计时器编号：{@code deathTime}。 */
    public static final long COUNTER_DEATH_TIME = 1L;
    /** 要复位的计时器编号：{@code hurtTime}。 */
    public static final long COUNTER_HURT_TIME = 2L;

    /** 血量比较容差（float 精度；{@code setHealth} 走的是 float）。 */
    public static final float EPS = 1e-4f;

    /** 总开关（UI 由主代理后接；本类只提供字段，不做 UI）。 */
    public static volatile boolean enabled = true;

    // ═══════════════════════════════ 口径①：判定结果（yes ＋ reason） ═══════════════════════════════

    /** 一条判定的结果：{@code yes} 是结论，{@code reason} 是给日志的原因串。 */
    public static final class Decision {

        /** 结论。 */
        public final boolean yes;
        /** 原因串（永远非空，便于直接进日志）。 */
        public final String reason;

        private Decision(boolean yes, String reason) {
            this.yes = yes;
            this.reason = reason;
        }

        public static Decision of(boolean yes, String reason) {
            return new Decision(yes, reason);
        }

        @Override
        public String toString() {
            return yes + "（" + reason + "）";
        }
    }

    // ═══════════════════════════════ ① 持有判据（按物品 id） ═══════════════════════════════

    /** 携带列表里是不是含那件模块（按物品 id 判，不按类名、不看 NBT）。 */
    public static boolean holdsModule(List<String> carriedItemIds) {
        return holdsModuleDecision(carriedItemIds).yes;
    }

    /** 同上，带原因串。 */
    public static Decision holdsModuleDecision(List<String> carriedItemIds) {
        if (carriedItemIds == null || carriedItemIds.isEmpty()) {
            return Decision.of(false, "未持有模块（携带列表为空/未取到数据）");
        }
        for (final String id : carriedItemIds) {
            if (isModuleId(id)) {
                return Decision.of(true, "持有模块（" + id.trim() + "）");
            }
        }
        return Decision.of(false, "未持有模块（查了 " + carriedItemIds.size() + " 件，均非 " + MODULE_ID + "）");
    }

    /** id 比对：全名或 path 形态都认，大小写不敏感。 */
    public static boolean isModuleId(String itemId) {
        if (itemId == null) {
            return false;
        }
        final String s = itemId.trim();
        return MODULE_ID.equalsIgnoreCase(s) || MODULE_PATH.equalsIgnoreCase(s);
    }

    // ═══════════════════════════════ ② 挡伤害 ═══════════════════════════════

    /** 只对<b>持有模块的玩家</b>为真。 */
    public static boolean shouldBlockDamage(String itemId, boolean isPlayer) {
        return blockDamageDecision(itemId, isPlayer, enabled).yes;
    }

    /** 同上，带原因串（用类里的 {@link #enabled}）。 */
    public static Decision blockDamageDecision(String itemId, boolean isPlayer) {
        return blockDamageDecision(itemId, isPlayer, enabled);
    }

    /** 纯函数版：开关也当参数传（离线复现不依赖静态状态）。 */
    public static Decision blockDamageDecision(String itemId, boolean isPlayer, boolean enabledFlag) {
        if (!enabledFlag) {
            return Decision.of(false, "开关关闭（enabled=false）⇒ 不拦伤害");
        }
        if (!isPlayer) {
            return Decision.of(false, "受伤方不是玩家（isPlayer=false）⇒ 不拦伤害");
        }
        if (!isModuleId(itemId)) {
            return Decision.of(false, "未持有模块（itemId=" + itemId + "）⇒ 不拦伤害");
        }
        return Decision.of(true, "伤害被取消（持有模块）");
    }

    // ═══════════════════════════════ ③ 拦死亡 ═══════════════════════════════

    /** 同 {@link #shouldBlockDamage}：只对<b>持有模块的玩家</b>为真。 */
    public static boolean shouldBlockDeath(String itemId, boolean isPlayer) {
        return blockDeathDecision(itemId, isPlayer, enabled).yes;
    }

    /** 同上，带原因串（用类里的 {@link #enabled}）。 */
    public static Decision blockDeathDecision(String itemId, boolean isPlayer) {
        return blockDeathDecision(itemId, isPlayer, enabled);
    }

    /** 纯函数版。 */
    public static Decision blockDeathDecision(String itemId, boolean isPlayer, boolean enabledFlag) {
        if (!enabledFlag) {
            return Decision.of(false, "开关关闭（enabled=false）⇒ 不拦死亡");
        }
        if (!isPlayer) {
            return Decision.of(false, "死亡方不是玩家（isPlayer=false）⇒ 不拦死亡");
        }
        if (!isModuleId(itemId)) {
            return Decision.of(false, "未持有模块（itemId=" + itemId + "）⇒ 不拦死亡");
        }
        return Decision.of(true, "死亡被取消（持有模块）");
    }

    // ═══════════════════════════════ ④ 回血 ═══════════════════════════════

    /** {@code current < max} ⇒ 需要补。 */
    public static boolean needsHeal(float current, float max) {
        return needsHealDecision(current, max).yes;
    }

    /** 同上，带原因串。 */
    public static Decision needsHealDecision(float current, float max) {
        if (Float.isNaN(current) || Float.isNaN(max)) {
            return Decision.of(false, "血读到 NaN（current=" + current + ", max=" + max + "）⇒ 数据不可信，不补"
                    + "（交给 looksDead 交叉校验）");
        }
        if (max <= 0f) {
            return Decision.of(false, "上限非正（max=" + max + "）⇒ 不补");
        }
        if (current < max - EPS) {
            return Decision.of(true, "血 " + current + " < 上限 " + max + " ⇒ 需回满");
        }
        return Decision.of(false, "血已满（" + current + " / " + max + "）⇒ 不补");
    }

    /** 🔴 回满 = 上限（判据不变量：{@code healTarget(max) == max}）。 */
    public static float healTarget(float max) {
        return max; // 负对照③ 会把这一行改成 max / 2f，用来验证自检判据真会报红
    }

    /** 这一 tick 该不该回血 ＋ 原因串（形如"血量被外部设为 3.0 ⇒ 回满（目标 20.0）"）。 */
    public static Decision healDecision(float current, float max) {
        return healDecision(current, max, enabled);
    }

    /** 纯函数版。 */
    public static Decision healDecision(float current, float max, boolean enabledFlag) {
        if (!enabledFlag) {
            return Decision.of(false, "开关关闭（enabled=false）⇒ 不回血");
        }
        final Decision need = needsHealDecision(current, max);
        if (!need.yes) {
            return Decision.of(false, need.reason);
        }
        return Decision.of(true, "血量被外部设为 " + current + " ⇒ 回满（目标 " + healTarget(max) + "）");
    }

    // ═══════════════════════════════ ⑤ 死亡界面 ═══════════════════════════════

    /**
     * 🔴 只认 1.20.1 的 {@code DeathScreen}。
     *
     * <p>{@code GuiGameOver} 是 1.12.2 的名字 —— 这里<b>必须不认</b>
     * （认错名字的后果：屏幕照开，玩家看到死亡界面却其实没死）。
     */
    public static boolean shouldCancelDeathScreen(String screenClassName) {
        return cancelDeathScreenDecision(screenClassName).yes;
    }

    /** 同上，带原因串。全名（带包）与简名都认。 */
    public static Decision cancelDeathScreenDecision(String screenClassName) {
        if (screenClassName == null) {
            return Decision.of(false, "屏名为空 ⇒ 不取消");
        }
        final String full = screenClassName.trim();
        final int dot = full.lastIndexOf('.');
        final String simple = (dot >= 0 && dot + 1 < full.length()) ? full.substring(dot + 1) : full;
        if (DEATH_SCREEN_SIMPLE.equals(simple)) {
            return Decision.of(true, "1.20.1 死亡界面（" + DEATH_SCREEN_SIMPLE + "，实到 " + full + "）⇒ 取消死亡界面");
        }
        if (DEATH_SCREEN_LEGACY_SIMPLE.equals(simple)) {
            return Decision.of(false, DEATH_SCREEN_LEGACY_SIMPLE + " 是 1.12.2 的名字，1.20.1 不会出现"
                    + "（实到 " + full + "）⇒ 不取消");
        }
        return Decision.of(false, "不是 1.20.1 死亡界面（实到 " + full + "）⇒ 不取消");
    }

    /** 完整判定：开关 ＋ 是否持有模块 ＋ 屏名（Forge 侧建议用这条）。 */
    public static Decision cancelDeathScreenDecision(String screenClassName, boolean holdingModule,
                                                     boolean enabledFlag) {
        if (!enabledFlag) {
            return Decision.of(false, "开关关闭（enabled=false）⇒ 不取消死亡界面");
        }
        if (!holdingModule) {
            return Decision.of(false, "未持有模块 ⇒ 不取消死亡界面");
        }
        return cancelDeathScreenDecision(screenClassName);
    }

    // ═══════════════════════════════ ⑥ 要复位的计时器 ═══════════════════════════════

    /**
     * 要复位的计时器清单：{@code deathTime} / {@code hurtTime}。
     *
     * <p>🔴 与血量无关：有人每 tick 把 {@code deathTime} 写到满 20 再处决，那时血条是满的 ⇒
     * 只按"血 &lt; 满血"当判据会漏掉整条攻击路径。
     *
     * @return 每次返回<b>新数组</b>（别让调用方互相污染）
     */
    public static long[] countersToReset() {
        return new long[] { COUNTER_DEATH_TIME, COUNTER_HURT_TIME };
    }

    /** 编号 → 名字（打印/日志用）。 */
    public static String counterName(long counter) {
        if (counter == COUNTER_DEATH_TIME) {
            return "deathTime";
        }
        if (counter == COUNTER_HURT_TIME) {
            return "hurtTime";
        }
        return "unknown(" + counter + ")";
    }

    // ═══════════════════════════════ 口径②：回血自证 ═══════════════════════════════

    private static final AtomicLong HEAL_ATTEMPTS = new AtomicLong();
    private static final AtomicLong HEAL_FAILURES = new AtomicLong();

    /**
     * 🔴 <b>回血自证</b>：回完血把<b>真实读数</b>与期望值对一下。
     *
     * <p>对方可能 mixin 掉 {@code setHealth}/{@code heal} ⇒ 我们以为补了、其实没补（静默失效）。
     * 不相等就记一次失败（{@link #healFailureCount()}），不相等还说"成功"就是自欺。
     *
     * @param after    回血后真读到的血量
     * @param expected 期望值（一般是 {@link #healTarget(float)} 的结果）
     * @return 相等（容差 {@link #EPS}）⇒ true
     */
    public static boolean healVerified(float after, float expected) {
        HEAL_ATTEMPTS.incrementAndGet();
        if (Float.isNaN(after) || Math.abs(after - expected) <= EPS) {
            if (Float.isNaN(after)) {
                HEAL_FAILURES.incrementAndGet();
                return false;
            }
            return true;
        }
        HEAL_FAILURES.incrementAndGet();
        return false;
    }

    /** 自证跑过几次。 */
    public static long healAttemptCount() {
        return HEAL_ATTEMPTS.get();
    }

    /** 🔴 自证<b>不相等</b>的次数（静默失效的计数）。 */
    public static long healFailureCount() {
        return HEAL_FAILURES.get();
    }

    /** 清空计数（离线复现/换局时用）。 */
    public static void resetHealCounters() {
        HEAL_ATTEMPTS.set(0L);
        HEAL_FAILURES.set(0L);
    }

    /**
     * 🔴 <b>第二通道：直写同步血量</b>（先把接口留着，Forge 侧后接）。
     *
     * <p>第一通道（{@code setHealth}/{@code heal}）可能被人 mixin 掉；第二通道绕过去直接写。
     * <b>本类不实现它</b>，只提供插槽；没接就<b>不假报成功</b>。
     */
    public interface SecondChannelHeal {

        /** 直接写同步血量；返回是否真的写进去了。 */
        boolean writeHealth(float value);
    }

    /** 第二通道插槽（默认 null = 未接入）。 */
    public static volatile SecondChannelHeal secondChannel = null;

    /** 走第二通道直写，并给出原因串（未接入 ⇒ 明确说"未接入"，不假装成功）。 */
    public static Decision healViaSecondChannel(float expected) {
        final SecondChannelHeal ch = secondChannel;
        if (ch == null) {
            return Decision.of(false, "第二通道未接入（只留接口）⇒ 未直写同步血量 " + expected);
        }
        final boolean ok;
        try {
            ok = ch.writeHealth(expected);
        } catch (Throwable t) {
            return Decision.of(false, "第二通道抛异常：" + t);
        }
        return Decision.of(ok, ok ? "第二通道已直写同步血量 = " + expected : "第二通道直写失败（返回 false）");
    }

    // ═══════════════════════════════ 口径③：读血不可全信 ═══════════════════════════════

    /**
     * 🔴 <b>交叉校验</b>：{@code getHealth()} 可能已被改写/假血 ⇒ 用死亡标志与计时器一起判。
     *
     * <pre>
     *   isDeadOrDying = true                ⇒ 判【死】（血再满也不信，血与标志冲突会在原因串里点出来）
     *   deathTime &gt; 0                       ⇒ 判【死】（同上）
     *   health &lt;= 0                         ⇒ 判【死】
     *   health = NaN                        ⇒ 判【死】（读数坏了，不敢信）
     *   只是 hurtTime &gt; 0                    ⇒ 判【未死】（受击计时 ≠ 死）
     *   全假                                 ⇒ 判【未死】
     * </pre>
     */
    public static boolean looksDead(float health, boolean isDeadOrDying, int deathTime, int hurtTime) {
        return looksDeadDecision(health, isDeadOrDying, deathTime, hurtTime).yes;
    }

    /** 同上，带原因串（血与标志冲突时会在串里点明"疑似假血"）。 */
    public static Decision looksDeadDecision(float health, boolean isDeadOrDying, int deathTime, int hurtTime) {
        if (Float.isNaN(health)) {
            return Decision.of(true, "血读到 NaN（读数不可信）⇒ 按【死】处理");
        }
        final boolean bloodLooksAlive = health > EPS;
        if (isDeadOrDying) {
            return Decision.of(true, bloodLooksAlive
                    ? "isDeadOrDying=true 与血 " + health + " 冲突（疑似假血/被改写）⇒ 以死亡标志为准：【死】"
                    : "isDeadOrDying=true ⇒ 【死】");
        }
        if (deathTime > 0) {
            return Decision.of(true, bloodLooksAlive
                    ? "deathTime=" + deathTime + " 与血 " + health + " 冲突（疑似假血）⇒ 以计时器为准：【死】"
                    : "deathTime=" + deathTime + " > 0 ⇒ 【死】");
        }
        if (health <= EPS) {
            return Decision.of(true, "血 " + health + " <= 0 ⇒ 【死】");
        }
        if (hurtTime > 0) {
            return Decision.of(false, "血 " + health + " > 0、死亡标志全假；hurtTime=" + hurtTime
                    + " 只是受击计时 ⇒ 【未死】");
        }
        return Decision.of(false, "血 " + health + " > 0、死亡标志全假、计时器归零 ⇒ 【未死】");
    }

    // ═══════════════════════════════ ⑦ 事件层签名（薄委托：签名的唯一来源仍是上面的判定） ═══════════════════════════════
    //
    // 🔴 为什么要这一段：事件层（ShanhaiGuardEvents，20:00 写）调用的就是下面这几个名字，
    //    而本核（20:03 重写）当时<b>一个都没带上</b> ⇒ 整个 guard 包编译不过（主代理实测：
    //    `ShanhaiGuardEvents.java:50: 找不到符号 —— 类 LogGate`）。
    //    ⇒ 这里补上，但**不写第二份判定**：每一个都是对上面既有判定的薄委托，避免"两套判据分裂"。

    /** 日志前缀（事件层直接打这一整行）。 */
    public static final String LOG_PREFIX = "[SHANHAI-GUARD] ";

    /** 🔴 每秒最多一行（同类在窗口内合并计数）。 */
    public static final long LOG_MIN_INTERVAL_MILLIS = 1000L;

    /**
     * 「是不是那一件」—— 按 id 的<b>两段</b>判（Forge 侧把 {@code BuiltInRegistries.ITEM.getKey(...)} 拆开喂进来）。
     *
     * <p>与 {@link #isModuleId(String)} 同口径（那条另外还认裸 path 形态）。
     */
    public static boolean isGuardItem(String namespace, String path) {
        return MODULE_NAMESPACE.equals(namespace) && MODULE_PATH.equals(path);
    }

    /** 这次的「即将受伤」要不要取消（事件层签名）—— 薄委托给 {@link #blockDamageDecision}。 */
    public static boolean blocksDamage(boolean holdingModule) {
        return blockDamageDecision(holdingModule ? MODULE_ID : null, true, enabled).yes;
    }

    /** 这次的「即将死亡」要不要取消（事件层签名）—— 薄委托给 {@link #blockDeathDecision}。 */
    public static boolean blocksDeath(boolean holdingModule) {
        return blockDeathDecision(holdingModule ? MODULE_ID : null, true, enabled).yes;
    }

    /** 「当场回满」要补多少血（事件层签名）= 缺失量；不补 ⇒ 0（一次 {@code setHealth} 都不发）。 */
    public static float healAmount(boolean holdingModule, float health, float maxHealth) {
        if (!holdingModule || !healDecision(health, maxHealth, enabled).yes) {
            return 0f;
        }
        final float missing = healTarget(maxHealth) - health;
        return missing > 0f ? missing : 0f;
    }

    /** 这一 tick 该不该动这个持有者（事件层签名）。 */
    public static boolean tickActs(boolean holdingModule) {
        return enabled && holdingModule;
    }

    /** 这次的「要开新屏幕」要不要取消（事件层签名）：新屏幕是死亡界面、且持有模块。 */
    public static boolean blocksGameOverScreen(boolean holdingModule, boolean openingIsDeathScreen) {
        if (!openingIsDeathScreen) {
            return false;
        }
        return cancelDeathScreenDecision(DEATH_SCREEN_SIMPLE, holdingModule, enabled).yes;
    }

    /**
     * <b>「同类合并 ＋ 每秒最多一行」的日志闸门</b>（纯 JDK、时间由调用方喂 ⇒ 离线可复现）。
     *
     * <pre>
     *   距上次落盘 &lt; 1 秒 ⇒ 本次不落盘，只把「哪一类」累计进待合并表
     *   距上次落盘 ≥ 1 秒 ⇒ 落一行：{@code [SHANHAI-GUARD] 拦截 伤害（同类合并 7 次） + 死亡}
     * </pre>
     *
     * <p>行内只报「拦了什么」—— 每秒最多一行，读日志的人不会被刷屏。
     */
    public static final class LogGate {

        private long lastEmitMillis = Long.MIN_VALUE / 2L;
        private final Map<String, Long> pending = new LinkedHashMap<>();

        /**
         * @param kind      类别（{@code 伤害} / {@code 死亡} / {@code 补血} / {@code 死亡界面} …）
         * @param nowMillis 当前毫秒（调用方喂，便于离线用假时钟复现）
         * @return 该打出去的一整行；本次不打则 {@code null}
         */
        public synchronized String admit(String kind, long nowMillis) {
            // 🔴 2026-10-10：只为"聊天栏那条路"多开了一个出口（admitBatch），日志这条路的
            //    行为逐字未变 —— 同一份 pending、同一个 render、同一套「每秒最多一行」的判断。
            final Batch batch = admitBatch(kind, nowMillis);
            return batch == null ? null : batch.logLine();
        }

        /**
         * 🔴 <b>同一套闸门的结构化出口</b>（2026-10-10 加，为聊天栏而加）。
         *
         * <p>同类照旧合并、每秒照旧最多一行；区别只是把这一窗口的合并结果<b>整体</b>交出来
         * —— 日志那一行与聊天栏那一行取的是<b>同一份计数</b>，不存在"两边各算一次、数字对不上"。
         *
         * @param kind      类别（与 {@link #admit} 同一套类别串）
         * @param nowMillis 当前毫秒（调用方喂，便于离线用假时钟复现）
         * @return 这一窗口该落盘的那一批；本次不打 ⇒ {@code null}
         */
        public synchronized Batch admitBatch(String kind, long nowMillis) {
            pending.merge(kind == null ? "未知" : kind, 1L, Long::sum);
            if (nowMillis - lastEmitMillis < LOG_MIN_INTERVAL_MILLIS) {
                return null;
            }
            lastEmitMillis = nowMillis;
            final Batch batch = new Batch(pending);
            pending.clear();
            return batch;
        }

        /** 自上次 {@link #admit} 真正落盘以来，攒下没打的次数（读数用）。 */
        public synchronized long suppressed() {
            long n = 0L;
            for (final Long v : pending.values()) {
                n += v;
            }
            return n;
        }

        public synchronized void reset() {
            pending.clear();
            lastEmitMillis = Long.MIN_VALUE / 2L;
        }

        private static String render(Map<String, Long> merged) {
            final StringBuilder sb = new StringBuilder(LOG_PREFIX).append("拦截 ");
            boolean first = true;
            for (final Map.Entry<String, Long> e : merged.entrySet()) {
                if (!first) {
                    sb.append(" + ");
                }
                first = false;
                sb.append(e.getKey());
                if (e.getValue() > 1L) {
                    sb.append("（同类合并 ").append(e.getValue()).append(" 次）");
                }
            }
            return sb.toString();
        }

        /**
         * 一个窗口里合并出来的结果（<b>不可变快照</b>）。
         *
         * <p>两行文案取自<b>同一份计数</b>：
         * <ul>
         *   <li>{@link #logLine()} ⇒ 日志那一行（{@code [SHANHAI-GUARD] 拦截 …（同类合并 N 次）}，
         *       与加本方法之前逐字相同）；</li>
         *   <li>{@link #chatLine()} ⇒ 给玩家本人那一行（{@code [创始现实修改模块] CANCELLED LivingAttackEvent xN}）。</li>
         * </ul>
         */
        public static final class Batch {

            private final Map<String, Long> merged;

            Batch(Map<String, Long> source) {
                this.merged = new LinkedHashMap<>(source);   // 拷一份：pending 随后就被 clear
            }

            /** 本窗口各类别来了几次（只读快照；键的顺序 = 首次出现的顺序）。 */
            public Map<String, Long> merged() {
                return java.util.Collections.unmodifiableMap(merged);
            }

            /** 本窗口一共拦了几次（各类别相加）。 */
            public long total() {
                long n = 0L;
                for (final Long v : merged.values()) {
                    n += (v == null ? 0L : v);
                }
                return n;
            }

            /** 日志那一行（与加本方法之前逐字相同）。 */
            public String logLine() {
                return render(merged);
            }

            /** 🔴 给玩家本人的那一行 ＋ 前缀；本窗口一个"该给玩家看"的类别都没有 ⇒ {@code null}（一条都不发）。 */
            public String chatLine() {
                return ShanhaiGuardCore.chatLine(merged);
            }
        }
    }

    // ═══════════════════════════════ ⑧【补件①】传送通道：位置离谱 ⇒ 拉回上次正常位置 ═══════════════════════════════

    /** 🔴 |x| 或 |z| 超过这个值 = 离谱（原版世界边界就是 3.0e7）。 */
    public static final double MAX_HORIZONTAL = 3.0e7;
    /** 🔴 |y| 超过这个值 = 离谱（原版建造高度上限 4096）。 */
    public static final double MAX_Y = 4096.0;

    /** 位置「离谱」吗（有人用 {@code setPos(1e9, 0, 0)} 把你送走）。 */
    public static boolean isPositionAbsurd(double x, double y, double z) {
        return positionDecision(x, y, z).yes;
    }

    /** 位置「正常」吗 = {@code !isPositionAbsurd}。 */
    public static boolean isPositionSane(double x, double y, double z) {
        return !isPositionAbsurd(x, y, z);
    }

    /**
     * 🔴 位置判定的原因串版：{@code yes} = <b>离谱 ⇒ 要拉回</b>。
     *
     * <pre>
     *   x / y / z 任一是 NaN          ⇒ 离谱（NaN 与任何数比较都是 false ⇒ 必须显式判，不能靠 &gt; 阈值兜住）
     *   |x| &gt; 3.0e7 或 |z| &gt; 3.0e7  ⇒ 离谱（±Infinity 也被这条兜住）
     *   |y| &gt; 4096                    ⇒ 离谱
     *   其余                          ⇒ 正常
     * </pre>
     */
    public static Decision positionDecision(double x, double y, double z) {
        final String readings = "x=" + x + " y=" + y + " z=" + z;
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return Decision.of(true, "位置读到 NaN（" + readings + "）⇒ 离谱，要拉回");
        }
        if (Math.abs(x) > MAX_HORIZONTAL) {
            return Decision.of(true, "|x|=" + Math.abs(x) + " > " + MAX_HORIZONTAL + "（" + readings + "）⇒ 离谱，要拉回");
        }
        if (Math.abs(z) > MAX_HORIZONTAL) {
            return Decision.of(true, "|z|=" + Math.abs(z) + " > " + MAX_HORIZONTAL + "（" + readings + "）⇒ 离谱，要拉回");
        }
        if (Math.abs(y) > MAX_Y) {
            return Decision.of(true, "|y|=" + Math.abs(y) + " > " + MAX_Y + "（" + readings + "）⇒ 离谱，要拉回");
        }
        return Decision.of(false, "位置正常（" + readings + "）⇒ 不拉回");
    }

    /**
     * 🔴 <b>自己记一份「上次正常位置」</b>（每 tick 由事件层喂一次坐标）。
     *
     * <p>离谱的那一帧<b>绝不覆盖</b>记住的坐标 —— 否则第二次离谱时"上次正常位置"已经被污染，
     * 会把人拉到一个同样死掉的地方。
     */
    public static final class PositionTrack {

        private boolean hasLast = false;
        private double lastX;
        private double lastY;
        private double lastZ;

        /** 有没有可拉回的坐标（没有就<b>不拉</b>，免得把人拉进 (0, 0, 0)）。 */
        public boolean hasLastSane() {
            return hasLast;
        }

        public double lastSaneX() {
            return lastX;
        }

        public double lastSaneY() {
            return lastY;
        }

        public double lastSaneZ() {
            return lastZ;
        }

        /** 只记正常位置（调用方自己先判过；这里不再判一次，避免两套判据）。 */
        public void record(double x, double y, double z) {
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.hasLast = true;
        }

        /**
         * 一次判定 ＋ 记录：正常 ⇒ 记为上次正常位置（{@code yes=false}）；
         * 离谱且有记录 ⇒ {@code yes=true}（拉回 {@link #lastSaneX()} 等）；
         * 离谱但还没有记录 ⇒ {@code yes=false}（<b>不猜坐标</b>，原因串里说明）。
         */
        public Decision check(double x, double y, double z) {
            final Decision d = positionDecision(x, y, z);
            if (!d.yes) {
                record(x, y, z);
                return Decision.of(false, d.reason + "；已记为「上次正常位置」");
            }
            if (!hasLast) {
                return Decision.of(false, d.reason + "，但还没有「上次正常位置」可拉回 ⇒ 本次不拉回（不猜坐标）");
            }
            return Decision.of(true, d.reason + " ⇒ 拉回上次正常位置 (" + lastX + ", " + lastY + ", " + lastZ + ")");
        }
    }

    // ═══════════════════════════════ ⑨【补件②⑤】被移除检测（含两套名单分裂） ═══════════════════════════════

    /**
     * 🔴 <b>两套名单分裂</b>（⑤）：{@code PlayerList} 与 {@code ServerLevel.players()} 会不一致。
     *
     * <p>判「他还在不在世界名单里」以 <b>{@code ServerLevel.players()}</b> 为准（世界名单才是实体真在的那个表）。
     * 两个读数不一致本身就<b>要报警</b> —— 它意味着有人绕过了正常的加入/移除路径。
     */
    public static Decision rosterSplitDecision(boolean inPlayerList, boolean inLevelRoster) {
        if (inPlayerList != inLevelRoster) {
            return Decision.of(true, "🔴 两套名单分裂：PlayerList 命中=" + inPlayerList
                    + "，ServerLevel.players() 命中=" + inLevelRoster + " ⇒ 以【世界名单】(ServerLevel.players()) 为准");
        }
        return Decision.of(false, "两套名单一致（PlayerList=" + inPlayerList
                + "，ServerLevel.players()=" + inLevelRoster + "）");
    }

    /** 判定「被移除」（②）：三种形态都算。 */
    public static boolean looksRemoved(boolean isRemoved, boolean isAddedToWorld,
                                       boolean inPlayerList, boolean inLevelRoster) {
        return removalDecision(isRemoved, isAddedToWorld, inPlayerList, inLevelRoster).yes;
    }

    /**
     * 🔴 <b>「被移除」判定的原因串版</b>（② ＋ ⑤）。
     *
     * <pre>
     *   两套名单分裂                  ⇒ 判【被移除】（⑤，以 ServerLevel.players() 为准）
     *   isRemoved() = true            ⇒ 判【被移除】
     *   isAddedToWorld() = false      ⇒ 判【被移除】🔴 有人不移除、只关这个标记
     *   不在 ServerLevel.players() 里 ⇒ 判【被移除】（世界名单是权威）
     *   其余                          ⇒ 【在】
     * </pre>
     */
    public static Decision removalDecision(boolean isRemoved, boolean isAddedToWorld,
                                           boolean inPlayerList, boolean inLevelRoster) {
        final Decision split = rosterSplitDecision(inPlayerList, inLevelRoster);
        if (split.yes) {
            return Decision.of(true, split.reason + " ⇒ 判【被移除】");
        }
        if (isRemoved) {
            return Decision.of(true, "isRemoved()=true ⇒ 判【被移除】");
        }
        if (!isAddedToWorld) {
            return Decision.of(true, "isAddedToWorld()=false（只关了标记、并没有真的移除）⇒ 判【被移除】");
        }
        if (!inLevelRoster) {
            return Decision.of(true, "不在 ServerLevel.players() 里 ⇒ 判【被移除】");
        }
        return Decision.of(false, "正常：未移除 ＋ isAddedToWorld=true ＋ 两套名单一致且都在 ⇒ 【在】");
    }

    // ═══════════════════════════════ ⑩【补件③】回血自证 ＋ 读血交叉校验 ═══════════════════════════════

    /**
     * 🔴 <b>回血自证</b>（③）：回完血当场读回复核，读回值 != 期望值 ⇒ <b>记一次失败</b>（不静默）。
     *
     * <p>{@code yes} = <b>自证失败</b>（供事件层直接进日志；计数在 {@link #healFailureCount()}）。
     */
    public static Decision healRoundTrip(float expected, float afterRead) {
        final boolean ok = healVerified(afterRead, expected);
        if (ok) {
            return Decision.of(false, "回血自证通过：读回 " + afterRead + " == 期望 " + expected
                    + "（累计 attempts=" + healAttemptCount() + " failures=" + healFailureCount() + "）");
        }
        return Decision.of(true, "🔴 回血自证失败：读回 " + afterRead + " != 期望 " + expected
                + "（setHealth/heal 可能被 mixin 掉）⇒ 已记一次失败，累计 failures=" + healFailureCount());
    }

    private static final AtomicLong BLOOD_CONTRADICTIONS = new AtomicLong();

    /** 读血交叉校验记下的<b>矛盾条数</b>。 */
    public static long bloodContradictionCount() {
        return BLOOD_CONTRADICTIONS.get();
    }

    /** 清空矛盾计数（离线复现/换局时用）。 */
    public static void resetBloodContradictionCount() {
        BLOOD_CONTRADICTIONS.set(0L);
    }

    /**
     * 🔴 <b>读血交叉校验</b>（③后半）：{@code getHealth()} 与死亡标志 / 计时器互相矛盾 ⇒ 记一条。
     *
     * <pre>
     *   deathTime &lt; 0 或 hurtTime &lt; 0            ⇒ 矛盾（vanilla 不可能为负）
     *   血 &gt; 0 且（isDeadOrDying 或 deathTime&gt;0）⇒ 矛盾（假血/被改写）
     *   血 &lt;= 0 或 NaN 且没有任何死亡标志          ⇒ 矛盾（死亡路径被掐掉 或 读数坏了）
     *   其余                                       ⇒ 自洽
     * </pre>
     *
     * <p>🔴 「血 &gt; 0 ＋ 只 hurtTime &gt; 0」<b>不算矛盾</b>（受击计时 ≠ 死）—— 与 {@link #looksDead} 同口径。
     *
     * @return {@code yes} = 记了一条矛盾（计数已 +1）
     */
    public static Decision crossCheckBlood(float health, boolean isDeadOrDying, int deathTime, int hurtTime) {
        final String readings = "血=" + health + " isDeadOrDying=" + isDeadOrDying
                + " deathTime=" + deathTime + " hurtTime=" + hurtTime;
        if (deathTime < 0 || hurtTime < 0) {
            BLOOD_CONTRADICTIONS.incrementAndGet();
            return Decision.of(true, "🔴 计时器为负（vanilla 不可能）：" + readings + " ⇒ 记一条矛盾");
        }
        if (health > EPS && (isDeadOrDying || deathTime > 0)) {
            BLOOD_CONTRADICTIONS.incrementAndGet();
            return Decision.of(true, "🔴 血 > 0 却带死亡标志（假血/被改写）：" + readings + " ⇒ 记一条矛盾");
        }
        if (!(health > EPS) && !isDeadOrDying && deathTime == 0) {
            BLOOD_CONTRADICTIONS.incrementAndGet();
            return Decision.of(true, "🔴 血 <= 0 或 NaN 却没有任何死亡标志（死亡路径被掐掉 或 读数坏了）："
                    + readings + " ⇒ 记一条矛盾");
        }
        return Decision.of(false, "读血自洽：" + readings + " ⇒ 不记");
    }

    // ═══════════════════════════════ ⑪【补件④】补回物品（有人反射直调 dropAllDeathLoot 掏空背包） ═══════════════════════════════

    /** 🔴 补回物品总开关（默认开；观测到"合法丢掉模块"的场景可关）。 */
    public static volatile boolean restoreModuleWhenMissing = true;

    private static final AtomicLong RESTORE_OK = new AtomicLong();
    private static final AtomicLong RESTORE_FAIL = new AtomicLong();

    /**
     * 🔴 <b>补回判据</b>（④）：<b>登记过的持有者</b>，但背包 ＋ 副手 ＋ 盔甲里都没有模块 ⇒ 补回。
     *
     * <p>🔴 只对"登记过的持有者"为真 ⇒ 本判据<b>没有</b>「遍历世界找谁该有模块」的入口；
     * 「找到模块」这个读数由事件层<b>只扫这一个持有者</b>的 inventory/offhand/armor 得到。
     *
     * @param registeredHolder 事件层登记过的持有者（一旦持有过就记住，防止"掏空背包 ⇒ 判据自己失效"）
     * @param foundInInventory 背包 ＋ 副手 ＋ 盔甲里找到模块了吗
     * @return {@code yes} = 要补回
     */
    public static Decision restoreModuleDecision(boolean registeredHolder, boolean foundInInventory) {
        if (!restoreModuleWhenMissing) {
            return Decision.of(false, "补回开关关闭（restoreModuleWhenMissing=false）⇒ 不补");
        }
        if (!registeredHolder) {
            return Decision.of(false, "不是登记过的持有者 ⇒ 不补（不无中生有、不遍历世界）");
        }
        if (foundInInventory) {
            return Decision.of(false, "背包＋副手＋盔甲里都在（找到 " + MODULE_ID + "）⇒ 不补");
        }
        return Decision.of(true, "登记过的持有者，但背包＋副手＋盔甲里都没有 " + MODULE_ID
                + "（可能被 dropAllDeathLoot 之类掏空）⇒ 补回一件");
    }

    /** 事件层补完一件后记账（成功/失败分开记，失败不静默）。 */
    public static void noteRestoreResult(boolean ok) {
        if (ok) {
            RESTORE_OK.incrementAndGet();
        } else {
            RESTORE_FAIL.incrementAndGet();
        }
    }

    /** 补回成功的次数。 */
    public static long moduleRestoreOkCount() {
        return RESTORE_OK.get();
    }

    /** 🔴 补回<b>失败</b>的次数（背包满 / 物品没注册），不静默。 */
    public static long moduleRestoreFailCount() {
        return RESTORE_FAIL.get();
    }

    /** 清空补回计数（离线复现/换局时用）。 */
    public static void resetRestoreCounters() {
        RESTORE_OK.set(0L);
        RESTORE_FAIL.set(0L);
    }

    // ═══════════════════════════════ ⑫【主动放手】补回判据收紧（用户原话逐字就是规格） ═══════════════════════════════
    //
    // 用户原话（2026-10-10）：
    //   「我选1，但是放入箱子或者 ae 或者我们的机器槽位中也算主动啊，不需要补啊」
    // ⇒ 补回的**唯一**条件是【被动失去】：
    //   ① 背包（含副手 ＋ 盔甲）里没有
    //   ② **不是**通过「丢出」通道离开的（按键丢出 = 主动放手 ⇒ 本局卸下保护）
    //   ③ 当前打开的容器菜单里也没有它（箱子／我们的机器槽位／各种 GUI）＋ AE/ME 网络里也没有多一份
    //   三条**同时**成立才补；任何一条不成立都**不补**（宁可不补，也不把人主动放好的东西抢回来）。

    /** 「丢出」通道的时间窗（tick）：按键丢出之后这么久内的"背包里没有"才算【走丢出通道离开的】。 */
    public static final long TOSS_WINDOW_TICKS = 200L;

    /** 这一 tick 走丢出通道了吗（丢出发生在窗口内 ＋ 背包里已经没有模块）。 */
    public static boolean leftViaDropChannel(long nowTick, long tossedAtTick, boolean foundInInventory) {
        if (foundInInventory) {
            return false; // 背包里还有 ⇒ 丢的可能是多出来的那一份，不算放手
        }
        if (tossedAtTick < 0L) {
            return false; // 没有丢出记录
        }
        final long age = nowTick - tossedAtTick;
        return age >= 0L && age <= TOSS_WINDOW_TICKS;
    }

    /** ME/AE 探针的三种结局（＋"本次没查"）。 */
    public enum MeProbeOutcome {

        /** 本次没查（背包里还有模块 ⇒ 不需要查；或探针还没轮到）。 */
        NOT_PROBED("本次未查（背包里还有模块，不需要查）"),
        /** 查到了：网络里还有一份 ⇒ 主动放手。 */
        FOUND("查到：AE/ME 网络里还有一份"),
        /** 网络可达，但里面没有这一份 ⇒ 不算主动放手。 */
        NOT_FOUND("网络可达，但网络里没有这一份"),
        /** 🔴 接口不可用（没装无线终端／未接网／抛异常）⇒ **当作没有**（宁可信其无），但要留一行日志。 */
        UNAVAILABLE("接口不可用（没有可用的无线终端／未接网／探针抛异常）⇒ 当作没有");

        private final String detail;

        MeProbeOutcome(String detail) {
            this.detail = detail;
        }

        /** 人话一句（直接进日志）。 */
        public String detail() {
            return detail;
        }

        /** 🔴 只有 {@link #FOUND} 才算"网络里还有一份"；查不到／不可用一律当作没有。 */
        public boolean countsAsPresent() {
            return this == FOUND;
        }
    }

    /**
     * 🔴 <b>「只在背包里没有的那一刻查一次」的闸门</b> —— 绝不每 tick 查 ME。
     *
     * <pre>
     *   背包里【有】模块  ⇒ 不查，并把本局探针复位（下一次"缺失"是新的一局）
     *   背包里【没有】且本轮还没查过 ⇒ 查一次（{@link #needsProbe} 返回 true）
     *   背包里【没有】且本轮已查过   ⇒ 不再查（用缓存结局）
     * </pre>
     */
    public static final class MeProbeGate {

        private boolean probedInThisEpisode = false;
        private MeProbeOutcome outcome = MeProbeOutcome.NOT_PROBED;

        /** 现在该不该去查 ME（true = 查这一次；返回 false 时一律用 {@link #outcome()} 的缓存）。 */
        public boolean needsProbe(boolean foundInInventory) {
            if (foundInInventory) {
                reset();
                return false;
            }
            return !probedInThisEpisode;
        }

        /** 记下这次探针的结局（null 当作 {@link MeProbeOutcome#UNAVAILABLE}，不假报成功）。 */
        public void record(MeProbeOutcome o) {
            this.probedInThisEpisode = true;
            this.outcome = (o == null ? MeProbeOutcome.UNAVAILABLE : o);
        }

        /** 本局探针的结局（没查过 = {@link MeProbeOutcome#NOT_PROBED}）。 */
        public MeProbeOutcome outcome() {
            return outcome;
        }

        /** 本局查过没有（读数用）。 */
        public boolean probed() {
            return probedInThisEpisode;
        }

        public void reset() {
            this.probedInThisEpisode = false;
            this.outcome = MeProbeOutcome.NOT_PROBED;
        }
    }

    /**
     * 🔴 <b>补回判据（收紧版）</b>：只有【被动失去】才补回，三条同时成立。
     *
     * <pre>
     *   ① 背包（含副手 ＋ 盔甲）里没有模块        foundInInventory == false
     *   ② 不是通过「丢出」通道离开的              leftViaDropChannel == false
     *   ③ 当前打开的容器菜单里也没有它            foundInOpenContainer == false
     *     ＋ AE/ME 网络里也没有多一份             me.countsAsPresent() == false
     * </pre>
     *
     * <p>🔴 只对"登记过的持有者"为真 ⇒ 本判据<b>没有</b>「遍历世界找谁该有模块」的入口。
     *
     * @param registeredHolder     事件层登记过的持有者（一旦持有过就记住）
     * @param foundInInventory     背包 ＋ 副手 ＋ 盔甲里找到模块了吗
     * @param leftViaDropChannel   是不是通过「丢出」通道离开的（按键丢出／从容器丢出）
     * @param foundInOpenContainer 当前打开的容器菜单（{@code player.containerMenu}）里有它吗
     * @param me                   AE/ME 探针结局（null ⇒ 当作接口不可用）
     * @return {@code yes} = 要补回
     */
    public static Decision restoreModuleDecision(boolean registeredHolder, boolean foundInInventory,
                                                 boolean leftViaDropChannel, boolean foundInOpenContainer,
                                                 MeProbeOutcome me) {
        if (!restoreModuleWhenMissing) {
            return Decision.of(false, "补回开关关闭（restoreModuleWhenMissing=false）⇒ 不补");
        }
        if (!registeredHolder) {
            return Decision.of(false, "不是登记过的持有者 ⇒ 不补（不无中生有、不遍历世界）");
        }
        if (foundInInventory) {
            return Decision.of(false, "①不成立：背包＋副手＋盔甲里就有 " + MODULE_ID + " ⇒ 不是【被动失去】，不补");
        }
        if (leftViaDropChannel) {
            return Decision.of(false, "②不成立：他是通过「丢出」通道放开的（按键丢出／从容器丢出）"
                    + "⇒ 主动放手 ⇒ 不补（并且本局卸下保护）");
        }
        if (foundInOpenContainer) {
            return Decision.of(false, "③不成立：当前打开的容器菜单里就有它（箱子／我们的机器槽位／各种 GUI）"
                    + "⇒ 主动放手 ⇒ 不补");
        }
        final MeProbeOutcome o = (me == null ? MeProbeOutcome.UNAVAILABLE : me);
        if (o.countsAsPresent()) {
            return Decision.of(false, "AE/ME 网络里还有一份（" + o.detail() + "）⇒ 主动放手 ⇒ 不补");
        }
        return Decision.of(true, "【被动失去】三条全成立（①背包（含副手/盔甲）没有 ②不是丢出通道 "
                + "③打开的容器里也没有）且 AE/ME 探针不认为还有一份（" + o.detail() + "）⇒ 补回一件");
    }

    /**
     * 走「丢出」通道 = 主动放手 ⇒ <b>本局卸下保护</b>（不再补回，也不再挡伤害/拦死亡）。
     *
     * <p>🔴 背包里还有别的一份时<b>不卸</b>（丢的可能是多出来的那一份）。
     */
    public static Decision disarmDecision(boolean registeredHolder, boolean foundInInventory,
                                          boolean leftViaDropChannel) {
        if (!registeredHolder) {
            return Decision.of(false, "不是登记过的持有者 ⇒ 不用卸下保护");
        }
        if (foundInInventory) {
            return Decision.of(false, "背包里还有模块 ⇒ 不卸下保护（他丢的可能是多出来的那一份）");
        }
        if (!leftViaDropChannel) {
            return Decision.of(false, "不是走「丢出」通道 ⇒ 不卸下保护（被动失去要保护、要补回）");
        }
        return Decision.of(true, "🔴 通过「丢出」通道主动放开模块（背包里已经没有，且不是被外力拿走）"
                + "⇒ 本局卸下保护：不再补回、也不再挡伤害/拦死亡（他再拿到模块时自动恢复）");
    }

    // ═══════════════════════════════ ⑬【救回】真的把它救回来（不是只打一行 warning） ═══════════════════════════════
    //
    // 🔴 三条硬约束（顺序与返回值都是判据）：
    //   ① 顺序死：先把位置放回去 → revive() → level().addFreshEntity(entity)（反了 = 只打一行 warning 然后永远回不来）
    //   ② 必须判 addFreshEntity 的返回值：false（区块未加载／uuid 重复／实体类型不许）就报警，别当成功
    //   ③ 先过滤「正常离开」：只对【被外力移除】动手 —— 换维度／登出／正常死亡／区块卸载一律不动手
    //   ＋ 玩家实体只做"实体层能救就救"：PlayerList／连接／tab／level.players() 四套名单的完整重建本轮**不做**。

    /** 救回三步的**唯一顺序**（{@link #rescueOrderCorrect} 判的就是它）。 */
    public static final String STEP_RESTORE_POSITION = "restorePosition";
    /** 第②步：实体层复活（必须在重挂之前）。 */
    public static final String STEP_REVIVE = "revive";
    /** 第③步：重新挂进世界（返回值必须判）。 */
    public static final String STEP_ADD = "addFreshEntity";

    /** 救回三步的固定顺序（每次返回新数组）。 */
    public static String[] rescueStepOrder() {
        return new String[] { STEP_RESTORE_POSITION, STEP_REVIVE, STEP_ADD };
    }

    /**
     * 🔴 <b>救回三步的顺序判据</b>：位置 → revive → 重挂，三步都在且严格递增才算对。
     *
     * <p>负对照：把顺序倒过来喂进来，本判据<b>必须</b>返回 false（见 main 里的突变体）。
     */
    public static boolean rescueOrderCorrect(List<String> trace) {
        if (trace == null) {
            return false;
        }
        final int p = trace.indexOf(STEP_RESTORE_POSITION);
        final int r = trace.indexOf(STEP_REVIVE);
        final int a = trace.indexOf(STEP_ADD);
        return p >= 0 && r >= 0 && a >= 0 && p < r && r < a;
    }

    /**
     * 🔴 <b>救回要动的那三下</b>（放在核里 ⇒ 顺序由核保证，事件层只提供 Minecraft 实现）。
     *
     * <p>事件层实现时：{@code restorePosition()} 只放实体层坐标（<b>不碰连接</b>）、
     * {@code revive()} 就是 {@code Entity#revive()}、{@code addFreshEntity()} 就是
     * {@code ServerLevel#addFreshEntity(entity)}（<b>返回值照原样返回</b>）。
     */
    public interface RescueOps {

        /** ① 先把位置放回去（重挂是按"当前位置"挑区块的）。 */
        void restorePosition();

        /** ② 实体层复活（顺序上必须在 {@code addFreshEntity} 之前）。 */
        void revive();

        /** ③ 重新挂进世界；<b>返回值必须如实返回</b>（false = 没救回来）。 */
        boolean addFreshEntity();
    }

    private static final AtomicLong RESCUE_OK = new AtomicLong();
    private static final AtomicLong RESCUE_FAIL = new AtomicLong();
    private static final AtomicLong RESCUE_SKIPPED = new AtomicLong();

    /** 救回成功次数。 */
    public static long rescueOkCount() {
        return RESCUE_OK.get();
    }

    /** 🔴 救回<b>失败</b>次数（{@code addFreshEntity} 返回 false／抛异常），不静默。 */
    public static long rescueFailCount() {
        return RESCUE_FAIL.get();
    }

    /** 因为"正常离开／没被移除"而<b>没动手</b>的次数。 */
    public static long rescueSkippedCount() {
        return RESCUE_SKIPPED.get();
    }

    /** 清空救回计数（离线复现/换局时用）。 */
    public static void resetRescueCounters() {
        RESCUE_OK.set(0L);
        RESCUE_FAIL.set(0L);
        RESCUE_SKIPPED.set(0L);
    }

    private static Decision rescueSkip(String reason) {
        RESCUE_SKIPPED.incrementAndGet();
        return Decision.of(false, reason);
    }

    private static Decision rescueFail(String reason) {
        RESCUE_FAIL.incrementAndGet();
        return Decision.of(false, reason);
    }

    /** 玩家实体的注记（四套名单的完整重建本轮不做 ⇒ 每次都要说清楚）。 */
    private static String playerRebuildNote(boolean isPlayer) {
        if (!isPlayer) {
            return "";
        }
        return "；⚠️ 玩家实体：本次只做【实体层能救就救】—— PlayerList／连接／tab／level.players() "
                + "四套名单的完整重建【本轮不做】（否则会出现「服务器认为他还在线、世界认为他不在」的分裂）";
    }

    /**
     * 🔴 <b>「真的把它救回来」的总判据</b>（{@code yes} = 救回来了）。
     *
     * <pre>
     *   开关关闭                                    ⇒ 不动手
     *   removalReason = CHANGED_DIMENSION            ⇒ 换维度，正常离开 ⇒ 绝不动手
     *   removalReason = UNLOADED_WITH_PLAYER         ⇒ 登出／随玩家卸载，正常离开 ⇒ 绝不动手
     *   removalReason = UNLOADED_TO_CHUNK            ⇒ 区块卸载，正常离开（还会回来）⇒ 绝不动手
     *   removalReason = KILLED                       ⇒ 正常死亡／kill ⇒ 绝不动手
     *   removalReason = DISCARDED ＋ shouldDestroy    ⇒ 【被外力移除】⇒ 动手（三步，顺序固定）
     *   removalReason = DISCARDED ＋ !shouldDestroy   ⇒ 口径冲突 ⇒ 宁可不救，报警
     *   removalReason = null ＋ isAddedToWorld=false  ⇒ 只关了标记（外力）⇒ 动手
     *   removalReason = null ＋ isAddedToWorld=true   ⇒ 他还在 ⇒ 不动手
     *   其它没见过的 reason                           ⇒ 不猜 ⇒ 不动手（报警）
     * </pre>
     */
    public static Decision rescueRemovedEntity(boolean enabledFlag, String removalReasonName, boolean shouldDestroy,
                                               boolean isAddedToWorld, boolean isPlayer, RescueOps ops) {
        if (!enabledFlag) {
            return rescueSkip("开关关闭（enabled=false）⇒ 不动手");
        }
        final String reason = removalReasonName == null ? "" : removalReasonName.trim();
        if ("CHANGED_DIMENSION".equals(reason)) {
            return rescueSkip("removalReason=CHANGED_DIMENSION（换维度）⇒ 正常离开 ⇒ 绝不动手");
        }
        if ("UNLOADED_WITH_PLAYER".equals(reason)) {
            return rescueSkip("removalReason=UNLOADED_WITH_PLAYER（登出／随玩家卸载）⇒ 正常离开 ⇒ 绝不动手");
        }
        if ("UNLOADED_TO_CHUNK".equals(reason)) {
            return rescueSkip("removalReason=UNLOADED_TO_CHUNK（区块卸载）⇒ 正常离开（还会回来）⇒ 绝不动手");
        }
        if ("KILLED".equals(reason)) {
            return rescueSkip("removalReason=KILLED（正常死亡／kill）⇒ 正常离开 ⇒ 绝不动手");
        }
        if ("DISCARDED".equals(reason)) {
            if (!shouldDestroy) {
                return rescueFail("⚠️ removalReason=DISCARDED 但 shouldDestroy()=false（vanilla 不可能）"
                        + "⇒ 口径冲突 ⇒ 宁可不救（报警）");
            }
        } else if (reason.isEmpty()) {
            if (isAddedToWorld) {
                return rescueSkip("removalReason=null 且 isAddedToWorld()=true ⇒ 他还在世界里 ⇒ 不动手");
            }
        } else {
            return rescueFail("不认识的 removalReason=" + reason + " ⇒ 不猜 ⇒ 绝不动手（报警）");
        }
        if (ops == null) {
            return rescueFail("没有可用的救回实现（ops=null）⇒ 不动手（报警）");
        }
        // ── 到这里才动手：三步，顺序固定（位置 → revive → 重挂）──
        try {
            ops.restorePosition();
        } catch (Throwable t) {
            return rescueFail("🔴 第①步【放回位置】抛异常：" + t + " ⇒ 没救回来（报警）");
        }
        try {
            ops.revive();
        } catch (Throwable t) {
            return rescueFail("🔴 第②步【revive()】抛异常：" + t + " ⇒ 没救回来（报警）");
        }
        final boolean added;
        try {
            added = ops.addFreshEntity();
        } catch (Throwable t) {
            return rescueFail("🔴 第③步【addFreshEntity()】抛异常：" + t + " ⇒ 没救回来（报警）");
        }
        final String order = STEP_RESTORE_POSITION + " → " + STEP_REVIVE + " → " + STEP_ADD;
        if (added) {
            RESCUE_OK.incrementAndGet();
            return Decision.of(true, "已救回（三步顺序：" + order + "）= true" + playerRebuildNote(isPlayer));
        }
        return rescueFail("🔴 " + STEP_ADD + "() 返回 false ⇒ 没救回来"
                + "（区块未加载／uuid 重复／实体类型不许）⇒ 报警，不当成功" + playerRebuildNote(isPlayer));
    }

    // ═══════════════════════════════ ⑭【聊天栏】只发给持有者本人（用户 2026-10-10 原话就是规格） ═══════════════════════════════
    //
    // 用户原话（逐字，两轮都是规格）：
    //   「还有那个拦截输出的我希望打在聊天栏里面，然后前缀是 [创始现实修改模块]」
    //   「还有我希望可以在【左下角可以弹出一些代码内容，可以写英文】」
    // ⇒ 第二轮把【正文】从人话改成【英文 / 代码风格】（像日志常量那样），前缀与结构一个字不动。
    //
    // 五条硬口径：
    //   ① 前缀逐字 = [创始现实修改模块]（后面接一个空格再接正文；**不改、不翻译、不加颜色码**）；
    //   ② 正文写【英文 / 代码风格】短句 —— 🔴 **纯 ASCII**（正文里不许出现任何一个非 ASCII 字符，
    //      自检里有一条判据专盯它，并带「正文塞一个中文」的负对照）；
    //   ③ 数量写成 `xN`（`x176` 这样连写）；🔴 内部 id 一律不进聊天栏
    //      （自检里另有一条判据专盯它，并带负对照）；
    //   ④ 只发给持有者本人（收件人规则 = chatRecipients），**绝不广播**；
    //   ⑤ 走【同一个】LogGate（同类合并 ＋ 每秒最多一行）⇒ 不刷屏；日志那一路照旧保留（**不是替换**）。
    //
    // 🔴 2026-10-11 用户追加口径（原话逐字就是规格）：
    //   「其实我希望左下角显示**拦截了什么事件**，或者**取消了什么事件**，写这些东西」
    //   ⇒ 上一版写的是"做了什么"（DAMAGE_BLOCKED），本版必须写"**拦了哪个事件 / 哪条代码路**"：
    //     · 有 Forge 事件的 ⇒ 写事件名（LivingAttackEvent ／ LivingDeathEvent ／ ItemTossEvent ／
    //       ScreenEvent.Opening ／ ServerTickEvent）；
    //     · 没有事件的 ⇒ 写那一步的名字（setHealth(max) ／ Entity.isRemoved() ／ Entity.isAddedToWorld()
    //       ／ ServerLevel.players() ／ inventory.add）；
    //     · **每一类都必须带一个机制名**（自检里有一条判据逐类断言它，并带「只有动作、没有机制名」的负对照）。
    //
    // ⚠️ **类别串只喂日志（render）与"哪一类该不该给玩家看"的分派**；聊天栏的正文在 chatPhrase 里，
    //    与那些中文类别串【分家】⇒ 改聊天文案**不会**动到日志行（改后日志逐字未变有专门判据守着）。
    //
    // 🔴 本类只管"说什么"与"发给谁"，怎么发（sendSystemMessage ／ displayClientMessage）在事件层。
    //
    // ⚠️ 「纯 ASCII」这条只约束【正文】。前缀那六个汉字是用户点名要的，**它是这条判据的唯一豁免**；
    //    所以判据必须写成「**去掉前缀之后**的正文里没有非 ASCII 字符」，不能写成「整行没有」。

    /** 🔴 聊天栏前缀（逐字，用户点单）。 */
    public static final String CHAT_PREFIX = "[创始现实修改模块] ";

    // ── 类别常量：唯一一份 ──
    // 🔴 这 12 个字符串原本写在 ShanhaiGuardEvents（11 个）与 ShanhaiGuardClientEvents（1 个）里，
    //    本轮把定义搬到核里（**字符逐字未变**，日志行因此逐字未变）——
    //    因为"哪一类该跟玩家说什么"这张表住在核里，两处各写一份字面量必然漂移。

    /** 挡伤害。 */
    public static final String KIND_DAMAGE = "伤害";
    /** 拦死亡。 */
    public static final String KIND_DEATH = "死亡";
    /** 补血。 */
    public static final String KIND_HEAL = "补血";
    /** 传送拉回。 */
    public static final String KIND_TELEPORT = "传送拉回";
    /** 被移除。 */
    public static final String KIND_REMOVED = "被移除";
    /** 读血矛盾（<b>诊断类</b>：只进日志，不进聊天栏）。 */
    public static final String KIND_BLOOD = "读血矛盾";
    /** 回血自证失败（<b>诊断类</b>：只进日志，不进聊天栏）。 */
    public static final String KIND_HEAL_FAIL = "回血自证失败";
    /** 补回模块。 */
    public static final String KIND_RESTORE = "补回模块";
    /** 主动放手·卸下保护。 */
    public static final String KIND_DISARM = "主动放手·卸下保护";
    /** AE/ME 探针（<b>诊断类</b>：只进日志，不进聊天栏）。 */
    public static final String KIND_ME = "AE/ME 探针";
    /** 已救回。 */
    public static final String KIND_RESCUED = "已救回";
    /** 死亡界面（客户端那半边用）。 */
    public static final String KIND_SCREEN = "死亡界面";

    // ═══════════════ 机制名（用户追加口径：必须回答"拦了哪个事件 / 哪条代码路"） ═══════════════
    //
    // 🔴 这些名字**不是文案作者编的**：每一个都能在 ShanhaiGuardEvents 里找到对应的那几行
    //    （行号见每条注释）——"写机制名"这件事因此是可对账的，不是修辞。

    /** {@code LivingAttackEvent}：{@code ShanhaiGuardEvents#onLivingAttack} 里 {@code setCanceled(true)}（:143-154）。 */
    public static final String MECH_LIVING_ATTACK = "LivingAttackEvent";
    /** {@code LivingDeathEvent}：{@code ShanhaiGuardEvents#onLivingDeath} 里 {@code setCanceled(true)}（:168-180）。 */
    public static final String MECH_LIVING_DEATH = "LivingDeathEvent";
    /** {@code ItemTossEvent}：{@code ShanhaiGuardEvents#onItemToss}（按键丢出／从容器丢出都走它，:399-410）。 */
    public static final String MECH_ITEM_TOSS = "ItemTossEvent";
    /** {@code ScreenEvent.Opening}：客户端 {@code ShanhaiGuardClientEvents#onScreenOpening} 里取消开屏（:64-80）。 */
    public static final String MECH_SCREEN_OPENING = "ScreenEvent.Opening";
    /** {@code TickEvent.ServerTickEvent}：{@code ShanhaiGuardEvents#onServerTick} 每 tick 兜底那一路（:205+）。 */
    public static final String MECH_SERVER_TICK = "ServerTickEvent";
    /** 直接写同步血量那一步（回血两条路最后都落到它：{@code player.setHealth(max)}）。 */
    public static final String MECH_SET_HEALTH = "setHealth(max)";
    /** 实体层移除标志（{@code guardOne} 里读它判"被外力移除"）。 */
    public static final String MECH_ENTITY_REMOVED = "Entity.isRemoved()";
    /** 只关了"已加入世界"这个标记、并没有真的移除。 */
    public static final String MECH_ADDED_TO_WORLD = "Entity.isAddedToWorld()";
    /** 世界名单（两套名单分裂时以它为准）。 */
    public static final String MECH_LEVEL_ROSTER = "ServerLevel.players()";
    /** 实体层复活那一步（救回三步的中间一步，{@code RescueOps#revive}）。 */
    public static final String MECH_REVIVE = "Entity.revive()";
    /** 重新挂进世界那一步（救回三步的最后一步，判它的返回值）。 */
    public static final String MECH_RE_ADD = "ServerLevel.addFreshEntity()";
    /** 补回一件模块那一步（{@code giveModule} → {@code Inventory#add}）。 */
    public static final String MECH_INVENTORY_ADD = "inventory.add";

    /**
     * 🔴 <b>类别 → 机制名</b>（用户 2026-10-11 追加口径的那一条）。
     *
     * <p>为什么单独开一张表、而不是把名字写死在 {@link #chatPhrase} 的字符串里：<b>为了能被断言</b> ——
     * 自检拿这张表对每一类的正文断言"必须含这个机制名"，而不是靠"文案里恰好出现过某个词"来自证。
     *
     * <p>返回 {@code null} 的三种情况（与 {@link #chatPhrase} 完全一致）：诊断类 ／ 不认识的类别 ／ kind 为 null。
     *
     * @param kind 类别（{@link #KIND_DAMAGE} 等）
     * @return 那一个机制名；这一类不该给玩家看 ⇒ {@code null}
     */
    public static String chatMechanism(String kind) {
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case KIND_DAMAGE:
                return MECH_LIVING_ATTACK;
            case KIND_DEATH:
                return MECH_LIVING_DEATH;
            case KIND_HEAL:
                return MECH_SERVER_TICK;
            case KIND_TELEPORT:
                return MECH_SERVER_TICK;
            case KIND_REMOVED:
                return MECH_ENTITY_REMOVED;
            case KIND_RESTORE:
                return MECH_INVENTORY_ADD;
            case KIND_DISARM:
                return MECH_ITEM_TOSS;
            case KIND_RESCUED:
                return MECH_REVIVE;
            case KIND_SCREEN:
                return MECH_SCREEN_OPENING;
            default:
                return null;   // 诊断类与不认识的类别
        }
    }

    /**
     * 🔴 <b>类别 → 给玩家的那一句【英文 / 代码风格】正文</b>（不含前缀）。
     *
     * <pre>
     *   返回 null 的三种情况（一律【不】发聊天栏）：
     *     · 诊断类（读血矛盾 ／ 回血自证失败 ／ AE/ME 探针）—— 那些是给读日志的人看的；
     *     · 不认识的类别（宁可少发，也不把内部串漏给玩家）；
     *     · kind == null。
     * </pre>
     *
     * <p>🔴 写这张表的五条纪律：
     * <ol>
     *   <li><b>纯 ASCII</b>：正文里一个非 ASCII 字符都不许有（前缀那六个汉字是本条的唯一豁免）；
     *       自检里有一条判据带负对照（往正文里塞一个中文 ⇒ 必须报红）。</li>
     *   <li>🔴 <b>每一类都必须带一个机制名</b>（事件名或代码路名，见 {@link #chatMechanism}）——
     *       这是用户 2026-10-11 追加口径的原话：「显示拦截了什么事件，或者取消了什么事件」。
     *       自检里有一条判据**逐类**断言它，并带负对照（把机制名抠掉、只留动作 ⇒ 必须报红）。</li>
     *   <li><b>英文 / 代码风格</b>：全大写动词（{@code CANCELLED}／{@code DENIED}／{@code DETECTED}／
     *       {@code RESTORED}）＋ 机制名 ＋ {@code xN}。</li>
     *   <li><b>数量写 {@code xN}</b>（{@code x176} 这样连写，不写"176 次"）；只有一次的那些类别不缀数量
     *       —— 一次不缀、多次才缀，这正是日志常量的习惯。</li>
     *   <li>🔴 <b>内部 id 一律不进这一列</b>：{@code shanhai:genesis_…} 那种 {@code 命名空间:路径}
     *       是<span>不许出现</span>的（{@code leaksInternal} 拿冒号当招牌形状，带负对照）。
     *       ⚠️ <b>机制名 ≠ 内部 id</b>：{@code Entity.isRemoved()} 这种"代码路名"是<u>玩家要看的答案</u>
     *       （用户点名要的就是它），而 {@code shanhai:genesis_…} 是物品的内部主键 —— 两者必须分开对待。</li>
     * </ol>
     *
     * @param kind  类别（{@link #KIND_DAMAGE} 等）
     * @param count 本窗口合并的次数（&lt; 1 一律当 1 处理）
     * @return 英文代码风格一句（纯 ASCII，且必含机制名）；不该给玩家看 ⇒ {@code null}
     */
    public static String chatPhrase(String kind, long count) {
        if (kind == null) {
            return null;
        }
        final long n = count < 1L ? 1L : count;
        final String x = " x" + n;   // 数量一律 `xN` 连写
        switch (kind) {
            case KIND_DAMAGE:
                return "CANCELLED " + MECH_LIVING_ATTACK + x;
            case KIND_DEATH:
                return "CANCELLED " + MECH_LIVING_DEATH + x + " - health restored";
            case KIND_HEAL:
                // setHealth(max) 那一步 ＋（只补多次时）数量；机制名由 ServerTickEvent 那一路给出
                return "RESTORED " + MECH_SET_HEALTH + " (" + MECH_SERVER_TICK
                        + (n > 1L ? x : "") + ")";
            case KIND_SCREEN:
                return "CANCELLED " + MECH_SCREEN_OPENING + x;
            case KIND_TELEPORT:
                return "RESTORED position (" + MECH_SERVER_TICK + " - "
                        + MECH_ADDED_TO_WORLD + " sane)";
            case KIND_REMOVED:
                // 判出"被外力移除"、但**没救回来**（或按规矩不动手）⇒ 报检测那一步
                return "DETECTED " + MECH_ENTITY_REMOVED + " - no rescue";
            case KIND_RESTORE:
                return "RESTORED module (" + MECH_INVENTORY_ADD + x + ")";
            case KIND_DISARM:
                // 一次状态变化，只报一次、不叠次数
                return "DISARMED guard (" + MECH_ITEM_TOSS + ": module handed over)";
            case KIND_RESCUED:
                return "RESCUED entity (" + MECH_REVIVE + " + " + MECH_RE_ADD + ")";
            default:
                return null;   // 诊断类与不认识的类别：不发给玩家
        }
    }

    /**
     * 🔴 <b>一整个窗口 → 一行聊天栏消息</b>：逐类取出英文标签、用 ASCII 的 {@code " + "} 连起来（一行 = 一条消息）。
     *
     * <p>为什么合成一行而不是每类一行：口径是「每秒最多一行、不刷屏」⇒ 一个窗口只能出一条。
     *
     * <p>⚠️ 连接符是<b>半角</b> {@code " + "}（不是全角 ＋）—— 整行正文必须纯 ASCII，全角加号会当场破坏它。
     *
     * @param merged 窗口快照（来自 {@link LogGate.Batch#merged()}）
     * @return 该发给玩家的那一行（含前缀）；本窗口一个"该给玩家看"的类别都没有 ⇒ {@code null}
     */
    public static String chatLine(Map<String, Long> merged) {
        if (merged == null || merged.isEmpty()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(CHAT_PREFIX);
        boolean first = true;
        for (final Map.Entry<String, Long> e : merged.entrySet()) {
            final long n = e.getValue() == null ? 0L : e.getValue();
            final String phrase = chatPhrase(e.getKey(), n);
            if (phrase == null) {
                continue;   // 诊断类：跳过它，同一个窗口里别的类别照发
            }
            if (!first) {
                sb.append(SEP);
            }
            first = false;
            sb.append(phrase);
        }
        return first ? null : sb.toString();
    }

    /** 🔴 一行里多个类别之间的连接符（半角，纯 ASCII —— 见 {@link #chatLine}）。 */
    private static final String SEP = " + ";

    /** 单独一条（不合并）的聊天栏整行：{@link #chatPhrase} ＋ 前缀；不该给玩家看 ⇒ {@code null}。 */
    public static String chatLineOf(String kind, long count) {
        final String phrase = chatPhrase(kind, count);
        return phrase == null ? null : CHAT_PREFIX + phrase;
    }

    /**
     * 🔴 <b>收件人 = 只有持有者本人</b>（<b>绝不广播</b>）。
     *
     * <p>为什么把它写成纯函数：这是"只发给他一个人"这条口径<b>唯一可机器验证</b>的地方 ——
     * 自检里拿一个"返回全部在线玩家"的突变体喂进判据，判据必须报红（见 {@code main}）。
     *
     * <p>🔴 第二个参数（在线名单）<b>本实现故意不读</b>：它只在自检里用来构造"广播给所有人"那个突变体。
     * 生产调用点传 {@code null} 就行 —— <b>不看名单</b>正是"绝不广播"的实现方式。
     *
     * @param holder        持有者本人（该收到消息的那一个）；{@code null} ⇒ 空名单（一条都不发）
     * @param onlinePlayers 在线名单（<b>故意不用</b>；只为自检的负对照存在）
     * @param <P>           收件人类型（游戏里是 {@code ServerPlayer}；自检里用字符串即可）
     * @return 只有一个元素的名单（就是他本人）；{@code holder == null} ⇒ 空名单
     */
    public static <P> List<P> chatRecipients(P holder, List<P> onlinePlayers) {
        if (holder == null) {
            return List.of();
        }
        return List.of(holder);
    }

    // ═══════════════════════════════ 自检（同文件 main） ═══════════════════════════════

    /** 输出：强制 UTF-8 写 stdout（Windows 控制台默认 GBK，会把中文读数打成乱码 ⇒ 证据就不成立）。 */
    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (Throwable t) {
            return System.out;
        }
    }

    private static int checks = 0;
    private static int reds = 0;

    /** 一条判据：{@code expected == actual} 才算过；{@code reading} 一律打印<b>真实读数</b>（不许只打 PASS）。 */
    private static void expect(String group, String name, boolean expected, boolean actual, String reading) {
        checks++;
        final boolean ok = (expected == actual);
        if (!ok) {
            reds++;
        }
        OUT.println(String.format("%-4s | %-4s | %-32s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", group, name, expected, actual, reading));
    }

    private static void note(String s) {
        OUT.println("       · " + s);
    }

    /** 回血目标不变量：{@code healTarget(max)} 必须 == max。 */
    private interface HealTargetFn {
        float apply(float max);
    }

    /**
     * 🔴 判据本体（负对照③ 把<b>另一个实现</b>喂进来，验这条判据真会报红）。
     */
    private static boolean healTargetInvariant(HealTargetFn fn) {
        final float[] ms = { 20.0f, 1.0f, 7.5f, 1024.25f };
        for (final float m : ms) {
            if (fn.apply(m) != m) {
                return false;
            }
        }
        return true;
    }

    // ─────────── 负对照的「活性证明」用的【故意写坏的实现】（放在类里，避免新建文件）───────────

    /** 【故意写坏】永远说"位置正常"（回答的是{@code isPositionSane} 那个问题）⇒ 1e9 那次传送会被整个漏掉。 */
    private static boolean mutantPositionAlwaysSane(double x, double y, double z) {
        return true;
    }

    /** 【故意写坏】只看 {@code isRemoved()} ⇒ 漏掉"只置 isAddedToWorld=false"和两套名单分裂。 */
    private static boolean mutantRemovedOnlyIsRemoved(boolean isRemoved, boolean addedToWorld,
                                                      boolean inPlayerList, boolean inLevelRoster) {
        return isRemoved;
    }

    /** 【故意写坏】回血期望值写成一半（等于"补一半血"那种 mixin）。 */
    private static float mutantHealTargetHalf(float max) {
        return max / 2f;
    }

    /** 【故意写坏】永远说"不用补回"（回答的是{@code restoreModuleDecision} 那个问题）⇒ 背包被掏空当没看见。 */
    private static boolean mutantRestoreNeverFires(boolean registeredHolder, boolean foundInInventory) {
        return false;
    }

    /** 【故意写坏】两套名单永不说"分裂"。 */
    private static boolean mutantRosterNeverSplits(boolean inPlayerList, boolean inLevelRoster) {
        return false;
    }

    /** 【故意写坏】只认"背包里没有"就补 —— 把"主动放手"（丢出／容器／AE）整个漏掉（用户原话点名的那个错）。 */
    private static boolean mutantRestoreIgnoresActiveRelease(boolean registeredHolder, boolean foundInInventory) {
        return registeredHolder && !foundInInventory;
    }

    /** 【故意写坏】丢出窗口里"背包里还有模块"也算丢出通道 ⇒ 会把人正在用的模块判成放手。 */
    private static boolean mutantTossIgnoresInventory(long nowTick, long tossedAtTick, boolean foundInInventory) {
        return tossedAtTick >= 0L;
    }

    /** 【故意写坏】换维度／登出／正常死亡也照样动手（把"正常离开"的过滤器整个漏掉）。 */
    private static boolean mutantRescueOnNormalLeave(String removalReasonName) {
        return true;
    }

    /** 【故意写坏】救回时把顺序倒过来（先重挂、再 revive）—— 那会"只打一行 warning 然后永远回不来"。 */
    private static boolean mutantRescueOrderReversed(List<String> trace) {
        return rescueOrderCorrect(List.of(STEP_ADD, STEP_REVIVE, STEP_RESTORE_POSITION));
    }

    /** 记录调用的救回实现（离线判据用；{@code addReturns} 就是要喂给判据的返回值）。 */
    private static RescueOps recorder(List<String> trace, boolean addReturns) {
        return new RescueOps() {
            @Override
            public void restorePosition() {
                trace.add(STEP_RESTORE_POSITION);
            }

            @Override
            public void revive() {
                trace.add(STEP_REVIVE);
            }

            @Override
            public boolean addFreshEntity() {
                trace.add(STEP_ADD);
                return addReturns;
            }
        };
    }

    /** 第三步抛异常的救回实现（验证"抛异常 ⇒ 报警而不是炸 tick"）。 */
    private static RescueOps thrower(List<String> trace) {
        return new RescueOps() {
            @Override
            public void restorePosition() {
                trace.add(STEP_RESTORE_POSITION);
            }

            @Override
            public void revive() {
                trace.add(STEP_REVIVE);
            }

            @Override
            public boolean addFreshEntity() {
                trace.add(STEP_ADD);
                throw new IllegalStateException("区块未加载（离线模拟）");
            }
        };
    }

    /** 探针闸门喂 null 之后的结局（验证不假报成功）。 */
    private static MeProbeOutcome newGateWithNull() {
        final MeProbeGate g = new MeProbeGate();
        g.record(null);
        return g.outcome();
    }

    // ─────────── 【聊天栏】判据本体 ＋【故意写坏】的实现（判据要能被证明是"活的"）───────────

    /**
     * 🔴 前缀<b>字面量</b>：这里<b>故意再写一遍</b>，<b>不复用</b> {@link #CHAT_PREFIX}。
     *
     * <p>为什么：用常量自己比自己对 —— 常量一旦写错，判据会跟着一起错，那条判据就等于没有。
     * （本工程踩过这个坑："自检复用了生产者那套错公式 ⇒ 永远自洽"。）
     */
    private static final String CHAT_PREFIX_LITERAL = "[创始现实修改模块]";

    /** 🔴 前缀后面必须紧跟的<b>那一个半角空格</b>（{@code "[创始现实修改模块] "} —— 用户样例逐字有形）。 */
    private static final String CHAT_PREFIX_WITH_SPACE = CHAT_PREFIX_LITERAL + " ";

    // ─────────── 两枚【故意写坏】的判据开关（只给"判据有没有牙"的负对照用；生产默认都是 false）───────────
    //
    // 🔴 为什么要留这两枚开关：证明一条判据是【活的】，最直接的办法是【把判据自己弄坏】，
    //    再看自检是不是真报红。留在这里而不是"另存一份二级源文件"，是因为二级源文件要再抄一遍全文、
    //    必然与主文件漂移（本工程已有"两处各写一份字面量必然漂移"的教训）。
    //
    // 🔴 2026-10-10 实测教训（这两枚开关的来历）：
    //    第一版把 bodyIsPureAscii 的【整段前缀闸门】删掉，187 条判据【全绿】——
    //    因为当时所有样本的属主前缀都是对的，那道闸门根本没被踩到 ⇒ 它是【没载荷】的。
    //    后来补了"错前缀 ＋ 正文纯 ASCII"那一条样本（见 main 的 负①b），闸门才真正被踩到。
    //
    // 用法（不需要二级源文件、不需要文本替换）：
    //   java -Dshanghai.mutant=skipPrefixGuard -cp <out> com.shanhai.common.guard.ShanhaiGuardCore   ⇒ 必须报红
    //   java -Dshanghai.mutant=skipNonAscii    -cp <out> com.shanhai.common.guard.ShanhaiGuardCore   ⇒ 必须报红

    /** 【故意写坏】拿掉"必须先是对的属主前缀"那道闸门。 */
    private static final boolean MUTANT_ASCII_SKIP_PREFIX_GUARD = mutant("skipPrefixGuard");
    /** 【故意写坏】拿掉"正文不许有非 ASCII 字符"那道检查。 */
    private static final boolean MUTANT_ASCII_SKIP_NONASCII_CHECK = mutant("skipNonAscii");

    /** 读变异开关（只认那两个名字；别的值一律当"没开"）。 */
    private static boolean mutant(String name) {
        try {
            return name.equalsIgnoreCase(System.getProperty("shanghai.mutant", "").trim());
        } catch (Throwable t) {
            return false;   // 取属性被安全策略拦掉也当"没开"（生产路径永远不受影响）
        }
    }

    /**
     * 🔴 判据：这一行必须<b>逐字</b>以 {@code [创始现实修改模块] } 开头（含<b>那一个空格</b>）。
     *
     * <p>为什么连空格一起判：正文是英文，前缀与正文之间没有那个空格就会粘成
     * {@code [创始现实修改模块]CANCELLED} —— 用户要的是
     * {@code [创始现实修改模块] CANCELLED LivingDeathEvent x176} 这个形。
     */
    private static boolean hasFounderPrefix(String line) {
        return line != null && line.startsWith(CHAT_PREFIX_WITH_SPACE);
    }

    /**
     * 🔴 判据：<b>正文</b>（即剥掉 {@code [创始现实修改模块] } 前缀之后的那一段）必须是<b>纯 ASCII</b>。
     *
     * <pre>
     *   非 ASCII 字符（如全角 ＋、中文、§ 色码）出现任何一个 ⇒ 返回 false ⇒ 报红
     *   前缀本身那六个汉字是【唯一豁免】⇒ 判据必须先剥前缀，不能拿整行判
     * </pre>
     *
     * <p>负对照见 {@code main}：往正文里塞一个中文、塞一个 {@code §}、塞一个全角 ＋ ⇒ 三条都必须报红。
     */
    private static boolean bodyIsPureAscii(String line) {
        if (line == null || (!MUTANT_ASCII_SKIP_PREFIX_GUARD && !hasFounderPrefix(line))) {
            return false;   // 前缀都不对 ⇒ 谈不上"正文纯 ASCII"
        }
        final String body = line.substring(CHAT_PREFIX_WITH_SPACE.length());
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) > 0x7F && !MUTANT_ASCII_SKIP_NONASCII_CHECK) {
                return false;
            }
        }
        return true;
    }

    /** 前缀那一行的正文（剥掉前缀；不是前缀开头 ⇒ 原样返回，便于把读数打出来）。 */
    private static String bodyOf(String line) {
        if (line == null || !hasFounderPrefix(line)) {
            return "(不是那行前缀，原样) " + line;
        }
        return line.substring(CHAT_PREFIX_WITH_SPACE.length());
    }

    /**
     * 🔴 判据：这一行里有没有把<b>内部 id</b>漏给玩家。
     *
     * <pre>
     *   命中 ⇒ true（= 漏了内部 id，报红）
     * </pre>
     *
     * <p>🔴 <b>本轮（2026-10-11）收窄过一次，理由必须写清楚</b>：
     * 上一版这条判据把"逗号以外的 ASCII 冒号 ／ 小写点号路径 ／ 驼峰方法名"一律当成"漏了内部 id"。
     * 那是<b>当时正文是纯大写标签</b>才成立的口径。现在用户点名要正文里出现
     * {@code Entity.isRemoved()} ／ {@code ServerLevel.addFreshEntity()} ／ {@code setHealth(max)}
     * 这几种【代码路名】—— 它们<b>本来就带点号和驼峰</b>，旧判据会把<b>用户要的答案</b>判成违规。
     * ⇒ 收窄成一条更准的判据：<b>只看"内部 id 的招牌形状" = 命名空间:路径</b>
     * （物品主键 {@code shanhai:genesis_reality_modification_module} 那种），外加等号赋值。
     *
     * <p>⚠️ <b>"机制名" 与 "内部 id" 是两回事</b>，这条判据必须能分开它们：
     * <ul>
     *   <li>{@code Entity.isRemoved()} ⇒ <b>不命中</b>（玩家要看的答案："是哪个代码路"）；</li>
     *   <li>{@code shanhai:genesis_reality_modification_module} ⇒ <b>命中</b>（物品内部主键）；</li>
     *   <li>{@code obj.field = something} ⇒ <b>命中</b>（内部赋值语句）。</li>
     * </ul>
     *
     * <p>自检里带负对照：把核里的原因串（带 {@code shanhai:…}）喂进来必须报红，
     * 而九类真文案（含 {@code Entity.isRemoved()} 那种）必须全绿。
     */
    private static boolean leaksInternal(String line) {
        if (line == null) {
            return true;   // null 也算"有问题"（调用方本应先判非空）
        }
        // ① 命名空间:路径 —— 内部 id 的招牌形状（🔴 这条是主判据）
        if (line.contains(":")) {
            return true;
        }
        // ② 内部赋值 / 参数写法（等号）
        if (line.contains("=")) {
            return true;
        }
        // ③ 小写点号路径（net.minecraft.… ／ com.shanhai.… 那种"全小写包名"；
        //    机制名里的大写开头点号（Entity. ／ ServerLevel.）**不**命中）
        return line.matches(".*\\b[a-z][a-z0-9_]*\\.[a-z][A-Za-z0-9_]*.*");
    }

    /** 一个"出话函数"（正样本 = 走 LogGate 的那条路；突变体 = 没有闸门的那条路）。 */
    private interface GateFn {

        String apply(String kind, long nowMillis);
    }

    /**
     * 🔴 判据：{@link #chatPhrase} 与 {@link #chatMechanism} 对"这一类该不该给玩家看"必须**同进同出**。
     *
     * <p>为什么单独判一次：两张表分开写就有漂移风险 —— 哪天给某一类加了正文却忘了加机制名
     * （于是正文永远缺机制名），或者反过来（有机制名却没有正文，静默漏掉一整类）。
     * 这里把"两边同时为 null ⇔ 同时非 null"做成断言，让那种漂移当场报红。
     */
    private static boolean mechAgreeOnNullability(String[] kinds) {
        for (final String k : kinds) {
            if ((chatPhrase(k, 1L) == null) != (chatMechanism(k) == null)) {
                return false;
            }
        }
        return true;
    }

    /** 在 {@code [base, base + times*step)} 这段假时钟里喂 {@code times} 次，数一数放行了几行。 */
    private static int countLinesInWindow(GateFn fn, long baseMillis, int times, long stepMillis) {
        int emitted = 0;
        for (int i = 0; i < times; i++) {
            if (fn.apply(KIND_DAMAGE, baseMillis + i * stepMillis) != null) {
                emitted++;
            }
        }
        return emitted;
    }

    /** 收件人选择函数（正样本 = {@link ShanhaiGuardCore#chatRecipients}）。 */
    private interface AudienceFn<P> {

        List<P> apply(P holder, List<P> onlinePlayers);
    }

    /** 🔴 判据：收件人<b>必须只有一个人 —— 那个人就是持有者本人</b>（广播给所有人 ⇒ 判据报红）。 */
    private static <P> boolean audienceIsOnlyHolder(AudienceFn<P> fn, P holder, List<P> onlinePlayers) {
        final List<P> got = fn.apply(holder, onlinePlayers);
        return got != null && got.size() == 1 && got.get(0) == holder;
    }

    /** 【故意写坏】广播给所有人（把在线名单原样返回）—— 收件人判据必须报红。 */
    private static AudienceFn<String> mutantBroadcastToAll() {
        return (holder, online) -> online == null ? List.of() : online;
    }

    /** 【故意写坏】前缀写成别的（{@code [免死模块]}）—— 前缀判据必须报红。 */
    private static final String MUTANT_WRONG_PREFIX_LINE = "[免死模块] DAMAGE_BLOCKED x1";

    /** 【故意写坏】把前缀<b>翻译成英文</b> —— 用户点名"不许翻译" ⇒ 前缀判据必须报红。 */
    private static final String MUTANT_TRANSLATED_PREFIX_LINE =
            "[Genesis Reality Modification Module] DAMAGE_BLOCKED x1";

    /** 【故意写坏】前缀与正文之间丢了那一个空格（粘成一坨）⇒ 前缀判据必须报红。 */
    private static final String MUTANT_NO_SPACE_PREFIX_LINE = "[创始现实修改模块]DAMAGE_BLOCKED x1";

    /**
     * 🔴 <b>离线自检</b>：正向逐条打印真实读数；5 条负对照必须真报红（判据自己要能被证明是活的）。
     *
     * <pre>
     *   javac -encoding UTF-8 -d &lt;out&gt; ShanhaiGuardCore.java
     *   java  -Dfile.encoding=UTF-8 -cp &lt;out&gt; com.shanhai.common.guard.ShanhaiGuardCore
     * </pre>
     * 有任何一条不成立 ⇒ 退出码 1。
     */
    public static void main(String[] args) {
        final float MAX = 20.0f;
        final List<String> WITH = List.of(MODULE_ID);
        final List<String> WITHOUT = List.of("minecraft:stick", "minecraft:diamond_sword");

        OUT.println("=== [SHANHAI-GUARD] 纯逻辑核离线自检（只 import java.*）===");
        OUT.println("被测类 = " + ShanhaiGuardCore.class.getName() + "（零 Forge / 零 Minecraft）");
        OUT.println("模块 id = " + MODULE_ID + " ；1.20.1 死亡界面 = " + DEATH_SCREEN_SIMPLE
                + " ；enabled = " + enabled);
        OUT.println();

        // ─────────── 正向：持有 ⇒ 三条判定为真；血满 ⇒ 不补；DeathScreen ⇒ 取消 ───────────
        OUT.println("── 正向：持有 ⇒ ①挡伤害 ③拦死亡 ⑦取消死亡界面 为真 ──");
        expect("正向", "holdsModule(含模块)", true, holdsModule(WITH), "表=" + WITH);
        note("原因串：" + holdsModuleDecision(WITH).reason);
        expect("正向", "holdsModule(只给 path 形态)", true, holdsModule(List.of(MODULE_PATH)), "表=[" + MODULE_PATH + "]");
        expect("正向", "shouldBlockDamage(模块, 玩家)", true, shouldBlockDamage(MODULE_ID, true),
                "→ " + blockDamageDecision(MODULE_ID, true).reason);
        expect("正向", "shouldBlockDeath(模块, 玩家)", true, shouldBlockDeath(MODULE_ID, true),
                "→ " + blockDeathDecision(MODULE_ID, true).reason);
        expect("正向", "shouldCancelDeathScreen(DeathScreen 简名)", true, shouldCancelDeathScreen(DEATH_SCREEN_SIMPLE),
                "→ " + cancelDeathScreenDecision(DEATH_SCREEN_SIMPLE).reason);
        expect("正向", "shouldCancelDeathScreen(1.20.1 全名)", true, shouldCancelDeathScreen(DEATH_SCREEN_FQN),
                "→ " + cancelDeathScreenDecision(DEATH_SCREEN_FQN).reason);

        OUT.println("── 正向：回血目标 = 上限；血满 ⇒ 不补 ──");
        final float t20 = healTarget(MAX);
        final float t100 = healTarget(100.0f);
        expect("正向", "healTarget(20) == 20", true, t20 == MAX, "healTarget(20.0)=" + t20);
        expect("正向", "healTarget(100) == 100", true, t100 == 100.0f, "healTarget(100.0)=" + t100);
        expect("正向", "needsHeal(20 / 20)", false, needsHeal(MAX, MAX),
                "→ " + needsHealDecision(MAX, MAX).reason);
        expect("正向", "needsHeal(19.5 / 20)", true, needsHeal(19.5f, MAX),
                "→ " + healDecision(19.5f, MAX).reason);

        final long[] cs = countersToReset();
        final StringBuilder csTxt = new StringBuilder();
        for (final long c : cs) {
            csTxt.append(c).append('=').append(counterName(c)).append(' ');
        }
        expect("正向", "countersToReset() = deathTime/hurtTime", true,
                cs.length == 2 && cs[0] == COUNTER_DEATH_TIME && cs[1] == COUNTER_HURT_TIME,
                "实际 [" + csTxt.toString().trim() + "]");
        OUT.println();

        // ─────────── 负对照①：不持有 ⇒ 全不拦 ───────────
        OUT.println("── 【负对照①】不持有 ⇒ 全不拦 ──");
        expect("负①", "holdsModule(空表)", false, holdsModule(List.of()), "空表 ⇒ " + holdsModule(List.of()));
        expect("负①", "holdsModule(别的物品)", false, holdsModule(WITHOUT), "→ " + holdsModuleDecision(WITHOUT).reason);
        expect("负①", "shouldBlockDamage(非模块, 玩家)", false, shouldBlockDamage("minecraft:stick", true),
                "→ " + blockDamageDecision("minecraft:stick", true).reason);
        expect("负①", "shouldBlockDamage(模块, 非玩家)", false, shouldBlockDamage(MODULE_ID, false),
                "→ " + blockDamageDecision(MODULE_ID, false).reason);
        expect("负①", "shouldBlockDeath(非模块, 玩家)", false, shouldBlockDeath("minecraft:stick", true),
                "→ " + blockDeathDecision("minecraft:stick", true).reason);
        expect("负①", "shouldBlockDeath(模块, 非玩家)", false, shouldBlockDeath(MODULE_ID, false),
                "→ " + blockDeathDecision(MODULE_ID, false).reason);
        expect("负①", "取消死亡界面(不持有)", false,
                cancelDeathScreenDecision(DEATH_SCREEN_SIMPLE, false, true).yes,
                "→ " + cancelDeathScreenDecision(DEATH_SCREEN_SIMPLE, false, true).reason);
        OUT.println();

        // ─────────── 负对照②：血满 ⇒ 不补 ───────────
        OUT.println("── 【负对照②】血满 ⇒ 不补（一次 setHealth 都不发）──");
        expect("负②", "needsHeal(满, 满)", false, needsHeal(MAX, MAX), "→ " + needsHealDecision(MAX, MAX).reason);
        expect("负②", "healDecision(满, 满).yes", false, healDecision(MAX, MAX).yes,
                "→ " + healDecision(MAX, MAX).reason);
        expect("负②", "needsHeal(差 1e-5，容差内)", false, needsHeal(MAX - 1e-5f, MAX),
                "→ " + needsHealDecision(MAX - 1e-5f, MAX).reason);
        expect("负②", "needsHeal(差 0.5 ⇒ 该补)", true, needsHeal(MAX - 0.5f, MAX),
                "→ " + healDecision(MAX - 0.5f, MAX).reason);
        OUT.println();

        // ─────────── 负对照③：healTarget 改成 max/2 ⇒ 自检必须报红 ───────────
        OUT.println("── 【负对照③】把 healTarget 改成 max/2 ⇒ 判据必须报红 ──");
        final boolean realOk = healTargetInvariant(ShanhaiGuardCore::healTarget);
        expect("负③", "判据对【本工程实现】", true, realOk,
                "healTarget(20.0)=" + healTarget(20.0f) + " / healTarget(7.5)=" + healTarget(7.5f)
                        + " / healTarget(1024.25)=" + healTarget(1024.25f) + " ⇒ 判据=" + realOk);
        final boolean mutantOk = healTargetInvariant(m -> m / 2f);
        expect("负③", "判据对【max/2 突变体】", false, mutantOk,
                "突变体 healTarget(20.0)=10.0 ⇒ 判据=" + mutantOk + "（这里【期望 false】= 判据真会报红）");
        OUT.println();

        // ─────────── 负对照④：enabled=false ⇒ 全不拦 ───────────
        OUT.println("── 【负对照④】enabled=false ⇒ 全不拦 ──");
        final boolean oldEnabled = enabled;
        enabled = false;
        try {
            expect("负④", "shouldBlockDamage(模块, 玩家)", false, shouldBlockDamage(MODULE_ID, true),
                    "→ " + blockDamageDecision(MODULE_ID, true, enabled).reason);
            expect("负④", "shouldBlockDeath(模块, 玩家)", false, shouldBlockDeath(MODULE_ID, true),
                    "→ " + blockDeathDecision(MODULE_ID, true, enabled).reason);
            expect("负④", "healDecision(残血).yes", false, healDecision(3.0f, MAX).yes,
                    "→ " + healDecision(3.0f, MAX, enabled).reason);
            expect("负④", "取消死亡界面(持模块 + DeathScreen)", false,
                    cancelDeathScreenDecision(DEATH_SCREEN_SIMPLE, true, enabled).yes,
                    "→ " + cancelDeathScreenDecision(DEATH_SCREEN_SIMPLE, true, enabled).reason);
            note("注意：holdsModule 是【读事实】不是【拦截】⇒ 它不受开关影响：" + holdsModule(WITH));
        } finally {
            enabled = oldEnabled;
        }
        expect("负④", "开关复位后 ⇒ 又拦", true, shouldBlockDamage(MODULE_ID, true),
                "enabled=" + enabled + " ⇒ " + blockDamageDecision(MODULE_ID, true).reason);
        OUT.println();

        // ─────────── 负对照⑤：屏名给 GuiGameOver ⇒ 不该取消 ───────────
        OUT.println("── 【负对照⑤】屏名给 GuiGameOver ⇒ 不该取消（判据要认对那一个名）──");
        expect("负⑤", "shouldCancelDeathScreen(GuiGameOver)", false, shouldCancelDeathScreen("GuiGameOver"),
                "→ " + cancelDeathScreenDecision("GuiGameOver").reason);
        expect("负⑤", "shouldCancelDeathScreen(1.12.2 全名)", false,
                shouldCancelDeathScreen("net.minecraft.client.gui.GuiGameOver"),
                "→ " + cancelDeathScreenDecision("net.minecraft.client.gui.GuiGameOver").reason);
        expect("负⑤", "shouldCancelDeathScreen(别的屏)", false,
                shouldCancelDeathScreen("net.minecraft.client.gui.screens.inventory.InventoryScreen"),
                "→ 读数为 " + shouldCancelDeathScreen("net.minecraft.client.gui.screens.inventory.InventoryScreen"));
        expect("负⑤", "shouldCancelDeathScreen(DeathScreen) 对照", true, shouldCancelDeathScreen("DeathScreen"),
                "→ " + cancelDeathScreenDecision("DeathScreen").reason);
        OUT.println();

        // ─────────── 口径①：每次判定都给原因串 ───────────
        OUT.println("── 口径①：每次判定都给原因串（不是只返回 boolean）──");
        final String[] reasonSamples = {
            blockDamageDecision(MODULE_ID, true).reason,
            blockDamageDecision("minecraft:stick", true).reason,
            blockDeathDecision(MODULE_ID, true).reason,
            needsHealDecision(3.0f, MAX).reason,
            healDecision(3.0f, MAX).reason,
            cancelDeathScreenDecision(DEATH_SCREEN_SIMPLE).reason,
            cancelDeathScreenDecision("GuiGameOver").reason,
            looksDeadDecision(0.0f, false, 20, 0).reason,
        };
        int nonEmpty = 0;
        for (final String r : reasonSamples) {
            if (r != null && !r.isEmpty()) {
                nonEmpty++;
            }
            note("原因串「" + r + "」");
        }
        expect("口径①", reasonSamples.length + " 条原因串非空", true, nonEmpty == reasonSamples.length,
                nonEmpty + "/" + reasonSamples.length + " 非空");
        OUT.println();

        // ─────────── 口径②：回血自证 ───────────
        OUT.println("── 口径②：回血自证（healVerified ＋ 失败计数 ＋ 第二通道）──");
        resetHealCounters();
        final boolean v1 = healVerified(MAX, MAX);
        final boolean v2 = healVerified(0.0f, MAX);
        final boolean v3 = healVerified(MAX - 1e-6f, MAX);
        expect("口径②", "healVerified(20, 期望 20)", true, v1, "after=20.0 expected=20.0 ⇒ " + v1);
        expect("口径②", "healVerified(0, 期望 20)", false, v2,
                "after=0.0 expected=20.0 ⇒ " + v2 + "（回血被静默失效，这里必须抓到）");
        expect("口径②", "healVerified(容差内)", true, v3, "after=" + (MAX - 1e-6f) + " expected=20.0 ⇒ " + v3);
        expect("口径②", "失败计数器 = 1（不等的次数）", true, healFailureCount() == 1L,
                "attempts=" + healAttemptCount() + " failures=" + healFailureCount());
        expect("口径②", "第二通道未接入 ⇒ 不假报成功", false, healViaSecondChannel(MAX).yes,
                "→ " + healViaSecondChannel(MAX).reason);
        OUT.println();

        // ─────────── 口径③：读血不可全信 ───────────
        OUT.println("── 口径③：读血不可全信（looksDead 交叉校验）──");
        expect("口径③", "血 20 + isDeadOrDying=true ⇒ 判死", true, looksDead(MAX, true, 0, 0),
                "→ " + looksDeadDecision(MAX, true, 0, 0).reason);
        expect("口径③", "血 20 + deathTime=20 ⇒ 判死", true, looksDead(MAX, false, 20, 0),
                "→ " + looksDeadDecision(MAX, false, 20, 0).reason);
        expect("口径③", "血 0 + 标志全假 ⇒ 判死", true, looksDead(0.0f, false, 0, 0),
                "→ " + looksDeadDecision(0.0f, false, 0, 0).reason);
        expect("口径③", "血 20 + 只 hurtTime=5 ⇒ 判【未死】", false, looksDead(MAX, false, 0, 5),
                "→ " + looksDeadDecision(MAX, false, 0, 5).reason);
        expect("口径③", "血 NaN ⇒ 不敢信 ⇒ 判死", true, looksDead(Float.NaN, false, 0, 0),
                "→ " + looksDeadDecision(Float.NaN, false, 0, 0).reason);
        OUT.println();

        // ─────────── 编译门：⑦ 事件层签名（薄委托）───────────
        OUT.println("── 编译门⑦：事件层实际调用的那 7 个签名（缺一个整个 guard 包就编不过）──");
        expect("⑦", "isGuardItem(shanhai, path)", true, isGuardItem(MODULE_NAMESPACE, MODULE_PATH),
                "两段 = (" + MODULE_NAMESPACE + ", " + MODULE_PATH + ")");
        expect("⑦", "isGuardItem(gtceu, path) 负", false, isGuardItem("gtceu", MODULE_PATH),
                "别的命名空间 ⇒ " + isGuardItem("gtceu", MODULE_PATH));
        expect("⑦", "isGuardItem(null, null) 负", false, isGuardItem(null, null), "null ⇒ " + isGuardItem(null, null));
        expect("⑦", "blocksDamage(true)", true, blocksDamage(true), "→ " + blockDamageDecision(MODULE_ID, true).reason);
        expect("⑦", "blocksDamage(false) 负", false, blocksDamage(false), "→ 不持有 ⇒ " + blocksDamage(false));
        expect("⑦", "blocksDeath(true)", true, blocksDeath(true), "→ " + blockDeathDecision(MODULE_ID, true).reason);
        expect("⑦", "blocksDeath(false) 负", false, blocksDeath(false), "不持有 ⇒ " + blocksDeath(false));
        expect("⑦", "healAmount(true, 3/20)", true, healAmount(true, 3.0f, MAX) == 17.0f,
                "healAmount(true, 3.0, 20.0)=" + healAmount(true, 3.0f, MAX));
        expect("⑦", "healAmount(满血) 负", true, healAmount(true, MAX, MAX) == 0f,
                "满血 ⇒ " + healAmount(true, MAX, MAX) + "（不发 setHealth）");
        expect("⑦", "healAmount(不持有) 负", true, healAmount(false, 3.0f, MAX) == 0f,
                "不持有 ⇒ " + healAmount(false, 3.0f, MAX));
        expect("⑦", "tickActs(true) / tickActs(false)", true, tickActs(true) && !tickActs(false),
                "true ⇒ " + tickActs(true) + " ； false ⇒ " + tickActs(false));
        expect("⑦", "blocksGameOverScreen(true, true)", true, blocksGameOverScreen(true, true), "持有 ＋ 死亡界面");
        expect("⑦", "blocksGameOverScreen(true, false) 负", false, blocksGameOverScreen(true, false), "别的屏 ⇒ 不动");
        expect("⑦", "blocksGameOverScreen(false, true) 负", false, blocksGameOverScreen(false, true), "不持有 ⇒ 不动");
        OUT.println();

        // ─────────── ⑦ 日志闸门：同类合并 ＋ 每秒最多一行 ───────────
        OUT.println("── ⑦ 日志闸门（LogGate：同类合并 ＋ 每秒最多一行）──");
        final LogGate gate = new LogGate();
        final String g1 = gate.admit("伤害", 0L);
        final String g2 = gate.admit("伤害", 100L);
        final String g3 = gate.admit("死亡", 300L);
        final String g4 = gate.admit("伤害", 400L);
        final String g5 = gate.admit("补血", 1_000L);
        final String g7 = gate.admit("伤害", 2_100L);
        note("t=0    → " + g1);
        note("t=100  → " + g2);
        note("t=300  → " + g3);
        note("t=400  → " + g4);
        note("t=1000 → " + g5);
        note("t=2100 → " + g7);
        expect("⑦日志", "首行落盘且带前缀", true, g1 != null && g1.startsWith(LOG_PREFIX), "→ " + g1);
        expect("⑦日志", "1 秒内不再落盘（同类被合并）", true, g2 == null && g3 == null && g4 == null,
                "t=100/300/400 都返回 null ⇒ " + (g2 == null && g3 == null && g4 == null));
        // 🔴 实测口径：t=0 那一行【当场落盘并被清出待合并表】⇒ 该窗口里剩 伤害×2 ＋ 死亡×1，
        //    所以正确读数是「同类合并 2 次」，不是 3 次。
        //    （旧版备份 ShanhaiGuardCore.java.bak-2026-10-10 第 433 行期望的是 3 次 —— 那条期望本身是错的，
        //     同一个 LogGate 实现下必然报红；这里按实测值写，并在报告里点名。）
        expect("⑦日志", "下一窗口报出「同类合并 2 次」＋合并了三类",
                true, g5 != null && g5.contains("同类合并 2 次") && g5.contains("死亡") && g5.contains("补血"),
                "→ " + g5);
        expect("⑦日志", "再下一窗口落盘", true, g7 != null && g7.startsWith(LOG_PREFIX), "→ " + g7);
        expect("⑦日志", "合并计数 = 待合并次数（不含已落盘那一条）", true, gate.suppressed() == 0L,
                "本窗口 suppressed=" + gate.suppressed() + "（刚落盘 ⇒ 0）");
        OUT.println();

        // ─────────── 【补件①】传送通道 ───────────
        OUT.println("── 【补件①】传送通道：位置离谱 ⇒ 拉回上次正常位置 ──");
        final PositionTrack track = new PositionTrack();
        final Decision p1 = track.check(100.0, 64.0, 200.0);
        expect("①正向", "位置正常 ⇒ 不拉回", false, p1.yes, "→ " + p1.reason);
        expect("①正向", "已记住「上次正常位置」", true, track.hasLastSane(),
                "lastSane=(" + track.lastSaneX() + ", " + track.lastSaneY() + ", " + track.lastSaneZ() + ")");
        final Decision p2 = track.check(1.0e9, 0.0, 0.0);
        expect("①负对照", "x=1.0e9（有人 setPos 送你走）⇒ 必须拉回", true, p2.yes, "→ " + p2.reason);
        expect("①负对照", "拉回目标 = 上次正常位置 (100, 64, 200)", true,
                track.lastSaneX() == 100.0 && track.lastSaneY() == 64.0 && track.lastSaneZ() == 200.0,
                "lastSane=(" + track.lastSaneX() + ", " + track.lastSaneY() + ", " + track.lastSaneZ() + ")");
        expect("①负对照", "z 离谱 ⇒ 拉回", true, isPositionAbsurd(0.0, 64.0, -3.0e7 - 1.0),
                "|z|=3.0E7+1 ⇒ " + isPositionAbsurd(0.0, 64.0, -3.0e7 - 1.0));
        expect("①负对照", "y 离谱（4097）⇒ 拉回", true, isPositionAbsurd(0.0, 4097.0, 0.0),
                "|y|=4097 ⇒ " + isPositionAbsurd(0.0, 4097.0, 0.0));
        expect("①负对照", "x = NaN ⇒ 离谱", true, isPositionAbsurd(Double.NaN, 64.0, 0.0),
                "→ " + positionDecision(Double.NaN, 64.0, 0.0).reason);
        expect("①负对照", "y = -Infinity ⇒ 离谱（被 |y|>4096 兜住）", true,
                isPositionAbsurd(0.0, Double.NEGATIVE_INFINITY, 0.0), "→ " + isPositionAbsurd(0.0,
                        Double.NEGATIVE_INFINITY, 0.0));
        expect("①边界", "x 正好 3.0e7 ⇒ 仍算正常（不 >）", false, isPositionAbsurd(3.0e7, 64.0, 3.0e7),
                "读数 " + isPositionAbsurd(3.0e7, 64.0, 3.0e7));
        expect("①边界", "y 正好 4096 ⇒ 仍算正常", false, isPositionAbsurd(0.0, 4096.0, 0.0),
                "读数 " + isPositionAbsurd(0.0, 4096.0, 0.0));
        final PositionTrack fresh = new PositionTrack();
        final Decision p3 = fresh.check(1.0e9, 0.0, 0.0);
        expect("①负对照", "没有「上次正常位置」时不乱拉", false, p3.yes, "→ " + p3.reason);
        OUT.println();

        // ─────────── 【补件②⑤】被移除（含两套名单分裂）───────────
        OUT.println("── 【补件②⑤】被移除检测：isRemoved ／ isAddedToWorld=false ／ 两套名单分裂 ──");
        final Decision r0 = removalDecision(false, true, true, true);
        expect("②正向", "正常玩家 ⇒ 不判移除", false, r0.yes, "→ " + r0.reason);
        final Decision r1 = removalDecision(false, false, true, true);
        expect("②负对照", "只把 isAddedToWorld 置 false ⇒ 必须判出来", true, r1.yes, "→ " + r1.reason);
        final Decision r2 = removalDecision(true, true, true, true);
        expect("②负对照", "isRemoved()=true ⇒ 判出来", true, r2.yes, "→ " + r2.reason);
        expect("⑤正向", "两套名单一致（都在）⇒ 不报", false, rosterSplitDecision(true, true).yes,
                "→ " + rosterSplitDecision(true, true).reason);
        expect("⑤正向", "两套名单一致（都不在）⇒ 不报「分裂」", false, rosterSplitDecision(false, false).yes,
                "→ " + rosterSplitDecision(false, false).reason);
        expect("⑤负对照", "假装名单不一致（PlayerList 有、世界名单没有）⇒ 必须报警", true,
                rosterSplitDecision(true, false).yes, "→ " + rosterSplitDecision(true, false).reason);
        expect("⑤负对照", "分裂接进 ② 的检测 ⇒ 判【被移除】", true, looksRemoved(false, true, true, false),
                "→ " + removalDecision(false, true, true, false).reason);
        expect("⑤负对照", "反向分裂（世界名单有、PlayerList 没有）也报警", true, rosterSplitDecision(false, true).yes,
                "→ " + rosterSplitDecision(false, true).reason);
        OUT.println();

        // ─────────── 【补件③】回血自证 ＋ 读血交叉校验 ───────────
        OUT.println("── 【补件③】回血自证（读回复核）＋ 读血交叉校验 ──");
        resetHealCounters();
        final Decision h1 = healRoundTrip(MAX, MAX);
        expect("③正向", "回血成功（读回 = 期望）⇒ 自证通过", false, h1.yes, "→ " + h1.reason);
        expect("③正向", "失败计数 = 0", true, healFailureCount() == 0L,
                "attempts=" + healAttemptCount() + " failures=" + healFailureCount());
        final Decision h2 = healRoundTrip(MAX / 2f, MAX);
        expect("③负对照", "把期望值故意写成错的一半（期望 10，读回 20）⇒ 必须报失败", true, h2.yes,
                "→ " + h2.reason);
        expect("③负对照", "失败计数 = 1（红）", true, healFailureCount() == 1L,
                "attempts=" + healAttemptCount() + " failures=" + healFailureCount());
        note("把这条判据喂给「回一半」的突变体：healRoundTrip(期望 20.0, 读回 10.0).yes = "
                + healRoundTrip(MAX, MAX / 2f).yes + "（期望 true）");
        expect("③负对照", "突变体（期望 20、读回 10）⇒ 也报失败", true, healRoundTrip(MAX, MAX / 2f).yes,
                "failures=" + healFailureCount());

        resetBloodContradictionCount();
        final Decision c1 = crossCheckBlood(MAX, false, 0, 5);
        expect("③b正向", "血 20 ＋ 只 hurtTime=5 ⇒ 自洽，不记", false, c1.yes, "→ " + c1.reason);
        expect("③b正向", "矛盾计数 = 0", true, bloodContradictionCount() == 0L, "count=" + bloodContradictionCount());
        final Decision c2 = crossCheckBlood(MAX, true, 0, 0);
        expect("③b负对照", "血 20 但 isDeadOrDying=true ⇒ 记一条", true, c2.yes, "→ " + c2.reason);
        final Decision c3 = crossCheckBlood(MAX, false, 20, 0);
        expect("③b负对照", "血 20 但 deathTime=20 ⇒ 记一条", true, c3.yes, "→ " + c3.reason);
        final Decision c4 = crossCheckBlood(0.0f, false, 0, 0);
        expect("③b负对照", "血 0 却没有任何死亡标志 ⇒ 记一条", true, c4.yes, "→ " + c4.reason);
        final Decision c5 = crossCheckBlood(MAX, false, 0, -1);
        expect("③b负对照", "hurtTime=-1（vanilla 不可能）⇒ 记一条", true, c5.yes, "→ " + c5.reason);
        final Decision c6 = crossCheckBlood(Float.NaN, false, 0, 0);
        expect("③b负对照", "血 NaN 却无死亡标志 ⇒ 记一条", true, c6.yes, "→ " + c6.reason);
        expect("③b负对照", "矛盾计数 = 5", true, bloodContradictionCount() == 5L,
                "count=" + bloodContradictionCount() + "（期望 5 = c2..c6 各一条）");
        OUT.println();

        // ─────────── 【补件④】补回物品 ───────────
        OUT.println("── 【补件④】补回物品（只对登记过的持有者，只扫他的背包/副手/盔甲）──");
        resetRestoreCounters();
        final Decision m0 = restoreModuleDecision(true, true);
        expect("④正向", "背包完整（找到模块）⇒ 不补", false, m0.yes, "→ " + m0.reason);
        final Decision m1 = restoreModuleDecision(true, false);
        expect("④负对照", "模块被掏空（没找到）⇒ 必须补回", true, m1.yes, "→ " + m1.reason);
        final Decision m2 = restoreModuleDecision(false, false);
        expect("④负对照", "不是登记过的持有者 ⇒ 不补（不无中生有）", false, m2.yes, "→ " + m2.reason);
        noteRestoreResult(true);
        noteRestoreResult(false);
        expect("④读数", "补回 成功/失败 分开记账（失败不静默）", true,
                moduleRestoreOkCount() == 1L && moduleRestoreFailCount() == 1L,
                "ok=" + moduleRestoreOkCount() + " fail=" + moduleRestoreFailCount());
        final boolean oldRestore = restoreModuleWhenMissing;
        restoreModuleWhenMissing = false;
        try {
            expect("④负对照", "开关关闭 ⇒ 掏空了也不补", false, restoreModuleDecision(true, false).yes,
                    "→ " + restoreModuleDecision(true, false).reason);
        } finally {
            restoreModuleWhenMissing = oldRestore;
        }
        OUT.println();

        // ─────────── 【主动放手】②③ ＋ AE/ME 探针（判据 2026-10-10 用户原话：「放入箱子或者 ae 或者我们的机器槽位中也算主动啊，不需要补啊」）───────────
        OUT.println("── 【主动放手】只有【被动失去】才补回：①背包没有 ＋ ②非丢出通道 ＋ ③打开的容器里没有 ＋ AE 里也没有 ──");
        resetRestoreCounters();
        expect("放手①", "背包里有模块 ⇒ 不补", false,
                restoreModuleDecision(true, true, false, false, MeProbeOutcome.NOT_PROBED).yes,
                "→ " + restoreModuleDecision(true, true, false, false, MeProbeOutcome.NOT_PROBED).reason);
        final Decision ra = restoreModuleDecision(true, false, false, false, MeProbeOutcome.NOT_FOUND);
        expect("放手负对照", "背包被掏空 ＋ 没开容器 ＋ 非丢出 ＋ AE 里没有 ⇒ **必须补**", true, ra.yes,
                "→ " + ra.reason);
        final Decision rb = restoreModuleDecision(true, false, false, true, MeProbeOutcome.NOT_PROBED);
        expect("放手负对照", "背包没有，但【当前开着箱子且箱子里有】⇒ 不补", false, rb.yes, "→ " + rb.reason);
        final Decision rc = restoreModuleDecision(true, false, true, false, MeProbeOutcome.NOT_PROBED);
        expect("放手负对照", "通过【丢出】通道放开 ⇒ 不补", false, rc.yes, "→ " + rc.reason);
        final Decision rd = disarmDecision(true, false, true);
        expect("放手负对照", "通过【丢出】通道放开 ⇒ **保护卸下**", true, rd.yes, "→ " + rd.reason);
        expect("放手负对照", "丢的是多出来的那一份（背包里还有）⇒ **不卸保护**", false,
                disarmDecision(true, true, true).yes, "→ " + disarmDecision(true, true, true).reason);
        final Decision re = restoreModuleDecision(true, false, false, false, MeProbeOutcome.FOUND);
        expect("放手负对照", "AE/ME 网络里还有一份 ⇒ 不补", false, re.yes, "→ " + re.reason);
        expect("放手边界", "接口不可用 ⇒ 当作【没有】⇒ 照补（宁可信其无）", true,
                restoreModuleDecision(true, false, false, false, MeProbeOutcome.UNAVAILABLE).yes,
                "→ " + restoreModuleDecision(true, false, false, false, MeProbeOutcome.UNAVAILABLE).reason);
        expect("放手边界", "本次没查（NOT_PROBED）⇒ 当作【没有】⇒ 照补", true,
                restoreModuleDecision(true, false, false, false, MeProbeOutcome.NOT_PROBED).yes,
                "（reason: " + restoreModuleDecision(true, false, false, false, MeProbeOutcome.NOT_PROBED).reason + "）");
        expect("放手边界", "MeProbeOutcome.FOUND 才算\"网络里还有\"", true,
                MeProbeOutcome.FOUND.countsAsPresent() && !MeProbeOutcome.NOT_FOUND.countsAsPresent()
                        && !MeProbeOutcome.UNAVAILABLE.countsAsPresent() && !MeProbeOutcome.NOT_PROBED.countsAsPresent(),
                "FOUND=" + MeProbeOutcome.FOUND.countsAsPresent() + " NOT_FOUND=" + MeProbeOutcome.NOT_FOUND.countsAsPresent()
                        + " UNAVAILABLE=" + MeProbeOutcome.UNAVAILABLE.countsAsPresent());
        expect("放手边界", "丢出窗口：窗口内(200 tick)算丢出、窗口外不算", true,
                leftViaDropChannel(1000L, 900L, false) && !leftViaDropChannel(1000L, 700L, false),
                "now=1000 tossed=900 ⇒ " + leftViaDropChannel(1000L, 900L, false)
                        + " ；now=1000 tossed=700 ⇒ " + leftViaDropChannel(1000L, 700L, false)
                        + "（窗口 " + TOSS_WINDOW_TICKS + " tick）");
        expect("放手边界", "丢出窗口：背包里还有模块 ⇒ 一律不算丢出通道", false,
                leftViaDropChannel(1000L, 990L, true), "now=1000 tossed=990 背包里有 ⇒ " + leftViaDropChannel(1000L, 990L, true));

        OUT.println("── 【主动放手】ME 探针闸门：只在【背包里没有】的那一刻查一次，绝不每 tick 查 ──");
        final MeProbeGate gate2 = new MeProbeGate();
        final boolean mp1 = gate2.needsProbe(true);
        final boolean mp2 = gate2.needsProbe(false);
        gate2.record(MeProbeOutcome.NOT_FOUND);
        final boolean mp3 = gate2.needsProbe(false);
        final boolean mp4 = gate2.needsProbe(false);
        expect("探针闸门", "背包里有时不查（needsProbe(true)=false）", false, mp1, "needsProbe(true)=" + mp1);
        expect("探针闸门", "背包里没有 ⇒ 查一次（needsProbe(false)=true）", true, mp2, "needsProbe(false)=" + mp2);
        expect("探针闸门", "🔴 之后每一 tick 都【不再查】（绝不每 tick 查 ME）", true, !mp3 && !mp4,
                "已查过 ⇒ 第 2 次=" + mp3 + " 第 3 次=" + mp4 + "；缓存结局=" + gate2.outcome());
        expect("探针闸门", "缓存结局留在闸门里（后续直接用）", true,
                gate2.outcome() == MeProbeOutcome.NOT_FOUND && gate2.probed(),
                "outcome=" + gate2.outcome() + " probed=" + gate2.probed());
        final boolean mq1 = gate2.needsProbe(true);
        final boolean mq2 = gate2.needsProbe(false);
        expect("探针闸门", "背包里又有模块 ⇒ 本局复位；下次缺失才重新查一次", true, !mq1 && mq2,
                "needsProbe(true)=" + mq1 + " ⇒ 复位；needsProbe(false)=" + mq2 + " ⇒ 新一局查一次");
        expect("探针闸门", "record(null) ⇒ 当作接口不可用（不假报成功）", true,
                newGateWithNull() == MeProbeOutcome.UNAVAILABLE, "record(null) ⇒ " + newGateWithNull());
        final boolean oldRestore2 = restoreModuleWhenMissing;
        restoreModuleWhenMissing = false;
        try {
            expect("放手边界", "补回开关关闭 ⇒ 掏空了也不补（开关可关）", false,
                    restoreModuleDecision(true, false, false, false, MeProbeOutcome.NOT_FOUND).yes,
                    "→ " + restoreModuleDecision(true, false, false, false, MeProbeOutcome.NOT_FOUND).reason);
        } finally {
            restoreModuleWhenMissing = oldRestore2;
        }
        OUT.println();

        // ─────────── 【救回】只有【被外力移除】才动手 ＋ 顺序 ＋ 判返回值 ───────────
        OUT.println("── 【救回】顺序：放回位置 → revive() → addFreshEntity()；正常离开（换维度/登出/正常死亡）绝不动手 ──");
        resetRescueCounters();
        final List<String> traceA = new java.util.ArrayList<>();
        final Decision rv1 = rescueRemovedEntity(true, "DISCARDED", true, false, true, recorder(traceA, true));
        expect("救回正向", "被外力移除（DISCARDED ／ shouldDestroy=true）⇒ 动手", true, rv1.yes, "→ " + rv1.reason);
        expect("救回正向", "三步都调用了，且顺序 = 放回位置 → revive → addFreshEntity", true,
                traceA.equals(List.of(STEP_RESTORE_POSITION, STEP_REVIVE, STEP_ADD)), "调用序列=" + traceA);
        final boolean okOrder = rescueOrderCorrect(traceA);
        expect("救回正向", "顺序判据对【本工程实现】= 真", true, okOrder, "trace=" + traceA + " ⇒ 判据=" + okOrder);
        expect("救回正向", "成功计数（读数）", true, rescueOkCount() == 1L,
                "ok=" + rescueOkCount() + " fail=" + rescueFailCount() + " skipped=" + rescueSkippedCount());
        expect("救回正向", "玩家实体：原因串里写清【只做实体层、四套名单重建本轮不做】", true,
                rv1.reason.contains("实体层") && rv1.reason.contains("四套名单"),
                "→ " + rv1.reason);

        final List<String> traceB = new java.util.ArrayList<>();
        final Decision rv2 = rescueRemovedEntity(true, "DISCARDED", true, false, true, recorder(traceB, false));
        expect("救回负对照", "🔴 addFreshEntity 返回 false ⇒ **不当成功**（报警）", false, rv2.yes,
                "→ " + rv2.reason);
        expect("救回负对照", "返回值 false ⇒ 失败计数 +1（不静默）", true, rescueFailCount() == 1L,
                "fail=" + rescueFailCount() + " ；trace=" + traceB);

        final String[] normalLeaves = { "CHANGED_DIMENSION", "UNLOADED_WITH_PLAYER", "KILLED", "UNLOADED_TO_CHUNK" };
        for (final String r : normalLeaves) {
            final List<String> tr = new java.util.ArrayList<>();
            final Decision d = rescueRemovedEntity(true, r, true, false, true, recorder(tr, true));
            expect("救回负对照", "正常离开 " + r + " ⇒ 绝不动手（一次都不调用）", true,
                    !d.yes && tr.isEmpty(), "decision.yes=" + d.yes + " 调用序列=" + tr + " → " + d.reason);
        }
        expect("救回负对照", "正常离开累计【没动手】次数 = 4", true, rescueSkippedCount() == 4L,
                "skipped=" + rescueSkippedCount());
        final List<String> trN = new java.util.ArrayList<>();
        final Decision rv3 = rescueRemovedEntity(true, null, false, true, true, recorder(trN, true));
        expect("救回负对照", "removalReason=null 且 isAddedToWorld=true ⇒ 他还在 ⇒ 不动手", true,
                !rv3.yes && trN.isEmpty(), "→ " + rv3.reason);
        final List<String> trN2 = new java.util.ArrayList<>();
        final Decision rv4 = rescueRemovedEntity(true, null, false, false, true, recorder(trN2, true));
        expect("救回正向", "removalReason=null 但 isAddedToWorld=false（只关了标记）⇒ 动手", true,
                rv4.yes && rescueOrderCorrect(trN2), "→ " + rv4.reason + " ／ 序列=" + trN2);
        final List<String> trU = new java.util.ArrayList<>();
        final Decision rv5 = rescueRemovedEntity(true, "SOMETHING_ELSE", true, false, true, recorder(trU, true));
        expect("救回负对照", "不认识的 removalReason ⇒ 不猜、绝不动手", true,
                !rv5.yes && trU.isEmpty(), "→ " + rv5.reason);
        final List<String> trT = new java.util.ArrayList<>();
        final Decision rv6 = rescueRemovedEntity(true, "DISCARDED", true, false, true, thrower(trT));
        expect("救回负对照", "第三步抛异常 ⇒ 不当成功、不炸（报警）", false, rv6.yes, "→ " + rv6.reason);
        final Decision rv7 = rescueRemovedEntity(false, "DISCARDED", true, false, true, recorder(new java.util.ArrayList<>(), true));
        expect("救回边界", "开关关闭 ⇒ 不动手", false, rv7.yes, "→ " + rv7.reason);
        final boolean mutantOrder = rescueOrderCorrect(List.of(STEP_ADD, STEP_REVIVE, STEP_RESTORE_POSITION));
        expect("救回负对照", "顺序判据对【倒过来的突变体】= 假（判据真有牙）", false, mutantOrder,
                "突变体序列=[" + STEP_ADD + ", " + STEP_REVIVE + ", " + STEP_RESTORE_POSITION + "] ⇒ 判据=" + mutantOrder);
        final boolean mutantOrder2 = rescueOrderCorrect(List.of(STEP_RESTORE_POSITION, STEP_ADD, STEP_REVIVE));
        expect("救回负对照", "顺序判据对【revive 与重挂对调】= 假", false, mutantOrder2,
                "序列=[" + STEP_RESTORE_POSITION + ", " + STEP_ADD + ", " + STEP_REVIVE + "] ⇒ 判据=" + mutantOrder2);
        final String[] steps = rescueStepOrder();
        expect("救回正向", "固定顺序常量 = 位置/revive/重挂", true,
                steps.length == 3 && STEP_REVIVE.equals(steps[1]) && STEP_ADD.equals(steps[2]),
                "[" + steps[0] + ", " + steps[1] + ", " + steps[2] + "]");
        OUT.println();

        // ─────────── 负对照的「活性证明」：5 件各喂一个突变体 ⇒ 读数必须与正样本不同 ───────────
        OUT.println("── 【负对照活性】把 5 条判据各喂一个【故意写坏的实现】⇒ 读数必须与正样本不同（否则判据是瞎的）──");
        expect("活性①", "突变体「永不判离谱」在 x=1e9 上说【正常】⇒ 与正样本读数相反（同一问题：位置正常吗）", true,
                mutantPositionAlwaysSane(1.0e9, 0.0, 0.0) != isPositionSane(1.0e9, 0.0, 0.0),
                "突变体「正常吗」=" + mutantPositionAlwaysSane(1.0e9, 0.0, 0.0)
                        + " 正样本「正常吗」=" + isPositionSane(1.0e9, 0.0, 0.0)
                        + "（正样本「离谱吗」=" + isPositionAbsurd(1.0e9, 0.0, 0.0) + "）");
        expect("活性②", "突变体「只看 isRemoved()」漏掉 isAddedToWorld=false ⇒ 与正样本不同", true,
                mutantRemovedOnlyIsRemoved(false, false, true, true) != looksRemoved(false, false, true, true),
                "突变体=" + mutantRemovedOnlyIsRemoved(false, false, true, true)
                        + " 正样本=" + looksRemoved(false, false, true, true));
        final boolean mutantHealRed = healRoundTrip(mutantHealTargetHalf(MAX), MAX).yes;
        final boolean realHealGreen = !healRoundTrip(healTarget(MAX), MAX).yes;
        expect("活性③", "突变体「期望写成一半」被判失败，本工程实现被判通过", true, mutantHealRed && realHealGreen,
                "突变体 expected=" + mutantHealTargetHalf(MAX) + " ⇒ 报失败=" + mutantHealRed
                        + "；正样本 expected=" + healTarget(MAX) + " ⇒ 报通过=" + realHealGreen);
        expect("活性④", "突变体「永远说不用补回」漏掉被掏空 ⇒ 与正样本不同（同一问题：要补回吗）", true,
                mutantRestoreNeverFires(true, false) != restoreModuleDecision(true, false).yes,
                "突变体「要补回吗」=" + mutantRestoreNeverFires(true, false)
                        + " 正样本「要补回吗」=" + restoreModuleDecision(true, false).yes);
        expect("活性⑤", "突变体「永不说分裂」漏掉名单分裂 ⇒ 与正样本不同", true,
                mutantRosterNeverSplits(true, false) != rosterSplitDecision(true, false).yes,
                "突变体=" + mutantRosterNeverSplits(true, false)
                        + " 正样本=" + rosterSplitDecision(true, false).yes);
        expect("活性⑥", "突变体「只看背包没有就补」把【主动放手】整个漏掉 ⇒ 与正样本不同（同一问题：要补回吗）", true,
                mutantRestoreIgnoresActiveRelease(true, false)
                        != restoreModuleDecision(true, false, false, true, MeProbeOutcome.NOT_PROBED).yes,
                "样本 =【背包没有 ＋ 当前开着箱子且箱子里有】：突变体「要补回吗」="
                        + mutantRestoreIgnoresActiveRelease(true, false)
                        + " 正样本「要补回吗」=" + restoreModuleDecision(true, false, false, true, MeProbeOutcome.NOT_PROBED).yes);
        expect("活性⑦", "突变体「丢出窗口内不看背包」把【还拿着模块】判成放手 ⇒ 与正样本不同", true,
                mutantTossIgnoresInventory(1000L, 990L, true) != leftViaDropChannel(1000L, 990L, true),
                "样本 =【现在 1000，990 时丢过一次，但背包里还有模块】：突变体="
                        + mutantTossIgnoresInventory(1000L, 990L, true)
                        + " 正样本=" + leftViaDropChannel(1000L, 990L, true));
        expect("活性⑧", "突变体「换维度也动手」漏掉【正常离开】过滤 ⇒ 与正样本不同（同一问题：要动手吗）", true,
                mutantRescueOnNormalLeave("CHANGED_DIMENSION")
                        != rescueRemovedEntity(true, "CHANGED_DIMENSION", false, false, true, recorder(new java.util.ArrayList<>(), true)).yes,
                "样本 =【removalReason=CHANGED_DIMENSION】：突变体「要动手吗」=" + mutantRescueOnNormalLeave("CHANGED_DIMENSION")
                        + " 正样本「要动手吗」=" + rescueRemovedEntity(true, "CHANGED_DIMENSION", false, false, true,
                                recorder(new java.util.ArrayList<>(), true)).yes);
        expect("活性⑨", "突变体「顺序倒过来」被顺序判据判假 ⇒ 与正样本不同", true,
                mutantRescueOrderReversed(traceA) != rescueOrderCorrect(traceA),
                "正样本序列=" + traceA + " ⇒ 判据=" + rescueOrderCorrect(traceA)
                        + "；倒序突变体 ⇒ 判据=" + mutantRescueOrderReversed(traceA));
        OUT.println();

        // ─────────── ⑭【聊天栏】英文 / 代码风格文案（2026-10-10 第二轮：用户原话「…可以写英文」） ───────────
        OUT.println("── ⑭【聊天栏·英文文案】前缀逐字 [创始现实修改模块] ＋ 正文纯 ASCII 代码风格 ＋ 只发给本人 ──");

        // ─────── 中文旧文案 → 英文新文案 对照表（逐条打印真实读数） ───────
        OUT.println("· 对照表（中文旧文案 → 英文新文案 =【动作 ＋ 机制名】；数量一律 xN 连写）：");
        final String[][] MAP = {
            { "挡伤害",   KIND_DAMAGE,   "挡下了 176 次致命伤害",             "CANCELLED LivingAttackEvent x176" },
            { "拦死亡",   KIND_DEATH,    "拦下了 176 次死亡，把你救回来了",   "CANCELLED LivingDeathEvent x176 - health restored" },
            { "补血",     KIND_HEAL,     "血量被外部清零，已补回 160 次",     "RESTORED setHealth(max) (ServerTickEvent x160)" },
            { "传送拉回", KIND_TELEPORT, "有人把你传送到离谱的位置，已拉回原位", "RESTORED position (ServerTickEvent - Entity.isAddedToWorld() sane)" },
            { "被移除",   KIND_REMOVED,  "你被外力从世界里移除了",           "DETECTED Entity.isRemoved() - no rescue" },
            { "补回模块", KIND_RESTORE,  "背包里少了那件模块，已补回 1 件",   "RESTORED module (inventory.add x1)" },
            { "主动放手", KIND_DISARM,   "你把模块交了出去，保护已卸下",     "DISARMED guard (ItemTossEvent: module handed over)" },
            { "已救回",   KIND_RESCUED,  "你被外力移除后，已经救回来了",     "RESCUED entity (Entity.revive() + ServerLevel.addFreshEntity())" },
            { "死亡界面", KIND_SCREEN,   "拦下了 1 次死亡界面",              "CANCELLED ScreenEvent.Opening x1" },
        };
        final long[] MAP_COUNTS = { 176L, 176L, 160L, 1L, 1L, 1L, 1L, 1L, 1L };
        int mapOk = 0;
        int mechInBodyOk = 0;
        for (int i = 0; i < MAP.length; i++) {
            final String body = chatPhrase(MAP[i][1], MAP_COUNTS[i]);
            final String mech = chatMechanism(MAP[i][1]);
            final boolean ok = MAP[i][3].equals(body);
            // 🔴 用户追加口径那条：这一类的正文里**必须含**它自己的机制名
            final boolean hasMech = mech != null && body != null && body.contains(mech);
            if (ok) {
                mapOk++;
            }
            if (hasMech) {
                mechInBodyOk++;
            }
            OUT.println(String.format("    %-8s 中文「%s」", MAP[i][0], MAP[i][2]));
            OUT.println(String.format("             → 英文「%s」  %s   [含机制名 %s = %s]",
                    body, ok ? "✓" : "✗ 期望「" + MAP[i][3] + "」", mech, hasMech));
        }
        expect("⑭对照表", MAP.length + " 类逐字与对照表一致", true, mapOk == MAP.length, mapOk + "/" + MAP.length + " 一致");
        expect("⑭机制名", "🔴 " + MAP.length + " 类的正文【每一类都含它自己的机制名】", true,
                mechInBodyOk == MAP.length, mechInBodyOk + "/" + MAP.length + " 类含机制名");

        // 正向①：持有者被拦 ⇒ 抛出一次，同一个窗口给出【两行】：日志行（照旧）＋ 聊天行（给玩家本人）
        final LogGate chatGate = new LogGate();
        final LogGate.Batch cb1 = chatGate.admitBatch(KIND_DAMAGE, 0L);
        final String chat1 = cb1 == null ? null : cb1.chatLine();
        note("同一窗口 · 日志行（照旧）→ " + (cb1 == null ? null : cb1.logLine()));
        note("同一窗口 · 聊天行（给本人）→ " + chat1);
        expect("⑭正向", "持有者被拦 ⇒ 有给玩家的一行", true, chat1 != null && !chat1.isEmpty(), "→ " + chat1);
        expect("⑭正向", "整行逐字 = [创始现实修改模块] CANCELLED LivingAttackEvent x1", true,
                "[创始现实修改模块] CANCELLED LivingAttackEvent x1".equals(chat1), "实测 → " + chat1);
        expect("⑭正向", "前缀判据（拿字面量比，不用常量自比）", true, hasFounderPrefix(chat1), "→ " + chat1);
        expect("⑭正向", "前缀常量本身就带那个字面量", true, CHAT_PREFIX.startsWith(CHAT_PREFIX_LITERAL),
                "CHAT_PREFIX=" + CHAT_PREFIX);
        expect("⑭正向", "🔴 正文纯 ASCII（用户点单：可以写英文）", true, bodyIsPureAscii(chat1),
                "正文 =「" + bodyOf(chat1) + "」");
        expect("⑭正向", "没有内部 id/类名/参数名（命名空间冒号 / 驼峰 / 小写点号路径）", false, leaksInternal(chat1),
                "→ " + chat1);
        expect("⑭正向", "日志那一行照旧（[SHANHAI-GUARD] 开头，没被聊天栏替换掉）", true,
                cb1 != null && cb1.logLine().startsWith(LOG_PREFIX), "→ " + (cb1 == null ? null : cb1.logLine()));
        expect("⑭正向", "用户样例②：CANCELLED LivingDeathEvent x176 - health restored", true,
                "CANCELLED LivingDeathEvent x176 - health restored".equals(chatPhrase(KIND_DEATH, 176L)),
                "→ " + chatPhrase(KIND_DEATH, 176L));
        expect("⑭正向", "用户样例③：DISARMED guard (ItemTossEvent: module handed over)", true,
                "DISARMED guard (ItemTossEvent: module handed over)".equals(chatPhrase(KIND_DISARM, 1L)),
                "→ " + chatPhrase(KIND_DISARM, 1L));

        // 🔴 正向扫全类：每一个"给玩家看"的类别都必须产出【纯 ASCII ＋ 以前缀开头】的整行
        final String[] CHAT_KINDS = { KIND_DAMAGE, KIND_DEATH, KIND_HEAL, KIND_TELEPORT,
                KIND_REMOVED, KIND_RESTORE, KIND_DISARM, KIND_RESCUED, KIND_SCREEN };
        final long[] CHAT_COUNTS = { 1L, 7L, 1L, 3L, 5L, 1L, 1L, 2L, 9L };
        int asciiOk = 0;
        int prefixOk = 0;
        final StringBuilder detail = new StringBuilder();
        for (int i = 0; i < CHAT_KINDS.length; i++) {
            final String one = chatLineOf(CHAT_KINDS[i], CHAT_COUNTS[i]);
            final boolean a = bodyIsPureAscii(one);
            final boolean p = hasFounderPrefix(one);
            if (a) {
                asciiOk++;
            }
            if (p) {
                prefixOk++;
            }
            detail.append("\n      ").append(String.format("%-14s x%-3d → %s   [ASCII=%s 前缀=%s]",
                    CHAT_KINDS[i], CHAT_COUNTS[i], one, a, p));
        }
        OUT.println("· 逐类正向读数（共 " + CHAT_KINDS.length + " 类）：" + detail);
        expect("⑭正向扫全类", CHAT_KINDS.length + " 类：正文全是纯 ASCII", true, asciiOk == CHAT_KINDS.length,
                asciiOk + "/" + CHAT_KINDS.length + " 类纯 ASCII");
        expect("⑭正向扫全类", CHAT_KINDS.length + " 类：整行全以 [创始现实修改模块]␠ 开头", true,
                prefixOk == CHAT_KINDS.length, prefixOk + "/" + CHAT_KINDS.length + " 类前缀逐字");
        // 🔴 顺带盯住那条最容易复发的东西：分隔符若是全角 ＋，上面那条 ASCII 判据会当场报红
        final Map<String, Long> twoKinds = new java.util.LinkedHashMap<>();
        twoKinds.put(KIND_DAMAGE, 2L);
        twoKinds.put(KIND_HEAL, 1L);
        final String twoLine = chatLine(twoKinds);
        expect("⑭合并", "两类合成一行（半角 + 连接）", true,
                "[创始现实修改模块] CANCELLED LivingAttackEvent x2 + RESTORED setHealth(max) (ServerTickEvent)"
                        .equals(twoLine), "→ " + twoLine);
        expect("⑭合并", "🔴 两类合出来的那一行正文也纯 ASCII（全角 ＋ 会在这里当场报红）", true,
                bodyIsPureAscii(twoLine), "正文 =「" + bodyOf(twoLine) + "」");

        // 同类合并：聊天栏这一路与日志那一路是【同一份计数】
        final LogGate chatGate2 = new LogGate();
        chatGate2.admitBatch(KIND_DAMAGE, 0L);      // 第一行当场落盘
        chatGate2.admitBatch(KIND_DAMAGE, 100L);    // 下面两次进待合并表
        chatGate2.admitBatch(KIND_DAMAGE, 200L);
        final LogGate.Batch cb2 = chatGate2.admitBatch(KIND_DAMAGE, 1_200L);
        final String chat2 = cb2 == null ? null : cb2.chatLine();
        expect("⑭合并", "同一窗口 3 次 ⇒ 聊天行报「x3」", true,
                "[创始现实修改模块] CANCELLED LivingAttackEvent x3".equals(chat2), "→ " + chat2);
        expect("⑭合并", "同一窗口 3 次 ⇒ 机制名照旧在（合并只吃数量，不吃机制名）", true,
                chat2 != null && chat2.contains(chatMechanism(KIND_DAMAGE)), "→ " + chat2);
        expect("⑭合并", "日志行同一份计数（同类合并 3 次）", true,
                cb2 != null && cb2.logLine().contains("同类合并 3 次"), "→ " + (cb2 == null ? null : cb2.logLine()));

        // 同一秒最多一行（负对照：没有闸门的突变体必须被同一条判据报红）
        final LogGate rateGate = new LogGate();
        final GateFn realGate = (kind, now) -> {
            final LogGate.Batch b = rateGate.admitBatch(kind, now);
            return b == null ? null : b.chatLine();
        };
        final int realLines = countLinesInWindow(realGate, 0L, 10, 100L);        // 同一秒里喂 10 次
        final GateFn mutantGate = (kind, now) -> MUTANT_WRONG_PREFIX_LINE;       // 没有闸门：来几条发几条
        final int mutantLines = countLinesInWindow(mutantGate, 0L, 10, 100L);
        expect("⑭闸门", "正样本：同一秒里最多 1 行（喂 10 次 / 每 100 ms）", true, realLines <= 1, "放行行数=" + realLines);
        expect("⑭闸门负对照", "突变体「来几条发几条」⇒ 同一条判据报红（放行 > 1）", true, mutantLines > 1,
                "突变体放行行数=" + mutantLines + "（> 1 ⇒ 判据对它有牙）");
        expect("⑭闸门负对照", "同一条判据：正样本 true ／ 突变体 false", true,
                (realLines <= 1) && !(mutantLines <= 1),
                "判据(正样本 lines=" + realLines + ")=" + (realLines <= 1)
                        + " ；判据(突变体 lines=" + mutantLines + ")=" + (mutantLines <= 1));

        // 只发给持有者本人（负对照：广播给所有人的突变体必须报红）
        final List<String> online = List.of("甲玩家", "乙玩家", "丙玩家");
        final String holder = "乙玩家";   // 三个人里只有他是持有者
        final List<String> toHolder = chatRecipients(holder, online);
        expect("⑭收件人", "收件人只有持有者本人（1 个人，且就是他）", true,
                toHolder.size() == 1 && holder.equals(toHolder.get(0)), "收件人=" + toHolder + " ；在线=" + online);
        expect("⑭收件人", "没有持有者 ⇒ 空名单（一条都不发）", true, chatRecipients(null, online).isEmpty(),
                "收件人=" + chatRecipients(null, online));
        expect("⑭收件人负对照", "判据对【广播给所有人】的突变体报红", true,
                !audienceIsOnlyHolder(mutantBroadcastToAll(), holder, online)
                        && audienceIsOnlyHolder(ShanhaiGuardCore::chatRecipients, holder, online),
                "突变体收件人=" + mutantBroadcastToAll().apply(holder, online) + " ⇒ 判据="
                        + audienceIsOnlyHolder(mutantBroadcastToAll(), holder, online)
                        + " ；正样本收件人=" + toHolder + " ⇒ 判据="
                        + audienceIsOnlyHolder(ShanhaiGuardCore::chatRecipients, holder, online));

        // ─────── 🔴 三条负对照必须真报红（判据自己要能被证明是活的） ───────
        OUT.println("── ⑭【三条负对照】判据真会报红吗（每条都有【故意写坏】的样本） ──");
        // 负①：正文里塞一个中文 ⇒ 纯 ASCII 判据必须报红（正样本同时为真 ⇒ 证明是判据有牙，不是它永远说假）
        final String cjkBody = CHAT_PREFIX + "DAMAGE_BLOCKED 拦截 x1";
        expect("负①", "正文里塞一个中文 ⇒ 纯 ASCII 判据【报红】", true,
                bodyIsPureAscii(chat1) && !bodyIsPureAscii(cjkBody),
                "正样本 bodyIsPureAscii = " + bodyIsPureAscii(chat1) + "（" + chat1 + "）"
                        + " ；塞中文那条 = " + bodyIsPureAscii(cjkBody) + "（" + cjkBody + "）");
        // 🔴 负①b：前缀不对、但【正文确实纯 ASCII】⇒ 仍必须报红。
        //    这一条是【判据内部那道前缀闸门】的载荷证明：少了它，"正文纯 ASCII"这句话会在
        //    "根本不是我那行前缀"的输入上被误判成 true。（2026-10-10 实测发现：没有这一条时，
        //    把 bodyIsPureAscii 里那道前缀闸门删掉，187 条判据【全绿】—— 即那道闸门当时是没载荷的。）
        final String wrongPrefixAsciiBody = "[免死模块] DAMAGE_BLOCKED x1";
        expect("负①b", "前缀不对但正文是纯 ASCII ⇒ 仍【报红】（前缀闸门是载荷的）", true,
                bodyIsPureAscii(chat1) && !bodyIsPureAscii(wrongPrefixAsciiBody),
                "正样本=" + bodyIsPureAscii(chat1) + " ；错前缀那条=" + bodyIsPureAscii(wrongPrefixAsciiBody)
                        + "（" + wrongPrefixAsciiBody + " ⇒ 正文本来就是纯 ASCII，报红靠的是那道前缀闸门）");
        // 负②：前缀写错 / 翻译 ⇒ 前缀判据必须报红（正样本同时为真）
        expect("负②", "前缀写错（[免死模块] …）⇒ 前缀判据【报红】", true,
                hasFounderPrefix(chat1) && !hasFounderPrefix(MUTANT_WRONG_PREFIX_LINE),
                "正样本=" + hasFounderPrefix(chat1) + " ；写错那条=" + hasFounderPrefix(MUTANT_WRONG_PREFIX_LINE)
                        + "（" + MUTANT_WRONG_PREFIX_LINE + "）");
        expect("负②", "前缀被翻译成英文（[Genesis Module] …）⇒ 同样【报红】", true,
                !hasFounderPrefix(MUTANT_TRANSLATED_PREFIX_LINE),
                "翻译那条=" + hasFounderPrefix(MUTANT_TRANSLATED_PREFIX_LINE) + "（" + MUTANT_TRANSLATED_PREFIX_LINE + "）");
        expect("负②", "前缀丢了尾随空格（粘成 [创始现实修改模块]DAMAGE_BLOCKED）⇒ 也【报红】", true,
                !hasFounderPrefix(MUTANT_NO_SPACE_PREFIX_LINE),
                "无空格那条=" + hasFounderPrefix(MUTANT_NO_SPACE_PREFIX_LINE) + "（" + MUTANT_NO_SPACE_PREFIX_LINE + "）");
        // 负③：去色码相关 —— 加 § ⇒ 报红（§ 是 U+00A7 > 0x7F，会同时破掉"纯 ASCII"）
        final String ssLine = CHAT_PREFIX + "\u00A7aDAMAGE_BLOCKED x1";
        expect("负③", "往正文里加 § 色码 ⇒ 纯 ASCII 判据【报红】", true,
                bodyIsPureAscii(chat1) && !bodyIsPureAscii(ssLine),
                "正样本=" + bodyIsPureAscii(chat1) + " ；加 § 那条=" + bodyIsPureAscii(ssLine)
                        + "（§ 的码位 U+00A7 > 0x7F ⇒ 正文不再纯 ASCII）");
        expect("负③", "前缀里加 § 也【报红】（前缀逐字，不许带色码）", true,
                !hasFounderPrefix(CHAT_PREFIX_LITERAL + "\u00A7r DAMAGE_BLOCKED x1"),
                "读数=" + hasFounderPrefix(CHAT_PREFIX_LITERAL + "\u00A7r DAMAGE_BLOCKED x1")
                        + "（加 §r 之后不再是那个字面量前缀）");
        expect("负③", "全角 ＋ 当连接符 ⇒ 纯 ASCII 判据【报红】（这就是它非改成半角的原因）", true,
                !bodyIsPureAscii(CHAT_PREFIX + "DAMAGE_BLOCKED x1 ＋ HEALTH_RESTORED"),
                "读数=" + bodyIsPureAscii(CHAT_PREFIX + "DAMAGE_BLOCKED x1 ＋ HEALTH_RESTORED"));
        // 内部串那一条的负对照：把核里的原因串（带内部 id）喂进判据 ⇒ 报红
        final String internalSample = "登记过的持有者，但背包＋副手＋盔甲里都没有 " + MODULE_ID
                + "（可能被 dropAllDeathLoot 之类掏空）⇒ 补回一件";
        expect("⑭人话负对照", "把核里的原因串（带内部 id）喂进判据 ⇒ 报红（那种串不许进聊天栏）", true,
                !leaksInternal(chat1) && leaksInternal(internalSample),
                "聊天行 leaks=" + leaksInternal(chat1) + " ；原因串 leaks=" + leaksInternal(internalSample)
                        + "（含 " + MODULE_ID + "）");
        expect("⑭人话负对照", "英文正文里的 ASCII 字母【不算】漏内部串（判据本轮已反过来）", false,
                leaksInternal("[创始现实修改模块] DAMAGE_BLOCKED x176"), "→ 全大写标签 ＋ xN ⇒ 不命中");

        // 边界：诊断类一条都不发；空窗口不发空行
        final LogGate diagGate = new LogGate();
        final LogGate.Batch diagBatch = diagGate.admitBatch(KIND_BLOOD, 0L);
        expect("⑭边界", "整个窗口全是诊断类 ⇒ chatLine()=null（一条都不发）", true,
                diagBatch != null && diagBatch.chatLine() == null,
                "聊天行=" + (diagBatch == null ? "（本窗口没落盘，不该）" : diagBatch.chatLine())
                        + " ；同一窗口的日志行照旧有 → " + (diagBatch == null ? null : diagBatch.logLine()));
        expect("⑭边界", "三种诊断类单独问 ⇒ 都是 null", true,
                chatPhrase(KIND_BLOOD, 1L) == null && chatPhrase(KIND_HEAL_FAIL, 1L) == null
                        && chatPhrase(KIND_ME, 1L) == null,
                "读血矛盾=" + chatPhrase(KIND_BLOOD, 1L) + " ；回血自证失败=" + chatPhrase(KIND_HEAL_FAIL, 1L)
                        + " ；AE/ME 探针=" + chatPhrase(KIND_ME, 1L));
        expect("⑭边界", "不认识的类别 / kind=null ⇒ 不发（宁可少发，也不漏内部串）", true,
                chatPhrase("某种新类别", 1L) == null && chatPhrase(null, 1L) == null,
                "新类别=" + chatPhrase("某种新类别", 1L) + " ；null=" + chatPhrase(null, 1L));
        final Map<String, Long> mixedWindow = new java.util.LinkedHashMap<>();
        mixedWindow.put(KIND_DAMAGE, 1L);
        mixedWindow.put(KIND_BLOOD, 2L);
        expect("⑭边界", "混窗：事件名类照发 ／ 诊断类被跳过", true,
                "[创始现实修改模块] CANCELLED LivingAttackEvent x1".equals(chatLine(mixedWindow)),
                "→ " + chatLine(mixedWindow));
        expect("⑭边界", "空窗口 ⇒ null（不发空行 —— 空行会在聊天栏留一条空白）", true,
                chatLine(new java.util.LinkedHashMap<>()) == null, "→ " + chatLine(new java.util.LinkedHashMap<>()));
        expect("⑭边界", "单独一条的整行（客户端那半边用）也带前缀", true,
                "[创始现实修改模块] CANCELLED ScreenEvent.Opening x1".equals(chatLineOf(KIND_SCREEN, 1L)),
                "→ " + chatLineOf(KIND_SCREEN, 1L));
        expect("⑭边界", "单独一条：诊断类 ⇒ null", true, chatLineOf(KIND_ME, 1L) == null, "→ " + chatLineOf(KIND_ME, 1L));
        expect("⑭边界", "数量 < 1 一律当 1（x0 / x-5 都不会出现）", true,
                "CANCELLED LivingAttackEvent x1".equals(chatPhrase(KIND_DAMAGE, 0L))
                        && "CANCELLED LivingAttackEvent x1".equals(chatPhrase(KIND_DAMAGE, -5L)),
                "count=0 → " + chatPhrase(KIND_DAMAGE, 0L) + " ；count=-5 → " + chatPhrase(KIND_DAMAGE, -5L));

        // ─────── 🔴 本轮新增的正式负对照：文案【只有动作、没有机制名】必须报红 ───────
        OUT.println("── ⑭【机制名判据】每一类都必须带机制名（带「只有动作」的负对照） ──");
        expect("⑭机制名", "🔴 逐类断言：正文必须含该类的机制名（9/9）", true,
                mechInBodyOk == MAP.length, mechInBodyOk + "/" + MAP.length + " 类含机制名");
        // 负对照①：把机制名抠掉、只留动作 ⇒ 必须报红（正样本同时为真 ⇒ 证明判据有牙）
        final String actionOnly = "CANCELLED DAMAGE x1";
        expect("⑭机制名负对照", "文案只有动作、没有机制名 ⇒ 【报红】", true,
                mechInBodyOk == MAP.length && !actionOnly.contains(chatMechanism(KIND_DAMAGE)),
                "正样本 " + chat1 + " 含机制名=" + (chat1 != null && chat1.contains(chatMechanism(KIND_DAMAGE)))
                        + " ；只有动作那条=" + actionOnly
                        + " 含机制名=" + actionOnly.contains(chatMechanism(KIND_DAMAGE)));
        // 负对照②：机制名【张冠李戴】—— 写的是别的类的机制名 ⇒ 同一条判据也要报红
        final String wrongMech = "CANCELLED " + MECH_ITEM_TOSS + " x1";
        expect("⑭机制名负对照", "机制名张冠李戴（挡伤害写了 ItemTossEvent）⇒ 【报红】", true,
                wrongMech.contains(MECH_ITEM_TOSS) && !wrongMech.contains(chatMechanism(KIND_DAMAGE)),
                "张冠李戴那条=" + wrongMech + " 含该类机制名=" + wrongMech.contains(chatMechanism(KIND_DAMAGE)));
        // 机制名表本身：该给的都给、不该给的一律 null（诊断类不许漏出去）
        final String[] MECH_KINDS = { KIND_DAMAGE, KIND_DEATH, KIND_HEAL, KIND_TELEPORT,
                KIND_REMOVED, KIND_RESTORE, KIND_DISARM, KIND_RESCUED, KIND_SCREEN };
        int mechGiven = 0;
        final StringBuilder mechList = new StringBuilder();
        for (final String k : MECH_KINDS) {
            final String m = chatMechanism(k);
            if (m != null && !m.isEmpty()) {
                mechGiven++;
            }
            mechList.append("\n      ").append(String.format("%-14s → %s", k, m));
        }
        OUT.println("· 类别 → 机制名（逐类真实读数）：" + mechList);
        expect("⑭机制名", "9 类都给了机制名", true, mechGiven == MECH_KINDS.length,
                mechGiven + "/" + MECH_KINDS.length + " 类有机制名");
        expect("⑭机制名", "三种诊断类 ⇒ chatMechanism 返回 null（不许漏给玩家）", true,
                chatMechanism(KIND_BLOOD) == null && chatMechanism(KIND_HEAL_FAIL) == null
                        && chatMechanism(KIND_ME) == null,
                "读血矛盾=" + chatMechanism(KIND_BLOOD) + " ；回血自证失败=" + chatMechanism(KIND_HEAL_FAIL)
                        + " ；AE/ME 探针=" + chatMechanism(KIND_ME));
        expect("⑭机制名", "不认识的类别 / kind=null ⇒ 也返回 null", true,
                chatMechanism("某种新类别") == null && chatMechanism(null) == null,
                "新类别=" + chatMechanism("某种新类别") + " ；null=" + chatMechanism(null));
        expect("⑭机制名", "机制名与「哪一类该不该给玩家看」同进同出（phrase 与 mechanism 的同空性）", true,
                mechAgreeOnNullability(MECH_KINDS),
                "9 类逐个比对 (chatPhrase==null) == (chatMechanism==null) ⇒ " + mechAgreeOnNullability(MECH_KINDS));
        OUT.println();

        OUT.println("=== 汇总：判据 " + checks + " 条，报红 " + reds + " 条 ⇒ "
                + (reds == 0 ? "全绿" : "有红 ✗") + " ===");
        OUT.flush();
        if (reds != 0) {
            System.exit(1);
        }
    }
}
