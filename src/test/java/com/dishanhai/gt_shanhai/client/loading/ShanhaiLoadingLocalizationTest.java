package com.dishanhai.gt_shanhai.client.loading;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：載入介面的固定文案必須在語言資源中，避免固定繁體中文。 */
class ShanhaiLoadingLocalizationTest {
    @Test void startupAtlasCoversBuiltInLoadingTranslations() throws Exception {
        var resources = Path.of("src/main/resources/assets/gt_shanhai");
        String glyphs = JsonParser.parseString(Files.readString(resources.resolve("textures/gui/loading/shanhai_loading_font.json")))
                .getAsJsonObject().get("characters").getAsString();
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            var language = JsonParser.parseString(Files.readString(resources.resolve("lang/" + locale + ".json"))).getAsJsonObject();
            for (var entry : language.entrySet()) {
                if (!entry.getKey().startsWith("gui.gt_shanhai.loading.") || entry.getKey().contains(".report.")
                        || entry.getKey().contains(".kind.") || entry.getKey().contains(".outcome.")) continue;
                entry.getValue().getAsString().codePoints().filter(c -> !Character.isWhitespace(c)).forEach(c ->
                        assertTrue(glyphs.indexOf(c) >= 0, entry.getKey() + ": " + Character.toString(c)));
            }
        }
    }

    @Test void reportAndLoadingUiDoNotEmbedChineseLabels() throws Exception {
        for (String name : new String[]{"ShanhaiLoadingReportScreen", "ShanhaiLoadingClient", "ShanhaiLoadingRenderer"}) {
            String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/loading/" + name + ".java"));
            // 日誌可保留開發者診斷文字；介面字串不能固定語言。
            source = source.replaceAll("(?m)^.*LogManager.*$", "");
            assertFalse(Pattern.compile("\"[^\"\\r\\n]*\\p{IsHan}[^\"\\r\\n]*\"").matcher(source).find(), name);
        }
    }

    @Test void loadingKeysHaveMatchingTranslationsAndPlaceholders() throws Exception {
        var root = Path.of("src/main/resources/assets/gt_shanhai/lang");
        var en = JsonParser.parseString(Files.readString(root.resolve("en_us.json"))).getAsJsonObject();
        for (String locale : new String[]{"zh_cn"}) {
            var translated = JsonParser.parseString(Files.readString(root.resolve(locale + ".json"))).getAsJsonObject();
            for (var entry : en.entrySet()) {
                if (!entry.getKey().startsWith("gui.gt_shanhai.loading.")) continue;
                assertTrue(translated.has(entry.getKey()), locale + ": " + entry.getKey());
                assertEquals(entry.getValue().getAsString().chars().filter(c -> c == '%').count(),
                        translated.get(entry.getKey()).getAsString().chars().filter(c -> c == '%').count(), entry.getKey());
            }
        }
    }
}
