package com.dishanhai.gt_shanhai.common.shop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShopMaterialPurchaseSourceTest {

    private static final Path MATERIAL_PURCHASE = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopMaterialPurchase.java");
    private static final Path ACTION_PACKET = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/network/ShopActionPacket.java");
    private static final Path NETWORK = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/network/ShanhaiNetwork.java");
    private static final Path SCREEN = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java");

    @Test
    void serverBuysMissingPhysicalItemCostsThroughShopEntries() throws Exception {
        String source = Files.readString(MATERIAL_PURCHASE);
        String buyMissing = extractBlock(source, "public static Result buyMissingMaterials(");
        String index = extractBlock(source, "private static Map<IngredientKey, MaterialOffer> buildMaterialOfferIndex(");
        String execute = extractBlock(source, "private static ShopPurchase.BulkBuyResult buyOffer(");

        assertAll(
                () -> assertTrue(buyMissing.contains("ShopPurchase.previewHave(player, targetCost, aeMode)"),
                        "必须按当前玩家背包/AE 实际余量只购买缺口，不能无脑买满成本"),
                () -> assertTrue(buyMissing.contains("ceilDiv(missing, BigInteger.valueOf(offer.goodsCount()))"),
                        "原料商品每份可能产出多个物品，购买次数必须向上取整"),
                () -> assertTrue(index.contains("ShopConfig.snapshot().entries()"),
                        "原料来源必须来自当前服务端商店快照"),
                () -> assertTrue(index.contains("!entry.allowsBuy()")),
                () -> assertTrue(index.contains("entry.getRewardMode() != ShopEntry.RewardMode.NONE"),
                        "奖励/随机商品不能作为确定原料来源"),
                () -> assertTrue(index.contains("entry.hasMultipleGoods()"),
                        "组合商品不能作为单一原料来源"),
                () -> assertTrue(index.contains("putIfAbsent"),
                        "多个商品卖同一原料时保留商店靠前项"),
                () -> assertTrue(execute.contains("ShopPurchase.buyBulk(player, offer.entry(), times"),
                        "最终扣款和交付必须复用真实商店购买逻辑"));
    }

    @Test
    void actionPacketExposesBuyMaterialsActionAndSyncsWallet() throws Exception {
        String packet = Files.readString(ACTION_PACKET);
        String apply = extractBlock(packet, "private static void apply(");
        String buyMaterials = extractBlock(packet, "private static void doBuyMaterials(");

        assertAll(
                () -> assertTrue(packet.contains("BUY_MATERIALS"),
                        "商店动作包必须有购买原料动作"),
                () -> assertTrue(apply.contains("case BUY_MATERIALS -> doBuyMaterials(player, entry, pkt)"),
                        "购买原料动作必须进入专用服务端处理分支"),
                () -> assertTrue(buyMaterials.contains("ShopMaterialPurchase.buyMissingMaterials(player, entry, pkt.times, pkt.aeMode, pkt.backpackMode)"),
                        "购买原料动作必须使用当前商品、次数、AE/背包模式"),
                () -> assertTrue(buyMaterials.contains("WalletAccountAPI.sync(player)"),
                        "购买原料成功后必须同步钱包余额"));
    }

    @Test
    void networkAndDetailUiExposeBuyMaterialsButton() throws Exception {
        String network = Files.readString(NETWORK);
        String screen = Files.readString(SCREEN);
        String drawDetail = extractBlock(screen, "private void drawDetail(");
        String click = extractBlock(screen, "protected boolean universalMouseClicked(");

        assertAll(
                () -> assertTrue(network.contains("ShopActionPacket.class"),
                        "购买原料复用 ShopActionPacket，不新增重复包"),
                () -> assertTrue(screen.contains("buyMaterialsBtnVisible"),
                        "详情页需要暂存购买原料按钮命中区域"),
                () -> assertTrue(drawDetail.contains("hasShopPurchasableMaterialCost(dcost)"),
                        "只有当前成本含可由商店购买的物品原料时才显示按钮"),
                () -> assertTrue(drawDetail.contains("购买原料"),
                        "按钮文案必须直观表达购买原料"),
                () -> assertTrue(click.contains("ShopActionPacket.Action.BUY_MATERIALS"),
                        "点击按钮必须发购买原料动作"),
                () -> assertTrue(click.contains("amount"),
                        "购买原料必须按当前输入购买次数计算需求"));
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openBrace, i + 1);
            }
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
