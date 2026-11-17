package com.shanhai.client.holo;

/**
 * 山海重构 · 全息面板<b>「打开那一刻自检」的纯算术核</b>（不依赖 Minecraft）。
 *
 * <h2>1. 它要解决的那句话</h2>
 * 用户反复报「<b>猫在的时候打不开面板</b>」。实测取证已经把它拆成两条互不相干的病根：
 * <ol>
 *   <li>🔴 <b>摆位</b>：默认 {@code ring} 把 5 块板按 72° 均布在<b>绕玩家一圈</b>的水平圆上，
 *       而 70° 视场角下站着看正前方时<b>只有 board0 在视锥内</b>（离线真算 + JOML 交叉核对
 *       NDC 差 5.96e-08）⇒ {@code boards_in=1/5}；</li>
 *   <li>🔴 <b>连点抵消</b>：1 秒 6 次 toggle，净状态落回"关"（见 {@link ShanhaiHoloMenuToggleGate}）。</li>
 * </ol>
 * 第 1 条<b>本轮按用户要求不改</b>（原话：「然后不要再修摆位了」），于是它必须先变得
 * <b>可判</b>：这一拍把"打开之后到底几块板真的在镜头里"变成一行读数 + 一行告警，
 * 下次"看不到"时不用再靠猜。
 *
 * <h2>2. 判据（与本工程既有读数<b>同一个口径</b>，不另起一套）</h2>
 * <pre>
 *   板心世界坐标  = {@link ShanhaiHoloViewMath#boardCenterWorld}（= 渲染器 applyHoloFrame→poseBoard）
 *   投影 / 视锥   = {@link ShanhaiHoloViewMath#proj}（behind 先判，再看 |ndc| ≤ 1）
 *   四角          = {@link ShanhaiHoloViewMath#cornersInFrustum}（与 visibility_dump 的 corners_in 同源）
 *   boards_in = 板心 in_frustum 的板数（与 visibility 那行 boards_in=k/n 完全同口径）
 *   all_in_frustum = 5 块板 × 4 角 = 20 个角【全部】in_frustum
 * </pre>
 * ⚠️ {@code cornersInFrustum} <b>忽略绕 X 的倾角</b>（板内 +X 方向只按世界偏航算）——
 * 默认档 {@code ring} 的五块板 {@code rotX} 恒为 0 ⇒ 对它<b>逐位精确</b>；
 * 旧两档（竖列/横排）两端板最多倾 42°，四角读数会略保守（见报告「没验到的」）。
 *
 * <h2>3. 告警口径（observability only —— 绝不自动改朝向）</h2>
 * <pre>
 *   boards_in &lt; 5（或四角没全进）⇒ 追加一行 holo_open_warn boards_in=&lt;k&gt; reason=&lt;a|b|c&gt;
 *   reason:
 *     layout   —— 有角落在相机平面【背后】（w ≤ 0）⇒ 这是"摆位把板放到了身后"
 *     fov      —— 没有角落到背后，但有角出了 NDC（|x|&gt;1 / |y|&gt;1 / z 出裁剪面）⇒ 这是"视场角不够"
 *     occluded —— 20 个角全在视锥内却仍数不满 ⇒ 剩下的只能是遮挡 / 深度 / 透明度
 * </pre>
 * 🔴 这一行只是<b>读数</b>：本类不返回任何"要不要转一下"的东西，调用方也没有那条路。
 */
public final class ShanhaiHoloOpenCheck {

    private ShanhaiHoloOpenCheck() {}

    /** 5 块板 = 20 个角（读数用；不假设 {@code BOARD_COUNT} 一定是 5）。 */
    public static final int CORNERS_PER_BOARD = 4;

