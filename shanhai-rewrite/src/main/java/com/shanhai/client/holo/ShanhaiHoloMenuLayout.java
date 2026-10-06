package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 悬浮全息菜单的<b>排布解算（纯算术核，不依赖 Minecraft）</b>。
 *
 * <h2>1. 它在算什么</h2>
 * 把 {@link ShanhaiHoloMenuTuning} 里的两个排布参数（{@link ShanhaiHoloMenuTuning#ARC_STEP_DEG} /
 * {@link ShanhaiHoloMenuTuning#ARC_RADIUS}）解成 5 块板在<b>全息本体坐标系</b>里的局部位姿。
 *
 * <h2>2. 全息本体坐标系（右手系，与 Minecraft 一致）</h2>
 * <pre>
 *   +X = 玩家的【右】侧
 *   +Y = 世界上方
 *   +Z = 【朝向玩家】（"正面"）
 *   原点 = 全息中心（浮在玩家眼睛高度附近、前方 DISTANCE_BLOCKS 格）
 * </pre>
 * 该坐标系由渲染器用 <b>{@code mulPose(Axis.YP.rotationDegrees(180 - yaw))}</b> 落到世界里。
 * <p>🔴 <b>这个 180-yaw 不是凑出来的</b>，两条都能独立验：
 * <ul>
 *   <li><b>板的法线朝向</b>：板默认法线 {@code (0,0,1)}；{@code Rot_Y(α)·(0,0,1) = (sin α, 0, cos α)}。
 *       取 {@code α = 180 − yaw} ⇒ 法线 = {@code (sin yaw, 0, −cos yaw)}。
 *       而玩家视线水平分量 = {@code (−sin yaw, 0, cos yaw)}（{@code Vec3.directionFromRotation}）
 *       ⇒ 法线 = <b>视线反向</b> = <b>正对玩家</b> ✓</li>
 *   <li><b>+X 是玩家的右</b>：{@code Rot_Y(180−yaw)·(1,0,0) = (−cos yaw, 0, −sin yaw)}；
 *       玩家右 = {@code look × up} = {@code (−sin yaw,0,cos yaw) × (0,1,0) = (−cos yaw, 0, −sin yaw)} ✓
 *       与上式逐分量相同 ⇒ 本坐标系的 +X 就是玩家右手边。</li>
 * </ul>
 *
 * <h2>3. 桶形弧面（两档排布）</h2>
 * 五块板都摆在<b>以 {@code (0,0,-ARC_RADIUS)} 为圆心</b>、半径 {@code ARC_RADIUS} 的一段圆弧上，
 * 每块板自转成<b>贴着圆弧的切平面</b>（即法线沿半径向外）—— 这样中间那块正对玩家、
 * 两端那块各自向外倾，且五块板彼此之间<b>不共面</b>（= 用户要的"转视角时立体感最强"）。
 * <pre>
 *   第 i 块的圆心角  φ_i = (mid − i) · ARC_STEP_DEG      mid = (5−1)/2 = 2
 *   位置(列排)       ( 0,               ARC_RADIUS·sin φ_i,  −ARC_RADIUS·(1 − cos φ_i) )
 *   位置(横排)       ( ARC_RADIUS·sin φ_i,   0,              −ARC_RADIUS·(1 − cos φ_i) )
 * </pre>
 * 两档都可独立验：{@code |position − center|} 恒等于 {@code ARC_RADIUS}（见 {@link #arcError}）。
 *
 * <h2>4. 自转角为什么这么取（两档各一条）</h2>
 * <ul>
 *   <li><b>列排（{@link #of} 的 {@code row=false}）</b>：绕 X 转 {@code −φ}。
 *       理由 {@code Rot_X(β)·(0,0,1) = (0, −sin β, cos β)}；要它等于径向 {@code (0, sin φ, cos φ)}
 *       ⇒ {@code β = −φ} ✓。效果：i=0（顶端）法线朝<b>上</b>外 —— 桶顶那块面朝上仰 ✓</li>
 *   <li><b>横排（{@code row=true}）</b>：绕 Y 转 {@code −φ}，位置取 {@code x = −R·sin φ}。
 *       <p>🔴 <b>横排必须比列排多一个负号，这是离线读数当场抓出来的</b>
 *       （第一版两档共用 {@code x = +R·sin φ}，结果 {@code /shanhai menu dump} 打出来的是
 *       {@code index=0 → x=+0.8398} —— 由于本坐标系 {@code +X = 玩家的右手边}，
 *       那等于把「配方查询」画到了<b>玩家右边</b>，五块板的阅读顺序整个左右颠倒）。
 *       <p>负号之后：{@code φ = (mid−i)·step}，i=0 ⇒ {@code φ=+42} ⇒ {@code x=−0.8398}（<b>左</b> ✓）。
 *       法线 {@code Rot_Y(−φ)·(0,0,1) = (−sin φ, 0, cos φ)}，与"圆心到该点的径向单位向量"
 *       {@code (−sin φ, 0, cos φ)} 逐分量相同 ⇒ 仍是切平面 ✓</li>
 * </ul>
 */
public final class ShanhaiHoloMenuLayout {

    private ShanhaiHoloMenuLayout() {}

    /**
     * 一块板在全息本体坐标系里的局部位姿。
     *
     * @param index 0..4（= {@link ShanhaiHoloMenuTuning#LABEL_ZH} 的下标）
     * @param x     局部位移 X（格）
     * @param y     局部位移 Y（格）
     * @param z     局部位移 Z（格）；负 = 更远离玩家
     * @param rotX  绕 X 轴自转（度）；列排用，横排恒 0
     * @param rotY  绕 Y 轴自转（度）；横排用，列排恒 0
     * @param scale 该板自身的缩放（甲案 {@code --sc}）
     */
    public record Board(int index, float x, float y, float z,
                        float rotX, float rotY, float scale) {
    }

    /**
     * 解一块板。
     *
     * @param index 0..{@code BOARD_COUNT-1}
     * @param row   {@code true} = 横排（五块板左右排开、向左右倾）；{@code false} = 列排（上下叠、向上下倾）
     * @throws IllegalArgumentException index 越界时抛（宁可响亮失败，也不静默画出一块位置不对的板）
     */
    public static Board of(int index, boolean row) {
        if (index < 0 || index >= ShanhaiHoloMenuTuning.BOARD_COUNT) {
            throw new IllegalArgumentException("board index out of range: " + index);
        }
        return of(index, row, ShanhaiHoloMenuTuning.ARC_STEP_DEG, ShanhaiHoloMenuTuning.ARC_RADIUS);
    }

    /** {@link #of(int, boolean)} 的<b>可注入参数版</b>（给离线自检用；渲染路径恒用默认值）。 */
    public static Board of(int index, boolean row, float arcStepDeg, float arcRadius) {
        final int mid = (ShanhaiHoloMenuTuning.BOARD_COUNT - 1) / 2;
        final float phi = (mid - index) * arcStepDeg;
        final float phiRad = (float) Math.toRadians(phi);
        final float sin = (float) Math.sin(phiRad);
        final float cos = (float) Math.cos(phiRad);
        final float scale = ShanhaiHoloMenuTuning.BOARD_SCALE[index];
        // 两端一律向"外"偏（z 恒 ≤ 0）：弧圆心在板后方 ⇒ 越靠两端离观众越远。
        final float z = -arcRadius * (1.0f - cos);
        if (row) {
            // 🔴 负号是必须的：本坐标系 +X = 玩家的右手边，而 index 0 是「配方查询」——它是第一项，
            //    必须出现在【左边】。φ = +42（i=0）⇒ x = −R·sin(+42) = −0.8398 = 左 ✓
            //    （第一版漏了这个负号，dump 打出来 index=0 在 +0.8398，阅读顺序左右颠倒。）
            return new Board(index, -arcRadius * sin, 0.0f, z, 0.0f, -phi, scale);
        }
        // i=0 在最上 ⇒ y = R·sin((2−0)·step) > 0 ✓
        return new Board(index, 0.0f, arcRadius * sin, z, -phi, 0.0f, scale);
    }

    /** 五块板（顺序 = {@link ShanhaiHoloMenuTuning#LABEL_ZH} 的顺序）。 */
    public static Board[] all(boolean row) {
        Board[] out = new Board[ShanhaiHoloMenuTuning.BOARD_COUNT];
        for (int i = 0; i < out.length; i++) {
            out[i] = of(i, row);
        }
        return out;
    }

    // ================================================================== 🆕 全息面板（设置 / 命令）

    /**
     * 🆕 2026-10-06：<b>全息面板</b>（设置面板 / 命令输入框）的五块"行板"。
     *
     * <h4>为什么复用"一块板 = 一行"这个形状</h4>
     * 用户点单要的是「点开配置设置的也是全息的面版」—— 即<b>与五块菜单板同一套观感</b>
     * （同一块底板、同一圈描边流光、同一套文字）。而"面板"需要的正是<b>若干条竖直排开的行</b>。
     * 把一行做成"一块一模一样尺寸的板"（{@code 560×62 bu}）就同时拿到了两件事：
     * <pre>
     *   ① 观感零成本复用：渲染器那三遍（底板/发光/文字）与输入层的射线打板一个字都不用改；
     *   ② 命中判定零成本复用：{@link ShanhaiHoloMenuHitTest} 的"把射线变到板内坐标系求交"
     *      直接吃 {@code Board[]}（本轮给它加了一个"板表由参数给"的重载）。
     * </pre>
     *
     * <h4>与环绕档的关系</h4>
     * 面板<b>不环绕</b>，它是"竖着叠在玩家正前方的一列"（整块随玩家扭头一起转，同甲案的列排版）。
     * 落地位姿由 {@link #frameYawOf}{@code ("column", …)} 与 {@link #centerOf}{@code ("column", …)}
     * 给出 —— 这两个函数是既有的，本轮<b>没有再写第二套朝向公式</b>。
     *
     * @return 长度 = {@link ShanhaiHoloMenuTuning#BOARD_COUNT}（5）；下标 0 在最<b>上</b>（与列排版同向）
     */
    public static Board[] panelRows() {
        final int n = ShanhaiHoloMenuTuning.BOARD_COUNT;
        final float mid = (n - 1) / 2.0f;
        final Board[] out = new Board[n];
        for (int i = 0; i < n; i++) {
            // 不自转（rotX=rotY=0）、不缩放（scale=1）：面板是一列平整的板，
            // 而列排版那两端的 ±外倾是给"菜单"用的（面板上会让第一行/最后一行歪掉）。
            out[i] = new Board(i, 0.0f,
                    (mid - i) * ShanhaiHoloMenuTuning.PANEL_ROW_PITCH_BLOCKS,
                    0.0f, 0.0f, 0.0f, 1.0f);
        }
        return out;
    }

    /** 面板整列的高度（格）= 4 个行距 + 一块板高（读数用，也用于那次"面板有没有顶出屏幕"的核算）。 */
    public static float panelTotalHeightBlocks() {
        final int n = ShanhaiHoloMenuTuning.BOARD_COUNT;
        return (n - 1) * ShanhaiHoloMenuTuning.PANEL_ROW_PITCH_BLOCKS
                + 2.0f * ShanhaiHoloMenuTuning.BOARD_HALF_HEIGHT_BLOCKS;
    }

    // ================================================================== 环绕档（ring，默认）

    /**
     * <b>环绕档</b>：把第 {@code index} 块板摆在"以玩家为圆心、半径 {@code radius} 的水平圆"上，
     * 板面法线<b>指向圆心（玩家）</b>。
     *
     * <h4>为什么这套公式是对的（三条都能独立验）</h4>
     * <pre>
     *   方位角          a_i = RING_BASE_YAW + i · RING_STEP          （原版偏航口径：0 = +Z）
     *   本地位置(α=0)   ( -R·sin a , 0 , +R·cos a )                  ← 圆心在原点 = 玩家
     *   板的自转        rotY = 180 − a
     *      ⇒ 法线 = ( sin rotY, 0, cos rotY ) = ( -sin a, 0, -cos a ) = 指向圆心的方向 ✓
     *   板的世界偏航    yaw_world = 180 + a
     *      ⇒ directionFromRotation(yaw) = ( -sin(yaw), 0, cos(yaw) ) = ( sin a, 0, -cos a ) ✓
     * </pre>
     * ⚠️ <b>环绕档下全息本体的偏航恒为 0</b>（{@link #frameYawOf}）—— 即
     * <b>板的世界姿态只由 a_i 决定，与玩家偏航无关</b>。这正是用户要的「不需要跟随玩家的头转动」：
     * 玩家扭头时看到的是环上<b>另外</b>几块板，所以他才能"选"。
     *
     * @param radius 环绕半径（格）；由 {@code ShanhaiHoloMenuState#ringRadius()} 给出（有下限）
     */
    public static Board ringBoard(int index, float radius) {
        if (index < 0 || index >= ShanhaiHoloMenuTuning.BOARD_COUNT) {
            throw new IllegalArgumentException("board index out of range: " + index);
        }
        final float a = ShanhaiHoloMenuTuning.RING_BASE_YAW_DEG
                + index * ShanhaiHoloMenuTuning.RING_STEP_DEG;
        final double rad = Math.toRadians(a);
        final float x = (float) (-radius * Math.sin(rad));
        final float z = (float) (radius * Math.cos(rad));
        final float scale = ShanhaiHoloMenuTuning.BOARD_SCALE[index];
        return new Board(index, x, 0.0f, z, 0.0f, 180.0f - a, scale);
    }

    /** 五块板（环绕档）。 */
    public static Board[] ringAll(float radius) {
        Board[] out = new Board[ShanhaiHoloMenuTuning.BOARD_COUNT];
        for (int i = 0; i < out.length; i++) {
            out[i] = ringBoard(i, radius);
        }
        return out;
    }

    /** 一块板在<b>世界</b>里的偏航（度）—— 环绕档 = {@code 180 + a_i}，与玩家偏航无关。 */
    public static float ringBoardWorldYaw(int index) {
        return wrap180(180.0f + ShanhaiHoloMenuTuning.RING_BASE_YAW_DEG
                + index * ShanhaiHoloMenuTuning.RING_STEP_DEG);
    }

    // ================================================================== 全息本体的落地位姿

    /** 排布档的名字（{@code ring} / {@code row} / {@code column}）—— JSON 与命令都用它。 */
    public static String layoutName(String layout) {
        if (layout == null) {
            return "ring";
        }
        final String s = layout.toLowerCase(Locale.ROOT);
        if (s.equals("row") || s.equals("column")) {
            return s;
        }
        return "ring";
    }

    /**
     * 全息本体的框（平移 + 绕 Y 的落地旋转）。<b>三种排布唯一不同的地方就在这里</b>。
     *
     * <ul>
     *   <li><b>ring</b>：中心 = 玩家眼睛 + 高度偏移（<b>不沿视线前移</b>）；框偏航 = 0
     *       ⇒ 圆心就是玩家本人、且与世界轴对齐 ⇒ 位置只跟"人走到哪"变，不跟"头转向哪"变；</li>
     *   <li><b>row / column</b>（甲案对照档）：中心 = 玩家眼睛沿视线前移 {@code distance} 格；
     *       框偏航 = {@code 180 − 玩家偏航} ⇒ 整块全息随头转（旧行为，原样保留）。</li>
     * </ul>
     */
    public static float frameYawOf(String layout, float playerYawDeg) {
        return layoutName(layout).equals("ring") ? 0.0f : 180.0f - playerYawDeg;
    }

    /** 全息中心的目标位置（世界坐标）。 */
    public static double[] centerOf(String layout, double eyeX, double eyeY, double eyeZ,
                                    float playerYawDeg, float distance) {
        if (layoutName(layout).equals("ring")) {
            return new double[]{eyeX, eyeY + ShanhaiHoloMenuTuning.HEIGHT_OFFSET_FROM_EYE, eyeZ};
        }
        final double rad = Math.toRadians(playerYawDeg);
        // 水平前进方向（与 Vec3.directionFromRotation 的水平分量一致）
        return new double[]{eyeX + (-Math.sin(rad)) * distance,
                eyeY + ShanhaiHoloMenuTuning.HEIGHT_OFFSET_FROM_EYE,
                eyeZ + Math.cos(rad) * distance};
    }

    /** 环绕档的<b>有效</b>半径（格）：{@code max(distance, 下限)}，下限见 {@code RING_MIN_RADIUS_BLOCKS}。 */
    public static float ringRadius(float distance) {
        return Math.max(distance, ShanhaiHoloMenuTuning.RING_MIN_RADIUS_BLOCKS);
    }

    /** 某个排布下的五块板（局部/相对本体框的位姿）。 */
    public static Board[] boardsOf(String layout, float distance) {
        final String l = layoutName(layout);
        if (l.equals("ring")) {
            return ringAll(ringRadius(distance));
        }
        return all(l.equals("row"));
    }

    /**
     * <b>世界位姿</b>：把"一块板的局部位姿"落到世界坐标里（与渲染器 {@code mulPose(Y,180−yaw)}
     * 用的是同一个变换）。
     *
     * @return {@code {x, y, z}} 世界坐标
     */
    public static double[] worldPosOf(String layout, float playerYawDeg, double[] center, Board b) {
        final float frameYaw = frameYawOf(layout, playerYawDeg);
        final double rad = Math.toRadians(frameYaw);
        // Rot_Y(frameYaw)·(x,0,z) = ( x·cos + z·sin , 0 , -x·sin + z·cos )
        final double wx = b.x() * Math.cos(rad) + b.z() * Math.sin(rad);
        final double wz = -b.x() * Math.sin(rad) + b.z() * Math.cos(rad);
        return new double[]{center[0] + wx, center[1] + b.y(), center[2] + wz};
    }

    /**
     * <b>{@code /shanhai menu dump}</b> 的那份 JSON —— <b>纯函数</b>，玩家状态全部由参数给。
     *
     * <p>🔴 它同时含两组<b>机器可判断言</b>（用户 A6 的要求：光说"我改了"不算）：
     * <pre>
     *   yaw_invariance   : 同一个位置、偏航 +37° ⇒ 每块板的【世界偏航】与【世界坐标】必须逐字节相同
     *   pos_follow       : 同一个偏航、位置 +1 格 ⇒ 每块板的世界坐标必须整块平移 (1,0,0)，偏航不动
     *   yaw_dependent    : 上面第一条的【负对照】—— 旧档 row/column 必须是 true（说明判据不是恒绿）
     * </pre>
     * 三个字段都写进 JSON 的 {@code "checks"} 里，配上原始读数（{@code player_yaw} / {@code player_pos}
     * / 每块板的 {@code yaw} 与 {@code pos}）。
     */
    public static String dumpJson(String layout, double playerX, double playerY, double playerZ,
                                  double eyeHeight, float playerYawDeg, float distance) {
        final String l = layoutName(layout);
        final double eyeX = playerX;
        final double eyeY = playerY + eyeHeight;
        final double eyeZ = playerZ;
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"layout\":\"").append(l).append('"');
        sb.append(",\"player_yaw\":").append(f(playerYawDeg));
        sb.append(",\"player_pos\":[").append(f(playerX)).append(',').append(f(playerY)).append(',')
                .append(f(playerZ)).append(']');
        sb.append(",\"player_eye\":[").append(f(eyeX)).append(',').append(f(eyeY)).append(',')
                .append(f(eyeZ)).append(']');

        final double[] center = centerOf(l, eyeX, eyeY, eyeZ, playerYawDeg, distance);
        sb.append(",\"frame_yaw\":").append(f(frameYawOf(l, playerYawDeg)));
        sb.append(",\"hologram_center\":[").append(f(center[0])).append(',').append(f(center[1])).append(',')
                .append(f(center[2])).append(']');
        sb.append(",\"ring_radius\":").append(f(ringRadius(distance)));
        sb.append(",\"distance\":").append(f(distance));
        sb.append(",\"boards\":[");
        final Board[] boards = boardsOf(l, distance);
        for (int i = 0; i < boards.length; i++) {
            final Board b = boards[i];
            final double[] wp = worldPosOf(l, playerYawDeg, center, b);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i)
                    .append(",\"zh\":\"").append(ShanhaiHoloMenuTuning.LABEL_ZH[i]).append('"')
                    .append(",\"yaw\":").append(f(boardWorldYaw(l, i, playerYawDeg)))
                    .append(",\"pos\":[").append(f(wp[0])).append(',').append(f(wp[1])).append(',')
                    .append(f(wp[2])).append(']')
                    .append(",\"x\":").append(f(b.x()))
                    .append(",\"y\":").append(f(b.y()))
                    .append(",\"z\":").append(f(b.z()))
                    .append(",\"rotX\":").append(f(b.rotX()))
                    .append(",\"rotY\":").append(f(b.rotY()))
                    .append(",\"scale\":").append(f(b.scale()))
                    .append('}');
        }
        sb.append(']');
        sb.append(",\"checks\":").append(checksJson(l, eyeX, eyeY, eyeZ, playerYawDeg, distance));
        sb.append('}');
        return sb.toString();
    }

    /** 一块板的<b>世界偏航</b>（度）：环绕档与玩家偏航无关；旧两档 = {@code frameYaw + rotY(+rotX 的等效)}。 */
    public static float boardWorldYaw(String layout, int index, float playerYawDeg) {
        final String l = layoutName(layout);
        if (l.equals("ring")) {
            return ringBoardWorldYaw(index);
        }
        // 旧档：本体框转了 (180 − yaw)，板自身又绕 Y 转了 rotY ⇒ 世界偏航 = 两者之和
        final Board b = of(index, l.equals("row"));
        return wrap180(frameYawOf(l, playerYawDeg) + b.rotY());
    }

    /** 一行摘要（供 dump 的日志行与聊天栏用）：{@code yaw_invariance=… pos_follow=… yaw_dependent=…}。 */
    public static String checksLine(String layout, double playerX, double playerY, double playerZ,
                                    double eyeHeight, float playerYawDeg, float distance) {
        final double eyeY = playerY + eyeHeight;
        final String base = poseSignature(layout, playerX, eyeY, playerZ, playerYawDeg, distance);
        final String turned = poseSignature(layout, playerX, eyeY, playerZ, playerYawDeg + 37.0f, distance);
        final boolean yawInvariant = base.equals(turned);
        return "yaw_invariance=" + yawInvariant
                + " pos_follow=" + posFollowOk(layout, playerX, eyeY, playerZ, playerYawDeg, distance)
                + " yaw_dependent=" + (!yawInvariant);
    }

    /**
     * 三组断言的具体算法（<b>都是拿 {@link #poseSignature} 比字节</b>，不做浮点容差判断）。
     */
    private static String checksJson(String layout, double eyeX, double eyeY, double eyeZ,
                                     float playerYawDeg, float distance) {
        final String base = poseSignature(layout, eyeX, eyeY, eyeZ, playerYawDeg, distance);
        final String turned = poseSignature(layout, eyeX, eyeY, eyeZ, playerYawDeg + 37.0f, distance);
        final String moved = poseSignature(layout, eyeX + 1.0, eyeY, eyeZ, playerYawDeg, distance);

        // ① 转头 ⇒ 板不动
        final boolean yawInvariant = base.equals(turned);
        // ② 走一格 ⇒ 每块板的世界坐标整块平移 (1,0,0)（见 posFollowOk）
        final boolean posFollow = posFollowOk(layout, eyeX, eyeY, eyeZ, playerYawDeg, distance);

        final StringBuilder sb = new StringBuilder(256);
        sb.append("{\"yaw_invariance\":").append(yawInvariant);
        sb.append(",\"pos_follow\":").append(posFollow);
        sb.append(",\"yaw_dependent\":").append(!yawInvariant);
        sb.append(",\"yaw_probe_deg\":").append(f(playerYawDeg + 37.0f));
        sb.append(",\"pose_sig_yaw_").append(f(playerYawDeg)).append(":\"").append(esc(base)).append('"');
        sb.append(",\"pose_sig_yaw_").append(f(playerYawDeg + 37.0f)).append(":\"").append(esc(turned)).append('"');
        sb.append(",\"pose_sig_moved_x_plus_1\":\"").append(esc(moved)).append('"');
        sb.append('}');
        return sb.toString();
    }

    /**
     * 「走一格，整套板跟着走一格」的<b>逐块</b>判据（不是只看中心）。
     *
     * @return 每一块板的世界坐标都恰好平移 {@code (+1, 0, 0)}（容差 1e-6）且世界偏航不变
     */
    public static boolean posFollowOk(String layout, double eyeX, double eyeY, double eyeZ,
                                      float playerYawDeg, float distance) {
        final String l = layoutName(layout);
        final double[] c0 = centerOf(l, eyeX, eyeY, eyeZ, playerYawDeg, distance);
        final double[] c1 = centerOf(l, eyeX + 1.0, eyeY, eyeZ, playerYawDeg, distance);
        final Board[] boards = boardsOf(l, distance);
        for (int i = 0; i < boards.length; i++) {
            final double[] p0 = worldPosOf(l, playerYawDeg, c0, boards[i]);
            final double[] p1 = worldPosOf(l, playerYawDeg, c1, boards[i]);
            if (Math.abs((p1[0] - p0[0]) - 1.0) > 1.0e-6
                    || Math.abs(p1[1] - p0[1]) > 1.0e-6
                    || Math.abs(p1[2] - p0[2]) > 1.0e-6) {
                return false;
            }
        }
        return true;
    }

    /** 一个排布在给定玩家状态下的<b>位姿指纹</b>（每块板的 world yaw + world pos，定长格式）。 */
    public static String poseSignature(String layout, double eyeX, double eyeY, double eyeZ,
                                       float playerYawDeg, float distance) {
        final String l = layoutName(layout);
        final double[] center = centerOf(l, eyeX, eyeY, eyeZ, playerYawDeg, distance);
        final Board[] boards = boardsOf(l, distance);
        final StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < boards.length; i++) {
            final double[] wp = worldPosOf(l, playerYawDeg, center, boards[i]);
            sb.append(i).append('|').append(f(boardWorldYaw(l, i, playerYawDeg))).append('|')
                    .append(f(wp[0])).append(',').append(f(wp[1])).append(',').append(f(wp[2])).append(';');
        }
        return sb.toString();
    }

    /**
     * 角度归一化到 {@code (-180, 180]}。
     * <p>🔴 <b>刻意不用 {@code Mth.wrapDegrees}</b>：本类是纯算术核（离线/无头都要能跑），
     * 引一个 Minecraft 类进来就会把整条几何链绑到客户端类加载器上。
     * 口径与 {@code Mth.wrapDegrees} 一致（只差边界上 ±180 的取哪一头，不影响本类任何判据）。
     */
    private static float wrap180(float deg) {
        float d = deg % 360.0f;
        if (d > 180.0f) {
            d -= 360.0f;
        } else if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * <b>离线自检</b>：解出来的每一块板，到弧圆心的距离必须恒等于弧半径。
     *
     * <p>判据（正/负对照都用它）：本方法返回<b>最大偏差</b>。
     * 正确实现应返回 {@code ~1e-7}（float 精度）；返回大数就说明 {@link #of} 的公式写歪了。
     * 它<b>不需要 Minecraft</b>，可以在任何时候跑（{@code /shanhai menu selftest} 就是调它）。
     */
    public static float arcError() {
        return arcError(ShanhaiHoloMenuTuning.ARC_RADIUS, ShanhaiHoloMenuTuning.ARC_RADIUS);
    }

    /**
     * 同上，但<b>两个半径可以分开给</b> —— 故意让它们不等时，本方法<b>必须报出那个差</b>。
     *
     * <p>这就是"判据自己是不是恒绿"的<b>负对照</b>：{@code arcError(R, R)} 恒 ≈ 0 并不证明什么，
     * 只有 {@code arcError(R, R + 0.5)} ≈ 0.5 才能证明这个判据真的在算距离
     * （本工程血规矩：「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」）。
     *
     * @param positionRadius 解算板位姿时用的弧半径
     * @param checkRadius    校验时假定的弧半径
     * @return 五块板到 {@code (0,0,-checkRadius)} 的距离与 {@code checkRadius} 的<b>最大</b>偏差
     */
    public static float arcError(float positionRadius, float checkRadius) {
        float worst = 0.0f;
        for (boolean row : new boolean[]{false, true}) {
            for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
                Board b = of(i, row, ShanhaiHoloMenuTuning.ARC_STEP_DEG, positionRadius);
                // 圆心在 (0,0,-checkRadius)（列排与横排都一样：弧只在 Y-Z 或 X-Z 平面内弯曲）
                float dz = b.z() - (-checkRadius);
                float r = (float) Math.sqrt(b.x() * b.x() + b.y() * b.y() + dz * dz);
                worst = Math.max(worst, Math.abs(r - checkRadius));
            }
        }
        return worst;
    }

    /**
     * 把五块板的局部位姿打成 JSON（给 HTML 预览直接抄成三维场景里的五个平面）。
     * <p>与 {@link ShanhaiHoloMenuTuning#toJson()} 拼起来就是"一份能复现同一套参数"的完整读数。
     */
    public static String toJson(boolean row) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"layout\":\"").append(row ? "row" : "column").append("\",\"boards\":[");
        Board[] boards = all(row);
        for (int i = 0; i < boards.length; i++) {
            Board b = boards[i];
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(b.index())
                    .append(",\"zh\":\"").append(ShanhaiHoloMenuTuning.LABEL_ZH[i]).append('"')
                    .append(",\"en\":\"").append(ShanhaiHoloMenuTuning.LABEL_EN[i]).append('"')
                    .append(",\"x\":").append(f(b.x()))
                    .append(",\"y\":").append(f(b.y()))
                    .append(",\"z\":").append(f(b.z()))
                    .append(",\"rotX\":").append(f(b.rotX()))
                    .append(",\"rotY\":").append(f(b.rotY()))
                    .append(",\"scale\":").append(f(b.scale()))
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 固定 6 位小数（跨语言比对用；不依赖本机 Locale）。收 {@code double}，float 实参会自动加宽。 */
    private static String f(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
