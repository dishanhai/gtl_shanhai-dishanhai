package com.shanhai.add.gravity;

/**
 * 山海 · <b>全量调制重力</b>的倍率本体（纯算术，<b>不 import 任何 Minecraft 类</b>）。
 *
 * <h2>一条倍率 G 的语义（用户 2026-10-18 拍板 B 档）</h2>
 * <pre>
 *   G = 0    ⇒ 关掉重力（所有重力项为 0，跳跃/推进/浮力照旧）
 *   G = 1    ⇒ 原版（每一个施加点都逐字等于原版常量）
 *   G &lt; 0    ⇒ 反重力（向上"掉"）
 *   0 &lt; G &lt; 1 ⇒ 半重力 / 更飘
 * </pre>
 *
 * <h2>🔴 为什么不能"共用一个 0.08"</h2>
 * 各实体写死的基础值不一样（生物 0.08、物品 0.04、经验球 0.03、箭 0.05、投掷物 0.03/0.05/0.07、
 * 钓鱼钩 0.03、羊驼口水 0.06、潜影贝子弹 0.04、矿车 0.04/0.005、船 0.04、鱿鱼 0.08、水生 0.005）。
 * ⇒ 唯一正确的口径是 <b>各基准值 × G</b>（{@link #scaleFor(double, double)}），
 * 这样 G=1 时与上游逐字节一致，G=0 时全部归零。
 *
 * <h2>🔴 为什么这个类必须"零 MC 依赖"</h2>
 * 自检要能<b>离线跑</b>（{@code javac} 编译 + {@code java} 直接执行，不开游戏、不过 Gradle）。
 * 所以：状态 + 算术 + 自检全在这里；一切碰 Minecraft 的代码在
 * {@link ShanhaiGravityLiving} ／ {@link ShanhaiGravityNetwork} ／ mixin 里。
 *
 * <h2>两端一致</h2>
 * 这是一个<b>进程内静态值</b>：单机（集成服务器与客户端同 JVM）天然共享同一份；
 * 专用服务器上客户端那份由 {@link ShanhaiGravityNetwork} 的 S2C 包写入，<b>拿不到就按 1</b>（安全默认）。
 */
public final class ShanhaiGravity {

    /** 本功能的日志前缀（主代理按这串 grep）。 */
    public static final String PREFIX = "[SHANHAI-GRAVITY]";

    /** 原版倍率。 */
    public static final double VANILLA = 1.0D;

    /**
     * 倍率下限/上限 = ±100。
     *
     * <p>为什么是 100：生物轨走的是 Forge 的 {@code forge:entity_gravity} 属性，
     * 它的值域是 <b>-8 ~ 8</b>（{@code RangedAttribute(..., -8.0D, 8.0D)}，见 ForgeMod），
     * 而基准值是 0.08 ⇒ 0.08×100 = 8.0 正好顶到上界。再大就会被属性本身夹住（页面/日志会看不到差别）。
     */
    public static final double MIN_MULT = -100.0D;
    public static final double MAX_MULT = 100.0D;

    /** {@code LivingEntity.DEFAULT_BASE_GRAVITY}（= {@code forge:entity_gravity} 的默认值）。 */
    public static final double LIVING_BASE_GRAVITY = 0.08D;

    /**
     * 原版缓降药水在 {@code forge:entity_gravity} 上加的那一条 modifier 的量（ADDITION）。
     *
     * <p>见 {@code LivingEntity} 的 {@code <clinit>}：{@code new AttributeModifier(..., -0.07, ADDITION)}，
     * 上游注释逐字写着 "Add -0.07 to 0.08 so we get the vanilla default of 0.01"。
     */
    public static final double SLOW_FALLING_VANILLA_ADDITION = -0.07D;

    /** 当前倍率（唯一权威值）。 */
    private static volatile double mult = VANILLA;

    /** 客户端是否已经从服务端收到过倍率（只用于日志判据，不参与物理）。 */
    private static volatile boolean syncedFromServer;

    private ShanhaiGravity() {}

    // ═══════════════════════════════ 状态 ═══════════════════════════════

    public static double get() {
        return mult;
    }

    /** 是否等于原版（= 每一个 mixin 都应该"逐字放行"）。 */
    public static boolean isVanilla() {
        return mult == VANILLA;
    }

    public static boolean isSyncedFromServer() {
        return syncedFromServer;
    }

