package com.dishanhai.gt_shanhai.client.holo;

import com.dishanhai.gt_shanhai.common.holo.ShanhaiHoloMenuBoards;

/**
 * 山海重构 · 全息菜单的<b>射线打板（纯算术核，不碰 Minecraft）</b>。
 *
 * <h2>1. 它解决什么</h2>
 * 用户点单：「<b>右键全息按钮 ⇒ 进入配方修改面板</b>」。
 * 全息板是<b>世界空间里画出来的几何</b>，不是方块也不是实体 ⇒
 * 原版的 {@code hitResult} 永远不会告诉我们在不在板上（对着板右键，{@code hitResult} 仍是
 * {@code MISS}）。所以"点到的是几号板"必须<b>自己算</b>：拿玩家视线的射线，去和五块板的
 * 矩形求交。
 *
 * <h2>2. 几何链（与渲染器 {@code pushBoard} 的变换<b>逐个字对齐</b>）</h2>
 * <pre>
 *   世界点 = 锚点
 *          + R_y(框偏航) · ( 板位姿 + R_y(板自转Y) · R_x(板自转X) · (s · 板内点) )
 *   s = BLOCKS_PER_BU × 该板自己的 scale
 * </pre>
 * （渲染器那三行是 {@code translate(anchor)→mulPose(Y,frameYaw)→pushBoard(translate(b.xyz)→
 * mulPose(Y,b.rotY)→mulPose(X,b.rotX)→scale(s))}，本类就是把这一串<b>反着解</b>。）
 * <p>板内坐标系（bu）里，板面就是 {@code z = 0}，矩形是
 * {@code |x| ≤ BOARD_WIDTH_BU/2}、{@code |y| ≤ BOARD_HEIGHT_BU/2}
 * —— 于是"求交"退化成"把射线变到板内坐标系，取 {@code t = -z/dz}，再看落点是否在矩形里"。
 *
 * <h2>3. 为什么它值得单独一个类 + 一份自检</h2>
 * 这一族的错误<b>不会崩、不会报错</b>，只会"点了没反应"或者"点左边那块却开了右边的"。
 * 而且它极易写成"自己和自己的约定一致"：
 * {@link #selfTest()} 因此<b>不用本类的逆变换去生成期望值</b>，而是：
 * <pre>
 *   · 射线方向用 {@code Minecraft} 自己的 {@code Vec3.directionFromRotation} 公式（逐字抄，见 lookVector）
 *   · 期望命中的板号来自板的角度表（环绕档：玩家朝向方位角 a_i 时正对第 i 块）
 *   · 锚点对环绕档是【手写的】(眼睛 x/z、眼睛 y−0.10)，再单独断言几何核给出的锚点与之逐位相同
 * </pre>
 */
public final class ShanhaiHoloMenuHitTest {

    private ShanhaiHoloMenuHitTest() {}

    /** 一次命中。 */
    public record Hit(int index, double distance, float localX, float localY) {}

    /** 射线超过这个距离就不算（全息最远 2.8 格；留足余量又不至于打到世界另一头）。 */
    public static final double MAX_DISTANCE_BLOCKS = 8.0;

    /** 自检的容差（格）。射线是 float 链算出来的，取 1e-3 已经很紧。 */
    private static final double TOL = 1.0e-3;

