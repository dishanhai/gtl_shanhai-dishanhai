// Author: dishanhai
package com.dishanhai.gt_shanhai.common.block;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DgyjhFumoResourcesTest {
    private static final String ASSETS = "/assets/gt_shanhai/";
    private static final String BLOCK_MODEL = "gt_shanhai:block/dgyjh02804_fumo";

    @Test
    void usesShCommonLibSeatedSkinTemplateForBlockAndItem() throws IOException {
        var blockModel = json("models/block/dgyjh02804_fumo.json");
        assertEquals("sh_common_lib:block/fumo/skin_64", blockModel.get("parent").getAsString());
        assertEquals(BLOCK_MODEL, blockModel.getAsJsonObject("textures").get("skin").getAsString());
        assertEquals(BLOCK_MODEL, json("models/item/dgyjh02804_fumo.json").get("parent").getAsString());

        var variants = json("blockstates/dgyjh02804_fumo.json").getAsJsonObject("variants");
        for (var facing : new String[]{"north", "east", "south", "west"}) {
            assertEquals(BLOCK_MODEL, variants.getAsJsonObject("facing=" + facing).get("model").getAsString());
        }

        try (var stream = resource("textures/block/dgyjh02804_fumo.png")) {
            var skin = ImageIO.read(stream);
            assertNotNull(skin);
            assertEquals(64, skin.getWidth());
            assertEquals(64, skin.getHeight());
            assertTrue(skin.getColorModel().hasAlpha());
        }
    }

    private static JsonObject json(String path) throws IOException {
        try (var reader = new InputStreamReader(resource(path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static InputStream resource(String path) {
        var stream = DgyjhFumoResourcesTest.class.getResourceAsStream(ASSETS + path);
        assertNotNull(stream, path);
        return stream;
    }
}
