package com.shanhai.client.render.blackhole;

/**
 * 黑洞的两条渲染路径（一级真透镜 / 二级贴图兜底）的<b>唯一数值来源</b>。
 *
 * <h2>为什么把数字从 {@link BlackHoleLensRenderer} 里抽出来</h2>
 * 2026-10-25 用户实机看完给的原话：
 * <blockquote>
 *   「<b>黑洞有点太小了，然后你目前就是画了个球是吧</b>」<br>
 *   「[光影关 owner=lens level=1] / [光影开 level=2] … 但用户觉得两者几乎一样」
 * </blockquote>
 * 复盘出两个真问题（都不是"看起来像"能解释的，是两个具体数字）：
 * <ol>
 *   <li><b>两条路都太小</b>：{@code RS_BLOCKS = 1.0} ⇒ 可见黑盘半径 = {@code B_CRIT × 1.0} ≈ 2.60 格。</li>
 *   <li><b>一级的吸积盘被"透镜矩形"裁掉了</b>：着色器里盘是 {@code 3..11 Rs}，
 *       而透镜矩形只有 {@code LENS_RANGE_RS = 3.1 Rs} ⇒ <b>盘只剩 3.0..3.1 Rs 的一条剃刀细边</b>，
 *       画面上就只剩"黑盘 + 一圈细环" = <b>一颗球</b>。二级兜底又是照着这个形态画的（同样只有
 *       黑盘+细环+淡淡光晕），所以两边看起来"几乎一样"。</li>
 * </ol>
 * ⇒ 这份 spec 把"世界空间半径"集中到一处，两路<b>读同一组数</b>，谁也不会再偷偷用另一套。
 *
 * <h2>⚠️ 纯数值 / 纯像素运算，不含任何 Minecraft 类型</h2>
 * 这样离线预览器（{@code temp/host-fx/probe/BlackHoleLookPng.java}）能直接 import 它，
 * 把"游戏里会画成什么样"逐像素复算出来，而<b>不必开游戏</b>。
 *
 * <h2>与着色器的契约（改这里必须同步改 {@code blackhole.fsh}）</h2>
 * <pre>
 *   blackhole.fsh : const float B_CRIT     = 2.5980762;   // 与本类 B_CRIT 同值
 *                   const float DISK_INNER = 3.0;         // = {@link #DISK_INNER_RS}
 *                   const float DISK_OUTER = 11.0;        // = {@link #DISK_OUTER_RS}
 *   blackhole.fsh 的盘半径以 uRs 为单位 ⇒ 只要本类把 rsBlocks 放大，盘也跟着放大（比例不变）
 * </pre>
 */
public final class BlackHoleLensSpec {

    private BlackHoleLensSpec() {}

    // ==========================================================================================
    // 🔴 着色器 uniform 的「可选集合」—— 与 BlackHoleLensRenderer.REQUIRED_UNIFORMS 配套
    // ==========================================================================================

