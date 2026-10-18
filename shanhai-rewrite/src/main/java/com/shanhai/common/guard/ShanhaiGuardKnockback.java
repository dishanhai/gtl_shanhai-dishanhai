package com.shanhai.common.guard;

/**
 * 山海 · 创始现实修改模块「<b>反击退 ＋ 反挤压</b>」的<b>纯逻辑核</b>（零 Minecraft 依赖 ⇒ 离线可跑）。
 *
 * <h2>🔴 用户口径（2026-10-17 改口径后，逐字保留）</h2>
 * <pre>
 *   「我还需要反击退（包括碰撞箱挤压我），这个猫把我扔来扔去的」
 *   「我不想做拉回，会误伤很多别的 mod 的东西」
 *   「不要做：遍历实体推算"谁在挤我"；只用上面三条判据」
 * </pre>
 * ⇒ <b>只改速度 / 只改标志，一个坐标都不搬</b>（本类里没有 {@code setPos}、没有位置快照、没有位置回滚，
 * 也没有 {@code isInWall}）。卡墙回退那一段已按新口径<b>整段删掉</b>（那也是"搬人"）。
 *
 * <h2>三条判据（每 server tick，只对【受保护玩家】）</h2>
 * <ol>
 *   <li><b>清零击退意图</b>：{@code Entity#hurtMarked == true} ⇒ 置 {@code false}。
 *       为什么这一条有用（读原版源码得到的，不是猜）：{@code ServerEntity#sendChanges()} 的
 *       {@code if (this.entity.hurtMarked) { broadcastAndSend(new ClientboundSetEntityMotionPacket(entity)); hurtMarked = false; }}
 *       会把实体当前速度当"击退"发给客户端 ⇒ 先把这个意图清掉，客户端就不会收到那一次击退速度。</li>
 *   <li><b>窗口内刹车</b>：只有"我们最近 {@value #INTERCEPT_WINDOW_TICKS} tick 内确实拦过东西"
 *       并且水平速度 {@code hypot(dx, dz) > }{@value #BRAKE_THRESHOLD} 格/tick 时，
 *       才把 {@code deltaMovement} 的 <b>x/z 清零、y 原样保留</b>。</li>
 *   <li><b>窗口口径</b>：拦到伤害 ／ 拒死 ／ 补血 ／ 拦移除 <b>任一</b>都算（见
 *       {@code ShanhaiGuardEvents#noteIntercept}）。⇒ 别的 mod 的弹射、跳板、技能推人
 *       （<b>没有攻击事件</b>）根本不会让窗口打开 ⇒ 我们碰不到它们 —— 这就是"误伤面最小"的实现方式。</li>
 * </ol>
 *
 * <h2>四条豁免（写进代码，也写进报告）</h2>
 * <pre>
 *   鞘翅飞行  LivingEntity#isFallFlying()
 *   在载具里  Entity#getVehicle() != null ／ Entity#isPassenger()
 *   旁观者    Player#isSpectator()
 *   刚传送    换维度 ／ 复活（实体 id 变了）／ ServerPlayer#isChangingDimension() 之后的 2 tick
 * </pre>
 *
 * <h2>为什么阈值是 {@value #BRAKE_THRESHOLD} 而不是 0</h2>
 * 原版正常移动的水平速度都远低于它：<b>走路 ≈ 0.2</b>、<b>冲刺 ≈ 0.3</b>、<b>跳跃水平 ≈ 0.5</b> 格/tick
 * ⇒ 阈值取 0.6 时这三种<b>原样通过</b>（自检里有 3 条判据盯着）。
 * 把阈值改成 0.0 时，走路 0.2 就会当场被判"要刹车"—— 自检里<b>专门留了一条负对照</b>证明这件事
 * （说明判据真的在生效，也说明为什么生产值不许用 0）。
 *
 * <h2>🔴 不依赖任何对手 mod 的类名 / 方法名</h2>
 * 上面三条判据用到的全部是<b>原版状态与几何量</b>：
 * {@code hurtMarked} ／ {@code getDeltaMovement()/setDeltaMovement(Vec3)} ／ {@code isSpectator()} ／
 * {@code isFallFlying()} ／ {@code getVehicle()}（可见性全部经 javap 确认，见报告）。
 */
public final class ShanhaiGuardKnockback {

    private ShanhaiGuardKnockback() {}

    // ═══════════════════════════════ 配置键（与 ShanhaiConfig 里 .define(…) 的串一致） ═══════════════════════════════

    /** 主开关在 {@code config/shanhai-common.toml} 里的键。 */
    public static final String KEY_MASTER = "guard_module.knockback_guard";
    /** 左下角播报开关的键（默认 false ⇒ 不播）。 */
    public static final String KEY_BROADCAST = "guard_module.knockback_broadcast";

    /** 主开关出厂默认值：<b>开</b>（用户口径）。 */
    public static final boolean DEFAULT_MASTER = true;
    /** 左下角播报出厂默认值：<b>关</b>（用户口径「默认不播，避免刷屏」）。 */
    public static final boolean DEFAULT_BROADCAST = false;

    // ═══════════════════════════════ 判据常数（唯一一份） ═══════════════════════════════

