package com.shanhai.machine.wildcard;

/**
 * 「<b>自动倍率</b>」的<b>纯判定核</b> —— 把「控制器上读到的那个数」规范化成生效倍率，
 * 读不到时按<b>「无额外产出 = ×1」</b>算。
 *
 * <h2>一、它回答的三个问题（也就是本功能的全部逻辑）</h2>
 * <ol>
 *   <li><b>读到的数合不合法 / 怎么取整</b>（{@link #normalizeRead(double, int, int)}）：
 *       <b>向下取整（floor）</b>，再夹到 {@code [min, max]}；</li>
 *   <li><b>生效倍率取哪一个</b>（{@link #effective(boolean, int, int, int, int)}）：
 *       自动开着 ⇒ 读到就用读到的、<b>读不到就用 {@link #NO_EXTRA}（= ×1）</b>；
 *       自动关着 ⇒ 用手填值；</li>
 *   <li><b>界面那一行该写什么</b>（{@link #describe} / {@link #reasonLine}）。</li>
 * </ol>
 *
 * <h2>二、🔴 2026-10-04 用户修正（第二轮，逐字）</h2>
 * <pre>
 *   ② 「这个数应该是向下取整的」                      ⇒ {@link #normalizeRead} 用 Math.floor（不是 round）
 *   ④ 「读不到不应该默认是1吗，因为没有额外产出的机器才读不到啊」
 *                                                    ⇒ 读不到 ⇒ {@link #NO_EXTRA}（= ×1），
 *                                                      <b>不再</b>退回手填值；
 *                                                      手填值只在【开关关着】时才用
 * </pre>
 * <p>⚠️ 修正④ 在语义上是对的：本开关要读的是「那台 controller 给配方的<b>额外产出</b>倍率」，
 * 而「读不到」恰恰等价于「这台机器不给额外产出」⇒ 那份额外产出就是 <b>×1</b>，
 * 与「退回手填」是两件完全不同的事（手填值是一个跟控制器无关的玩家设定）。
 *
 * <h2>三、🔴 为什么必须是「纯 JDK 类」，不许碰 Minecraft / GT</h2>
 * 本项目纪律「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」。
 * 「读不到」这条路径在冒烟专服里<b>根本不会被走到</b>（冒烟世界 {@code smokeworld} 里
 * 没有任何这台机器），若判定写在带 Minecraft 依赖的类里，离线就只能<b>照抄一遍</b>判定 ——
 * 那就分不清「抄错的是脚本还是生产代码」。
 * ⇒ 判定集中在本类，{@code javac --release 17} 可单独编译并跑 {@link #selfTest()}，
 * <b>游戏里跑的是同一份字节码</b>，不存在第二份判定。
 *
 * <h2>四、输入界限常量由调用方传入（不在这里另起一套）</h2>
 * 倍率的合法区间是上游 {@code FOAPatternConfigurator} 的 {@code IntInputWidget.setMin(1).setMax(30)}
 * ——本工程那份常量在 {@link ShanhaiForgePatternMode#MIN_MULTIPLIER} / {@code MAX_MULTIPLIER}。
 * 那几个字段所在的类引用了 Minecraft 类型 ⇒ 本纯类<b>不引用它</b>，改成由调用方把
 * {@code (min, max)} 传进来。<b>全工程仍然只有那一份常量</b>，这里不复制数字。
 *
 * <h2>五、非法值的口径（写死，不留模糊）</h2>
 * <pre>
 *   NaN / Infinity / 负数 / 0   ⇒ {@link #NO_READ}（= 读不到 ⇒ 按 ×1 算）
 *   正数                        ⇒ 【向下取整】，再夹到 [min, max]
 *   ⚠️ 0.4 这种：> 0 算合法 ⇒ floor 成 0 ⇒ 夹到 min(=1)，结果也是 1（与「读不到」同值但原因不同）
 * </pre>
 */
public final class ShanhaiAutoForgeMultiplier {

    /**
     * 「没读到 / 读到的值非法」的规范哨兵。
     *
     * <p>⚠️ 它<b>不是</b>合法倍率（合法区间是 {@code [min, max]} ⊂ 正整数），
     * 所以任何「拿它当倍率传给上游」的错误写法都会在自检里被抓住。
     */
    public static final int NO_READ = -1;

