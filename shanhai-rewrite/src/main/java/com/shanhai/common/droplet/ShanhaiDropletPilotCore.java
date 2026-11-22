package com.shanhai.common.droplet;

/**
 * 水滴「玩家驾驶」的<b>零 MC 依赖逻辑核</b>：速度积分、夹角判定、按键白名单、准星圈半径、文案格式化。
 *
 * <h2>一、为什么要把这些东西单独拆出来</h2>
 * 它们全是<b>纯算术与纯查表</b>，一条都不需要 {@code net.minecraft} 的类。拆出来之后可以：
 * <ul>
 *   <li>用<b>不带任何 classpath</b> 的 {@code javac} 单独编译（能编过就等于证明"零 MC 依赖"）；</li>
 *   <li>在<b>不启动游戏</b>的前提下跑断言与负对照（速度积分 20 tick 到满速、夹角 4.9/5.0/5.1 三档、
 *       白名单 7 过其余全拒、圈半径 (H/2)·tanθ/tan(fov/2)）；</li>
 *   <li>让"手感常数"（0.05 加速、1.0 上限）与"判定常数"（严格小于、边界归不锁）只有<b>一处</b>定义，
 *       负对照改这里就能让断言报红。</li>
 * </ul>
 *
 * <h2>二、规格映射</h2>
 * <ul>
 *   <li>规格「一开始为静止，按 w 加速，最快 20 格/秒，按 s 减速可以减到静止」
 *       ⇒ {@link #integrateSpeed(double, boolean, boolean)} + {@link #MAX_SPEED_PER_TICK}
 *       + {@link #ACCEL_PER_TICK}（20 tick = 1 秒到满速/到静止；下界 0、上界 1.0）。</li>
 *   <li>规格「准星与目标夹角小于 5 度时修正行驶方向」⇒ {@link #angleDeg} + {@link #assistLocks}；
 *       <b>边界口径：严格小于</b>（4.9 命中、5.0 不命中、5.1 不命中）。</li>
 *   <li>规格「只允许 w / s / shift / e / esc / 斜杠 / t」⇒ {@link PilotKey} + {@link #keyAllowed}。</li>
 *   <li>规格「5 度这一块用圆圈围起来」⇒ {@link #circleRadiusPx}（半径按 FOV 换算，见方法注释）。</li>
 * </ul>
 *
 * <h2>三、本类<b>不</b>负责什么</h2>
 * 不碰世界、不碰玩家、不碰网络、不写任何状态：所有方法都是纯函数（同输入必同输出）。
 * 状态（当前速度、意图、冷却）由实体自己持有，本类只提供"下一步该是多少"。
 */
public final class ShanhaiDropletPilotCore {

    // ------------------------------------------------------------------ 手感常数（规格：0…1.0 格/tick）

    /** 满速：1.0 格/tick = <b>20 格/秒</b>（20 tick/s）。与实体那条自动攻击的恒速同值。 */
    public static final double MAX_SPEED_PER_TICK = 1.0D;

    /** 静止：速度下界（永远不变负）。 */
    public static final double MIN_SPEED_PER_TICK = 0.0D;

    /**
     * 从静止到满速要多少 tick —— <b>与速度上限无关</b>（加速率 = 上限 ÷ 这个数）。
     *
     * <p>🔴 2026-11-25 起驾驶速度<b>可被指令改</b>（{@code /shanhai droplet speed 10~200} 格/秒）：
     * 上限变了以后，"1 秒到满速 / 1 秒到静止"这条手感必须<b>保持不变</b>，
     * 所以加速率不能是常量，而是 {@code maxSpeedPerTick / TICKS_TO_FULL_SPEED}（见 {@link #accelFor(double)}）。
     */
    public static final int TICKS_TO_FULL_SPEED = 20;

    /** 默认档（上限 {@link #MAX_SPEED_PER_TICK} = 1.0 格/tick）的加速率：0.05 格/tick 每 tick。 */
    public static final double ACCEL_PER_TICK = MAX_SPEED_PER_TICK / (double) TICKS_TO_FULL_SPEED;

    /**
     * 虚空地板相对 {@code level.getMinBuildHeight()} 的下沉量（格）：{@code 8}。
     *
     * <p>水滴掉到世界底部以下时<b>不许被原版丢掉</b>（{@code Entity#onBelowWorld()}），
     * 也不许无限下沉 ⇒ 夹在 {@code minBuildHeight - 8}。
     */
    public static final double VOID_MARGIN_BELOW_MIN_BUILD = 8.0D;

    // ------------------------------------------------------------------ 共享常量（服务端与客户端同一份）

    /** 客户端每 tick 只画这么多目标（按距离升序取前 N），防止实体密集处一帧几千个框。 */
    public static final int MAX_MARKS = 32;