    /**
     * 一次开面板自检的读数。
     *
     * @param layout        排布档名（{@code ring}/{@code row}/{@code column}）
     * @param boardsIn      板心在视锥内的板数（0..boardTotal）
     * @param boardTotal    板总数（正常 = 5）
     * @param board0NdcX    0 号板的板心 NDC x（不可投影 ⇒ NaN）
     * @param board0NdcY    0 号板的板心 NDC y（同上）
     * @param allInFrustum  5 块板 × 4 角 = 全部 20 个角都在视锥内
     * @param dist          <b>最近</b>那块板的板心到相机的距离（格）。
     *                      <p>⚠️ 刻意<b>不是</b>"板心平均点"的距离：默认档 {@code ring} 的五块板
     *                      是<b>绕着玩家</b>排的，平均点 ≈ 玩家本人 ⇒ 那个数会恒等于
     *                      {@code HEIGHT_OFFSET}（0.10 格），对"板离人有多远"毫无信息量。
     *                      最近那块板才是"你该看见的东西离你多远"。
     * @param faceCamera    五块板的法线是否都朝向相机（点积 ≥ {@value #FACE_CAMERA_MIN_DOT}）
     * @param cornersIn     20 个角里落在视锥内的个数
     * @param cornersTotal  角总数（= 4 × boardTotal）
     * @param behind        落在相机平面背后的角数
     * @param outOfNdc      在相机前方但出了 NDC 的角数
     */
    public record Report(String layout, int boardsIn, int boardTotal,
                         float board0NdcX, float board0NdcY,
                         boolean allInFrustum, float dist, boolean faceCamera,
                         int cornersIn, int cornersTotal, int behind, int outOfNdc) {

        /** 少几块板（读数用：{@code 5-3 = 2}）。 */
        public int missing() {
            return boardTotal - boardsIn;
        }

        /**
         * 告警原因（三种互斥；口径见类注释 §3）。
         *
         * @return {@code layout} / {@code fov} / {@code occluded}；读数全好时仍返回
         *         {@code occluded}（调用方先用 {@link #needsWarn()} 判要不要打）
         */
        public String reason() {
            if (behind > 0) {
                return "layout";
            }
            if (outOfNdc > 0) {
                return "fov";
            }
            return "occluded";
        }

        /** 要不要追加告警行（{@code boards_in < 5} <b>或</b>四角没全进）。 */
        public boolean needsWarn() {
            return boardsIn < boardTotal || !allInFrustum;
        }

        /**
         * {@code holo_open_check} 那一行的<b>正文</b>（不含 {@code [SHANHAI-HOLO] } 前缀）。
         *
         * <p>⚠️ <b>字段名固定</b>（用户任务书逐字）：{@code layout=} / {@code boards_in=} /
         * {@code board0_ndc=} / {@code all_in_frustum=} / {@code dist=} / {@code face_camera=}。
         */
        public String line() {
            return "holo_open_check layout=" + layout
                    + " boards_in=" + boardsIn + "/" + boardTotal
                    + " board0_ndc=(" + ShanhaiHoloViewMath.fmt4(board0NdcX)
                    + "," + ShanhaiHoloViewMath.fmt4(board0NdcY) + ")"
                    + " all_in_frustum=" + allInFrustum
                    + " dist=" + ShanhaiHoloViewMath.fmt2(dist)
                    + " face_camera=" + faceCamera;
        }

        /** {@code boards_in < 5} 时追加的那一行正文（{@link #needsWarn()} 为假时返回 {@code null}）。 */
        public String warnLine() {
            if (!needsWarn()) {
                return null;
            }
            return "holo_open_warn boards_in=" + boardsIn + " reason=" + reason();
        }

        /** 更细的读数（跟在 {@code holo_open_check} 同一行末尾，便于折算"到底怎么会数不满"）。 */
        public String detailSuffix() {
            return " corners_in=" + cornersIn + "/" + cornersTotal
                    + " corners_behind=" + behind + " corners_out=" + outOfNdc
                    + " missing=" + missing();
        }
    }

    /** "板面朝向相机"的点积阈值（1.0 = 完全正对；0.9 ≈ 25° 以内）。 */
    public static final float FACE_CAMERA_MIN_DOT = 0.9f;

