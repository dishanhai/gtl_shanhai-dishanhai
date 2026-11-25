package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/** Holds the selected block's original line vertices until the world lens has finished sampling. */
final class KerrBlockOutline {
    private final BufferBuilder lines = new BufferBuilder(256);
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private VertexSorting sorting;
    private Level level;
    private int frame;

    VertexConsumer capture(int serial, Level world) {
        if (level != null && (frame != serial || level != world)) discard();
        if (level == null) {
            var type = RenderType.lines();
            lines.begin(type.mode(), type.format());
            projection.set(RenderSystem.getProjectionMatrix());
            modelView.set(RenderSystem.getModelViewMatrix());
            sorting = RenderSystem.getVertexSorting();
            level = world;
            frame = serial;
        }
        return lines;
    }

    void flush(int serial) {
        if (level == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (frame != serial || level != mc.level) {
            discard();
            return;
        }
        level = null;
        var mesh = lines.endOrDiscardIfEmpty();
        if (mesh == null) return;
        var type = RenderType.lines();
        var stack = RenderSystem.getModelViewStack();
        var oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var oldSorting = RenderSystem.getVertexSorting();
        stack.pushPose();
        try {
            stack.last().pose().set(modelView);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            type.setupRenderState();
            try {
                // Fabulous normally targets an intermediate buffer that has already been composited.
                mc.getMainRenderTarget().bindWrite(false);
                BufferUploader.drawWithShader(mesh);
            } finally {
                type.clearRenderState();
            }
        } finally {
            stack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
        }
    }

    void discard() {
        if (level == null) return;
        var mesh = lines.endOrDiscardIfEmpty();
        if (mesh != null) mesh.release();
        level = null;
    }
}
