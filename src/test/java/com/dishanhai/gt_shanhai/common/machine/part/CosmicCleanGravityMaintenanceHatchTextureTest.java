package com.dishanhai.gt_shanhai.common.machine.part;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmicCleanGravityMaintenanceHatchTextureTest {

    private static final String NAME = "cosmic_clean_gravity_maintenance_hatch";
    private static final Path TEXTURE_DIR = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "textures", "block", "machine", "part");
    private static final Path MODEL = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "models", "block", "machine", "part", NAME + ".json");
    private static final Path OVERLAY = TEXTURE_DIR.resolve(NAME + ".png");
    private static final Path EMISSIVE = TEXTURE_DIR.resolve(NAME + "_emissive.png");

    @Test
    void modelReferencesSynchronizedOverlayAndEmissiveAnimations() throws IOException {
        assertTrue(Files.exists(MODEL), "缺少寰宇维护仓覆盖层模型");
        JsonObject model = JsonParser.parseString(Files.readString(MODEL)).getAsJsonObject();
        JsonObject textures = model.getAsJsonObject("textures");

        assertEquals("gtceu:block/overlay/front_emissive", model.get("parent").getAsString());
        assertEquals("gt_shanhai:block/machine/part/" + NAME, textures.get("overlay").getAsString());
        assertEquals("gt_shanhai:block/machine/part/" + NAME + "_emissive",
                textures.get("overlay_emissive").getAsString());

        assertAnimationMetadata(OVERLAY.resolveSibling(OVERLAY.getFileName() + ".mcmeta"));
        assertAnimationMetadata(EMISSIVE.resolveSibling(EMISSIVE.getFileName() + ".mcmeta"));
    }

    @Test
    void bothTextureStripsContainThirtyTwoDistinctSixteenPixelFrames() throws IOException {
        BufferedImage overlay = readTexture(OVERLAY);
        BufferedImage emissive = readTexture(EMISSIVE);

        assertTextureStrip(overlay);
        assertTextureStrip(emissive);
        assertNotEquals(frameHash(overlay, 0), frameHash(emissive, 0));
        assertTrue(countColoredPixels(emissive, true) > 180, "发光层必须包含青色无重力像素");
        assertTrue(countColoredPixels(emissive, false) > 180, "发光层必须包含紫色强重力像素");
    }

    private static void assertAnimationMetadata(Path path) throws IOException {
        assertTrue(Files.exists(path), "缺少动画元数据: " + path);
        JsonObject animation = JsonParser.parseString(Files.readString(path))
                .getAsJsonObject().getAsJsonObject("animation");
        assertEquals(2, animation.get("frametime").getAsInt());
        assertEquals(false, animation.get("interpolate").getAsBoolean());
    }

    private static BufferedImage readTexture(Path path) throws IOException {
        assertTrue(Files.exists(path), "缺少动画材质: " + path);
        BufferedImage image = ImageIO.read(path.toFile());
        assertTrue(image != null, "无法解析 PNG: " + path);
        return image;
    }

    private static void assertTextureStrip(BufferedImage image) {
        assertEquals(16, image.getWidth());
        assertEquals(16 * 32, image.getHeight());
        Set<Long> hashes = new HashSet<>();
        for (int frame = 0; frame < 32; frame++) {
            hashes.add(frameHash(image, frame));
        }
        assertTrue(hashes.size() >= 28, "32 帧中至少 28 帧必须具有独立像素状态");
    }

    private static long frameHash(BufferedImage image, int frame) {
        long hash = 0xcbf29ce484222325L;
        int yOffset = frame * 16;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                hash ^= image.getRGB(x, yOffset + y);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static int countColoredPixels(BufferedImage image, boolean cyan) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) == 0) continue;
                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                if (cyan ? (g > r + 20 && b > r + 20) : (r > g + 20 && b > g)) count++;
            }
        }
        return count;
    }
}