    /** 夹到 [{@link #MIN_MULT}, {@link #MAX_MULT}]；NaN ⇒ 当 1（不许把 NaN 带进物理）。 */
    public static double clamp(double want) {
        if (Double.isNaN(want)) {
            return VANILLA;
        }
        if (want < MIN_MULT) {
            return MIN_MULT;
        }
        if (want > MAX_MULT) {
            return MAX_MULT;
        }
        return want;
    }

    /** 服务端/本地设置倍率（夹取后落到静态值）。返回<b>真正生效</b>的值。 */
    public static double set(double want) {
        final double v = clamp(want);
        mult = v;
        return v;
    }

    /** 客户端收到服务端下发的倍率。 */
    public static double setFromServer(double want) {
        final double before = mult;
        final double v = set(want);
        syncedFromServer = true;
        return v == before ? before : v;
    }

    // ═══════════════════════════════ 算术 ═══════════════════════════════

    /**
     * 🔴 唯一的口径：<b>各自基准值 × G</b>。
     *
     * <p>之所以把 {@code m} 作为参数而不是直接读 {@link #mult}：自检要能喂任意 G（含负对照），
     * 生产调用走 {@link #scale(double)} ／ {@link #scaleF(float)}，两者是<b>同一行代码</b>。
     */
    public static double scaleFor(double base, double m) {
        return base * m;
    }

    /** 把一个 double 基准常量按当前倍率缩放（{@code ModifyConstant} 的落点）。 */
    public static double scale(double base) {
        return scaleFor(base, mult);
    }

    /** 同上，float 版（{@code AbstractArrow} 的 0.05F、羊驼口水的 -0.06F、船的 -0.04F 等）。 */
    public static float scaleF(float base) {
        return (float) scaleFor((double) base, mult);
    }

    /**
     * 把原版"缓降 = ADDITION -0.07"换算成 <b>MULTIPLY_TOTAL 的等价值</b>（-0.875）。
     *
     * <p>推导：{@code value = base + (-0.07) = 0.01}，相对基准 0.08 的倍率是
     * {@code 0.01/0.08 = 0.125}；MULTIPLY_TOTAL 下 {@code value = base × (1 + amount)}
     * ⇒ {@code amount = 0.125 - 1 = -0.875}。
     *
     * <p>🔴 为什么必须换成乘法：原版是<b>加法</b>，它跟"再乘一个 G"混在一起时，
     * G=0 会留下 -0.07（缓降变成"缓升"）、G=-1 会叠成更强上升。
     * 换成乘法后 {@code value = 0.08 × 0.125 × G}：
     * G=1 ⇒ 0.01（与原版逐字一致）、G=0 ⇒ 0、G=-1 ⇒ -0.01（= 反重力下"缓降"该有的 1/8 强度）。
     *
     * @param vanillaAddition 原版那条 ADDITION 的量（本版 = -0.07）
     * @return MULTIPLY_TOTAL 下等价的量（本版 = -0.875）
     */
    public static double slowFallingAmount(double vanillaAddition) {
        final double factor = (LIVING_BASE_GRAVITY + vanillaAddition) / LIVING_BASE_GRAVITY;
        return factor - 1.0D;
    }

    /**
     * 服务端"玩家悬空"反作弊判据里的那个 y 速度阈值（{@code d7 >= -0.03125D}）。
     *
     * <p>原版语义：<b>没有方块支撑、且这一 tick 没在往下掉够多</b> ⇒ 记 {@code clientIsFloating}，
     * 连续 80 tick（4 秒）⇒ 踢人（{@code multiplayer.disconnect.flying}）。
     * G ≤ 0 时玩家本来就"悬着"/往上走，这条判据必然成立 ⇒ 必须放宽。
     *
     * <p>做法：<b>只把比较阈值推到 +∞</b>（{@code d7 >= +∞} 永远 false），
     * 于是 {@code clientIsFloating = false}、永远不踢；<b>不动</b> {@code server.isFlightAllowed()}、
     * <b>不动</b> {@code abilities.mayfly}、<b>不动</b> 玩家能力/NBT ⇒ 恢复 G=1 时逐字回到原版。
     *
     * @param vanillaThreshold 原版常量（-0.03125D）
     * @return G=1 ⇒ 原常量（原版判据完全不变）；否则 +∞（永不判浮空）
     */
    public static double floatingThreshold(double vanillaThreshold) {
        return isVanilla() ? vanillaThreshold : Double.POSITIVE_INFINITY;
    }

    // ═══════════════════════════════ 读数 ═══════════════════════════════

