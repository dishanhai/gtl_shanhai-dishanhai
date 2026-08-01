package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Client-only personal display preferences for the wallet shop UI. */
public final class ClientShopUiSettings {

    public static final int DEFAULT_CARD_WIDTH = 70;
    public static final int DEFAULT_CARD_HEIGHT = 36;
    private static final int MIN_CARD_WIDTH = 48;
    private static final int MAX_CARD_WIDTH = 120;
    private static final int MIN_CARD_HEIGHT = 28;
    private static final int MAX_CARD_HEIGHT = 72;
    private static final String CONFIG_DIR = "config/gt_shanhai";
    private static final File SETTINGS_FILE = new File(CONFIG_DIR, "shop_client_ui.json");
    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static boolean loaded;
    private static int cardWidth = DEFAULT_CARD_WIDTH;
    private static int cardHeight = DEFAULT_CARD_HEIGHT;

    private ClientShopUiSettings() {}

    public static int cardWidth() {
        ensureLoaded();
        return cardWidth;
    }

    public static int cardHeight() {
        ensureLoaded();
        return cardHeight;
    }

    public static synchronized void setCardSize(int width, int height) {
        cardWidth = clampCardWidth(width);
        cardHeight = clampCardHeight(height);
        loaded = true;
        save();
    }

    public static int clampCardWidth(int width) {
        return Math.max(MIN_CARD_WIDTH, Math.min(MAX_CARD_WIDTH, width));
    }

    public static int clampCardHeight(int height) {
        return Math.max(MIN_CARD_HEIGHT, Math.min(MAX_CARD_HEIGHT, height));
    }

    private static synchronized void ensureLoaded() {
        if (!loaded) reload();
    }

    public static synchronized void reload() {
        loaded = true;
        cardWidth = DEFAULT_CARD_WIDTH;
        cardHeight = DEFAULT_CARD_HEIGHT;
        if (!SETTINGS_FILE.exists()) return;
        try (java.io.Reader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(SETTINGS_FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("cardWidth")) cardWidth = clampCardWidth(root.get("cardWidth").getAsInt());
            if (root.has("cardHeight")) cardHeight = clampCardHeight(root.get("cardHeight").getAsInt());
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop_client_ui.json 失败: {}", e.getMessage());
        }
    }

    private static void save() {
        try {
            new File(CONFIG_DIR).mkdirs();
            JsonObject root = new JsonObject();
            root.addProperty("cardWidth", cardWidth);
            root.addProperty("cardHeight", cardHeight);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(SETTINGS_FILE), StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(root));
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 保存 shop_client_ui.json 失败: {}", e.getMessage());
        }
    }
}
