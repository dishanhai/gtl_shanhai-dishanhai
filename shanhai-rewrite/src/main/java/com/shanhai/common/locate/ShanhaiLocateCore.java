package com.shanhai.common.locate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 山海重构 · 「合成状态 / 合成CPU 屏里 Shift+右键点物品 ⇒ 我们的样板总成在哪」的
 * <b>纯逻辑核</b>：本文件<b>一个 MC / AE2 的类都不 import</b>（只有 {@code java.util}），
 * 于是"从样板清单里找某个键的所有者"这件事<b>可以在没有游戏的机器上断言</b>
 * （离线自证见 {@code temp\locate-pattern\selftest\}）。
 *
 * <h2>1. 为什么这件事必须抽成纯逻辑核</h2>
 * 判据有三条互不相干、但都会在游戏里"静默失败"（点了没反应 / 反应错地方）的规则：
 * <pre>
 *   ① 只认<b>产出</b>：某台机器的样板把目标键当<b>输入</b>用，不算它"有这个物品的样板"；
 *   ② 概率副产也算：GT 配方里 {@code Content.chance < maxChance} 的那一份，
 *      在样板里是<b>照写进产出</b>的（证据：{@code ShanhaiWildcardRecipeBinding:57-58} 原文
 *      「概率产出（{@code Content.chance < maxChance}）一律<b>计入</b>输出集合：样板是编码时把
 *      {@code getIOStacks} 全写进去的，漏掉它会让我们把"正确配方"判成不匹配」）
 *      ⇒ 所以只读 {@code IPatternDetails#getOutputs()}，<b>一次都不许读 getInputs()</b>；
 *   ③ 0 命中必须<b>静默</b>：不但不许发消息，连高亮包也不许发
 *      —— 否则玩家会看到"一堆东西闪了一下但没告诉我在哪"，比没反应更糟。
 * 三条都能在纯逻辑里判，于是它们不该活在"要开游戏才知道对不对"的代码里。
 *
 * <h2>2. 与服务端/客户端的分工（硬约束：客户端不许猜）</h2>
 * <pre>
 *   客户端：只做两件事 —— 把"我指着这个键"发出去；收到坐标后画 6 秒线框。
 *   服务端：拿 {@link com.shanhai.common.locate.ShanhaiLocateRegistry} 里那批<b>已加载的</b>
 *           自家 part，逐个问 {@code getAvailablePatterns()}（只读），再交给本类判。
 * </pre>
 * 本类<b>不碰网络、不碰世界、不碰日志</b>：它只回答"哪几台有、坐标是什么、该做哪几件事"。
 *
 * <h2>3. 线程</h2>
 * 本类所有方法都是<b>纯函数 / 无状态</b>（{@link Candidate}/{@link Hit}/{@link Result} 是不可变快照）
 * ⇒ 谁在哪条线程上调都安全。真正的线程约束在注册表与网络层（见那两处注释）。
 */
public final class ShanhaiLocateCore {

    /** 机器种类：星律样板总成（{@code StellarSampleAssemblerPartMachine}）。 */
    public static final int KIND_STELLAR = 1;

    /** 机器种类：超级通配符样板总成（{@code SuperWildcardPatternBufferPartMachine}）。 */
    public static final int KIND_SUPER_WILDCARD = 2;

    /**
     * 机器种类：<b>不是我们注册的那两类</b>的样板总成 —— 搜索时<b>被动发现</b>到的第三方仓室。
     *
     * <p>2026-11-01 第二轮新增。用户实机日志（{@code [SHANHAI-RLPROBE] setup_gate … layout=…}）：
     * <pre>
     *   machine=useless_mod:uselessdim2@-88,130,83 layout=[AdaptiveNetEnergyHatchPartMachinex2, MESuperPatternBufferPartMachinex1]
     *   machine=useless_mod:uselessdim2@-88,131,82 providers=[StellarSampleAssemblerPartMachine=3, MESuperPatternBufferPartMachine=0]
     * </pre>
     * ⇒ 用户的样板装在 <b>gtladditions 的 {@code MESuperPatternBufferPartMachine}</b> 里
     * （{@code javap}：{@code extends org.gtlcore...MEPatternBufferPartMachine}
     *    {@code ← MEPatternBufferPartMachineBase implements appeng.api.networking.crafting.ICraftingProvider}），
     * 而本定位第一版只登记我们自己的两台 ⇒ 这些样板<b>永远搜不到</b>（{@code [SHANHAI-LOCATE] none}）。
     */
    public static final int KIND_UPSTREAM_PATTERN_BUFFER = 3;

    /** {@link #kindFor} 的"这台不算候选"。 */
    public static final int KIND_NONE = -1;

    /**
     * 最多高亮几台（用户规格：「多台就都高亮，最多 8 台」）。
     * <p>它与"共 N 台"是<b>两件事</b>：{@link Result#totalHits()} 是真实命中数（可以 &gt; 8），
     * {@link Result#highlight()} 才是发给客户端画框的那一段（≤ 8）。
     */
    public static final int MAX_HIGHLIGHT = 8;

    /** {@link #plan(int)} 的返回值：<b>什么都不做</b>（不发消息、不发包、不画）。 */
    public static final int ACTION_NONE = 0;

    /** {@link #plan(int)} 的返回值：聊天栏报一行。 */
    public static final int ACTION_REPORT = 1;

    /** {@link #plan(int)} 的返回值：给客户端发高亮坐标。 */
    public static final int ACTION_HIGHLIGHT = 2;

    // ---------------------------------------------------------------- 文案键（显示给玩家的字全在 lang 里）

    /** {@code [山海定位] 找到了：%s 在 %s，维度 %s（离你 %s 格）} */
    public static final String LANG_FOUND = "shanhai.locate.found";

    /** {@code ；另外还有 %s 台，已一并高亮} */
    public static final String LANG_MORE = "shanhai.locate.more";

    /** {@code ；另外还有 %s 台（只高亮前 %s 台）} */
    public static final String LANG_MORE_CAPPED = "shanhai.locate.more_capped";

    /** {@code 星律样板总成} */
    public static final String LANG_MACHINE_STELLAR = "shanhai.locate.machine.stellar";

    /** {@code 超级通配符样板总成} */
    public static final String LANG_MACHINE_SUPER_WILDCARD = "shanhai.locate.machine.super_wildcard";

    /** {@code 超级样板总成}（被动发现的第三方仓室，例如 gtladditions 那台）。 */
    public static final String LANG_MACHINE_UPSTREAM = "shanhai.locate.machine.upstream_pattern_buffer";

    private ShanhaiLocateCore() {}

    // ================================================================ 快照类型

    /**
     * <b>一台候选机器的只读快照</b>（服务端现算一次，之后本类只读它）。
     *
     * @param <K> 键的类型。生产环境是 {@code appeng.api.stacks.AEKey}；离线断言用 {@code String}
     *            —— 泛型擦除后本文件对 AE2 零依赖，这就是它能离线跑的原因。
     */
    public static final class Candidate<K> {

        /** {@link #KIND_STELLAR} / {@link #KIND_SUPER_WILDCARD}。 */
        public final int kind;

        /** 维度 id（{@code minecraft:overworld} 这种字符串；不引 {@code ResourceLocation} 是为了保持零依赖）。 */
        public final String dim;

        /** 方块坐标。 */
        public final int x;
        public final int y;
        public final int z;

        /** 这台机器<b>所有样板的全部产出键</b>（输入不在里面 —— 由调用方保证，见类注释 ①）。 */
        public final List<K> produced;

        /**
         * 🔴 <b>只读探针</b>：生产代码<b>一次都不许</b>给它加一。
         *
         * <p>它存在的唯一理由是让"只读"这条断言<b>有一个能翻红的观测点</b>：
         * 离线自证里有一个负对照，把 {@link #find} 改成"顺手调一次 {@link #pushPatternProbe()}"
         * ⇒ 断言 {@code pushPatternCalls == 0} 必须变红（见 {@code selftest\LocateAssertions.java}）。
         * 没有这个字段，"只读"就只能靠肉眼读代码。
         */
        public int pushPatternCalls;

        public Candidate(int kind, String dim, int x, int y, int z, List<K> produced) {
            this.kind = kind;
            this.dim = dim == null ? "" : dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.produced = produced == null ? Collections.emptyList() : produced;
        }

        /** 便捷构造（离线断言与生产侧同一入口，免得两处各写一套参数顺序）。 */
        public static <K> Candidate<K> of(int kind, String dim, int x, int y, int z, List<K> produced) {
            return new Candidate<>(kind, dim, x, y, z, produced);
        }

        /** 这台机器有没有"目标键"的样板：<b>只看产出</b>。 */
        public boolean produces(K wanted) {
            if (wanted == null) {
                return false;
            }
            for (K key : this.produced) {
                if (key != null && key.equals(wanted)) {
                    return true;
                }
            }
            return false;
        }

        /** 见 {@link #pushPatternCalls}：<b>仅供离线负对照</b>，生产代码不许调。 */
        public void pushPatternProbe() {
            this.pushPatternCalls++;
        }
    }

    /** 一处命中（服务端算、客户端画、聊天栏报的都是它）。 */
    public static final class Hit {

        public final int kind;
        public final String dim;
        public final int x;
        public final int y;
        public final int z;

        public Hit(int kind, String dim, int x, int y, int z) {
            this.kind = kind;
            this.dim = dim == null ? "" : dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return "kind=" + kind + " dim=" + dim + " pos=" + x + "," + y + "," + z;
        }
    }

    /** {@link #find} 的结果：<b>全部命中</b> + <b>要画的那一段</b>（≤ {@link #MAX_HIGHLIGHT}）。 */
    public static final class Result {

        private final List<Hit> hits;
        private final List<Hit> highlight;
        private final int cap;

        Result(List<Hit> hits, int cap) {
            this.cap = cap;
            this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
            final int shown = Math.min(cap, this.hits.size());
            this.highlight = Collections.unmodifiableList(new ArrayList<>(this.hits.subList(0, shown)));
        }

        /** 全部命中（按调用方给的候选顺序；服务端会先按"离玩家多远"排过）。 */
        public List<Hit> hits() {
            return this.hits;
        }

        /** 发给客户端画的这一段（前 {@code cap} 台）。 */
        public List<Hit> highlight() {
            return this.highlight;
        }

        /** 真实命中台数（可能就是聊天栏那个"N 台"）。 */
        public int totalHits() {
            return this.hits.size();
        }

        /** 有没有超过上限（超过 ⇒ 聊天栏要补"只高亮前 N 台"）。 */
        public boolean truncated() {
            return this.hits.size() > this.cap;
        }

        /** 0 命中 ⇒ 调用方必须什么都不做。 */
        public boolean isEmpty() {
            return this.hits.isEmpty();
        }
    }

    // ================================================================ 核心：找所有者

    /**
     * 从一批候选里挑出"有目标键样板"的那些。
     *
     * @param candidates 候选快照（服务端按"离玩家多远"排好再传进来，于是结果第一位 = 最近那台）
     * @param wanted     目标键；{@code null} ⇒ 直接返回空结果（不许 NPE，也不许"全命中"）
     * @param maxHighlight 最多高亮几台（内部夹到 {@code 1..MAX_HIGHLIGHT}）
     * @return 不可变结果；0 命中时 {@link Result#isEmpty()} 为 true
     */
    public static <K> Result find(List<Candidate<K>> candidates, K wanted, int maxHighlight) {
        final List<Hit> hits = new ArrayList<>();
        final int cap = Math.max(1, Math.min(maxHighlight, MAX_HIGHLIGHT));
        if (candidates != null && wanted != null) {
            for (Candidate<K> candidate : candidates) {
                if (candidate == null || !candidate.produces(wanted)) {
                    continue;
                }
                hits.add(new Hit(candidate.kind, candidate.dim, candidate.x, candidate.y, candidate.z));
            }
        }
        return new Result(hits, cap);
    }

    /**
     * <b>0 命中 ⇒ {@link #ACTION_NONE}</b>：调用方一个字都不许发。
     *
     * <p>抽成一个函数（而不是在调用点写 {@code if (hits > 0)}）的理由和 {@link Candidate#pushPatternCalls}
     * 一样：它让"0 命中要静默"这条规则<b>可以被断言</b>
     * （离线负对照：把这里改成 {@code hitCount == 0} 也返回 {@link #ACTION_REPORT} ⇒ 断言必须红）。
     */
    public static int plan(int hitCount) {
        if (hitCount <= 0) {
            return ACTION_NONE;
        }
        return ACTION_REPORT | ACTION_HIGHLIGHT;
    }

    /** 机器种类 ⇒ 显示名 lang 键（未知种类给星律那句，绝不硬编码中文）。 */
    public static String kindLangKey(int kind) {
        if (kind == KIND_SUPER_WILDCARD) {
            return LANG_MACHINE_SUPER_WILDCARD;
        }
        if (kind == KIND_UPSTREAM_PATTERN_BUFFER) {
            return LANG_MACHINE_UPSTREAM;
        }
        return LANG_MACHINE_STELLAR;
    }

    /**
     * <b>一台候选该算哪一类</b>（唯一一份，纯函数）—— 这是"把第三方样板总成也纳入"的落点。
     *
     * <pre>
     *   不是 ICraftingProvider            ⇒ KIND_NONE（它连样板都没有，问它 = 白问）
     *   注册表里的（我们自己 onLoad 报名） ⇒ 它报名时给的那个 kind（1/2/…）
     *   搜索时被动发现的                  ⇒ KIND_UPSTREAM_PATTERN_BUFFER
     * </pre>
     *
     * <p>🔴 为什么"不是 ICraftingProvider 就丢"要写在这里而不是调用点：
     * 被动发现会把<b>结构里的每一个仓室</b>都送进来（电池仓、输入仓…），
     * 如果这里不判，就会对一堆非样板仓调 {@code getAvailablePatterns()}（那是别人的方法，
     * 后果不可预期）⇒ 这条判据必须有断言（见 {@link #selfTest()} ⑥）。
     *
     * @param fromRegistry    这台是不是注册表里的（{@code ShanhaiLocateRegistry}）
     * @param registryKind    注册表里的种类（{@code fromRegistry == false} 时忽略）
     * @param craftingProvider 这台是不是 {@code appeng.api.networking.crafting.ICraftingProvider}
     */
    public static int kindFor(boolean fromRegistry, int registryKind, boolean craftingProvider) {
        if (!craftingProvider) {
            return KIND_NONE;
        }
        return fromRegistry ? registryKind : KIND_UPSTREAM_PATTERN_BUFFER;
    }

    /**
     * <b>候选总表的唯一一份拼法</b>：先放注册表里的，再放被动发现的（同坐标只留先来的那台）。
     *
     * <p>为什么"同坐标只留先来的"很重要：一台机器既可能在注册表里（我们自己的 part），
     * 又会被被动发现（从结构/网格回收到它自己）⇒ 不去重就会在聊天栏里报"2 台"而世界只高亮一处。
     * 先来的（注册表）优先，因为它带着<b>准确的</b>种类（星律 / 超级通配符），
     * 而被动发现只能给"上游样板总成"这个笼统的名字。
     *
     * @param fromRegistry 注册表快照转出来的候选（可以为 {@code null}）
     * @param discovered   被动发现转出来的候选（可以为 {@code null}）
     */
    public static <K> List<Candidate<K>> assemble(List<Candidate<K>> fromRegistry, List<Candidate<K>> discovered) {
        final List<Candidate<K>> out = new ArrayList<>();
        addAll(out, fromRegistry);
        addAll(out, discovered);
        return out;
    }

    private static <K> void addAll(List<Candidate<K>> out, List<Candidate<K>> in) {
        if (in == null) {
            return;
        }
        for (Candidate<K> c : in) {
            if (c == null || indexOf(out, c) >= 0) {
                continue;
            }
            out.add(c);
        }
    }

    /** 同坐标（维度 + x/y/z）在不在表里。 */
    private static <K> int indexOf(List<Candidate<K>> out, Candidate<K> c) {
        for (int i = 0; i < out.size(); i++) {
            final Candidate<K> o = out.get(i);
            if (o.x == c.x && o.y == c.y && o.z == c.z && o.dim.equals(c.dim)) {
                return i;
            }
        }
        return -1;
    }

    /** 已经做出来的动作位（{@code plan} 的位测试小工具，日志用）。 */
    public static boolean has(int plan, int action) {
        return (plan & action) != 0;
    }

    // ================================================================ 开机自检（纯算术）

    /**
     * 判据 4 条，其中 <b>2 条自带口径对照/负对照</b>：
     * <pre>
     *   ① 3 台候选、只有 2 台有该键 ⇒ 命中 2 台，且<b>坐标逐位对得上</b>（顺序 = 传进来的顺序）
     *   ② 【口径】概率副产算命中；只作输入的<b>不算</b>（往 produced 里塞"输入"必须判不中）
     *   ③ 【负对照】0 命中 ⇒ plan = ACTION_NONE 且 Result.isEmpty()
     *   ④ 12 台命中、上限 8 ⇒ totalHits=12 / highlight=8 / truncated=true
     *      —— 这条防"把 8 当成了总台数"（聊天栏那个"N 台"会跟着错）
     * </pre>
     * 返回一行机器可判读数（{@code PASS=true/false}）。离线自证调用的是<b>同一个方法</b>，
     * 不是另写一份等价断言（本工程在"检查器自己错了"上付过账）。
     */
    public static String selfTest() {
        final int total = 6;
        int pass = 0;
        final StringBuilder bad = new StringBuilder();

        // ① 3 台里 2 台有
        try {
            final List<Candidate<String>> parts = new ArrayList<>();
            parts.add(Candidate.of(KIND_STELLAR, "minecraft:overworld", 10, 64, -3,
                    List.of("minecraft:iron_ingot", "shanhai:stellar_only")));
            parts.add(Candidate.of(KIND_SUPER_WILDCARD, "minecraft:overworld", 20, 70, 5,
                    List.of("minecraft:copper_ingot")));
            parts.add(Candidate.of(KIND_STELLAR, "minecraft:overworld", -7, 12, 8,
                    List.of("shanhai:chance_byproduct", "minecraft:iron_ingot")));
            final Result r = find(parts, "minecraft:iron_ingot", MAX_HIGHLIGHT);
            final boolean ok = r.totalHits() == 2 && r.highlight().size() == 2
                    && r.hits().get(0).x == 10 && r.hits().get(0).y == 64 && r.hits().get(0).z == -3
                    && r.hits().get(1).x == -7 && r.hits().get(1).y == 12 && r.hits().get(1).z == 8
                    && r.hits().get(0).dim.equals("minecraft:overworld");
            if (ok) {
                pass++;
            } else {
                bad.append(" ①3台里2台有却算出 hits=").append(r.totalHits()).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ①threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ② 概率副产算命中 / 只作输入不算
        try {
            final List<Candidate<String>> parts = new ArrayList<>();
            // 这台只有"概率副产"（编码时它就是被照写进 getOutputs() 的，见类注释②）
            parts.add(Candidate.of(KIND_STELLAR, "minecraft:overworld", 0, 0, 0,
                    List.of("shanhai:chance_byproduct")));
            // 这台把目标键当【输入】用 ⇒ produced 里没有它 ⇒ 必须判不中
            parts.add(Candidate.of(KIND_STELLAR, "minecraft:overworld", 1, 1, 1,
                    List.of("minecraft:iron_ingot")));
            final Result chance = find(parts, "shanhai:chance_byproduct", MAX_HIGHLIGHT);
            final Result inputOnly = find(parts, "minecraft:iron_ingot", MAX_HIGHLIGHT);
            final boolean ok = chance.totalHits() == 1 && chance.hits().get(0).x == 0
                    && inputOnly.totalHits() == 1 && inputOnly.hits().get(0).x == 1;
            if (ok) {
                pass++;
            } else {
                bad.append(" ②chance=").append(chance.totalHits())
                        .append(" inputOnly=").append(inputOnly.totalHits()).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ②threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ③ 负对照：0 命中必须静默
        try {
            final List<Candidate<String>> parts = List.of(
                    Candidate.of(KIND_STELLAR, "minecraft:overworld", 3, 3, 3, List.of("a:b")),
                    Candidate.of(KIND_SUPER_WILDCARD, "minecraft:overworld", 4, 4, 4, List.of("c:d")));
            final Result none = find(parts, "nobody:has_it", MAX_HIGHLIGHT);
            final boolean ok = none.isEmpty() && none.totalHits() == 0
                    && plan(none.totalHits()) == ACTION_NONE
                    && !has(plan(none.totalHits()), ACTION_REPORT)
                    && !has(plan(none.totalHits()), ACTION_HIGHLIGHT);
            if (ok) {
                pass++;
            } else {
                bad.append(" ③0命中却 plan=").append(plan(none.totalHits())).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ③threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ④ 12 台命中、上限 8 ⇒ 总台数与高亮台数必须分开
        try {
            final List<Candidate<String>> many = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                many.add(Candidate.of(KIND_STELLAR, "minecraft:overworld", i, 0, 0, List.of("x:y")));
            }
            final Result r = find(many, "x:y", MAX_HIGHLIGHT);
            final boolean ok = r.totalHits() == 12 && r.highlight().size() == MAX_HIGHLIGHT
                    && r.truncated() && has(plan(r.totalHits()), ACTION_HIGHLIGHT);
            if (ok) {
                pass++;
            } else {
                bad.append(" ④12台算出 total=").append(r.totalHits())
                        .append(" highlight=").append(r.highlight().size()).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ④threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ⑤ 我方零件 + 上游（gtlcore/gtladditions）零件各一台、都有该物品样板 ⇒ 两处坐标都报出
        //    —— 这一条就是"用户的样板装在 MESuperPatternBufferPartMachine 里也找得到"的判据。
        try {
            final Candidate<String> ours = Candidate.of(KIND_STELLAR, "useless_mod:uselessdim2", -88, 131, 82,
                    List.of("shanhai:light_voyage", "minecraft:iron_ingot"));
            final Candidate<String> upstream = Candidate.of(KIND_UPSTREAM_PATTERN_BUFFER,
                    "useless_mod:uselessdim2", -88, 130, 83, List.of("shanhai:light_voyage"));
            final List<Candidate<String>> all = assemble(List.of(ours), List.of(upstream));
            final Result r = find(all, "shanhai:light_voyage", MAX_HIGHLIGHT);
            final boolean ok = all.size() == 2 && r.totalHits() == 2
                    && r.hits().get(0).x == -88 && r.hits().get(0).y == 131 && r.hits().get(0).z == 82
                    && r.hits().get(0).kind == KIND_STELLAR
                    && r.hits().get(1).x == -88 && r.hits().get(1).y == 130 && r.hits().get(1).z == 83
                    && r.hits().get(1).kind == KIND_UPSTREAM_PATTERN_BUFFER;
            if (ok) {
                pass++;
            } else {
                bad.append(" ⑤我方+上游两台算出 candidates=").append(all.size())
                        .append(" hits=").append(r.totalHits()).append(';');
            }
        } catch (Throwable t) {
            bad.append(" ⑤threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // ⑥ 候选分类（唯一一份）：注册表用它的 kind；被动发现给上游 kind；不是 ICraftingProvider 一律丢
        //    + 去重：同一坐标既在注册表又被发现 ⇒ 只留一条（否则聊天栏会说"2 台"而世界只高亮一处）。
        try {
            final boolean ok = kindFor(true, KIND_SUPER_WILDCARD, true) == KIND_SUPER_WILDCARD
                    && kindFor(false, 0, true) == KIND_UPSTREAM_PATTERN_BUFFER
                    && kindFor(false, 0, false) == KIND_NONE
                    && kindFor(true, KIND_STELLAR, false) == KIND_NONE
                    && assemble(
                            List.of(Candidate.of(KIND_STELLAR, "d", 1, 2, 3, List.of("k"))),
                            List.of(Candidate.of(KIND_UPSTREAM_PATTERN_BUFFER, "d", 1, 2, 3, List.of("k")),
                                    Candidate.of(KIND_UPSTREAM_PATTERN_BUFFER, "d", 4, 5, 6, List.of("k")))).size() == 2;
            if (ok) {
                pass++;
            } else {
                bad.append(" ⑥kindFor/assemble 去重或分类不符;");
            }
        } catch (Throwable t) {
            bad.append(" ⑥threw ").append(t.getClass().getSimpleName()).append(';');
        }

        // 🔴 本方法【不写日志】：本类必须保持"零 MC 依赖"才能被离线 javac 单独编译并断言
        //    （打日志那一步由调用方做：服务端开机那一行由 ShanhaiLocateNetwork 打）。
        return "locate_core_selftest " + pass + "/" + total + " PASS=" + (pass == total)
                + "（判据：3 台里 2 台有 ⇒ 命中 2 台且坐标逐位对；概率副产算命中而只作输入不算；"
                + "0 命中 ⇒ plan=ACTION_NONE；12 台 ⇒ 总 12/高亮 " + MAX_HIGHLIGHT + "；"
                + "我方+上游各一台 ⇒ 两处坐标都报出；分类/去重唯一一份）"
                + (bad.length() == 0 ? "" : " FAILED:" + bad);
    }
}
