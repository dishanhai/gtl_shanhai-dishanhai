package com.dishanhai.gt_shanhai.client.renderer.machine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialOmegaEngineRendererPerformanceTest {

    private static final Path RENDERER_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "renderer", "machine", "PrimordialOmegaEngineRenderer.java");
    private static final Path BUFFER_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "renderer", "machine", "PrimordialOmegaEngineModelBuffers.java");

    @Test
    void renderHotPathDrawsCachedVertexBuffersInsteadOfRetessellatingModels() throws Exception {
        String renderer = Files.readString(RENDERER_SOURCE);

        assertTrue(renderer.contains("PrimordialOmegaEngineModelBuffers.getBuffers()"));
        assertTrue(renderer.contains("PrimordialOmegaEngineModelBuffers.beginRender()"));
        assertTrue(renderer.contains("PrimordialOmegaEngineModelBuffers.draw("));
        assertTrue(renderer.contains("PrimordialOmegaEngineModelBuffers.endRender()"));
        assertFalse(renderer.contains(".renderModel("));
        assertFalse(renderer.contains("ClientUtil.getBakedModel"));
    }

    @Test
    void modelBufferCacheRebuildsAfterModelReloadAndClosesOldGpuBuffers() throws Exception {
        String cache = Files.readString(BUFFER_SOURCE);

        assertTrue(cache.contains("new VertexBuffer(VertexBuffer.Usage.STATIC)"));
        assertTrue(cache.contains("ClientUtil.getBakedModel(MODEL_IDS.get(i))"));
        assertTrue(cache.contains("currentModels[i] != bakedModels[i]"));
        assertTrue(cache.contains("buffer.close()"));
        assertTrue(cache.contains("buffer.drawWithShader("));
        assertTrue(cache.contains("RenderType.solid().setupRenderState()"));
        assertTrue(cache.contains("RenderType.solid().clearRenderState()"));
    }
}