    /**
     * <b>「没有额外产出」的倍率 = ×1</b>（用户 2026-10-04 逐字：
     * 「读不到不应该默认是1吗，<b>因为没有额外产出的机器才读不到啊</b>」）。
     *
     * <p>用途只有一个：自动开着、但控制器上读不到那份「额外产出倍率」时，
     * 生效倍率取这个数（再夹到 {@code [min, max]}；本工程 {@code min = 1} ⇒ 就是 1）。
     */
    public static final int NO_EXTRA = 1;

    // ═══════════════════════════ 来源标注（读成功时上界面第二行） ═══════════════════════════
    //
    // ⚠️ 刻意很短：这些串会被摆进一个侧栏面板（中文字宽 9 px；面板内容区 134 px ⇒ 最多 14 个全角字）。

    /** 来源①：gtladditions 的伪神之锻炉控制器（经反射读 {@code getRecipeOutputMultiply()}）。 */
    public static final String SOURCE_FORGE_OF_THE_ANTICHRIST = "伪神之锻炉控制器";

    /** 来源②：本工程自己的原始终焉引擎（直接调 {@code PrimordialRecipeEffects#outputMultiplier}）。 */
    public static final String SOURCE_PRIMORDIAL_ENGINE = "原始终焉引擎";

    /**
     * 来源③：gtladditions 的伪神之锻炉<b>模块</b>（用户 2026-10-04 追加①：
     * 「伪神锻之锻炉和原始终焉引擎的<b>模块</b>也是有倍率产出的啊，它读不到」）。
     *
     * <p>模块自己的产出倍率 = <b>它主机的</b>那个数（{@code javap -c} 实证：模块的
     * {@code calculateParallels()} 里就是 {@code ContentModifier.multiplier(getHost().getRecipeOutputMultiply())}）。
     */
    public static final String SOURCE_FORGE_MODULE = "伪神之锻炉模块";

    /** 来源④：本工程自己的模块（原初临界加工模块那一脉）；同样取它主机的产出倍率。 */
    public static final String SOURCE_PRIMORDIAL_MODULE = "原始终焉引擎模块";

    // ═══════════════════════════ 失败原因（读不到时上界面第二行） ═══════════════════════════

    /** 本仓室没有控制器（多半整座多方块还没成型）。 */
    public static final String REASON_NO_CONTROLLER = "未成型（无控制器）";

    /** 有控制器，但它不是能提供额外产出的那四种（两台机器 + 它们的模块）。 */
    public static final String REASON_NO_SOURCE = "控制器无额外产出";

    /** 那台模块还没连上主机（上游模块在没主机时自己也会崩，本工程只能给 ×1）。 */
    public static final String REASON_MODULE_NO_HOST = "模块未连主机";

    /** 类名就是伪神锻，但签名对不上（上游改过）—— 这一档要 ERROR，不是常态。 */
    public static final String REASON_SIGNATURE_BROKEN = "伪神锻但签名变了";

    /** 方法是通的，但读回来的是 NaN / Infinity / ≤0。 */
    public static final String REASON_ILLEGAL_VALUE = "返回值非法";

    /** 反射调用本身抛了（上游内部状态未就绪等）。 */
    public static final String REASON_FAILED = "读取异常";

    /** 自动开关关着 —— 这一档不是「失败」，是「没启用」。 */
    public static final String REASON_AUTO_OFF = "自动开关关着";

    private ShanhaiAutoForgeMultiplier() {}

    /** 把 {@code value} 夹到 {@code [min, max]}；{@code min > max} 时以 {@code max} 为准。 */
    public static int clamp(int value, int min, int max) {
        final int lo = Math.min(min, max);
        final int hi = Math.max(min, max);
        return Math.max(lo, Math.min(hi, value));
    }

