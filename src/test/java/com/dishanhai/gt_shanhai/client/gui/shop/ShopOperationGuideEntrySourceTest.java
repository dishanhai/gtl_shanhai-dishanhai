package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopOperationGuideEntrySourceTest {
    private static final Path SHOP = Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop");

    @Test
    void storefrontOffersAPersistentReplayEntryThroughItsNativeToolbar() throws IOException {
        String screen = Files.readString(SHOP.resolve("ShopScreen.java"));
        assertTrue(extractBlock(screen, "private Component toolbarLabel(")
                .contains("case OPERATION_GUIDE -> \"gt_shanhai.shop.operation_guide.button\""));
        String replay = extractBlock(extractBlock(screen, "private void activateToolbar("), "case OPERATION_GUIDE ->");
        assertTrue(replay.contains("setFocused(button)"));
        assertTrue(replay.contains("Minecraft.getInstance().setScreen(new ShopOperationGuideScreen(this))"));
    }

    @Test
    void welcomeOffersOptionalPracticeAndReturnsToTheSameWizard() throws IOException {
        String welcome = Files.readString(SHOP.resolve("ShopWelcomeScreen.java"));
        String init = extractBlock(welcome, "protected void initScaled()");
        assertTrue(init.contains("addWidget(new ShopNativeButton("));
        assertTrue(init.contains("new ShopOperationGuideScreen(this)"));
        assertTrue(extractBlock(welcome, "private void setPage(").contains("page == 0 && !availabilityOnly"));
        assertTrue(extractBlock(welcome, "private void drawFooter(").contains("operationGuideButton.render("));
    }

    @Test
    void availabilitySyncKeepsLocalPracticeOpenAndRefreshesTheWizardOnReturn() throws IOException {
        String availability = Files.readString(SHOP.resolve("../../shop/ClientShopAvailability.java").normalize());
        assertTrue(extractBlock(availability, "public static void apply(")
                .contains("!(mc.screen instanceof ShopOperationGuideScreen)"));
        String welcome = Files.readString(SHOP.resolve("ShopWelcomeScreen.java"));
        assertTrue(extractBlock(welcome, "protected void initScaled()")
                .contains("if (!globalEdited) globalDisabled = ClientShopAvailability.globalDisabled()"));
    }
}