    /**
     * 刹车阈值：<b>水平速度</b>（{@code hypot(dx, dz)}）严格大于它才刹车，单位<b>格/tick</b>。
     *
     * <p>0.6 的来由：原版走路 ≈0.2 ／ 冲刺 ≈0.3 ／ 跳跃水平 ≈0.5 都低于它 ⇒ 正常移动原样通过；
     * 而"被扔来扔去"的量级通常在 1 格/tick 以上。改小 = 更容易刹（也更可能误伤加速类移动），
     * 改到 0 = 走路都会被刹（自检的负对照就是这么证明的）。
     */
    public static final double BRAKE_THRESHOLD = 0.6;

    /**
     * 「刚被攻击」窗口长度（tick）：只有在<b>我们最近这么多个 tick 内确实拦过东西</b>时，第 2 条判据才生效。
     *
     * <p>这是"误伤面最小"那条口径的实现：拦伤害 ／ 拒死 ／ 补血 ／ 拦移除 任一都会把窗口重新打开；
     * 别的 mod 的弹射、跳板、技能推人没有这些事件 ⇒ 窗口一直关着 ⇒ 一条都不刹车。
     */
    public static final int INTERCEPT_WINDOW_TICKS = 20;

    /** 换维度 ／ 复活之后要跳过的 tick 数（"刚传送"豁免）。 */
    public static final int TELEPORT_SKIP_TICKS = 2;

    /** 「从来没有拦过」的哨兵值（{@link Long#MIN_VALUE} 直接参与减法会溢出，所以用它的四分之一）。 */
    public static final long NEVER = Long.MIN_VALUE / 2L;

    // ═══════════════════════════════ 四条豁免 ═══════════════════════════════

    /** 四种"不许刹车"的豁免（日志里用 {@link #label()} 那一串）。 */
    public enum Exempt {

        /** 没有豁免 ⇒ 正常判。 */
        NONE("none"),
        /** 旁观者。 */
        SPECTATOR("spectator"),
        /** 鞘翅飞行。 */
        FALL_FLYING("fall_flying"),
        /** 在载具里（骑乘中）。 */
        IN_VEHICLE("vehicle"),
        /** 刚传送（换维度 ／ 复活 ／ {@code isChangingDimension()} 之后的 2 tick）。 */
        JUST_TELEPORTED("just_teleported");

        private final String label;

        Exempt(String label) {
            this.label = label;
        }

        /** 日志里那一小串（纯 ASCII）。 */
        public String label() {
            return label;
        }
    }

    /**
     * 按<b>原版读数</b>挑出豁免（顺序 = 日志里看到的那个顺序，先命中先返回）。
     *
     * <p>四个参数全部来自原版方法：{@code Player#isSpectator()} ／ {@code LivingEntity#isFallFlying()} ／
     * {@code Entity#getVehicle()} ／ （换维度 ／ 复活 ／ {@code ServerPlayer#isChangingDimension()}
     * 由 {@link PlayerState#observe} 记出来的"刚刚传送过"）。
     */
    public static Exempt exemptOf(boolean spectator, boolean fallFlying, boolean inVehicle, boolean justTeleported) {
        if (spectator) {
            return Exempt.SPECTATOR;
        }
        if (fallFlying) {
            return Exempt.FALL_FLYING;
        }
        if (inVehicle) {
            return Exempt.IN_VEHICLE;
        }
        if (justTeleported) {
            return Exempt.JUST_TELEPORTED;
        }
        return Exempt.NONE;
    }

    // ═══════════════════════════════ ① 清零击退意图 ═══════════════════════════════

    /**
     * ① 这次要不要把 {@code hurtMarked} 置 false。
     *
     * <pre>
     *   开关关                     ⇒ false（关掉后本功能完全不动玩家）
     *   不是受保护玩家             ⇒ false（一个字段都不碰）
     *   hurtMarked == false        ⇒ false（本来就没击退意图）
     *   受保护 ＋ hurtMarked=true  ⇒ true
     * </pre>
     */
    public static ShanhaiGuardCore.Decision hurtMarkedDecision(boolean guarded, boolean hurtMarked, boolean enabled) {
        if (!enabled) {
            return ShanhaiGuardCore.Decision.of(false, "开关关闭（knockback_guard=false）⇒ 不清 hurtMarked");
        }
        if (!guarded) {
            return ShanhaiGuardCore.Decision.of(false, "不是受保护玩家 ⇒ 不清 hurtMarked（一个字段都不碰）");
        }
        if (!hurtMarked) {
            return ShanhaiGuardCore.Decision.of(false, "hurtMarked=false（本来就没有击退意图）⇒ 不动");
        }
        return ShanhaiGuardCore.Decision.of(true,
                "受保护玩家 ＋ hurtMarked=true ⇒ 置 false（"
                        + "原版 ServerEntity#sendChanges 会把它当击退速度发给客户端）");
    }

    /** 同上（只要结论）。 */
    public static boolean shouldClearHurtMarked(boolean guarded, boolean hurtMarked, boolean enabled) {
        return hurtMarkedDecision(guarded, hurtMarked, enabled).yes;
    }

    // ═══════════════════════════════ ③ 窗口 ═══════════════════════════════

