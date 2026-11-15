package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 全息菜单的<b>纯判据工具箱</b>：注册名口径、角度规范化、NDC / 视锥 / 屏幕坐标、板的世界位姿、
 * JSON 片段。<b>纯算术 + 纯字符串，不碰 Minecraft、不碰 GL、不碰时间。</b>
 *
 * <h2>0. 为什么要有这一个类</h2>
 * 旧日志里没有"到底画没画、在不在视野里"这个信号 —— 于是"看不到"永远只能靠猜。
 * 本类负责把这件事变成<b>可离线造数据、可断言</b>的东西：
 * <ul>
 *   <li>{@link #normalizeDeg} / {@link #yawText} / {@link #pitchText} —— 角度规范化到
 *       {@code (-180, 180]}（旧日志打出过 {@code yaw=-993.45} 这种越界值，那是一行会误导人的读数）；</li>
 *   <li>{@link #namespaceOf} / {@link #isForeignId} —— <b>按注册名的命名空间</b>区分"原版 / 外来"，
 *       只看{@code namespace} 这一段，<b>不写任何具体模组的名字、不写任何具体类名</b>；</li>
 *   <li>{@link #proj} —— 世界点 → NDC / 屏幕坐标，并给出 {@code behind} / {@code in_frustum}；</li>
 *   <li>{@link #boardCenterWorld} / {@link #cornersInFrustum} —— 板在位姿上的世界坐标与四角可见性。</li>
 * </ul>
 *
 * <h2>1. 🔴 矩阵排布：列主序 {@code m[c*4 + r] = M[r][c]}</h2>
 * 这不是我挑的，是<b>本工程既有的、已在游戏里跑通的口径</b>：
 * {@code ShanhaiHoloSurround.Proj} 的字段注释逐字写着「MVP 矩阵，<b>列主序</b>：{@code m[c * 4 + r] = M[r][c]}
 * （JOML {@code Matrix4f.get(float[])} 的排布）」，而 {@code ShanhaiHoloCommandScreen} 与
 * {@code ShanhaiHoloSurroundRenderer} 都在用它做屏幕投影。
 * ⇒ 本类的 {@link #proj} 用<b>同一套下标</b>：
 * <pre>
 *   clip.x = m[0]*x + m[4]*y + m[8] *z + m[12]
 *   clip.y = m[1]*x + m[5]*y + m[9] *z + m[13]
 *   clip.z = m[2]*x + m[6]*y + m[10]*z + m[14]
 *   clip.w = m[3]*x + m[7]*y + m[11]*z + m[15]
 * </pre>
 * 光靠注释保证不了它没被写反，所以还有两道机器判据：
 * <ol>
 *   <li><b>离线</b>：{@link #selfTest()} 用"平移矩阵把点推到 x=11"这一条钉住下标
 *       （排布搞反的话同一个点会落到 x=1，见突变体）；</li>
 *   <li><b>在线</b>：渲染器第一次打 visibility 读数时会拿 JOML 自己的
 *       {@code Matrix4f.transform(Vector4f)} 对一遍同一个点，打一行 {@code holo_proj_selftest pass=true|false}。</li>
 * </ol>
 *
 * <h2>2. {@code behind} / {@code in_frustum} 的定义（任务书点名要写进注释）</h2>
 * <pre>
 *   behind      = 投影<span>前</span>的 w ≤ 0（= 相机空间 z ≥ 0，点在相机平面【背后】或正好在平面上）。
 *                 纯透视下 w = −z_view，所以 w ≤ 0 与"在背后"是同一件事。
 *   in_frustum  = 不 behind，且 |ndc.x| ≤ 1、|ndc.y| ≤ 1、−1 ≤ ndc.z ≤ 1
 *                 （z 必须落在近、远裁剪面之间 —— OpenGL 原版投影的 NDC z 区间就是 [−1, 1]）。
 *   screen      = GUI 坐标（左上角原点、+Y 朝下）：x = (ndc.x+1)/2·guiW，y = (1−ndc.y)/2·guiH。
 * </pre>
 * 🔴 {@code behind} 必须<b>先</b>判：w < 0 时照样能做除法，商往往会落在一个"看起来很合理"的
 * 区间里（自检里专门造了一个这样的点：{@code x/w} 落在 [−1,1] 内）⇒ 忘了先判 behind 的实现
 * 会把它报成"在视锥内"，那正是"明明看不到却显示 in_frustum=true"这种会误导读数。
 *
 * <h2>3. 什么时候该判哪一条（给读日志的人）</h2>
 * <table border="1">
 *   <tr><th>读数</th><th>结论</th></tr>
 *   <tr><td>{@code progress=0.000}</td><td>① <b>根本没开</b>（动画在 0，渲染器提前返回，一块板都没画）</td></tr>
 *   <tr><td>{@code progress>0, in_frustum=false}</td><td>② 开了但<b>在视锥外</b>（背对 / 偏出画面 / 出了远近裁剪面）</td></tr>
 *   <tr><td>{@code progress>0, in_frustum=true, 屏幕上还是没有}</td><td>③ 在视锥内却看不见 ⇒ 遮挡 / 深度 / 透明度问题</td></tr>
 * </table>
 */
public final class ShanhaiHoloViewMath {

    private ShanhaiHoloViewMath() {}

    // ==================================================================== 一、注册名 / 命名空间

    /**
     * 游戏本体自己的命名空间。
     * <p>🔴 这是<b>唯一</b>被写死的命名空间，而且它不是某个模组的 —— 它属于游戏本体；
     * 除它以外的一切命名空间都算"外来"，与本工程装了哪些模组无关。
     */
    public static final String VANILLA_NAMESPACE = "minecraft";

    /**
     * 注册名（{@code namespace:path}）里的<b>命名空间</b>。
     * <p>没有冒号 ⇒ 原样返回整串（那它就不是一个合法注册名）；
     * {@code null} / 空 ⇒ {@code ""}。
     */
    public static String namespaceOf(String registryId) {
        if (registryId == null) {
            return "";
        }
        final int i = registryId.indexOf(':');
        return i < 0 ? registryId : registryId.substring(0, i);
    }

    /**
     * 这一条实体注册名是不是<b>外来</b>的（{@code namespace != minecraft}）。
     *
     * <p>⚠️ 三个刻意的判法：
     * <ol>
     *   <li>按<b>命名空间整段</b>比，不许用 {@code startsWith}/{@code contains}：
     *       {@code examplemod:minecraft_like} 的命名空间是 {@code examplemod} ⇒ 外来（true），
     *       而 {@code minecraft:pig} 才是原版（false）—— 自检里有这两条负对照；</li>
     *   <li>缺冒号 / 冒号在头尾 ⇒ 不是合法注册名 ⇒ <b>fail-closed</b> 判 false
     *       （宁可漏掉一个，也不许把垃圾字符串当成"有东西在场"）；</li>
     *   <li>{@code null} ⇒ false。</li>
     * </ol>
     */
    public static boolean isForeignId(String registryId) {
        if (registryId == null) {
            return false;
        }
        final int i = registryId.indexOf(':');
        if (i <= 0 || i >= registryId.length() - 1) {
            return false;                       // 缺 namespace 或 缺 path ⇒ 不是合法注册名
        }
        return !VANILLA_NAMESPACE.equals(registryId.substring(0, i));
    }

    /** 日志用：{@code null} / 空 ⇒ {@code none}（"没有"必须写成可 grep 的一个词）。 */
    public static String idText(String registryId) {
        return registryId == null || registryId.isEmpty() ? "none" : registryId;
    }

    /**
     * 从 {@code EntityType.toString()}（= {@code getDescriptionId()}，形如 {@code entity.<ns>.<path>}）
     * 里取命名空间。**兜底用**：只有在 {@code EntityType.getKey} 拿不到注册键时才走这条路
     * （真值来源：{@code javap -c EntityType.toString} → {@code getDescriptionId}）。
     */
    public static String namespaceOfDescriptionId(String descriptionId) {
        if (descriptionId == null || descriptionId.isEmpty()) {
            return "";
        }
        final String[] parts = descriptionId.split("\\.");
        return parts.length >= 2 ? parts[1] : "";
    }

    // ==================================================================== 二、角度规范化

    /**
     * 把任意角度规范化到 <b>{@code (-180, 180]}</b>。
     * <p>口径：
     * <pre>
     *   0       → 0
     *   180     → 180        （右端闭合）
     *   -180    → 180        （同一个方向只留一个表示，左端开）
     *   540     → 180
     *   -993.45 → 86.55      （旧日志里 holo_click 打出来的那种越界值）
     *   NaN/∞   → NaN        （认不出来的角度不许伪装成 0）
     * </pre>
     */
    public static float normalizeDeg(float deg) {
        if (!Float.isFinite(deg)) {
            return Float.NaN;
        }
        float d = deg % 360.0f;
        if (d <= -180.0f) {
            d += 360.0f;
        } else if (d > 180.0f) {
            d -= 360.0f;
        }
        // −0.0f 会打印成 "-0.00"：两个符号的零是同一个方向，统一成 +0.0f
        return d == 0.0f ? 0.0f : d;
    }

    /** 偏航的日志文本：<b>先规范化再格式化</b>（{@code %.2f} + {@link Locale#ROOT}，永远点号）。 */
    public static String yawText(float rawYaw) {
        return ShanhaiHoloClickRules.angleText(normalizeDeg(rawYaw));
    }

    /** 俯仰的日志文本（同一个口径）。MC 的 pitch 本来就在 {@code [-90, 90]}，这一条对它是恒等的。 */
    public static String pitchText(float rawPitch) {
        return ShanhaiHoloClickRules.angleText(normalizeDeg(rawPitch));
    }

    /**
     * 原始角度<b>越界</b>了吗（偏航超出 {@code ±360}、俯仰超出 {@code ±90}）。
     * <p>用途：规范化之后日志里就看不见"原来是个越界值"了，而那条信息本身是诊断线索
     * （谁把偏航转了好几圈）。所以只在越界时才补打 {@code yaw_raw=…}（见调用点）。
     */
    public static boolean angleOutOfRange(float rawYaw, float rawPitch) {
        return !Float.isFinite(rawYaw) || !Float.isFinite(rawPitch)
                || Math.abs(rawYaw) > 360.0f || Math.abs(rawPitch) > 90.0f;
    }

    // ==================================================================== 三、投影 / 视锥

    /**
     * 一个世界点的投影读数（不可变）。
     *
     * @param behind   投影<b>前</b> w ≤ 0（点在相机平面背后或平面上）⇒ 此时 ndc 无意义
     * @param inFrustum 不 behind，且 |ndc.x| ≤ 1、|ndc.y| ≤ 1、−1 ≤ ndc.z ≤ 1
     * @param ndcX     NDC x（behind 或非有限值 ⇒ NaN）
     * @param ndcY     NDC y（同上）
     * @param ndcZ     NDC z（NDC z ∈ [−1,1] = 在近远裁剪面之间）
     * @param screenX  GUI 坐标 x（左上角原点）；不可投影 ⇒ {@code -1}
     * @param screenY  GUI 坐标 y（+Y 朝下）；不可投影 ⇒ {@code -1}
     * @param w        裁剪空间的 w（就是"距离相机平面多远"，仅在 w>0 时有几何意义）
     */
    public record Proj(boolean behind, boolean inFrustum,
                       float ndcX, float ndcY, float ndcZ,
                       int screenX, int screenY, float w) {

        /** 一个"没有投影"的读数（板一块没画 / 矩阵还没准备好时用它，别用 null）。 */
        public static final Proj NONE =
                new Proj(false, false, Float.NaN, Float.NaN, Float.NaN, -1, -1, 0.0f);

        /** 日志里的 {@code ndc=(x,y)} 文本（behind 时是 {@code (nan,nan)}，不是 (0,0)）。 */
        public String ndcText() {
            return "(" + fmt4(ndcX) + "," + fmt4(ndcY) + ")";
        }
    }

    /**
     * 世界点 → NDC / 屏幕坐标 / 视锥判定（<b>纯函数</b>）。
     *
     * @param mvpColMajor MVP 矩阵，<b>列主序</b>（{@code m[c*4 + r] = M[r][c]}，即 JOML
     *                    {@code Matrix4f.get(float[])} 的排布；见类注释 §1）
     * @param x,y,z       世界坐标
     * @param guiW,guiH   GUI 缩放的屏幕宽高（{@code Window#getGuiScaledWidth/Height}）；
     *                    任一 ≤ 0 ⇒ 只算 NDC，屏幕坐标给 {@code -1}
     */
    public static Proj proj(float[] mvpColMajor, double x, double y, double z, int guiW, int guiH) {
        if (mvpColMajor == null || mvpColMajor.length < 16) {
            return Proj.NONE;
        }
        final float[] m = mvpColMajor;
        final float fx = (float) x;
        final float fy = (float) y;
        final float fz = (float) z;
        final float cx = m[0] * fx + m[4] * fy + m[8] * fz + m[12];
        final float cy = m[1] * fx + m[5] * fy + m[9] * fz + m[13];
        final float cz = m[2] * fx + m[6] * fy + m[10] * fz + m[14];
        final float cw = m[3] * fx + m[7] * fy + m[11] * fz + m[15];
        // 🔴 behind 必须先判：w<0 时除法照样能算出一个"看着像在画面里"的数（见类注释 §2）
        if (!Float.isFinite(cw) || cw <= 0.0f) {
            return new Proj(true, false, Float.NaN, Float.NaN, Float.NaN, -1, -1, cw);
        }
        final float inv = 1.0f / cw;
        final float nx = cx * inv;
        final float ny = cy * inv;
        final float nz = cz * inv;
        final boolean in = nx >= -1.0f && nx <= 1.0f
                && ny >= -1.0f && ny <= 1.0f
                && nz >= -1.0f && nz <= 1.0f;
        if (guiW <= 0 || guiH <= 0) {
            return new Proj(false, in, nx, ny, nz, -1, -1, cw);
        }
        // GUI 坐标：左上角原点、+Y 朝下 ⇒ y 要翻一次
        final int sx = clampScreen(Math.round((nx + 1.0f) * 0.5f * guiW));
        final int sy = clampScreen(Math.round((1.0f - ny) * 0.5f * guiH));
        return new Proj(false, in, nx, ny, nz, sx, sy, cw);
    }

    /** 屏幕坐标的钳位（NDC 跑到几十万倍时 {@code Math.round} 会溢出 int —— 日志里要一个能读的数）。 */
    private static int clampScreen(int v) {
        return v < -999_999 ? -999_999 : (v > 999_999 ? 999_999 : v);
    }

    // ==================================================================== 四、板的世界位姿

    /**
     * 一块板的<b>板心世界坐标</b>（与渲染器 {@code applyHoloFrame → poseBoard} 同一个变换，
     * 只省掉 0.004 格的抖动 {@code applyJitter}）。
     *
     * <p>{@code Rot_Y(θ)·(x,y,z) = (x·cosθ + z·sinθ, y, −x·sinθ + z·cosθ)}
     * —— 与 {@code Axis.YP.rotationDegrees} 和 {@code ShanhaiHoloMenuLayout.worldPosOf} 同一个式子。
     *
     * @param out3 出口（长度 ≥ 3）
     * @param ox,oy,oz 全息原点世界坐标（已含展开动画的原点插值）
     * @param yawDeg 板所处的纵轴偏航（= 框偏航 + 展开自转）
     * @param scale  整块缩放（展开动画 {@code scale01()}；正常态 = 1）
     * @param lx,ly,lz 板在<b>全息本体坐标系</b>里的局部位移（{@code Board#x()/y()/z()}）
     */
    public static void boardCenterWorld(double[] out3, double ox, double oy, double oz,
                                        float yawDeg, float scale,
                                        double lx, double ly, double lz) {
        final double rad = Math.toRadians(yawDeg);
        final double c = Math.cos(rad);
        final double s = Math.sin(rad);
        final double x = lx * scale;
        final double y = ly * scale;
        final double z = lz * scale;
        out3[0] = ox + x * c + z * s;
        out3[1] = oy + y;
        out3[2] = oz - x * s + z * c;
    }

    /**
     * 一块板<b>四角里有几个</b>落在视锥内（0..4）。
     *
     * <p>四角 = 板心 ± 板内"右"方向 · 半宽 ± 世界上方向 · 半高。
     * 板内 +X 在世界里的方向 = {@code (cos yaw, 0, −sin yaw)}（与
     * {@code ShanhaiHoloCommandScreen#projectPanelRect} 同一句），<b>忽略绕 X 的倾角</b>
     * —— 列排版/横排档两端最多倾 42°，那会让四角估计偏保守一点点（见报告「没验到的」）。
     *
     * @param out4 可空；非空时按 {@code k&1}（左/右）、{@code k<2}（下/上）的顺序写进 4 个投影读数
     * @param halfW,halfH 板的世界半宽 / 半高（已含板自己的 scale 与整块动画缩放）
     */
    public static int cornersInFrustum(Proj[] out4, float[] mvpColMajor,
                                       double cx, double cy, double cz,
                                       float boardWorldYawDeg, double halfW, double halfH,
                                       int guiW, int guiH) {
        final double rad = Math.toRadians(boardWorldYawDeg);
        final double rx = Math.cos(rad);        // 板内 +X 在世界 XZ 平面上的方向
        final double rz = -Math.sin(rad);
        int n = 0;
        for (int k = 0; k < 4; k++) {
            final double sx = (k & 1) == 0 ? -1.0 : 1.0;
            final double sy = k < 2 ? -1.0 : 1.0;
            final Proj p = proj(mvpColMajor,
                    cx + rx * halfW * sx, cy + halfH * sy, cz + rz * halfW * sx, guiW, guiH);
            if (out4 != null) {
                out4[k] = p;
            }
            if (p.inFrustum()) {
                n++;
            }
        }
        return n;
    }

    // ==================================================================== 五、供自检 / 离线探针用的矩阵构造

    /**
     * 与 JOML {@code Matrix4f.perspective(fovY, aspect, near, far)} <b>同一个式子</b>（列主序出口）。
     * <p>只用在下文自检与离线探针里造数据；游戏里用的是事件给的真实投影矩阵。
     */
    public static float[] perspective(float fovYRad, float aspect, float near, float far) {
        final float f = 1.0f / (float) Math.tan(fovYRad * 0.5f);
        final float[] m = new float[16];
        m[0] = f / aspect;                          // m00（c0r0）
        m[5] = f;                                   // m11（c1r1）
        m[10] = (far + near) / (near - far);        // m22（c2r2）
        m[11] = -1.0f;                              // m23（c2r3）⇒ clip.w = −z
        m[14] = 2.0f * far * near / (near - far);   // m32（c3r2）
        return m;
    }

    /** 单位矩阵（列主序）。 */
    public static float[] identity4() {
        final float[] m = new float[16];
        m[0] = 1.0f;
        m[5] = 1.0f;
        m[10] = 1.0f;
        m[15] = 1.0f;
        return m;
    }

    /** 平移矩阵（列主序；{@code t} 进第 3 列 ⇒ 下标 12/13/14）。 */
    public static float[] translation4(float tx, float ty, float tz) {
        final float[] m = identity4();
        m[12] = tx;                                 // m30（c3r0）
        m[13] = ty;                                 // m31
        m[14] = tz;                                 // m32
        return m;
    }

    /** 绕 Y 的旋转（列主序）：{@code M·p = (x·cos + z·sin, y, −x·sin + z·cos)}。 */
    public static float[] rotY4(float yawDeg) {
        final float rad = (float) Math.toRadians(yawDeg);
        final float c = (float) Math.cos(rad);
        final float s = (float) Math.sin(rad);
        final float[] m = identity4();
        m[0] = c;           // m00
        m[8] = s;           // m20（c2r0）
        m[2] = -s;          // m02（c0r2）
        m[10] = c;          // m22
        return m;
    }

    /** 4×4 相乘（列主序出口，{@code out = a·b}）：{@code C[c][r] = ΣK A[k][r]·B[c][k]}。 */
    public static float[] mul4(float[] a, float[] b) {
        final float[] out = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float acc = 0.0f;
                for (int k = 0; k < 4; k++) {
                    acc += a[k * 4 + r] * b[c * 4 + k];
                }
                out[c * 4 + r] = acc;
            }
        }
        return out;
    }

    // ==================================================================== 六、JSON 片段（离线可验）

    /**
     * JSON 数字：非有限值一律写 {@code null}。
     * <p>🔴 {@code nan} 不是合法 JSON —— 一块板转到背后时 {@code ndc} 就是 NaN，
     * 直接打会造出 {@code [nan,nan,nan]}，整行读数<b>解析不了</b>（那比没有读数更糟）。
     */
    public static String jsonNum(double v) {
        return Double.isFinite(v) ? fmt4(v) : "null";
    }

    /** JSON 字符串里的转义（只处理 {@code \\} 与 {@code "}；本工程的标签里没有控制字符）。 */
    public static String jsonEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 一块板的 JSON 片段（{@code /shanhai menu dump} 的 {@code visibility_dump} 里那个元素）。
     *
     * <p>把它做成<b>纯函数</b>的理由：这是整份读数里唯一"一改就静默坏掉"的地方
     * （少一个引号 / NaN 漏进 JSON），而它输入全是数 ⇒ 可以在没有游戏的机器上造数据验。
     * <p>{@code proj == null} ⇒ 投影那一组字段写 {@code null}（还没拿到 MVP）。
     */
    public static String boardJson(int index, String zh,
                                   double px, double py, double pz,
                                   double lx, double ly, double lz,
                                   float rotX, float rotY, float worldYaw, float scale,
                                   double eyeDist, Proj proj, int cornersIn) {
        final StringBuilder sb = new StringBuilder(320);
        sb.append("{\"i\":").append(index)
                .append(",\"zh\":\"").append(jsonEsc(zh)).append('"')
                .append(",\"pos\":[").append(fmt3(px)).append(',').append(fmt3(py)).append(',')
                .append(fmt3(pz)).append(']')
                .append(",\"local\":[").append(fmt3(lx)).append(',').append(fmt3(ly)).append(',')
                .append(fmt3(lz)).append(']')
                .append(",\"rot_x\":").append(fmt2(rotX))
                .append(",\"rot_y\":").append(fmt2(rotY))
                .append(",\"world_yaw\":").append(fmt2(worldYaw))
                .append(",\"scale\":").append(fmt3(scale))
                .append(",\"eye_dist\":").append(fmt2(eyeDist));
        if (proj == null) {
            sb.append(",\"ndc\":null,\"in_frustum\":null,\"behind\":null,\"screen\":null");
        } else {
            sb.append(",\"ndc\":[").append(jsonNum(proj.ndcX())).append(',')
                    .append(jsonNum(proj.ndcY())).append(',')
                    .append(jsonNum(proj.ndcZ())).append(']')
                    .append(",\"in_frustum\":").append(proj.inFrustum())
                    .append(",\"behind\":").append(proj.behind())
                    .append(",\"screen\":[").append(proj.screenX()).append(',')
                    .append(proj.screenY()).append(']')
                    .append(",\"corners_in\":").append(cornersIn);
        }
        sb.append('}');
        return sb.toString();
    }

    // ==================================================================== 七、离线自检

    /** 自检结果（与 {@link ShanhaiHoloClickRules.Report} 同一个形状，便于同一套读法）。 */
    public record Report(int pass, int total, String bad) {
        public boolean ok() {
            return bad == null;
        }
    }

    /**
     * <b>离线自检</b>（不碰 Minecraft、不碰 GL；矩阵全部在这里现造）。
     *
     * <p>判据分四组，每组都带<b>负对照</b>（"这条判据真的会红"）：
     * <pre>
     *   A 角度规范化   (-180,180] 的边界、越界值、非有限值、格式化口径（点号）
     *   B 投影/视锥    behind 先判（带一个"w&lt;0 但商落在 [−1,1] 里"的陷阱点）、
     *                  上下左右边界、近/远裁剪面、屏幕 Y 翻转、旋转相机（证明矩阵真被用了）
     *   C 命名空间     只认 namespace 整段（examplemod:minecraft_like 必须 true、minecraft:pig 必须 false）
     *   D 板的世界位姿 Rot_Y 生效、缩放生效、"原点不动"
     * </pre>
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};

        // ---------------------------------------------------------------- 造矩阵
        final float[] persp = perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
        final float[] mvp70 = persp;                       // 相机在原点、朝 −Z
        // fovY=90°、aspect=1 ⇒ f=1 ⇒ ndc = (x/−z, y/−z)：边界值可以精确地等于 1.0，便于钉边界
        final float[] mvp90 = perspective((float) Math.toRadians(90.0), 1.0f, 0.05f, 1000.0f);
        final float[] mvpRot90 = mul4(mvp90, rotY4(90.0f));
        final int gw = 1920;
        final int gh = 1080;

        // ================================================================ A 角度
        c(bad, n, "A1 负对照用例：-993.45 规范化后 = 86.55",
                approx(normalizeDeg(-993.45f), 86.55f, 1.0e-3f), true);
        c(bad, n, "A2 yawText(-993.45) = \"86.55\"（holo_click 那一行的新口径）",
                yawText(-993.45f), "86.55");
        c(bad, n, "A3 0 ⇒ 0", normalizeDeg(0.0f), 0.0f);
        c(bad, n, "A4 右端闭合：180 ⇒ 180", normalizeDeg(180.0f), 180.0f);
        c(bad, n, "A5 左端开：-180 ⇒ +180（(-180,180] 只留一个表示）", normalizeDeg(-180.0f), 180.0f);
        c(bad, n, "A6 多转一圈：540 ⇒ 180", normalizeDeg(540.0f), 180.0f);
        c(bad, n, "A7 负数多转两圈：-540 ⇒ 180", normalizeDeg(-540.0f), 180.0f);
        c(bad, n, "A8 负零不打印成 -0.00", Float.floatToRawIntBits(normalizeDeg(-0.0f)),
                Float.floatToRawIntBits(0.0f));
        c(bad, n, "A9 NaN 原样给 NaN（不许伪装成 0）", Float.isNaN(normalizeDeg(Float.NaN)), true);
        c(bad, n, "A10 无穷给 NaN", Float.isNaN(normalizeDeg(Float.POSITIVE_INFINITY)), true);
        c(bad, n, "A11 pitch 越界 -270 ⇒ 90", approx(normalizeDeg(-270.0f), 90.0f, 1.0e-3f), true);
        // 性质测试：把 −1000..1000 扫一遍，规范化后必须全部落在 (−180,180]
        int outOfRange = 0;
        int rawOutOfRange = 0;
        for (float v = -1000.0f; v <= 1000.0f; v += 7.0f) {
            final float d = normalizeDeg(v);
            if (!(d > -180.0f && d <= 180.0f)) {
                outOfRange++;
            }
            if (!(v > -180.0f && v <= 180.0f)) {
                rawOutOfRange++;
            }
        }
        c(bad, n, "A12 性质：扫 −1000..1000（步长 7）规范化后全部落在 (−180,180]", outOfRange, 0);
        c(bad, n, "A13 负对照：同一个扫描里【未规范化】的值确实有越界（证明 A12 不是空转）",
                rawOutOfRange > 0, true);
        c(bad, n, "A14 angleOutOfRange(-993.45, 12) 必须为 true（要补打 yaw_raw）",
                angleOutOfRange(-993.45f, 12.0f), true);
        c(bad, n, "A15 负对照：angleOutOfRange(-86.55, 12) 必须为 false（正常游玩不刷 raw）",
                angleOutOfRange(-86.55f, 12.0f), false);
        c(bad, n, "A16 yawText 里必须是点号不是逗号", yawText(12.345f).indexOf(',') < 0, true);

        // ================================================================ B 投影
        // B1 正前方 5 格 ⇒ 屏幕正中、在视锥内
        final Proj front = proj(mvp70, 0.0, 0.0, -5.0, gw, gh);
        c(bad, n, "B1 正前方 (0,0,−5)：in_frustum=true", front.inFrustum(), true);
        c(bad, n, "B2 正前方 (0,0,−5)：behind=false", front.behind(), false);
        c(bad, n, "B3 正前方：ndc=(0,0)", approx(front.ndcX(), 0.0f, 1.0e-6f) && approx(front.ndcY(), 0.0f, 1.0e-6f), true);
        c(bad, n, "B4 正前方：screen=(960,540)（GUI 坐标，左上角原点）",
                front.screenX() == gw / 2 && front.screenY() == gh / 2, true);

        // B5 背后 ⇒ behind=true、in_frustum=false（不管商是多少）
        final Proj back = proj(mvp70, 0.0, 0.0, 5.0, gw, gh);
        c(bad, n, "B5 背后 (0,0,+5)：behind=true", back.behind(), true);
        c(bad, n, "B6 背后：in_frustum=false", back.inFrustum(), false);
        c(bad, n, "B7 背后：ndc 记 NaN（无意义的值不许伪装成读数）", Float.isNaN(back.ndcX()), true);
        c(bad, n, "B8 背后：screen=(-1,-1)", back.screenX() == -1 && back.screenY() == -1, true);

        // B9 🔴 陷阱点：w<0，但 x/w、y/w 都落在 [−1,1] 里 ⇒ 忘了先判 behind 的实现会报"在视锥内"
        final float[] trapM = mvp70;
        final double trapX = 0.10;
        final double trapY = 0.10;
        final double trapZ = 5.0;
        final float trapW = (float) (trapM[3] * trapX + trapM[7] * trapY + trapM[11] * trapZ + trapM[15]);
        final float trapNdcX = (float) (trapM[0] * trapX + trapM[4] * trapY + trapM[8] * trapZ + trapM[12]) / trapW;
        final float trapNdcY = (float) (trapM[1] * trapX + trapM[5] * trapY + trapM[9] * trapZ + trapM[13]) / trapW;
        c(bad, n, "B9 陷阱：该点 w<0，且 x/w 落在 [−1,1] 内（这条判据有意义的前提）",
                trapW < 0.0f && Math.abs(trapNdcX) <= 1.0f && Math.abs(trapNdcY) <= 1.0f, true);
        final Proj trap = proj(mvp70, trapX, trapY, trapZ, gw, gh);
        c(bad, n, "B10 陷阱点必须报 behind=true & in_frustum=false（先判 behind 的铁证）",
                trap.behind() && !trap.inFrustum(), true);

        // B11 fov90/aspect1 下的精确边界：ndc 恰好 = ±1 ⇒ 仍算在视锥内（闭区间）
        final Proj edge = proj(mvp90, 5.0, 0.0, -5.0, gw, gh);
        c(bad, n, "B11 边界 |ndc.x|=1.000000 算在视锥内（闭区间；此处精确等于 1）",
                approx(edge.ndcX(), 1.0f, 1.0e-6f) && edge.inFrustum(), true);
        c(bad, n, "B12 负对照：|ndc.x|=1.0001 必须判在视锥外",
                proj(mvp90, 5.0005, 0.0, -5.0, gw, gh).inFrustum(), false);
        c(bad, n, "B13 负对照：|ndc.y|=1.1 必须判在视锥外",
                proj(mvp90, 0.0, 5.5, -5.0, gw, gh).inFrustum(), false);

        // B14 近裁剪面之内（0.01 < near 0.05）⇒ ndc.z < −1 ⇒ 在视锥外，但【不是】behind
        final Proj nearPt = proj(mvp90, 0.0, 0.0, -0.01, gw, gh);
        c(bad, n, "B14 比近平面更近：ndc.z<−1 ⇒ in_frustum=false（且 behind=false，两种病因要分得开）",
                !nearPt.behind() && nearPt.ndcZ() < -1.0f && !nearPt.inFrustum(), true);
        // B15 比远平面更远 ⇒ ndc.z > 1 ⇒ 在视锥外
        final Proj farPt = proj(mvp90, 0.0, 0.0, -2000.0, gw, gh);
        c(bad, n, "B15 比远平面更远：ndc.z>1 ⇒ in_frustum=false",
                farPt.ndcZ() > 1.0f && !farPt.inFrustum(), true);
        // B16 近远之间 ⇒ 在视锥内（正对照，证明 B14/B15 不是恒 false）
        final Proj mid = proj(mvp90, 0.0, 0.0, -500.0, gw, gh);
        c(bad, n, "B16 正对照：(−500) 在近远裁剪面之间 ⇒ in_frustum=true",
                mid.ndcZ() >= -1.0f && mid.ndcZ() <= 1.0f && mid.inFrustum(), true);

        // B17 屏幕 Y 翻转：画面上方的点，GUI y 必须【小于】半屏
        final Proj up = proj(mvp90, 0.0, 2.5, -5.0, gw, gh);
        c(bad, n, "B17 屏幕上方的点：GUI y < 半屏（+Y 朝下，必须翻）", up.screenY() < gh / 2, true);
        c(bad, n, "B18 负对照：如果不翻 Y，同一个点会落在 810（≠ 现在的读数）", up.screenY() != 810, true);
        final Proj right = proj(mvp90, 2.5, 0.0, -5.0, gw, gh);
        c(bad, n, "B19 屏幕右侧的点：GUI x > 半屏", right.screenX() > gw / 2, true);

        // B20 矩阵真的被用到了：相机转 90° 之后，世界点 (5,0,0) 落到正中
        final Proj rotated = proj(mvpRot90, 5.0, 0.0, 0.0, gw, gh);
        c(bad, n, "B20 相机转 90° 后 (5,0,0) 落在正中 ⇒ 矩阵乘法生效",
                approx(rotated.ndcX(), 0.0f, 1.0e-6f) && rotated.inFrustum(), true);
        c(bad, n, "B21 负对照：同一个点在【不转】的相机里 w=0 ⇒ behind=true（不是 ndc.x=1）",
                proj(mvp90, 5.0, 0.0, 0.0, gw, gh).behind(), true);
        c(bad, n, "B21b 负对照续：把那个点往前挪 5 格，不转的相机给出 ndc.x=1（≠ B20 的 0）",
                approx(proj(mvp90, 5.0, 0.0, -5.0, gw, gh).ndcX(), 1.0f, 1.0e-6f), true);

        // B22 列主序下标：平移矩阵把点推到 x=11（排布搞反会得到 x=1）
        final Proj shifted = proj(translation4(10.0f, 0.0f, 0.0f), 1.0, 2.0, 3.0, gw, gh);
        c(bad, n, "B22 列主序：T(10,0,0)·(1,2,3) ⇒ ndc.x=11（搞反会得到 1）",
                approx(shifted.ndcX(), 11.0f, 1.0e-5f), true);
        // B23 mul4 的下标也要钉：单位矩阵乘任何矩阵等于它自己
        final float[] rot30 = rotY4(30.0f);
        final float[] rot60 = rotY4(60.0f);
        final float[] rot90 = mul4(rot60, rot30);
        final Proj viaMul = proj(rot90, 1.0, 0.0, 0.0, 0, 0);
        c(bad, n, "B23 mul4：Rot_Y(60)·Rot_Y(30) = Rot_Y(90) ⇒ (1,0,0) 落到 (0,0,−1)",
                approx(viaMul.ndcX(), 0.0f, 1.0e-6f) && approx(viaMul.ndcZ(), -1.0f, 1.0e-6f), true);
        final Proj unit = proj(mul4(identity4(), mvp90), 2.0, 0.0, -5.0, 0, 0);
        c(bad, n, "B24 mul4 负对照：I·M ≠ 转置后的 M（这里用 ndc.x=0.4 钉住）",
                approx(unit.ndcX(), 0.4f, 1.0e-6f), true);
        // B25 空矩阵 ⇒ 优雅降级（不许 NPE）
        c(bad, n, "B25 矩阵给 null ⇒ 返回 Proj.NONE（不抛）", proj(null, 0, 0, 0, gw, gh) == Proj.NONE, true);
        // B26 矩阵没准备好（全 0）⇒ w=0 ⇒ behind，不许伪装成"在画面正中"
        c(bad, n, "B26 全零矩阵 ⇒ behind=true（w=0 不算在前面）",
                proj(new float[16], 1.0, 2.0, 3.0, gw, gh).behind(), true);

        // ================================================================ C 命名空间（原版 / 外来）
        // 🔴 样本一律用中立假数据（example / examplemod），不出现任何真实模组的名字。
        c(bad, n, "C1 example:thing 是外来命名空间", isForeignId("example:thing"), true);
        c(bad, n, "C2 examplemod:another_thing 也是外来（只认命名空间，不认某个类）",
                isForeignId("examplemod:another_thing"), true);
        c(bad, n, "C3 负对照：minecraft:pig 是原版（不算外来）", isForeignId("minecraft:pig"), false);
        c(bad, n, "C4 负对照：examplemod:minecraft_like 的 path 里带 minecraft，但命名空间是"
                + " examplemod ⇒ 仍然算外来", isForeignId("examplemod:minecraft_like"), true);
        c(bad, n, "C5 负对照：minecraftx:pig 的命名空间是 minecraftx（整段比，不是 startsWith）⇒ 算外来",
                isForeignId("minecraftx:pig"), true);
        c(bad, n, "C6 负对照：没有命名空间的 example_thing 不算（那不是合法注册名 ⇒ fail-closed）",
                isForeignId("example_thing"), false);
        c(bad, n, "C7 负对照：缺 path 的 example: 不算", isForeignId("example:"), false);
        c(bad, n, "C8 负对照：缺 namespace 的 :thing 不算", isForeignId(":thing"), false);
        c(bad, n, "C9 负对照：null 不算", isForeignId(null), false);
        c(bad, n, "C10 namespaceOf 只取冒号前那一段", namespaceOf("a:b:c"), "a");
        c(bad, n, "C11 idText(null)=\"none\"（日志里「没有」必须是一个可 grep 的词）",
                idText(null), "none");
        c(bad, n, "C12 idText(\"example:thing\") 原样", idText("example:thing"), "example:thing");
        c(bad, n, "C13 描述名兜底：entity.example.thing ⇒ example",
                namespaceOfDescriptionId("entity.example.thing"), "example");
        c(bad, n, "C14 负对照：entity.minecraft.pig 的描述名 ⇒ minecraft",
                namespaceOfDescriptionId("entity.minecraft.pig"), "minecraft");
        c(bad, n, "C15 负对照：空描述名 ⇒ 空串（不许抛）", namespaceOfDescriptionId(null), "");

        // ================================================================ D 板的世界位姿
        final double[] p3 = new double[3];
        boardCenterWorld(p3, 10.0, 20.0, 30.0, 0.0f, 1.0f, 1.0, 0.0, 0.0);
        c(bad, n, "D1 偏航 0、局部 (1,0,0) ⇒ 世界 (+1,0,0)",
                approx(p3[0], 11.0, 1.0e-9) && approx(p3[1], 20.0, 1.0e-9) && approx(p3[2], 30.0, 1.0e-9), true);
        boardCenterWorld(p3, 10.0, 20.0, 30.0, 90.0f, 1.0f, 1.0, 0.0, 0.0);
        c(bad, n, "D2 偏航 90°、局部 (1,0,0) ⇒ 世界 (0,0,−1)（Rot_Y 生效）",
                approx(p3[0], 10.0, 1.0e-9) && approx(p3[2], 29.0, 1.0e-9), true);
        c(bad, n, "D3 负对照：D1 与 D2 的结果必须不同（否则 D2 是空转）",
                approx(p3[0], 10.0, 1.0e-9) && !approx(p3[0], 11.0, 1.0e-9), true);
        boardCenterWorld(p3, 0.0, 0.0, 0.0, 0.0f, 0.5f, 2.0, 4.0, 0.0);
        c(bad, n, "D4 整块缩放 0.5 ⇒ 局部 (2,4) 变成世界 (1,2)",
                approx(p3[0], 1.0, 1.0e-9) && approx(p3[1], 2.0, 1.0e-9), true);
        // 四角判据：板心在正前方、半宽 1 ⇒ fov90 下板宽占满整屏（x=±1 恰好边界 ⇒ 4 角全在）
        final int all4 = cornersInFrustum(null, mvp90, 0.0, 0.0, -5.0, 0.0f, 5.0, 5.0, gw, gh);
        c(bad, n, "D5 正对照：半宽/半高恰好顶到边界 ⇒ 4 角全在视锥内", all4, 4);
        final int none4 = cornersInFrustum(null, mvp90, 0.0, 0.0, 5.0, 0.0f, 5.0, 5.0, gw, gh);
        c(bad, n, "D6 负对照：整块板跑到相机背后 ⇒ 4 角一个都不在", none4, 0);

        // ================================================================ E JSON 片段（机器可读性）
        // 🔴 一条"看不见的坏"：NaN 漏进 JSON ⇒ 整行解析失败。这里正面钉住 "null"。
        final Proj behind = proj(mvp90, 0.0, 0.0, 5.0, gw, gh);
        // 标签里故意放一个【英文双引号】：不转义就会把 JSON 截断（E3 就是钉这个的）
        final String behindJson = boardJson(2, "配方\"修改",
                0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0f, 0.0f, 0.0f, 1.0f, 5.0, behind, 0);
        c(bad, n, "E1 behind 的板：ndc 必须写成 [null,null,null]（nan 不是合法 JSON）",
                behindJson.contains("\"ndc\":[null,null,null]"), true);
        c(bad, n, "E2 behind 的板：in_frustum=false / behind=true 都在片段里",
                behindJson.contains("\"in_frustum\":false") && behindJson.contains("\"behind\":true"), true);
        c(bad, n, "E3 片段里的引号被转义（标签含 \" 也不会把 JSON 截断）",
                behindJson.indexOf("\\\"") > 0, true);
        c(bad, n, "E4 片段的大括号/方括号配平", jsonBalanced(behindJson), true);
        final String frontJson = boardJson(0, "配方修改", 0.0, 0.0, -5.0, 0.0, 0.0, 0.0,
                0.0f, 180.0f, 180.0f, 1.0f, 5.0, proj(mvp90, 0.0, 0.0, -5.0, gw, gh), 4);
        c(bad, n, "E5 正对照：看得见的板 in_frustum=true、corners_in=4、ndc 是数不是 null",
                frontJson.contains("\"in_frustum\":true") && frontJson.contains("\"corners_in\":4")
                        && genuineJsonNumber(frontJson), true);
        c(bad, n, "E6 负对照：null 投影 ⇒ 投影那组字段全写 null（不许抛）",
                boardJson(0, "x", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, null, 0)
                        .contains("\"ndc\":null,\"in_frustum\":null"), true);
        c(bad, n, "E7 jsonNum(NaN)=null，jsonNum(1.5)=1.5000", "null".equals(jsonNum(Double.NaN))
                && "1.5000".equals(jsonNum(1.5)), true);

        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b);
    }

    /** JSON 片段里 {@code {[} 与 {@code }]} 是否配平（不做完整语法树，只看计数与嵌套下界）。 */
    public static boolean jsonBalanced(String s) {
        if (s == null) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    /** 片段里至少有一个 {@code :<数字>} 形态的实数值（证明 ndc 那组不是全 null）。 */
    private static boolean genuineJsonNumber(String s) {
        return s.matches("(?s).*\"ndc\":\\[-?\\d+\\.\\d+.*");
    }

    /** 一行自检读数（进日志；{@code PASS=false} 时把每一条失败的原文列出来）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_viewmath_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok()
                + (r.ok()
                        ? "（角度规范化 (-180,180] + NDC/视锥判定 + 原版/外来命名空间 + 板心世界坐标；"
                                + "含 B9/B10 背后陷阱、B12/B13 边界外、C3-C9 命名空间负对照）"
                        : " FAILED:" + r.bad());
    }

    /**
     * 离线入口：{@code java -cp <只有本类所在目录> com.shanhai.client.holo.ShanhaiHoloViewMath}
     * <p>输出一行 {@code holo_viewmath_selftest pass/total PASS=true|false}，退出码 0/1。
     * <p>⚠️ 本类<b>不依赖 Minecraft</b> ⇒ 这条自检不需要构建 mod、不需要开游戏。
     */
    public static void main(String[] args) {
        final Report r = selfTest();
        System.out.println("holo_viewmath_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok());
        if (!r.ok()) {
            System.out.println("FAILED:" + r.bad());
            System.exit(1);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ 断言器

    private static boolean approx(double a, double b, double eps) {
        return Double.isFinite(a) && Math.abs(a - b) <= eps;
    }

    private static boolean approx(float a, float b, float eps) {
        return Float.isFinite(a) && Math.abs(a - b) <= eps;
    }

    private static void c(StringBuilder bad, int[] n, String what, boolean actual, boolean expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, int actual, int expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, String actual, String expected) {
        n[1]++;
        if (java.util.Objects.equals(actual, expected)) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 `").append(actual)
                    .append("`，应当 `").append(expected).append("`;");
        }
    }

    /** 浮点比较版（同一套 {@code c(...)}，只是把"相等"换成"差 ≤ eps"）。 */
    private static void c(StringBuilder bad, int[] n, String what, float actual, float expected) {
        n[1]++;
        if (Float.compare(actual, expected) == 0) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
        }
    }

    /** 定点小数（Locale.ROOT；日志专用）。 */
    public static String fmt2(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "nan";
    }

    /** 定点小数（Locale.ROOT；日志专用）。 */
    public static String fmt3(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.3f", v) : "nan";
    }

    /** 定点小数（Locale.ROOT；NDC 用 4 位）。 */
    public static String fmt4(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : "nan";
    }
}
