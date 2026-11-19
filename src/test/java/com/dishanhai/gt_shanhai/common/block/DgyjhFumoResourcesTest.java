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
    private static final String HOLDING_MODEL = BLOCK_MODEL + "_holding";

    @Test
    void usesDishanhaiGeometryInBothPosesWithItsOwnSkin() throws IOException {
        var blockModel = json("models/block/dgyjh02804_fumo.json");
        assertEquals("sh_common_lib:block/dishanhai_fumo", blockModel.get("parent").getAsString());
        assertEquals(BLOCK_MODEL, blockModel.getAsJsonObject("textures").get("skin").getAsString());
        assertEquals(BLOCK_MODEL, json("models/item/dgyjh02804_fumo.json").get("parent").getAsString());

        var holdingModel = json("models/block/dgyjh02804_fumo_holding.json");
        assertEquals("sh_common_lib:block/dishanhai_fumo_holding", holdingModel.get("parent").getAsString());
        assertEquals(BLOCK_MODEL, holdingModel.getAsJsonObject("textures").get("skin").getAsString());

        var variants = json("blockstates/dgyjh02804_fumo.json").getAsJsonObject("variants");
        int rotation = 0;
        for (var facing : new String[]{"north", "east", "south", "west"}) {
            assertVariant(variants, facing, false, BLOCK_MODEL, rotation);
            assertVariant(variants, facing, true, HOLDING_MODEL, rotation);
            rotation += 90;
        }

        try (var stream = resource("textures/block/dgyjh02804_fumo.png")) {
            var skin = ImageIO.read(stream);
            assertNotNull(skin);
            assertEquals(64, skin.getWidth());
            assertEquals(64, skin.getHeight());
            assertTrue(skin.getColorModel().hasAlpha());
        }
    }

    private static void assertVariant(JsonObject variants, String facing, boolean holding, String model, int rotation) {
        var variant = variants.getAsJsonObject("facing=" + facing + ",holding=" + holding);
        assertNotNull(variant);
        assertEquals(model, variant.get("model").getAsString());
        assertEquals(rotation, variant.has("y") ? variant.get("y").getAsInt() : 0);
    }

    @Test
    void canOccupyCuriosHeadSlot() throws IOException {
        var stream = DgyjhFumoResourcesTest.class.getResourceAsStream("/data/curios/tags/items/head.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var tag = JsonParser.parseReader(reader).getAsJsonObject();
            assertTrue(tag.getAsJsonArray("values").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("gt_shanhai:dgyjh02804_fumo")));
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