    /**
     * <b>可选 uniform</b>：链接后的程序里取不到它 ⇒ <b>只打一行 INFO，不降级</b>。
     *
     * <h2>判据：「着色器里没人读它」和「着色器坏了」是两件事</h2>
     * <ul>
     *   <li>GLSL 编译器会把<b>只声明、从不读取</b>的 uniform 整条删掉 ⇒
     *       {@code glGetUniformLocation} 返回 −1 ⇒ vanilla 的 {@code ShaderInstance#updateLocations}
     *       打一行 {@code could not find uniform named …} 并且<b>不把它放进 uniformMap</b> ⇒
     *       {@code ShaderInstance#getUniform} 返回 {@code null}。这是<b>正常</b>的优化结果，不是故障。</li>
     *   <li>「真的坏了」（名字打错 / fsh 里没声明 / 链接失败 / 驱动不给编译）<b>同样</b>表现为
     *       {@code getUniform == null}。两种 null 在调用处长得一模一样，只能靠这份名单区分：
     *       <br>· <b>在名单里</b> ⇒ 只 {@code LOGGER.info} 一行 {@link #optionalMissingLine}，
     *       <b>不降级</b>，一级透镜照旧画（缺了它画面逐像素不变）；
     *       <br>· <b>不在名单里</b> ⇒ 保持原行为：{@code WARN} + 降二级
     *       （那说明真的坏了 —— 宁可画贴图，也别画出一坨错的）。</li>
     * </ul>
     *
     * <h2>什么时候才可以把一项登记进来</h2>
     * 只有当它<b>只用于日志 / 诊断 / 契约占位</b>、画面缺了它<b>逐像素不变</b>时才登记。
     * 参与画面的（颜色、几何、门控）一律<b>不许</b>登记 —— 那种项必须在 shader 里真的读它。
     *
     * <h2>本工程已经踩过两次（同一个形态）</h2>
     * <ol>
     *   <li>{@code uScreenSize}：声明了但没读 ⇒ 被优化掉 ⇒ 判成缺失 ⇒ <b>洞内一帧都不渲染</b>
     *       （实机 {@code fallback=2 reason=inside_uniform_missing:uScreenSize}）。</li>
     *   <li>{@code uHoleDepth}：采样门改成「逐像素自比」之后没人读它了 ⇒ 被优化掉 ⇒ 判成缺失 ⇒
     *       <b>整条一级透镜永久降二级静态贴图</b>，用户原话「<b>现在引力透镜完全不显示了</b>」
     *       （实机 {@code fallback=2 reason=uniform_missing:uHoleDepth} ×13，每一次之前 2 ms 都有
     *       vanilla 的 {@code could not find uniform named uHoleDepth}）。</li>
     * </ol>
     * ⇒ <b>铁律</b>：新增 / 改动任何 uniform 时，<b>要么真的读它，要么把它登记到这里</b>。
     * 只有这两种合法状态；"声明了却谁都不读"必然被编译器删掉。
     */
    public static final java.util.List<String> OPTIONAL_UNIFORMS = java.util.List.of(
            // 洞心平面的窗口深度。首选做法是让 shader 真的读它（fsh 里 `refDepth = max(dSelf, uHoleDepth)`）；
            // 这里登记是第二道保险：万一哪天那个 max 又被改掉，也只是少一行日志，不会把整条透镜降级。
            "uHoleDepth"
    );

    /** 是不是「缺了不影响画面、只该打一行日志」的可选项。 */
    public static boolean isOptionalUniform(String name) {
        return name != null && OPTIONAL_UNIFORMS.contains(name);
    }

    /**
     * 可选 uniform 取不到时<b>那唯一一行 INFO</b>（格式固定、可被日志断言；一面会话一次）。
     *
     * <pre>
     *   [SHANHAI-BH] lens_uniform_optional_missing name=uHoleDepth note=该项只用于日志/诊断，缺了不影响画面
     * </pre>
     */
    public static String optionalMissingLine(String name) {
        return BlackHoleFallbackGuard.PREFIX + " lens_uniform_optional_missing name="
                + BlackHoleFallbackGuard.sanitize(name) + " note=该项只用于日志/诊断，缺了不影响画面";
    }

    /** 临界碰撞参数 / Rs = 3√3/2（与着色器 {@code B_CRIT} 同值）。 */
    public static final double B_CRIT = 2.598076211353316D;

    /**
     * 视界（"史瓦西半径"）在世界里的方块数 —— 🔴 2026-10-25 放大：{@code 1.0 → 2.0}（<b>2.0 倍</b>）。
     *
     * <p>用户口径「以我们内环半径 48 格为参照，黑洞可见半径做到现在的大约 1.8~2.2 倍」
     * ⇒ 取区间中值 2.0：
     * <pre>
     *   改前：可见黑盘半径 = 2.598 × 1.0 = 2.60 格（直径 5.20 格）
     *   改后：可见黑盘半径 = 2.598 × 2.0 = 5.20 格（直径 10.39 格）
     *   比率 = 2.00 倍（落在 1.8~2.2 里）
     * </pre>
     * 相对内环 48 格：黑盘半径 = 环半径的 10.8%（改前 5.4%）。
     */
    public static final double RS_BLOCKS_DEFAULT = 2.0D;