    /**
     * 控制器读回来的原始数值 ⇒ 生效倍率；读不到 / 非法 ⇒ {@link #NO_READ}。
     *
     * <h2>🔴 取整口径 = 【向下取整】（用户 2026-10-04 修正②逐字：「这个数应该是向下取整的」）</h2>
     * {@code Math.floor} —— <b>不是</b> {@code Math.round}（14.5 会给 15），也<b>不是</b>
     * {@code (int)} 强转（对负数是向零截断，语义不同；虽然负数在下面已被判非法）。
     *
     * <p>🔴 <b>绝不抛异常</b>：本方法对任何输入都返回值（{@code NaN} / {@code ±Infinity} /
     * 天文数字都在自检覆盖内）。
     */
    public static int normalizeRead(double raw, int min, int max) {
        if (!Double.isFinite(raw) || raw <= 0.0) {
            return NO_READ;
        }
        // ⚠️ 必须在 long 空间先夹再转 int：Math.floor(1e300) 仍是 1e300，
        //    直接 (int) 强转会绕回负数（自检里有一条专门盯它）。
        final double floored = Math.floor(raw);
        final double bounded = Math.max(min, Math.min(max, floored));
        return (int) bounded;
    }

    /**
     * <b>生效倍率</b> —— 全工程唯一的口径。
     *
     * <pre>
     *   自动开 + 读到了   ⇒ 读到的值（已向下取整并夹过）
     *   自动开 + 读不到   ⇒ {@link #NO_EXTRA} = ×1      ← 用户修正④的落点
     *   自动关            ⇒ 手填值                      ← 手填【只】在这一档参与
     * </pre>
     *
     * <p>🔴 「读不到」<b>不是</b>「退回手填值」、<b>不是</b>「用 15」、<b>不是</b>「抛异常」，
     * 而是「那台机器没给额外产出 ⇒ ×1」。
     *
     * <p>⚠️ 「读到了」的判据是 {@link #isValidRead(int, int, int)}（<b>不</b>只是
     * {@code read != NO_READ}）：读取方存进去的值一定是夹过的，所以任何落在
     * {@code [min, max]} 之外的值都说明<b>这个数从来不是一次真实的读取结果</b>
     * （典型来源：客户端那份字段在首次同步之前还是 Java 默认值 {@code 0}）
     * ⇒ 一并按「读不到」处理（结果是 ×1），绝不显示一个算法没用过的数
     * —— 本工程红线「活的界面上不许放假数据」。
     */
    public static int effective(boolean autoEnabled, int read, int hand, int min, int max) {
        if (autoEnabled) {
            return isValidRead(read, min, max) ? clamp(read, min, max) : clamp(NO_EXTRA, min, max);
        }
        return clamp(hand, min, max);
    }

    /**
     * 这个读数是不是<b>一次真实的读取结果</b>。
     *
     * <p>真 ⟺ 它落在 {@code [min, max]} 内（服务端写入前一定夹过）。
     * {@link #NO_READ} 与任何越界值（含客户端未同步时的 {@code 0}）都为假。
     */
    public static boolean isValidRead(int read, int min, int max) {
        if (read == NO_READ) {
            return false;
        }
        final int lo = Math.min(min, max);
        final int hi = Math.max(min, max);
        return read >= lo && read <= hi;
    }

    /**
     * 面板第一行（<b>当前倍率是手填的还是读来的、读到的值是多少</b>）。
     *
     * <p>三种形态，逐字如下（{@code §} 是 Minecraft 的颜色码，纯函数 ⇒ 离线可断言）：
     * <pre>
     *   自动关              ：§7手动·倍率 §f15
     *   自动开 + 读到了     ：§b自动·读到 §a23
     *   自动开 + 读不到     ：§b自动§f·读不到 -&gt; §a×1
     * </pre>
     * ⚠️ 第三行写的是 <b>×1</b>（不是手填值）：这正是用户修正④的口径 ——
     * 「读不到 = 这台机器不给额外产出」，与输入框里填了几无关。
     */
    public static String describe(boolean autoEnabled, int read, int hand, int min, int max) {
        if (!autoEnabled) {
            return "§7手动·倍率 §f" + clamp(hand, min, max);
        }
        if (!isValidRead(read, min, max)) {
            return "§b自动§f·读不到 -> §a×" + clamp(NO_EXTRA, min, max);
        }
        return "§b自动·读到 §a" + clamp(read, min, max);
    }

