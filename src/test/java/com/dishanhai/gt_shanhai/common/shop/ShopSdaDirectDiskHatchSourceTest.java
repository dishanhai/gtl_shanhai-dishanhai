package com.dishanhai.gt_shanhai.common.shop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShopSdaDirectDiskHatchSourceTest {

    private static final Path CONFIG = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/config/DShanhaiConfig.java");
    private static final Path PACKET = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/network/ShopSettingsPacket.java");
    private static final Path ACTION_PACKET = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/network/ShopActionPacket.java");
    private static final Path SETTINGS_SCREEN = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopSettingsScreen.java");
    private static final Path CONFIG_SCREEN = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/config/DShanhaiConfigScreen.java");
    private static final Path PURCHASE = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopPurchase.java");
    private static final Path AE_NETWORK = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopAeNetwork.java");
    private static final Path DISK_HATCH = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/common/machine/part/MEDiskHatchPartMachine.java");

    @Test
    void configAndSettingsExposeDirectSdaDiskHatchInjectionEnabledByDefault() throws Exception {
        String config = Files.readString(CONFIG);
        String packet = Files.readString(PACKET);
        String settings = Files.readString(SETTINGS_SCREEN);
        String configScreen = Files.readString(CONFIG_SCREEN);

        assertAll(
                () -> assertTrue(config.contains("shopSdaDirectDiskHatchInject"),
                        "公共配置必须有独立开关，不能复用 AE 禁止注入"),
                () -> assertTrue(config.contains(".define(\"sdaDirectDiskHatchInject\", true)"),
                        "将SDA直接注入磁盘仓室必须默认开启"),
                () -> assertTrue(packet.contains("sdaDirectDiskHatchInject"),
                        "商店设置包必须同步并保存新开关"),
                () -> assertTrue(settings.contains("将SDA直接注入磁盘仓室"),
                        "商店设置 UI 必须显示该开关"),
                () -> assertTrue(configScreen.contains("将SDA直接注入磁盘仓室"),
                        "模组配置屏也必须显示该开关"));
    }

    @Test
    void autoPackedSdaTriesBoundDiskHatchBeforeInventoryFallback() throws Exception {
        String purchase = Files.readString(PURCHASE);
        String pack = extractBlock(purchase, "private static boolean packAsSdaBatch(");

        int inject = pack.indexOf("ShopAeNetwork.injectSdaIntoBoundDiskHatch(player, sda)");
        int inventory = pack.indexOf("player.getInventory().add(sda)");

        assertAll(
                () -> assertTrue(pack.contains("shopSdaDirectDiskHatchInject.get()"),
                        "自动打包 SDA 必须受新开关控制"),
                () -> assertTrue(inject >= 0, "自动打包 SDA 必须先尝试插入同网磁盘仓室"),
                () -> assertTrue(inventory >= 0 && inject < inventory,
                        "磁盘仓室插入失败后才允许退回背包/掉落"));
    }

    @Test
    void directNonEmptySdaGoodsMayMountButEmptySdaGoodsStayAsNormalItem() throws Exception {
        String purchase = Files.readString(PURCHASE);
        String deliver = extractBlock(purchase, "public static String deliverItems(");
        String batch = extractBlock(purchase, "public static String deliverItemBatch(");

        assertAll(
                () -> assertTrue(purchase.contains("hasDirectMountableSdaContent("),
                        "直接商品 SDA 必须先判断是否确实有内容"),
                () -> assertTrue(deliver.contains("tryDeliverDirectSdaToDiskHatch(player, unit, total)"),
                        "单商品/奖励直接 SDA 应优先尝试挂载"),
                () -> assertTrue(batch.contains("tryDeliverDirectSdaToDiskHatch(player, unit, total)"),
                        "组合商品里的直接 SDA 也应优先尝试挂载"),
                () -> assertTrue(purchase.contains("TAG_TYPES") && purchase.contains("getInt(TAG_TYPES) > 0"),
                        "有内容判断至少要识别轻量统计，空 SDA 不得自动挂载"),
                () -> assertTrue(deliver.contains("!directMountableGoods && leftover > 0L && key != null"),
                        "直接可挂载存储商品的背包余量不能再被二次打包进另一份 SDA"),
                () -> assertTrue(deliver.contains("dropDirectItemCopies(player, unit, leftover)"),
                        "直接 SDA 商品背包放不下时应作为 SDA 副本兜底交付"),
                () -> assertFalse(purchase.contains("unit.getItem() instanceof com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem)\n            ShopAeNetwork.injectSdaIntoBoundDiskHatch"),
                        "不能只凭 SuperDiskArrayItem 类型就挂载，空 SDA 商品必须保留为普通交付"));
    }

    @Test
    void directInfinityCellGoodsMountThroughSameDiskHatchPath() throws Exception {
        String purchase = Files.readString(PURCHASE);
        String ae = Files.readString(AE_NETWORK);
        String hatch = Files.readString(DISK_HATCH);
        String deliver = extractBlock(purchase, "public static String deliverItems(");
        String batch = extractBlock(purchase, "public static String deliverItemBatch(");
        String direct = extractBlock(purchase, "private static java.math.BigInteger tryDeliverDirectSdaToDiskHatch(");
        String runtime = extractBlock(hatch, "private SlotRuntimeCache getOrCreateSlotRuntime(");

        assertAll(
                () -> assertTrue(deliver.contains("tryDeliverDirectSdaToDiskHatch(player, unit, total)"),
                        "单商品无限元件应优先尝试挂载到同网磁盘仓室"),
                () -> assertTrue(batch.contains("tryDeliverDirectSdaToDiskHatch(player, unit, total)"),
                        "组合商品里的无限元件也应优先尝试挂载到同网磁盘仓室"),
                () -> assertTrue(purchase.contains("createEaeInfinityCellStorage(stack)"),
                        "直接挂载路径必须显式识别无限元件专用存储"),
                () -> assertTrue(ae.contains("MEDiskHatchPartMachine.createEaeInfinityCellStorage(sda)"),
                        "AE 网络层插入校验必须接受 EAE 无限元件专用直接存储"),
                () -> assertTrue(runtime.contains("createEaeInfinityCellStorage(stack)"),
                        "磁盘仓室运行时必须把无限元件挂成可用存储"));
    }

    @Test
    void directDiskHatchDeliveryHasDedicatedSuccessDisplay() throws Exception {
        String purchase = Files.readString(PURCHASE);
        String actionPacket = Files.readString(ACTION_PACKET);
        String deliver = extractBlock(purchase, "public static String deliverItems(");
        String batch = extractBlock(purchase, "public static String deliverItemBatch(");
        String pack = extractBlock(purchase, "private static boolean packAsSdaBatch(");
        String viaText = extractBlock(actionPacket, "String viaText = switch");

        assertAll(
                () -> assertTrue(deliver.contains("return \"disk_hatch\""),
                        "单商品直注入磁盘仓室时不能继续回传 sda"),
                () -> assertTrue(batch.contains("return \"disk_hatch\""),
                        "组合商品或奖励直注入磁盘仓室时不能继续回传 sda"),
                () -> assertTrue(pack.contains("return true"),
                        "自动打包 SDA 直注入磁盘仓室时需要把结果反馈给上层显示"),
                () -> assertTrue(viaText.contains("case \"disk_hatch\""),
                        "购买成功提示必须有磁盘仓室专用分支"),
                () -> assertTrue(viaText.contains("已注入磁盘仓室"),
                        "磁盘仓室直注入不能显示成已打包为超级磁盘阵列"));
    }

    @Test
    void diskHatchInjectionIsLimitedToBoundProviderGrid() throws Exception {
        String ae = Files.readString(AE_NETWORK);
        String hatch = Files.readString(DISK_HATCH);
        String method = extractBlock(ae, "public static boolean injectSdaIntoBoundDiskHatch(");

        assertAll(
                () -> assertTrue(ae.contains("MEDiskHatchPartMachine"),
                        "注入目标必须是山海 ME 磁盘仓室"),
                () -> assertTrue(hatch.contains("ShopAeNetwork.registerDiskHatch(this);"),
                        "磁盘仓室加载时必须注册到商店 AE 网络索引"),
                () -> assertTrue(hatch.contains("ShopAeNetwork.unregisterDiskHatch(this);"),
                        "磁盘仓室卸载时必须取消注册，避免过期引用"),
                () -> assertTrue(method.contains("provider.grid()"),
                        "同网边界必须来自商店终端/FTBQ AE 提交器的绑定源网格"),
                () -> assertTrue(method.contains("hatch.getMainNode().getGrid() != grid"),
                        "不能跨 AE 网络使用其他磁盘仓室"),
                () -> assertTrue(ae.contains("StorageCells.getCellInventory(sda, hatch)"),
                        "插入前必须确认该 SDA 对磁盘仓室是可挂载存储单元"),
                () -> assertTrue(ae.contains("diskSlots.storage.setStackInSlot"),
                        "成功时要直接占用磁盘仓室空槽"));
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
