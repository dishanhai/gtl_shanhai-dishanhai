package com.shanhai.client.render.hostfx;

import java.util.Locale;

/**
 * 归墟视界枢核 · <b>渲染侧的「开关语义」唯一判定处</b>（机器字段 {@code hostFxEnabled=false} ⇒ <b>降级态</b>）。
 *
 * <h2>用户原话（这一整个类的规格就来自这两句）</h2>
 * <pre>
 *   ① 「始终渲染为工作状态的按钮你也用起来，直接做成开关特效渲染（关闭之后黑洞还在，内环和 8 个小飞船不转，
 *       然后上下两个光束变成红色，周围物质解压机的小光束还在，主光束消失）」
 *   ② 「中心球体渲染风格，你直接改成可以修改黑洞的半径和是否开启引力透镜效果得了」
 * </pre>
 *
 * <h2>降级态 = 5 条语义（每条都在这里有一个"能离线断言"的出口）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>语义</th><th>本类的出口</th><th>真正画的地方</th></tr>
 *   <tr><td>1</td><td><b>黑洞仍在</b>（任何黑洞路径都不许 early-return）</td>
 *       <td>—（本类不碰黑洞；半径/透镜走 {@code BlackHoleTuning} 的主机接管）</td>
 *       <td>{@code BlackHoleLensRenderer} / {@code HostFxBlackHole}</td></tr>
 *   <tr><td>2</td><td><b>内环 + 8 艘飞船：静止但仍绘制</b></td>
 *       <td>{@link #spinSeconds()}（关 = 冻结值，<b>绝不是 0</b>）</td>
 *       <td>{@code HostFxRingGroups.render} / {@code HostFxDraw.drawBeams}</td></tr>
 *   <tr><td>3</td><td><b>中央上下两束变红</b>（{@link #DEGRADED_BEAM_RGB}）</td>
 *       <td>{@link #apply()} 复用 {@code HostFxGeometry} 既有的 {@code jetGlowOverride} 机制</td>
 *       <td>{@code HostFxPillarBeam.draw}（以及它的自绘回落）</td></tr>
 *   <tr><td>4</td><td><b>解压机 4 条主喷口光柱：消失</b>（整段跳过，不是"改成透明"）</td>
 *       <td>{@link #mdMainBeamsOn()} / {@link #mdMainBeamCount()}</td>
 *       <td>{@code HostFxDraw.drawBeams} 第 ③ 段</td></tr>
 *   <tr><td>5</td><td><b>12 条细紫小束：仍在</b>（一个字都不用改）</td>
 *       <td>{@link #thinBeamCount()}（恒 = {@code HostFxGeometry.THIN_BEAM_COUNT}）</td>
 *       <td>{@code HostFxDraw.drawBeams} 第 ① 段</td></tr>
 * </table>
 *
 * <h2>粗光柱环（{@code HostFxBeamRing}）为什么也消失</h2>
 * 任务书 5 条末尾的补注逐字是「<b>粗光柱环（{@code HostFxBeamRing}）算"主光束" ⇒ 降级态一起消失</b>」。
 * 它和条目 3 括号里的「含那圈粗光柱环」互斥，两边都能查出出处；本类按<b>补注</b>实现（消失），
 * 理由是：① 补注更晚、更具体（点名了类名并给出归类）；② 那一圈本来就是用伪神光柱围出来的"主光束环"，
 * 与用户原话里的「主光束消失」同族。
 * <p>要改成"变红"只需把 {@link #beamRingOn()} 改成 {@code return true;} —— 颜色已经接好了
 * （{@link #beamRingGlowRgb()} 在降级态返回红色，见 {@code HostFxDraw} 第 ④ 段）。
 *
 * <h2>「环显示」三态（2026-11-21：两条都保留，只让玩家选显示哪一条）</h2>
 * 用户原话是「<b>选 乙，在渲染设置那个页面切换</b>」⇒ 外缘吸积流（{@code 51..57} 格）与粗光柱环
 * （{@code r=54}、管径 {@code 6}）的<b>几何一个字节都没动</b>，变的是"这一帧提交哪一条"：
 * <pre>
 *   mode = 0（默认，两条都画）  ⇒ beamRingOn() = !degraded    accretionOn() = !degraded
 *   mode = 1（只画外缘吸积流）  ⇒ beamRingOn() = false        accretionOn() = !degraded
 *   mode = 2（只画粗光柱环）    ⇒ beamRingOn() = !degraded    accretionOn() = false
 * </pre>
 * 🔴 <b>特效总开关（{@code hostFxEnabled}=false = 降级态）优先级高于「环显示」</b>：
 * 降级态下两条环一条都不画 —— 「环显示」是玩家显式选择，不许把特效总开关关掉的东西顶回来。
 * <p>mode 的<b>唯一来源</b>是 {@link HostFxState#ringVisualMode()}（渲染入口每帧喂一次），
 * 本类只在 {@link #apply()} 里抄一份快照 ⇒ <b>没有第二套状态来源</b>；判定仍然全部在本类。
 *
 * <h2>🔴 可脱机断言（和 {@link HostFxState} 同一条纪律：开关语义必须能被机器判红判绿）</h2>
 * 本文件<b>自身不 import 任何 MC / GL 类型</b>（只有 {@code java.util.Locale}），只依赖两个类：
 * <ul>
 *   <li>{@link HostFxState} —— 零依赖（见那个类的类注释）；</li>
 *   <li>{@link HostFxGeometry} —— 签名里只出现 {@code net.minecraft.core.BlockPos/Direction}
 *       这两个<b>纯数据</b>类型（几何表的入参），用两份 30 行的桩就能编译。</li>
 * </ul>
 * 于是离线探针可以这样做（{@code temp/host-fx/probe-switch/run-switch-probe.ps1}）：
 * <pre>
 *   javac -d out BlockPos.java Direction.java HostFxState.java HostFxSwitch.java HostFxGeometry.java …
 *   java  -cp out SwitchProbe          # fx=true / fx=false 两态逐条断言 + 负对照
 * </pre>
 * 这就是为什么"开关"和"画"要分家：{@code HostFxDraw} 那套（Tesselator / RenderType）离线跑不起来，
 * 而开关语义是<b>纯逻辑</b>，必须能被机器判红判绿。
 *
 * <h2>⚠️ 线程</h2>
 * 只在客户端渲染线程读写（TESR 与离线探针各一份进程，不并发）。
 */
