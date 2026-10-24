package com.shanhai.common.diag;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.ShanhaiMod;
import com.shanhai.config.ShanhaiConfig;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「配方进度被清零」只读诊断探针的<b>接线层</b>（MC / GT 依赖都在这里，判定逻辑在
 * {@link RecipeProbeClassify}）。
 *
 * <h2>本类只做三件事，一个字节的行为都不改</h2>
 * <ol>
 *   <li><b>取现场</b>：机器坐标 / 维度 / 游戏时间 / {@code progress} / {@code duration} /
 *       {@code status} / {@code lastRecipe} 的 id / 仓室简表 / 过滤后的调用栈。</li>
 *   <li><b>限流</b>：同一台机器（按 pos）同一类事件 <b>2 秒最多 1 行</b>，行尾 {@code folded=N}
 *       （N = 自上一次真正打出来以后被折叠掉的次数）。另加一个<b>全局</b>预算
 *       （1 秒 80 行），超了只记账不打印 —— 这条是为了不重演本工程 2026-10-01 那次
 *       「一次实测 9431 行日志、其中 4311 行是同一台机器刷的」。</li>
 *   <li><b>汇总</b>：每 60 秒一行 {@code summary interrupts=… damps=… onworkingFalse=… gates=…}。</li>
 * </ol>
 *
 * <h2>🔴 为什么每个入口都套 try/catch(Throwable) 且【什么都不做】</h2>
 * 探针的契约是「没挂上、没数据、坏了，都不能影响机器」。所有 {@code public static} 入口
 * 一旦抛异常就会顺着 {@code @Inject} 落进 GT 的 {@code interruptRecipe} / {@code doDamping} /
 * {@code onWorking} / 上游的闸门里 ⇒ 那就是<b>改行为</b>。所以：全部吞掉。
 * （{@code require = 0} 只保证"注入失败不崩"，保证不了"注入成功后 handler 抛异常不崩"。）
 *
 * <h2>开关</h2>
 * {@code config/shanhai-common.toml} 的 {@code [diag] recipe_probe}，<b>默认 true</b>；
 * 配置还没加载时按默认值 true 处理（这一轮就是要抓现场），见 {@link #enabled()}。
 */
public final class RecipeProbe {

    /** 统一前缀。 */
    public static final String PREFIX = RecipeProbeClassify.PREFIX;

    /** 同一台机器同一类事件的最小间隔（毫秒）。任务口径：2 秒。 */
    private static final long THROTTLE_MS = 2_000L;

    /** 汇总行的间隔（毫秒）。任务口径：60 秒。 */
    private static final long SUMMARY_MS = 60_000L;

    /** 全局每秒最多打多少行（防一次性刷爆日志；被挡下的次数记进 summary 的 foldedGlobal=）。 */
    private static final int GLOBAL_LINES_PER_SECOND = 80;

    /** 单类事件的限流表最多记多少台机器；超了整表清空（宁可多打一行，不许无界增长）。 */
    private static final int MAX_KEYS = 20_000;

    private static final Object LOCK = new Object();

    private static final RecipeProbeClassify.Kind[] KINDS = RecipeProbeClassify.Kind.values();

    /**
     * 每类事件一张 {posKey -> {上次打印毫秒, 已折叠条数}}。
     *
     * <p>⚠️ 按 {@link RecipeProbeClassify.Kind} 的 {@code ordinal()} 分表 ⇒
     * {@code setup_gate} 的 enter 与 exit 是<b>两张表</b>（各自限流），{@code finish_gate} 同理。
     * 这是<b>刻意</b>的：如果 enter 与 exit 共用一张表，进场那一行会把 1 tick 之后的
     * <b>出场那一行</b>折叠掉 —— 而出场那行才是"配方被判丢了"的判据。宁可多一行，
     * 不许把唯一的证据折掉。限流口径因此写作：<b>同一台机器、同一类事件（enter / exit 分别算）
     * 2 秒最多 1 行</b>。
     */
    @SuppressWarnings("unchecked")
    private static final Map<Long, long[]>[] THROTTLE = new Map[KINDS.length];

    /** 每类事件的累计真实次数（含被折叠的）。 */
    private static final long[] COUNT = new long[KINDS.length];

    static {
        for (int i = 0; i < THROTTLE.length; i++) {
            THROTTLE[i] = new HashMap<>();
        }
    }

    private static long totalCleared;
    private static long globalFolded;
    private static long globalWindowStart;
    private static int globalWindowLines;
    private static long lastSummaryMs;
    private static final long STARTED_MS = System.currentTimeMillis();

    private RecipeProbe() {}

    // ═════════════════════════════ 开关 ═════════════════════════════

    /**
     * 探针是否启用（{@code [diag] recipe_probe}）。
     *
     * <p>配置未加载 / 读配置本身出错 ⇒ 一律按 {@code true}（默认值）处理：
     * 这一轮的交付目标就是「能在现场捞到数据」，静默关掉比多打几行更糟。
     */
    public static boolean enabled() {
        try {
            // 与全工程同款兜底（见 ShanhaiConfig#isLoaded 的注释）：配置未加载时 ConfigValue.get()
            // 会直接抛 IllegalStateException；这里回落默认值 true，不是抛出去。
            if (!ShanhaiConfig.isLoaded()) {
                return true;
            }
            return ShanhaiConfig.COMMON.recipeProbe.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 启动日志里那一行「当前值」。 */
    public static String configLine() {
        boolean loaded;
        try {
            loaded = ShanhaiConfig.isLoaded();
        } catch (Throwable t) {
            loaded = false;
        }
        return "config [diag] recipe_probe=" + enabled()
                + " loaded=" + loaded
                + " default=true source=config/shanhai-common.toml"
                + " （关掉它 = 本组探针一行都不打；改完需要重启生效）";
    }

    // ═════════════════════════════ 四个注入点的入口 ═════════════════════════════

    /**
     * P1：{@code RecipeLogic#interruptRecipe} HEAD。
     *
     * <p>行形：{@code interrupt machine=<dim>@<x>,<y>,<z> tick=<gt> progress=<p> duration=<d>
     * status=<s> lastRecipe=<id|null> cleared=<0|1> [neg=…] caller=<最多4帧> folded=<N>}
     */
    public static void interrupt(RecipeLogic logic) {
        try {
            if (logic == null || !enabled()) {
                return;
            }
            // ⚠️ 顺序很重要：先取<b>便宜的</b>状态 + 过限流，只有真的要打这一行时才去抓栈
            //    （抓栈 = new Throwable().getStackTrace()，每 tick 每台机器都做一次太浪费）。
            final GTRecipe last = logic.getLastRecipe();
            final int progress = logic.getProgress();
            final int duration = logic.getDuration();
            final RecipeProbeClassify.Verdict v = RecipeProbeClassify.interrupt(last != null, progress, duration);
            final MetaMachine machine = selfOf(logic);
            final long folded = admit(RecipeProbeClassify.Kind.INTERRUPT, keyOf(machine), v.cleared);
            if (folded < 0L) {
                return;
            }
            print(RecipeProbeClassify.Kind.INTERRUPT, "machine=" + pos(machine)
                    + " tick=" + gameTime(machine)
                    + " progress=" + progress
                    + " duration=" + duration
                    + " status=" + status(logic)
                    + " lastRecipe=" + recipeId(last)
                    + ' ' + v.tags
                    + " caller=" + caller(4, "interruptRecipe"), folded);
        } catch (Throwable ignored) {
            // 探针绝不许影响 GT 的行为
        }
    }

    /**
     * P2：{@code RecipeLogic#doDamping} HEAD。
     *
     * <p>行形：{@code damp machine=… reason=<waitingReason> progress=<p> status=<s> duration=<d>
     * cleared=<0|1> folded=<N>}
     */
    public static void damp(RecipeLogic logic) {
        try {
            if (logic == null || !enabled()) {
                return;
            }
            final int progress = logic.getProgress();
            final RecipeProbeClassify.Verdict v = RecipeProbeClassify.damp(progress);
            final MetaMachine machine = selfOf(logic);
            final long folded = admit(RecipeProbeClassify.Kind.DAMP, keyOf(machine), v.cleared);
            if (folded < 0L) {
                return;
            }
            print(RecipeProbeClassify.Kind.DAMP, "machine=" + pos(machine)
                    + " reason=" + waitingReason(logic)
                    + " progress=" + progress
                    + " status=" + status(logic)
                    + " duration=" + logic.getDuration()
                    + ' ' + v.tags, folded);
        } catch (Throwable ignored) {
        }
    }

    /**
     * P3：{@code WorkableMultiblockMachine#onWorking} RETURN（只在返回 false 时调用）。
     *
     * <p>行形：{@code onworking_false machine=… parts=<仓室简表> progress=<p> status=<s>
     * duration=<d> folded=<N>}
     */
    public static void onWorkingFalse(WorkableMultiblockMachine machine) {
        try {
            if (machine == null || !enabled()) {
                return;
            }
            final RecipeLogic logic = machine.recipeLogic;
            final RecipeProbeClassify.Verdict v = RecipeProbeClassify.onWorkingFalse();
            final long folded = admit(RecipeProbeClassify.Kind.ONWORKING_FALSE, keyOf(machine), v.cleared);
            if (folded < 0L) {
                return;
            }
            print(RecipeProbeClassify.Kind.ONWORKING_FALSE, "machine=" + pos(machine)
                    + " parts=" + parts(machine)
                    + " progress=" + (logic == null ? -1 : logic.getProgress())
                    + " status=" + (logic == null ? "?" : status(logic))
                    + " duration=" + (logic == null ? -1 : logic.getDuration())
                    + ' ' + v.tags, folded);
        } catch (Throwable ignored) {
        }
    }

    /** P4 HEAD：找配方的闸门（{@code MutableRecipesLogic#findAndHandleMultipleRecipe} / {@code GTLAddMultipleRecipesLogic#findAndHandleRecipe}）。 */
    public static void gateEnter(Object logic, String impl) {
        try {
            gate(RecipeProbeClassify.Kind.GATE_ENTER, logic, impl);
        } catch (Throwable ignored) {
        }
    }

    /** P4 RETURN。 */
    public static void gateExit(Object logic, String impl) {
        try {
            gate(RecipeProbeClassify.Kind.GATE_EXIT, logic, impl);
        } catch (Throwable ignored) {
        }
    }

    /** P5 HEAD：完工后再找下一条配方的闸门（这条闸门失败会直接 {@code progress=0}）。 */
    public static void finishEnter(Object logic, String impl) {
        try {
            gate(RecipeProbeClassify.Kind.FINISH_ENTER, logic, impl);
        } catch (Throwable ignored) {
        }
    }

    /** P5 RETURN。 */
    public static void finishExit(Object logic, String impl) {
        try {
            gate(RecipeProbeClassify.Kind.FINISH_EXIT, logic, impl);
        } catch (Throwable ignored) {
        }
    }

    /**
     * P4 / P5 共用的取现场 + 判定。
     *
     * <p>行形：{@code setup_gate|finish_gate machine=… match=<配方id|null> layout=<仓室简表>
     * impl=<哪个类> status=<s> phase=enter|exit hadRecipe=<0|1> progress=<p> duration=<d>
     * cleared=<0|1> folded=<N>}
     */
    private static void gate(RecipeProbeClassify.Kind kind, Object rawLogic, String impl) {
        if (!(rawLogic instanceof RecipeLogic logic) || !enabled()) {
            return;
        }
        final GTRecipe last = logic.getLastRecipe();
        final int progress = logic.getProgress();
        final int duration = logic.getDuration();
        final boolean has = last != null;
        final RecipeProbeClassify.Verdict v;
        if (kind == RecipeProbeClassify.Kind.GATE_ENTER) {
            v = RecipeProbeClassify.gateEnter(has, progress, duration);
        } else if (kind == RecipeProbeClassify.Kind.GATE_EXIT) {
            v = RecipeProbeClassify.gateExit(has, progress, duration);
        } else if (kind == RecipeProbeClassify.Kind.FINISH_ENTER) {
            v = RecipeProbeClassify.finishEnter(has, progress, duration);
        } else {
            v = RecipeProbeClassify.finishExit(has, progress, duration);
        }
        final MetaMachine machine = selfOf(logic);
        final long folded = admit(kind, keyOf(machine), v.cleared);
        if (folded < 0L) {
            return;
        }
        print(kind, "machine=" + pos(machine)
                + " match=" + recipeId(last)
                + " layout=" + parts(machine)
                + " impl=" + impl
                + " status=" + status(logic)
                + ' ' + v.tags, folded);
    }

    // ═════════════════════════════ 限流 + 汇总 ═════════════════════════════

    /**
     * 记账 + 限流判定（<b>不打日志</b>）。
     *
     * <p>🔴 为什么要与 {@link #print} 拆开：抓调用栈（{@code new Throwable().getStackTrace()}）、
     * 聚合仓室简表、反射读 {@code waitingReason} 这几件事都<b>不便宜</b>，
     * 而 {@code interruptRecipe} / {@code onWorking} 是每 tick 每台机器都会进的。
     * 所以顺序是「便宜的先算 → 过闸 → 只在真要打这一行时才组装正文」。
     *
     * @return {@code -1} = 这次不打（被这台机器的 2 秒窗口、或全局预算挡下）；
     *         {@code >=0} = 要打，值 = 自上次真打出来以后被折叠掉的条数（写进行尾 {@code folded=}）
     */
    private static long admit(RecipeProbeClassify.Kind kind, long key, boolean cleared) {
        final long now = System.currentTimeMillis();
        synchronized (LOCK) {
            final int k = kind.ordinal();
            COUNT[k]++;
            if (cleared) {
                totalCleared++;
            }
            bumpSummary(now);

            final Map<Long, long[]> map = THROTTLE[k];
            final long[] state = map.get(key);
            if (state != null && now - state[0] < THROTTLE_MS) {
                state[1]++;
                return -1L;
            }
            // 全局预算：超了只记账（并把它算进这台机器下一次那行的 folded）
            if (now - globalWindowStart >= 1_000L) {
                globalWindowStart = now;
                globalWindowLines = 0;
            }
            if (globalWindowLines >= GLOBAL_LINES_PER_SECOND) {
                if (state != null) {
                    state[1]++;
                }
                globalFolded++;
                return -1L;
            }
            globalWindowLines++;

            final long folded = state == null ? 0L : state[1];
            if (state == null) {
                if (map.size() > MAX_KEYS) {
                    map.clear();
                }
                map.put(key, new long[]{now, 0L});
            } else {
                state[0] = now;
                state[1] = 0L;
            }
            return folded;
        }
    }

    /** 真正落盘那一行；{@code folded} 永远在行尾（任务口径）。 */
    private static void print(RecipeProbeClassify.Kind kind, String body, long folded) {
        ShanhaiMod.LOGGER.info("{} {} {} folded={}", PREFIX, kind.lineName, body, folded);
    }

    private static void bumpSummary(long now) {
        bumpSummary(now, false);
    }

    private static void bumpSummary(long now, boolean force) {
        if (!force) {
            if (lastSummaryMs == 0L) {
                // 第一次进事件时只记起点：这样"启动后 60 秒内没有事件"不会先空打一行全 0
                lastSummaryMs = now;
                return;
            }
            if (now - lastSummaryMs < SUMMARY_MS) {
                return;
            }
        }
        lastSummaryMs = now;
        final long interrupts = COUNT[RecipeProbeClassify.Kind.INTERRUPT.ordinal()];
        final long damps = COUNT[RecipeProbeClassify.Kind.DAMP.ordinal()];
        final long onWorkingFalse = COUNT[RecipeProbeClassify.Kind.ONWORKING_FALSE.ordinal()];
        final long gates = COUNT[RecipeProbeClassify.Kind.GATE_ENTER.ordinal()]
                + COUNT[RecipeProbeClassify.Kind.GATE_EXIT.ordinal()]
                + COUNT[RecipeProbeClassify.Kind.FINISH_ENTER.ordinal()]
                + COUNT[RecipeProbeClassify.Kind.FINISH_EXIT.ordinal()];
        ShanhaiMod.LOGGER.info("{} summary interrupts={} damps={} onworkingFalse={} gates={}"
                        + " cleared={} foldedGlobal={} uptime_s={}",
                PREFIX, interrupts, damps, onWorkingFalse, gates,
                totalCleared, globalFolded, (now - STARTED_MS) / 1000L);
    }

    /**
     * 立刻打一行汇总（不重置累加计数）。
     *
     * <p>⚠️ 刻意<b>不</b>新挂 {@code ServerTickEvent}：探针的契约是「一个行为都不改」，
     * 而懒式汇总（每类事件进来时顺带判一次 60 秒）已经满足「每 60 秒一行」——
     * 没有事件进来时计数器本来也没动，少那一行不影响判读。
     * 需要"现在就出数"时由外部调本方法（目前只有 {@code main}/{@code flushSummary} 用）。
     */
    public static void flushSummary() {
        try {
            if (!enabled()) {
                return;
            }
            synchronized (LOCK) {
                bumpSummary(System.currentTimeMillis(), true);
            }
        } catch (Throwable ignored) {
        }
    }

    // ═════════════════════════════ 挂载自证 ═════════════════════════════

    /** 目标类（{@code Class.forName} 用字符串：gtladditions 缺席时本类仍然可加载）。 */
    private static final String C_RECIPE_LOGIC = "com.gregtechceu.gtceu.api.machine.trait.RecipeLogic";
    private static final String C_WORKABLE_MB = "com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine";
    private static final String C_MUTABLE = "com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic";
    private static final String C_GTLADD_MULTI = "com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic";

    /** 一条自证项：探针名 + 目标类 + 应当在目标类里出现的 handler 名字片段 + 至少几个。 */
    private static final class ProbeCheck {

        final String id;
        final String className;
        final String token;
        final int want;

        ProbeCheck(String id, String className, String token, int want) {
            this.id = id;
            this.className = className;
            this.token = token;
            this.want = want;
        }
    }

    /**
     * handler 名字片段。
     *
     * <p>🔴 Mixin 会把混入方法改名为 {@code handler$<随机串>$<原名>}（本工程 2026-09 已实测，
     * 见 {@code ModuleSetBlockWatch#verifyArmed} 的注释：栈里出现过
     * {@code LevelChunk.handler$zgh000$shanhai$watchSetBlockState}），
     * 所以判据是 <b>contains</b>（片段），不是 startsWith / equals。
     */
    private static final ProbeCheck[] CHECKS = {
            new ProbeCheck("P1", C_RECIPE_LOGIC, "probeInterrupt", 1),
            new ProbeCheck("P2", C_RECIPE_LOGIC, "probeDamp", 1),
            new ProbeCheck("P3", C_WORKABLE_MB, "probeOnWorking", 1),
            new ProbeCheck("P4a", C_MUTABLE, "probeGate", 2),
            new ProbeCheck("P5a", C_MUTABLE, "probeFinish", 2),
            new ProbeCheck("P4b", C_GTLADD_MULTI, "probeGate", 2),
            new ProbeCheck("P5b", C_GTLADD_MULTI, "probeFinish", 2),
    };

    private static final String[] BODY_CLASSES = {C_RECIPE_LOGIC, C_WORKABLE_MB, C_MUTABLE, C_GTLADD_MULTI};

    private static final String[] SHORT_NAMES = {"RecipeLogic", "WorkableMultiblockMachine",
            "MutableRecipesLogic", "GTLAddMultipleRecipesLogic"};

    /**
     * 「混入到底挂上了没有」的机器实证（日志可 grep {@code armed P1=}）。
     *
     * <p>为什么这条不可省：{@code require = 0} 让注入失败<b>完全静默</b>——
     * 「没数据」与「没挂上」在日志上长得一模一样。本行把两者分开：
     * <ul>
     *   <li>{@code P4a=false(found=1/want>=2) bodies=MutableRecipesLogic:39} ⇒ 类在、只挂了一半；</li>
     *   <li>{@code P4a=false(...) bodies=MutableRecipesLogic:CLASSNOTFOUND} ⇒ 类根本没加载（jar 缺席/版本变了）；</li>
     *   <li>{@code hits=…:0} 而 {@code bodies>0} ⇒ 类在、handler 一个都没有（注入全失败）。</li>
     * </ul>
     * 反射只用 {@code Class.forName(name, false, loader)}：<b>不初始化</b>目标类，
     * 不会把 GT 的静态初始化提前触发（那是「改了行为」）。
     */
    public static String armedLine() {
        final StringBuilder sb = new StringBuilder("armed ");
        final int[] classHits = new int[BODY_CLASSES.length];
        final int[] classTotal = new int[BODY_CLASSES.length];
        final boolean[] classFound = new boolean[BODY_CLASSES.length];
        for (int i = 0; i < BODY_CLASSES.length; i++) {
            final Class<?> c = load(BODY_CLASSES[i]);
            if (c == null) {
                continue;
            }
            classFound[i] = true;
            try {
                for (Method m : c.getDeclaredMethods()) {
                    classTotal[i]++;
                    if (m.getName().contains("shanhai$")) {
                        classHits[i]++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        boolean allTrue = true;
        for (ProbeCheck chk : CHECKS) {
            final int idx = indexOfClass(chk.className);
            final boolean foundClass = idx >= 0 && classFound[idx];
            final int found = foundClass ? countToken(chk.className, chk.token) : 0;
            final boolean ok = found >= chk.want;
            allTrue &= ok;
            sb.append(chk.id).append('=').append(ok)
                    .append("(found=").append(found).append("/want>=").append(chk.want)
                    .append(foundClass ? "" : ",CLASSNOTFOUND").append(") ");
        }
        final StringBuilder bodies = new StringBuilder("bodies=");
        final StringBuilder hits = new StringBuilder("hits=");
        for (int i = 0; i < BODY_CLASSES.length; i++) {
            if (i > 0) {
                bodies.append(',');
                hits.append(',');
            }
            bodies.append(SHORT_NAMES[i]).append(':')
                    .append(classFound[i] ? String.valueOf(classTotal[i]) : "CLASSNOTFOUND");
            hits.append(SHORT_NAMES[i]).append(':')
                    .append(classFound[i] ? String.valueOf(classHits[i]) : "CLASSNOTFOUND");
        }
        sb.append(bodies).append(' ').append(hits);
        if (!allTrue) {
            sb.append(" !! 有探针没挂上：上面 =false 的项【不代表现场没有该事件】，只代表本探针没在跑。");
        }
        return sb.toString();
    }

    /** 上面那条 {@code armed} 行是否全 true（给别处判读用；不参与任何业务判定）。 */
    public static boolean armedAll() {
        for (ProbeCheck chk : CHECKS) {
            if (countToken(chk.className, chk.token) < chk.want) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfClass(String className) {
        for (int i = 0; i < BODY_CLASSES.length; i++) {
            if (BODY_CLASSES[i].equals(className)) {
                return i;
            }
        }
        return -1;
    }

    /** @return 目标类；{@code null} = 没加载到（jar 缺席 / 类名变了），绝不抛。 */
    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, RecipeProbe.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int countToken(String className, String token) {
        final Class<?> c = load(className);
        if (c == null) {
            return 0;
        }
        try {
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().contains(token)) {
                    n++;
                }
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ═════════════════════════════ 现场取值 ═════════════════════════════

    private static MetaMachine selfOf(RecipeLogic logic) {
        try {
            final IRecipeLogicMachine m = logic.machine;
            return m == null ? null : m.self();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long keyOf(MetaMachine machine) {
        try {
            final long pos = machine.getPos().asLong();
            final ResourceLocation dim = machine.getLevel() == null
                    ? null : machine.getLevel().dimension().location();
            return pos ^ ((long) (dim == null ? 0 : dim.hashCode()) << 32);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String pos(MetaMachine machine) {
        try {
            final ResourceLocation dim = machine.getLevel() == null
                    ? null : machine.getLevel().dimension().location();
            return (dim == null ? "?" : dim.toString())
                    + '@' + machine.getPos().getX()
                    + ',' + machine.getPos().getY()
                    + ',' + machine.getPos().getZ();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static long gameTime(MetaMachine machine) {
        try {
            return machine.getLevel() == null ? -1L : machine.getLevel().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String status(RecipeLogic logic) {
        try {
            final RecipeLogic.Status s = logic.getStatus();
            return s == null ? "null" : s.name();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String recipeId(GTRecipe recipe) {
        try {
            if (recipe == null) {
                return "null";
            }
            return recipe.id == null ? "-" : recipe.id.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Field waitingReasonField;

    /** {@code RecipeLogic#waitingReason} 是 private 且没有 getter ⇒ 只能反射读（只读，不改）。 */
    private static String waitingReason(RecipeLogic logic) {
        try {
            Field f = waitingReasonField;
            if (f == null) {
                f = RecipeLogic.class.getDeclaredField("waitingReason");
                f.setAccessible(true);
                waitingReasonField = f;
            }
            final Object v = f.get(logic);
            if (v == null) {
                return "null";
            }
            if (v instanceof Component c) {
                final String s = c.getString();
                return s == null || s.isEmpty() ? "\"\"" : s;
            }
            return String.valueOf(v);
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 仓室简表（按类名聚合）：{@code [InputBusx3,EnergyHatchx2]}；最多 8 种，多的折成 {@code +Nmore}。
     *
     * <p>用途（P3/P4）：一眼看出"这台机器的仓室长什么样"，从而区分
     * 「无线电网输入失败」与「普通输入总线/电池缓冲」这两类机器。
     */
    private static String parts(MetaMachine machine) {
        try {
            if (!(machine instanceof IMultiController controller)) {
                return "-";
            }
            final List<IMultiPart> list = controller.getParts();
            if (list == null || list.isEmpty()) {
                return "[]";
            }
            final Map<String, Integer> agg = new LinkedHashMap<>();
            for (IMultiPart part : list) {
                String name;
                try {
                    final MetaMachine pm = part.self();
                    name = pm == null ? "?" : pm.getClass().getSimpleName();
                } catch (Throwable t) {
                    name = "?";
                }
                agg.merge(name, 1, Integer::sum);
            }
            final StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (Map.Entry<String, Integer> e : agg.entrySet()) {
                if (i >= 8) {
                    sb.append(",+").append(agg.size() - 8).append("more");
                    break;
                }
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('x').append(e.getValue());
                i++;
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 过滤后的调用栈：只留 gtceu / gtlcore / gtladditions / 我们自己的帧，最多 {@code max} 帧。
     *
     * <p>过滤规则（都是为了让"谁调的这一下"能一眼读出来）：
     * <ul>
     *   <li>丢掉探针自己的帧（{@code com.shanhai.common.diag.*} / {@code com.shanhai.mixin.probe.*}）；</li>
     *   <li>丢掉被注入的那个方法本身（{@code RecipeLogic#interruptRecipe}）；</li>
     *   <li>丢掉 Mixin 生成的 {@code handler$…} 帧；</li>
     *   <li>只留四个命名空间的帧。</li>
     * </ul>
     */
    private static String caller(int max, String selfMethod) {
        try {
            final StackTraceElement[] st = new Throwable().getStackTrace();
            final StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                final String cn = e.getClassName();
                final String mn = e.getMethodName();
                if (cn.startsWith("com.shanhai.common.diag.") || cn.startsWith("com.shanhai.mixin.probe.")) {
                    continue;
                }
                if (mn.contains("handler$") || mn.startsWith("shanhai$")) {
                    continue;
                }
                if (!interesting(cn)) {
                    continue;
                }
                if (selfMethod.equals(mn)) {
                    continue;
                }
                if (n > 0) {
                    sb.append('|');
                }
                sb.append(simple(cn)).append('#').append(mn);
                if (++n >= max) {
                    break;
                }
            }
            return n == 0 ? "-" : sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean interesting(String className) {
        return className.startsWith("com.gregtechceu.gtceu.")
                || className.startsWith("org.gtlcore.")
                || className.startsWith("com.gtladd.")
                || className.startsWith("com.shanhai.");
    }

    private static String simple(String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