    /**
     * 算一次自检读数（<b>纯函数</b>：世界 → NDC 全靠调用方给的 MVP）。
     *
     * @param mvpColMajor MVP，<b>列主序</b>（{@code m[c*4+r]}，= JOML {@code Matrix4f.get(float[])}）
     * @param guiW,guiH   GUI 缩放的屏幕宽高（{@code ≤0} ⇒ 只算 NDC，屏幕坐标给 -1）
     * @param boards      板表（正常 = {@link ShanhaiHoloMenuLayout#boardsOf} 的 5 块）
     * @param ox,oy,oz    全息原点世界坐标（已含展开动画的原点插值）
     * @param frameYawDeg 框偏航（已含朝向档语义）
     * @param animSpinDeg 展开动画的自转（完全展开时 = 0）
     * @param animScale   展开动画的缩放（完全展开时 = 1）
     * @param camX,camY,camZ 相机世界坐标（{@code dist} 与 {@code face_camera} 读它）
     * @param layoutName  排布档名（只用于日志）
     */
    public static Report evaluate(float[] mvpColMajor, int guiW, int guiH,
                                  ShanhaiHoloMenuLayout.Board[] boards,
                                  double ox, double oy, double oz, float frameYawDeg,
                                  float animSpinDeg, float animScale,
                                  double camX, double camY, double camZ,
                                  String layoutName) {
        final int total = boards == null ? 0 : boards.length;
        if (total == 0 || mvpColMajor == null || mvpColMajor.length < 16) {
            return new Report(layoutName, 0, total,
                    Float.NaN, Float.NaN, false, 0.0f, false, 0, 0, 0, 0);
        }
        final double[] c3 = new double[3];
        final ShanhaiHoloViewMath.Proj[] q = new ShanhaiHoloViewMath.Proj[CORNERS_PER_BOARD];
        int boardsIn = 0;
        int cornersIn = 0;
        int behind = 0;
        int outOfNdc = 0;
        boolean face = true;
        double nearest = Double.MAX_VALUE;
        float ndc0x = Float.NaN;
        float ndc0y = Float.NaN;

        for (int i = 0; i < total; i++) {
            final ShanhaiHoloMenuLayout.Board b = boards[i];
            ShanhaiHoloViewMath.boardCenterWorld(c3, ox, oy, oz, frameYawDeg + animSpinDeg, animScale,
                    b.x(), b.y(), b.z());
            final double dcx = c3[0] - camX;
            final double dcy = c3[1] - camY;
            final double dcz = c3[2] - camZ;
            nearest = Math.min(nearest, Math.sqrt(dcx * dcx + dcy * dcy + dcz * dcz));

            // ---- 板心（与 visibility 那行的 boards_in 同一口径）----
            final ShanhaiHoloViewMath.Proj pc =
                    ShanhaiHoloViewMath.proj(mvpColMajor, c3[0], c3[1], c3[2], guiW, guiH);
            if (pc.inFrustum()) {
                boardsIn++;
            }
            if (i == 0) {
                ndc0x = pc.ndcX();
                ndc0y = pc.ndcY();
            }

            // ---- 四角（板内 ±halfW / ±halfH；已含板自己的 scale 与动画缩放）----
            final double s = b.scale() * animScale;
            final double halfW = ShanhaiHoloMenuTuning.BOARD_HALF_WIDTH_BLOCKS * s;
            final double halfH = ShanhaiHoloMenuTuning.BOARD_HALF_HEIGHT_BLOCKS * s;
            final float boardWorldYaw = frameYawDeg + animSpinDeg + b.rotY();
            final int n = ShanhaiHoloViewMath.cornersInFrustum(q, mvpColMajor,
                    c3[0], c3[1], c3[2], boardWorldYaw, halfW, halfH, guiW, guiH);
            cornersIn += n;
            for (int k = 0; k < CORNERS_PER_BOARD; k++) {
                final ShanhaiHoloViewMath.Proj p = q[k];
                if (p == null) {
                    continue;
                }
                if (p.behind()) {
                    behind++;
                } else if (!p.inFrustum()) {
                    outOfNdc++;
                }
            }

            // ---- 板面法线是否朝向相机：n = R_y(worldYaw)·(0,0,1) ----
            final double rad = Math.toRadians(boardWorldYaw);
            final double nx = Math.sin(rad);
            final double nz = Math.cos(rad);
            final double vx = camX - c3[0];
            final double vy = camY - c3[1];
            final double vz = camZ - c3[2];
            final double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (!(len > 1.0e-9)) {
                face = false;
            } else if ((nx * vx + nz * vz) / len < FACE_CAMERA_MIN_DOT) {
                face = false;
            }
        }

        final double dist = nearest == Double.MAX_VALUE ? 0.0 : nearest;
        return new Report(layoutName, boardsIn, total, ndc0x, ndc0y,
                cornersIn == cornersTotal(total), (float) dist, face,
                cornersIn, cornersTotal(total), behind, outOfNdc);
    }

    /** 角总数（= 4 × 板数）。 */
    public static int cornersTotal(int boardTotal) {
        return CORNERS_PER_BOARD * boardTotal;
    }
}
