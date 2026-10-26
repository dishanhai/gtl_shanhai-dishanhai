package com.shanhai.client.render.hostfx;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shanhai.ShanhaiMod;
import com.shanhai.client.config.ShanhaiClientConfig;
import com.shanhai.client.render.blackhole.BlackHoleTuning;
import com.shanhai.machine.host.GuixuVisionCoreMachine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 归墟视界枢核 · <b>动态特效渲染器</b>（TESR）。
 *
 * <h2>画什么（全部是渲染，方块不变——真方块由 {@link HostFxHide} 临时藏成 AIR）</h2>
 * <ol>
 *   <li><b>内环带</b> —— 按用户口径改成"<b>真方块跟着转</b>"（照伪神之锻炉那三个环）：
 *       把该环的真方块藏起来，同一份台账烘成静态顶点缓冲（{@link HostFxBaked}），
 *       每帧绕环心转 {@link HostFxGeometry#RING_DEG_PER_SEC}°/秒 重画一遍；</li>
 *   <li><b>上下 8 艘磁悬浮飞船</b> —— 同样是"真方块跟着转"，但<b>上 4 艘 / 下 4 艘各自一份烘焙、
 *       各自一个角速度</b>（{@link HostFxGeometry#SHIP_UP_DEG_PER_SEC} / {@link HostFxGeometry#SHIP_DOWN_DEG_PER_SEC}，
 *       默认 +9 / −9 °/s，与内环 12°/s <b>故意不同</b>）⇒ 三组互不相等（用户 2026-10-25 要求 ①）；</li>
 *   <li><b>4 座物质解压机</b> —— 4 条辐条那批格已藏成 AIR 并从环缓冲里摘掉，由
 *       {@link HostFxDraw#drawMatterDecompressors} 画 {@link HostFxGeometry#mdBoxCount()} 件/座
 *       （≥160 件、单件 ≤1 格、横置朝黑洞）；与内环同枢轴同 spin；</li>
 *   <li><b>黑洞</b> —— 主路径不在本类：把中心喂给既有 {@link BlackHoleTuning#setCenter}，由
 *       {@code client/render/blackhole} 那一套（吸积盘 + 光子环 + 真透镜）去画。
 *       🔴 但那一套在<b>光影包生效</b>时降级、且二级贴图路径已整条停用 ⇒ 会<b>一帧都不画</b>，
 *       所以本类补一个<b>安全兜底</b>（{@link HostFxBlackHole}：纯顶点黑盘/光子环/吸积盘，零贴图零裸 GL），
 *       只在"真路径这一帧没画"时顶上（两条路互斥，绝不同时出现两个黑洞）；</li>
 *   <li><b>4 条解压机主喷口光柱 + 12 条细紫引导光束 + 中央主光柱</b> —— 🔴 第七改之后：
 *       4 条主喷口光柱（解压机圆孔 → 洞面）= <b>伪神同款</b>（外层 1.25→1.55 格，颜色可配，
 *       见 {@link HostFxPillarBeam#drawMdBeams}）；
 *       {@link HostFxGeometry#THIN_BEAM_COUNT} 条<b>细紫光束</b>（🔴 第七改：<b>每座解压机 3 条</b>，
 *       绕<b>本座喷口轴</b> r=1.90、互成 120°、方向与本座主柱夹角 0° —— 用户：「我说的小光束是
 *       围绕这内环上面 4 个物质解压机的啊……不是围绕上下两条光束的」）；
 *       中央<b>上下 2 根主光柱</b>（第五改起就是伪神那套 1:1）；
 *       ⇒ {@link HostFxDraw#drawBeams}（细紫走自绘加性管壳；6 根主柱走伪神 shader，都不写深度）。</li>
 * </ol>
 *
 * <h2>🔴 2026-10-24 修「一行都不画」（用户实机原话：「主机的特效一点没看见」）</h2>
 * <b>根因 = 距离裁剪</b>。旧实现按「玩家到<b>环心</b>的距离 &gt; 64 格就不画」；
 * 而环心到控制器本身就有 {@code hypot(72,11) ≈ 72.9} 格 ⇒
 * <b>站在控制室/站体上看的时候天然"超距"</b> ⇒ 一帧都没画。
 * 更糟的是那条路<b>没有日志</b>，于是日志 0 条 —— 我据此误判成"render 没被调用"。
 * <p>处置：<b>距离裁剪整条删除</b>（用户明确「走到 65 格以外 特效全停、真方块回来这个功能我不要」）。
 * 现在只剩两道闸门：<b>结构成型</b> ＋ <b>配置总开关</b>。
 *
 * <h2>🔴 每一道 return 都必须留一条节流日志（带 reason）</h2>
 * 现在每道 gate 都会打一行（同 reason 每 {@value #LOG_THROTTLE_MS} ms 最多一条）；
 * 绘制成功也会打一行<b>带计数</b>的 {@code draw} —— 那才是"到底画了没有"的唯一判据。
 *
 * <h2>🔴 2026-10-25 第二十改：开关语义与参数接线（本类的角色是"接线"，判定在别处）</h2>
 * 机器字段 {@code hostFxEnabled=false} ⇒ <b>降级态</b>（{@link HostFxSwitch}）。五条语义各自落在哪：
 * <ol>
 *   <li><b>黑洞仍在</b> —— 本方法对任何黑洞路径都<b>不加 early-return</b>；</li>
 *   <li><b>内环 + 8 艘飞船：静止但仍绘制</b> —— 传给 {@link HostFxRingGroups#render} 与
 *       {@code HostFxDraw.drawBeams} 的自转角一律来自 {@link HostFxSwitch#spinSeconds()}
 *       （= {@code HostFxState.animSeconds()}，关时<b>冻结</b>；不是世界时间、更不是 0）；</li>
 *   <li><b>中央上下两束变红</b> —— {@link HostFxSwitch#apply()} 复用
 *       {@code HostFxGeometry} 的 {@code jetGlowOverride}（红值 {@code (255,64,48)}）；</li>
 *   <li><b>4 条解压机主喷口光柱消失</b> —— {@code HostFxDraw} 里按
 *       {@link HostFxSwitch#mdMainBeamsOn()} 整段跳过（不是"透明色"）；</li>
 *   <li><b>12 条细紫小束仍在</b> —— 那段代码一个字没动。</li>
 * </ol>
 * 参数接线：角速度只读 {@code HostFxGeometry} 的 getter（改由机器面板喂）；
 * 黑洞半径 / 引力透镜走 {@link BlackHoleTuning} 的"主机接管"两个覆盖，
 * <b>只覆盖自己设过的</b>（主机不在 / 不成型 / 总闸关 ⇒ 还原，命令设的值不会被永久顶掉）。
 */
public class GuixuHostFxRenderer extends WorkableCasingMachineRenderer {

    /** 同一个 reason 的日志节流（毫秒）。5 秒 —— 足够定位，又不刷屏。 */
    public static final long LOG_THROTTLE_MS = 5_000L;

    /**
     * 🔴 <b>类加载探针</b>（2026-10-24 第二次排查新增）。
     *
     * <p>客户端日志里连 {@code gate=} 都没有 ⇒ 说明 {@code render(...)} 一次都没被调用。这一行用来区分两种可能：
     * <ul>
     *   <li>本类<b>被 new 出来过</b>（这行会出现，且 {@code ber_probe} 会跟着出现）⇒ 是 BER 没登记；</li>
     *   <li>本类<b>从没被加载</b>（这行都不出现）⇒ 供应商没被调用（GT 在 {@code LDLib.isClient()} 门禁上就没进来）。</li>
     * </ul>
     * JVM 语义保证它只在第一次加载时打一次，不是每帧。
     */
    static {
        ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] class_loaded renderer=GuixuHostFxRenderer（本类第一次被加载）");
        // 🔴 2026-10-25 第十九改：把 HostFxState 的 WARN 出口接到本 mod 的日志上。
        //    为什么不在 HostFxState 里直接用 ShanhaiMod.LOGGER：那个类必须保持【零 MC 依赖】
        //    （可以脱机 javac 自检），见 HostFxState 的类注释。
        HostFxState.setWarner(ShanhaiMod.LOGGER::warn);
    }

    /** 一次性探针标记（各只打一条，避免刷屏）。 */
    private static boolean probeHasTesrLogged;
    private static boolean probeGlobalLogged;
    private static boolean probeRenderEnteredLogged;

    /** 上一次喂给黑洞的中心（用来判断"该不该清掉"，避免覆盖玩家用命令设的中心）。 */
    private static double[] lastCenter;

    /**
     * 上一次喂给黑洞渲染件的<b>主机接管半径</b>（格；{@code null} = 还没喂过）。
     *
     * <p>与 {@link #lastCenter} 同一套簿记：只清"还是我们设的那个"，
     * 绝不把 {@code /shanhai bh scale} 设进 {@code BlackHoleTuning} 的值永久顶掉。
     */
    private static Float lastHoleRadiusBlocks;

    /** 上一次喂给黑洞渲染件的<b>主机接管透镜开关</b>（{@code null} = 还没喂过）。 */
    private static Boolean lastLensEnabled;

    /** 上一帧所在的 Level（换维度/离开世界时复位台账与降级级别，不依赖任何外部事件类）。 */
    private static Level lastLevel;

    /** 节流表：reason → 上次打印的毫秒。 */
    private static final java.util.Map<String, Long> LAST_LOG_MS = new java.util.HashMap<>();
    /** 上一次打 draw 行的毫秒。 */
    private static Long lastDrawMs;

    private static void logGate(String reason, String detail) {
        final long now = System.currentTimeMillis();
        final Long last = LAST_LOG_MS.get(reason);
        if (last != null && now - last < LOG_THROTTLE_MS) {
            return;
        }
        LAST_LOG_MS.put(reason, now);
        ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] gate={} {}", reason, detail);
    }

    private static void logDraw(boolean ships, boolean formedByController, boolean formedBySync,
                                String hostFxLine, String auditLine) {
        final long now = System.currentTimeMillis();
        if (lastDrawMs != null && now - lastDrawMs < LOG_THROTTLE_MS) {
            return;
        }
        lastDrawMs = now;
        ShanhaiMod.LOGGER.info(
                "[SHANHAI-HOSTFX] draw ships={} formed(ctrl)={} formed(sync)={} | 视界 {} | 喂料 {} | 时钟 {}s"
                        + " | {} | {} | {} | {} | 审计 {} | {}",
                ships, formedByController, formedBySync, hostFxLine, feedLine(),
                String.format(java.util.Locale.ROOT, "%.2f", HostFxState.animSeconds()),
                HostFxRingGroups.summary(),
                HostFxDraw.stats(), HostFxGlassArText.stats(), HostFxGeometry.budget(), auditLine,
                // 🔴 2026-10-25 第二十改：开关语义（fx / degraded / lens / holeR / ring / ships）
                HostFxSwitch.statusFragment());
    }

    /**
     * <b>此刻 {@link HostFxState} 里生效的值</b>（= 渲染侧真正读到的），与机器字段并列打在同一行。
     *
     * <p>判据（用户进游戏唯一需要的 grep）：两段数必须<b>逐位相同</b>。
     * 若"视界"那段变了而"喂料"这段没变 ⇒ 喂料链断了（这正是这条日志要抓的东西，
     * 只打机器字段是抓不到的 —— 机器字段本来就一定是对的）。
     */
    private static String feedLine() {
        return "fx=" + HostFxState.fxEnabled()
                + " 环=" + HostFxState.ringDegPerSec()
                + " 上舰=" + HostFxState.shipUpDegPerSec()
                + " 下舰=" + HostFxState.shipDownDegPerSec()
                + " 洞=" + String.format(java.util.Locale.ROOT, "%.2f", HostFxState.holeRadiusBlocks()) + "格"
                + " 透镜=" + (HostFxState.lensEnabled() ? "开" : "关");
    }

    public GuixuHostFxRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  「正面贴图放反了」的修法（2026-10-25 第二十二改）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 把工作面 overlay 的<b>正面</b>在渲染时转到 {@code facing} 的另一侧。
     *
     * <h2>为什么是这个开关、以及它到底改了什么</h2>
     * 「正面贴图在哪一面」在 GTCEu 里<b>只有一个输入</b>，链条逐段有字节码实证（报告 §①）：
     * <pre>
     *   MachineRenderer#renderModel 偏移 25-31
     *       MetaMachineBlock#getFrontFacing(BlockState)        ← 只拿 BlockState
     *   偏移 27-28  ↳ state.getValue(getRotationState().property)  ← 就是方块状态的 facing
     *   偏移 100-117 把该值当 renderMachine 的第 4 参往下传
     *   WorkableCasingMachineRenderer#renderMachine 偏移 65-85
     *       WorkableOverlayModel#bakeQuads(side, frontFacing, upwardsFacing, …)
     *   WorkableOverlayModel$OverlayFace#bySide  tableswitch
     *       3(NORTH)→FRONT / 4(SOUTH)→BACK / 5(WEST)→SIDE / 6(EAST)→SIDE
     *   bakeQuads 偏移 58-107  rotationAxis(π − toRadians(frontFacing.toYRot()), 0,1,0)
     *       ⇒ 模型空间 NORTH 转到 frontFacing 那一面
     * </pre>
     * ⇒ 模型空间的 FRONT 面 = NORTH，而那段旋转保证它落到 {@code frontFacing} 上。
     *
     * <h2>🔴 那为什么还要翻 180°</h2>
     * 用户的现场事实（他 F3 逐字读的）：方块在 {@code (1754,101,212)}、{@code facing: east}，
     * 他站在机器<b>西侧</b>，看到<b>朝西那一面</b>带着"正面/屏幕"那种贴图。
     * 而这张 overlay 目录（{@code gtceu:block/multiblock/fusion_reactor}）**只有 front 系列贴图**
     * （{@code overlay_front.png} / {@code _active} / {@code _emissive} 共 9 个文件，<b>没有</b>
     * {@code overlay_back} / {@code overlay_side} / {@code overlay_top}）——
     * ⇒ 他看到的"屏幕"只可能是 <b>FRONT 面片</b>，即 FRONT 实际落在了 facing 的<b>反面</b>。
     * <p>「代码说在 facing 一侧、现场看在反面」两者只能以<b>现场</b>为准（本工程的判据纪律：
     * 运行行为 > 静态推导）。所以这里把传给 overlay 的 frontFacing 取反，
     * 让 FRONT 面片落到 facing 所指的那一侧。
     *
     * <h2>⚠️ 只影响"贴图贴在哪一面"，不动任何几何/图案</h2>
     * 本覆写<b>只</b>改 renderer 里 {@code bakeQuads} 的输入方向：
     * <ul>
     *   <li>方块状态的 {@code facing} 一个字节都不动 ⇒ 结构图案（{@code .bin} 那套
     *       {@code LEFT/DOWN/BACK}）判定不变 ⇒ <b>老存档已搭好的机器照样成型</b>；</li>
     *   <li>机壳本身（{@code gtceu:block/cube/all}，六面同图）由 {@code renderModel} 用<b>原</b>
     *       facing 烘焙，本覆写碰不到它 ⇒ 外壳贴图不会跟着转；</li>
     *   <li>本机自己的特效（环 / 飞船 / 黑洞 / 光束 / 玻璃 AR）走 {@code render(BlockEntity,…)}
     *       里各自的 {@code facing}，与这里无关 ⇒ 特效位置不变。</li>
     * </ul>
     * <h2>怎么回滚</h2>
     * 把这一个常量改成 {@code false} 就回到 GTCEu 原样（一行、只影响渲染），
     * <b>不需要</b>改结构图案、不需要改 {@code .bin}、不需要玩家重搭。
     * <p>⚠️ 但注意：改成 {@code false} 会<b>连世界里一起</b>回到旧行为（正面又跑到 facing 的反面），
     * 它已经不是"只回滚图标"的开关了 —— 图标那条另见 {@link #isItemIconPath}。
     */
    public static final boolean FLIP_OVERLAY_FRONT = true;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  「物品图标要把有动态效果的那一面朝外」（2026-10-25 第二十三改）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 「这一趟 {@code renderMachine(...)} 是不是<b>物品图标</b>那条路」。
     *
     * <h2>为什么必须分开：翻转把图标也一起翻了</h2>
     * 用户原话：「物品图标像我给你发的两张样图一样，把有动态效果的那一面在外面」。
     * 第二十二改的 180° 翻转对<b>两条入口同时生效</b>（它们调的都是 {@code renderMachine}）⇒
     * 世界里对了、图标里反了。
     *
     * <h2>物品图标到底走哪条路（字节码实证，jar = {@code libs/gtceu-1.20.1-1.4.4.jar}）</h2>
     * <pre>
     * ① 入口：LDLib {@code core.mixins.ItemRendererMixin#injectRenderItem}
     *       （对 {@code ItemRenderer#render} 的 HEAD @Inject、cancellable、末尾 cancel）
     *    偏移 1-7   item instanceof IItemRendererProvider
     *    偏移 10-22 IItemRendererProvider.disabled.get() 为 true 就跳过（防重入）
     *    偏移 25-38 item.getRenderer(stack)            → 我们的 GuixuHostFxRenderer
     *    偏移 90-105 renderer.renderItem(...)          → IRenderer#renderItem
     *
     * ② MachineRenderer#renderItem 偏移 0-71（全文就这 70 多条指令）
     *    偏移  8-11 item instanceof MetaMachineItem
     *    偏移 21-28 IItemRendererProvider.disabled.set(TRUE)      ← GT 自己也是用 ThreadLocal 防重入
     *    偏移 31-58 Minecraft.getItemRenderer().render(stack, ctx, leftHand, ps, buf, light, overlay,
     *                                                  new MachineRenderer$1(this, machineItem))
     *    偏移 61-68 disabled.set(FALSE)
     *    ⇒ 它不是"自己画"，而是<b>拿一个包装过的 BakedModel 再走一遍原版 ItemRenderer</b>。
     *
     * ③ MachineRenderer$1#getQuads(BlockState, Direction side, RandomSource) 全文：
     *    new LinkedList();
     *    renderMachine(quads,
     *                  machineItem.getDefinition(),
     *                  null,                      ← 第 3 参 machine 【恒为 null】
     *                  Direction.NORTH,           ← 第 4 参 frontFacing 【写死 NORTH】
     *                  side,                      ← 第 5 参 side
     *                  rand,
     *                  side,                      ← 第 7 参 upwardsFacing = side 本身
     *                  BlockModelRotation.X0_Y0); ← 第 8 参 modelState 【恒等】
     *
     * ④ 对照：方块那条路 MachineRenderer#renderModel(BlockAndTintGetter, BlockPos, BlockState, Direction, RandomSource)
     *    偏移 33-52 machine = (getter==null || pos==null) ? null : MetaMachineBlock.getMachine(getter,pos)
     *    偏移 54-56 <b>if (machine == null) return Collections.emptyList();</b>   ← 🔴 关键
     *    偏移 66-71 modelState = ModelFactory.getRotation(frontFacing)
     *    偏移 73-89 upwardsFacing = ModelFactory.modelFacing(side, frontFacing)
     *    偏移 100-117 renderMachine(quads, def, machine, frontFacing, side, rand, upwardsFacing, modelState)
     * </pre>
     *
     * <h2>🔴 为什么判据取 {@code machine == null}，而不是别的</h2>
     * <ul>
     *   <li><b>它是充要的</b>：方块路径在偏移 54-56 就把 {@code machine == null} 的帧<b>整条
     *       {@code emptyList()} 掉</b>，根本走不到 {@code renderMachine}（javap 偏移 416 就是那句
     *       {@code Collections.emptyList()}）⇒ <b>只要 {@code renderMachine} 收到 null，就只可能是图标路径</b>；</li>
     *   <li><b>它不是 static 开关、不靠调用顺序</b>：判据直接来自<b>入参</b>，同一帧里方块与图标
     *       交替渲染、多线程 chunk bake 同时跑都不会串（这一点是任务书点名禁止的脆弱方案）；</li>
     *   <li><b>{@code modelState == X0_Y0} 不能当判据</b>：{@code ModelFactory.getRotation(Direction)}
     *       的 tableswitch 里有一支<b>就是返回 {@code BlockModelRotation.X0_Y0}</b>（偏移 68）
     *       ⇒ 那个朝向的<b>世界里</b>的方块会被误判成图标；</li>
     *   <li><b>{@code upwardsFacing == side} 不能当判据</b>：{@code ModelFactory.modelFacing(side, frontFacing)}
     *       在 {@code side == frontFacing} 时返回 NORTH、在 {@code frontFacing == NORTH} 时<b>原样返回 side</b>
     *       ⇒ 世界里的方块也会出现 {@code upwardsFacing == side}；</li>
     *   <li><b>{@code frontFacing == NORTH} 不能当判据</b>：朝北摆的机器世界里就是 NORTH。</li>
     * </ul>
     *
     * <h2>为什么不翻 = 用户要的效果（图标"动态面朝外"）</h2>
     * 图标这条路的模型变换不是我们的，是 GT 给的
     * {@code ItemBakedModel#getTransforms()} → {@code ModelFactory.MODEL_TRANSFORM_BLOCK}，
     * 其 GUI 项 = {@code ModelFactory.TRANSFORM_BLOCK_GUI}（javap 静态块偏移 10-32：
     * 旋转 {@code (30, 225, 0)}、平移 {@code (0,0,0)}、缩放 {@code 0.625}）—— 就是原版
     * {@code block/block} 的 gui 变换。
     * 把 {@code (0,0,-1)}（模型空间的 NORTH 法线）过一遍 Rx(30°)·Ry(225°)：
     * {@code Ry(225°)→(0.7071, 0, 0.7071)}，{@code Rx(30°)→(0.7071, −0.3536, +0.6124)}，
     * z 分量 <b>+0.612 > 0</b> ⇒ 模型空间 <b>NORTH 面正对玩家</b>。
     * <p>而 GT 默认（不翻）就是 {@code frontFacing = NORTH} ⇒ {@code bakeQuads} 的旋转
     * {@code π − toRadians(180°) = 0} ⇒ 正面面片落在模型 NORTH ⇒ <b>正对玩家</b> ✔
     * <br>我们翻了 180° ⇒ 正面落到 SOUTH（背对玩家），玩家看到的 NORTH 面变成
     * {@code OverlayFace.bySide(NORTH) = FRONT} 那张贴图被反转到背面之后留下的
     * {@code BACK} —— 而本机用的 overlay 目录 {@code gtceu:block/multiblock/fusion_reactor}
     * <b>没有 overlay_back</b> ⇒ 那一面是空的 ⇒ 图标看不到动态面。✘
     *
     * @return {@code true} = 这一趟是物品图标，<b>不要</b>翻转
     */
    public static boolean isItemIconPath(MetaMachine machine) {
        return machine == null;
    }

    /**
     * 覆写它 = 只为了把 {@code frontFacing} 取反后交给父类（理由与回滚见
     * {@link #FLIP_OVERLAY_FRONT}）。
     *
     * <p>父类的这条方法<b>只</b>用第 4 参（frontFacing）与第 5 参（side）去调
     * {@code overlayModel.bakeQuads(...)}（javap：{@code WorkableCasingMachineRenderer#renderMachine}
     * 偏移 17-119 只读 local4/local5/local9 + IWorkable），
     * 第 2 参（definition）、第 7 参（modelFacing）、第 8 参（modelState）它<b>一个都没读</b>
     * ⇒ 这里原样传下去不会有副作用。{@code upwardsFacing} 保持不动（它只管贴图在面内的朝向）。
     *
     * <h2>🔴 第二十三改：翻转只作用于【方块渲染上下文】，物品图标一律不翻</h2>
     * 两条入口进的是同一个方法，靠入参区分（判据与字节码见 {@link #isItemIconPath}）：
     * <ul>
     *   <li>{@code machine != null} ⇒ 方块（世界里的机器）⇒ <b>照第二十二改翻</b>（现状不动）；</li>
     *   <li>{@code machine == null} ⇒ 物品图标 ⇒ <b>原样</b>（= GTCEu 默认 = 与别的 GT 机器图标一致）。</li>
     * </ul>
     */
    @Override
    public void renderMachine(java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads,
                              com.gregtechceu.gtceu.api.machine.MachineDefinition definition,
                              com.gregtechceu.gtceu.api.machine.MetaMachine machine,
                              Direction frontFacing, Direction side,
                              net.minecraft.util.RandomSource random,
                              Direction upwardsFacing,
                              net.minecraft.client.resources.model.ModelState modelState) {
        final boolean itemIcon = isItemIconPath(machine);
        final Direction overlayFront = (FLIP_OVERLAY_FRONT && !itemIcon && frontFacing != null)
                ? frontFacing.getOpposite()
                : frontFacing;
        super.renderMachine(quads, definition, machine, overlayFront, side, random, upwardsFacing, modelState);
    }

    @Override
    public boolean hasTESR(BlockEntity blockEntity) {
        if (!probeHasTesrLogged) {
            probeHasTesrLogged = true;
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ber_probe hasTESR=true @{}（GT 正在问本渲染器要不要 BER）",
                    blockEntity == null ? "?" : blockEntity.getBlockPos().toShortString());
        }
        return true;
    }

    @Override
    public boolean isGlobalRenderer(BlockEntity blockEntity) {
        if (!probeGlobalLogged) {
            probeGlobalLogged = true;
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ber_probe isGlobalRenderer=true @{}",
                    blockEntity == null ? "?" : blockEntity.getBlockPos().toShortString());
        }
        return true;
    }

    @Override
    public int getViewDistance() {
        return 384;
    }

    /**
     * 从方块实体取机器。
     *
     * <p>⚠️ 这个 helper <b>不</b>继承自任何渲染器基类 —— 老的 {@code AbstractRingRenderer} 里那几个字是
     * 它自己的 {@code protected static} 方法（{@code javap} 在 {@code MachineRenderer} /
     * {@code TextureOverrideRenderer} / {@code WorkableCasingMachineRenderer} 上都查不到），这里自带一份。
     */
    protected static MetaMachine getMetaMachine(BlockEntity blockEntity) {
        return (blockEntity instanceof com.gregtechceu.gtceu.api.machine.IMachineBlockEntity holder)
                ? holder.getMetaMachine()
                : null;
    }

    /** 每帧渲染。所有异常都被兜住 ⇒ 最坏情况是"这一帧不画"，绝不把异常抛到渲染线程。 */
    @Override
    public void render(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        try {
            if (!probeRenderEnteredLogged) {
                probeRenderEnteredLogged = true;
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] render_entered @{}（BER 真的调到本渲染器了）",
                        blockEntity == null ? "?" : blockEntity.getBlockPos().toShortString());
            }
            // 🔴 第一道：证明"render 到底有没有被调到"。这条日志出现 ⇒ BER 链路通。
            final MetaMachine meta = getMetaMachine(blockEntity);
            if (!(meta instanceof GuixuVisionCoreMachine host)) {
                logGate("no_machine", "方块实体上的机器不是 GuixuVisionCoreMachine（meta="
                        + (meta == null ? "null" : meta.getClass().getSimpleName()) + "）");
                // 🔴 2026-10-25：没有主机在喂料 ⇒ 清掉视界状态（渲染侧读到的是"默认那组值"）
                HostFxState.clear();
                // 🔴 第二十改：红色的中央两束 / 黑洞半径 / 透镜覆盖也一起还原（只清我们设过的）
                resetSwitchAndOverrides();
                // 🔴 2026-10-24 傍晚：这条早退以前<b>不接还原</b> ⇒ 机器对象一变，台账就永远留在那里。
                restoreFor(blockEntity, "no_machine");
                return;
            }
            if (!(meta instanceof IMultiController controller)) {
                logGate("no_controller", "机器不是 IMultiController");
                HostFxState.clear();
                resetSwitchAndOverrides();
                restoreFor(blockEntity, "no_controller");
                return;
            }
            final Level level = blockEntity.getLevel();
            if (!(level instanceof ClientLevel clientLevel)) {
                logGate("not_client_level", "level=" + (level == null ? "null" : level.getClass().getName()));
                HostFxState.clear();
                resetSwitchAndOverrides();
                return;
            }
            final BlockPos controllerPos = blockEntity.getBlockPos();
            final Direction facing = meta.getFrontFacing();

            // ⓪ 换维度 / 离开世界：台账与降级级别一起复位（不依赖任何外部事件类，纯渲染侧自管）
            if (lastLevel != clientLevel) {
                if (lastLevel != null) {
                    // 🔴 2026-10-24 傍晚用户第 ① 条：换维度/退世界<b>先按台账写回</b>再丢表。
                    //    旧实现是 `HostFxHide.clearAll()` —— 只丢表不写回 ⇒ 那些格子在客户端永远是空气。
                    HostFxHide.clearAll(lastLevel);
                    HostFxFallback.reset();
                    HostFxHideAudit.reset();
                    // 第八改：光柱环的 shader/缓冲、玻璃 AR 文字的缓存也跟着换维度复位
                    HostFxBeamRing.reset();
                    HostFxGlassArText.reset();
                    // 第二十改：换维度也把"红两束 / 主机接管半径 / 主机接管透镜"还原
                    //（跨维度那份覆盖没有任何意义，留着只会让另一维的黑洞用错半径/透镜）
                    resetSwitchAndOverrides();
                    LAST_LOG_MS.clear();
                    lastDrawMs = null;
                }
                lastLevel = clientLevel;
            }

            // ① 成型闸门（两道：控制器自己的 isFormed，加本机 @DescSynced 的成型位兜底）
            final boolean formedByController = controller.isFormed();
            final boolean formedBySync = host.isHostFormedSync();
            if (!formedByController && !formedBySync) {
                logGate("not_formed", "@(" + controllerPos.toShortString() + ") 朝" + facing
                        + " isFormed()=false 且 formedSync=false（客户端认为结构没成型）");
                // 快路径：看得见主机时的还原（去抖 2 s）。看不见主机时的保险在
                // HostFxHideTicker → HostFxHide.reconcile（心跳超时 2 s ⇒ 立刻还原 + WARN）。
                HostFxHide.restore(clientLevel, controllerPos, System.currentTimeMillis());
                clearBlackHoleIfOurs();
                HostFxState.clear();
                resetSwitchAndOverrides();
                return;
            }
            // 🔴 心跳：走过成型闸门 ⇒ "我们在看着这台主机"（与"要不要藏"无关，见 HostFxHide.noteFormed）
            HostFxHide.noteFormed(controllerPos);

            // ② 配置总开关（距离裁剪已按用户要求整条删除，见类注释）
            if (!ShanhaiClientConfig.hostFxEnabled()) {
                logGate("config_disabled", "[hostfx] enabled=false");
                HostFxHide.restore(clientLevel, controllerPos, System.currentTimeMillis());
                clearBlackHoleIfOurs();
                HostFxState.clear();
                resetSwitchAndOverrides();
                return;
            }
            if (!HostFxFallback.drawAnything()) {
                logGate("fallback_level", "HostFxFallback.level=" + HostFxFallback.level()
                        + " reason=" + HostFxFallback.reason());
                HostFxHide.restore(clientLevel, controllerPos, System.currentTimeMillis());
                clearBlackHoleIfOurs();
                HostFxState.clear();
                resetSwitchAndOverrides();
                return;
            }

            // ③ 藏真块（配置可关；关掉 = "静帧真块 + 动效"两层，但绝不写方块）
            if (HostFxFallback.hideBlocks() && ShanhaiClientConfig.hostFxHideRealBlocks()) {
                HostFxHide.hide(clientLevel, controllerPos, facing);
            }

            // ④ 时间基：世界时间（暂停时 gameTime 不走 ⇒ 动效自然冻结）
            final float seconds = (clientLevel.getGameTime() + partialTick) / 20.0F;

            // ④.5 🔴 2026-10-25 第十九改：把机器上的 6 个视界参数<b>喂给渲染侧</b>。
            //
            //   位置纪律（唯一既有先例 = 本文件末尾 feedBlackHole 把环心喂给 BlackHoleTuning）：
            //     · 必须在【成型闸门 + 配置闸门之后】（来不及画的那些帧不喂，早退时 clear()）；
            //     · 必须在【任何绘制之前】（环/舰/光束这一帧就要按新值转）；
            //     · 每帧一次（不是每 tick）—— 与其它状态读取同一个节奏。
            //
            //   两个去向，各管一件事：
            //     ① HostFxState：渲染侧（另一条线）要接的新 API —— 特效总开关 / 黑洞半径 / 透镜开关
            //        没有别的入口，只能从这里读；
            //     ② HostFxGeometry.setGroupDegPerSec：三组角速度的<b>既有</b>读点
            //        （ringDeg / shipOrbitDeg / 光柱环自转 / bake 摘要）直接读那三个静态字段
            //        ⇒ 喂进去之后"面板一改、环与舰当帧就变"，不必等渲染侧那条线接完。
            //
            //   ⚠️ 两个去向的值【同源同帧】（都直接来自 host 的 @DescSynced 字段），
            //      不存在"HostFxState 是新的、HostFxGeometry 还是旧的"这种中间态。
            HostFxState.feed(
                    host.isHostFxEnabled(),
                    host.getHostRingDegPerSec(),
                    host.getHostShipUpDegPerSec(),
                    host.getHostShipDownDegPerSec(),
                    host.getHostBlackHoleRadiusBlocks(),
                    host.isHostLensEnabled());
            // 🔴 2026-10-25 第二十一改：AR 遮罩开关（与上面 6 个同一个节奏、同一次早退清空）
            HostFxState.feedArMask(host.isHostArMaskEnabled());
            HostFxGeometry.setGroupDegPerSec(
                    host.getHostRingDegPerSec(),
                    host.getHostShipUpDegPerSec(),
                    host.getHostShipDownDegPerSec());

            // ④.6 🔴 2026-10-25 第二十改：<b>开关语义</b>与<b>参数接线</b>（本文件只做"接线"，判定全在别处）
            //
            //   ① 降级态（机器 hostFxEnabled=false）：
            //        · 中央上下两束换红            → HostFxSwitch.apply()（复用 jetGlowOverride 机制）
            //        · 4 条解压机主柱 + 粗光柱环消失 → HostFxSwitch.mdMainBeamsOn()/beamRingOn()（HostFxDraw 里跳段）
            //        · 环/舰原地不动                → 下面那行 spinSeconds（冻结时钟，不是 0）
            //        · 黑洞仍在                     → 本方法对所有黑洞路径【不 early-return】
            //   ② 黑洞半径 / 引力透镜：机器字段<b>优先</b>，但只覆盖我们自己设过的
            //      （照本文件 clearBlackHoleIfOurs 的先例）：主机不在/不成型时还原 ⇒
            //      /shanhai bh scale|bh lens 设的值不会被永久顶掉。
            //      值的来源统一走 HostFxState（已在上面钳过），不直接读 host 的原始字段 ——
            //      这样"渲染侧看到的半径"与"日志里打印的半径"永远是同一个数。
            HostFxSwitch.apply();
            feedBlackHoleRadius(HostFxState.holeRadiusBlocks());
            feedBlackHoleLens(HostFxState.lensEnabled());

            // ④.7 自转时钟 = <b>动画时钟</b>（HostFxState.animSeconds）：环 / 8 舰 / 4 座解压机 /
            //      12 条细紫光束的自转角全部从它算。
            //      🔴 效果开时它就是墙钟（与改前的世界时间逐帧等价，动效没有回退）；
            //         效果关时它【冻结在上一次的值】⇒ 一切自转原地停住，且<b>不会瞬移回初始姿态</b>。
            final float spinSeconds = HostFxSwitch.spinSeconds();

            // ⑤ 枢轴 = <b>环心那一格的格中心</b>（TESR 的 PoseStack 原点 = 控制器方块的最小角）
            //
            // 🔴 2026-10-24 定稿：「+0.5」不是可选项，是**刚体转 90° 还落在格子上**的充要条件。
            //    · 顶点来自真方块烘焙：每格 translate 到"格中心 −0.5"（也就是 [n−0.5, n+0.5]），
            //      见 {@link HostFxGeometry#CELL_CENTER}；
            //    · 于是本地 (0,0,0) = 环心那一格的<b>中心</b>，而世界里的那个中心 = centerWorld + 0.5（三轴）；
            //    · 若这里不加 0.5（= 拿"格最小角"当原点），绕它转 90°/270° 会把每块立方体映射到隔壁一格
            //      ⇒ NORTH/EAST/SOUTH 三个朝向的环整体错开整整 1 格（离线探针② 实测过 1.0000）。
            final BlockPos center = HostFxGeometry.centerWorld(controllerPos, facing);
            final float cx = center.getX() - controllerPos.getX() + HostFxGeometry.CELL_CENTER;
            final float cy = center.getY() - controllerPos.getY() + HostFxGeometry.CELL_CENTER;
            final float cz = center.getZ() - controllerPos.getZ() + HostFxGeometry.CELL_CENTER;

            // ⑥ 内环带 + 8 艘飞船：<b>真方块跟着转</b>（烘焙自 ③ 那份台账；不再画任何四边形）
            //    逐组：RenderType.setupRenderState → VertexBuffer.drawWithShader → clearRenderState
            final boolean ships = HostFxFallback.drawShips();
            final boolean baked = HostFxRingGroups.ensureBaked(controllerPos, facing);
            if (baked) {
                // 🔴 spinSeconds（动画时钟）——不是世界时间 seconds：降级态冻结 ⇒ 环/舰原地不动
                HostFxRingGroups.render(poseStack, facing, cx, cy, cz, spinSeconds, ships);
            } else {
                logGate("not_baked", "台账还没建起来（HostFxHide 尚未藏块，先不画，免得和真方块叠成两层）");
            }

            // ⑦ 光束（🔴 2026-10-25 第七改）：
            //    12 条细紫引导光束（每座解压机 3 条，绕本座喷口轴 r=1.90、互成 120°）→ 自绘加性管壳；
            //    4 条解压机主喷口光柱 + 中央上下 2 根主光柱 → 伪神之锻炉那套（同一份 shader + space_layer 贴图）。
            //    几何在 HostFxGeometry.buildBeamQuads，状态与提交在 HostFxDraw / HostFxPillarBeam。
            final Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            // 黑洞表面半径：优先用 blackhole 包这一帧真正用的视界半径（× B_CRIT）
            final float holeR = HostFxBlackHole.surfaceRadius();

            // ⑥.5 黑洞<b>安全兜底</b>：纯顶点 billboard（黑盘 + 光子环 + 光晕 + 吸积盘）。
            //     只在"真路径这一帧没画"时顶上（HostFxBlackHole.active() 逐条判据见类注释）。
            if (HostFxBlackHole.active()) {
                final float[] camLocal =
                        HostFxBlackHole.cameraLocal(facing, cam.x, cam.y, cam.z, controllerPos);
                poseStack.pushPose();
                try {
                    HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
                    HostFxBlackHole.render(poseStack, camLocal[0], camLocal[1], camLocal[2], seconds, holeR);
                } finally {
                    poseStack.popPose();
                }
            }

            // 🔴 seconds 必须传进去：粒子沿轴流动的相位来自它（停世界 ⇒ 自然冻结）
            //    第八改起：这个调用里还包含【内外环之间那圈粗光柱环】（HostFxBeamRing）。
            //    ⚠️ 那个参数是 spinDeg（自转角）：必须来自 spinSeconds（动画时钟），
            //    不能再写成 seconds —— 否则降级态环停了、而挂在环上的细紫光束还在转（两者会脱钩）。
            //    UV 流动仍用 seconds（世界时间）：降级态中央两束是"红的、还在流"，那是刻意保留的
            //    "仍通电"信号（用户只要求"不转"，没要求"光也冻住"）。
            HostFxDraw.drawBeams(poseStack, cx, cy, cz, facing, controllerPos, cam.x, cam.y, cam.z,
                    holeR, HostFxGeometry.ringDeg(spinSeconds), seconds);

            // ⑦.5 玻璃墙上的绿色 AR 文字（用户要求 ②）：
            //      只在【成型之后】出现 —— 本方法在成型闸门（①）与配置总开关（②）之后才会走到这里，
            //      不成型时上面已经 return 了 ⇒ 这里不需要再判一次（判两次反而会出现"两处口径不一致"）。
            //      ⚠️ 它自己开 immediate 缓冲并当场 endBatch（不依赖调用方的 flush 时机）。
            HostFxGlassArText.render(poseStack, host, cx, cy, cz, facing, seconds);

            // 🔴 "到底画了没有"的唯一判据：这一行（带计数与台账核对）。每 5 秒最多一条。
            //    2026-10-25 第十九改：这一行里【加上了玩家在面板里调的那 6 个值】
            //    —— 进游戏之后 grep `视界` 就能看到玩家调的值到底有没有传到客户端渲染侧。
            logDraw(ships, formedByController, formedBySync,
                    host.hostFxParamsLine(),
                    HostFxHideAudit.report(clientLevel, controllerPos, facing)
                            + " | 对账 " + HostFxHide.lastReconcile()
                            + " | 藏块台账 " + HostFxHide.hiddenCount() + " 格 / "
                            + HostFxHide.hostCount() + " 台主机");

            // ⑧ 黑洞：把中心喂给既有渲染器（它自己负责吸积盘/光子环/透镜/兜底）
            feedBlackHole(center);
        } catch (Throwable t) {
            // 判据③：任何一步失败 ⇒ 降级 + 一行可 grep 的日志，绝不把异常抛到渲染线程
            HostFxFallback.degrade(HostFxFallback.LEVEL_OFF,
                    "渲染异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] 渲染异常已兜住（本帧不画）", t);
        }
    }

    /**
     * 早退路径上的还原：拿到方块实体的 Level / 位置就按台账写回。
     *
     * <p>⚠️ 这里刻意<b>不</b>判断"结构成没成型"：能走到这些早退，说明我们已经无法确认它的状态
     * （机器对象变了 / 不是多方块控制器），那就不该继续把世界里的真方块扣着。
     */
    private static void restoreFor(BlockEntity blockEntity, String why) {
        if (blockEntity == null) {
            return;
        }
        final Level level = blockEntity.getLevel();
        if (!(level instanceof ClientLevel)) {
            return;                                     // 服务端/已卸载：没有客户端世界可写
        }
        final BlockPos pos = blockEntity.getBlockPos();
        if (pos == null) {
            return;
        }
        ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] restore_on_gate gate={} host={}（这条早退以前不接还原）",
                why, pos.toShortString());
        HostFxHide.restoreNow(level, pos);
    }

    /**
     * 把环心喂给既有黑洞渲染器。
     *
     * <p>三个轴都 <b>+0.5</b>（= {@link HostFxGeometry#CELL_CENTER}）：烘好的三组绕的那条竖轴穿过
     * "环心那一格的<b>中心</b>"，不是"格最小角"。黑洞本体必须坐在同一条轴上，否则环一转黑洞就偏心
     * （上下 2 条极轴光束从本地 (0,±y,0) 射出，0.5 格的偏心肉眼能看出"光束没插进洞里"）。
     */
    private static void feedBlackHole(BlockPos center) {
        final double[] c = {center.getX() + HostFxGeometry.CELL_CENTER,
                center.getY() + HostFxGeometry.CELL_CENTER,
                center.getZ() + HostFxGeometry.CELL_CENTER};
        lastCenter = c;
        BlackHoleTuning.setCenter(c);
    }

    /** 停止画时清掉我们设的中心（只清"还是我们设的那个"，绝不覆盖玩家/命令设的值）。 */
    private static void clearBlackHoleIfOurs() {
        final double[] ours = lastCenter;
        if (ours == null) {
            return;
        }
        final double[] now = BlackHoleTuning.centerOverride();
        if (now != null && Math.abs(now[0] - ours[0]) < 1.0e-6D
                && Math.abs(now[1] - ours[1]) < 1.0e-6D
                && Math.abs(now[2] - ours[2]) < 1.0e-6D) {
            BlackHoleTuning.setCenter(null);
        }
        lastCenter = null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 2026-10-25 第二十改：黑洞【半径】与【引力透镜】的主机接管
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话：「中心球体渲染风格，你直接改成可以修改黑洞的半径和是否开启引力透镜效果得了」。
     *
     * 口径（三条，都是"不许踩坑"的）：
     *   ① 【机器字段优先】主机在场时，半径与透镜都以机器上那两个 @DescSynced 字段为准；
     *   ② 【只覆盖自己设过的】照 clearBlackHoleIfOurs 的先例：清之前比一次值，
     *      还是我们设的那个才清 ⇒ `/shanhai bh scale|bh lens` 设的值不会被永久顶掉；
     *   ③ 【值只走 HostFxState】半径用 HostFxState.holeRadiusBlocks()（已被钳到 1.0..12.0），
     *      不用 host 的原始字段 —— 保证"画出来的半径"与"日志里 holeR=" 永远是同一个数。
     */

    /** 把主机半径喂给黑洞渲染件（格）；黑洞包那边读它的是 {@code BlackHoleLensRenderer.rsBlocks()}。 */
    private static void feedBlackHoleRadius(float blocks) {
        lastHoleRadiusBlocks = blocks;
        BlackHoleTuning.setHostRadiusBlocks(blocks);
    }

    /** 把主机的引力透镜开关喂给黑洞渲染件（关 ⇒ 只画黑盘/光子环/吸积盘，背景不弯折）。 */
    private static void feedBlackHoleLens(boolean enabled) {
        lastLensEnabled = enabled;
        BlackHoleTuning.setHostLensEnabled(enabled);
    }

    /** 撤销我们设过的半径/透镜覆盖（只清"还是我们设的那个"，绝不覆盖命令设的值）。 */
    private static void clearBlackHoleOverridesIfOurs() {
        final Float oursR = lastHoleRadiusBlocks;
        if (oursR != null) {
            if (BlackHoleTuning.hasHostRadiusBlocks()
                    && Math.abs(BlackHoleTuning.hostRadiusBlocks() - oursR) < 1.0e-6F) {
                BlackHoleTuning.clearHostRadiusBlocks();
            }
            lastHoleRadiusBlocks = null;
        }
        final Boolean oursLens = lastLensEnabled;
        if (oursLens != null) {
            final Boolean now = BlackHoleTuning.hostLensEnabled();
            if (now != null && now.equals(oursLens)) {
                BlackHoleTuning.clearHostLensEnabled();
            }
            lastLensEnabled = null;
        }
    }

    /**
     * 早退路径上的<b>开关复位</b>（与 {@link #clearBlackHoleIfOurs()} 并列的那个"顺手还原"）。
     *
     * <p>为什么每条 early-return 都必须调：这些覆盖是<b>进程级静态状态</b>，
     * 不还原的话"主机拆了/退出视野"之后，那一维的黑洞会继续用主机上次给的半径与透镜开关
     * —— 玩家会看到"黑洞瘦了/透镜关不掉"，而任何日志都指不到这里。
     */
    private static void resetSwitchAndOverrides() {
        HostFxSwitch.reset();
        clearBlackHoleOverridesIfOurs();
    }
}
