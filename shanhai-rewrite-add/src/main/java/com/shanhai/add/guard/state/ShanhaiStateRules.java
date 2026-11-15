package com.shanhai.add.guard.state;

/**
 * 山海 · <b>「玩家状态由我们独占权威」的判据核</b>（<b>纯 JDK</b>：不认 MC、不认 mod、不认类名）。
 *
 * <h2>🔴 这个类存在的理由（用户口径）</h2>
 * <pre>
 *   「要么你就干脆保护玩家的代码不被修改啊」
 *   「不要写那种静态的 mixin」「他代码类换个名称，或者换个 mod，不就都失效了吗」
 *   「你不认识我，我也不认识你，我就知道你改了我的状态」
 * </pre>
 * ⇒ 判据只问一件事：<b>这一拍里，有没有我们之外的东西写了我认可的值</b>。
 * 🔴 2026-11-08 之后"我们之外"有了可观测的定义：{@code self_input}（玩家自己的移动包）与
 * {@code self_guard}（我们自己的写入）都算"自己"，其余才算外部
 * （旧版用 tick 边界猜归属，把玩家自己起跳 0.42 判成了外部写入 —— 用户当场否掉：
 * 「你为什么要把 tick 作为判据，我说了把是否为玩家自己的操作作为判据啊」）。
 * ⇒ 这里<b>没有</b>任何第三方类名、方法名、mod 名；也<b>没有</b>"动量超过 N 才拦"这类阈值判据
 * （用户明确否掉过阈值法）。动量判据 = <b>归属判据 ＋ 值层比对</b>：不是自己人写的、值又变了 = 外部。
 *
 * <h2>为什么判定必须住在这个「纯 JDK」类里</h2>
 * 本项目纪律「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」。
 * 判定若写在带 Minecraft 依赖的类里，离线自检只能<b>照抄一遍</b> ⇒ 抄错的是脚本还是生产代码就分不清了。
 * ⇒ 判定住在这里（{@code javac --release 17} 可单独编译并跑），带 MC 的类只负责"取值 / 写回 / 打日志"。
 * 游戏里跑的是<b>同一份字节码</b>，不存在第二套判据。
 *
 * <h2>四类值</h2>
 * <table border="1">
 *   <tr><th>方法</th><th>判据</th><th>阈值？</th></tr>
 *   <tr><td>{@link #momentumVerdict(boolean, boolean, ShanhaiSelfInput.Origin, double, double, double, double, double, double)}</td>
 *       <td><b>归属</b>（这次写入是不是玩家自己的操作）＋ 值是否逐位相等（含 NaN/Inf）
 *           ＋ 两条结构例外：豁免态重新学习、<b>归零不算攻击</b></td><td><b>没有</b></td></tr>
 *   <tr><td>{@link #positionVerdict(boolean, boolean, ShanhaiSelfInput.Origin, int, double, double, double, double, double, double, double)}</td>
 *       <td>归属 ＋ 距"玩家自己那次移动包"锚点的位移 vs「冲刺预算 × 经过 tick ＋ 认可动量贡献」</td>
 *       <td>有（{@link #SPRINT_BUDGET_PER_TICK}）—— 见下</td></tr>
 *   <tr><td>{@link #inventoryVerdict}</td>
 *       <td>窗口内一次少掉的非空槽 ≥ max(2, 30%)、且没有开容器</td><td>有（结构性 30%）</td></tr>
 * </table>
 *
 * <h2>🔴 为什么位置这一项<b>只能</b>用预算判据（诚实边界，写死）</h2>
 * 玩家的<b>位置</b>在 1.20.1 服务端本来就<b>不是</b>由 {@code deltaMovement} 推的：
 * 客户端每 tick 发 {@code ServerboundMovePlayerPacket}，服务端 {@code handleMovePlayer} 直接把玩家挪过去。
 * ⇒「这一 tick 有没有别人写过位置」这件事<b>在只有 API、不拦截的前提下不可观测</b>
 * （玩家的正常走路与别人的 {@code setPos} 长得一模一样）。
 * ⇒ 位置这一项退到「<b>窗口内位移超出人腿能走的范围</b>」＝ 复用工程既有口径
 * {@code 0.6 格/tick}（本体 {@code [guard_module] knockback_guard} 的配置注释里就是这组读数：
 * 走路约 0.2、冲刺约 0.3、跳跃水平约 0.5 格/tick）。
 * ⚠️ 它是阈值，且只作用于<b>位置</b>、只在<b>窗口内</b>、只在<b>携带模块的玩家</b>身上；动量那一项没有阈值。
 *
 * <h2>动作词表（三种，避免"改/不改"二值把语义压扁）</h2>
 * <pre>
 *   KEEP    = （不用动）
 *   ADOPT   = 以现状为准（把现状记成新的认可值），相当于"这一拍重新对齐"
 *   RESTORE = 写回我们认可的值
 * </pre>
 */