    /**
     * 开机/改值后必打的一行状态读数。
     *
     * <p>形如 {@code gravity_state mult=0.5 entities=living+nonliving sites=17}。
     */
    public static String stateLine(int sites) {
        return "gravity_state mult=" + fmt(mult) + " entities=living+nonliving sites=" + sites;
    }

    public static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ═══════════════════════════════ 离线自检 ═══════════════════════════════

    /** 17 个施加点的人话名字（与普查报告 §7.2 的 1-17 逐条对应）。 */
    public static final String[] SITE_NAMES = {
            "LivingEntity.travel(生物/玩家/坐骑 基准 0.08)",
            "ItemEntity.tick(掉落物 -0.04)",
            "ExperienceOrb.tick(经验球 -0.03)",
            "FallingBlockEntity.tick(下落方块 -0.04)",
            "PrimedTnt.tick(TNT -0.04)",
            "AbstractArrow.tick(箭/药箭/光谱箭/三叉戟 -0.05)",
            "ThrowableProjectile.tick(雪球/蛋/珍珠 0.03·经验瓶 0.07·药水 0.05)",
            "FishingHook.tick(钓鱼钩 -0.03)",
            "LlamaSpit.tick(羊驼口水 -0.06)",
            "ShulkerBullet.tick(潜影贝子弹 -0.04)",
            "AbstractMinecart.tick(矿车 -0.04 / 水里 -0.005)",
            "Boat.floatBoat(船 -0.04)",
            "Squid.aiStep(鱿鱼 -0.08)",
            "AbstractFish.travel(鱼 -0.005)",
            "Dolphin.travel(海豚 -0.005)",
            "Guardian.travel(守卫者 -0.005)",
            "Turtle.travel(海龟 -0.005)"
    };

    /**
     * 每个施加点的<b>原版基准值</b>（负值 = 原版就是向下减）。
     *
     * <p>🔴 这 17 个数必须来自普查报告 §3（每个都是文件:行级取证过的常量），
     * 不许拍脑袋写成一个统一的 0.08。
     */
    public static final double[] SITE_BASES = {
            0.08D,      //  LivingEntity（forge:entity_gravity 默认值）
            -0.04D,     //  ItemEntity
            -0.03D,     //  ExperienceOrb
            -0.04D,     //  FallingBlockEntity
            -0.04D,     //  PrimedTnt
            -0.05D,     //  AbstractArrow（0.05F）
            0.03D,      //  ThrowableProjectile 基类（子类 0.07 / 0.05 覆写）
            -0.03D,     //  FishingHook
            -0.06D,     //  LlamaSpit（-0.06F）
            -0.04D,     //  ShulkerBullet
            -0.04D,     //  AbstractMinecart（空中）
            -0.04D,     //  Boat（-0.04F）
            -0.08D,     //  Squid
            -0.005D,    //  AbstractFish
            -0.005D,    //  Dolphin
            -0.005D,    //  Guardian
            -0.005D     //  Turtle
    };

    /** 自检要过一遍的倍率（含用户验收用的 0.5 / 0 / -1）。 */
    public static final double[] CHECK_MULTS = { 1.0D, 0.5D, 0.0D, -1.0D, 2.0D, -0.5D };

    /** 被测函数（自检里可以换成正样本 / 突变体，这就是"负对照"的接口）。 */
    public interface ScaleFn {
        double apply(double base, double mult);
    }

    private static int failures;
    private static int checks;

