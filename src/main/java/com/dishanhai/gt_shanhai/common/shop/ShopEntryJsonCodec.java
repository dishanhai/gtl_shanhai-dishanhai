package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海商店单条商品的 JSON 编解码器。
 *
 * <p>配置文件、服务端目录快照和客户端分块同步共用同一份字段定义，避免三套序列化格式漂移。
 * {@link #fromJson(JsonObject)} 会访问 Forge 注册表并创建 {@link ItemStack}，只能在 Minecraft 主线程调用；
 * 网络线程和后台 I/O 线程只传递 JSON 字符串。</p>
 */
public final class ShopEntryJsonCodec {

    private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ShopEntryJsonCodec() {}

    public static JsonObject toJson(ShopEntry entry) {
        JsonObject out = new JsonObject();
        out.addProperty("goods", entry.getGoodsId().toString());
        out.addProperty("count", entry.getGoodsCount());
        out.addProperty("category", entry.getCategory());
        if (entry.getDescription() != null && !entry.getDescription().isEmpty()) {
            out.addProperty("description", entry.getDescription());
        }
        if (entry.isLimited()) {
            out.addProperty("limit", entry.getRemainingUses());
        }
        out.add("cost", costToJson(entry.getCost()));
        net.minecraft.nbt.CompoundTag nbt = entry.getGoodsNbt();
        if (nbt != null && !nbt.isEmpty()) {
            out.addProperty("nbt", nbt.toString());
        }
        if (entry.hasMultipleGoods()) {
            out.add("goodsList", goodsListToJson(entry.getGoodsList()));
        }
        if (entry.hasCustomIcons()) {
            out.add("icons", iconsToJson(entry.getDisplayIcons()));
        }
        if (entry.getRewardMode() != ShopEntry.RewardMode.NONE) {
            out.addProperty("rewardMode", entry.getRewardMode().name());
            if (entry.getRewardMode() == ShopEntry.RewardMode.FTBQ) {
                out.addProperty("ftbqTableId", entry.getFtbqTableId());
                out.addProperty("ftbqSubMode", entry.getFtbqSubMode().name());
            } else {
                out.add("rewardPool", rewardPoolToJson(entry.getRewardPool()));
            }
        }
        if (entry.isHidden()) {
            out.addProperty("hidden", true);
        }
        if (entry.hasLinkKey()) {
            out.addProperty("linkKey", entry.getLinkKey());
        }
        if (entry.hasLinkTarget()) {
            out.addProperty("linkTo", entry.getLinkTo());
        }
        if (entry.hasCustomName()) {
            out.addProperty("displayName", entry.getDisplayName());
        }
        if (entry.getTradeMode() != ShopEntry.TradeMode.BOTH) {
            out.addProperty("tradeMode", entry.getTradeMode().name());
        }
        if (entry.isPeriodLimited()) {
            out.addProperty("periodTicks", entry.getPeriodTicks());
            out.addProperty("periodLimit", entry.getPeriodLimit());
        }
        return out;
    }

    public static String toPayload(ShopEntry entry) {
        return COMPACT_GSON.toJson(toJson(entry));
    }

    public static ShopEntry fromPayload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            return parsed.isJsonObject() ? fromJson(parsed.getAsJsonObject()) : null;
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 跳过非法商品负载: {}", e.getMessage());
            return null;
        }
    }

    public static ShopEntry fromJson(JsonObject json) {
        try {
            String goods = json.get("goods").getAsString();
            int count = json.has("count") ? json.get("count").getAsInt() : 1;
            String category = json.has("category") ? json.get("category").getAsString() : ShopEntry.DEFAULT_CATEGORY;
            String description = json.has("description") ? json.get("description").getAsString() : "";
            long limit = json.has("limit") ? json.get("limit").getAsLong() : -1L;
            net.minecraft.nbt.CompoundTag nbt = parseNbt(json, "nbt");

            ShopCost cost;
            if (json.has("cost") && json.get("cost").isJsonObject()) {
                cost = parseCost(json.getAsJsonObject("cost"));
            } else {
                String currency = json.has("currency") ? json.get("currency").getAsString() : "dishanhai:dog_coins";
                int price = json.has("price") ? json.get("price").getAsInt() : 1;
                cost = ShopCost.singleCoin(new ResourceLocation(currency), price);
            }

            List<ShopEntry.GoodsStack> goodsList;
            if (json.has("goodsList") && json.get("goodsList").isJsonArray()) {
                goodsList = parseGoodsList(json.getAsJsonArray("goodsList"));
                if (goodsList.isEmpty()) throw new IllegalArgumentException("goodsList 解析为空");
            } else {
                goodsList = List.of(ShopEntry.GoodsStack.of(new ResourceLocation(goods), count, nbt));
            }

            List<ShopEntry.DisplayIcon> icons = json.has("icons") && json.get("icons").isJsonArray()
                    ? parseIcons(json.getAsJsonArray("icons")) : null;
            ShopEntry.RewardMode rewardMode = enumValue(
                    ShopEntry.RewardMode.class, json, "rewardMode", ShopEntry.RewardMode.NONE);
            List<ShopEntry.RewardOption> rewardPool = json.has("rewardPool") && json.get("rewardPool").isJsonArray()
                    ? parseRewardPool(json.getAsJsonArray("rewardPool")) : null;
            boolean hidden = json.has("hidden") && json.get("hidden").getAsBoolean();
            String linkKey = stringOrNull(json, "linkKey");
            String linkTo = stringOrNull(json, "linkTo");
            String displayName = stringOrNull(json, "displayName");
            String ftbqTableId = stringOrNull(json, "ftbqTableId");
            ShopEntry.RewardMode ftbqSubMode = enumValue(
                    ShopEntry.RewardMode.class, json, "ftbqSubMode", ShopEntry.RewardMode.RANDOM);
            ShopEntry.TradeMode tradeMode = enumValue(
                    ShopEntry.TradeMode.class, json, "tradeMode", ShopEntry.TradeMode.BOTH);
            long periodTicks = json.has("periodTicks") ? json.get("periodTicks").getAsLong() : -1L;
            long periodLimit = json.has("periodLimit") ? json.get("periodLimit").getAsLong() : -1L;

            return new ShopEntry(goodsList, category, cost, description, limit,
                    icons, rewardMode, rewardPool, hidden, linkKey, linkTo, displayName,
                    ftbqTableId, ftbqSubMode, tradeMode, periodTicks, periodLimit);
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Shop] 跳过非法商品条目: {}", e.getMessage());
            return null;
        }
    }

    private static String stringOrNull(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : null;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonObject json, String key, E fallback) {
        if (!json.has(key)) return fallback;
        try {
            return Enum.valueOf(type, json.get(key).getAsString());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static net.minecraft.nbt.CompoundTag parseNbt(JsonObject json, String key) {
        if (!json.has(key)) return null;
        try {
            String snbt = json.get(key).getAsString();
            return snbt == null || snbt.isBlank() ? null : net.minecraft.nbt.TagParser.parseTag(snbt);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject costToJson(ShopCost cost) {
        JsonObject out = new JsonObject();
        out.addProperty("spark", cost.spark.toString());
        JsonObject coins = new JsonObject();
        for (Map.Entry<ResourceLocation, BigInteger> entry : cost.coins.entrySet()) {
            coins.addProperty(entry.getKey().toString(), entry.getValue().toString());
        }
        out.add("coins", coins);
        JsonArray items = new JsonArray();
        for (ExchangeEntry.Ingredient ingredient : cost.physical) {
            JsonObject item = new JsonObject();
            item.addProperty("id", ingredient.id == null ? "" : ingredient.id.toString());
            item.addProperty("fluid", ingredient.isFluid);
            item.addProperty("count", ingredient.count);
            if (ingredient.hasNbt()) item.addProperty("nbt", ingredient.nbt().toString());
            items.add(item);
        }
        out.add("items", items);
        return out;
    }

    private static ShopCost parseCost(JsonObject json) {
        BigInteger spark = BigInteger.ZERO;
        LinkedHashMap<ResourceLocation, BigInteger> coins = new LinkedHashMap<>();
        List<ExchangeEntry.Ingredient> physical = new ArrayList<>();
        if (json.has("spark")) {
            try { spark = new BigInteger(json.get("spark").getAsString()); } catch (Exception ignored) {}
        }
        if (json.has("coins") && json.get("coins").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("coins").entrySet()) {
                try {
                    BigInteger amount = new BigInteger(entry.getValue().getAsString());
                    if (amount.signum() > 0) coins.put(new ResourceLocation(entry.getKey()), amount);
                } catch (Exception ignored) {}
            }
        }
        if (json.has("items") && json.get("items").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("items")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String id = item.has("id") ? item.get("id").getAsString() : "";
                if (id.isEmpty()) continue;
                boolean fluid = item.has("fluid") && item.get("fluid").getAsBoolean();
                long amount = item.has("count") ? item.get("count").getAsLong() : 1L;
                physical.add(new ExchangeEntry.Ingredient(
                        new ResourceLocation(id), fluid, amount, parseNbt(item, "nbt")));
            }
        }
        return new ShopCost(spark, coins, physical);
    }

    private static JsonArray goodsListToJson(List<ShopEntry.GoodsStack> goods) {
        JsonArray array = new JsonArray();
        for (ShopEntry.GoodsStack stack : goods) {
            if (stack == null || stack.id() == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", stack.id().toString());
            item.addProperty("count", stack.count());
            net.minecraft.nbt.CompoundTag nbt = stack.nbt();
            if (nbt != null && !nbt.isEmpty()) item.addProperty("nbt", nbt.toString());
            array.add(item);
        }
        return array;
    }

    private static List<ShopEntry.GoodsStack> parseGoodsList(JsonArray array) {
        List<ShopEntry.GoodsStack> goods = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!item.has("id")) continue;
            ResourceLocation id = new ResourceLocation(item.get("id").getAsString());
            int count = item.has("count") ? Math.max(1, item.get("count").getAsInt()) : 1;
            goods.add(ShopEntry.GoodsStack.of(id, count, parseNbt(item, "nbt")));
        }
        return goods;
    }

    private static JsonArray iconsToJson(List<ShopEntry.DisplayIcon> icons) {
        JsonArray array = new JsonArray();
        for (ShopEntry.DisplayIcon icon : icons) {
            if (icon == null) continue;
            JsonObject item = new JsonObject();
            if (icon.isTexture()) {
                item.addProperty("texture", icon.texture().toString());
            } else {
                ItemStack stack = icon.item();
                if (stack == null || stack.isEmpty()) continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id == null) continue;
                item.addProperty("id", id.toString());
                if (stack.hasTag()) item.addProperty("nbt", stack.getTag().toString());
            }
            array.add(item);
        }
        return array;
    }

    private static List<ShopEntry.DisplayIcon> parseIcons(JsonArray array) {
        List<ShopEntry.DisplayIcon> icons = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (item.has("texture")) {
                icons.add(ShopEntry.DisplayIcon.ofTexture(new ResourceLocation(item.get("texture").getAsString())));
                continue;
            }
            if (!item.has("id")) continue;
            var registryItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(item.get("id").getAsString()));
            if (registryItem == null) continue;
            ItemStack stack = new ItemStack(registryItem);
            net.minecraft.nbt.CompoundTag nbt = parseNbt(item, "nbt");
            if (nbt != null) stack.setTag(nbt);
            icons.add(ShopEntry.DisplayIcon.ofItem(stack));
        }
        return icons;
    }

    private static JsonArray rewardPoolToJson(List<ShopEntry.RewardOption> rewards) {
        JsonArray array = new JsonArray();
        for (ShopEntry.RewardOption reward : rewards) {
            if (reward == null) continue;
            ItemStack stack = reward.item();
            if (stack == null || stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", id.toString());
            item.addProperty("count", stack.getCount());
            if (stack.hasTag()) item.addProperty("nbt", stack.getTag().toString());
            item.addProperty("weight", reward.weight());
            item.addProperty("min", reward.minCount());
            item.addProperty("max", reward.maxCount());
            array.add(item);
        }
        return array;
    }

    private static List<ShopEntry.RewardOption> parseRewardPool(JsonArray array) {
        List<ShopEntry.RewardOption> rewards = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!item.has("id")) continue;
            var registryItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(item.get("id").getAsString()));
            if (registryItem == null) continue;
            int count = item.has("count") ? Math.max(1, item.get("count").getAsInt()) : 1;
            ItemStack stack = new ItemStack(registryItem, count);
            net.minecraft.nbt.CompoundTag nbt = parseNbt(item, "nbt");
            if (nbt != null) stack.setTag(nbt);
            int weight = item.has("weight") ? Math.max(1, item.get("weight").getAsInt()) : 1;
            int min = item.has("min") ? Math.max(1, item.get("min").getAsInt()) : count;
            int max = item.has("max") ? Math.max(1, item.get("max").getAsInt()) : count;
            rewards.add(ShopEntry.RewardOption.of(stack, weight, min, max));
        }
        return rewards;
    }
}
