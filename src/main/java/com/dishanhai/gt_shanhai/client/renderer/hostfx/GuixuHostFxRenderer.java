package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.GuixuClientConfig;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleTuning;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensMath;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensRenderer;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;

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
 *       每帧绕环心转 12°/秒 重画一遍；</li>
 *   <li><b>上下 8 艘磁悬浮飞船</b> —— 自绘小模型（≤3×2×2 格、单块 ≤1 格、仿《群星》物质解压机），
 *       <b>与内环同一个枢轴、同一个角速度</b>（用户：「必须跟着内环一起转」）；</li>
 *   <li><b>黑洞</b> —— 主路径不在本类：把中心喂给既有 {@link BlackHoleTuning#setCenter}，由
 *       {@code client/render/blackhole} 那一套（吸积盘 + 光子环 + 真透镜）去画。
 *       🔴 但那一套在<b>光影包生效</b>时降级、且二级贴图路径已整条停用 ⇒ 会<b>一帧都不画</b>，
 *       所以本类补一个<b>安全兜底</b>（{@link HostFxBlackHole}：纯顶点黑盘/光子环/吸积盘，零贴图零裸 GL），
 *       只在"真路径这一帧没画"时顶上（两条路互斥，绝不同时出现两个黑洞）；</li>
 *   <li><b>6 条光束</b> —— <b>4 条径向</b>（内环 4 个连接臂根部 → 黑洞表面）+ 黑洞上下 2 条，
 *       这是唯一保留的自绘特效（{@link HostFxDraw#drawBeams}：圆柱壳 / 加性 / 不写深度）。</li>
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
        GTDishanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] class_loaded renderer=GuixuHostFxRenderer（本类第一次被加载）");
    }

    /** 一次性探针标记（各只打一条，避免刷屏）。 */
    private static boolean probeHasTesrLogged;
    private static boolean probeGlobalLogged;
    private static boolean probeRenderEnteredLogged;

    /** 上一次喂给黑洞的中心（用来判断"该不该清掉"，避免覆盖玩家用命令设的中心）。 */
    private static double[] lastCenter;

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
        GTDishanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] gate={} {}", reason, detail);
    }

    private static void logDraw(boolean ships, boolean formedByController, boolean formedBySync,
                                String auditLine) {
        final long now = System.currentTimeMillis();
        if (lastDrawMs != null && now - lastDrawMs < LOG_THROTTLE_MS) {
            return;
        }
        lastDrawMs = now;
        GTDishanhaiMod.LOGGER.info(
                "[SHANHAI-HOSTFX] draw ships={} formed(ctrl)={} formed(sync)={} {} | {} | {} | 审计 {}",
                ships, formedByController, formedBySync, HostFxRingGroups.summary(),
                HostFxDraw.stats(), HostFxGeometry.budget(), auditLine);
    }

    public GuixuHostFxRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    @Override
    public boolean hasTESR(BlockEntity blockEntity) {
        if (!probeHasTesrLogged) {
            probeHasTesrLogged = true;
            GTDishanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ber_probe hasTESR=true @{}（GT 正在问本渲染器要不要 BER）",
                    blockEntity == null ? "?" : blockEntity.getBlockPos().toShortString());
        }
        return true;
    }

    @Override
    public boolean isGlobalRenderer(BlockEntity blockEntity) {
        if (!probeGlobalLogged) {
            probeGlobalLogged = true;
            GTDishanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ber_probe isGlobalRenderer=true @{}",
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
                GTDishanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] render_entered @{}（BER 真的调到本渲染器了）",
                        blockEntity == null ? "?" : blockEntity.getBlockPos().toShortString());
            }
            // 🔴 第一道：证明"render 到底有没有被调到"。这条日志出现 ⇒ BER 链路通。
            final MetaMachine meta = getMetaMachine(blockEntity);
            if (!(meta instanceof GuixuVisionCoreMachine host)) {
                logGate("no_machine", "方块实体上的机器不是 GuixuVisionCoreMachine（meta="
                        + (meta == null ? "null" : meta.getClass().getSimpleName()) + "）");
                // 🔴 2026-10-24 傍晚：这条早退以前<b>不接还原</b> ⇒ 机器对象一变，台账就永远留在那里。
                restoreFor(blockEntity, "no_machine");
                return;
            }
            if (!(meta instanceof IMultiController controller)) {
                logGate("no_controller", "机器不是 IMultiController");
                restoreFor(blockEntity, "no_controller");
                return;
            }
            final Level level = blockEntity.getLevel();
            if (!(level instanceof ClientLevel clientLevel)) {
                logGate("not_client_level", "level=" + (level == null ? "null" : level.getClass().getName()));
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
                return;
            }
            // 🔴 心跳：走过成型闸门 ⇒ "我们在看着这台主机"（与"要不要藏"无关，见 HostFxHide.noteFormed）
            HostFxHide.noteFormed(controllerPos);

            // ② 配置总开关（距离裁剪已按用户要求整条删除，见类注释）
            if (!GuixuClientConfig.hostFxEnabled()) {
                logGate("config_disabled", "[hostfx] enabled=false");
                HostFxHide.restore(clientLevel, controllerPos, System.currentTimeMillis());
                clearBlackHoleIfOurs();
                return;
            }
            if (!HostFxFallback.drawAnything()) {
                logGate("fallback_level", "HostFxFallback.level=" + HostFxFallback.level()
                        + " reason=" + HostFxFallback.reason());
                HostFxHide.restore(clientLevel, controllerPos, System.currentTimeMillis());
                clearBlackHoleIfOurs();
                return;
            }

            // ③ 藏真块（配置可关；关掉 = "静帧真块 + 动效"两层，但绝不写方块）
            if (HostFxFallback.hideBlocks() && GuixuClientConfig.hostFxHideRealBlocks()) {
                HostFxHide.hide(clientLevel, controllerPos, facing);
            } else {
                HostFxHide.restoreImmediately(clientLevel, controllerPos);
            }

            // ④ 时间基：世界时间（暂停时 gameTime 不走 ⇒ 动效自然冻结）
            final float seconds = (clientLevel.getGameTime() + partialTick) / 20.0F;

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
                HostFxRingGroups.render(poseStack, facing, cx, cy, cz, seconds, ships);
            } else {
                logGate("not_baked", "台账还没建起来（HostFxHide 尚未藏块，先不画，免得和真方块叠成两层）");
            }

            // ⑦ 六條光束（4 條徑向＋黑洞上下 2 條），星辰效果在下方另批提交。
            //    几何在 HostFxGeometry.buildBeamQuads（32 段 × 12 片圆 / 五次贝塞尔 / 亮色包络），
            //    状态与一次性 flush 在 HostFxDraw（加性、不写深度、真的关剔除）。
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

            final double effectSeconds = (clientLevel.getGameTime() + (double) partialTick) / 20.0;
            final var profile = host.getVisualProfile(effectSeconds);
            HostFxDraw.drawBeams(poseStack, cx, cy, cz, facing, controllerPos, cam.x, cam.y, cam.z,
                    holeR, HostFxGeometry.ringDeg(seconds), profile.brightness());

            // 山海：以目前黑洞尺寸收束星辰；與成型／總特效共用早退閘門，無世界粒子殘留。
            final double starHorizon = BlackHoleLensRenderer.rsBlocks() * BlackHoleLensMath.B_CRIT;
            if (BlackHoleTuning.enabled()) {
                final float[] camLocal = HostFxBlackHole.cameraLocal(facing, cam.x, cam.y, cam.z, controllerPos);
                poseStack.pushPose();
                try {
                    HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
                    GuixuStarRenderer.render(poseStack, camLocal,
                            effectSeconds, starHorizon, profile.density(), profile.brightness(), profile.jets());
                } finally {
                    poseStack.popPose();
                }
            }

            // 🔴 "到底画了没有"的唯一判据：这一行（带计数与台账核对）。每 5 秒最多一条。
            logDraw(ships, formedByController, formedBySync,
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
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] 渲染异常已兜住（本帧不画）", t);
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
        GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] restore_on_gate gate={} host={}（这条早退以前不接还原）",
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
}