    /**
     * 跑一遍纯算术自检。
     *
     * @param fn 被测的缩放实现（正样本 = {@link #scaleFor}；负对照 = 故意"不乘 G"的 {@link #mutantNoScale}）
     * @param verbose 是否逐条打印
     * @return 失败条数（0 = 全绿）
     */
    public static int runSelfCheck(ScaleFn fn, boolean verbose) {
        failures = 0;
        checks = 0;
        // ① 每个施加点 × 每个 G：期望值 = 基准值 × G（这就是规格本身）
        for (int i = 0; i < SITE_BASES.length; i++) {
            final double base = SITE_BASES[i];
            for (double g : CHECK_MULTS) {
                check(fn, verbose, SITE_NAMES[i] + " × G=" + fmt(g), base * g, fn.apply(base, g));
            }
        }
        // ② G=1 必须逐字等于原版（这是"不改变现有手感"的唯一判据）
        for (int i = 0; i < SITE_BASES.length; i++) {
            check(fn, verbose, SITE_NAMES[i] + " × G=1 必须逐字等于原版",
                    SITE_BASES[i], fn.apply(SITE_BASES[i], 1.0D));
        }
        // ③ G=0 必须严格 0（正负都算）
        for (int i = 0; i < SITE_BASES.length; i++) {
            check(fn, verbose, SITE_NAMES[i] + " × G=0 必须严格 0",
                    0.0D, fn.apply(SITE_BASES[i], 0.0D));
        }
        // ④ 缓降：0.08 × 0.125 = 0.01（G=1），且 G=-1 时是 -0.01 而不是"缓升到 -0.07"
        final double slowAmount = slowFallingAmount(SLOW_FALLING_VANILLA_ADDITION);
        check(verbose, "缓降换算 -0.07(ADDITION) ⇒ -0.875(MULTIPLY_TOTAL)", -0.875D, slowAmount);
        final double slowFactor = 1.0D + slowAmount;
        check(verbose, "缓降倍率 = 0.125", 0.125D, slowFactor);
        check(verbose, "G=1 缓降重力 = 0.01（原版）", 0.01D,
                fn.apply(LIVING_BASE_GRAVITY, 1.0D) * slowFactor);
        check(verbose, "G=0.5 缓降重力 = 0.005", 0.005D,
                fn.apply(LIVING_BASE_GRAVITY, 0.5D) * slowFactor);
        check(verbose, "G=0 缓降重力 = 0（不是 -0.07 的缓升）", 0.0D,
                fn.apply(LIVING_BASE_GRAVITY, 0.0D) * slowFactor);
        check(verbose, "G=-1 缓降重力 = -0.01（不是 -0.07 的强上升）", -0.01D,
                fn.apply(LIVING_BASE_GRAVITY, -1.0D) * slowFactor);
        check(verbose, "缓降换算对 0 幂等（没用药水时不改任何东西）", 0.0D, slowFallingAmount(0.0D));
        return failures;
    }

    /** 【故意写坏】的负对照：把"乘 G"漏掉 ⇒ 自检必须判红（证明判据有牙）。 */
    public static double mutantNoScale(double base, double ignoredMult) {
        return base;
    }

    private static void check(boolean verbose, String what, double expect, double actual) {
        checks++;
        final boolean ok = Double.compare(expect, actual) == 0 || Math.abs(expect - actual) < 1e-12D;
        if (!ok) {
            failures++;
        }
        if (verbose || !ok) {
            OUT.println((ok ? "  [ok]   " : "  [FAIL] ") + what
                    + "  期望=" + expect + " 实得=" + actual);
        }
    }

    private static void check(ScaleFn fn, boolean verbose, String what, double expect, double actual) {
        check(verbose, what + " [" + (fn == null ? "?" : "impl") + "]", expect, actual);
    }

    private static final java.io.PrintStream OUT = System.out;

    /**
     * 离线自检入口：{@code java -cp <classes> com.shanhai.add.gravity.ShanhaiGravity}
     *
     * <p>跑两遍：① 正样本（{@link #scaleFor}）必须 PASS；② 负对照（{@link #mutantNoScale}）必须 FAIL。
     * 第二遍红、第一遍绿，才说明"这套判据真的能抓到不乘 G 的实现"。
     */
    public static void main(String[] args) {
        OUT.println("=== 山海 · 全量调制重力 纯算术自检 ===");
        OUT.println("口径: 每个施加点 = 各自基准值 × G; G=1 逐字等于原版, G=0 全归零, G<0 反向");
        OUT.println("施加点: " + SITE_BASES.length + " 个; 倍率样本: " + java.util.Arrays.toString(CHECK_MULTS));
        OUT.println();
        OUT.println("── ① 正样本: scaleFor(base, G) = base * G ──");
        final int failPositive = runSelfCheck(ShanhaiGravity::scaleFor, false);
        OUT.println("  总检查点=" + checks + " 失败=" + failPositive);
        final boolean positivePass = failPositive == 0;
        OUT.println("  PASS=" + positivePass);
        OUT.println();
        OUT.println("── ② 负对照: 故意漏掉 *G 的实现（必须判红）──");
        final int failMutant = runSelfCheck(ShanhaiGravity::mutantNoScale, false);
        OUT.println("  总检查点=" + checks + " 失败=" + failMutant);
        final boolean mutantCaught = failMutant > 0;
        OUT.println("  PASS=" + mutantCaught + " （true = 判据抓到了这个坏实现）");
        OUT.println();
        final boolean all = positivePass && mutantCaught;
        OUT.println("SELFCHECK positivePASS=" + positivePass + " negativeCaught=" + mutantCaught
                + " PASS=" + all);
        if (!all) {
            System.exit(1);
        }
    }
}
