package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 货币汇率 / 币值配置（山海署名）。
 *
 * <p>读取 {@code config/gt_shanhai/currency_rates.json}（可热改不重编译），格式：
 * <pre>{ "dishanhai:copper_coin": 1, "dishanhai:dog_coins": 1, ... }</pre>
 * 每种货币映射到一个 <b>基准价值</b>（long，以 copper_coin=1 为锚）。</p>
 *
 * <p>用途：① ATM 币种清单（= 本表所有键）；② 币种兑换换算——
 * A→B 目标量 = floor(源量 × A币值 / B币值)。文件缺失时写出一份分档默认表。</p>
 */
public final class CurrencyRateConfig {

    private static final String CONFIG_DIR = "config/gt_shanhai";
    private static final File RATE_FILE = new File(CONFIG_DIR, "currency_rates.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 币种 → 基准价值（保序）。 */
    private static final Map<ResourceLocation, Long> RATES = new LinkedHashMap<>();
    private static boolean loaded = false;

    private CurrencyRateConfig() {}

    /** 币值（未定义返回 0，视为不可兑换）。 */
    public static long getValue(ResourceLocation currency) {
        ensureLoaded();
        Long v = currency == null ? null : RATES.get(currency);
        return v == null ? 0L : v;
    }

    /** 是否已配置币值（可参与兑换 / 出现在 ATM）。 */
    public static boolean has(ResourceLocation currency) {
        ensureLoaded();
        return currency != null && RATES.containsKey(currency);
    }

    /** ATM 币种清单（= 配置里出现的所有币，保序）。 */
    public static List<ResourceLocation> getCurrencies() {
        ensureLoaded();
        return new ArrayList<>(RATES.keySet());
    }

    private static void ensureLoaded() {
        if (!loaded) reload();
    }

    public static synchronized void reload() {
        RATES.clear();
        loaded = true;
        if (!RATE_FILE.exists()) {
            writeDefault();
        }
        // 必须显式 UTF-8：FileReader 用系统默认编码会把中文键读乱（本表键为纯 ASCII，但保持一致规范）
        try (Reader r = new InputStreamReader(new FileInputStream(RATE_FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (String key : root.keySet()) {
                try {
                    long v = root.get(key).getAsLong();
                    if (v > 0L) RATES.put(new ResourceLocation(key), v);
                } catch (Exception ex) {
                    GTDishanhaiMod.LOGGER.warn("[CurrencyRate] 跳过非法条目 {}: {}", key, ex.getMessage());
                }
            }
            GTDishanhaiMod.LOGGER.info("[CurrencyRate] 已加载 {} 种货币汇率", RATES.size());
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[CurrencyRate] 读取 currency_rates.json 失败: {}", e.getMessage());
        }
    }

    /** 写出一版分档默认表（powers-of-4 阶梯，copper=1 为锚；用户可自行改 JSON）。 */
    private static void writeDefault() {
        // 基础货币
        long[] tierBase = {1, 1, 4, 16, 64, 256};
        String[] base = {"dog_coins", "copper_coin", "cupronickel_coin", "silver_coin", "gold_coin", "platinum_coin"};
        // 高阶货币（自 osmium 起 ×4 阶梯）
        // 注意：星门水晶浆币为最高档，排在最后（无尽币退到它前一档）
        String[] high = {"osmium_coin", "naquadah_coin", "neutronium_coin", "magmatter_coin",
                "magnetohydrodynamicallyconstrainedstarmatter_coin", "primordialmatter_coin", "spacetime_coin",
                "transcendentmetal_coin", "cosmic_coin", "neutron_coin", "eternity_coin", "chaos_coin",
                "infinite_coin", "star_gate_crystal_slurry_coin"};
        // 其他代币（默认 1）
        String[] other = {"coin_secondary", "stupid_coin", "sadbapycat_token"};

        JsonObject root = new JsonObject();
        for (int i = 0; i < base.length; i++) root.addProperty("dishanhai:" + base[i], tierBase[i]);
        long v = 1024L; // osmium 起点
        for (String h : high) { root.addProperty("dishanhai:" + h, v); v *= 4L; }
        for (String o : other) root.addProperty("dishanhai:" + o, 1L);

        try {
            new File(CONFIG_DIR).mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(RATE_FILE), StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(root));
            }
            GTDishanhaiMod.LOGGER.info("[CurrencyRate] 已生成默认 currency_rates.json");
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[CurrencyRate] 写默认 currency_rates.json 失败: {}", e.getMessage());
        }
    }
}