    /**
     * 「刚被攻击」窗口开着吗。
     *
     * @param nowTick           现在（服务端 {@code level.getGameTime()}）
     * @param lastInterceptTick 最近一次"我们确实拦到东西"的 tick（从没拦过 = {@link #NEVER}）
     * @return {@code nowTick - lastInterceptTick <= INTERCEPT_WINDOW_TICKS}（含边界；从没拦过 ⇒ false）
     */
    public static boolean inWindow(long nowTick, long lastInterceptTick) {
        if (lastInterceptTick == NEVER) {
            return false;
        }
        final long diff = nowTick - lastInterceptTick;
        if (diff < 0L) {
            return false;   // 时间倒流（换维度/重载把 gameTime 换了一套）⇒ 当作没拦过，不猜
        }
        return diff <= (long) INTERCEPT_WINDOW_TICKS;
    }

    // ═══════════════════════════════ ② 刹车 ═══════════════════════════════

    /** 水平速度 = {@code hypot(dx, dz)}（格/tick）；任一入参是 NaN ⇒ NaN（调用方必须当"不刹"处理）。 */
    public static double horizontalSpeed(double dx, double dz) {
        return Math.hypot(dx, dz);
    }

    /**
     * ② 这次要不要<b>只把 x/z 清零</b>（y 原样保留）—— 生产阈值版（{@value #BRAKE_THRESHOLD}）。
     *
     * @param dx         {@code player.getDeltaMovement().x}
     * @param dz         {@code player.getDeltaMovement().z}
     * @param windowOpen 「刚被攻击」窗口是否开着（{@link #inWindow}）
     * @param exempt     四条豁免（{@link #exemptOf}）
     * @param enabled    主开关
     */
    public static ShanhaiGuardCore.Decision brakeDecision(double dx, double dz, boolean windowOpen,
                                                         Exempt exempt, boolean enabled) {
        return brakeDecision(dx, dz, windowOpen, exempt, enabled, BRAKE_THRESHOLD);
    }

