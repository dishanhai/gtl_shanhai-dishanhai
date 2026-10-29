package com.dishanhai.gt_shanhai.client.renderer.cover;

import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.client.util.StaticFaceBakery;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：覆蓋板置於機殼與工作覆層之間，主機 overlay 必須優先顯示。 */
class ShanhaiAeSteamCoverRendererTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/gt_shanhai");

    @Test
    void allSixCoverFacesSitBetweenMachineCasingAndNativeWorkableOverlay() throws Exception {
        AABB bounds = (AABB) Class.forName("com.dishanhai.gt_shanhai.client.renderer.cover.ShanhaiAeSteamCoverRenderer")
                .getField("SURFACE_BOUNDS").get(null);
        // 與實際 GTCEu 工作／發光覆層使用的邊界比較，不能再放到其外側。
        AABB overlay = StaticFaceBakery.SLIGHTLY_OVER_BLOCK;
        assertTrue(bounds.minX < 0 && bounds.minY < 0 && bounds.minZ < 0,
                "覆蓋板須在機殼外側，避免與機殼共面");
        assertTrue(bounds.maxX > 1 && bounds.maxY > 1 && bounds.maxZ > 1);
        assertTrue(bounds.minX > overlay.minX && bounds.minY > overlay.minY && bounds.minZ > overlay.minZ,
                "負向三面須在原生 overlay 內側，避免遮住主機圖案");
        assertTrue(bounds.maxX < overlay.maxX && bounds.maxY < overlay.maxY && bounds.maxZ < overlay.maxZ,
                "正向三面須在原生 overlay 內側，避免遮住主機圖案");
        assertTrue((float) bounds.minX > (float) overlay.minX && (float) bounds.maxX < (float) overlay.maxX,
                "轉成渲染浮點頂點後仍須保留層間距");
    }

    @Test
    void coverAlsoSitsBehindTheCloserNativeSteamHatchOverlay() throws Exception {
        AABB bounds = (AABB) Class.forName("com.dishanhai.gt_shanhai.client.renderer.cover.ShanhaiAeSteamCoverRenderer")
                .getField("SURFACE_BOUNDS").get(null);
        try (var input = getClass().getResourceAsStream("/assets/gtceu/models/block/overlay/front.json")) {
            assertNotNull(input);
            var nativeModel = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            // 蒸汽倉使用此 front 模型，JSON 座標為 0..16，須換算為方塊座標。
            double offset = -nativeModel.getAsJsonArray("elements").get(0).getAsJsonObject()
                    .getAsJsonArray("from").get(2).getAsDouble() / 16.0;
            assertTrue(offset > 0);
            assertTrue(bounds.minX > -offset && bounds.minY > -offset && bounds.minZ > -offset,
                    "六面旋轉後亦須在蒸汽倉 overlay 內側");
            assertTrue(bounds.maxX < 1 + offset && bounds.maxY < 1 + offset && bounds.maxZ < 1 + offset);
        }
    }

    @Test
    void coverAndBinderUseIndependentItemTexturesAndOwnBlockOverlay() throws Exception {
        for (String item : new String[]{"ae_steam_cover", "ae_steam_binder"}) {
            var model = JsonParser.parseString(Files.readString(ASSETS.resolve("models/item/" + item + ".json")))
                    .getAsJsonObject();
            assertEquals("gt_shanhai:item/" + item, model.getAsJsonObject("textures").get("layer0").getAsString());
            assertTexture("item/" + item);
        }
        assertTexture("block/cover/ae_steam_cover");
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/cover/ShanhaiAeSteamCover.java"));
        assertTrue(source.contains("new ShanhaiAeSteamCoverRenderer()"));
        assertFalse(source.contains("overlay_fluid_filter"));
    }

    private void assertTexture(String path) throws Exception {
        Path texture = ASSETS.resolve("textures/" + path + ".png");
        assertTrue(Files.exists(texture), texture.toString());
        var image = ImageIO.read(texture.toFile());
        assertNotNull(image);
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
        assertEquals(0, image.getRGB(0, 0) >>> 24, "外框之外須透明以保留機器外觀");
        assertNotEquals(0, image.getRGB(8, 8) >>> 24, "中心裝置須可見");
    }
}
