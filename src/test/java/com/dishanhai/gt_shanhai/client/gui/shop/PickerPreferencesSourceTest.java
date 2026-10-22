package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PickerPreferencesSourceTest {

    private static final Path SETTINGS = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/shop/ClientShopUiSettings.java");

    @Test
    void gettersLoadPreferencesAndTheSetterSavesBothTogether() throws Exception {
        String source = Files.readString(SETTINGS);
        String sortGetter = block(source, "public static String pickerSort()");
        String groupGetter = block(source, "public static boolean pickerGroupByMod()");
        String setter = block(source, "public static synchronized void setPickerPreferences(");

        assertTrue(sortGetter.contains("ensureLoaded();") && sortGetter.contains("return pickerSort;"));
        assertTrue(groupGetter.contains("ensureLoaded();") && groupGetter.contains("return pickerGroupByMod;"));
        assertTrue(setter.contains("ensureLoaded();"));
        assertTrue(setter.contains("pickerSort = normalizePickerSort(sort);"));
        assertTrue(setter.contains("pickerGroupByMod = grouped;"));
        assertTrue(setter.contains("save();"));
    }

    @Test
    void reloadResetsPickerDefaultsAndLoadsBothKeys() throws Exception {
        String source = Files.readString(SETTINGS);
        String reload = block(source, "public static synchronized void reload()");

        assertTrue(source.contains("private static String pickerSort = \"default\";"));
        assertTrue(source.contains("private static boolean pickerGroupByMod = false;"));
        assertTrue(reload.indexOf("pickerSort = \"default\";") >= 0
                && reload.indexOf("pickerSort = \"default\";") < reload.indexOf("if (!settingsFile().exists())"));
        assertTrue(reload.indexOf("pickerGroupByMod = false;") >= 0
                && reload.indexOf("pickerGroupByMod = false;") < reload.indexOf("if (!settingsFile().exists())"));
        assertTrue(reload.contains("root.has(\"pickerSort\")"));
        assertTrue(reload.contains("normalizePickerSort(root.get(\"pickerSort\").getAsString())"));
        assertTrue(reload.contains("root.has(\"pickerGroupByMod\")"));
        assertTrue(reload.contains("pickerGroupByMod = root.get(\"pickerGroupByMod\").getAsBoolean();"));
    }

    @Test
    void invalidSortFallsBackToDefaultAndAllSupportedSortsRemainAvailable() throws Exception {
        String normalize = block(Files.readString(SETTINGS), "private static String normalizePickerSort(");

        assertTrue(normalize.contains("sort == null"));
        assertTrue(normalize.contains("\"default\"") && normalize.contains("\"name\"")
                && normalize.contains("\"mod\"") && normalize.contains("\"id\"")
                && normalize.contains("\"count\""));
        assertTrue(normalize.contains("default -> \"default\";"));
    }

    @Test
    void savingPickerPreferencesKeepsAllExistingSettingKeys() throws Exception {
        String save = block(Files.readString(SETTINGS), "private static void save()");

        for (String key : new String[]{"pickerSort", "pickerGroupByMod", "cardWidth", "cardHeight",
                "rewardPreview", "rememberShopView", "shopSearch", "shopSelectedStableId", "reduceMotion", "uiSounds"}) {
            assertTrue(save.contains("root.addProperty(\"" + key + "\", " + key + ")"), key);
        }
    }

    private static String block(String source, String declaration) {
        int declarationStart = source.indexOf(declaration);
        assertTrue(declarationStart >= 0, declaration);
        int bodyStart = source.indexOf('{', declarationStart);
        int depth = 0;
        for (int position = bodyStart; position < source.length(); position++) {
            char current = source.charAt(position);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(bodyStart, position + 1);
        }
        throw new AssertionError(declaration);
    }
}
