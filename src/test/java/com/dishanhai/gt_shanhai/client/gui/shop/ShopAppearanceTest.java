package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import com.dishanhai.sh_better_ui.client.api.BackgroundSpec;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.gui.scaled.DotMatrixProfile;
import com.dishanhai.sh_better_ui.client.gui.scaled.ThemeEffects;
import com.dishanhai.sh_better_ui.config.ThemeSettings;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：主题草稿、旧配置和错误分组的兼容性。 */
public class ShopAppearanceTest {
    public record CustomBackgroundProfile(int density) {}

    @Test
    void oldAndMalformedAppearanceUseDefaults() {
        assertEquals(ShopAppearance.DEFAULT, ShopAppearance.fromJson(null));
        assertEquals(ShopAppearance.DEFAULT, ShopAppearance.fromJson(JsonParser.parseString("[]")));
        assertEquals(ShopAppearance.DEFAULT, ShopAppearance.fromJson(JsonParser.parseString(
                """
                {"background":"not a valid id","material":false,"effects":{"unknown":{},"dotMatrix":null}}
                """)));
    }

    @Test
    void keepsProviderSettingsWhenTheProviderHasNotRegisteredYet() {
        String id = "example:custom_background";
        var defaults = new CustomBackgroundProfile(5);
        var saved = JsonParser.parseString("""
                {"background":"example:custom_background","material":"preset",
                 "effects":{"example:custom_background":{"density":17}}}
                """);
        ShopAppearance restored = ShopAppearance.fromJson(saved);
        assertEquals(saved, restored.toJson());
        assertFalse(restored.hasBackground());

        var spec = new BackgroundSpec(id, "example.background.custom", defaults,
                logo -> (graphics, font, area, mouse, x, y, now, preferences) -> {});
        try (var registration = ShBetterUiApi.backgrounds().register(spec)) {
            assertTrue(restored.hasBackground());
            ShopVisuals.scoped(restored, false, () -> assertEquals(17, ThemeEffects.profile(id, defaults).density()));
        }
        assertFalse(restored.hasBackground());
        assertEquals(saved, restored.toJson());
    }

    @Test
    void allBackgroundAndMaterialChoicesRoundTripByTheirStableIds() {
        for (var background : ShopAppearance.Background.values()) {
            for (var material : ShopAppearance.Material.values()) {
                var appearance = ShopAppearance.DEFAULT.withBackground(background).withMaterial(material);
                assertEquals(appearance, ShopAppearance.fromJson(appearance.toJson()));
            }
        }
    }

    @Test
    void invalidGroupsDoNotRemoveOtherOverrides() {
        var restored = ShopAppearance.fromJson(JsonParser.parseString(
                """
                {"background":"dotMatrix","material":"smokeGlass","effects":{
                  "dotMatrix":{"grid":{"spacing":12}},
                  "lightRays":{"unexpected":3},
                  "flowBorder":{"color":"#CAAAFF","speed":1.5}
                }}
                """));

        assertEquals(ShopAppearance.Background.DOT_MATRIX, restored.background());
        assertEquals(ShopAppearance.Material.SMOKE_GLASS, restored.material());
        assertFalse(restored.effects().contains("lightRays"));
        assertTrue(restored.effects().contains("flowBorder"));
        assertEquals(12, ThemeEffects.profile(restored.theme(), "dotMatrix", DotMatrixProfile.VIOLET).grid().spacing());
        assertEquals(restored, ShopAppearance.fromJson(restored.toJson()));
    }

    @Test
    void everyLibraryParameterTemplateCanBeStagedAndReset() {
        for (String group : ThemeEffects.groups()) {
            var draft = ShopAppearance.DEFAULT.withGroup(group, ThemeEffects.template(group));
            assertTrue(draft.effects().contains(group), group);
            assertEquals(ShopAppearance.DEFAULT, draft.withGroup(group, new com.google.gson.JsonObject()), group);
        }
    }

    @Test
    void previewScopeRestoresGlobalThemeEvenWhenRenderingFails() {
        ThemeSettings global = ThemeEffects.settings().theme();
        var draft = ShopAppearance.DEFAULT.withGroup("flowBorder", ThemeEffects.template("flowBorder"));
        assertThrows(IllegalStateException.class, () -> ShopVisuals.scoped(draft, true, () -> {
            assertEquals(draft.theme(), ThemeEffects.settings().theme());
            assertTrue(ThemeEffects.settings().reducedMotion());
            throw new IllegalStateException();
        }));
        assertEquals(global, ThemeEffects.settings().theme());
    }
}
