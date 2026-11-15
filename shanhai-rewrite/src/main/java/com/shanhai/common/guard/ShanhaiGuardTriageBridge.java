package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 山海 · <b>分诊接线（T 件 ②③④ 里"碰游戏"的那一半）</b>：
 * 把守卫各路径上的动作累加成四个读数，拍末交给 {@link ShanhaiGuardTriage} 判一句"解没解决"。
 *
 * <h2>🔴 只记录、不反制（用户点名的硬要求）</h2>
 * 本类<b>一个判定都不改</b>：不碰 {@code setHealth}/{@code setRemoved}/{@code die}、
 * 不返回任何 {@code Decision}、不 cancel 任何事件。它只做三件事 ——
 * <b>读数 → 拼行 → 落盘</b>（外加"每局一次"的关键类扫描）。
 * <p>⇒ 因此它可以在"拦下/救回/判死被拒"的<b>任意一条分支</b>上被调用，
 * 而不会让那条分支的结论发生变化：所有入口方法都 {@code void}，内部 {@code try/catch(Throwable)} 全吞。
 *
 * <h2>四个读数分别从哪来（机器可判，全部只读）</h2>
 * <pre>
 *   R1  {@link #noteDamage}／{@link #noteDeath}  ← {@code ShanhaiGuardEvents.onLivingAttack}／{@code onLivingDeath}
 *       ⇒ "我们的监听器这一拍 fire 过"（<b>只</b>认 Forge 事件，不认 mixin 触达）
 *   R2  {@link #noteRescued}（救过）＋ {@link ShanhaiGuardValueLayer#readHealthField}
 *       ⇒ 拍末读 {@code LivingEntity.DATA_HEALTH_ID} 的<b>字段真值</b>（读不到 = NaN ⇒ 判成没解决）
 *   R3  {@link #noteDirectSetHealth}  ← {@code ShanhaiGuardValueLayer.guardSetHealth}（入参 {@code <=0} 那一支）
 *       ⇒ 我们自己只会写 {@code max>0}，所以走到这儿的一定是<b>外部直调</b>
 *   R4  {@link #noteDie}  ← {@code ShanhaiGuardValueLayer.refuseDie}（{@code decision.yes} 之后）
 *       {@link #noteRemoval} ← 给扩展的 {@code setRemoved} 那一路留的入口（本体这一版没有调用点，见报告）
 * </pre>
 *
 * <h2>限流（规格：同一 src 每人每 5 秒最多 1 行）</h2>
 * 键 = {@code <uuid>|<src>}，窗口 {@link ShanhaiGuardTriage#THROTTLE_WINDOW_MILLIS}；
 * 账本是纯 JDK 的 {@link ShanhaiGuardTriage.Throttle}（离线自检用假时钟跑<b>同一段代码</b>）。
 *
 * <h2>③ 受伤也抓栈（补动态样本）</h2>
 * 收到伤害事件时抓一次栈（{@link ShanhaiGuardTriage#captureStrikeFrames}，StackWalker），
 * <b>每人每局最多 3 次</b>（{@link ShanhaiGuardTriage.Quota}），打
 * {@code [SHANHAI-TRIAGE] strike_caller#N at=… frames=…}（抓不到写 {@code strike_caller#N none}）。
 * <p>⚠️ 抓栈放在"是不是受保护玩家"判定<b>之前</b>：本轮的现场正是"守卫 0 次触发 ⇒ 没有动态样本"
 * ⇒ 只有放在前面，才能拿到"守卫还没认出来"的那些伤害。
 *
 * <h2>线程</h2>
 * 状态读写全在服务端主线程（客户端那一半在入口就 {@code instanceof ServerPlayer} 挡掉）；
 * {@link #statusLine()} ／ {@link #scanNow} 可能来自命令线程 ⇒ 账本用 {@link ConcurrentHashMap}，
 * 读的是"大概齐"的读数（不参与任何判定）。
 */
public final class ShanhaiGuardTriageBridge {

    private ShanhaiGuardTriageBridge() {}

    /** 本件日志前缀（与 {@link ShanhaiGuardTriage#PREFIX} 同一串）。 */
    public static final String PREFIX = ShanhaiGuardTriage.PREFIX;

    /** src 取值：伤害事件（{@code LivingAttackEvent}）。 */
    public static final String SRC_ATTACK = "LivingAttackEvent";
    /** src 取值：死亡事件（{@code LivingDeathEvent}）。 */
    public static final String SRC_DEATH = "LivingDeathEvent";
    /** src 取值：外部直调 {@code setHealth(<=0)}。 */
    public static final String SRC_DIRECT = "direct";
    /** src 取值：{@code die} 触达（规格给的词表是"事件名|direct|remove"，这一个是我加的，见报告）。 */
    public static final String SRC_DIE = "die";
    /** src 取值：{@code setRemoved} 触达。 */
    public static final String SRC_REMOVE = "remove";

    /** 分诊默认开（用户口径：默认开，但只记录不反制）。 */
    public static final boolean DEFAULT_ENABLED = true;

    /** 🔴 运行时开关（<b>只在内存里</b>：不写 {@code shanhai-common.toml}）。 */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /** 同一个 src 每人 5 秒最多一行。 */
    private static final ShanhaiGuardTriage.Throttle THROTTLE =
            new ShanhaiGuardTriage.Throttle(ShanhaiGuardTriage.THROTTLE_WINDOW_MILLIS);

    /** 每个受观察玩家这一拍的读数（服务端主线程写；命令线程只读）。 */
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /** 上一次做过"进世界扫描"的服务器实例（换实例 = 新的一局）。 */
    private static volatile MinecraftServer lastServer;

    /** 关键类扫描已经跑过几次（读数用）。 */
    private static volatile int scansDone;

    /** 一拍的读数容器（{@link ShanhaiGuardTriage.Readings} ＋ 这一拍触发过的 src ＋ 抓栈配额）。 */
    private static final class State {

        final ShanhaiGuardTriage.Readings readings = new ShanhaiGuardTriage.Readings();

        /** 这一拍触发过的 src（顺序 = 首次出现顺序，落盘顺序也照它）。 */
        final Set<String> srcs = new LinkedHashSet<>();

        /** strike_caller 的配额（每人每局 3 次）。 */
        final ShanhaiGuardTriage.Quota strikes = new ShanhaiGuardTriage.Quota(ShanhaiGuardTriage.MAX_STRIKE_CALLERS);

        /** 配额账本的键（{@code uuid} 的字符串形式 —— 与 {@code strikes.take/used} 用的是同一个）。 */
        final String uuid;

        String name = "?";

        /** {@code setRemoved} 那一路带进来的原因（只进日志上下文，不进判据；没走过 ⇒ null）。 */
        String removeReason;

        State(UUID id) {
            this.uuid = id == null ? "?" : id.toString();
        }
    }

    // ═══════════════════════════════ 开关 ═══════════════════════════════

    /** 现在开着吗（默认开）。 */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * 改开关（{@code /shanhai guard triage on|off}）。
     *
     * <p>只改内存里的一个 volatile 布尔 —— <b>不写配置文件</b>（本轮的写入范围不含
     * {@code ShanhaiConfig}；这一条写进报告的"待确认"）。
     *
     * @return 改完之后的值
     */
    public static boolean setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            STATES.clear();
            THROTTLE.clear();
        }
        ShanhaiMod.LOGGER.info("{} triage_switch enabled={} note=只记录不反制", PREFIX, on);
        return enabled;
    }

    /** 清零（新的一局 / 命令重来）：读数、限流、抓栈配额全清。 */
    public static void resetAll() {
        STATES.clear();
        THROTTLE.clear();
    }

    // ═══════════════════════════════ ① 四个读数的入口（全部 void、全部只读） ═══════════════════════════════

    /**
     * R1 ＋（③）抓栈：收到<b>伤害事件</b>时调一次。
     *
     * <p>⚠️ 调用点必须在"是不是受保护玩家"那个判定<b>之前</b>
     * （本轮现场是"守卫 0 次触发 ⇒ 没有动态样本"，放后面就永远抓不到东西）。
     *
     * @param player 事件里的实体（只认 {@link ServerPlayer}；客户端 {@code LocalPlayer} 一律忽略）
     */
    public static void noteDamage(Player player) {
        if (!enabled) {
            return;
        }
        final ServerPlayer sp = asServerPlayer(player);
        if (sp == null) {
            return;
        }
        try {
            final State st = state(sp);
            st.readings.listenerFired = true;
            st.readings.damageEvent = true;
            st.srcs.add(SRC_ATTACK);
            captureStrike(sp, st);
        } catch (Throwable ignored) {
            // 取证坏了绝不许影响"挡伤害"那条路
        }
    }

    /** R1：收到<b>死亡事件</b>时调一次。 */
    public static void noteDeath(Player player) {
        if (!enabled) {
            return;
        }
        final ServerPlayer sp = asServerPlayer(player);
        if (sp == null) {
            return;
        }
        try {
            final State st = state(sp);
            st.readings.listenerFired = true;
            st.readings.deathEvent = true;
            st.srcs.add(SRC_DEATH);
        } catch (Throwable ignored) {
        }
    }

    /** R2 的"救过"：我们确实救过（拦下死亡 ＋ 补血 ／ 改写入参 ／ 拒死）时调一次。 */
    public static void noteRescued(Player player) {
        if (!enabled) {
            return;
        }
        final ServerPlayer sp = asServerPlayer(player);
        if (sp == null) {
            return;
        }
        try {
            state(sp).readings.rescued = true;
        } catch (Throwable ignored) {
        }
    }

    /**
     * R3：外部直调 {@code setHealth(<=0)} 被我们看到了（{@code guardSetHealth} 的改写分支里调）。
     *
     * @param value 原入参（只进读数，不进判据 —— 能走到这儿必然 {@code <= 0}）
     */
    public static void noteDirectSetHealth(Player player, float value) {
        if (!enabled) {
            return;
        }
        final ServerPlayer sp = asServerPlayer(player);
        if (sp == null) {
            return;
        }
        try {
            final State st = state(sp);
            st.readings.directSetHealth = true;
            st.srcs.add(SRC_DIRECT);
        } catch (Throwable ignored) {
        }
    }

    /** R4：{@code die} 被触达（{@code refuseDie} 判出"拒死"之后调一次；拒死本身就是一次救）。 */
    public static void noteDie(Player player) {
        if (!enabled) {
            return;
        }
        final ServerPlayer sp = asServerPlayer(player);
        if (sp == null) {
            return;
        }
        try {
            final State st = state(sp);
            st.readings.dieOrRemove = true;
            st.readings.rescued = true;   // 拒死 ⇒ 这一拍我们救过（R2 的"救完之后"就是它）
            st.srcs.add(SRC_DIE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * R4：{@code setRemoved} 被触达（<b>给扩展留的入口</b>）。
     *
     * <p>⚠️ <b>本体这一版没有调用点</b>：{@code setRemoved} 的 mixin 执行体已经搬进
     * {@code shanhai-rewrite-add}，本轮不许碰那个模块 ⇒ 这里只把入口备好，等扩展侧接上
     * （报告里"待确认"有这一条）。
     *
     * @param player 目标实体（只认 {@link ServerPlayer}）
     * @param reason RemovalReason 的名字（只进日志上下文，不进判据）
     */
    public static void noteRemoval(Player player, String reason) {
        if (!enabled) {
            return;
        }
        final ServerPlayer sp = asServerPlayer(player);
        if (sp == null) {
            return;
        }
        try {
            final State st = state(sp);
            st.readings.dieOrRemove = true;
            st.srcs.add(SRC_REMOVE);
            st.name = sp.getName().getString();
            st.removeReason = reason;   // 只进日志上下文（flushTick 里拼在 remove 那一行尾部）
        } catch (Throwable ignored) {
        }
    }

    // ═══════════════════════════════ ③ 受伤抓栈（每人每局 3 次） ═══════════════════════════════

    private static void captureStrike(ServerPlayer player, State st) {
        final int index = st.strikes.take(st.uuid);
        if (index <= 0) {
            return;   // 额度用完：连栈都不抓（这是"每 tick 一车伤害"那条路上唯一的一次省）
        }
        final List<String> frames;
        try {
            frames = ShanhaiGuardTriage.captureStrikeFrames();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.info("{} strike_caller#{} none ｜ player={}（抓栈抛异常：{}）",
                    PREFIX, index, player.getName().getString(), t.toString());
            return;
        }
        ShanhaiMod.LOGGER.info(ShanhaiGuardTriage.strikeLine(index, frames) + " ｜ player="
                + player.getName().getString());
    }

    // ═══════════════════════════════ 拍末：打那一行 ＋ 清读数 ═══════════════════════════════

    /**
     * 🔴 <b>进世界后一次</b>：换服务器实例 ⇒ 清账 ＋ 扫一遍关键类（被动、只读）。
     *
     * <p>调用点放在 {@code ShanhaiGuardEvents.onServerTick} 的<b>最前面</b>
     * （在 {@code ShanhaiGuardCore.enabled} 那个闸门<b>之前</b>）：关键类扫描与守卫开关无关，
     * 关掉守卫也该看得见"谁改过 LivingEntity"。
     *
     * @param server 当前服务器（{@code null} ⇒ 什么也不做）
     */
    public static void onServerTickHead(MinecraftServer server) {
        if (server == null || server == lastServer) {
            return;
        }
        lastServer = server;
        resetAll();                     // 新的一局：配额/限流/读数全清
        try {
            scanAndLog(null);           // 进世界后一次（失败只打一行 warn）
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} keyclass_scan_failed err={}（不影响任何游戏行为）",
                    ShanhaiKeyClassScan.LINE_PREFIX.trim(), t.toString());
        }
    }

    /**
     * 拍末（同一个 tick 内）：凡是这一拍发生过 R1–R4 任一事件的玩家，按 src 各打一行
     * {@code [SHANHAI-TRIAGE] solved=… r1=… …}（同一 src 每人 5 秒最多一行）。
     *
     * <p>血量读数在这里取：<b>这就是"救完之后"那一拍</b>。
     *
     * @param roster 这一拍在线玩家表（{@code null}/空 ⇒ 只清账）
     */
    public static void flushTick(List<ServerPlayer> roster) {
        if (!enabled) {
            return;
        }
        if (roster == null || roster.isEmpty()) {
            STATES.clear();
            return;
        }
        final long now = System.currentTimeMillis();
        final Set<UUID> present = new LinkedHashSet<>(Math.max(8, roster.size() * 2));
        for (final ServerPlayer player : roster) {
            if (player == null) {
                continue;
            }
            final UUID id = player.getUUID();
            present.add(id);
            final State st = STATES.get(id);
            if (st == null) {
                continue;
            }
            if (!st.readings.any() && st.srcs.isEmpty()) {
                continue;
            }
            try {
                // 拍末读一次：字段真值（不信 getHealth）＋ API 读数（只做对照）
                st.readings.hpTrue = ShanhaiGuardValueLayer.readHealthField(player);
                st.readings.hpApi = player.getHealth();
                // 🔴 2026-11-07 修（实测假阴性）：R2 的语义是「**救完之后字段真值血量 > 0**」，
                //   旧实现只在"真的救过"（拒死／拦死亡／补血）时把 rescued 置真 ⇒
                //   **普通一次被拦下的攻击 r2 恒为 false** ⇒ 明明挡住了却报 `solved=false`。
                //   实测现场：僵尸 3 点伤害被取消、hp_true=20.0，却打出 solved=false r2=false。
                //   现在以"拍末字段真值 > 0"为准（NaN = 读不到 ⇒ 保持 fail-closed，不置真）。
                if (!Float.isNaN(st.readings.hpTrue) && st.readings.hpTrue > 0.0f) {
                    st.readings.rescued = true;
                }
                final String who = player.getName().getString();
                for (final String src : new ArrayList<>(st.srcs)) {
                    if (!THROTTLE.admit(id + "|" + src, now)) {
                        continue;   // 同一 src 5 秒内已经打过
                    }
                    final String tail = (SRC_REMOVE.equals(src) && st.removeReason != null)
                            ? " ｜ remove_reason=" + st.removeReason : "";
                    ShanhaiMod.LOGGER.info(ShanhaiGuardTriage.line(st.readings, src)
                            + " ｜ player=" + who + tail);
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.info("{} triage_line_failed player={} err={}",
                        PREFIX, player.getName().getString(), t.toString());
            } finally {
                st.readings.clear();
                st.srcs.clear();
                st.removeReason = null;
            }
        }
        STATES.keySet().retainAll(present);
    }

    // ═══════════════════════════════ ④ 关键类扫描（一次性 ／ 命令） ═══════════════════════════════

    /**
     * 扫一次关键类并把每一条明细打出来（{@code /shanhai guard scan} 与"进世界后一次"共用）。
     *
     * @param loader {@code null} ⇒ 用本 mod 的 classloader（Forge 下它能看见 {@code net.minecraft.*}）
     * @return 明细行（调用方可以直接回显）
     */
    public static List<String> scanAndLog(ClassLoader loader) {
        final ClassLoader cl = loader != null ? loader : ShanhaiGuardTriageBridge.class.getClassLoader();
        final List<ShanhaiKeyClassScan.Result> results = ShanhaiKeyClassScan.scan(cl);
        final List<String> lines = ShanhaiKeyClassScan.lines(results);
        for (final String line : lines) {
            ShanhaiMod.LOGGER.info(line);
        }
        ShanhaiMod.LOGGER.info(ShanhaiKeyClassScan.summaryLine(results));
        scansDone++;
        return lines;
    }

    /** 已经扫过几次。 */
    public static int scansDone() {
        return scansDone;
    }

    // ═══════════════════════════════ 命令：自检 ═══════════════════════════════

    /** 自检结果（命令入口回显用）。 */
    public static final class SelftestOutcome {

        /** 一共判了几条。 */
        public int checks;
        /** 红的几条。 */
        public int reds;
        /** 完整自检文本（已经一行一行落进日志）。 */
        public String text = "";

        public boolean green() {
            return reds == 0;
        }
    }

    /**
     * 跑 ②③④ 的<b>纯逻辑</b>自检（不碰世界、不改任何状态）。
     *
     * <p>判据来自 {@link ShanhaiGuardTriage#selfcheck} 与 {@link ShanhaiKeyClassScan#selfcheck}
     * —— 与离线 {@code javac} 自检<b>同一份代码</b>，所以"游戏里绿"和"离线绿"是同一件事。
     */
    public static SelftestOutcome selftest() {
        final SelftestOutcome out = new SelftestOutcome();
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(16 * 1024);
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            final ShanhaiGuardTriage.SelfcheckResult a = ShanhaiGuardTriage.selfcheck(ps);
            final ShanhaiKeyClassScan.SelfcheckResult b = ShanhaiKeyClassScan.selfcheck(ps);
            out.checks = a.checks + b.checks;
            out.reds = a.reds + b.reds;
        } catch (Throwable t) {
            out.reds++;
            ShanhaiMod.LOGGER.warn("{} selftest_failed err={}", PREFIX, t.toString());
        }
        out.text = buf.toString(StandardCharsets.UTF_8);
        for (final String line : out.text.split("\n", -1)) {
            if (!line.isEmpty()) {
                ShanhaiMod.LOGGER.info("{} selftest ｜ {}", PREFIX, line);
            }
        }
        ShanhaiMod.LOGGER.info("{} selftest 合计 {} 条，红 {} 条 ⇒ {}",
                PREFIX, out.checks, out.reds, out.green() ? "全绿 PASS" : "有红 FAIL");
        return out;
    }

    // ═══════════════════════════════ 读数（命令回显） ═══════════════════════════════

    /** 一行总表。 */
    public static String statusLine() {
        int strikeTotal = 0;
        for (final State st : STATES.values()) {
            strikeTotal += st.strikes.used(st.uuid);
        }
        return "triage enabled=" + enabled + "（默认 " + DEFAULT_ENABLED + "，运行时开关，不落配置）"
                + " 抓栈=每人每局最多 " + ShanhaiGuardTriage.MAX_STRIKE_CALLERS + " 次"
                + " 限流=" + ShanhaiGuardTriage.THROTTLE_WINDOW_MILLIS + "ms/同一 src 每人"
                + " 在册玩家=" + STATES.size()
                + " 已抓栈=" + strikeTotal
                + " 本拍无样本=" + quietPlayers()
                + " 限流账=" + THROTTLE.size() + " 键"
                + " keyclass_scan=" + scansDone + " 次"
                + "（读数说明：solved = R1 && R2 && !R3 && !R4）";
    }

    // ═══════════════════════════════ 小工具 ═══════════════════════════════

    private static ServerPlayer asServerPlayer(Player player) {
        return player instanceof ServerPlayer sp ? sp : null;
    }

    private static State state(ServerPlayer player) {
        final UUID id = player.getUUID();
        final State st = STATES.get(id);
        if (st != null) {
            return st;
        }
        final State fresh = new State(id);
        fresh.name = player.getName().getString();
        final State prev = STATES.putIfAbsent(id, fresh);
        return prev != null ? prev : fresh;
    }

    /** 只读快照：某个玩家这一拍抓过几次栈（命令/报告用；没记录 ⇒ 0）。 */
    public static int strikesUsed(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        final State st = STATES.get(player.getUUID());
        return st == null ? 0 : st.strikes.used(player.getUUID().toString());
    }

    /** 只读快照：现在在册哪些玩家（命令/报告用）。 */
    public static List<String> watchedNames() {
        final List<String> out = new ArrayList<>();
        for (final State st : STATES.values()) {
            out.add(st.name);
        }
        Collections.sort(out);
        return out;
    }

    /** 只读快照：这一拍从没读到过任何东西的玩家有几个（用来判断"到底有没有样本"）。 */
    public static int quietPlayers() {
        int quiet = 0;
        for (final State st : STATES.values()) {
            if (!st.readings.any()) {
                quiet++;
            }
        }
        return quiet;
    }
}