    /** 客户端目标缓存的重算周期（tick）：2 ⇒ 10 Hz（扫描 128 格 AABB 不便宜，逐帧重算没必要）。 */
    public static final int TARGET_REFRESH_TICKS = 2;

    /** W/S 意图的超时（tick）：超过这么久没收到新包 ⇒ 一律视为"没按"（防客户端停了服务端还在加速）。 */
    public static final int INTENT_TIMEOUT_TICKS = 10;

    /** 准星圈的折线段数（越大越圆，96 段在 4K 下也看不出多边形）。 */
    public static final int CROSSHAIR_CIRCLE_SEGMENTS = 96;

    /** 目标框每条边的虚线段上限（成本护栏；边长越长也不会无限加点）。 */
    public static final int MAX_DOTS_PER_EDGE = 24;

    /** 目标框虚线段的最小点数（再短的边也至少 3 个点，否则看不出是条线）。 */
    public static final int MIN_DOTS_PER_EDGE = 3;

    /** 目标框虚线点的间距（GUI 像素）。 */
    public static final double EDGE_DOT_SPACING_PX = 8.0D;

    // ------------------------------------------------------------------ 按键分类

    /**
     * 驾驶期间会遇到的按键类别（服务端不认识按键；这个枚举只用于"白名单判定"与离线断言）。
     *
     * <p>真实按键 → 类别的映射在客户端做（按 {@code KeyMapping} 对象同一性比对 {@code Options} 字段），
     * 见 {@code client\pilot\ShanhaiDropletPilotKeys}。<b>不在白名单的一律强制置 false</b>，
     * 所以新增/未知按键默认落到 {@link #OTHER} ⇒ 默认被拦（宁可多拦，不可漏拦）。
     */
    public enum PilotKey {

        /** W：加速。 */
        FORWARD,
        /** S：减速。 */
        BACK,
        /** Shift（潜行键）：离开水滴。 */
        SNEAK,
        /** E：物品栏。 */
        INVENTORY,
        /** Esc：暂停菜单（1.20.1 里<b>不是</b> KeyMapping，由 Screen/KeyboardHandler 直接处理）。 */
        PAUSE,
        /** 斜杠：指令栏。 */
        COMMAND,
        /** T：聊天栏。 */
        CHAT,

        /** 攻击（左键）。 */
        ATTACK,
        /** 使用 / 放置（右键）。 */
        USE,
        /** 丢弃物品。 */
        DROP,
        /** 跳跃。 */
        JUMP,
        /** 冲刺。 */
        SPRINT,
        /** 左平移。 */
        LEFT,
        /** 右平移。 */
        RIGHT,
        /** 热键栏第 1 格。 */
        HOTBAR_1,
        /** 热键栏第 2 格。 */
        HOTBAR_2,
        /** 热键栏第 3 格。 */
        HOTBAR_3,
        /** 热键栏第 4 格。 */
        HOTBAR_4,
        /** 热键栏第 5 格。 */
        HOTBAR_5,
        /** 热键栏第 6 格。 */
        HOTBAR_6,
        /** 热键栏第 7 格。 */
        HOTBAR_7,
        /** 热键栏第 8 格。 */
        HOTBAR_8,
        /** 热键栏第 9 格。 */
        HOTBAR_9,
        /** 切换视角（第一/第三人称）。 */
        PERSPECTIVE,
        /** 拾取方块。 */
        PICK_ITEM,
        /** 副手交换。 */
        SWAP_OFFHAND,
        /** 快捷存热键栏。 */
        SAVE_HOTBAR,
        /** 快捷读热键栏。 */
        LOAD_HOTBAR,
        /** 社交界面。 */
        SOCIAL,
        /** 其它一切（默认拦截）。 */
        OTHER
    }

