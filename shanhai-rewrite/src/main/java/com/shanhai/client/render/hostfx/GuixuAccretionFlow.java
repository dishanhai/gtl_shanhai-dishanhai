package com.shanhai.client.render.hostfx;

/**
 * 山海 · <b>外缘吸积流的纯运算模型</b>（作者 dishanhai，2026-11-22 移植进本工程）。
 *
 * <h2>来源与"纯移植"口径</h2>
 * 逐行照抄他的 {@code com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuAccretionFlow}
 * （{@code temp/upstream-bump/src/GuixuAccretionFlow.java}），<b>一个常量、一个公式都没有改</b>：
 * <ul>
 *   <li>{@link #INNER_RADIUS} = <b>51F</b>、{@link #OUTER_RADIUS} = <b>57F</b>、{@link #HALF_HEIGHT} = <b>.45F</b>；</li>
 *   <li>{@link #height} 的双波（2 阶 × .7 + 5 阶 × .3）与两个转速（−.12 / +.09）；</li>
 *   <li>{@link #angularSpeed} 开普勒差速 {@code .72 × (51/r)^1.5}；</li>
 *   <li>{@link #phase} = {@code 22s + 3orbit + .16t + .65 sin(4orbit+5s+.11t)}；</li>
 *   <li>{@link #shade} 的 24 秒双相位湍流、{@code D^3} 多普勒近似、{@code (51/r)^3} 热辐射衰减。</li>
 * </ul>
 * 他的注释逐字：「{@code 山海：外緣吸積流的純運算模型；CPU 回落與離線驗證共用，GLSL 使用相同公式。}」
 * ⇒ 本工程同样<b>只走 CPU 这一条</b>（不新开着色器、不建纹理），所以公式一致性是这一件的全部意义：
 * 他的 {@code assets/gt_shanhai/shaders/core/guixu_blackhole.fsh} 里只有透镜那一套（屏幕空间），
 * 外缘吸积流<b>没有</b>对应 GLSL（已核对：fsh 里 {@code DISK_INNER=3.0 / DISK_OUTER=11.0} 是
 * <b>以 Rs 为单位的透镜盘</b>，不是这里 51..57 格的流带）⇒ 这里不存在"CPU 与 GLSL 走样"的风险。
 *
 * <h2>🔴 零 Minecraft 依赖（硬约束，与 {@link HostFxState} 同一条纪律）</h2>
 * 本文件<b>只 import {@code java.lang}</b>（连 {@code java.util} 都不需要）⇒ 可以脱机
 * {@code javac -d out GuixuAccretionFlow.java} 直接过、离线探针直接跑（见
 * {@code temp/migrate-fx/probe/}）。
 *
 * <h2>坐标系</h2>
 * {@code radius / angle} 是<b>环心本地帧</b>（原点 = 环心那一格的格中心，环面 = XZ 平面，Y 向上），
 * 与 {@code HostFxGeometry} 的"格中心在整数上"口径一致；{@code camX/Y/Z} 也必须是同一帧里的相机坐标
 * （渲染侧给的是 {@code HostFxGeometry.cameraInRingLocal(...)} 那一份）。
 *
 * <h2>他的两条纪律（照抄，不许在本工程里改写）</h2>
 * <ol>
 *   <li><b>逐顶点不配置物件</b>：{@link #shade} 把结果写进调用方复用的 {@code rgba[4]}；</li>
 *   <li><b>footprint = 每像素的归一化径向宽度</b>：低到像素宽度以下时由 {@code filter} 把流纹滤掉
 *       （这是他在 CPU 回路上兑现"抗锯齿"的唯一手段，见 {@code density} 里的三处 {@code filter}）。</li>
 * </ol>
 */
public final class GuixuAccretionFlow {

    private GuixuAccretionFlow() {
    }

    /** 流带内缘（格，环心本地半径）—— 他的值。 */
    public static final float INNER_RADIUS = 51F;
    /** 流带外缘（格，环心本地半径）—— 他的值。 */
    public static final float OUTER_RADIUS = 57F;
    /** 流面半厚（格）—— 他的值：{@code .45}，即上下各 0.45 格的起伏。 */
    public static final float HALF_HEIGHT = .45F;

    /** 流面高度（格）：两阶角向波，相位由 {@code seconds} 推动。 */
    public static float height(float s, float angle, float seconds) {
        return (float) (HALF_HEIGHT * Math.sin(Math.PI * s)
                * (.7 * Math.sin(2 * angle - .12 * seconds) + .3 * Math.sin(5 * angle + .09 * seconds)));
    }