    /**
     * 透镜矩形（= 屏幕上要重画的那块正方形）的半边长，单位 <b>Rs</b>。
     *
     * <p>🔴 2026-10-25 放大：{@code 3.1 → 11.5}。这是"为什么只剩一颗球"的真因：
     * 吸积盘在着色器里是 {@code DISK_INNER..DISK_OUTER = 3..11 Rs}，
     * 而矩形只有 3.1 Rs ⇒ 盘<b>只露出 3.0..3.1 的一条细边</b>。
     * 现在 11.5 Rs 把整张盘（11 Rs）包进矩形，盘的<b>外沿</b>（11 Rs = 22 格）比黑盘半径（2.6 Rs = 5.2 格）
     * 大 4.2 倍 ⇒ 画面里"盘/环明显大于核心黑盘"。
     */
    public static final double LENS_RANGE_RS = 14.0D;

    /**
     * 偏折强度的<b>总增益</b>：{@code uStrength = BlackHoleTuning.strength() × 这个值}。
     *
     * <h2>🔴 2026-10-25：{@code 1.00 → 0.42}（用户：「引力透镜有点劲大」）</h2>
     * 着色器里 {@code α = deflectAngle(bn) × uStrength}，而
     * {@code deflectAngle(bn) = 2t + 2.2t² + 1.6t³}（{@code t = 1/bn}）。改前 {@code uStrength = 1.00} 时：
     * <pre>
     *   bn = 2.60（光子环）   α = 1.19 rad = 68.2°  ← 整个背景被拽成一圈的主因
     *   bn = 5.00            α = 0.49 rad = 27.9°
     *   bn = 11.00（盘外缘）  α = 0.19 rad = 11.0°
     *   bn = 14.00（矩形边）  α = 0.14 rad =  8.0°
     * </pre>
     * ⇒ 连"盘外缘"都有 11°，视觉上就是<b>把背景整片绕着洞拖一圈</b>。
     * 乘 0.42 之后：
     * <pre>
     *   bn = 2.60    α = 0.500 rad = 28.6°
     *   bn = 5.00    α = 0.204 rad = 11.7°
     *   bn = 11.00   α = 0.078 rad =  4.5°
     *   bn = 14.00   α = 0.058 rad =  3.3°   ← 再乘下面的边缘淡出 ⇒ 边界处 0
     * </pre>
     * 口径：<b>"能看出弯折（近场还有 28°），但不再把背景拽成一圈"</b>。
     * <p>为什么做成独立增益而不是改 {@code blackhole_strength} 的默认值：那个值在用户的
     * {@code shanhai-client.toml} 里已经落盘成 1.0，改默认值对他<b>不生效</b>；
     * 乘在代码里才会立刻体现。{@code /shanhai bh strength 2} 仍然是在这个 0.42 之上再放大。
     */
    public static final double DEFLECT_GAIN = 0.42D;

    /**
     * 边缘淡出的<b>起始半径比例</b>（相对矩形半边长）：{@code dHole ∈ [0.86, 1.0]} 内把
     * <b>warp / 盘 / 光子环 / 光晕</b> 一起用 smoothstep 衰减到 0。
     *
     * <h2>🔴 2026-10-25：用户「左右有明显的分割线」</h2>
     * 改前的边缘处理有两条路，但<b>只覆盖了一部分贡献</b>：
     * <ul>
     *   <li>warp 有 {@code maskRadius(=0.70..1.0)} 与 {@code maskEdge(=矩形边缘 10%)} 两道 ⇒ 边界处已经是 0；</li>
     *   <li><b>盘 / 光子环 / 光晕完全没有边缘掩码</b>（{@code col += diskCol; col += ringCol;} 直接加）
     *       ⇒ 只要矩形边界切到盘环，就是一条<b>硬边</b>。像素预算把矩形一收（近距常见），
     *       盘被切在 6~7 Rs 处 ⇒ 屏幕左右各一条竖直分界线。这就是用户看到的东西。</li>
     * </ul>
     * ⇒ 现在用<b>同一个</b> {@code fade} 乘全部四项，四条边按"到矩形中心的最大半径"归一化，
     * 最后 14% 淡出（{@code 0.86..1.0}）。同时把 {@link #LENS_RANGE_RS} 抬到 14 Rs，
     * 让<b>盘的外缘（11 Rs）落在淡出带之外</b>（11/14 = 0.786 < 0.86）⇒ 盘本身不被削小。
     */
    public static final double EDGE_FADE_INNER = 0.86D;

