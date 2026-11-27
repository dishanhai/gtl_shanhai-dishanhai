package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AetherophasicPlasmaShaderTest {
    @Test
    void shaderProgramsMaintainTheirBlendModesWhenMinecraftAppliesThem() throws IOException {
        var surface = program("aetherophasic_surface");
        var corona = program("aetherophasic_plasma");
        assertBlend(surface, "1-srcalpha");
        assertBlend(corona, "1");
        assertEquals(surface.get("vertex"), corona.get("vertex"));
        assertEquals(surface.get("fragment"), corona.get("fragment"));
        assertEquals(surface.get("uniforms"), corona.get("uniforms"));
    }

    private static JsonObject program(String name) throws IOException {
        String path = "/assets/gt_shanhai/shaders/core/" + name + ".json";
        try (var input = AetherophasicPlasmaShaderTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void assertBlend(JsonObject program, String destination) {
        var blend = program.getAsJsonObject("blend");
        assertNotNull(blend, "ShaderInstance applies opaque blending when the blend object is absent");
        assertEquals("add", blend.get("func").getAsString());
        assertEquals("srcalpha", blend.get("srcrgb").getAsString());
        assertEquals(destination, blend.get("dstrgb").getAsString());
    }
}
