package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.function.Consumer;

final class StarLifterMeshUpload {
    // BufferBuilder owns native memory without a close method in 1.20.1. Reuse one staging buffer across reloads.
    private static BufferBuilder staging;

    private StarLifterMeshUpload() {}

    static VertexBuffer upload(VertexFormat format, Consumer<BufferBuilder> emit) {
        RenderSystem.assertOnRenderThread();
        if (staging == null) staging = new BufferBuilder(1 << 18);
        staging.begin(VertexFormat.Mode.QUADS, format);
        try {
            emit.accept(staging);
            var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            try {
                buffer.upload(staging.end());
                return buffer;
            } catch (RuntimeException exception) {
                buffer.close();
                throw exception;
            } finally {
                VertexBuffer.unbind();
            }
        } finally {
            if (staging.building()) {
                var remaining = staging.endOrDiscardIfEmpty();
                if (remaining != null) remaining.release();
            }
        }
    }
}
