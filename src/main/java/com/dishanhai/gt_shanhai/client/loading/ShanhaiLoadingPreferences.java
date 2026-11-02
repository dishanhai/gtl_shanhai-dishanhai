package com.dishanhai.gt_shanhai.client.loading;

import com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 山海：獨立客戶端設定，可在模組初始化尚未完成時安全讀取。 */
public final class ShanhaiLoadingPreferences {
    private static boolean loaded;
    private static boolean startup = true;
    private static boolean world = true;
    private static boolean reduceMotion;
    private static boolean profiling = true;
    private static boolean sampling = true;
    private static boolean asyncWorldData = true;

    private ShanhaiLoadingPreferences() {}

    public static synchronized boolean startup() { load(); return startup; }
    public static synchronized boolean world() { load(); return world; }
    public static synchronized boolean reduceMotion() { load(); return reduceMotion; }
    public static synchronized boolean profiling() { load(); return profiling; }
    public static synchronized boolean sampling() { load(); return sampling; }
    public static synchronized boolean asyncWorldData() { load(); return asyncWorldData; }
    public static synchronized void setStartup(boolean value) { load(); startup = value; save(); }
    public static synchronized void setWorld(boolean value) { load(); world = value; save(); }
    public static synchronized void setReduceMotion(boolean value) { load(); reduceMotion = value; save(); }
    public static synchronized void setProfiling(boolean value) { load(); profiling = value; save(); }
    public static synchronized void setSampling(boolean value) { load(); sampling = value; save(); }
    public static synchronized void setAsyncWorldData(boolean value) { load(); asyncWorldData = value; save(); }

    private static Path path() { return ShanhaiConfigPaths.clientDir().resolve("loading_animation.json"); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path file = path();
        if (!Files.exists(file)) { save(); return; }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            startup = !json.has("startup") || json.get("startup").getAsBoolean();
            world = !json.has("world") || json.get("world").getAsBoolean();
            reduceMotion = json.has("reduceMotion") && json.get("reduceMotion").getAsBoolean();
            profiling = !json.has("profiling") || json.get("profiling").getAsBoolean();
            sampling = !json.has("sampling") || json.get("sampling").getAsBoolean();
            asyncWorldData = !json.has("asyncWorldData") || json.get("asyncWorldData").getAsBoolean();
        } catch (IOException | RuntimeException error) {
            LogManager.getLogger("gt_shanhai:loading").warn("山海載入動畫設定讀取失敗，使用預設值", error);
            startup = world = true;
            reduceMotion = false;
            profiling = true;
            sampling = true;
            asyncWorldData = true;
        }
    }

    private static void save() {
        JsonObject json = new JsonObject();
        json.addProperty("startup", startup);
        json.addProperty("world", world);
        json.addProperty("reduceMotion", reduceMotion);
        json.addProperty("profiling", profiling);
        json.addProperty("sampling", sampling);
        json.addProperty("asyncWorldData", asyncWorldData);
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
            }
        } catch (IOException error) {
            LogManager.getLogger("gt_shanhai:loading").warn("山海載入動畫設定儲存失敗", error);
        }
    }
}
