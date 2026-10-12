package com.shanhai.client.holo;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.holo.ShanhaiHoloRemoteCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海重构 · 联机全息的<b>收方那张表</b>：{@code UUID → 那个人的全息状态}。
 *
 * <h2>1. 谁写、谁读</h2>
 * <table border="1">
 *   <tr><th>动作</th><th>在哪个线程</th><th>怎么落进来</th></tr>
 *   <tr><td>写（DEF / STATE 到了）</td><td><b>网络线程</b></td>
 *       <td>包处理器里先 {@code ctx.enqueueWork(...)} 排到<b>主线程</b>再调 {@link #acceptDef} /
 *           {@link #acceptState}（本工程既有口径，见 {@code ShanhaiHoloMenuNetwork} 的 handle）</td></tr>
 *   <tr><td>读（这一帧要不要画他）</td><td><b>渲染线程</b></td>
 *       <td>{@link #of} / {@link #paintable}</td></tr>
 * </table>
 * ⇒ 两侧线程不同 ⇒ 用 {@link ConcurrentHashMap}（不锁、读不用同步）。
 * 单条 Entry 的字段只在主线程上写，渲染线程只读<b>已经写完</b>的对象 ⇒ 不会读到半个状态。
 *
 * <h2>2. 🔴 三条硬约定</h2>
 * <ol>
 *   <li><b>没收到 DEF ⇒ 不画</b>（{@link Entry#defReceived}）。否则会用<b>我自己的</b> 15 项参数
 *       去画别人的星空 —— 那正是本功能要消灭的"两人眼里的星空不是同一个东西"。</li>
 *   <li><b>来路只认服务端</b>：只有 {@code PLAY_TO_CLIENT} 那条能写这张表。
 *       C2S 那条（客户端把自己的设置报上去）<b>不许</b>用来改别人的表 —— 收件人由服务端算。</li>
 *   <li><b>换维度 / 断线要清干净</b>（{@link #clearAll}）：{@code ClientLevel.players()} 只含当前维度
 *       ⇒ 换维度那一刻旧玩家全部卸载，表里留着他们就是"幽灵板子"。</li>
 * </ol>
 *
 * <p>⚠️ 本类<b>刻意不 import 任何 Minecraft 类</b>（只有 {@code UUID} / {@code Map}）⇒
 * 它能在离线套件里被真跑起来（{@link #selfTest()} 含负对照）。
 */
public final class ShanhaiHoloRemoteState {

    private ShanhaiHoloRemoteState() {
    }

    /** 本类日志前缀（便于 grep；与 {@code ShanhaiHoloMenuNetwork.PREFIX} 同一族）。 */
    public static final String PREFIX = "[SHANHAI-HOLO-MP]";

    /** 一个人（一个 UUID）那份状态。字段只在主线程写、渲染线程只读。 */
    public static final class Entry {

        /** 对方开着投影没有（他那边 {@code ShanhaiHoloMenuState.enabled()}）。 */
        public boolean enabled;

        /** 排布序号（0=ring 1=column 2=row）。 */
        public int layout = ShanhaiHoloRemoteCodec.LAYOUT_RING;

        /** 距离（0.8 ~ 8.0 格）。 */
        public float distance = 3.0f;

        /** 朝向档（0=FOLLOW 1=SNAP 2=FIXED）；环绕档下无意义。 */
        public int facing = ShanhaiHoloRemoteCodec.FACING_FIXED;

        /** 环绕档（0=关 1=弱 2=中 3=强）—— DEF 与 STATE 都带，后到的那份赢。 */
        public int surroundPreset;

        /** 15 项高级调参（DEF 给的；没收到 DEF 之前是 null）。 */
        public float[] params;

        /** 面板开着没有 —— 🔴 本轮收方<b>不用</b>它（面板不同步），只留读数。 */
        public boolean panelOpen;

        /** 🔴 收到 DEF 没有。false ⇒ 这一份<b>不画</b>（见类注释 §2-1）。 */
        public boolean defReceived;

        /** 最后收到 STATE / DEF 的时刻（{@code System.nanoTime()}），做读数与超时用。 */
        public long lastStateNanos;
        public long lastDefNanos;

        /** 收到过几条包（读数用：能看出"只在登录发一次"这件事有没有真的发生）。 */
        public int statePackets;
        public int defPackets;
    }

    /** 那张表。渲染线程读、主线程写 ⇒ {@link ConcurrentHashMap}。 */
    private static final Map<UUID, Entry> REMOTE = new ConcurrentHashMap<>();

    /** 统计：清过几次、为什么清（读数用）。 */
    private static int clearedCount;
    private static String lastClearReason = "none";

    // ================================================================== 读（渲染线程）

    /** 那个人的那一份；<b>没有就返回 {@code null}</b>（只读，不建）。 */
    public static Entry of(UUID id) {
        return id == null ? null : REMOTE.get(id);
    }

    /**
     * 🔴 <b>这一帧该不该画他</b> —— 本地渲染路径唯一该问的问题。
     *
     * <p>= 表里有他 ＆ 他自己开着投影 ＆ <b>他的 DEF 已经到了</b>（否则会用我的参数画他的星空）。
     */
    public static boolean paintable(UUID id) {
        final Entry e = of(id);
        return e != null && e.enabled && e.defReceived;
    }

    /** 表里现在跟着几个人（换维度/断线之后必须回到 0 —— 这是一条机器可判读数）。 */
    public static int tracked() {
        return REMOTE.size();
    }

    // ================================================================== 写（主线程）

    /** 收到 DEF（{@code preset} + 15 项）。{@code params} 长度不对 ⇒ 整包丢掉（不用半个包画人）。 */
    public static void acceptDef(UUID id, int surroundPreset, float[] params) {
        if (id == null || params == null || params.length != ShanhaiHoloRemoteCodec.PARAM_COUNT) {
            ShanhaiMod.LOGGER.warn("{} holo_mp_def_rejected reason=bad_payload id={} len={}",
                    PREFIX, id, params == null ? -1 : params.length);
            return;
        }
        final Entry e = REMOTE.computeIfAbsent(id, k -> new Entry());
        e.surroundPreset = surroundPreset;
        e.params = params.clone();                    // ← 拷贝：包缓冲会被复用，不能留引用
        e.defReceived = true;
        e.defPackets++;
        e.lastDefNanos = System.nanoTime();
    }

    /** 收到 STATE（开关 / 排布 / 距离 / 朝向 / 环绕档）。 */
    public static void acceptState(UUID id, boolean enabled, int layout, float distance,
                                   int facing, int surroundPreset, boolean panelOpen) {
        if (id == null) {
            return;
        }
        final Entry e = REMOTE.computeIfAbsent(id, k -> new Entry());
        e.enabled = enabled;
        e.layout = layout;
        e.distance = distance;
        e.facing = facing;
        e.surroundPreset = surroundPreset;
        e.panelOpen = panelOpen;
        e.statePackets++;
        e.lastStateNanos = System.nanoTime();
    }

    /**
     * 忘掉一个人（他卸载了 / 他退出去了 / 他收了模块）。
     *
     * <p>⚠️ 与"他只是关了投影"不同：那种情况<b>留着他的 DEF</b>（他再打开时不用重发 61 字节）。
     */
    public static void forget(UUID id) {
        if (id != null) {
            REMOTE.remove(id);
        }
    }

    /** 全部清掉（换维度 / 断线）。返回清掉几个人。 */
    public static int clearAll(String reason) {
        final int n = REMOTE.size();
        REMOTE.clear();
        clearedCount++;
        lastClearReason = reason == null ? "unknown" : reason;
        ShanhaiMod.LOGGER.info("{} holo_mp_cleared reason={} tracked_before={} cleared_total={}",
                PREFIX, lastClearReason, n, clearedCount);
        return n;
    }

    // ================================================================== 读数

    /** 一行读数（机器可判；开机与换维度都打）。 */
    public static String describe() {
        return "tracked=" + REMOTE.size()
                + " paintable=" + countPaintable()
                + " cleared_total=" + clearedCount
                + " last_clear=" + lastClearReason;
    }

    private static int countPaintable() {
        int n = 0;
        for (Entry e : REMOTE.values()) {
            if (e.enabled && e.defReceived) {
                n++;
            }
        }
        return n;
    }

    /** 表里每个人都来一行（排查用；最多打 8 个人，免得刷屏）。 */
    public static List<String> lines() {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> kv : REMOTE.entrySet()) {
            if (out.size() >= 8) {
                break;
            }
            final Entry e = kv.getValue();
            out.add(String.format(Locale.ROOT,
                    "uuid=%s enabled=%s def=%s layout=%d distance=%.2f preset=%d state_pkts=%d def_pkts=%d",
                    kv.getKey().toString().substring(0, 8), e.enabled, e.defReceived,
                    e.layout, e.distance, e.surroundPreset, e.statePackets, e.defPackets));
        }
        return out;
    }

    // ================================================================== 离线自检

    /**
     * <b>离线自检</b>（纯逻辑，不碰游戏）：走一遍"没 DEF ⇒ 不画 → 收 DEF ⇒ 能画 → 关开关 ⇒ 不画
     * → 换维度清空 ⇒ 回到 0 个人"这条状态机，并带一条负对照（少一个参数必须被拒）。
     */
    public static String selfTest() {
        final Map<UUID, Entry> backup = new java.util.HashMap<>(REMOTE);
        final int savedCleared = clearedCount;
        final String savedReason = lastClearReason;
        int checks = 0;
        int fails = 0;
        final StringBuilder bad = new StringBuilder();
        try {
            REMOTE.clear();
            final UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

            checks++;
            if (paintable(a)) {
                fails++;
                bad.append(" unknown_should_not_paint");
            }

            // 只有 STATE（没 DEF）⇒ 仍然不许画（这就是"宁可晚一帧"那条）
            acceptState(a, true, ShanhaiHoloRemoteCodec.LAYOUT_RING, 3.0f, 2, 2, false);
            checks++;
            if (paintable(a)) {
                fails++;
                bad.append(" no_def_should_not_paint");
            }
            checks++;
            if (tracked() != 1) {
                fails++;
                bad.append(" tracked_after_state");
            }

            // 收 DEF ⇒ 可以画了
            final float[] p = new float[ShanhaiHoloRemoteCodec.PARAM_COUNT];
            for (int i = 0; i < p.length; i++) {
                p[i] = i + 0.5f;
            }
            acceptDef(a, 3, p);
            checks++;
            if (!paintable(a)) {
                fails++;
                bad.append(" def_then_paint");
            }

            // 🆕 负对照：少一个参数必须被拒（并且不能把 defReceived 置上）
            final UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
            acceptDef(b, 2, new float[ShanhaiHoloRemoteCodec.PARAM_COUNT - 1]);
            checks++;
            if (paintable(b) || of(b) != null) {
                fails++;
                bad.append(" neg_short_def_accepted");
            }

            // 参数是拷贝：改调用方那份不许影响表里那份
            p[0] = 999.0f;
            checks++;
            if (of(a) == null || of(a).params[0] != 0.5f) {
                fails++;
                bad.append(" params_not_copied");
            }

            // 关掉 ⇒ 不画（但表里还留着他的 DEF）
            acceptState(a, false, ShanhaiHoloRemoteCodec.LAYOUT_RING, 3.0f, 2, 2, false);
            checks++;
            if (paintable(a) || of(a) == null || !of(a).defReceived) {
                fails++;
                bad.append(" disabled_still_paints");
            }

            // 换维度 ⇒ 清空
            acceptState(a, true, ShanhaiHoloRemoteCodec.LAYOUT_RING, 3.0f, 2, 2, false);
            final int cleared = clearAll("selftest");
            checks++;
            if (cleared != 1 || tracked() != 0 || paintable(a)) {
                fails++;
                bad.append(" clear_all");
            }
        } finally {
            REMOTE.clear();
            REMOTE.putAll(backup);
            clearedCount = savedCleared;
            lastClearReason = savedReason;
        }
        return String.format(Locale.ROOT, "holo_remote_state_selfcheck ok=%s checks=%d fails=%d%s %s",
                fails == 0, checks, fails, bad, describe());
    }
}
