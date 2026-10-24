package com.shanhai.common.diag;

/**
 * 「配方进度被清零」只读诊断探针的<b>纯 JDK 判定核</b>（零 Minecraft / 零 GT / 零 Forge 依赖）。
 *
 * <h2>它存在的唯一理由</h2>
 * 上游有三个<b>完全静默</b>的"把进度弄没"的入口，日志上长得一模一样，分不开是谁干的：
 * <ol>
 *   <li>{@code RecipeLogic#interruptRecipe()} ⇒ {@code status=IDLE, progress=0, duration=0}；</li>
 *   <li>{@code RecipeLogic#doDamping()} ⇒ 进度<b>回退</b>（不是清零）；</li>
 *   <li>"先扣料、后判产出"的空窗 ⇒ 料没了、{@code lastRecipe=null}、机器空转。</li>
 * </ol>
 * 本类只回答一个问题：<b>这一次观察，到底该归到哪一类、要不要当成"真的清零"来报警</b>。
 *
 * <h2>🔴 为什么判定必须住在这个「纯 JDK」类里</h2>
 * 本工程纪律：「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」。
 * 若把判定写在带 Minecraft 依赖的类里，离线复现脚本只能<b>照抄一遍</b>判定 ——
 * 那就变成「抄错的是脚本还是生产代码」无法分叉。
 * ⇒ 本类<b>零依赖</b>，{@code javac --release 17} 可单独编译并直接 {@code java} 跑 {@link #main}，
 * <b>游戏里跑的是同一份字节码</b>，不存在第二份判定。
 *
 * <h2>判定依据全部来自对【装好的 jar】的 javap（不是猜的）</h2>
 * <ul>
 *   <li>{@code RecipeLogic#interruptRecipe()}：偏移 9 取 {@code lastRecipe}，
 *       <b>{@code ifnull → 51 return}</b> ⇒ {@code lastRecipe==null} 时它<b>一个字段都不动</b>；
 *       非 null 才走到 {@code postWorking} → {@code status=IDLE} → {@code progress=0} →
 *       {@code duration=0} → {@code ocResult.reset()}。</li>
 *   <li>{@code RecipeLogic#doDamping()}：偏移 1 取 {@code progress}，<b>{@code ifle 53 return}</b>；
 *       之后 {@code recipeProgressLowEnergy ? progress=1 : progress=max(1, progress-2)}
 *       ⇒ 结果只可能是 {@code 1} 或 {@code progress-2}，真正"回退"要 {@code progress>=2}。</li>
 * </ul>
 */
public final class RecipeProbeClassify {

    /** 统一前缀（{@code grep} 只认这一个串即可把本组探针的全部行捞出来）。 */
    public static final String PREFIX = "[SHANHAI-RLPROBE]";

    private RecipeProbeClassify() {}

    /** 探针事件的类别 = 会打出哪一类行。 */
    public enum Kind {
        /** {@code RecipeLogic#interruptRecipe} HEAD。 */
        INTERRUPT("interrupt"),
        /** {@code RecipeLogic#doDamping} HEAD。 */
        DAMP("damp"),
        /** {@code WorkableMultiblockMachine#onWorking} RETURN=false。 */
        ONWORKING_FALSE("onworking_false"),
        /** 找配方的闸门（先扣料后判产出）HEAD。 */
        GATE_ENTER("setup_gate"),
        /** 找配方的闸门 RETURN。 */
        GATE_EXIT("setup_gate"),
        /** 配方完工后再找下一条配方的闸门 HEAD（这条会直接 {@code progress=0}）。 */
        FINISH_ENTER("finish_gate"),
        /** 配方完工后再找下一条配方的闸门 RETURN。 */
        FINISH_EXIT("finish_gate");

        /** 日志行名（行首那一个词）。 */
        public final String lineName;

        Kind(String lineName) {
            this.lineName = lineName;
        }
    }

    /** 一次观察的结论。 */
    public static final class Verdict {

