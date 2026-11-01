package com.shanhai.common.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 山海重构 · {@code /shanhai why} 的<b>判定核</b>（纯 JDK，零 Minecraft / GT / AE 依赖）。
 *
 * <h2>1. 这条命令是干什么的（用户现场原话）</h2>
 * <pre>
 *   用户那台：shanhai:primordial_causal_weaving_matrix @ useless_mod:uselessdim2 -88,130,83
 *     layout=[AdaptiveNetEnergyHatchPartMachine×2, MESuperPatternBufferPartMachine×1]
 *     setup_gate … match=null（长期）；[SHANHAI-LOOKUP] candidates 平均 0.0
 *     跑过的都是【别的测试配方】；目标配方 shanhai:primordial_causal_weaving/new_recipe_4
 *     （产物 shanhai:light_voyage）从未匹配
 * </pre>
 * ⇒ 玩家手上没有一条"我在现场问这台机：你到底为什么不动"的通道。本类就是那条通道的
 * <b>排版与判定</b>部分：输入一份"机器的纯数据快照"，输出 ①–⑥ 六段读数。
 *
 * <h2>2. 🔴 只读：本类<b>不认识任何机器</b>，也<b>不碰任何东西</b></h2>
 * 它只接收调用方（{@code ShanhaiWhyCommand}）<b>读出来</b>的数据，只算字符串。
 * 唯一"会去问机器"的入口是注入进来的 {@link RecipeProbe} 回调 —— 生产侧那一个调的是
 * {@code org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper#matchRecipe}（<b>SIMULATE</b>：
 * {@code javap -c} 实证 {@code matchRecipeInput} → {@code handleRecipe(IO.IN, holder, recipe.inputs,
 * Collections.emptyMap(), false, recipe, <b>true</b>)}，最后一个 {@code true} 就是 isSimulate，
 * 它只走 {@code matchRecipeInputV2/FluidV2} 那套"判够不够"，<b>不调 forgetLastInput / modifyRecipeInput</b>
 * ⇒ 一个物料都不扣）。
 *
 * <h2>3. 🔴 为什么"缺什么"要单独一条纪律（宁可缺，不可假）</h2>
 * {@code matchRecipe} 只回答<b>是/否</b>，它<b>不告诉</b>你缺哪一项。本类因此把缺料拆成两条独立结论：
 * <pre>
 *   需要 = 从 {@code GTRecipe.inputs} 直接读（一定读得到：每条 content 的 id 与数量）
 *   现有 = 只有【输入侧条目全部可解析】时才报数字；
 *          只要看到一个读不懂的条目（AEKey / GenericStack / 任何陌生类型）⇒ 本次全部写 unknown
 * </pre>
 * 理由：漏算 AE 网络里的库存就会把"有 64"报成"现有 0"——那是<b>假数字</b>，
 * 比 {@code unknown} 更害人（用户会照着一个错的结论去拆机器）。
 *
 * <h2>4. 聊天栏 vs 日志（用户规格）</h2>
 * <pre>
 *   全部行 &lt;= {@link #CHAT_MAX_LINES}  ⇒ 聊天栏发全部
 *   否则                                ⇒ 聊天栏只发【摘要】①③④⑥ ＋ 一行"完整清单见日志"
 *   日志                                ⇒ 永远发全部（调用方每行加 {@code [SHANHAI-WHY]} 前缀）
 * </pre>
 *
 * <h2>5. 自证（离线，纯 JDK：{@code temp\zhuguang-bug\why\Check.java}）</h2>
 * <pre>
 *   ① 样板数=0 的夹具            ⇒ 输出必须含 {@link #LINE_NOTHING} 那句
 *   ② 样板数=2、只有 1 条能匹配  ⇒ ⑤ 一条成功一条失败，失败那条点名缺的 id 与数量
 *   ③ 只读负对照                  ⇒ 注入的 matcher 一旦"真扣"，内容物指纹断言必须变红
 *   ④ 参数打错 / 坐标非法         ⇒ 返回 error 一句话，不抛
 *   ⑤ 样板带 raw=[…]              ⇒ ③ 那一行追加 {@code raw=…}；raw 为空时那一行<b>逐字节不变</b>
 * </pre>
 */
public final class ShanhaiWhyCore {

    /** 日志前缀（本命令所有日志行都以它以开头，便于 {@code findstr /C:} 一次抓全）。 */
    public static final String LOG_PREFIX = "[SHANHAI-WHY]";

    /** 命令名（挂在既有 {@code /shanhai} 这棵树上，<b>不新增命令根</b>）。 */
    public static final String COMMAND_WHY = "why";

    /**
     * 用户规格原话（逐字）：「若③的样板数=0 ⇒ 明确一行：
     * 「样板总成里一块样板都没有 ⇒ 不是配方问题，先把样板放进来」」。
     */
    public static final String LINE_NOTHING = "样板总成里一块样板都没有 ⇒ 不是配方问题，先把样板放进来";

    /** 样板清单最多列几条（用户规格：最多列 20 条）。 */
    public static final int PATTERN_LIST_CAP = 20;

    /**
     * 聊天栏放得下的行数上限；超过就只发摘要（①③④⑥）。
     *
     * <p>为什么取 20 而不是 10：一次典型诊断（1 个类型 + 2 条配方 + 1 块样板）大约 9–12 行，
     * 取 10 会把它降级成摘要、<b>把"缺什么"从聊天栏里弄没</b> —— 而那正是玩家要看的东西。
     * 20 之后仍然降级的情形 = 真的刷屏，那时"摘要 ＋ 一行指路日志"才对。
     */
    public static final int CHAT_MAX_LINES = 20;

    /** 一次最多对多少条配方跑 SIMULATE（防护：某些类型有上千条配方）。 */
    public static final int SIMULATE_CAP = 512;

    /** 读不到就叫它 {@code unknown} —— 本工程的统一口径，不许换成"未知"/"?"。 */
    public static final String UNKNOWN = "unknown";

    private ShanhaiWhyCore() {}

    // ══════════════════════════════════ 参数 ══════════════════════════════════

    /**
     * 命令参数：{@code 0 个}（自动找最近的机器）或 {@code 3 个}（x y z）。
     *
     * <p>为什么参数校验放在纯核里而不是只靠 Brigadier：控制台 / 命令方块 / 脚本拼串
     * 都可能绕过 tab 补全直接塞东西进来，而"参数打错"恰恰是自证第 ④ 条。
     */
    public static final class Args {

        /** 三个坐标；空数组 = 没给坐标（自动找）。 */
        public final int[] pos;

        /** {@code null} = 没问题；非 null = 一句中文错误（调用方直接回显）。 */
        public final String error;

        private Args(int[] pos, String error) {
            this.pos = pos;
            this.error = error;
        }

        public static Args auto() {
            return new Args(new int[0], null);
        }

        public static Args at(int x, int y, int z) {
            return new Args(new int[]{x, y, z}, null);
        }

        /** 有没有给坐标。 */
        public boolean hasPos() {
            return this.error == null && this.pos.length == 3;
        }

        /** 一行式回显（日志/聊天同一份）。 */
        public String describe() {
            if (this.error != null) {
                return "error:" + this.error;
            }
            return hasPos() ? ("pos=" + pos[0] + "," + pos[1] + "," + pos[2]) : "pos=auto";
        }

        /**
         * 解析裸参数（{@code 0} 个 / {@code 3} 个）。
         *
         * @param raw 可能为 {@code null}（= 一个参数都没给）
         * @return 永远非 null；非法输入 ⇒ {@link #error} 有值，<b>不抛异常</b>
         */
        public static Args parse(String[] raw) {
            if (raw == null || raw.length == 0) {
                return auto();
            }
            if (raw.length != 3) {
                return new Args(new int[0], "参数只能是 0 个（自动找最近的机器）或 3 个（x y z），"
                        + "实际收到 " + raw.length + " 个");
            }
            final int[] out = new int[3];
            for (int i = 0; i < 3; i++) {
                try {
                    out[i] = Integer.parseInt(String.valueOf(raw[i]).trim());
                } catch (Throwable t) {
                    return new Args(new int[0], "第 " + (i + 1) + " 个坐标不是整数：" + raw[i]);
                }
            }
            return new Args(out, null);
        }
    }

    // ══════════════════════════════════ 输入数据 ══════════════════════════════════

    /** 一处"挂着的样板总成"（按类名聚合）。 */
    public static final class Assembly {

        /** 类简名（{@code getClass().getSimpleName()}；空名字给 {@code "?"}）。 */
        public final String className;

        /** 这一类挂了几台（{@code MESuperPatternBufferPartMachine×1} 的那个 1）。 */
        public final int machines;

        /** 这几台加起来有多少块样板（读失败的那台按 0 计，同时置 {@link #unreadable}）。 */
        public final int patterns;

        /** 有几台的样板数没读出来（{@code >0} ⇒ ③ 那行的数字要打星号）。 */
        public final int unreadable;

        public Assembly(String className, int machines, int patterns, int unreadable) {
            this.className = className == null || className.isEmpty() ? "?" : className;
            this.machines = machines;
            this.patterns = patterns;
            this.unreadable = unreadable;
        }
    }

    /** 一块样板（只用于 ③ 的清单）。 */
    public static final class PatternView {

        /** 产物，形如 {@code shanhai:light_voyage × 1}（读不到就是 {@code ? }）。 */
        public final List<String> outputs;

        /** 该样板自己声明的配方类型；{@code null} = 读不出（画面上写 unknown）。 */
        public final String declaredType;

        /**
         * 该样板 NBT 里那个类型键的<b>原始</b>值，形如 {@code [gt_shanhai_recipe_type='原初因果编织']}。
         *
         * <p>🔴 这是<b>解析前</b>的原文，与 {@link #declaredType}（归一化后的结论）不是一回事 ——
         * 两者一对照就能看出「到底是没写、写的是中文名、还是写在别的键上」。
         * <p>{@code ""} = 这块样板没有任何跟类型有关的键（那一段就不追加，老输出逐字节不变）。
         */
        public final String rawDeclaration;

        public PatternView(List<String> outputs, String declaredType) {
            this(outputs, declaredType, "");
        }

        public PatternView(List<String> outputs, String declaredType, String rawDeclaration) {
            this.outputs = outputs == null ? List.of() : outputs;
            this.declaredType = declaredType;
            this.rawDeclaration = rawDeclaration == null ? "" : rawDeclaration;
        }
    }

    /** 一项"缺"的料。{@code need} / {@code have} 为 {@code null} = unknown（<b>不许填一个猜的数</b>）。 */
    public static final class Shortage {

        /** 显示名，例如 {@code shanhai:navigate_prism} 或 {@code EU}。 */
        public final String label;

        /** 需要多少；{@code null} = unknown。 */
        public final Long need;

        /** 现有多少；{@code null} = unknown。 */
        public final Long have;

        /** 单位后缀（空串 / {@code " mB"}）。 */
        public final String unit;

        /** 非 null ⇒ 本项不是"缺料"而是一句原样说明（例如"另有 3 项是 tag/多候选"）。 */
        public final String raw;

        public Shortage(String label, Long need, Long have, String unit) {
            this(label, need, have, unit, null);
        }

        private Shortage(String label, Long need, Long have, String unit, String raw) {
            this.label = label == null ? "?" : label;
            this.need = need;
            this.have = have;
            this.unit = unit == null ? "" : unit;
            this.raw = raw;
        }

        /** 一句原样说明（不参与"缺/不缺"的判定，也不会被当成数字读）。 */
        public static Shortage note(String text) {
            return new Shortage("?", null, null, "", text == null ? UNKNOWN : text);
        }

        /** 是不是"一句说明"。 */
        public boolean isNote() {
            return raw != null;
        }

        /** 一行式：{@code id × 需要 4 / 现有 0}；读不到的写 {@code unknown}。 */
        public String describe() {
            if (raw != null) {
                return raw;
            }
            return label + " × 需要 " + (need == null ? UNKNOWN : need + unit)
                    + " / 现有 " + (have == null ? UNKNOWN : have + unit);
        }

        /** 能不能确定"确实缺"（两项都读到了才叫确定）。 */
        public boolean certain() {
            return !isNote() && need != null && have != null && have < need;
        }
    }

    /**
     * 注入进来的<b>唯一</b>会碰机器的入口（生产侧 = {@code RecipeRunnerHelper.matchRecipe} 的 SIMULATE）。
     *
     * @param <R> 调用方的配方句柄（生产侧是 {@code GTRecipe}；离线夹具里是任意对象）
     */
    public interface RecipeProbe<R> {

        /** 这条配方现在能不能匹配（<b>必须只读</b>；本工程的唯一实现是 SIMULATE 模式）。 */
        boolean matches(R recipe);

        /**
         * 只在 {@link #matches} 为 {@code false} 时被调：逐项列出"缺什么"。
         *
         * <p>返回空表 = <b>给不出</b>（本类会写 {@code unknown}，绝不编）。
         */
        List<Shortage> shortages(R recipe);

        /** 日志用的附加读数（例如 {@code inputs=是 outputs=否}）；默认什么都没有。 */
        default String detail(R recipe) {
            return "";
        }
    }

    /** 一条目标配方：id ＋ 调用方的句柄。 */
    public static final class RecipeRef<R> {

        public final String id;
        public final R handle;

        public RecipeRef(String id, R handle) {
            this.id = id == null ? "(null_id)" : id;
            this.handle = handle;
        }
    }

    /** 一个目标配方类型下面【GT 索引里】的配方。 */
    public static final class Target<R> {

        /** 类型注册 id，例如 {@code gtceu:primordial_causal_weaving}。 */
        public final String typeId;

        /** 这个类型下的配方（可能已被 {@link #SIMULATE_CAP} 截断）。 */
        public final List<RecipeRef<R>> recipes;

        /** 截断前的总条数（= ④ 那个 N）。 */
        public final int totalInIndex;

        public Target(String typeId, List<RecipeRef<R>> recipes, int totalInIndex) {
            this.typeId = typeId;
            this.recipes = recipes == null ? List.of() : recipes;
            this.totalInIndex = Math.max(totalInIndex, this.recipes.size());
        }

        public boolean truncated() {
            return totalInIndex > recipes.size();
        }
    }

    /** 机器的一份<b>纯数据</b>快照（调用方读好之后交进来；本类只读它）。 */
    public static final class MachineView {

        /** 机器注册路径（{@code definition.getId()}），例如 {@code shanhai:primordial_causal_weaving_matrix}。 */
        public final String id;

        public final String dim;
        public final int x;
        public final int y;
        public final int z;

        /** 成型了没有。 */
        public final boolean formed;

        /** 配方逻辑是不是 WORKING（不是就是 IDLE）。 */
        public final boolean working;

        /** 手上那条配方的 id；{@code null} = 没有（画面上写"无"）。 */
        public final String runningRecipeId;

        /** 机器声明的配方类型（{@code getRecipeTypes()}，逐条 id）。 */
        public final List<String> declaredTypes;

        /** 挂着的样板总成（按类名聚合）。 */
        public final List<Assembly> assemblies;

        /** 样板清单（只用于 ③ 的清单；不是每块样板都要有）。 */
        public final List<PatternView> patterns;

        /** 机器激活的那个类型的 id（样板/机器都读不出类型时的兜底目标）；{@code null} = 读不出。 */
        public final String activeTypeId;

        /**
         * 必须在 ③ 之后、④ 之前<b>醒目</b>报出来的事实（例如"样板声明的类型和机器声明的类型没有交集"）。
         *
         * <p>为什么允许它存在：这类结论是<b>读出来的</b>（两边的类型表都拿到了），不是猜的；
         * 但它不属于 ①–⑥ 任何一段，硬塞进去会破坏那六段的形状。
         */
        public final List<String> warnings;

        public MachineView(String id, String dim, int x, int y, int z, boolean formed, boolean working,
                           String runningRecipeId, List<String> declaredTypes, List<Assembly> assemblies,
                           List<PatternView> patterns, String activeTypeId) {
            this(id, dim, x, y, z, formed, working, runningRecipeId, declaredTypes, assemblies, patterns,
                    activeTypeId, List.of());
        }

        public MachineView(String id, String dim, int x, int y, int z, boolean formed, boolean working,
                           String runningRecipeId, List<String> declaredTypes, List<Assembly> assemblies,
                           List<PatternView> patterns, String activeTypeId, List<String> warnings) {
            this.id = id == null || id.isEmpty() ? "?" : id;
            this.dim = dim == null || dim.isEmpty() ? "?" : dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.formed = formed;
            this.working = working;
            this.runningRecipeId = runningRecipeId;
            this.declaredTypes = declaredTypes == null ? List.of() : declaredTypes;
            this.assemblies = assemblies == null ? List.of() : assemblies;
            this.patterns = patterns == null ? List.of() : patterns;
            this.activeTypeId = activeTypeId;
            this.warnings = warnings == null ? List.of() : warnings;
        }

        /** 挂着的样板总成台数（读不到样板数的那台也算一台）。 */
        public int assemblyCount() {
            int n = 0;
            for (Assembly a : assemblies) {
                n += a.machines;
            }
            return n;
        }

        /** 样板总数（= ③ 那个 N）。 */
        public int patternCount() {
            int n = 0;
            for (Assembly a : assemblies) {
                n += a.patterns;
            }
            return n;
        }

        /** 有没有样板数没读出来。 */
        public boolean patternCountUnreadable() {
            for (Assembly a : assemblies) {
                if (a.unreadable > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    // ══════════════════════════════════ 输出 ══════════════════════════════════

    /** 一份报告：{@link #all} 是日志口径（全量），{@link #summary} 是聊天栏兜底口径。 */
    public static final class Report {

        /** 全部行（①→⑥ 的原始顺序；日志逐行打这一份）。 */
        public final List<String> all;

        /** 摘要（①③④⑥ ＋ 必要时那一行"见日志"）—— 聊天栏放不下时只发这一份。 */
        public final List<String> summary;

        /** 走的是哪一条回显路（{@code chat=all} / {@code chat=summary}）；只用于日志。 */
        public final String chatRoute;

        /** ③ 的样板数是不是 0（自证 ① 的判据）。 */
        public final boolean patternEmpty;

        /** 还没判定的条数（0 = 每条都判过）。 */
        public final int notEvaluated;

        Report(List<String> all, List<String> summary, String chatRoute, boolean patternEmpty,
               int notEvaluated) {
            this.all = List.copyOf(all);
            this.summary = List.copyOf(summary);
            this.chatRoute = chatRoute;
            this.patternEmpty = patternEmpty;
            this.notEvaluated = notEvaluated;
        }

        /** 聊天栏该发哪一份。 */
        public List<String> forChat() {
            return "chat=all".equals(chatRoute) ? all : summary;
        }
    }

    // ══════════════════════════════════ 主入口 ══════════════════════════════════

    /**
     * 把机器快照 ＋ 目标配方 ＋ 注入的只读判定器渲染成 ①–⑥。
     *
     * <p>只做字符串运算 ＋ 调 {@code probe}；<b>不抛异常</b>（单条配方判定抛了就地降级成一条
     * "判定本身抛异常"的读数，不连坐整份报告）。
     */
    public static <R> Report build(MachineView m, List<Target<R>> targets, RecipeProbe<R> probe) {
        if (m == null) {
            throw new IllegalArgumentException("MachineView 为 null");
        }
        if (probe == null) {
            throw new IllegalArgumentException("RecipeProbe 为 null（只读判定器必须注入）");
        }
        final List<String> all = new ArrayList<>();
        final List<String> summary = new ArrayList<>();
        final List<String> detail = new ArrayList<>();

        // ---------------- ① 机器本体 ----------------
        final String head = "① 机器=" + m.id
                + " pos=" + m.dim + "@" + m.x + "," + m.y + "," + m.z
                + " 成型=" + (m.formed ? "是" : "否")
                + " 状态=" + (m.working ? "WORKING" : "IDLE")
                + " 已有配方=" + (m.runningRecipeId == null || m.runningRecipeId.isEmpty()
                        ? "无" : m.runningRecipeId);
        all.add(head);
        summary.add(head);

        // ---------------- ② 机器声明的配方类型 ----------------
        final StringBuilder types = new StringBuilder();
        for (String t : m.declaredTypes) {
            if (types.length() > 0) {
                types.append("，");
            }
            types.append(t);
        }
        final String typesLine = "② 机器声明的配方类型=" + m.declaredTypes.size() + " 条："
                + (m.declaredTypes.isEmpty() ? "（一个都没声明）" : types.toString());
        all.add(typesLine);
        detail.add(typesLine);

        // ---------------- ③ 挂着的样板总成 ----------------
        final int patternCount = m.patternCount();
        final StringBuilder asm = new StringBuilder();
        for (Assembly a : m.assemblies) {
            if (asm.length() > 0) {
                asm.append("，");
            }
            asm.append(a.className).append('×').append(a.machines);
        }
        final String patternHead = "③ 挂着的样板总成："
                + (m.assemblies.isEmpty() ? "（一个都没找到）" : asm.toString())
                + "，样板数=" + patternCount
                + (m.patternCountUnreadable() ? "（有总成的样板数没读出来 ⇒ 这个数偏小，可能是 unknown）" : "");
        all.add(patternHead);
        summary.add(patternHead);
        if (!m.assemblies.isEmpty() && m.patterns.isEmpty() && patternCount > 0) {
            final String noList = "   · 样板清单读不出来（总成说有 " + patternCount
                    + " 块，但一块都拿不到详情）⇒ 清单=" + UNKNOWN;
            all.add(noList);
            detail.add(noList);
        }
        if (!m.patterns.isEmpty()) {
            final int cap = Math.min(m.patterns.size(), PATTERN_LIST_CAP);
            final String listHead = "   · 样板清单（最多列 " + PATTERN_LIST_CAP + " 条，本机 "
                    + m.patterns.size() + " 块，列前 " + cap + " 条）：";
            all.add(listHead);
            detail.add(listHead);
            for (int i = 0; i < cap; i++) {
                final PatternView p = m.patterns.get(i);
                final String outs = p.outputs.isEmpty() ? UNKNOWN : String.join(" + ", p.outputs);
                final String line = "     #" + (i + 1) + " 产物=" + outs
                        + " 声明类型=" + (p.declaredType == null || p.declaredType.isEmpty()
                                ? UNKNOWN : p.declaredType)
                        // 🆕 原始声明字符串（解析前）：与「声明类型」一对照就能看出是中文名还是别的键。
                        // 空串（样板里压根没有类型相关的键）⇒ 不追加，保证老输出逐字节不变。
                        + (p.rawDeclaration == null || p.rawDeclaration.isEmpty()
                                ? "" : " raw=" + p.rawDeclaration);
                all.add(line);
                detail.add(line);
            }
            if (m.patterns.size() > cap) {
                final String more = "     …另有 " + (m.patterns.size() - cap) + " 块未列（上限 "
                        + PATTERN_LIST_CAP + " 条）";
                all.add(more);
                detail.add(more);
            }
        }

        // ---------------- ④ 目标类型下【GT 索引里】有多少条 ----------------
        for (String w : m.warnings) {
            if (w == null || w.isEmpty()) {
                continue;
            }
            all.add(w);
            summary.add(w);
        }
        final List<Target<R>> safeTargets = targets == null ? List.of() : targets;
        if (safeTargets.isEmpty()) {
            final String none = "④ 目标类型=unknown（机器没声明配方类型）⇒ 这个类型下有多少条配方：unknown";
            all.add(none);
            summary.add(none);
        } else {
            for (Target<R> t : safeTargets) {
                final String line = "④ 目标类型 " + t.typeId + " 下【GT 索引里】有 " + t.totalInIndex + " 条配方"
                        + (t.truncated() ? "（超过判定上限 " + SIMULATE_CAP + "，只判前 "
                        + t.recipes.size() + " 条）" : "");
                all.add(line);
                summary.add(line);
            }
        }

        // ---------------- ⑤ 逐条判定（只读 SIMULATE） ----------------
        int notEvaluated = 0;
        if (!safeTargets.isEmpty()) {
            final String evalHead = "⑤ 逐条判定（复用 RecipeRunnerHelper.matchRecipe · SIMULATE 只读；"
                    + "它只判 recipe.inputs / recipe.outputs，【不含】tickInputs 里的 EU）";
            all.add(evalHead);
            detail.add(evalHead);
            for (Target<R> t : safeTargets) {
                if (t.recipes.isEmpty()) {
                    final String empty = "   · " + t.typeId + " 这个类型下一条配方都没有 ⇒ ⑤ 无从判起";
                    all.add(empty);
                    detail.add(empty);
                    continue;
                }
                for (RecipeRef<R> ref : t.recipes) {
                    boolean ok;
                    String why = "";
                    try {
                        ok = probe.matches(ref.handle);
                    } catch (Throwable ex) {
                        ok = false;
                        why = "（判定本身抛异常：" + oneLine(ex) + " ⇒ 结论=" + UNKNOWN + "）";
                    }
                    if (ok) {
                        final String line = "   · 匹配成功：" + ref.id;
                        all.add(line);
                        detail.add(line);
                        continue;
                    }
                    final List<Shortage> shorts;
                    try {
                        shorts = probe.shortages(ref.handle);
                    } catch (Throwable ex) {
                        final String line = "   · 匹配失败：" + ref.id + " 缺：" + UNKNOWN
                                + "（读缺料时抛异常：" + oneLine(ex) + "）";
                        all.add(line);
                        detail.add(line);
                        continue;
                    }
                    final String line = "   · 匹配失败：" + ref.id + " 缺：" + shortagesText(shorts) + why;
                    all.add(line);
                    detail.add(line);
                    final String extra = detailOf(probe, ref.handle);
                    if (!extra.isEmpty()) {
                        final String dbg = "       · 引擎旁证：" + extra;
                        all.add(dbg);
                        detail.add(dbg);
                    }
                }
            }
        }

        // ---------------- ⑥ 空样板那一条红线 ----------------
        final boolean patternEmpty = patternCount == 0;
        if (patternEmpty) {
            if (m.assemblies.isEmpty()) {
                final String noAsm = "⑥ 这个结构里没有找到任何样板总成（ICraftingProvider 仓室）⇒ 先挂一个样板总成";
                all.add(noAsm);
                summary.add(noAsm);
            }
            all.add("⑥ " + LINE_NOTHING);
            summary.add("⑥ " + LINE_NOTHING);
        }

        // ---------------- 聊天栏要不要降级成摘要 ----------------
        final String route;
        if (all.size() <= CHAT_MAX_LINES) {
            route = "chat=all";
        } else {
            route = "chat=summary";
            summary.add("（聊天栏放不下 " + all.size() + " 行，完整清单见日志 " + LOG_PREFIX + " ；"
                    + "机器日志里搜这一串就能抓全）");
        }
        return new Report(all, summary, route, patternEmpty, notEvaluated);
    }

    // ══════════════════════════════════ 小工具（纯函数） ══════════════════════════════════

    /** "缺什么"那一段的排版；空表 ⇒ {@code unknown}（<b>不编</b>）。 */
    public static String shortagesText(List<Shortage> shorts) {
        if (shorts == null || shorts.isEmpty()) {
            return UNKNOWN + "（逐项读不出缺哪一样）";
        }
        final StringBuilder sb = new StringBuilder();
        boolean anyUnknown = false;
        for (Shortage s : shorts) {
            if (s == null) {
                continue;
            }
            if (!s.isNote() && !s.certain()) {
                anyUnknown = true;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(s.describe());
        }
        if (sb.length() == 0) {
            return UNKNOWN + "（逐项读不出缺哪一样）";
        }
        if (anyUnknown) {
            sb.append("（标 unknown 的项没读到数，不敢填）");
        }
        return sb.toString();
    }

    /** 调一次 {@link RecipeProbe#detail}，抛了就当没有（绝不连坐整份报告）。 */
    private static <R> String detailOf(RecipeProbe<R> probe, R recipe) {
        try {
            final String s = probe.detail(recipe);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "unknown（读引擎旁证时抛异常：" + oneLine(t) + "）";
        }
    }

    /** 异常 → 一句话（不换行、不超长）：聊天栏与日志共用。 */
    public static String oneLine(Throwable t) {
        if (t == null) {
            return UNKNOWN;
        }
        String msg;
        try {
            msg = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        } catch (Throwable ignored) {
            msg = t.getClass().getName();
        }
        msg = msg.replace('\r', ' ').replace('\n', ' ').trim();
        if (msg.length() > 160) {
            msg = msg.substring(0, 160) + "…";
        }
        return msg;
    }

    /** 出错时聊天栏那一行（用户规格：报"诊断失败：<一句话>"，不许崩服）。 */
    public static String failLine(Throwable t) {
        return "诊断失败：" + oneLine(t);
    }

    /** 出错时聊天栏那一行（只要一句话的场合）。 */
    public static String failLine(String why) {
        return "诊断失败：" + (why == null || why.isEmpty() ? UNKNOWN : why);
    }

    /**
     * 机器内容物指纹：{@code 条目=数量} 表 → 一个稳定的字符串。
     *
     * <p>用途只有一个（自证第 ③ 条）：命令跑之前取一次、跑完再取一次，<b>两次必须逐字节相同</b>
     * —— 那才叫"一个物料都没动"。缺任何一项、多任何一项、数量差 1 都算变。
     */
    public static String fingerprint(Map<String, Long> contents) {
        final TreeMap<String, Long> sorted = new TreeMap<>();
        if (contents != null) {
            for (Map.Entry<String, Long> e : contents.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                sorted.merge(e.getKey(), e.getValue() == null ? 0L : e.getValue(), Long::sum);
            }
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("n=").append(sorted.size()).append('{');
        boolean first = true;
        for (Map.Entry<String, Long> e : sorted.entrySet()) {
            if (e.getValue() == 0L) {
                continue;                      // 0 条目不进指纹（与"没有这一项"等价）
            }
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    // ══════════════════════════════════ 缺料排版的夹具工具（离线自证用） ══════════════════════════════════

    /** 便捷构造：确定缺。 */
    public static Shortage shortage(String label, long need, long have) {
        return new Shortage(label, Long.valueOf(need), Long.valueOf(have), "");
    }

    /** 便捷构造：数量读不出（两项都 unknown）。 */
    public static Shortage unknownShortage(String label) {
        return new Shortage(label, null, null, "");
    }

    /** 便捷构造：只需要（现有读不出）。 */
    public static Shortage needOnly(String label, long need) {
        return new Shortage(label, Long.valueOf(need), null, "");
    }

    /** 便捷构造：流体（带 mB 后缀）。 */
    public static Shortage fluidShortage(String label, long need, Long have) {
        return new Shortage(label, Long.valueOf(need), have, " mB");
    }

    /** 一份只读空表（免得调用方每次 {@code new}）。 */
    public static <K, V> Map<K, V> emptyMap() {
        return new LinkedHashMap<>();
    }
}