    /**
     * "相机在洞里"的判定倍率：{@code dist ≤ 可见黑盘半径 × 这个值} ⇒ 走<b>洞内视角</b>。
     *
     * <h2>🔴 2026-10-25 傍晚：{@code 1.02 → 1.6}（<b>这就是"里面往外看什么都不画"的真因</b>）</h2>
     * 用户实机原话：「<b>黑洞我在里面往外看还是不能渲染</b>」。日志 {@code GTL山海9.10test\logs\latest.log}
     * 里 15896 行、{@code inside=true} <b>0 行</b>；{@code dist=} 的最小值是 <b>5.3</b>
     * （另有 7.8 / 13.1 / 17 / 18.8 / 22.1 / 28 / 28.3）。而改前的阈值恰好是：
     * <pre>
     *   可见黑盘半径 = B_CRIT × rsBlocks = 2.598 × 2.0 = 5.196 格
     *   改前阈值     = 5.196 × 1.02        = 5.30 格   ← 与日志最小值 5.3 逐位相同
     * </pre>
     * ⇒ 那 2% 的"余量"小于玩家一帧的行走位移、也小于浮点误差的量级，<b>判据实际永远不成立</b>：
     * 走得更近一点就 {@code inside=true}，可那一刻起日志行又被另一处早退吞掉了 —— 两头都看不见。
     *
     * <h2>改后的阈值（两个口径取大者，见 {@link #insideThresholdBlocks}）</h2>
     * <pre>
     *   ① 可见黑盘半径 × 1.6           = 8.31 格
     *   ② 可见黑盘半径 + 2.0 格        = 7.20 格        ⇒ max = 8.31 格
     * </pre>
     * 为什么是"1.6 倍 / +2 格"而不是贴着脸（1.02）：视界半径 5.196 格的球，玩家身体宽 0.6 格、
     * 眼高 1.62 格 ⇒ <b>相机"实际进到球里"时 dist 还在 4~6 格之间</b>；要把"刚跨进球面但还没到球心"
     * 这一段也当成洞内，阈值必须比球面明显外扩。<b>+2 格</b>那条是给 {@code /shanhai bh scale}
     * 调小的场合兜底（scale 很小时 1.6 倍只多出零点几格，会再次卡在边界上）。
     */
    public static final double INSIDE_FACTOR = 1.6D;

    /** 洞内判据的<b>绝对余量</b>（格）：阈值再取 {@code 可见黑盘半径 + 这个值} 一起比大（见类注释）。 */
    public static final double INSIDE_MARGIN_BLOCKS = 2.0D;

    /** 改前的阈值倍率（只用于日志/报告的"改前 → 改后"对照，代码里不再使用）。 */
    public static final double INSIDE_FACTOR_BEFORE = 1.02D;

    /** 吸积盘内缘 / Rs（= 史瓦西 ISCO；与着色器 {@code DISK_INNER} 同值）。 */
    public static final double DISK_INNER_RS = 3.0D;
    /** 吸积盘外缘 / Rs（与着色器 {@code DISK_OUTER} 同值）。 */
    public static final double DISK_OUTER_RS = 11.0D;

    /**
     * 吸积盘亮度总增益（一级着色器 uniform {@code uDiskGain} 的基数，再乘 {@code BlackHoleTuning.disk()}）。
     *
     * <p>🔴 2026-10-25：{@code 1.00 → 0.62}。盘从"被矩形裁掉的一条细边"变成"整张 6..22 格的盘"之后，
     * 1.00 会把盘的多普勒亮侧<b>推到全白</b>（着色器里 {@code shade × beam} 可达 ~6），
     * 颜色梯度（内白热→中橙→外暗红）被白成一片 ⇒ 看上去还是一团。0.62 之后高光仍会到顶，
     * 但盘身留住橙色渐变（出图见 {@code temp/host-fx/probe/BlackHoleLookPng.java} 的读后自述）。
     */
    public static final float DISK_GAIN = 0.62F;
    /** 光子环亮度总增益。 */
    public static final float RING_GAIN = 1.0F;