public final class ShanhaiStateRules {

    private ShanhaiStateRules() {}

    /**
     * 人腿每 tick 能走的距离上限（= 工程既有口径 0.6 格/tick）。
     *
     * <p>⚠️ 只用于<b>位置</b>判据；动量判据一个阈值都不用。
     */
    public static final double SPRINT_BUDGET_PER_TICK = 0.6D;

    /** 浮点容差（只吸收 double 运算误差，**不是**玩法阈值）。 */
    public static final double EPSILON = 1.0e-9D;

    /** 物品栏"一次少掉这么多非空槽"才算被外部抹掉：占原来的 30%（下限 2 格）。 */
    public static final double INVENTORY_LOST_FRACTION = 0.30D;

    /** 动作词表。 */
    public enum Verdict {
        /** 不用动。 */
        KEEP,
        /** 以现状为准（重新学习认可值，不写回）。 */
        ADOPT,
        /** 写回我们认可的值。 */
        RESTORE
    }

    // ═══════════════════════════════ ① 动量（核心，无阈值） ═══════════════════════════════

    /**
     * 动量判据（<b>按"是不是玩家自己的操作"归属</b>，tick 边界只当采样时机）。
     *
     * <p>🔴 这是 2026-11-08 之后<b>唯一</b>的生产判据（用户原话：
     * 「反击退的话，你为什么要把 tick 作为判据，我说了把是否为玩家自己的操作作为判据啊」）。
     * 判定顺序本身就是口径，改动等于改功能：
     * <pre>
     *   ① 开关关            → KEEP     （一个字节都不碰）
     *   ② 值没变            → KEEP     （没变就是没变，无论来源）
     *   ③ 豁免态            → ADOPT    （旁观/载具/鞘翅/换维度/死亡/刚加入：不纠正，但记成新基准）
     *   ④ 这次写入是"自己的" → ADOPT    （self_input = 玩家移动包那一段；self_guard = 我们自己写的）
     *   ⑤ 被写成 0          → ADOPT    （归零不算攻击：本体传送拉回也写 0，两边不许互相打架）
     *   ⑥ 其余              → RESTORE  （外部写入）
     * </pre>
     *
     * @param enabled 该开关现在开着吗
     * @param exempt  这一拍处于豁免态
     * @param origin  这次写入的来源（{@link ShanhaiSelfInput#origin()} 读出来的那个值）
     * @param approved 我们认可的动量（玩家自己那一次写入之后的值 / 上一拍 END 的值）
     * @param now      现在读到的动量
     * @return {@link Verdict#ADOPT} ⇒ 调用方把 {@code now} 记成新的认可值；
     *         {@link Verdict#RESTORE} ⇒ 调用方写回 {@code approved} 并补一个
     *         {@code ClientboundSetEntityMotionPacket}
     */
    public static Verdict momentumVerdict(boolean enabled, boolean exempt, ShanhaiSelfInput.Origin origin,
                                          double approvedX, double approvedY, double approvedZ,
                                          double nowX, double nowY, double nowZ) {
        if (!enabled) {
            return Verdict.KEEP;
        }
        if (same3(approvedX, approvedY, approvedZ, nowX, nowY, nowZ)) {
            return Verdict.KEEP;
        }
        if (exempt) {
            return Verdict.ADOPT;
        }
        if (origin != null && origin.isSelf()) {
            // 🔴 用户点名的那一条：玩家自己起跳 0.42 / 自己冲刺 0.2 都是【自己的操作】⇒ 采纳
            return Verdict.ADOPT;
        }
        if (isZero3(nowX, nowY, nowZ)) {
            return Verdict.ADOPT;
        }
        return Verdict.RESTORE;
    }