    /**
     * 按键白名单：<b>只有</b> W / S / Shift / E / Esc / 斜杠 / T 放行，其余全部拒绝。
     *
     * <p>{@code null} 视为拒绝（防御性：数组里可能有 null 槽）。
     */
    public static boolean keyAllowed(PilotKey key) {
        if (key == null) {
            return false;
        }
        switch (key) {
            case FORWARD:
            case BACK:
            case SNEAK:
            case INVENTORY:
            case PAUSE:
            case COMMAND:
            case CHAT:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ 速度积分

    /**
     * 一次速度积分（带上限；每 tick 调一次）：W 加速、S 减速、同时按则互相抵消。
     *
     * <p>🔴 2026-11-25 起上限<b>由指令设置</b>（{@code /shanhai droplet speed 10~200} 格/秒
     * ⇒ {@code maxSpeedPerTick}），加速率 = 上限 ÷ {@link #TICKS_TO_FULL_SPEED}
     * ⇒ 无论上限多少档，都是 <b>20 tick（1 秒）到满速</b>。
     * 结果夹在 [0, 上限]：一直按 S 只到 0（<b>永不变负</b>），一直按 W 只到上限（<b>永不超速</b>）。
     *
     * @param current 上一 tick 的速度（格/tick）
     * @param forward 是否按着 W
     * @param backward 是否按着 S
     * @param maxSpeedPerTick 上限（格/tick），来自玩家设置
     * @return 这一 tick 的速度（格/tick），恒在 0…上限
     */
    public static double integrateSpeed(double current, boolean forward, boolean backward,
                                       double maxSpeedPerTick) {
        final double cap = Double.isNaN(maxSpeedPerTick) || maxSpeedPerTick < 0.0D
                ? 0.0D : maxSpeedPerTick;
        final double accel = accelFor(cap);
        double v = current;
        if (forward && !backward) {
            v += accel;
        } else if (backward && !forward) {
            v -= accel;
        }
        if (v < MIN_SPEED_PER_TICK) {
            v = MIN_SPEED_PER_TICK;
        }
        if (v > cap) {
            v = cap;
        }
        return v;
    }

    /**
     * 一次速度积分（默认上限 {@link #MAX_SPEED_PER_TICK}）：保留给"只想跑默认档"的调用方与老断言。
     *
     * @param current 上一 tick 的速度（格/tick）
     * @param forward 是否按着 W
     * @param backward 是否按着 S
     * @return 这一 tick 的速度（格/tick），恒在 0…{@link #MAX_SPEED_PER_TICK}
     */
    public static double integrateSpeed(double current, boolean forward, boolean backward) {
        return integrateSpeed(current, forward, backward, MAX_SPEED_PER_TICK);
    }

    /**
     * 某一档上限对应的加速率（格/tick 每 tick）：{@code 上限 / TICKS_TO_FULL_SPEED}。
     *
     * <p>这样无论上限是 1.0（20 格/秒）还是 10.0（200 格/秒），**都是 20 tick（1 秒）到满速**。
     * 上限 ≤ 0 / NaN ⇒ 0（不加速，避免除零或把速度推成负的）。
     */
    public static double accelFor(double maxSpeedPerTick) {
        if (Double.isNaN(maxSpeedPerTick) || maxSpeedPerTick <= 0.0D) {
            return 0.0D;
        }
        return maxSpeedPerTick / (double) TICKS_TO_FULL_SPEED;
    }

    // ------------------------------------------------------------------ 虚空地板

    /**
     * 把 Y 夹到虚空地板以上：{@code max(y, floor)}。
     *
     * <p>为什么要夹：原版 {@code Entity#onBelowWorld()} 会在 {@code y < minBuildHeight - 64} 时
     * 把实体丢掉（水滴"死在虚空里"就是这个）。驾驶时更糟：玩家会跟着一起掉出世界。
     *
     * @param y 当前 Y
     * @param floor 地板（= {@code minBuildHeight - }{@link #VOID_MARGIN_BELOW_MIN_BUILD}）
     * @return 夹过之后的 Y；NaN ⇒ 原样返回
     */
    public static double clampVoidY(double y, double floor) {
        if (Double.isNaN(y) || Double.isNaN(floor)) {
            return y;
        }
        return y < floor ? floor : y;
    }

    /** 由世界的 {@code minBuildHeight} 算出虚空地板。 */
    public static double voidFloorFor(int minBuildHeight) {
        return (double) minBuildHeight - VOID_MARGIN_BELOW_MIN_BUILD;
    }

    // ------------------------------------------------------------------ 夹角 / 瞄准辅助

    /**
     * 两个方向的夹角（度，0…180）。任一向量长度≈0 ⇒ 返回 180（"没有方向"一律当作不锁定）。
     *
     * <p>用 {@code acos} 而不是"先算斜率"：正弦/余弦在小角度区都平，{@code acos} 在 0° 附近反而最敏感，
     * 而规格要判的正是"小于 5 度"这种小角度。
     */
    public static double angleDeg(double ax, double ay, double az, double bx, double by, double bz) {
        final double lenA = Math.sqrt(ax * ax + ay * ay + az * az);
        final double lenB = Math.sqrt(bx * bx + by * by + bz * bz);
        if (lenA < 1.0E-9D || lenB < 1.0E-9D) {
            return 180.0D;
        }
        double dot = (ax * bx + ay * by + az * bz) / (lenA * lenB);
        if (dot > 1.0D) {
            dot = 1.0D;
        }
        if (dot < -1.0D) {
            dot = -1.0D;
        }
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * 瞄准辅助是否锁定：夹角<b>严格小于</b>阈值。
     *
     * <p>边界口径（写清、可断言）：<b>4.9° 锁定、5.0° 不锁定、5.1° 不锁定</b>。
     * 选"边界归不锁"的理由：边界值恰好等于阈值时几何上"正好擦着圈"，此时让玩家自己微调更符合直觉，
     * 也避免浮点误差让边界上忽锁忽不锁（{@code acos} 在端点附近有 1e-13 量级抖动）。
     */
    public static boolean assistLocks(double angleDeg, double thresholdDeg) {
        return angleDeg < thresholdDeg;
    }

    // ------------------------------------------------------------------ 准星圈半径

    /**
     * 准星圈半径（GUI 像素）：{@code r ≈ (H/2)·tan(θ)/tan(fov/2)}。
     *
     * <h4>推导</h4>
     * 透视投影的焦距（像素）= {@code f = (H/2)/tan(fov/2)}；与视轴成 θ 的方向，落在屏幕上的半径
     * 就是 {@code r = f·tan(θ)}。代入即 {@code r = (H/2)·tan(θ)/tan(fov/2)}。
     * <p>调用方一般没有 {@code tan(fov/2)} 这个量，但有透视矩阵的 {@code m11 = 1/tan(fov/2)}
     * ⇒ 本方法收 {@code projectionM11} 后直接 {@code r = halfHeightPx·tan(θ)·projectionM11}，
     * 好处是<b>动态 FOV（冲刺/药水/水下）自动跟随</b>，圈的物理含义永远是"与视轴夹角 θ 的锥面"。
     *
     * @param halfHeightPx 半屏高 {@code H/2}（GUI 缩放后的像素）
     * @param projectionM11 本帧透视矩阵的 {@code m11}（= {@code 1/tan(fovY/2)}）
     * @param angleDeg 锥半角 θ（度）
     * @return 半径（GUI 像素）；参数非法（NaN/负数）⇒ 0
     */
    public static double circleRadiusPx(double halfHeightPx, double projectionM11, double angleDeg) {
        if (Double.isNaN(halfHeightPx) || Double.isNaN(projectionM11) || Double.isNaN(angleDeg)) {
            return 0.0D;
        }
        if (halfHeightPx <= 0.0D || projectionM11 <= 0.0D || angleDeg <= 0.0D) {
            return 0.0D;
        }
        return halfHeightPx * Math.tan(Math.toRadians(angleDeg)) * projectionM11;
    }

    // ------------------------------------------------------------------ 文案（纯字符串，无 MC 类型）

    /** 距离文案：一位小数 + 「格」。 */
    public static String distanceText(double blocks) {
        return String.format(java.util.Locale.ROOT, "%.1f 格", Double.valueOf(blocks));
    }

    /** 血量文案：「血量 当前 / 上限」。 */
    public static String healthText(double health, double maxHealth) {
        return String.format(java.util.Locale.ROOT, "血量 %.1f / %.1f",
                Double.valueOf(health), Double.valueOf(maxHealth));
    }

    /** 名字过长时截断（中文按字符截，不用字节；超长补省略号）。 */
    public static String trimName(String name, int maxChars) {
        if (name == null) {
            return "";
        }
        final String trimmed = name.trim();
        if (maxChars <= 0 || trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, maxChars) + "…";
    }

    /** 速度文案（报告/命令 status 用）：两位小数 + 格/tick。 */
    public static String speedText(double speedPerTick) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(speedPerTick));
    }

    // ------------------------------------------------------------------ 客户端读取水滴驾驶态的最小接口

    /**
     * 客户端从水滴实体读"驾驶态"的最小接口。
     *
     * <h2>为什么要一个接口，而不是直接调实体方法</h2>
     * 客户端要画准星圈、要判"锁定"用哪个角度、要判是否自动驾驶（决定圈还画不画、HUD 上要不要提示），
     * 这些字段是<b>服务端写、客户端读</b>的同步字段。本接口把"读"这一侧定死，
     * 由实体在<b>接入驾驶逻辑时实现</b>（实体侧只需 {@code implements PilotView} + 三个 getter），
     * 好处是客户端代码<b>不需要</b>知道实体内部字段名，也不会因为实体还没接线而编译不过
     * （此时 {@code instanceof} 为 false，客户端退化成默认值）。
     */
    public interface PilotView {

        /** 是否自动驾驶中（服务端权威，客户端只用于显示）。 */
        boolean pilotAutopilot();

        /** 瞄准辅助是否开启。 */
        boolean pilotAssist();

        /** 瞄准辅助的判定角度（度，1…45）。 */
        double pilotAssistAngleDeg();
    }

    private ShanhaiDropletPilotCore() {}
}