    /**
     * 面板第二行（这个数是<b>从哪来的</b> / 读不到是<b>哪种</b>读不到）。
     *
     * @param note 读取成功时是 {@code SOURCE_*} 之一（来源），失败时是 {@code REASON_*} 之一
     */
    public static String reasonLine(boolean autoEnabled, int read, int min, int max, String note) {
        if (!autoEnabled) {
            return "§8关掉开关即可改倍率";
        }
        final String text = (note == null || note.isEmpty()) ? REASON_FAILED : note;
        if (isValidRead(read, min, max)) {
            return "§8来源：" + text;
        }
        return "§8原因：" + text;
    }

    /**
     * 🔴 <b>自检 —— 本项目纪律「检查器先证明自己对」的落点。</b>
     *
     * <p>逐条断言 {@link #normalizeRead} 与 {@link #effective} 的<b>全部分支</b>，
     * 以及 {@link #describe} / {@link #reasonLine} 的三种形态。
     * 任一条不成立 ⇒ 抛 {@link IllegalStateException}
     * （宁可当场炸，也不许「看起来能自动、其实读不到时算错」）。
     *
     * <p><b>为什么它是纯的</b>：不碰日志、不碰文件、不碰反射、不碰时钟 ⇒
     * {@code javac --release 17 ShanhaiAutoForgeMultiplier.java} 单独编译即可跑，
     * 游戏里跑的是<b>同一份字节码</b>。
     *
     * @return 给调用方打印用的一整份读数
     */
    public static String selfTest() {
        final int MIN = 1;
        final int MAX = 30;
        final StringBuilder sb = new StringBuilder();
        int checks = 0;

        // ═══════════ ① 读不到 ⇒ NO_READ ⇒ 自动开着时按 ×1 算（用户修正④） ═══════════
        final double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, -0.5, 0.0};
        for (double v : bad) {
            final int r = normalizeRead(v, MIN, MAX);
            require(r == NO_READ, "① 非法输入 " + v + " 应判 NO_READ，实为 " + r);
            checks++;
            // 🔴 修正④：读不到 ⇒ ×1（【不是】手填值 7）
            final int auto = effective(true, r, 7, MIN, MAX);
            require(auto == NO_EXTRA && auto == 1, "① 非法输入 " + v + " 自动开着应算 ×1，实为 " + auto);
            checks++;
            // 自动关着 ⇒ 仍然是手填值（手填【只】在关的时候用）
            final int manual = effective(false, r, 7, MIN, MAX);
            require(manual == 7, "① 自动关着 ⇒ 生效倍率应为手填值 7，实为 " + manual);
            checks++;
        }
        sb.append("① 非法值 ").append(bad.length).append(" 种 ⇒ 全部 NO_READ；自动开 ⇒ ×1；自动关 ⇒ 手填 7 ; checks=")
                .append(checks).append('\n');

        // ═══════════ ② 读到了：【向下取整】 + 夹到 [1,30]（用户修正②） ═══════════
        // 🔴 判据就在这两条分界上：[14.5 ⇒ 14]（round 会给 15）、[22.5 ⇒ 22]（round 会给 23）
        final double[] rawOk = {0.4, 0.5, 0.99, 1.0, 1.99, 2.0, 14.49, 14.5, 15.0, 18.99, 22.5, 29.99, 30.0, 30.9, 999.0, 1e300};
        final int[] want = {1, 1, 1, 1, 1, 2, 14, 14, 15, 18, 22, 29, 30, 30, 30, 30};
        for (int i = 0; i < rawOk.length; i++) {
            final int r = normalizeRead(rawOk[i], MIN, MAX);
            require(r == want[i], "② 读到 " + rawOk[i] + " 向下取整后应为 " + want[i] + "，实为 " + r);
            checks++;
        }
        sb.append("② 向下取整 ").append(rawOk.length).append(" 组（含 14.5⇒14 / 22.5⇒22 这两条 round/floor 分界） ; checks=")
                .append(checks).append('\n');

