package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海商店禁用：配置、权限与所有会扣款/交付的网络入口契约。 */
class ShopAvailabilitySourceTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    private static String read(String path) throws Exception {
        return Files.readString(ROOT.resolve(path));
    }

    @Test
    void globalAndPersonalFlagsPersistSeparatelyAndDefaultToEnabled() throws Exception {
        String config = read("config/DShanhaiConfig.java");
        assertTrue(config.contains("shopDisabled"));
        assertTrue(config.contains(".define(\"disabled\", false)"));
        String client = read("client/shop/ClientShopUiSettings.java");
        assertTrue(client.contains("root.addProperty(\"shopDisabled\", shopDisabled)"));
        assertTrue(client.contains("root.has(\"shopDisabled\")"));
        assertTrue(client.contains("shopDisabled = false;"));
    }

    @Test
    void globalUpdatesUseServerOpPermissionRatherThanEditorWhitelist() throws Exception {
        String packet = read("network/ShopAvailabilityPacket.java");
        assertTrue(packet.contains("player.hasPermissions(2)"));
        assertFalse(packet.contains("ShopEditPermission.canEdit"));
        assertTrue(packet.contains("shopDisabled.save()"));
        assertTrue(packet.contains("NetworkDirection.PLAY_TO_SERVER"));
        assertTrue(packet.contains("NetworkDirection.PLAY_TO_CLIENT"));
        assertTrue(packet.indexOf("if (!player.hasPermissions(2))") < packet.indexOf("shopDisabled.set("));
        assertTrue(packet.contains("packet.action == Action.QUERY || packet.action == Action.UPDATE"));
        assertTrue(packet.contains("packet.action == Action.STATE || packet.action == Action.RESULT"));
    }

    @Test
    void wizardOffersPersonalControlToEveryoneAndGlobalControlOnlyToOps() throws Exception {
        String wizard = read("client/gui/shop/ShopWelcomeScreen.java");
        assertTrue(wizard.contains("个人禁用商店"));
        assertTrue(wizard.contains("全局禁用商店"));
        assertTrue(wizard.contains("ClientShopAvailability.canConfigureGlobal()"));
        assertTrue(wizard.contains("ClientShopUiSettings.setShopDisabled("));
        assertTrue(wizard.contains("ShopAvailabilityPacket.update("));
        assertTrue(wizard.contains("Minecraft.getInstance().setScreen(next.get())"),
                "完成或退出恢复配置后必须回到商店主页，由禁用窗口拦截操作");
    }

    @Test
    void disabledPlayersCanStillOpenTheShopHomePage() throws Exception {
        String opener = read("client/gui/shop/ShopScreenOpener.java");
        assertTrue(opener.contains("new ShopScreen("));
        assertFalse(opener.contains("ShopWelcomeScreen.availability("));
        assertTrue(read("network/ShopOpenPacket.java").contains("ShopAvailabilityPacket.sendTo(player)"));
        assertTrue(read("client/gui/shop/ShopWizardCommand.java").contains("new ShopWelcomeScreen("));
    }

    @Test
    void serverBlocksTransactionsEvenForAlreadyOpenScreens() throws Exception {
        for (String packet : new String[]{"ShopActionPacket", "ShopSubmitPacket", "ShopBankActionPacket",
                "ShopMembershipBuyPacket", "ShopAutoCraftRequestPacket", "ShopAutoCraftConfirmPacket",
                "CurrencyActionPacket", "CurrencyQuickSellPacket", "ExchangePacket"}) {
            assertTrue(read("network/" + packet + ".java").contains("ShopAvailability.requireEnabled(player)"),
                    packet + " 必须在任何扣款/交付之前校验全局禁用");
        }
        assertTrue(read("common/shop/ShopAutoCraft.java").contains("ShopAvailability.isDisabled()"),
                "已经排队的 AE 商店计划也必须在禁用后停止");
    }

    @Test
    void availabilitySyncRunsOnLoginAndProtocolVersionChanges() throws Exception {
        assertTrue(read("common/event/ShopCatalogSyncEventHandler.java").contains("ShopAvailabilityPacket.sendTo(player)"));
        String network = read("network/ShanhaiNetwork.java");
        assertTrue(network.contains("ShopAvailabilityPacket.class"));
        assertFalse(network.contains("PROTOCOL_VERSION = \"13\""));
    }

    @Test
    void logoutClearsServerStateButPreservesPersonalConfig() throws Exception {
        String client = read("client/shop/ClientShopAvailability.java");
        String logout = client.substring(client.indexOf("public static void onLogout("));
        assertTrue(logout.contains("globalDisabled = false;"));
        assertTrue(logout.contains("canConfigureGlobal = false;"));
        assertFalse(logout.contains("setShopDisabled"));
    }

    @Test
    void everyWalletAndCommandOpeningPushesAvailabilityBeforeOpening() throws Exception {
        for (String path : new String[]{"network/WalletOpenRequestPacket.java", "common/item/WalletItem.java",
                "command/DShanhaiCommands.java"}) {
            assertTrue(read(path).contains("ShopOpenPacket.sendTo("), path);
        }
        String packet = read("network/ShopOpenPacket.java");
        assertTrue(packet.indexOf("ShopAvailabilityPacket.sendTo(player)") < packet.indexOf("new ShopOpenPacket("));
    }

    @Test
    void globalSaveWaitsForServerResultAndSkipDoesNotSaveChoices() throws Exception {
        String wizard = read("client/gui/shop/ShopWelcomeScreen.java");
        assertTrue(wizard.contains("if (awaitingGlobalSave && updateResult)"));
        assertTrue(wizard.contains("if (save) {"));
        assertTrue(wizard.contains("awaitingGlobalSave = true;"));
        assertTrue(wizard.contains("finish(false);"));
    }

    @Test
    void bankCommandsCannotBypassThePacketGuards() throws Exception {
        String commands = read("command/DShanhaiCommands.java");
        for (String name : new String[]{"execBankDeposit", "execBankWithdraw", "execBankBorrow", "execBankRepay"}) {
            int start = commands.indexOf("private static int " + name + "(");
            assertTrue(start >= 0, name);
            int end = commands.indexOf("private static int ", start + 1);
            String method = commands.substring(start, end >= 0 ? end : commands.length());
            int guard = method.indexOf("ShopAvailability.requireEnabled(player)");
            int mutation = method.indexOf("WalletAccountAPI.bank");
            assertTrue(guard >= 0 && mutation > guard, name + " 必须在银行操作之前校验全局禁用");
        }
    }

    @Test
    void disabledModalHasPriorityOverEveryOtherOverlayAndInputPath() throws Exception {
        String shop = read("client/gui/shop/ShopScreen.java");
        assertTrue(shop.contains("renderDisabledOverlay(g, mx, my)"));
        assertTrue(shop.contains("handleDisabledClick(mx, my, btn)"));
        assertTrue(shop.indexOf("renderDisabledOverlay(g, mx, my)") < shop.indexOf("renderStageLockOverlay(g, mx, my)"));
        assertTrue(shop.indexOf("handleDisabledClick(mx, my, btn)") < shop.indexOf("return handleContextMenuClick(mx, my)"));
        for (String method : new String[]{"universalMouseScrolled", "universalMouseDragged", "universalMouseReleased", "keyPressed"}) {
            int start = shop.indexOf(" " + method + "(");
            assertTrue(start >= 0, method);
            assertTrue(shop.substring(start, Math.min(shop.length(), start + 600)).contains("ClientShopAvailability.isDisabled()"), method);
        }
        int chars = shop.indexOf("public boolean charTyped(");
        assertTrue(chars >= 0 && shop.substring(chars, chars + 200).contains("modalLayerOpen()"),
                "字元输入复用统一的禁用／浮层门控");
        int modal = shop.indexOf("private boolean modalLayerOpen()");
        assertTrue(modal >= 0 && shop.substring(modal, modal + 250).contains("ClientShopAvailability.isDisabled()"));
        assertTrue(shop.contains("ShopWelcomeScreen.availability(() -> this)"));
        assertTrue(shop.contains("§n[开启个人商店]"));
        assertTrue(shop.contains("§n[开启全局商店]"));
    }

    @Test
    void previewsEditsFavoritesAndCartRequestsAreAlsoRejected() throws Exception {
        for (String packet : new String[]{"ShopFavoriteEditPacket", "ShopCartEditPacket", "ShopEditPacket",
                "ShopToggleHiddenPacket", "ShopReorderPacket", "ShopCategoryReorderPacket", "ShopBatchManagePacket",
                "ShopBranchCopyPacket", "ShopQuestLinkEditPacket", "ShopStageEditPacket", "ShopSettingsPacket",
                "ShopCostPreviewRequestPacket", "ShopStagePreviewRequestPacket", "ShopBankQueryRequestPacket",
                "CurrencyAeBalanceRequestPacket", "ExchangeEditPacket"}) {
            assertTrue(read("network/" + packet + ".java").contains("ShopAvailability.requireEnabled(player)"), packet);
        }
    }

    @Test
    void personalDisableIsSyncedToServerAndClearedOnLogout() throws Exception {
        String packet = read("network/ShopAvailabilityPacket.java");
        assertTrue(packet.contains("Action.PERSONAL"));
        assertTrue(packet.contains("ShopAvailability.setPersonalDisabled(player.getUUID(), packet.disabled)"));
        assertTrue(read("client/shop/ClientShopAvailability.java").contains("ClientPlayerNetworkEvent.LoggingIn"));
        assertTrue(read("client/shop/ClientShopUiSettings.java").contains("ClientShopAvailability.syncPersonal()"));
        assertTrue(read("common/event/ShopCatalogSyncEventHandler.java").contains("ShopAvailability.clearPersonal"));
    }
}
