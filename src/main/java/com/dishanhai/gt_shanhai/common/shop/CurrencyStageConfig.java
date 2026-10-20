package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 货币兑换的阶段锁（山海署名）。
 *
 * <p>每种普通货币可以挂一个电压阶段名（{@code ulv}、{@code lv}…）。完整路径是
 * {@code stagePrefix/阶段名}，默认 {@code 杂货/正常/hv} 这种，和商店分类阶段同一套
 * {@link ShopStageConfig} 提交。没提交过的阶段，不能拿对应货币去兑换、转星火或用星火换出。
 * 存入钱包和提取实体币不看阶段。没写阶段、或该路径没有提交物品的货币，不锁。</p>
 */
public final class CurrencyStageConfig {

    private static File stageFile() {
        return com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.ensureParent(
                com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.currencyStages()).toFile();
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String DEFAULT_PREFIX = "杂货/正常";

    private static final Map<ResourceLocation, String> TIERS = new LinkedHashMap<>();
    private static String prefix = DEFAULT_PREFIX;
    private static boolean loaded = false;

    private CurrencyStageConfig() {}

    public static String stagePrefix() {
        ensureLoaded();
        return prefix;
    }

    /** 该币要提交的阶段路径。无阶段返回空串。 */
    public static String stagePath(ResourceLocation currency) {
        ensureLoaded();
        if (currency == null) return "";
        String tier = TIERS.get(currency);
        return join(prefix, tier);
    }

    static String join(String stagePrefix, String tier) {
        if (tier == null || tier.isBlank()) return "";
        String name = tier.trim();
        if (stagePrefix == null || stagePrefix.isBlank()) return name;
        return stagePrefix.trim() + "/" + name;
    }

    /**
     * 服务端：兑换该币还缺哪一段提交。已解锁、无阶段、作弊模式返回 null。
     * 父路径若也配了提交物品，和商店购买一样先交父阶段。
     */
    public static String missingStage(ServerPlayer player, ResourceLocation currency) {
        if (player == null || currency == null) return null;
        if (ShopCheatMode.isEnabled(player.getUUID())) return null;
        String path = stagePath(currency);
        if (path.isEmpty()) return null;
        ShopSubmissionSavedData data = ShopSubmissionSavedData.get(player.getServer());
        for (ShopStageConfig.StageRequirement requirement : ShopStageConfig.requirementsForCategory(path)) {
            if (!data.has(player, "stage:" + requirement.path())) return requirement.path();
        }
        return null;
    }

    /** 客户端预览，读已同步的阶段提交状态。没配提交物品的路径不锁。 */
    public static String missingStageClient(ResourceLocation currency) {
        String path = stagePath(currency);
        if (path.isEmpty()) return null;
        String[] parts = path.split("/");
        StringBuilder built = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (built.length() > 0) built.append('/');
            built.append(part);
            String candidate = built.toString();
            if (com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.hasStageRequirement(candidate)
                    && !com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.hasStage(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void ensureLoaded() {
        if (!loaded) reload();
    }

    public static synchronized void reload() {
        TIERS.clear();
        prefix = DEFAULT_PREFIX;
        loaded = true;
        if (!stageFile().exists()) writeDefault();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(stageFile()), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) return;
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("stagePrefix") && obj.get("stagePrefix").isJsonPrimitive()) {
                String value = obj.get("stagePrefix").getAsString().trim();
                if (!value.isEmpty()) prefix = value;
            }
            if (!obj.has("coins") || !obj.get("coins").isJsonObject()) return;
            for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("coins").entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                String tier = entry.getValue().getAsString().trim();
                if (tier.isEmpty()) continue;
                try {
                    TIERS.put(new ResourceLocation(entry.getKey()), tier);
                } catch (Exception ex) {
                    GTDishanhaiMod.LOGGER.warn("[CurrencyStage] 跳过非法货币 {}: {}", entry.getKey(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[CurrencyStage] 读取 currency_stages.json 失败: {}", e.getMessage());
        }
    }

    private static void writeDefault() {
        JsonObject coins = new JsonObject();
        coins.addProperty("dishanhai:copper_coin", "ulv");
        coins.addProperty("dishanhai:cupronickel_coin", "lv");
        coins.addProperty("dishanhai:silver_coin", "mv");
        coins.addProperty("dishanhai:gold_coin", "hv");
        coins.addProperty("dishanhai:platinum_coin", "ev");
        coins.addProperty("dishanhai:osmium_coin", "iv");
        coins.addProperty("dishanhai:naquadah_coin", "luv");
        coins.addProperty("dishanhai:neutronium_coin", "zpm");
        coins.addProperty("dishanhai:degenerate_rhenium_coin", "uhv");
        coins.addProperty("dishanhai:vibranium_coin", "uev");
        coins.addProperty("dishanhai:spacetime_coin", "uev");
        coins.addProperty("dishanhai:coin_secondary", "uiv");
        coins.addProperty("dishanhai:neutron_coin", "uev");
        coins.addProperty("dishanhai:infinite_coin", "uxv");
        coins.addProperty("dishanhai:transcendentmetal_coin", "max");
        coins.addProperty("dishanhai:chaos_coin", "opv");
        coins.addProperty("dishanhai:cosmic_coin", "opv");
        coins.addProperty("dishanhai:magmatter_coin", "opv");
        coins.addProperty("dishanhai:primordialmatter_coin", "opv");
        coins.addProperty("dishanhai:eternity_coin", "opv");
        coins.addProperty("dishanhai:magnetohydrodynamicallyconstrainedstarmatter_coin", "max");
        coins.addProperty("dishanhai:star_gate_crystal_slurry_coin", "max");
        JsonObject root = new JsonObject();
        root.addProperty("stagePrefix", DEFAULT_PREFIX);
        root.add("coins", coins);
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(stageFile()), StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(root));
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[CurrencyStage] 写默认 currency_stages.json 失败: {}", e.getMessage());
        }
    }
}