    /**
     * 旧签名（<b>没有来源信息</b> = {@link ShanhaiSelfInput.Origin#NONE}）。
     *
     * <p>⚠️ 保留它只为了让 2026-11-08 那张真值表（{@code temp\momentum\offline\StateAuthorityOfflineCheck}）
     * 还能逐条复算；<b>生产代码一律走带 {@code origin} 的那个重载</b>。
     * 用这个重载调出来的 {@code RESTORE} 在真实游戏里会把"玩家自己起跳"判成外部写入 —— 那正是被用户否掉的行为。
     *
     * <p>语义（为什么这样切是安全的）：
     * <pre>
     *   玩家自己那一拍（{@code ServerPlayer.tick()}）里，原版会合法地改 deltaMovement（摩擦力/重力/碰撞）。
     *   那些改动发生在【窗口内】，我们在窗口 <b>END</b> 取到的值就是"原版跑完的合法结果"。
     *   两个窗口之间（别的实体 tick / 事件 / 网络包处理）没有任何原版逻辑该改玩家动量
     *   ⇒ 下一拍 <b>START</b> 上发现值变了 = 有别人在窗口之外写过。
     *   在 START（原版把速度积进位置之前）写回 ⇒ 服务端这一拍<b>一次都不会</b>用那个动量移动玩家。
     * </pre>
     *
     * @param enabled  该开关现在开着吗（关 ⇒ 一个字节都不碰）
     * @param exempt   这一拍处于豁免态（旁观/载具/鞘翅/刚传送/刚复活/死亡中）
     * @param approved 我们认可的动量（上一拍 END 的值，或我们/deltaMovement 侧自己写过的值）
     * @param now      现在读到的动量
     * @return {@link Verdict#RESTORE} ⇒ 调用方写回 {@code approved}；{@link Verdict#ADOPT} ⇒ 记成新的认可值
     */
    public static Verdict momentumVerdict(boolean enabled, boolean exempt,
                                          double approvedX, double approvedY, double approvedZ,
                                          double nowX, double nowY, double nowZ) {
        return momentumVerdict(enabled, exempt, ShanhaiSelfInput.Origin.NONE,
                approvedX, approvedY, approvedZ, nowX, nowY, nowZ);
    }

    // ═══════════════════════════════ ② 位置（窗口内，预算判据 ＋ 归属） ═══════════════════════════════

