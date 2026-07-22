package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商店详情页「购买原料」：从当前商品成本里的物品项反查商店条目，按缺口直接购买这些前置物品。
 */
public final class ShopMaterialPurchase {

    private ShopMaterialPurchase() {}

    public record Result(int missingTypes, int purchasableTypes, long orders,
                         BigInteger requestedItems, BigInteger purchasedItems) {
        public boolean hasMissing() { return missingTypes > 0; }
        public boolean hasPurchasable() { return purchasableTypes > 0; }
        public boolean boughtAny() { return orders > 0L && purchasedItems.signum() > 0; }
    }

    public static Result buyMissingMaterials(ServerPlayer player, ShopEntry target, long targetTimes,
                                             boolean aeMode, boolean backpackMode) {
        if (player == null || target == null || targetTimes <= 0L) {
            return new Result(0, 0, 0L, BigInteger.ZERO, BigInteger.ZERO);
        }
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();
        ShopCost targetCost = server == null
                ? target.getCost()
                : target.getEffectiveCost(ShopMembership.discountPercent(server, uuid));
        List<ExchangeEntry.Ingredient> itemCosts = targetCost.items();
        if (itemCosts.isEmpty()) {
            return new Result(0, 0, 0L, BigInteger.ZERO, BigInteger.ZERO);
        }

        ShopPurchase.CostPreview have = ShopPurchase.previewHave(player, targetCost, aeMode);
        Map<IngredientKey, MaterialOffer> offers = buildMaterialOfferIndex();
        BigInteger timesBig = BigInteger.valueOf(targetTimes);
        int missingTypes = 0;
        int purchasableTypes = 0;
        long orders = 0L;
        BigInteger requestedItems = BigInteger.ZERO;
        BigInteger purchasedItems = BigInteger.ZERO;

        for (int i = 0; i < itemCosts.size(); i++) {
            ExchangeEntry.Ingredient in = itemCosts.get(i);
            BigInteger need = BigInteger.valueOf(in.count).multiply(timesBig);
            long haveLong = i < have.items().size() && have.items().get(i) != null ? have.items().get(i) : 0L;
            BigInteger missing = need.subtract(BigInteger.valueOf(haveLong));
            if (missing.signum() <= 0) continue;
            missingTypes++;
            requestedItems = requestedItems.add(missing);
            MaterialOffer offer = offers.get(IngredientKey.of(in));
            if (offer == null) continue;
            purchasableTypes++;
            BigInteger buyTimesBig = ceilDiv(missing, BigInteger.valueOf(offer.goodsCount()));
            long buyTimes = clampToLong(buyTimesBig);
            if (buyTimes <= 0L) continue;
            ShopPurchase.BulkBuyResult result = buyOffer(player, offer, buyTimes, aeMode, backpackMode);
            if (result.done() <= 0L) continue;
            orders += result.done();
            purchasedItems = purchasedItems.add(BigInteger.valueOf(offer.goodsCount()).multiply(BigInteger.valueOf(result.done())));
        }
        return new Result(missingTypes, purchasableTypes, orders, requestedItems, purchasedItems);
    }

    private static Map<IngredientKey, MaterialOffer> buildMaterialOfferIndex() {
        Map<IngredientKey, MaterialOffer> result = new LinkedHashMap<>();
        List<ShopEntry> entries = ShopConfig.snapshot().entries();
        if (entries == null) return result;
        for (ShopEntry entry : entries) {
            if (entry == null) continue;
            if (!entry.allowsBuy()) continue;
            if (!entry.isStructurallyValid()) continue;
            if (!entry.isValid()) continue;
            if (entry.getRewardMode() != ShopEntry.RewardMode.NONE) continue;
            if (entry.hasMultipleGoods()) continue;
            if (entry.isPrimaryGoodsFluid()) continue;
            if (entry.getGoodsList().isEmpty()) continue;
            ShopEntry.GoodsStack goods = entry.getGoodsList().get(0);
            if (goods == null || goods.isFluid()) continue;
            result.putIfAbsent(IngredientKey.of(goods), new MaterialOffer(entry, goods.count()));
        }
        return result;
    }

    private static ShopPurchase.BulkBuyResult buyOffer(ServerPlayer player, MaterialOffer offer, long times,
                                                       boolean aeMode, boolean backpackMode) {
        return ShopPurchase.buyBulk(player, offer.entry(), times, aeMode, backpackMode);
    }

    private static BigInteger ceilDiv(BigInteger value, BigInteger divisor) {
        if (value == null || divisor == null || value.signum() <= 0 || divisor.signum() <= 0) return BigInteger.ZERO;
        return value.add(divisor).subtract(BigInteger.ONE).divide(divisor);
    }

    private static long clampToLong(BigInteger value) {
        if (value == null || value.signum() <= 0) return 0L;
        return value.bitLength() < 63 ? value.longValue() : Long.MAX_VALUE;
    }

    private record MaterialOffer(ShopEntry entry, int goodsCount) {
        private MaterialOffer {
            goodsCount = Math.max(1, goodsCount);
        }
    }

    private record IngredientKey(ResourceLocation id, String nbtKey) {
        static IngredientKey of(ExchangeEntry.Ingredient ingredient) {
            return new IngredientKey(ingredient.id, nbtString(ingredient.nbt()));
        }

        static IngredientKey of(ShopEntry.GoodsStack goods) {
            return new IngredientKey(goods.id(), nbtString(goods.nbt()));
        }

        private static String nbtString(CompoundTag tag) {
            return tag == null || tag.isEmpty() ? "" : tag.toString();
        }
    }
}
