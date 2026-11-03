package com.dishanhai.gt_shanhai.common.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：奇蹟物質與奇蹟之流的像素動畫資源契約。 */
class ShanhaiMiracleAssetsTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/gt_shanhai");

    @Test
    void matterModelUsesTransparentSixteenPixelAnimation() throws IOException {
        JsonObject model = readJson(ASSETS.resolve("models/item/miracle_matter.json"));
        assertEquals("minecraft:item/generated", model.get("parent").getAsString());
        assertEquals("gt_shanhai:item/miracle_matter", model.getAsJsonObject("textures").get("layer0").getAsString());
        assertAnimation("item/miracle_matter", false);
    }

    @Test
    void fluidSpriteLivesInBlockAtlasAndHasOpaqueAnimatedFrames() throws IOException {
        assertAnimation("block/fluid/miracle_flow", true);
    }

    @Test
    void fluidBucketUsesTheAnimatedFluidContainerModel() throws IOException {
        JsonObject model = readJson(ASSETS.resolve("models/item/miracle_flow_bucket.json"));
        assertEquals("forge:item/bucket", model.get("parent").getAsString());
        assertEquals("forge:fluid_container", model.get("loader").getAsString());
        assertEquals("gt_shanhai:miracle_flow", model.get("fluid").getAsString());
        JsonObject chinese = readJson(ASSETS.resolve("lang/zh_cn.json"));
        JsonObject english = readJson(ASSETS.resolve("lang/en_us.json"));
        assertNotNull(chinese.get("item.gt_shanhai.miracle_flow_bucket"));
        assertNotNull(english.get("item.gt_shanhai.miracle_flow_bucket"));
        assertEquals("奇迹之流桶", chinese.get("item.gt_shanhai.miracle_flow_bucket").getAsString());
        assertEquals("Miracle Flow Bucket", english.get("item.gt_shanhai.miracle_flow_bucket").getAsString());
    }

    @Test
    void bothRegistryNamesHaveChineseAndEnglishTranslations() throws IOException {
        JsonObject chinese = readJson(ASSETS.resolve("lang/zh_cn.json"));
        JsonObject english = readJson(ASSETS.resolve("lang/en_us.json"));
        assertNotNull(chinese.get("item.gt_shanhai.miracle_matter"));
        assertNotNull(chinese.get("fluid.gt_shanhai.miracle_flow"));
        assertEquals("奇迹物质", chinese.get("item.gt_shanhai.miracle_matter").getAsString());
        assertEquals("奇迹之流", chinese.get("fluid.gt_shanhai.miracle_flow").getAsString());
        assertEquals("Miracle Matter", english.get("item.gt_shanhai.miracle_matter").getAsString());
        assertEquals("Miracle Flow", english.get("fluid.gt_shanhai.miracle_flow").getAsString());
    }

    private static void assertAnimation(String texture, boolean opaque) throws IOException {
        Path png = ASSETS.resolve("textures/" + texture + ".png");
        assertTrue(Files.isRegularFile(png), "Missing texture: " + png);
        var image = ImageIO.read(png.toFile());
        assertNotNull(image);
        assertEquals(16, image.getWidth());
        assertEquals(16 * 16, image.getHeight());
        JsonObject animation = readJson(png.resolveSibling(png.getFileName() + ".mcmeta")).getAsJsonObject("animation");
        assertEquals(16, animation.get("width").getAsInt());
        assertEquals(16, animation.get("height").getAsInt());
        assertEquals(2, animation.get("frametime").getAsInt());
        assertFalse(animation.get("interpolate").getAsBoolean(), "Keep hard pixel edges");
        var distinctFrames = new HashSet<String>();
        for (int frame = 0; frame < 16; frame++) {
            int[] pixels = image.getRGB(0, frame * 16, 16, 16, null, 0, 16);
            distinctFrames.add(Arrays.toString(pixels));
            int visible = 0;
            for (int pixel : pixels) {
                int alpha = pixel >>> 24;
                assertTrue(alpha == 0 || alpha == 255, "Pixels must not contain blurred alpha fringes");
                if (alpha == 255) visible++;
            }
            if (opaque) assertEquals(256, visible, "Fluid must fill each tile");
            else assertTrue(visible > 0 && visible < 256, "Item needs a visible silhouette and a transparent background");
        }
        assertTrue(distinctFrames.size() >= 8, "Animation needs visibly changing frames");
    }

    private static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