    /**
     * 位置判据（<b>带归属</b>）：窗口内比对「上一次"玩家的移动包"锚点」到现在的位移。
     *
     * <h2>🔴 与旧版的唯一区别，也是用户点名要的那一条</h2>
     * 旧版锚点 = <b>窗口打开那一刻</b>，一开窗 20 tick 里所有位移都拿去和它比 ⇒
     * 正常冲刺（每 tick 0.2~0.3 格，边界上偶尔 0.57）会被当成"被传送"，日志里就是
     * {@code field=pos before=(60.789,80.745,-48.373) after=(59.015,80.745,-47.161) restored=true via=window}。
     * 新版：<b>玩家每发一个移动包就把锚点挪到"他自己写完之后的位置"</b>
     * ⇒ 锚点与现在之间永远只差"那一包之后的合法物理"，所以走路/冲刺/跳跃/下落都不会被拉回。
     *
     * <p>判定顺序：
     * <pre>
     *   ① 开关关 / 豁免态     → KEEP
     *   ② 这次写入是自己的     → ADOPT（self_input：玩家自己在走；self_guard：我们刚挪过他）
     *   ③ 距锚点 0 tick       → KEEP（这一拍没有可比区间）
     *   ④ 现在是 NaN          → RESTORE（与本体 positionDecision 同一口径）
     *   ⑤ 位移 > 预算         → RESTORE，否则 KEEP
     * </pre>
     *
     * @param enabled         开关
     * @param exempt          豁免态
     * @param origin          这次写入的来源
     * @param ticksSinceAnchor 距上一次重锚过了多少 tick（0/负数 ⇒ 不判）
     * @param anchorX/anchorY/anchorZ 锚点（窗口打开时的位置，或玩家自己那次移动包之后的落点）
     * @param nowX/nowY/nowZ  现在的位置
     * @param approvedSpeed   认可动量的水平速率（{@code hypot(x,z)} 格/tick）
     * @return {@link Verdict#RESTORE} ⇒ 调用方把玩家放回锚点
     */
    public static Verdict positionVerdict(boolean enabled, boolean exempt, ShanhaiSelfInput.Origin origin,
                                          int ticksSinceAnchor,
                                          double anchorX, double anchorY, double anchorZ,
                                          double nowX, double nowY, double nowZ,
                                          double approvedSpeed) {
        if (!enabled || exempt) {
            return Verdict.KEEP;
        }
        if (origin != null && origin.isSelf()) {
            return Verdict.ADOPT;                    // 玩家自己在走（走/冲刺/下落都过 handleMovePlayer）
        }
        if (ticksSinceAnchor <= 0) {
            return Verdict.KEEP;
        }
        if (Double.isNaN(nowX) || Double.isNaN(nowY) || Double.isNaN(nowZ)) {
            return Verdict.RESTORE;                  // 读到 NaN 一律拉回（与本体 positionDecision 同一口径）
        }
        final double dx = nowX - anchorX;
        final double dy = nowY - anchorY;
        final double dz = nowZ - anchorZ;
        final double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double speed = approvedSpeed > 0.0D && !Double.isNaN(approvedSpeed) ? approvedSpeed : 0.0D;
        final double budget = (SPRINT_BUDGET_PER_TICK + speed) * (double) ticksSinceAnchor + EPSILON;
        return moved > budget ? Verdict.RESTORE : Verdict.KEEP;
    }

    /**
     * 位置判据（<b>没有来源信息</b> = {@link ShanhaiSelfInput.Origin#NONE}）。
     *
     * <p>⚠️ 与动量那个旧重载同一个用途：只为让 2026-11-08 的真值表还能复算，<b>生产不调它</b>。
     *
     * @param enabled        开关
     * @param exempt         豁免态（旁观/载具/鞘翅/换维度/复活/刚加入）
     * @param ticksInWindow  从窗口打开到现在过了多少 tick（0/负数 ⇒ 窗口没开 ⇒ 不判）
     * @param openX/openY/openZ 窗口打开那一刻的位置
     * @param nowX/nowY/nowZ 现在的位置
     * @param approvedSpeed  认可动量的水平速率（{@code hypot(x,z)} 格/tick，由调用方算好）
     * @return {@link Verdict#RESTORE} ⇒ 调用方把玩家放回窗口打开时的位置
     */
    public static Verdict positionVerdict(boolean enabled, boolean exempt, int ticksInWindow,
                                          double openX, double openY, double openZ,
                                          double nowX, double nowY, double nowZ,
                                          double approvedSpeed) {
        return positionVerdict(enabled, exempt, ShanhaiSelfInput.Origin.NONE, ticksInWindow,
                openX, openY, openZ, nowX, nowY, nowZ, approvedSpeed);
    }

    // ═══════════════════════════════ ③ 物品栏（窗口内，只认"一次少掉一大片"） ═══════════════════════════════