    /**
     * ② 同一套判据，<b>阈值可注入</b>（只为离线自检的负对照存在：阈值喂 0.0 ⇒ 走路必须被判"要刹"）。
     *
     * <pre>
     *   开关关                       ⇒ false（关掉后本功能完全不动玩家）
     *   有豁免（鞘翅/载具/旁观/刚传送）⇒ false（写进代码的四条豁免）
     *   窗口没开                     ⇒ false（← 别的 mod 的弹射/跳板/技能全部走这一条）
     *   速度是 NaN / Infinity        ⇒ false（怪值不碰，宁可不刹）
     *   水平速度 &gt; 阈值             ⇒ true（只清 x/z，y 保留）
     *   其余                         ⇒ false（走路 0.2 / 冲刺 0.3 / 跳跃水平 0.5 都在这里）
     * </pre>
     */
    public static ShanhaiGuardCore.Decision brakeDecision(double dx, double dz, boolean windowOpen,
                                                         Exempt exempt, boolean enabled, double threshold) {
        final Exempt ex = exempt == null ? Exempt.NONE : exempt;
        final double speed = horizontalSpeed(dx, dz);
        final String readings = "dx=" + dx + " dz=" + dz + " 水平速度=" + speed
                + " 阈值=" + threshold + " 窗口=" + windowOpen + " 豁免=" + ex.label();
        if (!enabled) {
            return ShanhaiGuardCore.Decision.of(false, "开关关闭（knockback_guard=false）⇒ 不刹车（" + readings + "）");
        }
        if (ex != Exempt.NONE) {
            return ShanhaiGuardCore.Decision.of(false, "豁免 " + ex.label() + " ⇒ 不刹车（" + readings + "）");
        }
        if (!windowOpen) {
            return ShanhaiGuardCore.Decision.of(false,
                    "窗口没开（最近 " + INTERCEPT_WINDOW_TICKS + " tick 内没有拦过任何东西）⇒ 不刹车（" + readings + "）");
        }
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            return ShanhaiGuardCore.Decision.of(false, "速度读数异常（" + speed + "）⇒ 不刹车（" + readings + "）");
        }
        if (speed > threshold) {
            return ShanhaiGuardCore.Decision.of(true,
                    "窗口内 ＋ 水平速度 " + speed + " > " + threshold + " ⇒ 把 deltaMovement 的 x/z 清零（y 保留）");
        }
        return ShanhaiGuardCore.Decision.of(false,
                "窗口内但水平速度 " + speed + " 未超过 " + threshold + " ⇒ 不刹车（" + readings + "）");
    }

    /** 同上（只要结论；生产阈值）。 */
    public static boolean shouldBrake(double dx, double dz, boolean windowOpen, Exempt exempt, boolean enabled) {
        return brakeDecision(dx, dz, windowOpen, exempt, enabled).yes;
    }

    // ═══════════════════════════════ 逐玩家状态（窗口记账 ＋ 传送豁免） ═══════════════════════════════

    /**
     * 一个受保护玩家的反击退状态（<b>只在服务端 tick 里读写</b>；键 = 玩家 UUID）。
     *
     * <p>🔴 这里<b>不存坐标</b>：本功能按新口径只改速度与标志，坐标一个都不记（也就没有"拉回"可言）。
     */
    public static final class PlayerState {

        private long lastInterceptTick = NEVER;
        private int lastEntityId = Integer.MIN_VALUE;
        private String lastDimension = null;
        private int teleportSkipTicks = 0;
        private long intercepts = 0L;
        private long hurtMarkedCleared = 0L;
        private long braked = 0L;

        /** 记一次"我们确实拦到了东西"（伤害 ／ 死亡 ／ 补血 ／ 拦移除 ／ 拒死 任一）。 */
        public void noteIntercept(long nowTick) {
            this.lastInterceptTick = nowTick;
            this.intercepts++;
        }

        /** 「刚被攻击」窗口开着吗。 */
        public boolean windowOpen(long nowTick) {
            return inWindow(nowTick, lastInterceptTick);
        }

        /** 最近一次拦截的 tick（日志用）。 */
        public long lastInterceptTick() {
            return lastInterceptTick;
        }

        /**
         * 每 tick 开头调一次：认出「换维度 ／ 复活（实体 id 变了）／ {@code isChangingDimension()}」，
         * 并<b>消耗</b>掉 {@value ShanhaiGuardKnockback#TELEPORT_SKIP_TICKS} tick 的"刚传送"豁免。
         *
         * @return true = 本 tick 属于「刚传送」豁免窗口（调用方据此给 {@code Exempt.JUST_TELEPORTED}）
         */
        public boolean observe(int entityId, String dimensionKey, boolean changingDimension) {
            final boolean respawned = lastEntityId != Integer.MIN_VALUE && entityId != lastEntityId;
            final boolean changedDimension = lastDimension != null && dimensionKey != null
                    && !lastDimension.equals(dimensionKey);
            lastEntityId = entityId;
            lastDimension = dimensionKey;
            if (changingDimension || respawned || changedDimension) {
                teleportSkipTicks = TELEPORT_SKIP_TICKS;
            }
            if (teleportSkipTicks > 0) {
                teleportSkipTicks--;
                return true;
            }
            return false;
        }

        /** 还欠几个 tick 的"刚传送"豁免（读数用）。 */
        public int teleportSkipRemaining() {
            return teleportSkipTicks;
        }

        public void noteHurtMarkedCleared() {
            hurtMarkedCleared++;
        }

        public void noteBraked() {
            braked++;
        }

        public long intercepts() {
            return intercepts;
        }

        public long hurtMarkedCleared() {
            return hurtMarkedCleared;
        }

        public long braked() {
            return braked;
        }
    }

    // ═══════════════════════════════ 左下角播报（默认不播） ═══════════════════════════════

    /**
     * 左下角（action bar）那一行的正文（**默认不播**，只有 {@code knockback_broadcast=true} 时才发）。
     *
     * <p>与聊天栏那套（{@code ShanhaiGuardCore#chatPhrase}）分开：本类这一条<b>不进聊天栏</b>
     * （用户口径「不播报」），要播就播左下角，并且同样"每秒最多一行"。
     */
    public static String broadcastText(long count) {
        final long n = count < 1L ? 1L : count;
        return ShanhaiGuardCore.CHAT_PREFIX + "BRAKED setDeltaMovement (x=0,z=0; y kept) x" + n;
    }

    // ═══════════════════════════════ 读数（/shanhai guard 与日志用） ═══════════════════════════════

    /** 一行总表（命令回显 / 报告都用它）。 */
    public static String statusLine() {
        return "knockback_guard on=" + ShanhaiGuardSwitches.knockbackGuard()
                + "（默认 " + DEFAULT_MASTER + "）"
                + " broadcast=" + ShanhaiGuardSwitches.knockbackBroadcast()
                + "（默认 " + DEFAULT_BROADCAST + "）"
                + " 窗口=" + INTERCEPT_WINDOW_TICKS + " tick 阈值=" + BRAKE_THRESHOLD + " 格/tick"
                + " 刚传送豁免=" + TELEPORT_SKIP_TICKS + " tick"
                + " ／ 累计 清 hurtMarked=" + TOTAL_HURT_MARKED_CLEARED.get()
                + " 刹车=" + TOTAL_BRAKED.get();
    }

    /** 累计清掉 hurtMarked 的次数（全局读数；每玩家读数在 {@link PlayerState}）。 */
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_HURT_MARKED_CLEARED =
            new java.util.concurrent.atomic.AtomicLong();

    /** 累计刹车的次数（全局读数）。 */
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_BRAKED =
            new java.util.concurrent.atomic.AtomicLong();

    public static void noteHurtMarkedCleared() {
        TOTAL_HURT_MARKED_CLEARED.incrementAndGet();
    }

    public static void noteBraked() {
        TOTAL_BRAKED.incrementAndGet();
    }

    public static long hurtMarkedClearedTotal() {
        return TOTAL_HURT_MARKED_CLEARED.get();
    }

    public static long brakedTotal() {
        return TOTAL_BRAKED.get();
    }

    /** 归零（离线自检 / 换局用）。 */
    public static void resetCounters() {
        TOTAL_HURT_MARKED_CLEARED.set(0L);
        TOTAL_BRAKED.set(0L);
    }

    // ═══════════════════════════════ 离线自检（同文件 main） ═══════════════════════════════
    //
    // 运行方式（不需要游戏、不需要 Forge）：
    //   java -cp build\classes\java\main com.shanhai.common.guard.ShanhaiGuardKnockback
    //
    // 🔴 两条负对照是用户点名的红线：
    //   ① 把窗口关掉（当作永不命中）⇒ 第 2 条必须【一条都不触发】（证明别的 mod 的弹射/跳板不会被误伤）
    //   ② 把阈值改成 0.0 ⇒ 普通走路【必须被误伤】（证明判据真的在生效，并说明生产值为什么用 0.6）

    /** 输出：强制 UTF-8 写 stdout（Windows 控制台默认 GBK，会把中文读数打成乱码 ⇒ 证据就不成立）。 */
    private static final java.io.PrintStream OUT = utf8Stdout();

    private static java.io.PrintStream utf8Stdout() {
        try {
            return new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        } catch (Throwable t) {
            return System.out;
        }
    }

    private static int checks = 0;
    private static int reds = 0;

    /** 一条判据：{@code expected == actual} 才算过；{@code reading} 一律打印<b>真实读数</b>。 */
    private static void expect(String group, String name, boolean expected, boolean actual, String reading) {
        checks++;
        final boolean ok = (expected == actual);
        if (!ok) {
            reds++;
        }
        OUT.println(String.format("%-4s | %-4s | %-46s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", group, name, expected, actual, reading));
    }

    private static void note(String s) {
        OUT.println("       · " + s);
    }

    /**
     * 🔴 负对照①的判据本体：<b>窗口关着的时候，任何速度都不许触发刹车</b>。
     *
     * <p>喂进来的 {@code brake} 就是被测的那个实现（正样本 = 本工程实现；突变体 = 故意写坏的那个），
     * 这样"判据真有牙"是被<b>另一个实现</b>证明的，不是自己说自己。
     */
    private interface BrakeFn {
        boolean apply(double dx, double dz, boolean windowOpen, Exempt exempt, boolean enabled, double threshold);
    }

    private static final double[] SPEEDS = { 0.0, 0.2, 0.3, 0.5, 0.6, 0.9, 1.5, 3.0, 10.0 };

    /** 窗口关着 ⇒ 上面每一个速度都不许刹（含"被别的 mod 弹射 10 格/tick"这种）。 */
    private static boolean neverBrakesWhenWindowClosed(BrakeFn fn, double threshold) {
        for (final double s : SPEEDS) {
            if (fn.apply(s, s, false, Exempt.NONE, true, threshold)) {
                return false;
            }
        }
        return true;
    }

    /** 给定阈值 ⇒ 走路 0.2 / 冲刺 0.3 / 跳跃水平 0.5 是否<b>全部</b>被判"要刹车"。 */
    private static boolean brakesWalkingWithThreshold(BrakeFn fn, double threshold) {
        for (final double s : new double[] { 0.2, 0.3, 0.5 }) {
            if (!fn.apply(s, 0.0, true, Exempt.NONE, true, threshold)) {
                return false;
            }
        }
        return true;
    }

    /** 阈值 0.0 ⇒ 走路 0.2 / 冲刺 0.3 / 跳跃 0.5 必须<b>全部</b>被判"要刹车"（= 误伤红利那条红线）。 */
    private static boolean brakesWalkingWhenThresholdZero(BrakeFn fn) {
        return brakesWalkingWithThreshold(fn, 0.0);
    }

    /** 【故意写坏】窗口永远当成开着（不管拦没拦过）⇒ 负对照①必须把它抓出来。 */
    private static boolean mutantWindowAlwaysOpen(double dx, double dz, boolean windowOpen, Exempt exempt,
                                                  boolean enabled, double threshold) {
        return enabled && exempt == Exempt.NONE && Math.hypot(dx, dz) > threshold;
    }

    public static void main(String[] args) {
        OUT.println("=== 山海 · 反击退 / 反挤压（knockback_guard）离线自检 ===");
        OUT.println("· 口径：只改速度 ＋ 只改标志，**一个坐标都不搬**（本类里没有 setPos / 位置回滚 / isInWall）");
        OUT.println("· 判据：① hurtMarked 置 false  ② 窗口内且水平速度 > " + BRAKE_THRESHOLD
                + " ⇒ x/z 清零、y 保留  ③ 窗口 = 最近 " + INTERCEPT_WINDOW_TICKS + " tick 拦过东西");
        OUT.println("· 豁免：鞘翅飞行 ／ 在载具里 ／ 旁观者 ／ 刚传送（" + TELEPORT_SKIP_TICKS + " tick）");
        OUT.println();

        // ─────────── ① 清零击退意图 ───────────
        OUT.println("── ① 清零击退意图（Entity#hurtMarked → false）──");
        final ShanhaiGuardCore.Decision h1 = hurtMarkedDecision(true, true, true);
        expect("①", "受保护 ＋ hurtMarked=true ＋ 开关开 ⇒ 清掉", true, h1.yes, "→ " + h1.reason);
        final ShanhaiGuardCore.Decision h2 = hurtMarkedDecision(true, false, true);
        expect("①", "hurtMarked=false ⇒ 不动", false, h2.yes, "→ " + h2.reason);
        final ShanhaiGuardCore.Decision h3 = hurtMarkedDecision(false, true, true);
        expect("①", "不是受保护玩家 ⇒ 一个字段都不碰", false, h3.yes, "→ " + h3.reason);
        final ShanhaiGuardCore.Decision h4 = hurtMarkedDecision(true, true, false);
        expect("①", "开关关 ⇒ 一个字段都不碰", false, h4.yes, "→ " + h4.reason);
        OUT.println();

        // ─────────── ③ 窗口口径 ───────────
        OUT.println("── ③ 「刚被攻击」窗口（最近 " + INTERCEPT_WINDOW_TICKS + " tick 拦过东西）──");
        final PlayerState st = new PlayerState();
        expect("③", "从没拦过任何东西 ⇒ 窗口关着", false, st.windowOpen(1000L),
                "lastInterceptTick=" + st.lastInterceptTick() + "（哨兵 " + NEVER + "）");
        st.noteIntercept(1000L);
        expect("③", "拦到的那一刻 ⇒ 窗口开", true, st.windowOpen(1000L), "now=1000 last=1000 diff=0");
        expect("③", "拦到后第 19 tick（diff=19）⇒ 还开", true, st.windowOpen(1019L), "diff=19");
        expect("③", "拦到后第 20 tick（diff=20，边界含）⇒ 还开", true, st.windowOpen(1020L), "diff=20");
        expect("③", "拦到后第 21 tick（diff=21）⇒ 关了", false, st.windowOpen(1021L), "diff=21");
        expect("③", "时间倒流（now < last）⇒ 当作没拦过", false, inWindow(900L, 1000L), "now=900 last=1000");
        expect("③", "从没拦过（哨兵）⇒ 关", false, inWindow(1000L, NEVER), "last=" + NEVER);
        OUT.println();

        // ─────────── ② 正常移动不许被刹 ───────────
        OUT.println("── ② 正常移动必须原样通过（阈值 " + BRAKE_THRESHOLD + "）──");
        expect("②正向", "走路 0.2 格/tick ⇒ 不刹", false,
                shouldBrake(0.2, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(0.2, 0.0, true, Exempt.NONE, true).reason);
        expect("②正向", "冲刺 0.3 格/tick ⇒ 不刹", false,
                shouldBrake(0.3, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(0.3, 0.0, true, Exempt.NONE, true).reason);
        expect("②正向", "跳跃水平 0.5 格/tick ⇒ 不刹", false,
                shouldBrake(0.5, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(0.5, 0.0, true, Exempt.NONE, true).reason);
        expect("②正向", "斜向走路 (0.3, 0.3) ⇒ 合成 0.424 ⇒ 不刹", false,
                shouldBrake(0.3, 0.3, true, Exempt.NONE, true),
                "水平速度=" + horizontalSpeed(0.3, 0.3));
        OUT.println();

        // ─────────── ② 该刹的要刹 ───────────
        OUT.println("── ② 该刹的要刹（窗口内 ＋ 超阈值）──");
        expect("②负向", "击退量级 1.2 格/tick ⇒ 刹", true,
                shouldBrake(1.2, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(1.2, 0.0, true, Exempt.NONE, true).reason);
        expect("②负向", "被扔 3.0 格/tick ⇒ 刹", true,
                shouldBrake(0.0, 3.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(0.0, 3.0, true, Exempt.NONE, true).reason);
        expect("②边界", "正好 0.6（不严格大于）⇒ 不刹", false,
                shouldBrake(0.6, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(0.6, 0.0, true, Exempt.NONE, true).reason);
        expect("②边界", "0.60001 ⇒ 刹", true,
                shouldBrake(0.60001, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(0.60001, 0.0, true, Exempt.NONE, true).reason);
        expect("②边界", "斜向击退 (0.5, 0.5) 合成 0.707 ⇒ 刹", true,
                shouldBrake(0.5, 0.5, true, Exempt.NONE, true),
                "水平速度=" + horizontalSpeed(0.5, 0.5));
        expect("②边界", "NaN 速度 ⇒ 不刹（怪值不碰）", false,
                shouldBrake(Double.NaN, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(Double.NaN, 0.0, true, Exempt.NONE, true).reason);
        expect("②边界", "Infinity 速度 ⇒ 不刹（怪值不碰）", false,
                shouldBrake(Double.POSITIVE_INFINITY, 0.0, true, Exempt.NONE, true),
                "→ " + brakeDecision(Double.POSITIVE_INFINITY, 0.0, true, Exempt.NONE, true).reason);
        OUT.println();

        // ─────────── ② 四条豁免 ───────────
        OUT.println("── ② 四条豁免（速度 5.0、窗口开着，也必须一条都不刹）──");
        for (final Exempt ex : new Exempt[] { Exempt.SPECTATOR, Exempt.FALL_FLYING,
                Exempt.IN_VEHICLE, Exempt.JUST_TELEPORTED }) {
            expect("②豁免", "豁免 " + ex.label() + " ⇒ 不刹", false,
                    shouldBrake(5.0, 0.0, true, ex, true),
                    "→ " + brakeDecision(5.0, 0.0, true, ex, true).reason);
        }
        expect("②豁免", "exemptOf：旁观者优先", true,
                exemptOf(true, true, true, true) == Exempt.SPECTATOR,
                "读数=" + exemptOf(true, true, true, true).label());
        expect("②豁免", "exemptOf：没有豁免 ⇒ none", true,
                exemptOf(false, false, false, false) == Exempt.NONE,
                "读数=" + exemptOf(false, false, false, false).label());
        OUT.println();

        // ─────────── 开关关掉 ⇒ 完全不动 ───────────
        OUT.println("── 开关关掉（knockback_guard=false）⇒ 本功能完全不动玩家 ──");
        expect("开关", "开关关 ＋ 窗口开 ＋ 速度 10 ⇒ 不刹", false,
                shouldBrake(10.0, 0.0, true, Exempt.NONE, false),
                "→ " + brakeDecision(10.0, 0.0, true, Exempt.NONE, false).reason);
        expect("开关", "开关关 ＋ 速度 10 ＋ 阈值 0.0 ⇒ 仍不刹", false,
                brakeDecision(10.0, 0.0, true, Exempt.NONE, false, 0.0).yes,
                "→ " + brakeDecision(10.0, 0.0, true, Exempt.NONE, false, 0.0).reason);
        OUT.println();

        // ─────────── 🔴 负对照①：窗口关掉 ⇒ 一条都不触发 ───────────
        OUT.println("── 🔴 负对照①（用户点名）：把窗口关掉（当作永不命中）⇒ 第 2 条必须一条都不触发 ──");
        final BrakeFn real = (dx, dz, win, ex, en, th) -> brakeDecision(dx, dz, win, ex, en, th).yes;
        expect("负对照①", "本工程实现：窗口关 ⇒ 9 个速度（含 10.0）全不刹", true,
                neverBrakesWhenWindowClosed(real, BRAKE_THRESHOLD),
                "速度表=" + java.util.Arrays.toString(SPEEDS));
        expect("负对照①", "判据有牙：突变体「窗口永远开着」⇒ 同一条判据报红", false,
                neverBrakesWhenWindowClosed(ShanhaiGuardKnockback::mutantWindowAlwaysOpen, BRAKE_THRESHOLD),
                "→ 突变体在窗口关时仍然会刹 ⇒ " + !neverBrakesWhenWindowClosed(
                        ShanhaiGuardKnockback::mutantWindowAlwaysOpen, BRAKE_THRESHOLD));
        note("⇒ 别的 mod 的弹射 / 跳板 / 技能推人【没有攻击事件】⇒ 窗口不会打开 ⇒ 我们碰不到它们");
        OUT.println();

        // ─────────── 🔴 负对照②：阈值 0.0 ⇒ 走路必须被误伤 ───────────
        OUT.println("── 🔴 负对照②（用户点名）：把阈值改成 0.0 ⇒ 普通走路必须被误伤 ──");
        expect("负对照②", "阈值 0.0 ＋ 走路 0.2 ⇒ 被判「要刹车」（= 误伤）", true,
                brakesWalkingWhenThresholdZero(real),
                "走路 0.2=" + real.apply(0.2, 0.0, true, Exempt.NONE, true, 0.0)
                        + " ／ 冲刺 0.3=" + real.apply(0.3, 0.0, true, Exempt.NONE, true, 0.0)
                        + " ／ 跳跃 0.5=" + real.apply(0.5, 0.0, true, Exempt.NONE, true, 0.0));
        expect("负对照②", "生产阈值 " + BRAKE_THRESHOLD + " ＋ 同样三种速度 ⇒ 全不刹（对照）", false,
                brakesWalkingWithThreshold(real, BRAKE_THRESHOLD),
                "同一判据、阈值换成生产值 " + BRAKE_THRESHOLD + " ⇒ "
                        + brakesWalkingWithThreshold(real, BRAKE_THRESHOLD));
        expect("负对照②", "判据有牙：阈值 0.0 时【站着不动】(0,0) 仍不刹（不是见谁刹谁）", false,
                brakeDecision(0.0, 0.0, true, Exempt.NONE, true, 0.0).yes,
                "→ " + brakeDecision(0.0, 0.0, true, Exempt.NONE, true, 0.0).reason);
        note("⇒ 生产值用 " + BRAKE_THRESHOLD + "：走路 0.2 ／ 冲刺 0.3 ／ 跳跃水平 0.5 全部低于它；"
                + "阈值取 0 会把正常走路当场刹住（这条负对照就是证明判据在生效）");
        OUT.println();

        // ─────────── PlayerState：换维度 / 复活 的"刚传送"豁免 ───────────
        OUT.println("── ③ 「刚传送」豁免：换维度 ／ 复活 之后 " + TELEPORT_SKIP_TICKS + " tick 内跳过 ──");
        final PlayerState ps = new PlayerState();
        expect("传送豁免", "第一 tick 正常观察 ⇒ 不是刚传送", false,
                ps.observe(7, "minecraft:overworld", false), "entityId=7 dim=overworld");
        expect("传送豁免", "换维度那一 tick ⇒ 刚传送", true,
                ps.observe(7, "minecraft:the_nether", false), "dim=overworld → the_nether");
        expect("传送豁免", "下一 tick ⇒ 仍在豁免里（第 2 tick）", true,
                ps.observe(7, "minecraft:the_nether", false), "remaining=" + ps.teleportSkipRemaining());
        expect("传送豁免", "第 3 tick ⇒ 豁免用完", false,
                ps.observe(7, "minecraft:the_nether", false), "remaining=" + ps.teleportSkipRemaining());
        expect("传送豁免", "复活（实体 id 变了）⇒ 刚传送", true,
                ps.observe(99, "minecraft:the_nether", false), "entityId 7 → 99");
        ps.observe(99, "minecraft:the_nether", false);
        ps.observe(99, "minecraft:the_nether", false);
        expect("传送豁免", "isChangingDimension()=true ⇒ 刚传送", true,
                ps.observe(99, "minecraft:the_nether", true), "changingDimension=true");
        ps.observe(99, "minecraft:the_nether", false);
        ps.observe(99, "minecraft:the_nether", false);
        expect("传送豁免", "没变化 ⇒ 不再豁免", false,
                ps.observe(99, "minecraft:the_nether", false), "remaining=" + ps.teleportSkipRemaining());
        OUT.println();

        // ─────────── 聊天表：诊断类不播报 ───────────
        OUT.println("── ⑥ 播报口径：默认不播（聊天表这一路不扩）──");
        expect("⑥播报", "KIND_KNOCKBACK 不进聊天栏（chatPhrase=null）", true,
                ShanhaiGuardCore.chatPhrase(ShanhaiGuardCore.KIND_KNOCKBACK, 3L) == null,
                "→ " + ShanhaiGuardCore.chatPhrase(ShanhaiGuardCore.KIND_KNOCKBACK, 3L));
        expect("⑥播报", "KIND_KNOCKBACK 也没有聊天机制名（chatMechanism=null）", true,
                ShanhaiGuardCore.chatMechanism(ShanhaiGuardCore.KIND_KNOCKBACK) == null,
                "→ " + ShanhaiGuardCore.chatMechanism(ShanhaiGuardCore.KIND_KNOCKBACK));
        expect("⑥播报", "左下角正文（要开 knockback_broadcast 才发）含机制名与 xN", true,
                broadcastText(3L).startsWith(ShanhaiGuardCore.CHAT_PREFIX)
                        && broadcastText(3L).contains("setDeltaMovement") && broadcastText(3L).endsWith("x3"),
                "→ " + broadcastText(3L));
        expect("⑥播报", "正文纯 ASCII（前缀那六个汉字是唯一豁免）", true,
                isAsciiOnly(broadcastText(1L).substring(ShanhaiGuardCore.CHAT_PREFIX.length())),
                "→ " + broadcastText(1L).substring(ShanhaiGuardCore.CHAT_PREFIX.length()));
        OUT.println();

        // ─────────── 读数／计数 ───────────
        OUT.println("── 读数与计数（/shanhai guard 回显的那一行）──");
        resetCounters();
        noteHurtMarkedCleared();
        noteHurtMarkedCleared();
        noteBraked();
        expect("读数", "全局计数：清 hurtMarked=2 ／ 刹车=1", true,
                hurtMarkedClearedTotal() == 2L && brakedTotal() == 1L,
                "cleared=" + hurtMarkedClearedTotal() + " braked=" + brakedTotal());
        final PlayerState cs = new PlayerState();
        cs.noteIntercept(50L);
        cs.noteIntercept(60L);
        cs.noteHurtMarkedCleared();
        cs.noteBraked();
        expect("读数", "逐玩家：拦截 2 次 ／ 清 hurtMarked 1 次 ／ 刹车 1 次", true,
                cs.intercepts() == 2L && cs.hurtMarkedCleared() == 1L && cs.braked() == 1L,
                "intercepts=" + cs.intercepts() + " cleared=" + cs.hurtMarkedCleared()
                        + " braked=" + cs.braked());
        expect("读数", "常数口径逐字：阈值 0.6 ／ 窗口 20 ／ 豁免 2 tick ／ 默认开、播报默认关", true,
                BRAKE_THRESHOLD == 0.6 && INTERCEPT_WINDOW_TICKS == 20 && TELEPORT_SKIP_TICKS == 2
                        && DEFAULT_MASTER && !DEFAULT_BROADCAST,
                "threshold=" + BRAKE_THRESHOLD + " window=" + INTERCEPT_WINDOW_TICKS
                        + " skip=" + TELEPORT_SKIP_TICKS + " master=" + DEFAULT_MASTER
                        + " broadcast=" + DEFAULT_BROADCAST);
        resetCounters();
        OUT.println();

        OUT.println("=== 汇总：判据 " + checks + " 条，报红 " + reds + " 条 ⇒ "
                + (reds == 0 ? "全绿" : "有红 ✗") + " ===");
        OUT.flush();
        if (reds != 0) {
            System.exit(1);
        }
    }

    /** 纯 ASCII 吗（替身：只看 &gt;0x7F 的字符存不存在）。 */
    private static boolean isAsciiOnly(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
