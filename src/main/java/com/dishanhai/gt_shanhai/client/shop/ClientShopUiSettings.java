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
    /** 获取预览：方格（图标格，旧版）。奖励表格子下方是概率，获得和花费的格子下方是数量。 */
    public static final String PREVIEW_GRID = "grid";
    /** 获取预览：条形（名称列表，默认）。奖励表右侧是概率，获得和花费的右侧是数量。 */
    public static final String PREVIEW_BAR = "bar";
    private static final int MIN_CARD_WIDTH = 48;
    private static final int MAX_CARD_WIDTH = 120;
    private static final int MIN_CARD_HEIGHT = 28;
    private static final int MAX_CARD_HEIGHT = 72;
    private static File settingsFile() {
        return com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.ensureParent(
                com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.shopClientUi()).toFile();
    }
    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static boolean loaded;
    private static int cardWidth = DEFAULT_CARD_WIDTH;
    private static int cardHeight = DEFAULT_CARD_HEIGHT;
    private static String rewardPreview = PREVIEW_BAR;
    /** 關閉商店再打開時，是否還原上次的搜索詞和選中商品。缺省為開。 */
    private static boolean rememberShopView = true;
    private static String shopSearch = "";
    private static String shopSelectedStableId = "";
    /** 减少动效：按钮不缩放、不抖、不走流光，也不画点击波纹，只保留颜色闪。缺省关。 */
    private static boolean reduceMotion = false;
    /** 商店界面音效（点击、连发、成交结果）。缺省开。 */
    private static boolean uiSounds = true;

    private ClientShopUiSettings() {}

    public static int cardWidth() {
        ensureLoaded();
        return cardWidth;
    }

    public static int cardHeight() {
        ensureLoaded();
        return cardHeight;
    }

    /** 详情「预计获得」和「花费预览」用条形名称列表。false 为旧版方格图标。 */
    public static boolean rewardPreviewBar() {
        ensureLoaded();
        return PREVIEW_BAR.equals(rewardPreview);
    }

    public static synchronized void setCardSize(int width, int height) {
        ensureLoaded();
        cardWidth = clampCardWidth(width);
        cardHeight = clampCardHeight(height);
        save();
    }

    public static synchronized void setRewardPreviewBar(boolean bar) {
        ensureLoaded();
        rewardPreview = bar ? PREVIEW_BAR : PREVIEW_GRID;
        save();
    }

    /** 關閉商店再打開時還原搜索詞和選中商品。只存在這台客戶端。 */
    public static boolean rememberShopView() {
        ensureLoaded();
        return rememberShopView;
    }

    public static String shopSearch() {
        ensureLoaded();
        return shopSearch;
    }

    public static String shopSelectedStableId() {
        ensureLoaded();
        return shopSelectedStableId;
    }

    public static synchronized void setRememberShopView(boolean remember) {
        ensureLoaded();
        rememberShopView = remember;
        save();
    }

    /** 記住當前搜索和選中商品。開關關掉時不寫，避免把已經清空的偏好又存回去。 */
    public static synchronized void setShopView(String search, String stableId) {
        ensureLoaded();
        if (!rememberShopView) return;
        shopSearch = limit(search, 256);
        shopSelectedStableId = limit(stableId, 128);
        save();
    }

    public static synchronized void clearShopView() {
        ensureLoaded();
        shopSearch = "";
        shopSelectedStableId = "";
        save();
    }

    public static boolean reduceMotion() {
        ensureLoaded();
        return reduceMotion;
    }

    public static boolean uiSounds() {
        ensureLoaded();
        return uiSounds;
    }

    /** 按键反馈的两个个人偏好一起存。只存在这台客户端。 */
    public static synchronized void setFeedbackPrefs(boolean reduce, boolean sounds) {
        ensureLoaded();
        reduceMotion = reduce;
        uiSounds = sounds;
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
        rewardPreview = PREVIEW_BAR;
        rememberShopView = true;
        shopSearch = "";
        shopSelectedStableId = "";
        reduceMotion = false;
        uiSounds = true;
        if (!settingsFile().exists()) return;
        try (java.io.Reader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(settingsFile()), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("cardWidth")) cardWidth = clampCardWidth(root.get("cardWidth").getAsInt());
            if (root.has("cardHeight")) cardHeight = clampCardHeight(root.get("cardHeight").getAsInt());
            // 没写过这个键的旧文件保持新默认（条形）。只有明确存过 grid 才回到旧版方格。
            if (root.has("rewardPreview") && PREVIEW_GRID.equals(root.get("rewardPreview").getAsString())) {
                rewardPreview = PREVIEW_GRID;
            }
            if (root.has("rememberShopView")) rememberShopView = root.get("rememberShopView").getAsBoolean();
            if (root.has("shopSearch")) shopSearch = limit(root.get("shopSearch").getAsString(), 256);
            if (root.has("shopSelectedStableId")) {
                shopSelectedStableId = limit(root.get("shopSelectedStableId").getAsString(), 128);
            }
            if (root.has("reduceMotion")) reduceMotion = root.get("reduceMotion").getAsBoolean();
            if (root.has("uiSounds")) uiSounds = root.get("uiSounds").getAsBoolean();
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop_client_ui.json 失败: {}", e.getMessage());
        }
    }

    private static String limit(String value, int max) {
        if (value == null || value.isEmpty()) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("cardWidth", cardWidth);
            root.addProperty("cardHeight", cardHeight);
            root.addProperty("rewardPreview", rewardPreview);
            root.addProperty("rememberShopView", rememberShopView);
            root.addProperty("shopSearch", shopSearch);
            root.addProperty("shopSelectedStableId", shopSelectedStableId);
            root.addProperty("reduceMotion", reduceMotion);
            root.addProperty("uiSounds", uiSounds);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(settingsFile()), StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(root));
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 保存 shop_client_ui.json 失败: {}", e.getMessage());
        }
    }
}