        /** 归类到的类别。 */
        public final Kind kind;

        /**
         * {@code true} = 这一次观察<b>确实</b>把"已经存在的进度/配方"弄没了，值得报警；
         * {@code false} = 本来就没有东西可清（空转）⇒ 属于<b>负对照</b>，行里必须写明原因，
         * 免得读日志的人把每一行都当成"又被清零了一次"。
         */
        public final boolean cleared;

        /** 追加进日志行尾的 {@code k=v} 串（{@code cleared=…} 一定在里面；永不为 {@code null}）。 */
        public final String tags;

        Verdict(Kind kind, boolean cleared, String tags) {
            this.kind = kind;
            this.cleared = cleared;
            this.tags = tags;
        }

        @Override
        public String toString() {
            return kind.lineName + "{" + tags + "}";
        }
    }

    // ═════════════════════════════ 判定函数 ═════════════════════════════

    /**
     * {@code interruptRecipe()} HEAD 的判定。
     *
     * @param hadLastRecipe 进入方法时 {@code lastRecipe != null}
     * @param progress      进入方法时的 {@code progress}
     * @param duration      进入方法时的 {@code duration}
     */
    public static Verdict interrupt(boolean hadLastRecipe, int progress, int duration) {
        if (!hadLastRecipe) {
            // javap：lastRecipe==null ⇒ 偏移 9 直接跳 51 return ⇒ 进度一个字都没动
            return new Verdict(Kind.INTERRUPT, false, "cleared=0 neg=no_lastRecipe");
        }
        if (progress <= 0 && duration <= 0) {
            // 负对照：本来就是 0 与 0 ⇒ 没有"被清零"这回事
            return new Verdict(Kind.INTERRUPT, false, "cleared=0 neg=already_zero");
        }
        return new Verdict(Kind.INTERRUPT, true, "cleared=1");
    }

    /**
     * {@code doDamping()} HEAD 的判定（只看"这次到底有没有把进度往回扒"）。
     *
     * @param progress 进入方法时的 {@code progress}
     */
    public static Verdict damp(int progress) {
        if (progress <= 0) {
            return new Verdict(Kind.DAMP, false, "cleared=0 neg=already_zero");
        }
        if (progress < 2) {
            // progress==1 ⇒ 两个分支都把它写成 1 ⇒ 名义上 damp 了，实际一格没退
            return new Verdict(Kind.DAMP, false, "cleared=0 neg=progress_le_1");
        }
        return new Verdict(Kind.DAMP, true, "cleared=1 rollback=1");
    }

    /** {@code onWorking()} 返回 {@code false} 的判定（它本身不清进度，但会紧接着触发 P1）。 */
    public static Verdict onWorkingFalse() {
        return new Verdict(Kind.ONWORKING_FALSE, false, "cleared=0 why=next_tick_interrupt");
    }

    /** 找配方闸门 HEAD 的判定（进入时还什么都没动）。 */
    public static Verdict gateEnter(boolean hasRecipeBefore, int progressBefore, int durationBefore) {
        return new Verdict(Kind.GATE_ENTER, false,
                "phase=enter hadRecipe=" + (hasRecipeBefore ? 1 : 0)
                        + " progress=" + progressBefore + " duration=" + durationBefore);
    }

    /**
     * 找配方闸门 RETURN 的判定。
     *
     * <p>{@code matchRecipeOutput} 判失败时上游<b>不会</b>打任何日志：料已经在
     * {@code getRecipe()} 里被真扣掉了，{@code lastRecipe} 留在 {@code null}，进度/时长原样停住
     * ⇒ 出来就是"配方进度从头再跑"。所以这里把「出口没有配方」直接判成一次清零。
     */
    public static Verdict gateExit(boolean hasRecipeAfter, int progressAfter, int durationAfter) {
        return new Verdict(Kind.GATE_EXIT, !hasRecipeAfter,
                "phase=exit hadRecipe=" + (hasRecipeAfter ? 1 : 0)
                        + " progress=" + progressAfter + " duration=" + durationAfter
                        + (hasRecipeAfter ? "" : " neg=gate_dropped_recipe"));
    }

