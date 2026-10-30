package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuNebulaShaderTest {
    private final Path core = Path.of("src/main/resources/assets/gt_shanhai/shaders/core");

    @Test void dynamicMaterialHasARealTimeUniformAndBoundedCounterFlow() throws Exception {
        var json = JsonParser.parseString(Files.readString(core.resolve("guixu_nebula.json"))).getAsJsonObject();
        var uniforms = json.getAsJsonArray("uniforms");
        boolean phase = false, detail = false;
        for (var entry : uniforms) {
            String name = entry.getAsJsonObject().get("name").getAsString();
            phase |= name.equals("NebulaPhase");
            detail |= name.equals("NebulaDetail");
        }
        assertTrue(phase && detail);
        String shader = Files.readString(core.resolve("guixu_nebula.fsh"));
        assertTrue(shader.contains("uniform float NebulaPhase"));
        assertTrue(shader.contains("counterFlow"));
        assertTrue(shader.contains("mirrorFlow"), "Flow must not reveal a seam in the non-tiled source texture");
        assertFalse(shader.contains("for ("), "Keep fragment work bounded without ray marching or fBm loops");
    }

    @Test void shaderAttributesMatchAnAlignedVertexFormatIncludingSmoothNormals() throws Exception {
        var format = GuixuNebulaShader.FORMAT;
        assertEquals(0, format.getVertexSize() % 4, "Normal bytes need padding to preserve the next vertex alignment");
        var json = JsonParser.parseString(Files.readString(core.resolve("guixu_nebula.json"))).getAsJsonObject();
        for (var attribute : json.getAsJsonArray("attributes")) {
            assertTrue(format.getElementAttributeNames().contains(attribute.getAsString()));
        }
        assertTrue(format.getElements().contains(com.mojang.blaze3d.vertex.DefaultVertexFormat.ELEMENT_NORMAL));
    }
}
