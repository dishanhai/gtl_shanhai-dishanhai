package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * 归墟视界枢核 · <b>内外环之间那圈"伪神光柱环"</b>的纯几何（<b>零渲染状态、零 GL</b>）。
 *
 * <h2>用户口径（原话，逐字）</h2>
 * <pre>
 *   「我希望可以把<b>伪神的那个光柱变粗</b>，然后<b>围绕在外环和内环中间</b>，
 *     就是在外环和内环中间用<b>伪神的光柱画一个环</b>，<b>要很粗，直径得有 5-7 格</b>」
 * </pre>
 * ⇒ 交付物是<b>一条真正的三维管环（torus）</b>：中心线半径 {@link #RING_R}、管半径 {@link #TUBE_R}
 * （管径 = 2×{@link #TUBE_R} = {@link #DIAMETER} 格，落在用户给的 5~7 区间正中偏上）；
 * 材质 = 与伪神之锻炉<b>同一张</b> {@code space_layer.png} + <b>同一套</b> rim 光照公式 + 同样两遍
 * Intensity 2.0 / 4.0（见 {@code HostFxBeamRing}）。
 *
 * <h2>半径为什么是 54.00 —— 逐项实测，不是拍出来的</h2>
 * 结构体（{@code guixu_vision_core.bin}，148×135×131）按"格中心半径"统计（离线脚本
 * {@code temp/host-fx/ringar/radial-occupancy.mjs} + {@code spokes.mjs}，读数见报告）：
 * <table border="1">
 *   <tr><th>径向区间（格）</th><th>那里是什么</th><th>对管环的意义</th></tr>
 *   <tr><td>46.5 .. 49.5</td><td><b>内环带</b>（y 8..12，最外表面 r = <b>49.50</b>）</td>
 *       <td>管环内沿必须 &gt; 49.50</td></tr>
 *   <tr><td>49.5 .. 57.5</td><td><b>4 条辐条（连接臂）</b> + 4 座物质解压机的机身
 *       （方位角 0° / 90° / 180° / 270° 各一，径向 46.9..56.9）</td>
 *       <td>管环必然从这 4 处"穿过" ⇒ 用 {@link #FADE_ZERO_DEG}~{@link #FADE_FULL_DEG} 的
 *       <b>alpha 淡出窗</b>把那 4 段让出去（见下）</td></tr>
 *   <tr><td>57.5 .. 58.5</td><td><b>空</b>（真方块 0 格）</td><td>管环外沿必须 &lt; 58.5</td></tr>
 *   <tr><td>58.5 起</td><td><b>外环桁架带 / 8 艘飞船</b>（63.0±2，最小 r ≈ 58.5）</td>
 *       <td>管环外沿必须 &lt; 58.5</td></tr>
 * </table>
 * ⇒ 自由环带 = <b>[49.50, 58.50]，宽 9.00 格</b>，正中 = <b>54.00</b>。
 * 管径 6.00 ⇒ 内沿 51.00（离内环 49.50 留 <b>1.50</b>）、外沿 57.00（离飞船带 58.50 留 <b>1.50</b>）
 * —— <b>左右各留 1.5 格，取的是"能塞下 5~7 格管径又能对中"的唯一整数解</b>，且<b>不碰内环、不碰外环、
 * 不碰飞船</b>。
 *
 * <h2>那 4 处"穿过"怎么处理（关键设计）</h2>
 * 辐条与 4 座解压机正好压在这圈上（它们的方位角是 0/90/180/270），任何半径 50~57 的管环都会穿过它们。
 * 处理办法不是缩半径（那就不是"内外环之间"了），而是<b>让光柱在那 4 段自然隐去</b>：
 * <pre>
 *   alpha ×= smoothstep(FADE_ZERO_DEG=4°, FADE_FULL_DEG=9°, 到最近一个机器方位角的角度差)
 * </pre>
 * 读数依据：解压机机身切向半宽 2.76 格 ÷ 54.0 半径 ⇒ <b>±2.93°</b>；辐条框架最外格 ±5.3°。
 * 取 4° 内全灭、9° 外全亮 ⇒ 机器本体（±3°）与辐条主体（±5.3°）都被压在淡出区里，
 * 视觉上就是<b>"光柱从解压机背后穿过去"</b>，而不是"光柱从机器玻璃里切出来"。
 * <b>代价</b>：环上有 4 个 ≈10° 的暗窗（合计约 11% 周长），近看能看出 4 段弧；
 * 把 {@link #FADE_AT_MACHINES} 置 false 就是完整闭环（但会和机器/辐条硬穿）。
 *
 * <h2>自转</h2>
 * 4 座解压机<b>跟着内环带一起以 12°/s 自转</b>（{@code HostFxGeometry.RING_DEG_PER_SEC}）⇒
 * 淡出窗必须<b>钉在机器上</b>，所以本环与机器<b>同一个自转角</b>绘制（调用方套 {@code spin}，
 * 见 {@code HostFxDraw/TESR}）。观感 = 一圈粗光柱跟着机器转，暗窗在环上慢速巡游
 * （12°/s ⇒ 30 秒一圈）；线速度 = 54.0 × 12 × π/180 = <b>11.31 格/秒</b>。
 *
 * <h2>贴图流向（为什么 UV0 沿<b>环周长</b>映射）</h2>
 * 伪神那根柱子的 UV 公式是 {@code u = offset/256 + tick/240}（offset = 沿<b>轴</b>的弧长，单位格），
 * 我们这里把"轴"换成<b>环周长</b>：{@code u = (R·θ)/256 + tick/240}，{@code v = φ/2π + u/3 + tick/240}
 * —— 与伪神<b>逐字同一个公式</b>，只是 {@code offset} 从"直线弧长"变成"圆弧弧长"。
 * 效果：贴图以同样的 texel 密度（256 格一个重复）沿环流动，与中央主光柱的流动手感一致。
 */
public final class HostFxBeamRingGeom {

    private HostFxBeamRingGeom() {}

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ① 尺寸（用户口径：管径 5~7 格；位置：内外环中间）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 环中心线半径（格）= <b>54.00</b>。
     *
     * <p>来历：自由环带 [49.50(内环外表面), 58.50(飞船带内沿)] 的正中；
     * 逐项读数见类注释的表格与 {@code temp/host-fx/ringar/spokes.mjs}。
     */
    public static final float RING_R = 54.00F;

    /**
     * 管半径（格）= <b>3.00</b> ⇒ 管径 {@link #DIAMETER} = <b>6.00 格</b>（用户：5~7）。
     *
     * <p>取 3.00 而不是 2.50 / 3.50：内沿 51.00 与 49.50 留 1.50、外沿 57.00 与 58.50 留 1.50，
     * <b>左右等距</b>；取 3.50 会吃掉外环侧全部余量（58.50−57.50=1.00）并逼近飞船带。
     */
    public static final float TUBE_R = 3.00F;

    /** 管径（格）—— 报告/日志里的那个数（用户要 5~7）。 */
    public static final float DIAMETER = TUBE_R * 2.0F;

    /** 环所在高度（环心本地坐标；0 = 环面 = 内环带所在平面）。 */
    public static final float CENTER_Y = 0.0F;

    /**
     * <b>外带桁架带最内表面</b>（格）= 58.50。
     *
     * <p>🔴 这个数<b>不在台账里</b>（{@code HostFxGeometry.hideTargets()} 只含"要转的"三组：内环带 / 辐条 / 8 艘飞船），
     * 外带是<b>静态真方块</b>、从来没被藏过，所以只能量出来写成常量：
     * {@code temp/host-fx/ringar/spokes.mjs} 对整份 {@code .bin} 逐格统计 ⇒
     * <b>r ∈ [57.5, 58.5] 一格方块都没有</b>，58.5 起才有（且最内表面正好落在 58.500）。
     * <p>为什么非要有它：没有这条，{@link #selfCheck()} 只能看到飞船（台账里最内 60.50），
     * 于是 r=57（外沿 60.00）会被判"通过"——而它其实已经捅进外带 1.5 格了。
     * <b>负对照 F 就是这条</b>（见报告 §1.2）。
     */
    public static final float OUTER_BAND_INNER_FACE = 58.50F;

    /** 沿环周几段（64 ⇒ 每段 5.625°；弦长 2·54·sin(2.8125°) = 5.30 格，拱高 0.065 格 ⇒ 肉眼无棱）。 */
    public static final int SEGMENTS = 64;

    /** 管截面几等分（与伪神主光柱的 {@code PILLAR_QUADS = 16} 同一个数，观感同一套）。 */
    public static final int FACETS = 16;

    /** 一圈有多少个三角形（= SEGMENTS × FACETS × 2）与顶点（× 6，走 TRIANGLES 不共享顶点）。 */
    public static int triangleCount() {
        return SEGMENTS * FACETS * 2;
    }

    public static int vertexCount() {
        return SEGMENTS * FACETS * 6;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ② 着色参数（与伪神主光柱同一套）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * soft 遍 Intensity（<b>数值必须等于</b> {@code HostFxPillarBeam.INTENSITY_SOFT} = 伪神写死的 2.0）。
     *
     * <p>为什么在这里写死而不引用那个常量：本类要能被<b>离线预览</b>（{@code temp/host-fx/ringar/}）
     * 单独 javac —— 一引用 {@code HostFxPillarBeam} 就会连带拖进 {@code ShaderInstance} / GL 那些
     * 只有游戏里才有的类，预览就编不过了。**数值一致性**由 {@code HostFxBeamRing} 里的
     * {@code selfCheck}（编译期常量比较不了就直接断言相等）在运行期兜住：那边会打一行
     * {@code ring_size_ok}，读到 2.0/4.0 即为一致（与主光柱同一个数）。
     */
    public static final float INTENSITY_SOFT = 2.0F;
    /** intense 遍 Intensity（= {@code HostFxPillarBeam.INTENSITY_INTENSE} = 4.0）。 */
    public static final float INTENSITY_INTENSE = 4.0F;

    /** 外层（soft 遍）管半径倍数 = 1.00 ⇒ 管径 6.00 格。 */
    public static final float OUTER_R_SCALE = 1.00F;
    /** 内芯（intense 遍）半径倍数 —— 与伪神柱子的 {@code PILLAR_INNER_SCALE = 0.45} 同口径。 */
    public static final float INNER_R_SCALE = 0.45F;

    /** 外层 alpha（1.0 = 与伪神柱子的 {@code PILLAR_OUTER_ALPHA} 一致，浓淡交给 fsh 的 rim 算）。 */
    public static final float OUTER_ALPHA = 1.00F;
    /** 内芯 alpha（同上）。 */
    public static final float INNER_ALPHA = 1.00F;

    /**
     * <b>rim 下限</b>（0..1）—— 保证"任何视线角度都不断"。
     *
     * <p>伪神那套 rim 是 {@code pow(max((dot−0.5)*2,0), Intensity)}：管子背光的那一半、
     * 以及<b>掠射</b>（视线几乎切过管面、dot≈0）都会算成 0 ⇒ 整段不渲染。
     * 用户实机三条症状里的第 2、3 条（"明明没被挡却不画""在控制室朝环心看一点都没有"）就是这个。
     * <p>处置：<b>给 rim 加一个下限</b> —— {@code shape = RIM_FLOOR + (1−RIM_FLOOR)×rim}：
     * 任何角度的 alpha 至少是 {@code RIM_FLOOR × 贴图亮度}，边缘仍比正面亮（体积感还在），
     * 但<b>没有任何角度会塌到 0</b>。取值来源：{@code 0.40} 时亮边与底面的亮度比 ≈ 1 : 0.4，
     * 管子的圆形轮廓仍读得出来（预览图 55 的右下角能在掠射机位看到完整管身）。
     */
    public static final float RIM_FLOOR = 0.40F;

    /**
     * 发光颜色（0..1）：默认<b>与中央主光柱同一个默认值</b> {@code JET_GLOW_RGB = (0.62, 0.84, 1.00)}
     * —— 用户要的是"伪神的光柱"，同色才像同一套灯。
     * <p>想让环单独一色就改这一行（别去动 {@code HostFxGeometry.JET_GLOW_RGB}，那是中央两根的）。
     */
    public static final float[] GLOW_RGB = {0.62F, 0.84F, 1.00F};

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ③ 4 处"让位"淡出窗（钉在 4 座解压机的方位角上）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 是否在 4 座解压机方位角处淡出。
     *
     * <p>🔴 <b>现在是 {@code false}</b>（2026-10-25 第三轮，用户看着实机图说「<b>四个角那里有 4 个缺口，补上</b>」）：
     * 那 4 个缺口就是这套淡出窗（±4° 全灭 + ±9° 全亮 ⇒ 吃掉了周长 11%）。
     * <p><b>代价（如实记）</b>：光柱环会在那 4 处与<b>解压机本体 / 辐条硬穿</b>（不再有暗窗掩护）；
     * 好在机器是<b>不透明且先画、写深度</b>的（{@code HostFxRingGroups} 在光柱之前），所以穿进去的那部分
     * 会被机器本体挡掉，看起来是"光柱从机器里穿过去"，而不是"光柱糊在机器玻璃上"。
     * <p>想改回"窄暗窗"的备选：{@code FADE_AT_MACHINES = true} 且 {@link #FADE_ZERO_DEG} = 2、
     * {@link #FADE_FULL_DEG} = 4（只吃掉 4.4% 周长，缺口几乎看不见）。
     */
    public static final boolean FADE_AT_MACHINES = false;

    /** 淡出窗里"完全熄灭"的角度半径（度）：4.0 ⇒ 覆盖机器机身切向半宽 2.93° 还多 1°。 */
    public static final float FADE_ZERO_DEG = 4.00F;

    /** 淡出窗外"完全点亮"的角度半径（度）：9.0 ⇒ 超过辐条框架最外格 5.3° 之后才开始全亮。 */
    public static final float FADE_FULL_DEG = 9.00F;

    /** 4 座解压机的方位角（度）—— 与 {@code HostFxGeometry.MD_AZIMUTH_DEG} 同源。 */
    public static final float[] MACHINE_AZIMUTH_DEG = {0.0F, 90.0F, 180.0F, 270.0F};

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ④ 顶点出口（零分配：游戏内写 BufferBuilder，离线预览写自己的光栅化器）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 顶点接收器。
     *
     * <p>为什么用一个接口而不是返回 {@code float[]}：本环每遍 <b>6144 顶点</b>，
     * 每帧 2 遍 ⇒ 若每帧都新建一个 {@code float[]}（9 个 float/顶点）就是 ≈430 KB/帧的垃圾，
     * 而渲染线程本来就怕 GC 抖动。用接口直接把顶点推出去，两边都零分配。
     */
    public interface Sink {
        /**
         * 一个顶点。
         *
         * @param x,y,z   环心本地坐标（已套好自转）
         * @param nx,ny,nz 管面<b>外法线</b>（单位向量，环心本地坐标）
         * @param u,v     贴图坐标（{@code space_layer.png}）
         * @param alpha   已经乘过淡出窗的 alpha（0..1），由实现方决定怎么打包（游戏内 UV1×10000）
         */
        void vertex(float x, float y, float z, float nx, float ny, float nz,
                    float u, float v, float alpha, float azimuthDeg);
    }

    /**
     * 把一环的管面顶点推给 {@code sink}（一次性，顺序 = 逐段 → 逐片 → 每片 2 个三角形）。
     *
     * @param radiusScale 半径倍数（soft 遍传 {@link #OUTER_R_SCALE}、intense 遍传 {@link #INNER_R_SCALE}）
     * @param alpha       整遍的 alpha 基数（再乘每个顶点的淡出窗）
     * @param tick        世界 tick（伪神 UV 公式里的 {@code tick/240}）
     * @param spinDeg     自转角度（度）—— 与 4 座解压机同一个角，淡出窗<b>跟着它转</b>
     * @return 推出去的顶点数（= {@link #vertexCount()}）
     */
    public static int emitTube(Sink sink, float radiusScale, float alpha, float tick, float spinDeg) {
        final float r = RING_R;
        final float tube = TUBE_R * radiusScale;
        final float spin = (float) Math.toRadians(spinDeg);
        int emitted = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            final float th0 = (float) (Math.PI * 2.0D * i / SEGMENTS) + spin;
            final float th1 = (float) (Math.PI * 2.0D * (i + 1) / SEGMENTS) + spin;
            // 段的淡出窗：按"不含自转的自身方位角"算 ⇒ 窗随自转一起转，永远压在机器上
            final float envAlpha = alpha * fadeEnvelopeDeg(360.0F * i / SEGMENTS);
            // 段的贴图流向：u 用弧长（与伪神的 offset/256 同一口径），并加 tick/240
            final float u0 = (float) (Math.PI * 2.0D * i / SEGMENTS) * r / 256.0F + tick / 240.0F;
            final float u1 = (float) (Math.PI * 2.0D * (i + 1) / SEGMENTS) * r / 256.0F + tick / 240.0F;
            final float cosA = (float) Math.cos(th0);
            final float sinA = (float) Math.sin(th0);
            final float cosB = (float) Math.cos(th1);
            final float sinB = (float) Math.sin(th1);
            // 两个截面的中心 + 面内径向外方向（= 截面的 x 基）与 +Y（截面的 y 基）
            final float cxa = r * cosA;
            final float cza = r * sinA;
            final float cxb = r * cosB;
            final float czb = r * sinB;
            for (int j = 0; j < FACETS; j++) {
                final float p0 = (float) (Math.PI * 2.0D * j / FACETS);
                final float p1 = (float) (Math.PI * 2.0D * (j + 1) / FACETS);
                // 4 个角：A0(i,j) A1(i,j+1) B1(i+1,j+1) B0(i+1,j)
                // 法线 = cosφ·径向外 + sinφ·(+Y)（环心本地坐标，单位向量）
                final float n0x = (float) Math.cos(p0) * cosA;
                final float n0y = (float) Math.sin(p0);
                final float n0z = (float) Math.cos(p0) * sinA;
                final float n1x = (float) Math.cos(p1) * cosA;
                final float n1y = (float) Math.sin(p1);
                final float n1z = (float) Math.cos(p1) * sinA;
                final float n2x = (float) Math.cos(p1) * cosB;
                final float n2y = (float) Math.sin(p1);
                final float n2z = (float) Math.cos(p1) * sinB;
                final float n3x = (float) Math.cos(p0) * cosB;
                final float n3y = (float) Math.sin(p0);
                final float n3z = (float) Math.cos(p0) * sinB;
                // 截面点 = 中心 + tube × 法线（法线本身就在截面上，含 +Y 分量）
                final float ax0 = cxa + tube * n0x;
                final float ay0 = CENTER_Y + tube * n0y;
                final float az0 = cza + tube * n0z;
                final float ax1 = cxa + tube * n1x;
                final float ay1 = CENTER_Y + tube * n1y;
                final float az1 = cza + tube * n1z;
                final float bx1 = cxb + tube * n2x;
                final float by1 = CENTER_Y + tube * n2y;
                final float bz1 = czb + tube * n2z;
                final float bx0 = cxb + tube * n3x;
                final float by0 = CENTER_Y + tube * n3y;
                final float bz0 = czb + tube * n3z;
                // v = φ/2π + u/3 + tick/240（与伪神 {@code addBeamVertex} 逐字同式）
                final float v0a = (float) (j / (double) FACETS) + u0 / 3.0F + tick / 240.0F;
                final float v0b = (float) (j / (double) FACETS) + u1 / 3.0F + tick / 240.0F;
                final float v1a = (float) ((j + 1) / (double) FACETS) + u0 / 3.0F + tick / 240.0F;
                final float v1b = (float) ((j + 1) / (double) FACETS) + u1 / 3.0F + tick / 240.0F;
                // 🔴 方位角（度）随顶点一起送出去：着色器要用它重建"管轴切线"，把视线的切线分量投影掉
                //    （= 伪神 `normalize(CameraPosition.xy)` 的等价物）。不送的话侧向那两段会整片黑。
                final float azDeg = 360.0F * i / SEGMENTS;
                // 三角形 1：A0 → A1 → B1
                sink.vertex(ax0, ay0, az0, n0x, n0y, n0z, u0, v0a, envAlpha, azDeg);
                sink.vertex(ax1, ay1, az1, n1x, n1y, n1z, u0, v1a, envAlpha, azDeg);
                sink.vertex(bx1, by1, bz1, n2x, n2y, n2z, u1, v1b, envAlpha, azDeg);
                // 三角形 2：A0 → B1 → B0
                sink.vertex(ax0, ay0, az0, n0x, n0y, n0z, u0, v0a, envAlpha, azDeg);
                sink.vertex(bx1, by1, bz1, n2x, n2y, n2z, u1, v1b, envAlpha, azDeg);
                sink.vertex(bx0, by0, bz0, n3x, n3y, n3z, u1, v0b, envAlpha, azDeg);
                emitted += 6;
            }
        }
        return emitted;
    }

    /**
     * 淡出包络：{@code 0} = 该方位完全熄灭，{@code 1} = 完全点亮。
     *
     * @param azimuthDeg 该段的方位角（度，<b>不含自转</b>，因为解压机与环同一个自转角）
     */
    public static float fadeEnvelopeDeg(float azimuthDeg) {
        if (!FADE_AT_MACHINES) {
            return 1.0F;
        }
        float best = Float.MAX_VALUE;
        for (float m : MACHINE_AZIMUTH_DEG) {
            // 有符号最短角差 ∈ [−180,180)：+540 是为了让负角度也落进 [0,360)
            final float d = Math.abs(((azimuthDeg - m) % 360.0F + 540.0F) % 360.0F - 180.0F);
            if (d < best) {
                best = d;
            }
        }
        if (best <= FADE_ZERO_DEG) {
            return 0.0F;
        }
        if (best >= FADE_FULL_DEG) {
            return 1.0F;
        }
        final float t = (best - FADE_ZERO_DEG) / (FADE_FULL_DEG - FADE_ZERO_DEG);
        return t * t * (3.0F - 2.0F * t);                    // smoothstep
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ⑤ 尺寸读数（报告 / 日志 / 离线预览共用这几个数）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 管环<b>内沿</b>半径（格）。 */
    public static float innerEdge() {
        return RING_R - TUBE_R * OUTER_R_SCALE;
    }

    /** 管环<b>外沿</b>半径（格）。 */
    public static float outerEdge() {
        return RING_R + TUBE_R * OUTER_R_SCALE;
    }

    /** 周长（格）。 */
    public static float circumference() {
        return (float) (Math.PI * 2.0D * RING_R);
    }

    /** 自转角速度（°/s）= 内环带的那一个（机器转多快，暗窗就转多快）。 */
    public static float spinDegPerSec() {
        return HostFxGeometry.RING_DEG_PER_SEC;
    }

    /** 中心线的线速度（格/秒）。 */
    public static float linearSpeedPerSec() {
        return (float) (RING_R * Math.toRadians(spinDegPerSec()));
    }

    /** 贴图沿环的流速（格/秒）：{@code u += tick/240} ⇒ 12 秒走完 256 格。 */
    public static float textureFlowBlocksPerSec() {
        return 256.0F / 12.0F;
    }

    /** 4 个暗窗合计占周长的比例（0..1），报告里要写清"少了多少"。 */
    public static float darkFraction() {
        if (!FADE_AT_MACHINES) {
            return 0.0F;
        }
        return MACHINE_AZIMUTH_DEG.length * 2.0F * FADE_ZERO_DEG / 360.0F;
    }

    private static String f2(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * 一行读数（渲染器日志里打这一行 —— 用户口径的 5 个数全在里面）。
     *
     * <p>判据：{@code 管径} 必须落在 <b>5.00 ~ 7.00</b>；{@code 内沿} 必须 &gt; 49.50（内环外表面）；
     * {@code 外沿} 必须 &lt; 58.50（飞船带内沿）。
     */
    public static String stats() {
        return "光柱环 半径 " + f2(RING_R) + " / 管径 " + f2(DIAMETER)
                + "（内沿 " + f2(innerEdge()) + " … 外沿 " + f2(outerEdge()) + "）"
                + " / " + SEGMENTS + " 段 × " + FACETS + " 面 = " + triangleCount() + " 三角形/遍"
                + " / 自转 " + f2(spinDegPerSec()) + "°/s（" + f2(linearSpeedPerSec()) + " 格/秒，与解压机同角）"
                + " / 贴图流速 " + f2(textureFlowBlocksPerSec()) + " 格/秒（u=弧长/256+tick/240）"
                + " / ring_gaps=" + (FADE_AT_MACHINES ? "on" : "off")
                + (FADE_AT_MACHINES
                        ? ("（4×" + f2(2.0F * FADE_ZERO_DEG) + "° = 周长 " + f2(darkFraction() * 100.0F) + "%）")
                        : "（完整闭环，无暗窗）")
                + " / rim 下限 " + f2(RIM_FLOOR)
                + " / 2 遍 Intensity " + INTENSITY_SOFT + "/" + INTENSITY_INTENSE
                + " / 色 " + HostFxGeometry.rgbText(GLOW_RGB);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ⑥ 自检：数字真的站得住吗（几何 + 与真方块/机器的干涉，逐条算，不靠眼睛）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 几何自检（返回 {@code null} = 全部通过；否则一行人话）。
     *
     * <p>查 5 条，每条都有真值来源：
     * <ol>
     *   <li><b>管径 ∈ [5,7]</b>（用户硬线）；</li>
     *   <li><b>内沿 &gt; 内环带最外表面</b>（由 {@link HostFxGeometry#hideTargets()} 里内环带那批格
     *       按"角点半径"实测，不是写死 49.50）；</li>
     *   <li><b>外沿 &lt; 飞船带内沿</b>（同样由 ledger 里飞船那批格实测）；</li>
     *   <li><b>管环不与内环带/飞船任何一格相交</b>（逐格算"格到中心线圆的最近距离"，要让 0 格）；</li>
     *   <li><b>与辐条/解压机相交的格全都落在淡出窗里</b>（0 个例外）。</li>
     * </ol>
     */
    public static String selfCheck() {
        final Reading rd = measure();
        // ① 管径（用户硬线 5~7）
        if (DIAMETER < 5.0F - 1.0e-4F || DIAMETER > 7.0F + 1.0e-4F) {
            return "管径 " + f2(DIAMETER) + " 格不在用户要求的 5~7 格区间内";
        }
        // ② 内沿要让开内环带最外表面
        if (innerEdge() <= rd.bandOuterFace + 1.0e-3F) {
            return "内沿 " + f2(innerEdge()) + " 没有让开内环带最外表面 " + f2(rd.bandOuterFace);
        }
        // ③ 外沿要让开飞船带最内表面（台账）
        if (rd.shipInnerFace < Float.MAX_VALUE && outerEdge() >= rd.shipInnerFace - 1.0e-3F) {
            return "外沿 " + f2(outerEdge()) + " 顶到了飞船带最内表面 " + f2(rd.shipInnerFace);
        }
        // ③.5 外沿还要让开【外带桁架】（静态真方块，不在台账里 ⇒ 用实测常量 58.50）
        if (outerEdge() >= OUTER_BAND_INNER_FACE - 1.0e-3F) {
            return "外沿 " + f2(outerEdge()) + " 顶到了外带桁架最内表面 "
                    + f2(OUTER_BAND_INNER_FACE) + "（这个数不在台账里，来自整份 .bin 实测）";
        }
        // ④ 与内环带 / 飞船相交的格必须 0（这两组没有任何淡出窗可用）
        if (rd.bandHits + rd.shipHits > 0) {
            return "管环与内环带/飞船真方块相交 " + (rd.bandHits + rd.shipHits)
                    + " 格（内环带 " + rd.bandHits + "、飞船 " + rd.shipHits + "）—— 这两组不许穿";
        }
        // ⑤ 与辐条/解压机相交的格：**只在开了淡出窗时**才要求它们全在窗里。
        //    2026-10-25 三轮：用户要"补上 4 个缺口" ⇒ FADE_AT_MACHINES=false ⇒ 这条自然不成立，
        //    改成**读数**（穿过多少格照实打出来），不再判红 —— 硬判据只剩"管径 5~7 / 内沿外沿不穿"。
        if (FADE_AT_MACHINES && rd.brightHits > 0) {
            return "有 " + rd.brightHits + " 格辐条/解压机方块落在淡出窗的<b>半亮以上</b>区域"
                    + "（包络 > 0.50 ⇒ 会看到光柱切进方块）";
        }
        return null;
    }

    /** 自检读数（报告/日志直接抄）。 */
    public record Reading(float bandOuterFace, float shipInnerFace, int bandHits, int shipHits,
                          int fadeHits, int dimHits, int brightHits, float maxAbsDz) {}

    /**
     * 逐格测量（{@link #selfCheck()} 与 {@link #selfCheckReading()} 共用这一份，免得两个口径）。
     *
     * <p>判"格 vs 管环"是否相交用的是<b>环面的精确判据</b>：环绕竖轴旋转对称 ⇒
     * 「径向区间重叠 且 竖直区间重叠」就等价于相交（不需要方位角）。方格径向区间取
     * {@code [hypot(max(0,|x|−0.5), max(0,|z|−0.5)), hypot(|x|+0.5, |z|+0.5)]}，竖直
     * {@code [y−0.5, y+0.5]}；管环径向 {@code [R−r, R+r]}、竖直 {@code [−r, +r]}。
     */
    public static Reading measure() {
        float bandOuterFace = -Float.MAX_VALUE;
        float shipInnerFace = Float.MAX_VALUE;
        int bandHits = 0;
        int shipHits = 0;
        int fadeHits = 0;
        int dimHits = 0;
        int brightHits = 0;
        float maxAbsDz = 0.0F;
        final float tube = TUBE_R * OUTER_R_SCALE;
        for (BlockPos g : HostFxGeometry.hideTargets()) {
            final float lx = g.getX();
            final float ly = g.getY() - HostFxGeometry.RING_Y;
            final float lz = g.getZ();
            final double r = Math.hypot(lx, lz);
            final double rMax = Math.hypot(Math.abs(lx) + 0.5D, Math.abs(lz) + 0.5D);
            final double rMin = Math.hypot(Math.max(0.0D, Math.abs(lx) - 0.5D),
                    Math.max(0.0D, Math.abs(lz) - 0.5D));
            final boolean isBand = Math.abs(r - HostFxGeometry.INNER_R) <= HostFxGeometry.INNER_TOLERANCE;
            final boolean isShip = !isBand && r > HostFxGeometry.SHIP_RADIAL_CENTER - 4.5D;
            if (isBand) {
                bandOuterFace = Math.max(bandOuterFace, (float) rMax);
            } else if (isShip) {
                shipInnerFace = Math.min(shipInnerFace, (float) rMin);
            }
            // 环面相交判据：径向重叠 且 竖直重叠
            final boolean radialOverlap = rMax >= RING_R - tube && rMin <= RING_R + tube;
            final boolean verticalOverlap = Math.abs(ly - CENTER_Y) <= tube + 0.5D;
            if (!radialOverlap || !verticalOverlap) {
                continue;
            }
            if (isBand) {
                bandHits++;
                continue;
            }
            if (isShip) {
                shipHits++;
                continue;
            }
            // 辐条 / 解压机那批：看它的 4 个角落在淡出窗的哪一档（取最亮的那个角）
            float env = 0.0F;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    final float az = (float) Math.toDegrees(
                            Math.atan2(lz + 0.5F * sz, lx + 0.5F * sx));
                    env = Math.max(env, fadeEnvelopeDeg(az));
                }
            }
            if (env <= 0.01F) {
                fadeHits++;
            } else if (env <= 0.50F) {
                dimHits++;
            } else {
                brightHits++;
            }
            maxAbsDz = Math.max(maxAbsDz, Math.abs(ly - CENTER_Y));
        }
        return new Reading(bandOuterFace, shipInnerFace, bandHits, shipHits,
                fadeHits, dimHits, brightHits, maxAbsDz);
    }

    public static String selfCheckReading() {
        final Reading rd = measure();
        return "管环 内沿 " + f2(innerEdge()) + " / 外沿 " + f2(outerEdge()) + " / 管径 " + f2(DIAMETER)
                + "；内环带最外表面 " + f2(rd.bandOuterFace()) + "（让开 "
                + f2(innerEdge() - rd.bandOuterFace()) + " 格，相交 " + rd.bandHits() + " 格）"
                + "；飞船带最内表面 " + (rd.shipInnerFace() == Float.MAX_VALUE ? "无"
                        : f2(rd.shipInnerFace())) + "（让开 " + f2(rd.shipInnerFace() - outerEdge())
                + " 格，相交 " + rd.shipHits() + " 格）"
                + "；外带桁架最内表面 " + f2(OUTER_BAND_INNER_FACE) + "（让开 "
                + f2(OUTER_BAND_INNER_FACE - outerEdge()) + " 格，常量·不在台账里）"
                + "；辐条/解压机：相交 " + (rd.fadeHits() + rd.dimHits() + rd.brightHits())
                + " 格 ⇒ 全灭区(α=0) " + rd.fadeHits() + " / 淡出区(α≤0.5) " + rd.dimHits()
                + " / 半亮以上(<b>必须 0</b>) " + rd.brightHits()
                + "；相交格最大 |Δy| " + f2(rd.maxAbsDz()) + " 格";
    }
}