    /** 完工后再找下一条配方的闸门 HEAD（此时进度/时长是<b>刚做完那一炉</b>的值）。 */
    public static Verdict finishEnter(boolean hasRecipeBefore, int progressBefore, int durationBefore) {
        return new Verdict(Kind.FINISH_ENTER, false,
                "phase=enter hadRecipe=" + (hasRecipeBefore ? 1 : 0)
                        + " progress=" + progressBefore + " duration=" + durationBefore);
    }

    /**
     * 完工后再找下一条配方的闸门 RETURN。
     *
     * <p>javap（{@code MutableRecipesLogic#onMultipleRecipeFinish} 偏移 158-175 /
     * {@code GTLAddMultipleRecipesLogic#onRecipeFinish} 同形）：{@code getRecipe()} 拿到东西但
     * {@code matchRecipeOutput} 判假 ⇒ 走 {@code status=IDLE; progress=0; duration=0}。
     * <b>这一条才是"进度清零"的完整足迹</b>（它不经 {@code interruptRecipe}）。
     */
    public static Verdict finishExit(boolean hasRecipeAfter, int progressAfter, int durationAfter) {
        return new Verdict(Kind.FINISH_EXIT, progressAfter <= 0 && durationAfter <= 0,
                "phase=exit hadRecipe=" + (hasRecipeAfter ? 1 : 0)
                        + " progress=" + progressAfter + " duration=" + durationAfter
                        + (progressAfter <= 0 && durationAfter <= 0 ? "" : " neg=not_zeroed"));
    }

    // ═════════════════════════════ 探针值表 ═════════════════════════════

    /** 一行样例：说明 + 期望判定。 */
    private static final class Case {

        final String input;
        final Verdict got;
        final boolean wantCleared;
        final String reading;

        Case(String input, Verdict got, boolean wantCleared, String reading) {
            this.input = input;
            this.got = got;
            this.wantCleared = wantCleared;
            this.reading = reading;
        }

        boolean pass() {
            return got.cleared == wantCleared;
        }
    }