    /**
     * 吸积盘相对<b>水平面</b>的倾角（度）—— 一级用它算 {@code uDiskNormal}，
     * 二级兜底用它把"赤道吸积带"画成屏幕上的扁椭圆（同一个倾角，两条路才对得上）。
     *
     * <p>🔴 2026-10-25：<b>18° → 32°</b>。这里有个必须写下来的观察：
     * 我们的环是<b>水平</b>的、玩家基本站在环面高度看洞 ⇒ 视线仰角只有几度。
     * 盘若只倾 18°，它与视线的夹角只有 ~8° ⇒ 投影出来的竖直厚度 ≈ {@code 22 格 × sin8° ≈ 3 格}，
     * 在 44 格外看就是<b>一条头发丝</b>——实测（{@code temp/host-fx/probe/BlackHoleLookPng.java} 第一版）
     * 镜头在 {@code y=0.6} 时盘完全看不见，画面只剩"黑盘 + 光子环"= 用户说的<b>那颗球</b>。
     * 抬到 32° 之后，环面视角下盘与视线夹角约 24~30° ⇒ 投影成明显的扁椭圆（短轴/长轴 ≈ 0.45）。
     */
    public static final double DISK_TILT_DEG = 32.0D;

    /** 盘法线的方位角（度）—— 只影响"盘朝哪边斜"，环是轴对称的，取 20° 让盘不与 4 条辐条共面。 */
    public static final double DISK_NORMAL_AZIMUTH_DEG = 20.0D;

    /** 盘法线（水平倾斜 {@link #DISK_TILT_DEG}°、方位 {@link #DISK_NORMAL_AZIMUTH_DEG}°）。 */
    public static float[] diskNormal() {
        final double i = Math.toRadians(DISK_TILT_DEG);
        final double az = Math.toRadians(DISK_NORMAL_AZIMUTH_DEG);
        return new float[]{(float) (Math.sin(i) * Math.cos(az)), (float) Math.cos(i),
                (float) (Math.sin(i) * Math.sin(az))};
    }

    /**
     * 二级兜底贴图的边长（像素）：{@code 256 → 512}（仍是 2 的幂；仍然<b>只注册一次</b>）。
     *
     * <p>为什么必须提：可见黑盘在贴图里只占 {@code B_CRIT / LENS_RANGE_RS = 0.226} 的半宽，
     * 256 时黑盘半径只有 29 px、光子环只有 2.6 px 宽 ⇒ 四个构件在近距离会糊成一坨。
     */
    public static final int FALLBACK_TEX_SIDE = 512;

    /** 黑盘的可见半径（格）。{@code = B_CRIT × rsBlocks}。 */
    public static double shadowRadiusBlocks(double rsBlocks) {
        return B_CRIT * rsBlocks;
    }

    /** 透镜矩形（以及二级公告板）的半边长（格）。{@code = LENS_RANGE_RS × rsBlocks}。 */
    public static double lensRadiusBlocks(double rsBlocks) {
        return LENS_RANGE_RS * rsBlocks;
    }

    /** 吸积盘内缘（格）。 */
    public static double diskInnerBlocks(double rsBlocks) {
        return DISK_INNER_RS * rsBlocks;
    }

    /** 吸积盘外缘（格）。 */
    public static double diskOuterBlocks(double rsBlocks) {
        return DISK_OUTER_RS * rsBlocks;
    }

    /**
     * <b>洞内判据的阈值（格）</b>：{@code max(可见黑盘半径 × INSIDE_FACTOR, 可见黑盘半径 + INSIDE_MARGIN_BLOCKS)}。
     *
     * <p>这是"相机在洞里"这件事的<b>唯一</b>判据来源 —— 渲染器、日志里的 {@code insideThr=}、
     * 离线出图器全都调这一个函数，不允许任何一处另写一遍"× 1.02"。
     *
     * @param rsBlocks 运行期视界半径（格）= {@code RS_BLOCKS × BlackHoleTuning.scale()}
     */
    public static double insideThresholdBlocks(double rsBlocks) {
        final double shadow = shadowRadiusBlocks(rsBlocks);
        return Math.max(shadow * INSIDE_FACTOR, shadow + INSIDE_MARGIN_BLOCKS);
    }

    /** {@link #insideThresholdBlocks} 的反解：给定距离，返回 {@code dist / 阈值}（1.0 = 正好在判据边界）。 */
    public static double insideRatioOf(double distBlocks, double rsBlocks) {
        final double thr = insideThresholdBlocks(rsBlocks);
        return thr > 1.0E-9D ? distBlocks / thr : 0.0D;
    }

