package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.api.MaterialsApi.ThemeChoice;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.glass.GlassTheme;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 山海：商店只负责本机主题选择，共用绘制由独立 SH-Better-UI 库提供。 */
public final class ShopTheme extends GlassTheme {
    private static final ThreadLocal<ShopTheme> PREVIEW = new ThreadLocal<>();
    private static final Map<GlassTheme, ShopTheme> ADAPTED = new WeakHashMap<>();
    public static final ShopTheme CLASSIC = adapt(GlassTheme.CLASSIC);
    public static final ShopTheme GLASS = adapt(GlassTheme.GLASS);
    public static final ShopTheme SMOKE = adapt(GlassTheme.SMOKE);
    public static final ShopTheme SLATE = adapt(GlassTheme.SLATE);
    public static final ShopTheme LIQUID_GLASS = adapt(GlassTheme.LIQUID_GLASS);
    public static final ShopTheme NETWORK = adapt(GlassTheme.NETWORK);
    public static final ShopTheme AURORA = adapt(GlassTheme.AURORA);
    public static final ShopTheme AMETHYST = adapt(GlassTheme.AMETHYST);
    public static final ShopTheme ROSE = adapt(GlassTheme.ROSE);
    public static final ShopTheme EMBER = adapt(GlassTheme.EMBER);

    private ShopTheme(GlassTheme source) {
        super(source);
    }

    private static synchronized ShopTheme adapt(GlassTheme source) {
        return ADAPTED.computeIfAbsent(source, ShopTheme::new);
    }

    public static ShopTheme current() {
        ShopTheme preview = PREVIEW.get();
        if (preview != null) return preview;
        return of(ClientShopUiSettings.shopTheme(), ClientShopUiSettings.shopAppearance().materialId());
    }

    static void preview(ShopTheme theme, Runnable render) {
        ShopTheme previous = PREVIEW.get();
        PREVIEW.set(theme);
        try {
            render.run();
        } finally {
            if (previous == null) PREVIEW.remove();
            else PREVIEW.set(previous);
        }
    }

    static ShopTheme of(String id, ShopAppearance.Material material) {
        return of(id, material.id);
    }

    static ShopTheme of(String id, String materialId) {
        if (ShopAppearance.Material.PRESET.id.equals(materialId)) return of(id);
        return ShBetterUiApi.materials().findTheme(canonicalMaterialId(materialId))
                .map(ShopTheme::adapt).orElseGet(() -> of(id));
    }

    public static ShopTheme of(String id) {
        return ShBetterUiApi.materials().findTheme(id).map(ShopTheme::adapt).orElse(CLASSIC);
    }

    static List<ThemeChoice> choices() {
        return ShBetterUiApi.materials().themeChoices();
    }

    static Component name(String id) {
        return choices().stream().filter(choice -> choice.id().equals(id)).findFirst()
                .map(ShopTheme::name).orElseGet(() -> Component.literal(id));
    }

    static Component name(ThemeChoice choice) {
        return Component.translatableWithFallback(choice.nameKey(), choice.id());
    }

    static String canonicalMaterialId(String id) {
        return switch (id) {
            case "clearGlass" -> ClientShopUiSettings.THEME_GLASS;
            case "smokeGlass" -> ClientShopUiSettings.THEME_SMOKE;
            default -> id;
        };
    }

    static String visibleId(String id, String materialId) {
        String material = canonicalMaterialId(materialId);
        if (ShBetterUiApi.materials().findTheme(material).isPresent()) return material;
        return ShBetterUiApi.materials().findTheme(id).isPresent() ? id : ClientShopUiSettings.THEME_CLASSIC;
    }

    public static String next(String id) {
        return adjacent(id, 1);
    }

    static String adjacent(String id, int step) {
        return adjacent(id, step, choices());
    }

    static String adjacent(String id, int step, List<ThemeChoice> choices) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id().equals(id)) {
                return choices.get(Math.floorMod(i + step, choices.size())).id();
            }
        }
        return choices.get(0).id();
    }
}