    /**
     * 打一条射线，返回<b>最近的</b>那块板。
     *
     * @param layout      排布档（{@code ring} / {@code row} / {@code column}）
     * @param distance    运行期距离（格）；环绕档会走 {@code ringRadius} 的下限
     * @param anchorX/Y/Z 全息中心的世界坐标（= {@code ShanhaiHoloMenuState.position()}）
     * @param frameYawDeg 全息本体的框偏航（= {@code ShanhaiHoloMenuState.frameYaw()}）
     * @param ox/oy/oz    射线起点（世界坐标）
     * @param dx/dy/dz    射线方向（不必是单位向量，内部会归一）
     * @return 命中；{@code null} = 没打到任何板
     */
    public static Hit raycast(String layout, float distance,
                              double anchorX, double anchorY, double anchorZ, float frameYawDeg,
                              double ox, double oy, double oz,
                              double dx, double dy, double dz) {
        final java.util.List<Hit> all = allHits(layout, distance, anchorX, anchorY, anchorZ, frameYawDeg,
                ox, oy, oz, dx, dy, dz);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 同上，但返回<b>所有</b>被这条射线穿过的板（按距离从近到远）。
     *
     * <p>它存在的理由：默认档（环绕档）里五块板互不遮挡，所以"最近的那块"就是把答案；
     * 而旧两档（甲案对照）的板间距只有 {@link ShanhaiHoloMenuTuning#BOARD_PITCH_BLOCKS} ≈ 0.45 格、
     * 板宽却是 {@link ShanhaiHoloMenuTuning#TOTAL_WIDTH_BLOCKS} = 2.3 格 ⇒ <b>板本身互相穿插</b>，
     * 一条射线穿过两三块板是几何事实。这时候"为什么打到了 3 号"只能靠这张全表回答
     * —— 否则那会变成一条查不动的现象。
     */
    public static java.util.List<Hit> allHits(String layout, float distance,
                                              double anchorX, double anchorY, double anchorZ,
                                              float frameYawDeg,
                                              double ox, double oy, double oz,
                                              double dx, double dy, double dz) {
        return allHitsFor(ShanhaiHoloMenuLayout.boardsOf(layout, distance),
                anchorX, anchorY, anchorZ, frameYawDeg, ox, oy, oz, dx, dy, dz);
    }

    /**
     * 🆕 2026-10-06：<b>板表由参数给</b>的那一版 —— 全息面板（设置 / 命令）就是靠它复用的。
     *
     * <p>面板那一列行的位姿由 {@link ShanhaiHoloMenuLayout#panelRows()} 给出，与菜单板走的是
     * <b>同一套</b>"锚点 → 框偏航 → 板自转 → 缩尺"链，所以本方法<b>一行数学都没改</b>，
     * 只是把"板从哪来"从写死的 {@code boardsOf(layout,…)} 换成调用方给的数组。
     *
     * @param boards 参与求交的板（长度任意，>= 1）
     */
    public static java.util.List<Hit> allHitsFor(ShanhaiHoloMenuLayout.Board[] boards,
                                                 double anchorX, double anchorY, double anchorZ,
                                                 float frameYawDeg,
                                                 double ox, double oy, double oz,
                                                 double dx, double dy, double dz) {
        final java.util.List<Hit> out = new java.util.ArrayList<>(
                boards == null ? 0 : boards.length);
        if (boards == null || boards.length == 0) {
            return out;
        }
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 0.0) || !Double.isFinite(len)) {
            return out;
        }
        dx /= len;
        dy /= len;
        dz /= len;

        // 世界 → 全息本体：先减锚点，再绕 Y 反向转 frameYaw
        final double[] ro = yawRot(ox - anchorX, oy - anchorY, oz - anchorZ, -frameYawDeg);
        final double[] rd = yawRot(dx, dy, dz, -frameYawDeg);

        final float halfW = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float halfH = ShanhaiHoloMenuTuning.BOARD_HEIGHT_BU * 0.5f;

        for (ShanhaiHoloMenuLayout.Board b : boards) {
            // 本体 → 板内：减去板位姿，再依次反向转 rotY、rotX，最后除以 s
            final double s = ShanhaiHoloMenuTuning.BLOCKS_PER_BU * b.scale();
            if (!(s > 0.0)) {
                continue;
            }
            final double[] po = boardRotFree(ro[0] - b.x(), ro[1] - b.y(), ro[2] - b.z(), b);
            final double[] pd = boardRotFree(rd[0], rd[1], rd[2], b);
            final double lz = po[2] / s;
            final double dz2 = pd[2] / s;
            if (Math.abs(dz2) < 1.0e-12) {
                continue;                       // 与板面平行
            }
            final double t = -lz / dz2;         // 距离单位 = 格（方向已归一）
            if (t <= 0.0 || t > MAX_DISTANCE_BLOCKS) {
                continue;                       // 在身后 / 太远
            }
            final double hx = (po[0] + t * pd[0]) / s;
            final double hy = (po[1] + t * pd[1]) / s;
            if (Math.abs(hx) > halfW || Math.abs(hy) > halfH) {
                continue;                       // 落在板外（从板边上擦过去）
            }
            out.add(new Hit(b.index(), t, (float) hx, (float) hy));
        }
        out.sort(java.util.Comparator.comparingDouble(Hit::distance));
        return out;
    }

    /**
     * 🆕 2026-10-06：对<b>一列行板</b>（全息面板）打一条射线，返回最近的一行。
     * <p>{@code Hit#localX} 是板内坐标（bu，原点=板心、向右为正）—— 输入层用它把"点在那一格"
     * 解成 0..3 的格子号（见 {@code ShanhaiHoloMenuPanel#cellOf}）。
     */
    public static Hit raycastRows(ShanhaiHoloMenuLayout.Board[] rows,
                                  double anchorX, double anchorY, double anchorZ, float frameYawDeg,
                                  double ox, double oy, double oz,
                                  double dx, double dy, double dz) {
        final java.util.List<Hit> all = allHitsFor(rows, anchorX, anchorY, anchorZ, frameYawDeg,
                ox, oy, oz, dx, dy, dz);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 绕 Y 转 {@code deg} 度（与 {@code Axis.YP.rotationDegrees} / JOML 的同一套公式）。 */
    private static double[] yawRot(double x, double y, double z, double deg) {
        final double r = Math.toRadians(deg);
        final double c = Math.cos(r);
        final double s = Math.sin(r);
        return new double[]{x * c + z * s, y, -x * s + z * c};
    }

    /** 绕 X 转 {@code deg} 度。 */
    private static double[] pitchRot(double x, double y, double z, double deg) {
        final double r = Math.toRadians(deg);
        final double c = Math.cos(r);
        final double s = Math.sin(r);
        return new double[]{x, y * c - z * s, y * s + z * c};
    }

    /**
     * 板位姿的<b>逆</b>变换（把向量从"全息本体坐标系"变到"板内 bu 坐标系"，只差最后除以 s）。
     * <p>渲染器的正变换是 {@code R_y(rotY) · R_x(rotX)} ⇒ 逆变换是
     * {@code R_x(−rotX) · R_y(−rotY)}（<b>顺序也要翻</b>，这是最容易写错的一处）。
     */
    private static double[] boardRotFree(double x, double y, double z, ShanhaiHoloMenuLayout.Board b) {
        final double[] a = yawRot(x, y, z, -b.rotY());
        return b.rotX() == 0.0f ? a : pitchRot(a[0], a[1], a[2], -b.rotX());
    }

    // ------------------------------------------------------------------ 自检

    /** 自检结果。 */
    public record Report(int pass, int total, String bad) {
        public boolean ok() {
            return bad == null;
        }
    }

    /**
     * <b>Minecraft 自己的视线向量公式</b>（{@code Vec3.directionFromRotation} 逐字转写）。
     * <p>🔴 抄它而不是自己推一个：自检的"射线"必须是<b>游戏真的会发出的那条</b>，
     * 否则测的是"我的公式和我另一个公式一致"。
     * <p>展开后（{@code pitch=0}）就是 {@code (-sin yaw, 0, cos yaw)}。
     */
    public static double[] lookVector(float pitchDeg, float yawDeg) {
        final float rad = (float) Math.PI / 180.0f;
        final double f = Math.cos(-yawDeg * rad - (float) Math.PI);
        final double f1 = Math.sin(-yawDeg * rad - (float) Math.PI);
        final double f2 = -Math.cos(-pitchDeg * rad);
        final double f3 = Math.sin(-pitchDeg * rad);
        return new double[]{f1 * f2, f3, f * f2};
    }

    /**
     * 自检（离线可跑）。
     *
     * <p>判据里 <b>4 组是负对照</b>：
     * <pre>
     *   N1 相邻两块板【正中间】的方向必须一块都打不到（36°）
     *   N2 抬头 45° 必须打不到
     *   N3 朝反方向（+180°）必须打不到
     *   N4 同一个方向、把锚点挪出去 100 格 ⇒ 必须打不到（证明真的在用锚点，而不是退化成别的式子）
     * </pre>
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};

        final double eyeX = 10.0;
        final double eyeY = 65.62;
        final double eyeZ = -3.0;
        // 环绕档：圆心 = 玩家本人（x/z 就是眼睛的 x/z），高度 = 眼睛 − 0.10
        final double anchorY = eyeY + ShanhaiHoloMenuTuning.HEIGHT_OFFSET_FROM_EYE;
        final float dist = ShanhaiHoloMenuTuning.DISTANCE_BLOCKS;

        // 0) 手写的锚点必须与几何核给出的逐位相同（否则下面全是用自己的约定验自己）
        final double[] c = ShanhaiHoloMenuLayout.centerOf("ring", eyeX, eyeY, eyeZ, 0.0f, dist);
        if (!near(c[0], eyeX, TOL) || !near(c[1], anchorY, TOL) || !near(c[2], eyeZ, TOL)) {
            n[1]++;
            bad.append(" 锚点与几何核不一致：centerOf=").append(c[0]).append('/').append(c[1])
                    .append('/').append(c[2]).append(';');
        } else {
            n[0]++;
            n[1]++;
        }
        if (ShanhaiHoloMenuLayout.frameYawOf("ring", 123.0f) == 0.0f) {
            n[0]++;
            n[1]++;
        } else {
            n[1]++;
            bad.append(" 环绕档框偏航不是 0（转头会带着整块转，射线就对不上了）;");
        }

        // 1) 环绕档：朝向第 i 块板的方位角时，必须命中第 i 块
        final int[] seen = new int[ShanhaiHoloMenuTuning.BOARD_COUNT];
        for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
            final float yaw = ShanhaiHoloMenuTuning.RING_BASE_YAW_DEG
                    + i * ShanhaiHoloMenuTuning.RING_STEP_DEG;
            final double[] d = lookVector(0.0f, yaw);
            final Hit hit = raycast("ring", dist, eyeX, anchorY, eyeZ, 0.0f,
                    eyeX, eyeY, eyeZ, d[0], d[1], d[2]);
            n[1]++;
            if (hit != null && hit.index() == i) {
                n[0]++;
                seen[i] = 1;
            } else {
                bad.append(" 环绕档 yaw=").append(yaw).append(" 应当命中第 ")
                        .append(board(i)).append(" 格，实际=")
                        .append(hit == null ? "没打到" : ("idx=" + hit.index())).append(';');
            }
            // 距离必须 ≈ 环绕半径（证明打在板上，而不是打在一个极远的平面上）
            n[1]++;
            if (hit != null && near(hit.distance(), ShanhaiHoloMenuLayout.ringRadius(dist), 0.05)) {
                n[0]++;
            } else {
                bad.append(" 第 ").append(board(i))
                        .append(" 格命中距离不对=").append(hit == null ? "n/a" : hit.distance())
                        .append(';');
            }
        }
        // 2) 五条射线必须命中五块【不同】的板（防"永远返回同一块"）
        int distinct = 0;
        for (int v : seen) {
            distinct += v;
        }
        n[1]++;
        if (distinct == ShanhaiHoloMenuTuning.BOARD_COUNT) {
            n[0]++;
        } else {
            bad.append(" 五条射线只命中了 ").append(distinct).append(" 块不同的板;");
        }

        // 3) 🔴 旧两档（甲案对照）的【已知几何事实】，不是缺陷：
        //    板间距 = ARC_RADIUS·sin(21°) ≈ 0.450 格，而板宽 = 2.30 格
        //    ⇒ 旧两档的板本来就互相穿插，一条射线穿过两三块板是必然的。
        //    所以那两档的"打到几号"没有唯一正确答案，本判据只断言：
        //      · 事实成立（间距 < 板宽）
        //      · 在那种穿插下 raycaster 仍然给得出一块板（不会整个失效）
        //    ⇒ 这两档只是甲案对照档，默认档（环绕档）不重叠（见 ④ 的弦长判据）。
        final float pitch = ShanhaiHoloMenuTuning.BOARD_PITCH_BLOCKS;
        final float width = ShanhaiHoloMenuTuning.TOTAL_WIDTH_BLOCKS;
        n[1]++;
        if (pitch < width) {
            n[0]++;
        } else {
            bad.append(" 旧两档的板间距 ").append(pitch).append(" 居然不小于板宽 ").append(width)
                    .append("（那下面的解释就不成立了，必须重查）;");
        }
        for (String legacy : new String[]{"column", "row"}) {
            final float pyaw = 47.0f;
            final double[] cc = ShanhaiHoloMenuLayout.centerOf(legacy, eyeX, eyeY, eyeZ, pyaw, dist);
            final float fy = ShanhaiHoloMenuLayout.frameYawOf(legacy, pyaw);
            final double[] d = lookVector(0.0f, pyaw);
            final java.util.List<Hit> hits = allHits(legacy, dist, cc[0], cc[1], cc[2], fy,
                    eyeX, eyeY, eyeZ, d[0], d[1], d[2]);
            n[1]++;
            if (!hits.isEmpty()) {
                n[0]++;
            } else {
                bad.append(' ').append(legacy).append(" 档正对着居然一块板都打不到（raycaster 整个失效）;");
            }
        }
        // 3b) 列排（上下叠、圆心在前方）：垂直方向不重叠 ⇒ 正对着必须正好打到中间那块
        {
            final float pyaw = 47.0f;
            final double[] cc = ShanhaiHoloMenuLayout.centerOf("column", eyeX, eyeY, eyeZ, pyaw, dist);
            final float fy = ShanhaiHoloMenuLayout.frameYawOf("column", pyaw);
            final double[] d = lookVector(0.0f, pyaw);
            final Hit hit = raycast("column", dist, cc[0], cc[1], cc[2], fy,
                    eyeX, eyeY, eyeZ, d[0], d[1], d[2]);
            n[1]++;
            if (hit != null && hit.index() == 2) {
                n[0]++;
            } else {
                bad.append(" column 档正对着应当命中第 3 格，实际=")
                        .append(hit == null ? "没打到" : ("idx=" + hit.index())).append(';');
            }
        }

        // ---- N1：相邻两块的正中间（+36°）
        final double[] d1 = lookVector(0.0f, ShanhaiHoloMenuTuning.RING_BASE_YAW_DEG + 36.0f);
        count(bad, n, "N1 负对照没响(两块板正中间也打到了)",
                raycast("ring", dist, eyeX, anchorY, eyeZ, 0.0f,
                        eyeX, eyeY, eyeZ, d1[0], d1[1], d1[2]));

        // ---- N2：抬头 45°
        final double[] d2 = lookVector(45.0f, ShanhaiHoloMenuTuning.RING_BASE_YAW_DEG);
        count(bad, n, "N2 负对照没响(抬头 45° 也打到了)",
                raycast("ring", dist, eyeX, anchorY, eyeZ, 0.0f,
                        eyeX, eyeY, eyeZ, d2[0], d2[1], d2[2]));

        // ---- N3：反方向
        final double[] d3 = lookVector(0.0f, ShanhaiHoloMenuTuning.RING_BASE_YAW_DEG + 180.0f);
        count(bad, n, "N3 负对照没响(朝反方向也打到了)",
                raycast("ring", dist, eyeX, anchorY, eyeZ, 0.0f,
                        eyeX, eyeY, eyeZ, d3[0], d3[1], d3[2]));

        // ---- N4：同方向、锚点挪出去 100 格
        final double[] d4 = lookVector(0.0f, ShanhaiHoloMenuTuning.RING_BASE_YAW_DEG);
        count(bad, n, "N4 负对照没响(锚点挪走 100 格也打到了)",
                raycast("ring", dist, eyeX + 100.0, anchorY, eyeZ, 0.0f,
                        eyeX, eyeY, eyeZ, d4[0], d4[1], d4[2]));

        // ---- 与板表交叉：能打到 ≠ 能用（第 1 格是「未启用」）
        n[1]++;
        if (ShanhaiHoloMenuBoards.isEnabled(0)) {
            bad.append(" 交叉判据失败：第 1 格居然是可用的（本轮它应当是「未启用」）;");
        } else {
            n[0]++;
        }

        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b);
    }

    /** 一行自检读数。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_hit_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok()
                + (r.ok() ? "（含 4 组负对照：两板之间 / 抬头 / 反方向 / 锚点挪走）"
                : (" FAILED:" + r.bad()));
    }

    /** 负对照那一格：必须"没打到"。 */
    private static void count(StringBuilder bad, int[] n, String what, Hit hit) {
        n[1]++;
        if (hit == null) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：打到了 idx=").append(hit.index())
                    .append(" dist=").append(hit.distance()).append(';');
        }
    }

    private static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    /** 把 0 基下标写成 1 基（人话口径），只在日志/错误文本里用。 */
    private static int board(int index) {
        return index + 1;
    }
}
