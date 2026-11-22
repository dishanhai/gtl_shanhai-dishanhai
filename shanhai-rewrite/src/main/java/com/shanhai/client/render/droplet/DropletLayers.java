package com.shanhai.client.render.droplet;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * 水滴用的 <b>4 条 RenderType</b>（全部 {@code POSITION_COLOR}：顶点色直接当片元色，
 * <b>不采样任何贴图</b>，与 {@code client/render/hostfx} 那套是同一个做法）。
 *
 * <h2>一、四条层与它们的差别（这张表就是"状态清单"）</h2>
 * <table border="1">
 *   <tr><th>层</th><th>用途</th><th>混合</th><th>深度测试</th><th>写深度</th><th>剔除</th></tr>
 *   <tr><td>{@link #body()}</td><td>本体（不透明金属壳）</td><td>关（不混合）</td><td>LEQUAL</td>
 *       <td><b>写</b></td><td><b>剔背面</b></td></tr>
 *   <tr><td>{@link #glow()}</td><td>推进环 + 尾迹（自发光）</td><td>加性 {@code SRC_ALPHA,ONE}</td>
 *       <td>LEQUAL</td><td>不写</td><td>不剔（薄壳双面）</td></tr>
 *   <tr><td>{@link #box()}</td><td>穿墙线框盒（12 棱）</td><td>普通 alpha</td>
 *       <td><b>ALWAYS（等于关掉深度测试）</b></td><td>不写</td><td>不剔（双面）</td></tr>
 *   <tr><td>{@link #gui()}</td><td>准星拉线（GUI 正交）</td><td>普通 alpha</td>
 *       <td><b>ALWAYS</b></td><td>不写</td><td>不剔</td></tr>
 * </table>
 *
 * <h2>二、"画完必须恢复深度/混合状态"是怎么保证的</h2>
 * 不是靠我手写 {@code finally}，而是靠 {@link RenderType} 自身的
 * {@code setupRenderState()} / {@code clearRenderState()} 一对：每一条
 * {@link RenderStateShard} 的 clear 都是"回到原版默认"（{@code enableDepthTest()} /
 * {@code depthMask(true)} / {@code enableCull()} / {@code disableBlend()} 或
 * {@code defaultBlendFunc()}）。所以只要顶点是 <b>通过</b> {@code MultiBufferSource} +
 * {@code endBatch(该层)} 提交的，状态就一定会还原 ——
 * <b>这条约束反过来就是代码纪律：本类之外不许出现裸的 {@code RenderSystem.*} 调用</b>
 * （整条渲染线用 {@code Select-String -Pattern "RenderSystem\."} 一查就知道越没越界）。
 *
 * <p>为什么这里自己写两份 transparency 分片（而不是用 {@code RenderStateShard.ADDITIVE_TRANSPARENCY}）：
 * 原版那几个字段是 {@code protected}，{@code hostfx} 里那两份是 {@code private}，
 * 而这一条线<b>不许改 hostfx</b> ⇒ 在自己包里落一份同语义的副本（与原版
 * {@code ADDITIVE_TRANSPARENCY} 的差别只有一处：原版是 {@code ONE,ONE}，
 * 这里是 {@code SRC_ALPHA,ONE} —— 顶点 alpha 才是亮度包络，用 {@code ONE,ONE} 会把包络丢掉）。
 */
public final class DropletLayers {

    private DropletLayers() {}

    /** 顶点色着色器：{@code position_color}（无贴图、无光照，颜色全在顶点里）。 */
    private static final RenderStateShard.ShaderStateShard POSITION_COLOR_SHADER =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader);

    /** 加性：{@code src*SRC_ALPHA + dst*ONE}（与 hostfx 的 ADDITIVE 逐项同义）。 */
    private static final RenderStateShard.TransparencyStateShard ADDITIVE =
            new RenderStateShard.TransparencyStateShard("shanhai_droplet_additive",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /** 普通 alpha：{@code src*SRC_ALPHA + dst*(1-SRC_ALPHA)}（GUI 拉线用）。 */
    private static final RenderStateShard.TransparencyStateShard ALPHA =
            new RenderStateShard.TransparencyStateShard("shanhai_droplet_alpha",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                                GlStateManager.SourceFactor.ONE,
                                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /** 不混合（不透明本体）。 */
    private static final RenderStateShard.TransparencyStateShard NO_BLEND =
            new RenderStateShard.TransparencyStateShard("shanhai_droplet_no_blend",
                    RenderSystem::disableBlend, RenderSystem::disableBlend);

    private static final RenderStateShard.DepthTestStateShard DEPTH_LEQUAL =
            new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL);
    private static final RenderStateShard.DepthTestStateShard DEPTH_ALWAYS =
            new RenderStateShard.DepthTestStateShard("always", GL11.GL_ALWAYS);

    private static final RenderStateShard.WriteMaskStateShard WRITE_COLOR_DEPTH =
            new RenderStateShard.WriteMaskStateShard(true, true);
    private static final RenderStateShard.WriteMaskStateShard WRITE_COLOR_ONLY =
            new RenderStateShard.WriteMaskStateShard(true, false);

    private static final RenderStateShard.CullStateShard CULL_BACK =
            new RenderStateShard.CullStateShard(true);
    private static final RenderStateShard.CullStateShard CULL_NONE =
            new RenderStateShard.CullStateShard(false);

    /** 上一批的模型视图矩阵（{@link #MAIN_TARGET_IDENTITY_MV} 的 setup/clear 一对用）。 */
    private static final Matrix4f SAVED_MODEL_VIEW = new Matrix4f();

    /**
     * 🔴 <b>世界层那一趟必须自己把模型视图矩阵设成单位矩阵</b>（2026-11-24 定位到的坑）。
     *
     * <h4>为什么要这一手（有字节码证据）</h4>
     * {@code VertexConsumer#vertex(Matrix4f,...)} 是<b>在 CPU 上</b>把顶点乘进"相机空间"的，
     * 而 {@code BufferUploader.drawWithShader} 又会把 {@code RenderSystem.getModelViewMatrix()}
     * 作为 {@code ModelViewMat} 交给着色器（{@code BufferUploader._drawWithShader} 偏移 9-19：
     * {@code getModelViewMatrix()} → {@code getProjectionMatrix()} → {@code VertexBuffer.drawWithShader}）。
     * <b>两者只能有一个生效</b>：实体渲染器能对上，是因为实体那一趟 {@code ModelViewMat} 恰好是单位阵。
     *
     * <p>而 {@code LevelRenderer.renderLevel} 在"云/天气"那一段会把
     * {@code RenderSystem.getModelViewStack()} 压一次再乘上视图矩阵、调 {@code applyModelViewMatrix()}
     * （偏移 2761-2778），在段落末尾再 {@code popPose()+applyModelViewMatrix()} 还原（偏移 3028-3033）。
     * 只要有人在<b>这两步之间</b>画东西（例如挂在某个 RenderType 尾部的阶段事件），就会吃到
     * {@code ModelViewMat = 视图矩阵} ⇒ 顶点被<b>二次变换</b> ⇒ 画到屏幕外，且<b>不报错</b>。
     *
     * <p>所以：这一趟不赌"当前矩阵是谁"，自己钉成单位阵，draw 完再原样还原。
     */
    private static final RenderStateShard.OutputStateShard MAIN_TARGET_IDENTITY_MV =
            new RenderStateShard.OutputStateShard("shanhai_droplet_main_identity_mv",
                    () -> {
                        // 输出目标钉回主帧缓冲：不继承上游那一趟（例如半透明地形）的目标
                        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                        SAVED_MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
                        RenderSystem.getModelViewMatrix().identity();
                    },
                    () -> RenderSystem.getModelViewMatrix().set(SAVED_MODEL_VIEW));

    /**
     * 每帧四边形上限的估计。
     *
     * <p>2026-11-24 重算（一个水滴）：本体 1152 + 推进环 2 遍 × 2 环 × 40 段 = 160 +
     * 尾迹 2 层 × 47 段 = 94 + 线框盒 12 棱 × 1 四边形 = 12 ⇒ <b>1418</b>（2026-11-24 换线框盒后）。
     * 三种批次混在一个 BufferSource 里，多水滴时按 3 个水滴预留。
     */
    private static final int BUFFER_SIZE = 262144;

    private static RenderType body;
    private static RenderType glow;
    private static RenderType box;
    private static RenderType gui;
    private static RenderType guiLines;

    /** 本体：不透明的冷蓝银金属壳（写深度、剔背面 —— 网格绕序已验证为外法线逆时针）。 */
    public static RenderType body() {
        if (body == null) {
            body = RenderType.create(
                    "shanhai_droplet_body",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    BUFFER_SIZE,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(NO_BLEND)
                            .setDepthTestState(DEPTH_LEQUAL)
                            .setWriteMaskState(WRITE_COLOR_DEPTH)
                            .setCullState(CULL_BACK)
                            .setOutputState(MAIN_TARGET_IDENTITY_MV)
                            .createCompositeState(false));
        }
        return body;
    }

    /**
     * 推进环 + 尾迹：加性自发光（不写深度 ⇒ 互相叠加不会互相遮挡）。
     *
     * <p>2026-11-24：这两件都改成<b>世界空间里留下的痕迹</b>，绘制搬到
     * {@link ShanhaiDropletWorldFx} 的全局阶段（不在实体渲染器里）⇒ 必须带单位模型视图矩阵。
     */
    public static RenderType glow() {
        if (glow == null) {
            glow = RenderType.create(
                    "shanhai_droplet_glow",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    BUFFER_SIZE,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(ADDITIVE)
                            .setDepthTestState(DEPTH_LEQUAL)
                            .setWriteMaskState(WRITE_COLOR_ONLY)
                            .setCullState(CULL_NONE)
                            .setOutputState(MAIN_TARGET_IDENTITY_MV)
                            .createCompositeState(false));
        }
        return glow;
    }

    /**
     * 穿墙的<b>蓝色线框盒</b>：<b>关深度测试</b>（{@code GL_ALWAYS}）、不写深度、普通 alpha 混合、
     * 不剔面（双面 12 条棱）。
     *
     * <p>为什么"关深度"就等于"在方块后面也看得见"：深度测试被设成 ALWAYS 之后，
     * 片元一律通过 ⇒ 墙体、地形、其它实体谁先写深度都不影响这一趟的像素。
     * 又因为我们<b>不写深度</b>，这一趟不会污染后面任何一层的深度比较（原版不变量不变）。
     *
     * <p>为什么用普通 alpha 而不是加性：线框要的是"实心蓝"的边界感；加性叠在亮地形上会被冲淡，
     * 而 alpha 混合在亮/暗背景上都保得住轮廓（用户点名要"和驾驶的框一样"）。
     *
     * <p>为什么必须带 {@link #MAIN_TARGET_IDENTITY_MV}：这一趟画在全局阶段里，
     * 别人可能刚改过模型视图矩阵（见该字段的注释）⇒ 自己钉单位阵最省事。
     */
    public static RenderType box() {
        if (box == null) {
            box = RenderType.create(
                    "shanhai_droplet_box",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    BUFFER_SIZE,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(ALPHA)
                            .setDepthTestState(DEPTH_ALWAYS)
                            .setWriteMaskState(WRITE_COLOR_ONLY)
                            .setCullState(CULL_NONE)
                            .setOutputState(MAIN_TARGET_IDENTITY_MV)
                            .createCompositeState(false));
        }
        return box;
    }

    /** 准星拉线：GUI 正交、关深度、普通 alpha。 */
    public static RenderType gui() {
        if (gui == null) {
            gui = RenderType.create(
                    "shanhai_droplet_gui",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1024,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(ALPHA)
                            .setDepthTestState(DEPTH_ALWAYS)
                            .setWriteMaskState(WRITE_COLOR_ONLY)
                            .setCullState(CULL_NONE)
                            .createCompositeState(false));
        }
        return gui;
    }

    /** 名字（日志用；null = 还没建过）。 */
    public static String debugNames() {
        return "body=" + (body == null ? "未建" : "shanhai_droplet_body")
                + " glow=" + (glow == null ? "未建" : "shanhai_droplet_glow")
                + " box=" + (box == null ? "未建" : "shanhai_droplet_box")
                + " gui=" + (gui == null ? "未建" : "shanhai_droplet_gui");
    }
}