        // ═══════════ ③ 上游伪神之锻炉的真值公式（javap -c 实证，修正③） ═══════════
        //
        //   ForgeOfTheAntichrist.baseRecipeOutputMultiply()
        //     = 1 + 14 * Math.pow(Math.min(runningSecs,14400) / 14400, 2.0)   ← ratio²，【不是】2^ratio
        //       字节码：… ddiv（m/14400）→ ldc2_w 2.0 → Math.pow(DD)D → dmul → +1.0
        //   ForgeOfTheAntichrist.getRecipeOutputMultiply()
        //     = baseRecipeOutputMultiply() * RecursiveReverseBuffState.outputMultiplier
        //       而后者在 buildBuffState 里是 `ldc2_w 2.0d / dconst_1` ⇒ 只可能是 1.0 或 2.0
        //   ⇒ base ∈ [1, 15]（上限 15 = 用户说的那个数）；乘上递归反演的 ×2 后总上限 = 30
        final double[] secs = {0.0, 3600.0, 7200.0, 10800.0, 14400.0, 99999.0};
        final int[] wantBase = {1, 1, 4, 8, 15, 15};
        final int[] wantRrf = {2, 3, 9, 17, 30, 30};
        for (int i = 0; i < secs.length; i++) {
            final double base = 1 + 14 * Math.pow(Math.min(secs[i], 14400.0) / 14400.0, 2.0);
            final int rBase = normalizeRead(base, MIN, MAX);
            final int rRrf = normalizeRead(base * 2.0, MIN, MAX);
            require(rBase == wantBase[i], "③ runningSecs=" + secs[i] + " 的 base 应为 " + wantBase[i]
                    + "（旧口径的 15/18/21/29 是错的），实为 " + rBase);
            require(rRrf == wantRrf[i], "③ runningSecs=" + secs[i] + " 的 base×2 应为 " + wantRrf[i] + "，实为 " + rRrf);
            checks += 2;
        }
        require(normalizeRead(1 + 14 * Math.pow(1.0, 2.0), MIN, MAX) == 15, "③ base 的上限必须是 15");
        require(normalizeRead(2 * (1 + 14 * Math.pow(1.0, 2.0)), MIN, MAX) == 30, "③ base×2 的上限是 30");
        checks += 2;
        sb.append("③ 上游真值公式 6 点 × 2（base ∈ [1,15] / base×RRF2 ∈ [2,30]） ; checks=")
                .append(checks).append('\n');

        // ═══════════ ④ 生效倍率：读到就用、读不到 ×1、关着用手填 ═══════════
        require(effective(true, 23, 7, MIN, MAX) == 23, "④ 自动开+读到 23 ⇒ 生效 23");
        require(effective(true, NO_READ, 7, MIN, MAX) == 1, "④ 自动开+读不到 ⇒ 生效 1（手填 7 不参与）");
        require(effective(false, 23, 7, MIN, MAX) == 7, "④ 自动关 ⇒ 生效手填 7（读到的 23 不算数）");
        require(effective(false, NO_READ, 7, MIN, MAX) == 7, "④ 自动关+读不到 ⇒ 生效手填 7");
        require(effective(false, NO_READ, 99, MIN, MAX) == 30, "④ 手填 99 ⇒ 夹到 30");
        require(effective(false, NO_READ, 0, MIN, MAX) == 1, "④ 手填 0 ⇒ 夹到 1");
        checks += 6;
        // 🔴 「越界的读数不算数」—— 防的是「客户端那份字段尚未同步（默认 0）时
        //    面板显示一个算法从没用过的数」。
        require(!isValidRead(0, MIN, MAX), "④ 读数 0（未同步的默认值）必须判为「不是真实读取」");
        require(!isValidRead(-5, MIN, MAX), "④ 读数 -5 必须判为无效");
        require(!isValidRead(31, MIN, MAX), "④ 读数 31（越上界）必须判为无效");
        require(isValidRead(1, MIN, MAX) && isValidRead(30, MIN, MAX), "④ 边界 1/30 必须判为有效");
        require(effective(true, 0, 7, MIN, MAX) == 1, "④ 自动开 + 读数未同步的 0 ⇒ 必须按 ×1");
        require(describe(true, 0, 7, MIN, MAX).contains("读不到"), "④ 自动开 + 读数 0 ⇒ 文案必须写「读不到」");
        require(describe(true, 31, 7, MIN, MAX).contains("读不到"), "④ 自动开 + 读数越上界 ⇒ 文案必须写「读不到」");
        checks += 7;
        sb.append("④ 生效口径 6 条 + 越界读数 7 条 ; checks=").append(checks).append('\n');

