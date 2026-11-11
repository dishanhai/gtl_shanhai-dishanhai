package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.gui.scaled.ThemeEffects;
import com.dishanhai.sh_better_ui.config.ThemeOverrides;
import com.dishanhai.sh_better_ui.config.ThemeSettings;
import com.dishanhai.sh_better_ui.theme.ThemeId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Objects;
import java.util.regex.Pattern;

/** 山海：商店独立的视觉配置，效果参数使用 SH-Better-UI 的分组协议。 */
public record ShopAppearance(String backgroundId, String materialId, ThemeOverrides effects) {
    private static final Pattern LEGACY_ID = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");
    public static final String NO_BACKGROUND = "none";
    public static final ShopAppearance DEFAULT = new ShopAppearance(Background.NONE, Material.PRESET, ThemeOverrides.EMPTY);

    public enum Background {
        NONE("none"), DOT_MATRIX("dotMatrix"), ANTIGRAVITY("antigravity"), LIGHT_RAYS("lightRays"),
        LASER_FLOW("laserFlow"), CODE_RAIN("codeRain"), ASCII("ascii"), PATTERN_WAVES("patternWaves"),
        SHAPE_WAVES("shapeWaves"), ELECTRIC_LOGO("electricLogo"), REVEAL("reveal");

        public final String id;

        Background(String id) {
            this.id = id;
        }
    }

    public enum Material {
        PRESET("preset"), CLASSIC("classic"), CLEAR_GLASS("clearGlass"), SMOKE_GLASS("smokeGlass"),
        SLATE("slate"), LIQUID_GLASS("liquidGlass"), NETWORK("network"),
        AURORA("aurora"), AMETHYST("amethyst"), ROSE("rose"), EMBER("ember");

        public final String id;

        Material(String id) {
            this.id = id;
        }
    }

    public ShopAppearance {
        if (!validSharedId(backgroundId)) throw new IllegalArgumentException("Invalid background id");
        if (!validMaterialId(materialId)) throw new IllegalArgumentException("Invalid material id");
        Objects.requireNonNull(effects);
    }

    public ShopAppearance(Background background, Material material, ThemeOverrides effects) {
        this(background.id, material.id, effects);
    }

    /** 旧调用方使用的内置背景视图。新调用方应使用注册 ID。 */
    public Background background() {
        for (Background value : Background.values()) {
            if (value.id.equals(backgroundId)) return value;
        }
        return Background.NONE;
    }

    /** 旧调用方使用的内置材质视图。新调用方应使用注册 ID。 */
    public Material material() {
        for (Material value : Material.values()) {
            if (value.id.equals(materialId)) return value;
        }
        return Material.PRESET;
    }

    public boolean hasBackground() {
        return !NO_BACKGROUND.equals(backgroundId) && ShBetterUiApi.backgrounds().find(backgroundId).isPresent();
    }

    public ShopAppearance withBackground(Background value) {
        return withBackground(value.id);
    }

    public ShopAppearance withBackground(String id) {
        return new ShopAppearance(id, materialId, effects);
    }

    public ShopAppearance withMaterial(Material value) {
        return withMaterial(value.id);
    }

    public ShopAppearance withMaterial(String id) {
        return new ShopAppearance(backgroundId, id, effects);
    }

    public ShopAppearance withGroup(String group, JsonObject values) {
        ThemeEffects.validate(group, inheritedEffectGroup(group), values);
        return new ShopAppearance(backgroundId, materialId, effects.withGroup(group, values));
    }

    public static JsonObject inheritedEffectGroup(String group) {
        var global = ShBetterUiApi.settings().theme();
        return global.mode() == ThemeSettings.Mode.CUSTOM ? global.effects().group(group) : new JsonObject();
    }

    public ThemeSettings theme() {
        return ThemeSettings.DEFAULT.withMode(ThemeSettings.Mode.CUSTOM).withEffects(effects);
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("background", backgroundId);
        result.addProperty("material", materialId);
        result.add("effects", JsonParser.parseString(effects.json()));
        return result;
    }

    public static ShopAppearance fromJson(JsonElement value) {
        if (value == null || !value.isJsonObject()) return DEFAULT;
        JsonObject json = value.getAsJsonObject();
        String backgroundId = readId(json.get("background"));
        String materialId = readId(json.get("material"));
        if (!validMaterialId(materialId)) materialId = Material.PRESET.id;
        if (!validSharedId(backgroundId)) backgroundId = NO_BACKGROUND;
        ShopAppearance appearance = new ShopAppearance(backgroundId, materialId, ThemeOverrides.EMPTY);
        JsonElement groups = json.get("effects");
        if (groups != null && groups.isJsonObject()) {
            for (var group : groups.getAsJsonObject().entrySet()) {
                if (!group.getValue().isJsonObject() || !validSharedId(group.getKey())) continue;
                try {
                    String id = group.getKey();
                    JsonObject values = group.getValue().getAsJsonObject();
                    // 插件可以晚于配置加载注册其参数；缺少定义时保留草稿原值。
                    appearance = ThemeEffects.groups().contains(id)
                            ? appearance.withGroup(id, values)
                            : new ShopAppearance(backgroundId, materialId, appearance.effects.withGroup(id, values));
                } catch (IllegalArgumentException ignored) {
                    // 损坏的分组使用默认值，保留其他有效分组。
                }
            }
        }
        return appearance;
    }

    private static boolean validSharedId(String id) {
        if (id == null) return false;
        if (!id.contains(":")) return LEGACY_ID.matcher(id).matches();
        try {
            ThemeId.require(id);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean validMaterialId(String id) {
        return Material.PRESET.id.equals(id) || Material.CLEAR_GLASS.id.equals(id) || Material.SMOKE_GLASS.id.equals(id)
                || ClientShopUiSettings.validThemeId(id);
    }

    private static String readId(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }
}
