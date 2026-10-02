package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分类阶段的提交物品配置，路径与 category 完全一致，例如 {@code 无限盘区/前期}。 */
public final class ShopStageConfig {
    private static final File FILE = new File("config/gt_shanhai/shop_stage_requirements.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, List<ExchangeEntry.Ingredient>> REQUIREMENTS = new LinkedHashMap<>();

    private ShopStageConfig() {}

    public static synchronized void reload() {
        REQUIREMENTS.clear();
        if (!FILE.exists()) return;
        try (InputStreamReader reader = new InputStreamReader(new java.io.FileInputStream(FILE), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) continue;
                List<ExchangeEntry.Ingredient> items = parse(entry.getValue().getAsJsonArray());
                if (!items.isEmpty()) REQUIREMENTS.put(entry.getKey(), items);
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 读取 shop_stage_requirements.json 失败: {}", e.getMessage());
        }
    }

    public static synchronized List<ExchangeEntry.Ingredient> get(String categoryPath) {
        if (categoryPath == null || categoryPath.isBlank()) return List.of();
        return List.copyOf(REQUIREMENTS.getOrDefault(categoryPath, List.of()));
    }

    public static synchronized boolean has(String categoryPath) {
        return !get(categoryPath).isEmpty();
    }

    public static synchronized java.util.Set<String> paths() {
        return java.util.Set.copyOf(REQUIREMENTS.keySet());
    }

    /** 返回当前商品分类及其所有已配置父阶段的要求，父阶段先提交。 */
    public static synchronized List<StageRequirement> requirementsForCategory(String categoryPath) {
        List<StageRequirement> result = new ArrayList<>();
        if (categoryPath == null || categoryPath.isBlank()) return result;
        String[] parts = categoryPath.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (path.length() > 0) path.append('/');
            path.append(part);
            String key = path.toString();
            List<ExchangeEntry.Ingredient> items = REQUIREMENTS.get(key);
            if (items != null && !items.isEmpty()) result.add(new StageRequirement(key, items));
        }
        return result;
    }

    public record StageRequirement(String path, List<ExchangeEntry.Ingredient> items) {
        public StageRequirement {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public static synchronized void set(String categoryPath, List<ExchangeEntry.Ingredient> items) {
        if (categoryPath == null || categoryPath.isBlank()) return;
        List<ExchangeEntry.Ingredient> cleaned = new ArrayList<>();
        if (items != null) {
            for (ExchangeEntry.Ingredient item : items) {
                if (item != null && !item.isFluid && item.id != null && ForgeRegistries.ITEMS.containsKey(item.id)) {
                    cleaned.add(new ExchangeEntry.Ingredient(item.id, false, item.count, item.nbt()));
                }
            }
        }
        if (cleaned.isEmpty()) REQUIREMENTS.remove(categoryPath);
        else REQUIREMENTS.put(categoryPath, List.copyOf(cleaned));
        save();
    }

    public static synchronized void save() {
        try {
            FILE.getParentFile().mkdirs();
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<ExchangeEntry.Ingredient>> entry : REQUIREMENTS.entrySet()) {
                JsonArray array = new JsonArray();
                for (ExchangeEntry.Ingredient item : entry.getValue()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("id", item.id.toString());
                    value.addProperty("count", item.count);
                    if (item.nbt() != null) value.addProperty("nbt", item.nbt().toString());
                    array.add(value);
                }
                root.add(entry.getKey(), array);
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(root));
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 保存 shop_stage_requirements.json 失败: {}", e.getMessage());
        }
    }

    private static List<ExchangeEntry.Ingredient> parse(JsonArray array) {
        List<ExchangeEntry.Ingredient> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!item.has("id")) continue;
            try {
                ResourceLocation id = new ResourceLocation(item.get("id").getAsString());
                if (!ForgeRegistries.ITEMS.containsKey(id)) continue;
                long count = item.has("count") ? Math.max(1L, item.get("count").getAsLong()) : 1L;
                net.minecraft.nbt.CompoundTag nbt = null;
                if (item.has("nbt")) nbt = net.minecraft.nbt.TagParser.parseTag(item.get("nbt").getAsString());
                result.add(new ExchangeEntry.Ingredient(id, false, count, nbt));
            } catch (Exception ignored) {}
        }
        return result;
    }
}