public final class HostFxSwitch {

    private HostFxSwitch() {
    }

    /**
     * 降级态中央上下两束的发光色：<b>(255, 64, 48)</b> = {@code (1.0, 0.25098, 0.18824)}。
     *
     * <p>为什么是这组数（不是随手挑的红）：它落在"警示红"区间（色相 ≈ 4°，明度很高），
     * 加性叠在深色星空上仍然读得出是<b>红</b>；同时保留 48 的蓝通道，
     * 避免在暖色天空背景下和"橙"糊成一片（与解压机橙光 1.00/0.62/0.20 能分开）。
     *
     * <p>🔴 它<b>只</b>替换"外层发光色"那一遍；内芯那一遍在伪神 shader 里恒为白 (1,1,1)
     * （见 {@code HostFxPillarBeam.drawBeam} 的第 ② 遍），所以降级态看到的是"白芯 + 红辉"，
     * 与正常态的"白芯 + 冷蓝辉"是同一套几何、只换色。
     */
    public static final float[] DEGRADED_BEAM_RGB = {
            255.0F / 255.0F, 64.0F / 255.0F, 48.0F / 255.0F};

    /** 最近一次 {@link #apply}/{@link #reset} 判定的降级态（{@code true} = 机器把特效关了）。 */
    private static boolean degraded;

    /**
     * 「环显示」三态的快照（{@link HostFxState#ringVisualMode()} 的每帧副本，见类注释那一节）。
     *
     * <p>{@code 0} = 两条都画 / {@code 1} = 只画外缘吸积流 / {@code 2} = 只画粗光柱环。
     * 只由 {@link #apply()} 写、{@link #reset()} 复位成 {@code 0} —— <b>不另设 setter</b>，
     * 否则就等于给渲染侧开了第二条"绕过喂料"的状态来源。
     */
    private static byte ringVisualMode = HostFxState.RING_VISUAL_BOTH;

    /** 红色是不是<b>我们</b>设的（恢复时只清自己设过的，照 {@code clearBlackHoleIfOurs} 的先例）。 */
    private static boolean degradedColorApplied;

