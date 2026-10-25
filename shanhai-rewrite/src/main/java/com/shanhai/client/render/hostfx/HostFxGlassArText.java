package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.math.Axis;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.machine.ParallelPowerBudget;
import com.shanhai.machine.host.GuixuVisionCoreMachine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 归墟视界枢核 · <b>聚变玻璃墙上的 AR 面板</b>（版式 B · 实测版）—— 文字 + 几何的<b>提交层</b>。
 *
 * <p>本类只做四件事：<b>①</b> 从机器与 {@link HostFxState} 读真值 → 填
 * {@link HostFxGlassArModel.State}；<b>②</b> 让 {@link HostFxGlassArModel} 算出这一帧的图元与文字；
 * <b>③</b> 调 {@link HostFxGlassArDraw} 提交几何；<b>④</b> 用 {@code Font.drawInBatch} 提交文字。
 * <b>布局</b>只有一份（{@link HostFxGlassArPanel}），<b>内容</b>只有一份（{@link HostFxGlassArModel}）。
 *
 * <h2>🔴 三种"能动"的东西全部只读同一个时钟</h2>
 * <pre>
 *   内环带 / 4 座解压机（含 12 条细紫束）  自转角 = animSeconds × ringDegPerSec
 *   上 4 舰 / 下 4 舰                      轨道角 = animSeconds × ±shipDegPerSec
 *   文字呼吸（扫描线+整块）                 相位     = animSeconds
 * </pre>
 * 时钟取 {@link HostFxSwitch#spinSeconds()}（= {@link HostFxState#animSeconds()}）：
 * <b>fx 关时它自己冻结</b> ⇒ 一切"原地不动"，而<b>不是</b>把角度乘 0（那会瞬移回初始姿态）。
 * 渲染器传进来的 {@code seconds}（世界时间）<b>刻意不用</b>：世界时间在关特效时还在走，
 * 用它算角速度会"关了特效环还在转"（{@link HostFxState} 的类注释里写着这条约定）。
 *
 * <h2>朝向（房间里正读、房间外镜像）—— 与改造前逐字相同，没破坏</h2>
 * {@code rotY(+90°) · diag(s, −s, −s)}：文字 +X → 本地 −Z（房间里看的"右"）、+Y → 本地 −Y（下）、
 * 正面朝 <b>+X = 房间</b>。同一片字<b>画两遍、只有绕序相反</b>（{@code diag(s,−s,−s)} 与
 * {@code diag(s,−s,+s)}）：剔除开着时房间只看见"前"、外面只看见"后"；剔除关着时两遍重合。
 * 所以"里面正读、外面镜像"对"MC 到底剔不剔"不敏感（推导见改造前那一版的类注释，结论未变）。
 *
 * <h2>读数的口径（宁可缺，不可假）</h2>
 * <table border="1">
 *   <tr><th>行</th><th>来源</th><th>取不到</th></tr>
 *   <tr><td>结构已成型</td><td>渲染器成型闸门（本方法只在成型之后被调用）</td><td>—（走不到这里）</td></tr>
 *   <tr><td>已连接模块 n / 24</td><td>{@code getFormedModuleCount()} / {@code getMaxModuleCount()}</td><td>{@code --}</td></tr>
 *   <tr><td>能源仓 / 动力仓 <b>容量</b></td><td>{@code getCapabilitiesProxy().get(IO.IN|OUT, EURecipeCapability.CAP)}
 *       里 {@link IEnergyContainer#getEnergyCapacity()} 求和（与 {@code GuixuVisionCoreMachine#energyTotals}
 *       同一读法）</td><td>{@code --}</td></tr>
 *   <tr><td>物质模块等级 / 等级复写 / 材料增产</td><td>{@code moduleSlotBonus()} /
 *       {@code getHostMatterLevelOverride()} / {@code productionMultiplier()}</td><td>{@code --}</td></tr>
 *   <tr><td>内环 / 上舰 / 下舰 / 黑洞半径 / 引力透镜 / 特效渲染</td><td>{@link HostFxState}（机器字段的客户端副本）</td>
 *       <td>—（{@code HostFxState} 自己钳到合法区间）</td></tr>
 * </table>
 * <b>为什么只显示容量、不显示"存量 / 容量"</b>：{@code EnergyHatchPartMachine.energyContainer} 是
 * {@code @Persisted} 而<b>没有</b> {@code @DescSynced}（javap 实证，见
 * {@code temp/host-fx/报告-光柱环与AR文字.md}）⇒ 存量<b>根本不同步到客户端</b>，客户端读到恒 0。
 * 显示成"0 / 1.07G"就是假数据（本工程红线）。原文口径是 {@code -- / 1073741824 EU}，
 * 本轮按用户"容量用紧凑格式 {@code 1.07G EU}"的要求改成"<b>容量</b> 1.07G EU"，语义仍然是真的。
 */
public final class HostFxGlassArText {

    private HostFxGlassArText() {}

    /** 满亮度 lightmap（{@code 0xF000F0}）：自发光屏，不吃环境光。 */
    public static final int LIGHT_FULL = 0x00F000F0;

    /**
     * 读数重算的节拍 = <b>每隔多少个游戏 tick 重建一次</b>。
     *
     * <p>🔴 2026-10-25 第二十七改（用户原话：「<b>还有就是你把 AR 屏幕的更新频率改成 1 tick 更新一下</b>」）：
     * 取 <b>1</b> ⇒ 每 tick 恰好重建一次 = <b>20 次/秒</b>（1 tick = 50 ms），与帧率<b>解耦</b>。
     *
     * <p><b>三个版本，别搞混</b>：
     * <pre>
     *   100 ms 时间窗口（10 次/秒）──第二十五改──▶ 每帧（60 fps ⇒ 60 次/秒）──第二十七改──▶ 每 tick（20 次/秒）
     * </pre>
     * <b>为什么是 tick 而不是"每毫秒 / 每帧"</b>：tick 是"机器数据真的可能变了"的最小节拍，
     * 帧只是"显示器要画"的节拍。60 fps 下按帧重建，其中约 2/3 的重建读到的必然是同 tick 的同一份数据
     * ⇒ 白读 40 次/秒（见 {@link #tickSkips}）。
     */
    public static final int REBUILD_EVERY_TICKS = 1;

    /** 荧光晕的 4 个像素偏移（(dx,dy) 对）。 */
    private static final float[][] HALO_OFFSETS = {{-1.0F, 0.0F}, {1.0F, 0.0F}, {0.0F, -1.0F}, {0.0F, 1.0F}};
    /** 荧光晕的 alpha 系数。 */
    private static final float HALO_ALPHA = 0.18F;

    /** 机器读数缓存（<b>每 tick</b> 刷新；{@link HostFxState} 那 6 个值每帧现读）。 */
    private static final HostFxGlassArModel.State STATE = new HostFxGlassArModel.State();
    /** "从未建过 / 读不到 tick"的哨兵。<b>绝不可参与减法</b>（见 {@link #rebuildReadingsIfStale} 的注释）。 */
    private static final long NEVER = Long.MIN_VALUE;
    /**
     * 上一次重建所在的<b>游戏 tick</b>（{@link #NEVER} = 还没建过 / 读不到 tick）。
     *
     * <p>🔴 这个字段是 {@link #rebuildReadingsIfStale} 唯一的节拍依据 —— <b>不是毫秒</b>。
     * 它只与 {@code gameTick} 做 {@code ==} / 受保护的减法（两边都先排除 {@link #NEVER}），
     * 因此 2026-10-25 那次"哨兵参与减法 ⇒ long 溢出 ⇒ 永远早退"的 bug 结构上不可能复发。
     */
    private static long lastBuildTick = NEVER;

    // ── 🔴 2026-10-25 第二十五改：三个计数 —— "客户端读路径到底跑没跑"的唯一凭据 ────────────
    //
    //   起因（真机现象）：AR 右侧信息栏整片 `--`，而服务端 `[SHANHAI-HOSTSNAP] seq=169` 在涨、
    //   客户端 `[SHANHAI-HOSTSNAP]` 却一行都没有。既有日志无法区分下面三种情况：
    //     ① 读路径压根没被执行；② 执行了但读到 seq=0；③ 执行了、被异常吞了。
    //   这三个计数 + 对应的 4 条日志，就是为了一眼分开它们。
    /**
     * 🔴 2026-10-25 第二十七改：{@link #rebuildReadingsIfStale} 真正建起来的次数
     * = <b>"重建了几次"</b>（<b>每 tick 一次</b>；不含被 tick 闸门跳过的渲染帧）。
     * <p>60 fps 下它与渲染帧数的比值恒为 {@code 20/60 = 1/3}（见 {@link #tickSkips}）。
     */
    private static int rebuildCalls;
    /**
     * 同一个 tick 内被 {@link #rebuildReadingsIfStale} 跳过的渲染帧数 ——
     * <b>"闸门真的在按 tick 生效"的判据</b>：60 fps 下 ≈ 40 帧/秒，即 {@link #rebuildCalls} 的 2 倍。
     * <p>它<b>不</b>代表"面板更新变慢"：一帧的读数被复用，只说明这一刻服务端也没有新数字
     * （服务端的进度现在是每 tick 推一次，见 {@code GuixuVisionCoreMachine#refreshProgressTick}）。
     */
    private static int tickSkips;
    /** {@link #readRuntimeSnapshot} 被调到过多少次（0 = 读路径压根没执行 ⇒ 上一轮的真凶）。 */
    private static int snapReadCalls;
    /** 20 tick 探针已经打了几行（前 5 行就够；判"同步到没到"）。 */
    private static int probeLines;
    /** 探针上一次所在的 tick 桶（= gameTime / 20），避免同一桶重复打。 */
    private static long probeBucket = NEVER;
    /**
     * 快照闸门（{@code seq <= 0}）连续空转多少次之后才升级成 WARN。
     * <p>为什么不在第一次就 WARN：首帧几乎必然 {@code seq==0}（服务端快照还没算完/包还没到），
     * 那不是故障。<b>每 tick</b> 一次（20 Hz）× 100 ≈ <b>5 秒</b>还是 0 ⇒ 才真的是断在同步链路。
     * <p>🔴 2026-10-25 第二十七改：读数从"每帧"改成"每 tick"后，同一个墙钟时间内的读次数变成 1/3，
     * 所以这个门限从 50 抬到 100，**保持"≈5 秒"这个语义不变**（换的不是判据，是节拍）。
     */
    private static final int SNAP0_WARN_AFTER = 100;

    /** 上一帧画出来的模型（{@code null} = 还没画过）。 */
    private static HostFxGlassArModel.Model lastModel;
    /** 上一帧提交的文字行数（0 = 没画）。 */
    private static int lastTexts;
    /** 启动自检的结果（{@code null} = 通过；否则是那一行）。 */
    private static String selfCheckFail;
    /** 已经打过的自检结论（同一条只打一次，避免刷屏；不会掩盖新问题）。 */
    private static final Set<String> WARNED = new LinkedHashSet<>();
    /** 上一帧的量（日志用）。 */
    private static int lastPrims;
    private static int lastQuads;

    // ── 🔴 2026-10-25 第二十六改：每帧重建的"内容级"去重缓存（不是时间窗口）────────────
    /** 上一帧已解析的主机输出文本（输入数组逐元素相同 ⇒ 直接复用，不再查注册表/拼字符串）。 */
    private static int[] lastHostOutIds;
    private static int[] lastHostOutAmts;
    private static String[] lastHostOutTexts = new String[0];
    /** 上一帧已解析的模块输出文本。 */
    private static int[] lastModOutIds;
    private static int[] lastModOutAmts;
    private static String[][] lastModOutTexts;
    /** 上一次读模块名时的权威位图（位图没变 ⇒ 名字不用重查世界）。 */
    private static long lastNameMask = NEVER;
    /** 名字是否已经成功解析过一次（{@code false} ⇒ 首帧必读）。 */
    private static boolean namesResolved;

    /** 上一帧提交的文字行数（0 = 没画；正常 = 每块加起来 30 行左右）。 */
    public static int linesDrawn() {
        return lastTexts;
    }

    /** 换维度/复位。 */
    public static void reset() {
        lastBuildTick = NEVER;
        lastModel = null;
        lastTexts = 0;
        lastPrims = 0;
        lastQuads = 0;
        selfCheckFail = null;
        WARNED.clear();
        // 诊断计数一起归零（换维度 = 重新问一遍"读路径通没通"，探针也因此能再打 5 行）
        rebuildCalls = 0;
        tickSkips = 0;
        snapReadCalls = 0;
        probeLines = 0;
        probeBucket = NEVER;
        STATE.animSeconds = 0.0F;
        // 2026-10-25 第二十六改：每帧重建的缓存一起作废（换维度 = 另一台机器、另一套输出）
        lastHostOutIds = null;
        lastHostOutAmts = null;
        lastHostOutTexts = new String[0];
        lastModOutIds = null;
        lastModOutAmts = null;
        lastModOutTexts = null;
        lastNameMask = NEVER;
        namesResolved = false;
        // 2026-10-25 第二十六改：供能口径也要重新探（换维度后是另一台机器/另一套仓）
        STATE.energyInKind = HostFxGlassArModel.ENERGY_KIND_UNKNOWN;
        // 2026-10-25 第二十二改：待提交的读数也一起丢掉（不留跨维度/跨存档的残渣）
        HostFxGlassArDeferred.reset();
    }

    /** 一行摘要（渲染器每 5 秒那条 {@code draw} 日志里带；进游戏 grep {@code 玻璃AR} 就能看到判据读数）。 */
    public static String stats() {
        final HostFxGlassArModel.Model m = lastModel;
        if (m == null) {
            return "玻璃AR 未画（model=null）";
        }
        final HostFxGlassArModel.Prim jet = m.first(HostFxGlassArModel.Z_SIDE, "side.jet_outer");
        final String jetColor = jet == null ? "无"
                : (jet.r() > 200 && jet.g() < 120 ? "红" : "青白");
        final int mdTop = m.count(HostFxGlassArModel.Z_TOP, "top.md_pillar");
        final int mdSide = m.count(HostFxGlassArModel.Z_SIDE, "side.md_pillar");
        return "玻璃AR fx=" + (m.fxOn ? "on" : "off")
                + " 遮罩=" + (HostFxState.arMaskEnabled() ? "开" : "关")
                + " 模块=" + STATE.moduleCount + "/" + STATE.maxModules
                + "（名 " + STATE.moduleNames.length + " 个"
                + (STATE.moduleNamesHidden > 0 ? "，另有 " + STATE.moduleNamesHidden + " 个未列" : "")
                + (STATE.moduleNamesUnavailable ? "，暂不可读" : "") + "）"
                + " degraded=" + (m.fxOn ? "no" : "yes")
                + " 分区=6 文字=" + m.texts.size() + "（提交 " + lastTexts + "）"
                + " 四边形=" + m.prims.size() + "（提交 " + lastQuads + "）"
                + " 主柱=top" + mdTop + "/side" + mdSide
                + " 细紫=top" + m.count(HostFxGlassArModel.Z_TOP, "top.thin") + "/side" + m.count(HostFxGlassArModel.Z_SIDE, "side.thin")
                + " 中央两束=" + jetColor
                + " 环角=" + String.format(java.util.Locale.ROOT, "%.1f", m.ringAngleDeg) + "°"
                + " 舰角=+" + String.format(java.util.Locale.ROOT, "%.1f", m.shipUpAngleDeg)
                + "/" + String.format(java.util.Locale.ROOT, "%.1f", m.shipDownAngleDeg) + "°"
                + " | 自检=" + (selfCheckFail == null ? "ok" : selfCheckFail)
                // 🔴 2026-10-25 第二十七改：长期判据（每 5 秒一行，比 5 行的探针更长久）——
                //    60 fps 下 重建:跳过 ≈ 1:2；重建数应与"服务端 progress# 的增量"同量级。
                + " | 读数重建=" + rebuildCalls + "次（每tick一次） 同tick跳过=" + tickSkips + "帧";
    }

    /**
     * 画整块 AR 面板（<b>调用方保证"已成型"</b>—— 渲染器在成型闸门之后才调到这里）。
     *
     * @param cx,cy,cz 环心相对控制器的坐标（与三组烘焙同一个枢轴）
     * @param facing   主机朝向（{@link HostFxRingTransforms#pivot} 用它套 yaw）
     * @param seconds  世界时间（秒）—— <b>刻意不用</b>（见类注释：动效只认 {@link HostFxState#animSeconds()}）
     * @return 提交的文字行数
     */
    public static int render(PoseStack poseStack, GuixuVisionCoreMachine host,
                             float cx, float cy, float cz, Direction facing, float seconds) {
        lastTexts = 0;
        if (host == null || poseStack == null) {
            return 0;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            return 0;
        }
        final Font font = mc.font;
        // ① 机器读数（每 tick 缓存 = 20 次/秒）——只有这部分是"贵"的（要遍历能力代理）
        rebuildReadingsIfStale(host);
        // ①.5 🔴 2026-10-25 第二十五改：每 20 tick 一行客户端探针（前 5 行）——
        //      "同步到底通没通"的判据，与①的取数解耦（它直接读机器上的 @DescSynced 镜像）。
        probeRuntimeSnapshot(host);
        // ② 特效侧 6 个值每帧现读（HostFxState 是机器字段的客户端副本，钳位已在那边做过）
        STATE.fxOn = HostFxState.fxEnabled();
        STATE.degraded = HostFxSwitch.degraded();
        STATE.lensOn = HostFxState.lensEnabled();
        // 🔴 2026-10-25 第二十一改：AR 遮罩开关（机器字段 hostArMaskEnabled 的客户端副本）
        STATE.arMask = HostFxState.arMaskEnabled();
        STATE.ringDegPerSec = HostFxState.ringDegPerSec();
        STATE.shipUpDegPerSec = HostFxState.shipUpDegPerSec();
        STATE.shipDownDegPerSec = HostFxState.shipDownDegPerSec();
        STATE.holeRadiusBlocks = HostFxState.holeRadiusBlocks();
        STATE.degradedRgb = HostFxSwitch.DEGRADED_BEAM_RGB;
        STATE.mdPillarCount = HostFxSwitch.mdMainBeamCount();
        STATE.thinBeamCount = HostFxSwitch.thinBeamCount();
        // ③ 🔴 唯一的时钟：关特效时它自己冻结（不是传 0）
        STATE.animSeconds = HostFxSwitch.spinSeconds();

        final HostFxGlassArModel.Model model = HostFxGlassArModel.build(STATE);
        lastModel = model;
        lastPrims = model.prims.size();

        // ④ 启动自检（一次 WARN 就够；同一个结论不重复打）
        if (selfCheckFail == null) {
            selfCheckFail = HostFxGlassArPanel.selfCheck(measure(font, model));
            if (selfCheckFail == null) {
                logOnce("ok", "[SHANHAI-HOSTFX] glass_ar_measured ok 判据：{}", HostFxGlassArPanel.stats());
            } else {
                logOnce(selfCheckFail, "[SHANHAI-HOSTFX] glass_ar_mismatch {} | {}",
                        selfCheckFail, HostFxGlassArPanel.stats());
            }
        }

        // ⑤/⑥ 🔴 2026-10-25 第二十二改：**这里不再当场画**，只把这一帧的模型与矩阵存起来。
        //
        //    为什么：本方法跑在 TESR 里（LevelRenderer.renderLevel 内部），而引力透镜跑在
        //    `GameRenderer.renderLevel` 里 LevelRenderer 返回之后 ⇒ AR **必然**是透镜的"背景"，
        //    于是被弯折、遮罩也盖不住黑洞（用户 2026-10-25 两条原话）。
        //    处置见 {@link HostFxGlassArDeferred}：透镜画完之后用同一份矩阵把同一批顶点提交一遍，
        //    位置逐像素不变、只是时机挪到透镜之上；深度缓冲此刻还没被清 ⇒ 方块遮挡照旧。
        HostFxGlassArDeferred.capture(poseStack, model, cx, cy, cz, facing);
        lastQuads = 0;
        lastTexts = 0;
        return 0;
    }

    /** {@link HostFxGlassArDeferred} 提交完之后回填"到底交了多少"（日志/判据的唯一来源）。 */
    static void noteDeferredFlush(int quads, int texts) {
        lastQuads = quads;
        lastTexts = texts;
    }

    /**
     * 文字提交：一片字画两遍（绕序相反），见类注释 §朝向。
     *
     * <p>包内可见（2026-10-25 第二十二改）：实际的提交时机交给 {@link HostFxGlassArDeferred}
     * （透镜之后），但它必须与几何用<b>同一个</b> {@code PoseStack}，所以这里不自己建栈。
     */
    static int drawTexts(PoseStack poseStack, Font font, HostFxGlassArModel.Model model,
                                 float cx, float cy, float cz, Direction facing) {
        final float t = STATE.animSeconds;
        // 扫描线呼吸：逐行错相位（±10%），再乘整块的慢呼吸（±4%）—— 相位同样来自动画时钟（关特效时冻结）
        final float breath = 0.04F * (float) Math.sin(t * 0.9D);
        int drawn = 0;
        poseStack.pushPose();
        try {
            HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
            poseStack.translate(HostFxGlassArPanel.textPlaneX(),
                    HostFxGlassArPanel.WALL_Y1, HostFxGlassArPanel.WALL_Z1);
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            final float s = HostFxGlassArPanel.PIXEL;
            final MultiBufferSource.BufferSource buffers =
                    MultiBufferSource.immediate(Tesselinator());
            try {
                for (int face = 0; face < 2; face++) {
                    poseStack.pushPose();
                    try {
                        // face 0 = 正面朝 +X（房间）；face 1 = 正面朝 −X（环那侧 = 镜像）
                        poseStack.scale(s, -s, face == 0 ? -s : s);
                        final Matrix4f m = poseStack.last().pose();
                        for (HostFxGlassArModel.Txt txt : model.texts) {
                            if (txt.text == null || txt.text.isEmpty()) {
                                continue;
                            }
                            final float wave = 0.10F * (float) Math.sin(t * 2.2D - txt.slot * 0.7D);
                            final float pulse = clamp01(0.92F + wave + breath);
                            final int color = withAlphaMul(txt.argb, pulse);
                            // ① 荧光晕（4 个方向的低 alpha 副本，垫在底下）
                            for (float[] off : HALO_OFFSETS) {
                                font.drawInBatch(txt.text, txt.x + off[0], txt.y + off[1],
                                        withAlphaMul(color, HALO_ALPHA), false, m, buffers,
                                        Font.DisplayMode.NORMAL, 0, LIGHT_FULL);
                            }
                            // ② 主字
                            font.drawInBatch(txt.text, txt.x, txt.y, color, false, m, buffers,
                                    Font.DisplayMode.NORMAL, 0, LIGHT_FULL);
                            if (face == 0) {
                                drawn++;
                            }
                        }
                    } finally {
                        poseStack.popPose();
                    }
                }
            } finally {
                // 当场提交：不依赖调用方的 flush 时机（"字到底出现没有"的唯一判据）
                buffers.endBatch();
                RenderSystem.defaultBlendFunc();
            }
        } finally {
            poseStack.popPose();
        }
        return drawn;
    }

    /** {@code Tesselator.getInstance().getBuilder()}（单独抽出来只是为了让 {@link #drawTexts} 读着不吵）。 */
    private static com.mojang.blaze3d.vertex.BufferBuilder Tesselinator() {
        return Tesselator.getInstance().getBuilder();
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    /** 把 0..1 的系数乘进 ARGB 的 alpha。 */
    private static int withAlphaMul(int argb, float mul) {
        final float a0 = ((argb >>> 24) & 0xFF) / 255.0F;
        final int a = Math.max(0, Math.min(255, Math.round(a0 * mul * 255.0F)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  自检用的实测宽度（用 font.width，不是估的）
    // ═══════════════════════════════════════════════════════════════════════════════════

    private static HostFxGlassArPanel.Widths measure(Font font, HostFxGlassArModel.Model model) {
        final HostFxGlassArPanel.Widths w = new HostFxGlassArPanel.Widths();
        for (HostFxGlassArModel.Txt t : model.texts) {
            final int px = font.width(t.text);
            switch (t.zone) {
                case HostFxGlassArModel.Z_READ -> {
                    final int col = t.slot / HostFxGlassArPanel.READ_ROWS;
                    if (col >= 0 && col < w.readCol.length) {
                        w.readCol[col] = Math.max(w.readCol[col], px);
                    }
                }
                case HostFxGlassArModel.Z_PHYS -> w.phys = Math.max(w.phys, px);
                case HostFxGlassArModel.Z_HOST -> {
                    // 左列 = slot 0..HOST_ROWS-1；右列 = HOST_ROWS..2*HOST_ROWS-1
                    if (t.slot < HostFxGlassArPanel.HOST_ROWS) {
                        w.hostA = Math.max(w.hostA, px);
                    } else {
                        w.hostB = Math.max(w.hostB, px);
                    }
                }
                case HostFxGlassArModel.Z_MODLIST -> {
                    // 表头（slot 0..6）与"…还有 N 个"（100/101）不参与；数据行是 200+
                    if (t.slot >= 200) {
                        w.modRows = Math.max(w.modRows, px);
                    }
                }
                default -> {
                }
            }
        }
        return w;
    }

    private static void logOnce(String key, String fmt, Object... args) {
        if (WARNED.add(key)) {
            // "ok" / "snapseq"（成功那条）/ 各条"我还活着"的探路日志走 INFO；
            // 其余走 WARN —— 判据：**WARN 里出现的一定是问题**（INFO 里出现的是进度）。
            switch (key) {
                case "ok", "snapseq", "snapseq0_first",
                     "read_enter", "snapread_throw_note" -> ShanhaiMod.LOGGER.info(fmt, args);
                default -> ShanhaiMod.LOGGER.warn(fmt, args);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  机器读数（每 tick 一次 = 20 次/秒）
    // ═══════════════════════════════════════════════════════════════════════════════════

    private static void rebuildReadingsIfStale(GuixuVisionCoreMachine host) {
        // ── 🔴 2026-10-25 第二十七改：闸门 = 【每 tick 一次】（用户原话：
        //    「还有就是你把 AR 屏幕的更新频率改成 1 tick 更新一下」）──
        //    判据只有一句：**上一次重建所在的 tick == 这一次 ⇒ 整段跳过**；跨 tick 才重建。
        //
        //    为什么用 level.getGameTime()（而不是 System.currentTimeMillis() / 帧计数）：
        //      ① **它就是 tick 计数**：客户端每 tick +1（20 次/秒），与帧率解耦 —— 60 fps 不会让它多走；
        //      ② 本文件里已经有一个 "tick" 口径：probeRuntimeSnapshot 用 host.getLevel().getGameTime()
        //         算 20 tick 桶 ⇒ 日志里 `tick=T` 与探针那栏 `tick=` 是同一个数，可以直接对账；
        //      ③ 不新增事件路径（本类刻意不注册 ClientTickEvent，理由见 probeRuntimeSnapshot 的注释）；
        //      ④ 世界没在跑（暂停 / 未进世界 / 换维度中）时 gameTime **冻结** ⇒ 白重建不会发生；
        //         而那时机器也不 tick、服务端也不推新值 ⇒ 面板内容本来就不变，不算"漏更新"。
        //    读不到 tick（level == null）⇒ 退化成"每帧重建"（旧行为），绝不因此把面板冻住。
        //
        //    ⚠️ 保留的既有短路（**不是时间窗口**，全是"内容没变就不重算"）：
        //      · readModuleNames：权威位图 mask 没变 ⇒ 不重查世界；
        //      · 输出 名字×数量：ids/amounts 逐元素相同 ⇒ 复用上一 tick 的数组。
        //      ⇒ 它们都按**内容**判，所以"数字一变，下一 tick 立刻反映"，不会多等一帧。
        //
        //    ⚠️ 这条闸门**只省 CPU，不改善流畅度**（这点很关键，别再搞错方向）：
        //      60 fps 下重建从 60 次/秒降到 20 次/秒；而"进度条流不流畅"由
        //      **服务端多久把 progress 写一变**决定 —— 那条已单独修成每 tick
        //      （GuixuVisionCoreMachine#refreshProgressTick，见该方法的注释）。
        final long tick = gameTick(host);
        if (tick != NEVER && lastBuildTick != NEVER && tick - lastBuildTick < REBUILD_EVERY_TICKS) {
            // 同一个 tick 内：复用上一次重建的读数。
            // 🔴 几何 / 角度 / 呼吸相位**不在这里**（它们在 render() 里每帧重算，见类注释 §三种"能动"的东西）
            //    ⇒ "面板还在转吗"与这条闸门无关，画面照旧是逐帧流畅的。
            tickSkips++;
            return;
        }
        lastBuildTick = tick;                      // 读不到 tick 时写回 NEVER ⇒ 下一帧照建（退化成每帧）
        rebuildCalls++;
        if (rebuildCalls <= 3) {
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTSNAP] client rebuild enter #{} tick={}"
                            + "（每tick一次；同 tick 内的渲染帧已跳过 {} 帧、复用上次读数）",
                    rebuildCalls, tick == NEVER ? "NA" : tick, tickSkips);
        }
        // ══════════════════════════════════════════════════════════════════════════════════
        // 🔴🔴 2026-10-25 第二十五改（历史，别重犯）—— **"AR 整片 --"的真凶就是"哨兵进算术"**
        //
        //   旧代码：
        //       private static long lastBuildMs = Long.MIN_VALUE;   // "从未建过"的哨兵
        //       if (now - lastBuildMs < REBUILD_MS) { return; }     // ← 哨兵参与减法 ⇒ long 溢出
        //       lastBuildMs = now;                                  // ← 只有活下来才写
        //
        //   算一下就明白：now ≈ 1.79e12，Long.MIN_VALUE = −2^63
        //       now − Long.MIN_VALUE = now + 2^63 > Long.MAX_VALUE  ⇒ **溢出**
        //   实测结果 = −9223370243953245457（负数）⇒ 恒 `< 100` ⇒ **必早退**；
        //   而早退发生在 `lastBuildMs = now` 之前 ⇒ 哨兵永远留在 MIN_VALUE ⇒ **永远早退**。
        //
        //   后果不是"少刷了一次"，而是**本方法自始至终一次都没有执行过**：
        //     · readModuleNames()    → 模块名 / 槽位 永远是 null ⇒ 模块列表整块"等伺服同步"
        //     · readRuntimeSnapshot() → 快照一次都没读 ⇒ seq<=0 那条 WARN 也到不了 ⇒ 日志里客户端零行
        //     · moduleCount / matterLevel / energyCapacity → 全是初值 −1 ⇒ AR 整片 `--`
        //   而 `内环 12.0°/s` 那 6 条是 render() 里每帧现读 HostFxState 的，不走这里 ⇒ 只有它们正常。
        //   ⇒ 这就是"服务端 seq 在涨、客户端一条日志都没有、AR 全横线"的完整解释。
        //   修法：**先把哨兵本身排除掉，再比差值**（哨兵不进算术）。
        //   红→绿实证：temp\ar-debug\probe\ThrottleProbe.java（JDK21 真跑）
        //       BEFORE 600 帧 built=0 ；AFTER built=86 。
        //
        // 🔴 2026-10-25 第二十六改：闸门整段删掉（当时用户说「AR 的更新速率有点慢」，
        //   我把"慢"读成了"刷新率不够"，于是改成每帧 —— **方向读错了**，见第二十七改）。
        //   那次"删掉闸门"本身的成本账仍然成立，保留在这里当参考：
        //     · 一次重建 = 1 次 `getFormedModuleCount` + 1 次 `hostModuleCountAuthoritative`
        //       + 读 7 个快照数组引用（**不算内容**）+ 2 次 `energyTotals`（各走一遍能力代理）
        //       + 1 次能源仓型探测（只在 UNKNOWN 时走，认出一次就锁存，见 detectEnergyInKind）。
        //       **不遍历 24 个模块槽**：槽位→行的映射在 `HostFxGlassArModel.buildHost/buildModuleList`
        //       里按 `moduleSlots` 走，那是"画每一帧本来就要做的事"，与重建无关。
        //     · 真正的字符串拼接全在 `HostFxGlassArModel.build()`（本来就每帧都建），
        //       所以删/加这道闸门不会改变拼接次数。
        //   ⇒ 现在这道闸门按 tick 计（第二十七改），60 fps 下重建次数 60 → 20 次/秒。
        // ══════════════════════════════════════════════════════════════════════════════════
        STATE.formed = true;                       // 走得到这里 = 渲染器的成型闸门已经放行
        STATE.moduleCount = safeInt(host::getFormedModuleCount);
        STATE.maxModules = safeInt(host::getMaxModuleCount);
        // 🔴 2026-10-25 第二十一改：⑥「已装模块」用【服务端权威位图】的人口数（不是客户端自己扫的结果）
        final int authoritative = safeInt(host::hostModuleCountAuthoritative);
        if (authoritative >= 0) {
            STATE.moduleCount = authoritative;
        }
        readModuleNames(host);
        readRuntimeSnapshot(host);
        STATE.matterLevel = safeInt(host::moduleSlotBonus);
        STATE.levelOverride = safeInt(host::getHostMatterLevelOverride);
        STATE.productionMultiplier = safeInt(host::productionMultiplier);
        // 🔴 2026-10-25 第二十六改：先探供能口径（决定"总耗能功率"写数字还是写 ∞）
        detectEnergyInKind(host);
        final long[] in = energyTotals(host, IO.IN);
        final long[] ou = energyTotals(host, IO.OUT);
        // 只在服务端快照没给容量时才退回客户端本地容量（快照优先：与 addDisplayText 同一来源）
        if (STATE.energyInCapacity < 0L) {
            STATE.energyInCapacity = in == null ? -1L : in[1];
        }
        if (STATE.energyOutCapacity < 0L) {
            STATE.energyOutCapacity = ou == null ? -1L : ou[1];
        }
    }

    private interface IntSupplier {
        int get();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  供能口径探测（2026-10-25 第二十六改 · 用户 bug「总耗能这里没显示」）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * <b>这台主机的能源仓是"记账的"还是"无限供电的"</b> —— 决定「总耗能功率」能不能写数字。
     *
     * <h2>为什么需要它（用户 2026-10-25 的反应，逐字）</h2>
     * 「<b>是不是我用的是创造能源仓的缘故？</b>」—— 对，正是：服务端 {@code [SHANHAI-HOSTSNAP]}
     * 亲口算出 {@code energyIn=0 EU/t}，而同一帧 {@code modules=5}、总产出 14.18M 件 ⇒ 模块在跑、
     * 仓侧却读到 0。<b>字节码实证</b>（{@code javap -p}，见 temp\ar-tick\报告.md §1）：
     * <pre>
     *   org.gtlcore.gtlcore.integration.gtmt.InfinityEnergyContainer      ← 创造模式能源仓的容器
     *     extends NotifiableEnergyContainer implements IInt128EnergyContainer
     *     只有 acceptEnergyFromNetwork / getEnergyStored
     *     【没有】getInputPerSec() ⇒ 基类那个"20 tick 窗口累加"的字段永远不被喂 ⇒ **恒 0**
     *   com.gtladd.gtladditions...NetworkEnergyContainer                  ← 无线电网输入终端
     *     有 getInputPerSec()/getOutputPerSec()（走 Int128 那套）⇒ **它是记账的**
     * </pre>
     *
     * <h2>判据（三级，前一级认出来就锁存）</h2>
     * <ol>
     *   <li><b>类名判型</b>：复用 {@code ParallelPowerBudget.knownKindByName} 那张<b>唯一</b>的
     *       类名表（沿超类链走，见 {@code EnergyHatchPower.classify}）—— 不在这里另抄一份类名；</li>
     *   <li><b>容量形态兜底</b>：{@code getEnergyCapacity() == Long.MAX_VALUE} ⇒ 当无限供电
     *       （上游换类名时退化成"仍然正确"，与 {@code EnergyHatchPower.classify} 同一条兜底）；</li>
     *   <li>都认不出来 ⇒ {@link HostFxGlassArModel#ENERGY_KIND_UNKNOWN}（面板照常写仓侧实测数字，
     *       若数字恒 0 而模块在烧电，{@code energyUsedIsBlind} 再把语义升级成 {@code ∞（无限供电）}）。</li>
     * </ol>
     *
     * <p>🔴 <b>只在还没认出来时探测</b>（{@code UNKNOWN}）：认出一次就锁存，免得每帧都走一遍代理。
     * 拆仓/换仓时 {@link #reset()} 会把它重新置回 UNKNOWN。
     */
    private static void detectEnergyInKind(GuixuVisionCoreMachine host) {
        if (STATE.energyInKind != HostFxGlassArModel.ENERGY_KIND_UNKNOWN) {
            return;                                   // 已锁存
        }
        int kind = HostFxGlassArModel.ENERGY_KIND_UNKNOWN;
        try {
            final List<IRecipeHandler<?>> handlers =
                    host.getCapabilitiesProxy().get(IO.IN, EURecipeCapability.CAP);
            if (handlers != null) {
                for (IRecipeHandler<?> h : handlers) {
                    if (!(h instanceof IEnergyContainer container)) {
                        continue;
                    }
                    final int k = kindOf(container);
                    if (k != HostFxGlassArModel.ENERGY_KIND_NORMAL) {
                        kind = k;                      // 认出一个"无限供电"就够定调了
                        break;
                    }
                    kind = HostFxGlassArModel.ENERGY_KIND_NORMAL;
                }
            }
        } catch (Throwable t) {
            kind = HostFxGlassArModel.ENERGY_KIND_UNKNOWN;   // 代理读不到 ⇒ 不猜
        }
        if (kind != HostFxGlassArModel.ENERGY_KIND_UNKNOWN) {
            STATE.energyInKind = kind;
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTSNAP] client 供能口径={} ⇒ 总耗能功率写 {}"
                            + "（创造仓 / 终端没有「每秒吞吐」这个量，仓侧恒 0 不是 bug）",
                    energyKindName(kind),
                    kind == HostFxGlassArModel.ENERGY_KIND_NORMAL ? "仓侧实测 EU/t" : "∞");
        }
    }

    /**
     * 单个能源仓容器 → 供能口径（{@link HostFxGlassArModel#ENERGY_KIND_CREATIVE} /
     * {@link HostFxGlassArModel#ENERGY_KIND_NET_TERMINAL} / {@link HostFxGlassArModel#ENERGY_KIND_NORMAL}）。
     *
     * <p>类名表<b>复用</b> {@code ParallelPowerBudget.knownKindByName}（唯一一份）；
     * 认不出来再按容量形态兜底。与 {@code EnergyHatchPower.classify} 同一判据，只是那边返回
     * 功率预算用的 {@code HatchKind}，这里返回面板用的整数口径（客户端不必拉进整个预算类）。
     */
    private static int kindOf(IEnergyContainer container) {
        for (Class<?> k = container.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            final ParallelPowerBudget.HatchKind known = ParallelPowerBudget.knownKindByName(k.getName());
            if (known == ParallelPowerBudget.HatchKind.CREATIVE) {
                return HostFxGlassArModel.ENERGY_KIND_CREATIVE;
            }
            if (known == ParallelPowerBudget.HatchKind.NET_TERMINAL) {
                return HostFxGlassArModel.ENERGY_KIND_NET_TERMINAL;
            }
            if (known != null) {
                return HostFxGlassArModel.ENERGY_KIND_NORMAL;
            }
        }
        try {
            if (container.getEnergyCapacity() == Long.MAX_VALUE) {
                return HostFxGlassArModel.ENERGY_KIND_CREATIVE;
            }
        } catch (Throwable ignored) {
            // 容量也读不到 ⇒ 交给上层写 UNKNOWN（不猜）
        }
        return HostFxGlassArModel.ENERGY_KIND_NORMAL;
    }

    /** 口径 → 日志里的中文名。 */
    static String energyKindName(int kind) {
        switch (kind) {
            case HostFxGlassArModel.ENERGY_KIND_CREATIVE:
                return "创造能源仓";
            case HostFxGlassArModel.ENERGY_KIND_NET_TERMINAL:
                return "无线电网终端";
            case HostFxGlassArModel.ENERGY_KIND_NORMAL:
                return "普通仓";
            default:
                return "未探测";
        }
    }

    /**
     * 🔴 2026-10-25 第二十五改：<b>客户端快照探针</b>（每 20 tick 一行，<b>只打前 5 行</b>）。
     *
     * <h2>它回答哪一个问题</h2>
     * 「客户端到底有没有收到这些字段」——不看渲染、不看面板，直接看机器上的同步镜像：
     * <pre>
     *   seq=0        且 服务端 seq 在涨   ⇒ 断在【同步链路】（@DescSynced 没写/没发/字段没登记）
     *   seq>0        但 AR 仍 --          ⇒ 断在【取数/映射】（函数没跑 / 走了 throw 分支）
     *   installed=-1 但 seq>0            ⇒ 服务端没填"已装模块"（那一路服务端自己的问题）
     * </pre>
     *
     * <p>为什么放在渲染里而不是注册一个 tick 事件：本类的读数本来就只在渲染侧用、
     * 也只在"玩家正看着这台主机"时有意义；多注册一个全局 tick 钩子 = 凭白多一条会在
     * 别处被调用的路径（本工程踩过"多条路径口径不一致"的坑）。{@code gameTime / 20} 就是服务端 tick。
     *
     * <p>只打 5 行：探针是"进游戏 5 秒内就能判死"的一次性判据，不是长期监控。
     */
    private static void probeRuntimeSnapshot(GuixuVisionCoreMachine host) {
        if (probeLines >= 5) {
            return;
        }
        try {
            final Level level = host.getLevel();
            if (level == null) {
                return;
            }
            final long tick = level.getGameTime();
            final long bucket = tick / 20L;
            if (bucket == probeBucket) {
                return;                                 // 同一个 20 tick 桶里已经打过
            }
            probeBucket = bucket;
            probeLines++;
            final long mask = safeLong(host::hostModuleMask);
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTSNAP] client probe #{} tick={} seq={} installed={} mask={}"
                            + "（{} 位） progress0={} | 读路径已调={}次 重建={}次（每tick一次，tick={}，"
                            + "同tick跳过={}帧） 供能口径={} 模块耗电={}",
                    probeLines, tick, safeInt(host::hostSnapSeq), safeInt(host::hostInstalledModules),
                    mask, Long.bitCount(mask), firstProgress(host),
                    snapReadCalls, rebuildCalls, lastBuildTick == NEVER ? "NA" : lastBuildTick, tickSkips,
                    energyKindName(STATE.energyInKind),
                    HostFxGlassArModel.powerText(HostFxGlassArModel.moduleEuSum(STATE)));
        } catch (Throwable t) {
            // 诊断日志绝不允许把渲染带下水（本类是 TESR 调用链上的一环）
            logOnce("probe_throw", "[SHANHAI-HOSTSNAP] client probe threw {}: {}",
                    t.getClass().getName(), String.valueOf(t.getMessage()));
        }
    }

    /** 探针用：第 0 个扫描位的模块进度（{@code byte[]}；读不到 ⇒ {@code "无"}）。 */
    private static String firstProgress(GuixuVisionCoreMachine host) {
        try {
            final byte[] p = host.hostModProgressPct();
            return p == null || p.length == 0 ? "无" : (p[0] + "%");
        } catch (Throwable t) {
            return "读异常";
        }
    }

    /**
     * 当前<b>游戏 tick</b>（读数重建的唯一节拍源）—— {@code level.getGameTime()}。
     *
     * <p>🔴 2026-10-25 第二十七改：<b>不要换成 {@code System.currentTimeMillis()}</b>。
     * 毫秒不是 tick：它会在暂停/卡顿时继续走（读数白重建），也会因为一毫秒内的多次调用
     * 把"同一 tick"判成"新 tick"；而 {@code gameTime} 恰好由客户端 tick 驱动，20 次/秒。
     *
     * <p>为什么优先取机器所在的 {@code level}：与本类 {@code probeRuntimeSnapshot} 用的是
     * <b>同一个</b> tick 源（它用 {@code level.getGameTime()/20} 分桶），日志里两个 {@code tick=}
     * 才能直接对账；机器没 level 时再退回 {@code Minecraft.getInstance().level}。
     *
     * @return tick；读不到 ⇒ {@link #NEVER}（调用方据此退化成"每帧重建"，绝不冻结面板）
     */
    private static long gameTick(GuixuVisionCoreMachine host) {
        try {
            Level level = host == null ? null : host.getLevel();
            if (level == null) {
                final Minecraft mc = Minecraft.getInstance();
                level = mc == null ? null : mc.level;
            }
            return level == null ? NEVER : level.getGameTime();
        } catch (Throwable t) {
            return NEVER;
        }
    }

    /**
     * 读「已装模块」的<b>名字</b>（每 tick，与其余读数同一个节奏）。
     *
     * <h2>取数链路（🔴 服务端权威值在前，客户端只做"按权威槽位查名字"这一件事）</h2>
     * <pre>
     *   服务端 GuixuVisionCoreMachine#refreshModuleMask()
     *      → 逐槽位 MetaMachine.getMachine(level, pos) → 已成型且 getHost()==this ⇒ 置位
     *      → @DescSynced long hostModuleMask  ──►  客户端读 host.hostModuleMask()
     *   客户端：对每个置位的槽位 i，去【自己这一侧的世界】取第 i 个扫描位上的机器，
     *          用它的 getDefinition().getItem() 名字填进 State.moduleNames
     * </pre>
     * <b>为什么名字不在服务端一起同步过去</b>：LDLib 的 syncdata 只登记基本类型与基本类型数组
     * （{@code com/lowdragmc/lowdraglib/syncdata/managed/} 全表 javap 过，<b>没有 String</b>）⇒
     * 同步 String 是"编译过、运行期不生效"的高危写法。名字来自两侧<b>同一个注册表</b>，
     * 按权威槽位号查不构成"猜"；查不到（区块没加载 / 机器不是模块）就写 {@code 槽#i}，
     * <b>绝不编一个名字</b>。
     *
     * <p>位图还是 0（刚读档、服务端还没扫）时整体标 {@code moduleNamesUnavailable}，
     * 面板显示"名字暂不可读"，而不是"0 个模块"。
     */
    private static void readModuleNames(GuixuVisionCoreMachine host) {
        final long mask;
        try {
            mask = host.hostModuleMask();
        } catch (Throwable t) {
            STATE.moduleNames = new String[0];
            STATE.moduleSlots = null;
            STATE.moduleNamesUnavailable = true;
            STATE.moduleNamesHidden = 0;
            return;
        }
        if (mask == 0L) {
            STATE.moduleNames = new String[0];
            STATE.moduleSlots = null;
            STATE.moduleNamesUnavailable = true;
            STATE.moduleNamesHidden = 0;
            lastNameMask = 0L;
            namesResolved = false;          // 0 位 = 还没同步好 ⇒ 下一帧继续问
            return;
        }
        // 🔴 2026-10-25 第二十六改（每帧重建的成本控制）：位图没变 ⇒ **名字不用重查世界**。
        // 这一步是整个重建里最贵的一段（每个已装模块一次 `MetaMachine.getMachine` + 物品名），
        // 每 tick 一次时无所谓（20 次/秒），但按帧做就是每帧 5~24 次世界查询。位图是"装了哪些模块"的唯一权威信号。
        if (mask == lastNameMask && namesResolved) {
            return;
        }
        final BlockPos[] positions = host.getModuleScanPositions();
        final Level level = host.getLevel();
        final List<String> names = new ArrayList<>(Long.bitCount(mask));
        final List<Integer> slots = new ArrayList<>(Long.bitCount(mask));
        for (int i = 0; i < positions.length && i < Long.SIZE; i++) {
            if ((mask & (1L << i)) == 0L) {
                continue;
            }
            names.add(moduleNameAt(level, positions[i], i));
            slots.add(i);
        }
        // 2026-10-25 第二十二改：不再截到 6 个 —— ⑦ 模块列表要能画到 HostFxGlassArPanel.MODLIST_ROWS 行，
        // 名字与槽位【一一对应】地全给（模型侧自己按行数截断并补「…还有 N 个」）。
        STATE.moduleNames = names.toArray(new String[0]);
        STATE.moduleSlots = slots.stream().mapToInt(Integer::intValue).toArray();
        STATE.moduleNamesHidden = 0;
        STATE.moduleNamesUnavailable = false;
        lastNameMask = mask;
        namesResolved = true;
    }

    /**
     * 读 AR 右侧信息栏那份<b>运行快照</b>（每 tick，与其余读数同一个节奏）。
     *
     * <h2>取数链路</h2>
     * <pre>
     *   服务端 GuixuVisionCoreMachine#refreshHostRuntimeSnapshot()
     *      → 一组 @DescSynced 基本类型 / 基本类型数组（hostMod* / hostRecipe* / hostTotal*）
     *      → 客户端读镜像；{@code hostSnapSeq() == 0} ⇒ 一次都没快照过
     * </pre>
     *
     * <p>🔴 <b>拿不到就填 {@code null} / {@code -1}</b>（面板画 {@code --}）：
     * 服务端还没算出来（{@code seq == 0}）、读字段抛异常、数组长度不对 —— 全都走"未知"这一条，
     * <b>绝不把 0 当成读数</b>（本工程红线：宁可缺，不可假）。
     */
    private static void readRuntimeSnapshot(GuixuVisionCoreMachine host) {
        final int seq = safeInt(host::hostSnapSeq);
        snapReadCalls++;
        // 🔴 2026-10-25 第二十五改：进入这一行的意义 = **客户端读路径真的被执行了**。
        //   上一轮的现象是"这条日志一行都没有" ⇒ 说明连函数体都没进（根因见 rebuildReadingsIfStale 的注释）。
        //   这一行打印的值同时回答：闸门看到的是多少（seqRaw）、闸门会不会放行（seqGate）、
        //   同一时刻位图/成型位有没有到（mask/formed）—— 三者一对就能判"是没同步还是没取数"。
        if (snapReadCalls <= 3) {
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTSNAP] client read enter #{} seqRaw={} seqGate={} mask={} formed={}",
                    snapReadCalls, seq, seq > 0 ? "pass" : "block(seq<=0)",
                    safeLong(host::hostModuleMask), safeBool(host::isHostFormedSync));
        }
        if (seq <= 0) {
            // 🔴 2026-10-25 第二十四改：这是"整片 -- 到底断在哪一环"的**客户端判据**。
            //   服务端日志里有 `[SHANHAI-HOSTSNAP] seq=N`（N 在涨）而这里还是 0
            //   ⇒ 断在【同步链路】（@DescSynced 没到客户端），不是服务端没算。
            //   两边都是 0 ⇒ 断在【服务端根本没跑 refreshHostRuntimeSnapshot】。
            // 2026-10-25 第二十五改：**分两级**——首帧必然是 0（同步还没到，不是故障），
            //   连打 50 次（≈5 s）还是 0 才升级成 WARN，免得真问题被"首帧的假警报"稀释。
            if (snapReadCalls == 1) {
                logOnce("snapseq0_first", "[SHANHAI-HOSTSNAP] client read skip(branch=seq<=0) 首次读到 seq=0"
                                + "（首帧正常：服务端快照还没到 ⇒ AR 先按未知画 --）。读路径本身是通的。");
            } else if (snapReadCalls >= SNAP0_WARN_AFTER) {
                logOnce("snapseq0", "[SHANHAI-HOSTSNAP] client read skip(branch=seq<=0) 已读 {} 次仍是 seq=0"
                                + " —— 服务端快照一次都没到客户端（mask={} formed={} 本机侧模块数={}）："
                                + "AR 面板整片 --，断点在【同步链路】或【服务端 tick】，不是取数函数",
                        snapReadCalls, safeLong(host::hostModuleMask), safeBool(host::isHostFormedSync),
                        safeInt(host::getFormedModuleCount));
            }
            unknownSnapshot();
            return;
        }
        try {
            STATE.modProgressPct = host.hostModProgressPct();
            STATE.modEuPerTick = host.hostModEuPerTick();
            STATE.modParallel = host.hostModParallel();
            STATE.modThreads = host.hostModThreads();
            STATE.modRemainSeconds = host.hostModRemainSec();
            STATE.hostProgressPct = host.hostRecipeProgressPct();
            STATE.hostRemainSeconds = host.hostRecipeRemainSec();
            STATE.hostParallel = host.hostRecipeParallel();
            STATE.hostThreads = host.hostRecipeThreads();
            STATE.hostEuPerTick = host.hostEuPerTick();
            STATE.totalOutputCount = host.hostTotalOutputCount();
            STATE.totalOutputKinds = host.hostTotalOutputKinds();
            // 2026-10-25 第二十四改：这两个是【功率】(EU/t，20 tick 滑动窗口)，不是累计 EU
            STATE.totalEuUsedPerTick = host.hostTotalEuUsedPerTick();
            STATE.totalEuGenPerTick = host.hostTotalEuGenPerTick();
            STATE.installedModules = host.hostInstalledModules();
            // 2026-10-25 第二十三改：能源仓 / 动力仓（与 addDisplayText 那两行同一来源）
            STATE.energyInStored = host.hostEnergyInStored();
            STATE.energyInCapacity = host.hostEnergyInCapacity();
            STATE.energyOutStored = host.hostEnergyOutStored();
            STATE.energyOutCapacity = host.hostEnergyOutCapacity();
        } catch (Throwable t) {
            // 🔴 2026-10-25 第二十五改：以前这里是**静默**吞掉（只置 --）。
            //   这正是"编译过、运行期整片 --、日志里一句话都没有"的典型形态 ⇒ 至少留一行带分支名的证据。
            //   走 WARN（真问题）＋ 只打一次（不刷屏）；异常类名/消息足够定位是哪一字段炸的。
            logOnce("snapread_throw", "[SHANHAI-HOSTSNAP] client read skip(branch=throw) {}: {} ⇒ AR 整片 --",
                    t.getClass().getName(), String.valueOf(t.getMessage()));
            unknownSnapshot();
            return;
        }
        // 🔴 2026-10-25 第二十四改：快照到过就打一行（一次）—— 判据：
        //   这一行出现 = 服务端→客户端整条链路通；不出现 = 断在前面（看服务端那行）。
        logOnce("snapseq", "[SHANHAI-HOSTSNAP] client seq={} 到手：installed={} 总耗能功率={} EU/t"
                        + " 总发电功率={} EU/t 进度={}%",
                seq, STATE.installedModules, STATE.totalEuUsedPerTick, STATE.totalEuGenPerTick,
                STATE.hostProgressPct);
        // 输出：服务端同步的是**物品注册表数值 id**（LDLib 的 syncdata 没有可用的 String 通道），
        // 客户端按 id 反查自己这一侧同一个注册表 ⇒ 名字。查不到（id<0 / byId 为 null / air）
        // ⇒ 那个条目就是"未知"，不编名字、不写 0。
        STATE.hostOutputTotal = safeInt(host::hostOutKinds);
        // 🔴 2026-10-25 第二十六改（"每帧重建"的成本控制）：输出的 `名字×数量` 要查物品注册表 +
        // 拼字符串 + 紧凑量级，模块侧是 24 槽 × 2 条 = 48 次。每 tick 一次时无所谓，按帧就贵了。
        // ⇒ **内容没变就复用上一帧的数组**（按 id/amount 逐元素比，不做时间窗口节流）。
        final int[] hostIds = host.hostOutItem();
        final int[] hostAmts = host.hostOutAmount();
        if (hostIds != null && hostAmts != null
                && java.util.Arrays.equals(hostIds, lastHostOutIds)
                && java.util.Arrays.equals(hostAmts, lastHostOutAmts)) {
            STATE.hostOutputs = lastHostOutTexts;
        } else {
            STATE.hostOutputs = resolveHostOutputs(hostIds, hostAmts);
            lastHostOutIds = hostIds;
            lastHostOutAmts = hostAmts;
            lastHostOutTexts = STATE.hostOutputs;
        }
        final int[] modIds = host.hostModOutItem();
        final int[] modAmts = host.hostModOutAmount();
        if (modIds != null && modAmts != null
                && java.util.Arrays.equals(modIds, lastModOutIds)
                && java.util.Arrays.equals(modAmts, lastModOutAmts)) {
            STATE.modOutputs = lastModOutTexts;
        } else {
            STATE.modOutputs = resolveModuleOutputs(modIds, modAmts);
            lastModOutIds = modIds;
            lastModOutAmts = modAmts;
            lastModOutTexts = STATE.modOutputs;
        }
        // 同上：名字按权威槽位去世界里查机器（每个模块一次）—— 这段自带"位图没变就不重查"的缓存，
        // 见 readModuleNames 里第二十六改的那段注释。
        readModuleNames(host);
    }

    /** 主机级输出：注册表 id × 量 → {@code 名字×数量}；一个都解析不出来 ⇒ 空数组（面板画 {@code --}）。 */
    private static String[] resolveHostOutputs(int[] ids, int[] amounts) {
        if (ids == null || amounts == null) {
            return new String[0];
        }
        final List<String> out = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length && i < amounts.length; i++) {
            final String s = outputEntry(ids[i], amounts[i]);
            if (s != null) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    /** 每模块输出：扁平数组（下标 = 槽位 × {@code OUT_PER_MODULE} + 第几条，-1 = 没有）。 */
    private static String[][] resolveModuleOutputs(int[] ids, int[] amounts) {
        final int slots = GuixuVisionCoreMachine.SNAP_SLOTS;
        final int per = GuixuVisionCoreMachine.OUT_PER_MODULE;
        if (ids == null || amounts == null || ids.length < slots * per) {
            return null;                                       // ⇒ 面板画 --
        }
        final String[][] out = new String[slots][];
        for (int s = 0; s < slots; s++) {
            final List<String> one = new ArrayList<>(per);
            for (int k = 0; k < per; k++) {
                final int idx = s * per + k;
                final String e = outputEntry(ids[idx], amounts[idx]);
                if (e != null) {
                    one.add(e);
                }
            }
            out[s] = one.toArray(new String[0]);
        }
        return out;
    }

    /**
     * 一条输出 → {@code 名字×数量}；{@code id < 0} / 数量 {@code <= 0} / 反查不到 ⇒ {@code null}。
     *
     * <p>反查用的是 {@code BuiltInRegistries.ITEM.byId}：**同一个注册表**在两侧的数值 id 是同一份
     * （原版发物品时写的就是这个 id，见 {@code FriendlyByteBuf} 的 ItemStack 编解码），
     * 所以这不是"猜"，与 {@code hostModuleMask} 按权威槽位查名字是同一条纪律。
     */
    private static String outputEntry(int id, int amount) {
        if (id < 0 || amount <= 0) {
            return null;
        }
        try {
            final net.minecraft.world.item.Item item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(id);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return null;
            }
            return new ItemStack(item).getHoverName().getString() + "×"
                    + HostFxGlassArModel.compactMagnitude(amount);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把快照相关的 State 字段全部置成"未知"（面板画 {@code --}）。 */
    private static void unknownSnapshot() {
        STATE.modProgressPct = null;
        STATE.modEuPerTick = null;
        STATE.modParallel = null;
        STATE.modThreads = null;
        STATE.modRemainSeconds = null;
        STATE.modOutputs = null;
        STATE.hostProgressPct = -1;
        STATE.hostRemainSeconds = -1;
        STATE.hostParallel = -1;
        STATE.hostThreads = -1;
        STATE.hostEuPerTick = -1L;
        STATE.hostOutputs = new String[0];
        STATE.hostOutputTotal = -1;
        STATE.totalOutputCount = -1L;
        STATE.totalOutputKinds = -1;
        STATE.totalEuUsedPerTick = -1L;
        STATE.totalEuGenPerTick = -1L;
        STATE.installedModules = -1;
        STATE.energyInStored = -1L;
        STATE.energyInCapacity = -1L;
        STATE.energyOutStored = -1L;
        STATE.energyOutCapacity = -1L;
    }

    /** 第 {@code slot} 个扫描位上的模块名；读不到 ⇒ {@code 槽#slot}（不编名字）。 */
    private static String moduleNameAt(Level level, BlockPos pos, int slot) {
        if (level == null || pos == null) {
            return "槽#" + slot;
        }
        try {
            final MetaMachine machine = MetaMachine.getMachine(level, pos);
            if (machine == null) {
                return "槽#" + slot;
            }
            return new ItemStack(machine.getDefinition().getItem()).getHoverName().getString();
        } catch (Throwable t) {
            return "槽#" + slot;
        }
    }

    /** 读一个 int；抛异常 ⇒ −1（显示 {@code --}），绝不编数。 */
    private static int safeInt(IntSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 读一个 long；抛异常 ⇒ −1（显示 {@code --}）。 */
    private static long safeLong(LongSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 读一个 boolean；抛异常 ⇒ {@code false}（只用于诊断日志，不参与显示）。 */
    private static boolean safeBool(BoolSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 与 {@link IntSupplier} 同构（long 版，只为诊断日志；避免引 {@code java.util.function} 的歧义 import）。 */
    private interface LongSupplier {
        long get();
    }

    /** 与 {@link IntSupplier} 同构（boolean 版）。 */
    private interface BoolSupplier {
        boolean get();
    }

    /**
     * 某一侧的 {@code (存量, 容量)} —— <b>与 {@code GuixuVisionCoreMachine#energyTotals} 同一读法</b>
     * （那边是 private，这里逐行复刻；改口径要两处一起改）。
     *
     * <p>存量在客户端恒 0（{@code @Persisted} 无 {@code @DescSynced}）⇒ 本面板<b>只显示容量</b>，
     * 见类注释 §读数的口径。
     */
    private static long[] energyTotals(GuixuVisionCoreMachine host, IO io) {
        long stored = 0L;
        long capacity = 0L;
        try {
            final List<IRecipeHandler<?>> handlers =
                    host.getCapabilitiesProxy().get(io, EURecipeCapability.CAP);
            if (handlers != null) {
                for (IRecipeHandler<?> handler : handlers) {
                    if (handler instanceof IEnergyContainer container) {
                        stored += container.getEnergyStored();
                        capacity += container.getEnergyCapacity();
                    }
                }
            }
            return new long[]{stored, capacity};
        } catch (Throwable t) {
            return null;                                   // ⇒ 显示 "--"
        }
    }

    /** 上一帧的图元数（日志/判据；0 = 没画）。 */
    public static int prims() {
        return lastPrims;
    }

    /** 上一帧提交的四边形数（日志/判据）。 */
    public static int quads() {
        return lastQuads;
    }

    /** 上一帧的模型（离线/调试用；正常游戏里没人读）。 */
    public static HostFxGlassArModel.Model lastModel() {
        return lastModel;
    }

    /** ① 的 16 行读数（调试/报告用）。 */
    public static List<String> readLines() {
        final List<String> out = new ArrayList<>();
        for (HostFxGlassArModel.Txt t : HostFxGlassArModel.build(STATE).textsOf(HostFxGlassArModel.Z_READ)) {
            out.add(t.text);
        }
        return out;
    }
}