    /** 開普勒差速旋轉；單位 rad/s，與盤面切線 (-sin(a), 0, cos(a)) 同向。 */
    public static double angularSpeed(double s) {
        double radius = INNER_RADIUS + (OUTER_RADIUS - INNER_RADIUS) * s;
        return .72 * Math.pow(INNER_RADIUS / radius, 1.5);
    }

    /** 氣體以公轉為主，隨流座標中的正時間項只造成緩慢內漂；角頻率均為整數。 */
    public static double phase(double s, double angle, double seconds) {
        double orbit = angle - angularSpeed(s) * seconds;
        return 22 * s + 3 * orbit + .16 * seconds + .65 * Math.sin(4 * orbit + 5 * s + .11 * seconds);
    }

    /** footprint 為每像素的歸一化徑向寬度；呼叫方重用陣列，逐頂點不配置物件。 */
    public static void shade(float radius, float angle, float seconds, float footprint,
                             float camX, float camY, float camZ, float[] rgba) {
        double s = (radius - INNER_RADIUS) / (OUTER_RADIUS - INNER_RADIUS);
        double edge = smooth(0, .13, s) * (1 - smooth(.68, 1, s));
        // 兩組 24 秒壽命的湍流錯開半週期淡入淡出，避免多年世界的剪切把盤面濾成靜止光環。
        // 基準角速度 π/4 在 24 秒內恰好三圈，因此整段取模也不會在週期邊界跳動。
        double clock = seconds - Math.floor(seconds / 24D) * 24;
        double age = (clock + 12) % 24;
        double weight = .5 - .5 * Math.cos(clock * Math.PI / 12);
        double density = weight * density(s, angle, clock, clock, footprint)
                + (1 - weight) * density(s, angle, clock, age, footprint);
        double vx = camX - radius * Math.cos(angle), vy = camY - height((float) s, angle, seconds);
        double vz = camZ - radius * Math.sin(angle);
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double approach = length > 1e-5 ? (-Math.sin(angle) * vx + Math.cos(angle) * vz) / length : 0;
        // 視覺近似：D^3 多普勒增亮與徑向熱輻射衰減；不宣稱為 GRMHD／黑體光譜解算。
        double beta = .32 * Math.sqrt(INNER_RADIUS / radius);
        double doppler = Math.sqrt(1 - beta * beta) / (1 - beta * approach);
        double emissivity = Math.pow(INNER_RADIUS / radius, 3) * (1 - .55 * s);
        double heat = Math.max(0, Math.min(1, 1 - smooth(.08, .92, s) + .16 * (doppler - 1)));
        rgba[0] = 1;
        rgba[1] = (float) (.48 + .47 * heat);
        rgba[2] = (float) (.16 + .68 * heat);
        rgba[3] = (float) Math.min(.62, 1.35 * edge * emissivity * density * doppler * doppler * doppler);
    }

    private static double density(double s, double angle, double clock, double seconds, float footprint) {
        double radius = INNER_RADIUS + (OUTER_RADIUS - INNER_RADIUS) * s;
        angle -= Math.PI / 4 * (clock - seconds);
        double orbit = angle - angularSpeed(s) * seconds;
        // 差速剪切會隨時間拉細流紋，回落路徑同樣在低於像素寬度時濾掉細節。
        double shear = 1.5 * angularSpeed(s) * seconds * (OUTER_RADIUS - INNER_RADIUS) / radius;
        double radialPhase = 22 + 3 * shear + .65 * Math.cos(4 * orbit + 5 * s + .11 * seconds) * (4 * shear + 5);
        double width = Math.max(0, footprint) * Math.max(Math.abs(radialPhase), Math.abs(37 + 5 * shear));
        double filter = Math.exp(-.5 * width * width);
        double ridge = Math.pow(.5 + .5 * Math.sin(phase(s, angle, seconds)), 4);
        ridge = .2734375 + (ridge - .2734375) * filter;
        double secondary = .5 + .5 * Math.sin(37 * s + 5 * orbit + .23 * seconds);
        secondary = .5 + (secondary - .5) * filter;
        double clumpPhase = 7 * orbit + 9 * s + .18 * seconds + .8 * Math.sin(3 * orbit + 4 * s + .07 * seconds);
        double clumpWidth = Math.max(0, footprint) * (9 + 7 * shear + .8 * (4 + 3 * shear));
        double clumpFilter = Math.exp(-.5 * clumpWidth * clumpWidth);
        double clumpWave = .5 + .5 * Math.sin(clumpPhase);
        double clumps = .32 + .68 * (.375 + (clumpWave * clumpWave - .375) * clumpFilter);
        return (.10 + .72 * ridge + .18 * secondary) * clumps;
    }

    private static double smooth(double lo, double hi, double x) {
        double t = Math.max(0, Math.min(1, (x - lo) / (hi - lo)));
        return t * t * (3 - 2 * t);
    }
}