    /**
     * 物品栏判据：<b>只认"一次少掉一大片"</b>这一种形态。
     *
     * <h2>🔴 为什么不能"缺了就补"</h2>
     * 玩家丢东西、吃东西、合成、往容器里放 —— 全都长得跟"被抹掉"一模一样，
     * 而只有 API、不拦截的前提下<b>分不出是谁干的</b>。
     * 「缺了就补」会造出<b>复制物品</b>（丢出去 → 地上一个 → 我们又补回一个），
     * 那是比"物品被抹掉"更糟的结果（原版刷物品漏洞级别）。
     * ⇒ 判据退到唯一能站得住的形态：<b>同一 tick 里一次少掉 ≥ 30%（且 ≥ 2 格）非空槽</b>，
     * 且<b>没开容器</b>、且处在"我们刚拦过东西"的窗口里 ⇒ 人不可能在一个 tick 里吃掉/丢空半背包。
     *
     * @param enabled          开关
     * @param containerOpen    玩家开着别的容器（{@code containerMenu != inventoryMenu}）
     * @param inWindow         处在"我们刚拦过东西"的窗口里
     * @param nonEmptyBefore   快照时非空槽数
     * @param nonEmptyAfter    现在非空槽数
     * @param lostCount        快照时非空、现在空了（或种类变了）的槽数
     * @param cursorNonEmpty   手上还捏着一叠（拖拽中）⇒ 一律不补（否则会把"正在移动的物品"复制一份）
     * @return {@link Verdict#RESTORE} ⇒ 调用方把 {@code lostCount} 个槽补回去
     */
    public static Verdict inventoryVerdict(boolean enabled, boolean containerOpen, boolean inWindow,
                                           int nonEmptyBefore, int nonEmptyAfter, int lostCount,
                                           boolean cursorNonEmpty) {
        if (!enabled || !inWindow || containerOpen || cursorNonEmpty) {
            return Verdict.KEEP;
        }
        if (lostCount <= 0 || nonEmptyBefore <= 0) {
            return Verdict.KEEP;
        }
        final int minLost = Math.max(2, (int) Math.ceil(nonEmptyBefore * INVENTORY_LOST_FRACTION));
        return lostCount >= minLost ? Verdict.RESTORE : Verdict.KEEP;
    }

    // ═══════════════════════════════ ④ 豁免（一处口径） ═══════════════════════════════

    /**
     * 豁免判据（<b>只看状态，不看是谁</b>）。
     *
     * <p>四类豁免与用户列的一致：<b>旁观 / 载具里 / 鞘翅飞行 / 刚传送（含换维度、复活、刚加入）</b>，
     * 另加<b>死亡中</b>（死亡流程本来就会动量归零、位置交给重生）。
     *
     * @param spectator       旁观者
     * @param passenger       在载具/别的实体上（被载具带着走，动量不是他的）
     * @param fallFlying      鞘翅飞行（自己控制动量）
     * @param changingDim     正在换维度
     * @param deadOrDying     死亡/正在死
     * @param ticksSinceJoin  加入世界后过了多少 tick（&lt; 宽限期 ⇒ 豁免）
     * @return true = 这一拍豁免（动量 ADOPT、位置不纠正、物品栏不碰）
     */
    public static boolean exempt(boolean spectator, boolean passenger, boolean fallFlying,
                                 boolean changingDim, boolean deadOrDying, int ticksSinceJoin) {
        return spectator || passenger || fallFlying || changingDim || deadOrDying
                || ticksSinceJoin < JOIN_GRACE_TICKS;
    }

    /** 刚加入世界后的宽限 tick 数（客户端位置包还没对齐，这段里一律不纠正）。 */
    public static final int JOIN_GRACE_TICKS = 40;

    // ═══════════════════════════════ 小工具（离线自检也直接用） ═══════════════════════════════

    /** 三个分量是否逐位相等（{@code NaN == NaN} 视为相等：NaN→NaN 属于"没改"，不是"被改"）。 */
    public static boolean same3(double ax, double ay, double az, double bx, double by, double bz) {
        return same(ax, bx) && same(ay, by) && same(az, bz);
    }

    /** 三个分量是否都是 0（{@code -0.0} 也算 0）。 */
    public static boolean isZero3(double x, double y, double z) {
        return x == 0.0D && y == 0.0D && z == 0.0D;
    }