    /**
     * 降级之前那层 {@code jetGlowOverride} 的<b>快照</b>（{@code null} = 当时用的是默认色）。
     *
     * <p>为什么要存：{@code HostFxGeometry.setJetGlowRgb} 是公开的配置钩子（将来 {@code /shanhai} 命令会用）。
     * 如果别处先设了一个颜色、我们降级时把它顶成红、恢复时又 {@code clearJetGlowOverride()}，
     * 那个颜色就<b>永久丢了</b>。存一份快照 ⇒ 恢复时原样写回。
     */
    private static float[] colorBeforeDegraded;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  每帧：应用 / 撤销
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 按 {@link HostFxState#fxEnabled()} 应用开关语义（渲染器在<b>喂料之后、任何绘制之前</b>每帧调一次）。
     *
     * <p>做三件事：
     * <ol>
     *   <li>把 {@link HostFxState#ringVisualMode()} 抄成这一帧的 mode 快照（{@link #beamRingOn()} /
     *       {@link #accretionOn()} 都读它）；</li>
     *   <li>降级 ⇒ 把中央上下两束的外层发光色换成 {@link #DEGRADED_BEAM_RGB}
     *       （走既有 {@code jetGlowOverride}，不新增硬编码分支）；</li>
     *   <li>恢复 ⇒ 把这层覆盖<b>还原成我们进来之前的样子</b>（有人先设过色就写回那个色，没有就清掉）。</li>
     * </ol>
     * 幂等：连调一百次的结果与调一次相同（没有累积状态）。
     */
    public static void apply() {
        // 🔴 2026-11-21：「环显示」三态的快照（唯一来源 = HostFxState 那个喂料口；本类不自己读机器/配置）
        ringVisualMode = HostFxState.ringVisualMode();
        final boolean off = !HostFxState.fxEnabled();
        degraded = off;
        if (off) {
            if (!degradedColorApplied) {
                colorBeforeDegraded = HostFxGeometry.hasJetGlowOverride() ? HostFxGeometry.jetGlowRgb() : null;
            }
            HostFxGeometry.setJetGlowRgb(DEGRADED_BEAM_RGB[0], DEGRADED_BEAM_RGB[1], DEGRADED_BEAM_RGB[2]);
            degradedColorApplied = true;
        } else {
            restoreColor();
        }
    }

    /**
     * 撤销：主机不在 / 结构不成型 / 配置总闸关 时调（渲染器每条 early-return 都要调，和
     * {@code clearBlackHoleIfOurs()} 并列）。
     *
     * <p>清的是<b>我们设过的东西</b>：红色覆盖 ⇒ 还原；降级标记 ⇒ 复位成 false（= 工作状态，
     * 因为下一帧要么重新 {@link #apply()}、要么根本没人画）。
     */
    public static void reset() {
        degraded = false;
        // 「环显示」也一起复位成默认（两条都画）：没有主机在喂料时不该停在"少一条"的那一档
        ringVisualMode = HostFxState.RING_VISUAL_BOTH;
        restoreColor();
    }

    /** 把颜色还原成"我们进来之前"的那一层（没有覆盖过就什么都不做 —— 绝不误清别人的色）。 */
    private static void restoreColor() {
        if (!degradedColorApplied) {
            return;
        }
        degradedColorApplied = false;
        final float[] before = colorBeforeDegraded;
        colorBeforeDegraded = null;
        if (before == null) {
            HostFxGeometry.clearJetGlowOverride();
        } else {
            HostFxGeometry.setJetGlowRgb(before[0], before[1], before[2]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  读（渲染侧唯一判据；离线探针直接断言这些）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 这一帧是不是降级态（{@code true} = 机器把 {@code hostFxEnabled} 关了）。 */
    public static boolean degraded() {
        return degraded;
    }

    /**
     * <b>自转时钟</b>（秒）：环 / 8 舰 / 4 座解压机 / 12 条细紫光束的自转角都从这条时钟算。
     *
     * <p>🔴 它就是 {@link HostFxState#animSeconds()}（<b>只转发、不另存</b>）：
     * <ul>
     *   <li>fx 开 ⇒ 按墙钟累加（环 12°/s、舰 ±9°/s 照旧）；</li>
     *   <li>fx 关 ⇒ <b>冻结在上一次的值</b> ⇒ 环/舰"原地不动"；</li>
     *   <li>🔴 <b>绝不是 0</b> —— 传 0 会让它们<b>瞬移回初始姿态</b>（用户会看到"一关特效环就跳回起点"）。</li>
     * </ul>
     * 为什么不让调用方自己读：这一句是"语义"，{@code HostFxGeometry.ringDeg(...)} 只认数字，
     * 传世界时间进来就会在关闭时空转 ⇒ 必须在<b>取时钟</b>这一步就分开。
     */
    public static float spinSeconds() {
        return HostFxState.animSeconds();
    }

    /** 中央上下两束<b>当前</b>外层发光色（正常 = 冷蓝白，降级 = 红）。 */
    public static float[] centralBeamRgb() {
        return HostFxGeometry.jetGlowRgb();
    }

    /** 4 条解压机主喷口光柱这一帧画不画（降级态 = false ⇒ {@code HostFxDraw} 整段跳过）。 */
    public static boolean mdMainBeamsOn() {
        return !degraded;
    }

    /** 4 条解压机主喷口光柱这一帧的条数（正常 4、降级 0；日志与探针都读这个）。 */
    public static int mdMainBeamCount() {
        return degraded ? 0 : HostFxGeometry.MD_COUNT;
    }

    /** 12 条细紫小束的条数（<b>恒 12</b>：降级态一个字都不改）。 */
    public static int thinBeamCount() {
        return HostFxGeometry.THIN_BEAM_COUNT;
    }

    /**
     * 内外环之间那圈<b>粗光柱环</b>这一帧画不画（{@code HostFxDraw} 第 ④ 段唯一的判据，
     * 着色器两遍 + CPU 回落<b>都</b>在这一个 {@code if} 里面）。
     *
     * <p>两条语义：
     * <ul>
     *   <li>降级态（机器 {@code hostFxEnabled=false}）算"主光束" ⇒ 不画（既有语义，一个字节都没改）；</li>
     *   <li>「环显示」= 只画外缘吸积流（{@code mode==1}）⇒ 不画（玩家自己选的）。</li>
     * </ul>
     * {@code mode==2}（只画粗光柱环）<b>不看吸积流那条</b>：它要的就是"这一条留着"⇒ 判据仍是降级态那一条。
     * <p>⚠️ {@code HostFxDraw} 里<b>不许</b>再判一次 mode —— 收口只在本方法（第二条判据 = 两处口径会分叉）。
     */
    public static boolean beamRingOn() {
        return ringVisualMode != HostFxState.RING_VISUAL_ACCRETION_ONLY && !degraded;
    }

    /**
     * <b>外缘吸积流</b>（{@code 51..57} 格那圈等离子流）这一帧画不画
     * —— {@code HostFxExtraLayers} 在提交点读它（跳过时连顶点都不算）。
     *
     * <p>两条判据：
     * <ol>
     *   <li>🔴 <b>降级态优先</b>：特效总开关关 ⇒ 一条都不画（「环显示」不许把总开关关掉的东西顶回来）；</li>
     *   <li>「环显示」= 只画粗光柱环（{@code mode==2}）⇒ 不画。</li>
     * </ol>
     */
    public static boolean accretionOn() {
        if (degraded) {
            return false;
        }
        return ringVisualMode != HostFxState.RING_VISUAL_BEAM_RING_ONLY;
    }

    /** 「环显示」三态的短名（日志用；与面板那三个带色标签一一对应）。 */
    public static String ringVisualModeText() {
        switch (ringVisualMode) {
            case HostFxState.RING_VISUAL_ACCRETION_ONLY:
                return "只吸积流";
            case HostFxState.RING_VISUAL_BEAM_RING_ONLY:
                return "只光柱环";
            default:
                return "两条";
        }
    }

    /**
     * <b>星辰场（围绕黑洞的粒子）</b>这一帧画不画
     * —— {@code HostFxExtraLayers} 第 ③ 段唯一的判据（跳过时连星点都不算）。
     *
     * <p>🔴 判据 = <b>客户端配置总闸 × 机器面板开关</b>（两者都为 true 才画），口径只在本方法一处：
     * <ul>
     *   <li>{@code configEnabled} = 客户端 {@code [hostfx] starField}（<b>总闸</b>，关 = 谁都别画）；</li>
     *   <li>{@link HostFxState#starFieldEnabled()} = 这台机器面板上的开关。</li>
     * </ul>
     * 为什么把 config 当入参传进来而不是在本类读配置：本类必须保持<b>零 MC 依赖</b>（见类注释），
     * 而 {@code ShanhaiClientConfig} 会拖 MC —— 传进来就能让"与"逻辑仍只有一处、且可离线断言。
     */
    public static boolean starFieldOn(boolean configEnabled) {
        return configEnabled && HostFxState.starFieldEnabled();
    }

    /** <b>结构闪电</b>这一帧画不画（判据同 {@link #starFieldOn(boolean)}：配置总闸 × 面板开关）。 */
    public static boolean structureLightningOn(boolean configEnabled) {
        return configEnabled && HostFxState.structureLightningEnabled();
    }

    /** 星辰场/结构闪电跳过时的原因（进 stats；{@code ""} = 真的画了）。 */
    public static String layerOffReason(boolean configEnabled, boolean panelEnabled) {
        if (!configEnabled) {
            return "关:配置";
        }
        if (!panelEnabled) {
            return "关:面板";
        }
        return "";
    }

    /**
     * 粗光柱环这一帧的发光色：正常 = 调用方给的默认色（{@code HostFxBeamRingGeom.GLOW_RGB}），降级 = 红。
     *
     * <p>为什么<b>不</b>在这里直接读 {@code HostFxBeamRingGeom.GLOW_RGB}：那个类拖 MC/GL
     * （{@code Minecraft} / {@code ShaderInstance}），一引用本类就不再"零 MC 依赖"，
     * 离线探针也就编不过 —— 而本类存在的意义正是"开关语义必须能被机器判红判绿"。
     * 所以默认色由调用方传进来，本类只决定"用你的还是用红的"。
     *
     * <p>为什么先把颜色接好：那个"消失 vs 变红"的分歧（见类注释）是一行开关的事，
     * 颜色先接上，改成"变红"时就不必再动 {@code HostFxDraw}。
     */
    public static float[] beamRingGlowRgb(float[] normalRgb) {
        return degraded ? DEGRADED_BEAM_RGB.clone() : normalRgb;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  日志（{@code logDraw} 行尾那一段；格式由任务书钉死，逐字可 grep）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * {@code logDraw} 行尾那一段：
     * <pre>
     *   fx=on degraded=no lens=on holeR=5.2 ring=12°/s ships=+9/−9°/s
     *   fx=off degraded=yes lens=off holeR=8.0 ring=12°/s ships=+9/−9°/s
     * </pre>
     *
     * <p>六个字段的<b>口径</b>（写错的日志比没日志更坏）：
     * <ul>
     *   <li>{@code fx=} 机器字段 {@code hostFxEnabled}（{@code on} = 工作状态）；
     *       ⚠️ 配置总闸 {@code ShanhaiClientConfig.hostFxEnabled()} 关时这一行<b>根本不打</b>
     *       （那条路径有自己的 {@code gate=config_disabled} 行）⇒ 见到 {@code fx=off} 就一定是机器关的；</li>
     *   <li>{@code degraded=} 由 {@code fx} 推出（两者互为反相，重复打是为了能单独 grep 一个词）；</li>
     *   <li>{@code lens=} 机器字段 {@code hostLensEnabled}（真正决定"背景扭不扭"的那一个）；</li>
     *   <li>{@code holeR=} 机器字段 {@code holeRadiusBlocks}（格，一位小数）；</li>
     *   <li>{@code ring=} 内环角速度（°/s）；{@code ships=+上/−下}（°/s，下组是<b>幅值</b>，负号是渲染侧的事）。</li>
     * </ul>
     */
    public static String statusFragment() {
        return "fx=" + (HostFxState.fxEnabled() ? "on" : "off")
                + " degraded=" + (degraded ? "yes" : "no")
                + " lens=" + (HostFxState.lensEnabled() ? "on" : "off")
                + " holeR=" + String.format(Locale.ROOT, "%.1f", HostFxState.holeRadiusBlocks())
                + " ring=" + deg(HostFxState.ringDegPerSec()) + "°/s"
                + " ships=+" + deg(HostFxState.shipUpDegPerSec())
                + "/−" + deg(HostFxState.shipDownDegPerSec()) + "°/s";
    }

    /** 角度写法：整数不带 {@code .0}（12 ⇒ {@code "12"}），非整数保留一位（12.5 ⇒ {@code "12.5"}）。 */
    private static String deg(float value) {
        final String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