    /** 一行写清"洞内阈值"（进日志的 {@code insideThr=} 与报告都用它，避免两处各写一遍口径）。 */
    public static String describeInside(double rsBlocks) {
        final double shadow = shadowRadiusBlocks(rsBlocks);
        return String.format(java.util.Locale.ROOT,
                "可见黑盘半径=%.2f格 阈值=%.2f格(=max(%.2f×%.2f, %.2f+%.1f)) 改前阈值=%.2f格(×%.2f)",
                shadow, insideThresholdBlocks(rsBlocks), shadow, INSIDE_FACTOR, shadow,
                INSIDE_MARGIN_BLOCKS, shadow * INSIDE_FACTOR_BEFORE, INSIDE_FACTOR_BEFORE);
    }

    /** 黑盘占"半宽"的比例（贴图里也是这个数，两条路靠它对齐）。 */
    public static double shadowFraction() {
        return B_CRIT / LENS_RANGE_RS;
    }

    /** 一行能写进日志/报告的实测值（{@code rsBlocks} 传运行期那个 {@code BlackHoleTuning.scale()} 乘积）。 */
    public static String describe(double rsBlocks) {
        return String.format(java.util.Locale.ROOT,
                "rs=%.2f格 黑盘半径=%.2f格 透镜半径=%.2f格 吸积盘=%.2f..%.2f格 盘/黑盘=%.2fx "
                        + "盘倾角=%.1f° DISK_GAIN=%.2f RING_GAIN=%.2f 兜底贴图=%d² 洞内阈值=%.2f格(×%.2f)",
                rsBlocks, shadowRadiusBlocks(rsBlocks), lensRadiusBlocks(rsBlocks),
                diskInnerBlocks(rsBlocks), diskOuterBlocks(rsBlocks),
                diskOuterBlocks(rsBlocks) / Math.max(1.0E-9D, shadowRadiusBlocks(rsBlocks)),
                DISK_TILT_DEG, (double) DISK_GAIN, (double) RING_GAIN, FALLBACK_TEX_SIDE,
                insideThresholdBlocks(rsBlocks), INSIDE_FACTOR);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    //  二级兜底贴图：<b>四件构件</b>逐像素画（纯 CPU；游戏里与离线预览共用这一份）
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 画一个像素（{@code u,v ∈ [-1,1]}，圆心 = 0；{@code |u|=|v|=1} 对应<b>透镜半宽</b>
     * {@code = LENS_RANGE_RS × rsBlocks}）。
     *
     * <p>四件构件（用户口径「四条里少一条都算没做到」），半径全部按 <b>Rs 归一</b>算出，
     * 所以两条路的<b>世界尺寸自动对齐</b>（报告里贴的两张图就是靠这个"尺度一致"）：
     * <ol>
     *   <li><b>近黑的核心盘</b>：{@code r ≤ B_CRIT/LENS_RANGE = 0.226}（= 5.20 格）⇒ 近纯黑、不透明，
     *       最外 0.022 一圈暗蓝过渡；</li>
     *   <li><b>明亮细光子环</b>：{@code r ∈ (0.226, 0.246]}（= 1.00–1.09 × 黑盘）⇒ 暖白、细、亮；</li>
     *   <li><b>赤道方向拉长的吸积带</b>：{@code DISK_INNER..DISK_OUTER = 3..11 Rs}
     *       ⇒ 半宽比例 {@code 0.261..0.957}，竖直方向按倾角压扁 {@code sin 32° = 0.53}
     *       ⇒ 屏幕上是一条<b>横着拉长</b>的椭圆带，颜色内白热 → 中橙 → 外暗红；</li>
     *   <li><b>外圈很淡的光晕</b>：{@code 0.30..0.62} 半宽（对应一级高斯光晕的可见范围），
     *       冷白偏蓝，alpha 从 0.12 衰减到 0。</li>
     * </ol>
     *
     * @return {@code 0xAABBGGRR}（{@code NativeImage.setPixelRGBA} 的字节序，与 {@code DynamicTexture} 一致）
     */
    public static int fallbackPixel(double u, double v) {
        final double r = Math.sqrt(u * u + v * v);
        final double shadow = shadowFraction();                    // 0.2261
        final double ringOut = shadow * 1.09D;
        // 盘的内/外缘（换算成"半宽的几分之几" ⇒ 与一级的世界尺寸逐格对齐）
        final double rIn = DISK_INNER_RS / LENS_RANGE_RS;          // 0.2609
        final double rOut = DISK_OUTER_RS / LENS_RANGE_RS;         // 0.9565

        double red = 0.0D;
        double green = 0.0D;
        double blue = 0.0D;
        double alpha = 0.0D;

        // ④ 外圈很淡的光晕（先铺底，后面三件盖在上面）
        if (r > 0.30D) {
            final double t = clamp01((r - 0.30D) / 0.32D);
            final double a = 0.12D * (1.0D - t) * (1.0D - t);
            if (a > alpha) {
                red = 168.0D;
                green = 190.0D;
                blue = 255.0D;
                alpha = a;
            }
        }

        // ③ 赤道吸积带：椭圆坐标（倾角 32° ⇒ 竖直方向压扁 sin(32°) = 0.53）
        final double sinI = Math.max(0.12D, Math.sin(Math.toRadians(DISK_TILT_DEG)));
        final double rho = Math.sqrt(u * u + (v / sinI) * (v / sinI)) / rOut;   // 1.0 = 盘外缘
        final double rhoIn = rIn / rOut;
        if (rho > rhoIn && rho <= 1.14D) {
            final double band = smoothstep(rhoIn, rhoIn * 1.10D, rho)
                    * (1.0D - smoothstep(0.90D, 1.14D, rho));
            if (band > 0.0D) {
                final double t = clamp01((rho - rhoIn) / Math.max(1.0E-6D, 1.0D - rhoIn));
                final double fall = Math.pow(1.0D - t, 1.6D);
                // 颜色梯度（与着色器 cHot→cMid→cLow 同一套）：内白热 → 中橙 → 外暗红
                final double cr = 255.0D;
                final double cg = 248.0D - 195.0D * t;                       // 248 → 53
                final double cb = 226.0D * Math.pow(1.0D - t, 2.2D);         // 226 → 0（蓝掉得最快）
                // 多普勒般的左右不对称（一侧更亮）—— 让"盘在转"看得出来，而不是一团对称的糊
                final double doppler = 0.74D + 0.46D * clamp01(u * 0.5D + 0.5D);
                final double a = clamp01(band * (0.30D + 0.80D * fall) * doppler);
                if (a >= alpha) {
                    red = cr;
                    green = Math.max(30.0D, cg);
                    blue = Math.max(0.0D, cb);
                    alpha = a;
                }
            }
        }

        // ② 光子环：细、亮、暖白
        if (r > shadow && r <= ringOut) {
            final double t = (r - shadow) / Math.max(1.0E-9D, ringOut - shadow);
            // 环的"内沿"最亮：1.00–1.03 一圈近乎纯白
            final double core = 1.0D - smoothstep(0.0D, 0.42D, t);
            red = 255.0D;
            green = 232.0D + 20.0D * core;
            blue = 196.0D + 40.0D * core;
            alpha = 0.92D + 0.08D * core;
        }

        // ① 近黑核心盘（最上层）：近纯黑 + 边缘 0.02 的暗蓝过渡
        if (r <= shadow) {
            final double edge = 1.0D - smoothstep(shadow - 0.022D, shadow, r);
            red = 2.0D + 8.0D * edge;
            green = 3.0D + 10.0D * edge;
            blue = 6.0D + 16.0D * edge;
            alpha = 1.0D;
        }

        if (alpha <= 0.004D) {
            return 0;
        }
        final int a8 = (int) Math.round(clamp01(alpha) * 255.0D);
        final int r8 = (int) Math.round(clamp01(red / 255.0D) * 255.0D);
        final int g8 = (int) Math.round(clamp01(green / 255.0D) * 255.0D);
        final int b8 = (int) Math.round(clamp01(blue / 255.0D) * 255.0D);
        return (a8 << 24) | (b8 << 16) | (g8 << 8) | r8;
    }

    private static double clamp01(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
    }

    private static double smoothstep(double a, double b, double x) {
        if (b <= a) {
            return x >= b ? 1.0D : 0.0D;
        }
        final double t = clamp01((x - a) / (b - a));
        return t * t * (3.0D - 2.0D * t);
    }
}
