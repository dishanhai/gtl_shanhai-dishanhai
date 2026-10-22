package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 山海資源選擇器的個人偏好，與獨立商店的目錄及帳戶無關。 */
public final class PickerUiSettings {
    private static boolean loaded;
    private static boolean reduceMotion;
    private static boolean uiSounds = true;
    private static String pickerSort = "default";
    private static boolean pickerGroupByMod;

    private PickerUiSettings() {}

    private static Path path() {
        return ShanhaiConfigPaths.clientDir().resolve("resource_picker.json");
    }

    public static boolean reduceMotion() { ensureLoaded(); return reduceMotion; }
    public static boolean uiSounds() { ensureLoaded(); return uiSounds; }
    public static String pickerSort() { ensureLoaded(); return pickerSort; }
    public static boolean pickerGroupByMod() { ensureLoaded(); return pickerGroupByMod; }

    public static synchronized void setPickerPreferences(String sort, boolean grouped) {
        ensureLoaded();
        pickerSort = normalizePickerSort(sort);
        pickerGroupByMod = grouped;
        JsonObject root = new JsonObject();
        root.addProperty("reduceMotion", reduceMotion);
        root.addProperty("uiSounds", uiSounds);
        root.addProperty("pickerSort", pickerSort);
        root.addProperty("pickerGroupByMod", pickerGroupByMod);
        try (var writer = Files.newBufferedWriter(ShanhaiConfigPaths.ensureParent(path()), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
        } catch (Exception exception) {
            GTDishanhaiMod.LOGGER.warn("山海資源選擇器偏好儲存失敗", exception);
        }
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path source = Files.isRegularFile(path()) ? path()
                : ShanhaiConfigPaths.root().resolve("shop/shop_client_ui.json");
        if (!Files.isRegularFile(source)) return;
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("reduceMotion")) reduceMotion = root.get("reduceMotion").getAsBoolean();
            if (root.has("uiSounds")) uiSounds = root.get("uiSounds").getAsBoolean();
            if (root.has("pickerSort")) pickerSort = normalizePickerSort(root.get("pickerSort").getAsString());
            if (root.has("pickerGroupByMod")) pickerGroupByMod = root.get("pickerGroupByMod").getAsBoolean();
        } catch (Exception exception) {
            GTDishanhaiMod.LOGGER.warn("山海資源選擇器偏好讀取失敗", exception);
        }
    }

    private static String normalizePickerSort(String sort) {
        if (sort == null) return "default";
        return switch (sort) {
            case "default", "name", "mod", "id", "count" -> sort;
            default -> "default";
        };
    }
}
