// Author: dishanhai
package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuixuStaticMeshSourceTest {
    private static final Path SOURCE = Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/hostfx");

    @Test void ringAndDecompressorsDrawCachedBuffers() throws Exception {
        String draw = Files.readString(SOURCE.resolve("HostFxDraw.java"));
        assertCachedDraw(method(draw, "int drawRingExtras("), "ringExtras");
        assertCachedDraw(method(draw, "int drawMatterDecompressors("), "decompressors");
    }

    @Test void resourceReloadReleasesTheStaticGeometry() throws Exception {
        String draw = Files.readString(SOURCE.resolve("HostFxDraw.java"));
        String reset = method(draw, "void releaseStaticMeshes(");
        assertTrue(reset.contains("ringExtras.close()"));
        assertTrue(reset.contains("decompressors.close()"));
        assertTrue(reset.contains("ringExtras = null"));
        assertTrue(reset.contains("decompressors = null"));
        String reload = Files.readString(SOURCE.resolve("HostFxPillarBeamReloadHook.java"));
        assertTrue(reload.contains("HostFxDraw.releaseStaticMeshes()"));
    }

    private static void assertCachedDraw(String body, String mesh) {
        assertFalse(body.contains("MultiBufferSource.immediate"), "static geometry must not upload every frame");
        assertTrue(body.contains("if (" + mesh + " == null)"));
        assertTrue(body.contains(mesh + ".render(poseStack)"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        return source.substring(start, source.indexOf("\n    }", start));
    }
}
