package com.dishanhai.gt_shanhai.client.loading;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 山海：资源重载前从内置语言文件取文案，重载后沿用原生翻译与资源包。 */
public final class ShanhaiLoadingText {
    private static final Map<String, JsonObject> EARLY_LANGUAGES = new HashMap<>();
    private ShanhaiLoadingText() {}

    public static String text(String suffix, Object... args) {
        String key = "gui.gt_shanhai.loading." + suffix;
        if (I18n.exists(key)) return Component.translatable(key, args).getString();
        Minecraft mc = Minecraft.getInstance();
        String language = mc.options == null ? "en_us" : mc.options.languageCode;
        JsonObject selected = bundled(language);
        String format = selected.has(key) ? selected.get(key).getAsString()
                : bundled("en_us").has(key) ? bundled("en_us").get(key).getAsString() : key;
        return args.length == 0 ? format : String.format(Locale.ROOT, format, args);
    }

    private static synchronized JsonObject bundled(String language) {
        return EARLY_LANGUAGES.computeIfAbsent(language, code -> {
            if (!code.matches("[a-z_]+")) return new JsonObject();
            try (var input = ShanhaiLoadingText.class.getResourceAsStream("/assets/gt_shanhai/lang/" + code + ".json")) {
                if (input == null) return new JsonObject();
                try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                }
            } catch (java.io.IOException error) { return new JsonObject(); }
        });
    }
}