    /** 单值相等：容差 {@link #EPSILON}；两边都是 NaN 也算相等。 */
    public static boolean same(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return false;
        }
        if (a == b) {
            return true;
        }
        return Math.abs(a - b) <= EPSILON;
    }

    /** 水平速率（{@code hypot(x,z)}），给 {@link #positionVerdict} 的 {@code approvedSpeed} 用。 */
    public static double horizontalSpeed(double x, double z) {
        return Math.hypot(x, z);    }

    // ═══════════════════════════════ ④b 「这一包要不要采纳」的那条判据 ═══════════════════════════════

    /**
     * <b>玩家自己这一包到底改过动量没有</b>（＝要不要把它采纳成认可值）。
     *
     * <h2>🔴 为什么不能"只要是 self_input 就采纳"（实现时想漏过一次）</h2>
     * <pre>
     *   猫推我（外部写 0.4，未标记）→ 下一拍玩家的移动包【先到】
     *   若 TAIL 无条件采纳"现在的值" ⇒ 那 0.4 变成了"玩家自己的操作" ⇒ 这次击退永远还不了
     * </pre>
     * ⇒ 判据：<b>只有"出门时的值 ≠ 进门时的值"，才证明是这一包自己改的</b>
     * （起跳 0.42 由 {@code handleMovePlayer} 里的 {@code jumpFromGround()} 产生，属于这一类）。
     *
     * @param headX/headY/headZ 这一包进门（HEAD）时的 {@code deltaMovement}
     * @param tailX/tailY/tailZ 这一包出门（RETURN）时的 {@code deltaMovement}
     * @return {@code true} = 这一包自己改过 ⇒ 采纳；{@code false} = 没碰过 ⇒ 认可值保持不动
     */
    public static boolean selfInputChangedMomentum(double headX, double headY, double headZ,
                                                   double tailX, double tailY, double tailZ) {
        return !same3(headX, headY, headZ, tailX, tailY, tailZ);
    }

    // ═══════════════════════════════ ⑤ 玩家朝向：一律先规范化再进日志 ═══════════════════════════════

    /**
     * 把偏航（yaw）规范化到 {@code (-180, 180]}。
     *
     * <h2>🔴 为什么必须有这一步（真实日志里踩到的字段 bug）</h2>
     * <pre>
     *   holo_click source=… yaw=-993.45 pitch=12.30 …
     * </pre>
     * {@code LocalPlayer.getYRot()} 是<b>累积</b>的（原版不做回绕；转了几圈就是几百上千度），
     * 于是日志里出现 {@code -993.45}。它既不在 {@code [-180,180]} 里，也没法回答"他到底朝哪"，
     * 更要命的是：<b>任何按角度做的判据都会因为多绕一圈而算错</b>。
     * ⇒ 口径：<b>凡打玩家朝向，先过这一步</b>（日志、抓栈行、报告都走同一个函数）。
     *
     * <p>边界取 {@code (-180, 180]}（{@code -180} 折成 {@code 180}）：这样"同一方向"永远只有一个写法。
     * {@code NaN}/{@code ±Inf} 原样返回（由 {@link #yawText} 打成 {@code nan}，不假装是数）。
     *
     * @param deg 原值（度，可以任意大／负）
     * @return {@code (-180, 180]} 内的等价值；非有限值原样返回
     */
    public static double wrapYaw(double deg) {
        if (Double.isNaN(deg) || Double.isInfinite(deg)) {
            return deg;
        }
        double n = deg % 360.0D;
        if (n > 180.0D) {
            n -= 360.0D;
        }
        if (n <= -180.0D) {
            n += 360.0D;
        }
        // 浮点余数可能给出 -180.0 的近似（例如 -540.0 % 360 = -180.0 ⇒ 已被上面折成 180）
        return n == 0.0D ? 0.0D : n;
    }

    /**
     * 朝向的日志写法：先 {@link #wrapYaw}，再两位小数（{@link java.util.Locale#ROOT}，永远点号）。
     *
     * <p>非有限值一律打 {@code nan}（与本体 {@code ShanhaiHoloClickRules#angleText} 同一口径），
     * 绝不出现 {@code NaN} 这种看不出是"没读到"还是"字符串"的形态。
     *
     * @param deg 原值
     * @return 例如 {@code 86.55} / {@code -179.00} / {@code 180.00} / {@code nan}
     */
    public static String yawText(double deg) {
        final double w = wrapYaw(deg);
        if (Double.isNaN(w) || Double.isInfinite(w)) {
            return "nan";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", w);
    }
}