        // ═══════════ ⑤ 界面文案：三态可分辨，且「读不到」写的是 ×1 而不是手填 ═══════════
        final String manual = describe(false, 23, 15, MIN, MAX);
        final String autoOk = describe(true, 23, 15, MIN, MAX);
        final String autoFail = describe(true, NO_READ, 15, MIN, MAX);
        require(manual.contains("手动") && manual.contains("15"), "⑤ 自动关的文案应含「手动 / 15」，实为 " + manual);
        require(autoOk.contains("读到") && autoOk.contains("23"), "⑤ 自动成功的文案应含「读到 23」，实为 " + autoOk);
        require(autoFail.contains("读不到") && autoFail.contains("×1"), "⑤ 读不到的文案应含「读不到 / ×1」，实为 " + autoFail);
        require(!autoFail.contains("15"), "⑤ 读不到的文案【不许】再出现手填值 15，实为 " + autoFail);
        require(!manual.equals(autoOk) && !autoOk.equals(autoFail) && !manual.equals(autoFail),
                "⑤ 三种形态的文案必须互不相同");
        require(reasonLine(false, NO_READ, MIN, MAX, null).contains("关掉开关"), "⑤ 自动关的原因行");
        require(reasonLine(true, 23, MIN, MAX, SOURCE_FORGE_OF_THE_ANTICHRIST).contains(SOURCE_FORGE_OF_THE_ANTICHRIST),
                "⑤ 自动成功的原因行应写出来源（伪神锻）");
        require(reasonLine(true, 23, MIN, MAX, SOURCE_PRIMORDIAL_ENGINE).contains(SOURCE_PRIMORDIAL_ENGINE),
                "⑤ 自动成功的原因行应写出来源（原始终焉引擎）");
        require(reasonLine(true, 23, MIN, MAX, SOURCE_FORGE_MODULE).contains(SOURCE_FORGE_MODULE),
                "⑤ 自动成功的原因行应写出来源（伪神之锻炉模块）");
        require(reasonLine(true, 23, MIN, MAX, SOURCE_PRIMORDIAL_MODULE).contains(SOURCE_PRIMORDIAL_MODULE),
                "⑤ 自动成功的原因行应写出来源（原始终焉引擎模块）");
        // 🔴 四种来源的标注必须两两不同（否则「面板显示当前来源」这条要求落空）
        final String[] sources = {SOURCE_FORGE_OF_THE_ANTICHRIST, SOURCE_PRIMORDIAL_ENGINE,
                SOURCE_FORGE_MODULE, SOURCE_PRIMORDIAL_MODULE};
        for (int i = 0; i < sources.length; i++) {
            for (int j = i + 1; j < sources.length; j++) {
                require(!sources[i].equals(sources[j]), "⑤ 来源标注重复：" + sources[i]);
            }
            // 每个来源都要放得下面板内容区（134 px ÷ 9 px = 14 个全角字上限）
            require(sources[i].length() <= 11, "⑤ 来源标注过长（面板放不下）：" + sources[i]);
        }
        require(reasonLine(true, NO_READ, MIN, MAX, REASON_NO_CONTROLLER).contains(REASON_NO_CONTROLLER),
                "⑤ 读不到的原因行应带上具体原因");
        require(reasonLine(true, 0, MIN, MAX, REASON_NO_SOURCE).contains(REASON_NO_SOURCE),
                "⑤ 读数越界时原因行也必须走「读不到」那一档");
        require(reasonLine(true, NO_READ, MIN, MAX, REASON_MODULE_NO_HOST).contains(REASON_MODULE_NO_HOST),
                "⑤ 「模块未连主机」也必须是可显示的一档");
        // ⑤ 段实际断言数：上面 8 条（三态 3 + 互异 1 + 关掉 1 + 四种来源 4 —— 其中三态/互异/关掉共 5）
        //   + 双重循环 6（两两不同）+ 4（长度）+ 下面 3（三条原因） = 18
        checks += 18;
        sb.append("⑤ 界面文案 3 态 + 4 种来源两两可分辨 + 3 条原因行 ; checks=").append(checks).append('\n');

        sb.append("AUTO-FORGE-MULTIPLIER SELFTEST = PASS (checks=").append(checks).append(')');
        return sb.toString();
    }

    /** 断言；不成立就抛（本工程：宁可当场炸，也不许静默）。 */
    static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("ShanhaiAutoForgeMultiplier 自检失败：" + message);
        }
    }
}
