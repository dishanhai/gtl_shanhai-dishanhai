package com.dishanhai.gt_shanhai.client.renderer.cover;

import com.google.gson.JsonParser;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：覆蓋板須避開原機器表面與工作覆層，物品和方塊貼圖各自解析。 */
class ShanhaiAeSteamCoverRendererTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/gt_shanhai");

    @Test
    void allSixCoverFacesSitOutsideMachineAndNativeWorkableOverlay() throws Exception {
        AABB bounds = (AABB) Class.forName("com.dishanhai.gt_shanhai.client.renderer.cover.ShanhaiAeSteamCoverRenderer")
                .getField("SURFACE_BOUNDS").get(null);
        // 原生 WorkableOverlayModel 使用 ±0.001；六面都必須與之分離。
        assertTrue(bounds.minX < -0.001 && bounds.minY < -0.001 && bounds.minZ < -0.001);
        assertTrue(bounds.maxX > 1.001 && bounds.maxY > 1.001 && bounds.maxZ > 1.001);
        assertTrue(bounds.maxX - bounds.minX < 1.01, "只微移，不能明顯改變方塊尺寸");
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
        assertEquals(32, image.getWidth());
        assertEquals(32, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
        assertEquals(0, image.getRGB(0, 0) >>> 24, "外框之外須透明以保留機器外觀");
        assertNotEquals(0, image.getRGB(16, 16) >>> 24, "中心裝置須可見");
    }
}
