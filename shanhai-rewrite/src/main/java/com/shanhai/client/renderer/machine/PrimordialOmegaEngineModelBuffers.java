package com.shanhai.client.renderer.machine;

import com.shanhai.ShanhaiMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.gtlcore.gtlcore.client.ClientUtil;

import java.util.List;

/**
 * 鸿蒙微型宇宙的静态模型 GPU 缓冲。
 * 模型烘焙只在首次使用或资源重载后发生，正常帧只提交 VBO 绘制。
 */
final class PrimordialOmegaEngineModelBuffers {

    static final int STAR = 0;
    static final int ORBIT_START = 1;
    static final int SPACE = 4;

    private static final List<ResourceLocation> MODEL_IDS = List.of(
            new ResourceLocation("gtlcore", "obj/star"),
            new ResourceLocation("gtlcore", "obj/the_nether"),
            new ResourceLocation("gtlcore", "obj/overworld"),
            new ResourceLocation("gtlcore", "obj/the_end"),
            new ResourceLocation("gtlcore", "obj/space")
    );

    private static BakedModel[] bakedModels;
    private static VertexBuffer[] buffers;

    private PrimordialOmegaEngineModelBuffers() {}

    static VertexBuffer[] getBuffers() {
        BakedModel[] currentModels = readCurrentModels();
        if (currentModels == null) return null;
        if (buffers == null || bakedModels == null || modelsChanged(currentModels)) {
            rebuild(currentModels);
        }
        return buffers;
    }

    /**
     * 作废本组 VBO（资源重载时由 {@code ShanhaiClientReloadInvalidator} 调用）。
     *
     * <p>本类<b>本来就有自愈</b>（{@link #modelsChanged} 比 {@link BakedModel} 实例 —— 重载后
     * {@code Minecraft.reloadResourcePacks()} 会 {@code new ModelManager(...)}，所以模型实例必变），
     * 这一条只是把"作废"提前到重载那一刻，别让作废与首次重烘之间的那一帧还挂着旧 UV。
     * <p>⚠️ 与 {@link PrimordialOmegaEngineRingBuffer} 不同，这里<b>没有</b>"一辈子只烘一次"的坑
     * —— 修 2026-10-04 那个贴图 bug 的关键在环形那条路上（它才是一直用旧 UV 的那一个）。
     *
     * <p>只在渲染线程真正 close；非渲染线程只丢引用（资源重载的 apply 跑在渲染线程）。
     */
    static void invalidate() {
        if (!RenderSystem.isOnRenderThread()) {
            buffers = null;
            bakedModels = null;
            return;
        }
        closeBuffers();
        bakedModels = null;
    }

    static void beginRender() {
        RenderType.solid().setupRenderState();
        RenderSystem.setShader(GameRenderer::getRendertypeSolidShader);
        RenderSystem.setShaderTexture(0, net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
    }

    static void draw(VertexBuffer buffer, PoseStack poseStack) {
        if (buffer == null) return;
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;
        buffer.bind();
        buffer.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);
        VertexBuffer.unbind();
    }

    static void endRender() {
        VertexBuffer.unbind();
        RenderType.solid().clearRenderState();
    }

    private static BakedModel[] readCurrentModels() {
        BakedModel[] models = new BakedModel[MODEL_IDS.size()];
        for (int i = 0; i < MODEL_IDS.size(); i++) {
            models[i] = ClientUtil.getBakedModel(MODEL_IDS.get(i));
            if (models[i] == null) return null;
        }
        return models;
    }

    private static boolean modelsChanged(BakedModel[] currentModels) {
        if (currentModels.length != bakedModels.length) return true;
        for (int i = 0; i < currentModels.length; i++) {
            if (currentModels[i] != bakedModels[i]) return true;
        }
        return false;
    }

    private static void rebuild(BakedModel[] currentModels) {
        closeBuffers();
        VertexBuffer[] rebuilt = new VertexBuffer[currentModels.length];
        try {
            for (int i = 0; i < currentModels.length; i++) {
                rebuilt[i] = buildBuffer(currentModels[i]);
            }
            bakedModels = currentModels;
            buffers = rebuilt;
        } catch (RuntimeException exception) {
            for (VertexBuffer buffer : rebuilt) {
                if (buffer != null) buffer.close();
            }
            bakedModels = null;
            buffers = null;
            ShanhaiMod.LOGGER.warn("鸿蒙微型宇宙模型 VBO 构建失败，将在下一帧重试", exception);
        }
    }

    private static VertexBuffer buildBuffer(BakedModel model) {
        BufferBuilder builder = new BufferBuilder(1 << 20);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

        PoseStack poseStack = new PoseStack();
        ClientUtil.modelRenderer().renderModel(
                poseStack.last(),
                builder,
                (BlockState) null,
                model,
                1.0f, 1.0f, 1.0f,
                15728880,
                OverlayTexture.NO_OVERLAY,
                ModelData.EMPTY,
                RenderType.solid()
        );

        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        return buffer;
    }

    private static void closeBuffers() {
        if (buffers == null) return;
        for (VertexBuffer buffer : buffers) {
            if (buffer != null) buffer.close();
        }
        buffers = null;
    }
}