    private static Case[] cases() {
        return new Case[]{
                // ── P1 interruptRecipe ───────────────────────────────────────────────
                new Case("interrupt lastRecipe=yes progress=37 duration=200",
                        interrupt(true, 37, 200), true,
                        "真清零：有配方 + 有进度 ⇒ 这一次把 37/200 抹了（要报警）"),
                new Case("interrupt lastRecipe=yes progress=0 duration=0",
                        interrupt(true, 0, 0), false,
                        "负对照：本来 0/0 ⇒ 不许报「清零」（空转抖动）"),
                new Case("interrupt lastRecipe=yes progress=0 duration=400",
                        interrupt(true, 0, 400), true,
                        "真清零：进度虽 0、duration 有值 ⇒ 这一炉的时长被抹了"),
                new Case("interrupt lastRecipe=no  progress=37 duration=200",
                        interrupt(false, 37, 200), false,
                        "负对照：lastRecipe==null ⇒ javap 证明它一个字段都不动（假警报源）"),
                // ── P2 doDamping ────────────────────────────────────────────────────
                new Case("damp progress=1",
                        damp(1), false,
                        "负对照：progress=1 ⇒ 两分支结果都是 1，一格没退"),
                new Case("damp progress=2",
                        damp(2), true,
                        "回退：2 ⇒ 1（recipeProgressLowEnergy 开）或 2 ⇒ 1（关）"),
                new Case("damp progress=900 duration=1000",
                        damp(900), true,
                        "回退：900 ⇒ 1（缺电配置开）—— 这是「从头再跑」的经典形态"),
                new Case("damp progress=0",
                        damp(0), false,
                        "负对照：progress<=0 ⇒ javap 偏移 4 ifle 直接 return"),
                // ── P3 onWorking ────────────────────────────────────────────────────
                new Case("onworking_false parts=[EnergyHatchx2,InputBusx3]",
                        onWorkingFalse(), false,
                        "它自己不清进度；判读要点是「下一 tick 紧跟一条 interrupt」"),
                // ── P4 找配方闸门 ───────────────────────────────────────────────────
                new Case("gate enter hadRecipe=1 progress=250 duration=400",
                        gateEnter(true, 250, 400), false,
                        "进闸：上一炉还在（250/400），接下来会被 lastRecipe=null"),
                new Case("gate exit  hadRecipe=0 progress=250 duration=400",
                        gateExit(false, 250, 400), true,
                        "🔴 空窗成立：料已扣、产出判失败 ⇒ 机器空转（进度停住不动=像清零）"),
                new Case("gate exit  hadRecipe=1 progress=0 duration=0",
                        gateExit(true, 0, 0), false,
                        "负对照：出口拿到配方 ⇒ 正常换料到下一炉"),
                // ── P5 完工闸门 ─────────────────────────────────────────────────────
                new Case("finish enter hadRecipe=1 progress=400 duration=400",
                        finishEnter(true, 400, 400), false,
                        "完工后开始找下一炉（此刻进度还是满的）"),
                new Case("finish exit  hadRecipe=0 progress=0 duration=0",
                        finishExit(false, 0, 0), true,
                        "🔴 真清零：完工闸门判失败 ⇒ status=IDLE + progress=0 + duration=0"),
                new Case("finish exit  hadRecipe=1 progress=400 duration=400",
                        finishExit(true, 400, 400), false,
                        "负对照：挂起/正常换料 ⇒ progress 与 duration 都还在（setupRecipe 会重置 progress，但 duration>0）"),
                new Case("finish exit  hadRecipe=1 progress=0 duration=400",
                        finishExit(true, 0, 400), false,
                        "负对照：duration 还在 ⇒ 没走清零那条（例如 SUSPEND 分支）"),
        };
    }

    /** 探针值表（人读版，直接可贴进报告）。 */
    public static String table() {
        final StringBuilder sb = new StringBuilder();
        sb.append("# 探针值表 —— 判定核 = ").append(RecipeProbeClassify.class.getName()).append('\n');
        sb.append("# 输入 → 该打哪一类行 / 判读（cleared=1 才是「真的把已有进度弄没了」）\n");
        for (Case c : cases()) {
            sb.append(String.format("%-62s | %-15s | cleared=%d | %s%n",
                    c.input, c.got.kind.lineName, c.got.cleared ? 1 : 0, c.reading));
        }
        return sb.toString();
    }

    /** 自检：逐行比对"判定"与"期望"，返回一行机器可判的结论。 */
    public static String selfTest() {
        int pass = 0;
        int fail = 0;
        final StringBuilder sb = new StringBuilder();
        for (Case c : cases()) {
            if (c.pass()) {
                pass++;
            } else {
                fail++;
                sb.append("  FAIL ").append(c.input)
                        .append(" -> got cleared=").append(c.got.cleared ? 1 : 0)
                        .append(" want cleared=").append(c.wantCleared ? 1 : 0)
                        .append(" (").append(c.got.tags).append(")\n");
            }
        }
        return "SELFTEST PASS=" + pass + " FAIL=" + fail
                + (fail == 0 ? " (全部一致)" : "\n" + sb);
    }

    /**
     * 纯 JDK 入口：{@code java -cp <classes> com.shanhai.common.diag.RecipeProbeClassify}。
     *
     * <p>打印「探针值表」+ 自检结论；自检不过时退出码 = 1（这样它可以被任何脚本当门禁用）。
     */
    public static void main(String[] args) {
        System.out.print(table());
        final String st = selfTest();
        System.out.println(st);
        if (st.contains("FAIL=0")) {
            System.out.println("RESULT=OK");
        } else {
            System.out.println("RESULT=BAD");
            System.exit(1);
        }
    }
}
