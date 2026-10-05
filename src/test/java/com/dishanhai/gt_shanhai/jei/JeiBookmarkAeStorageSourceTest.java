package com.dishanhai.gt_shanhai.jei;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class JeiBookmarkAeStorageSourceTest {

    private static final Path BUTTONS = source("integration", "jei", "JeiPatternQuickEncodeButtons.java");
    private static final Path REQUEST = source("network", "JeiBookmarkMissingItemsRequestPacket.java");
    private static final Path RESPONSE = source("network", "JeiBookmarkMissingItemsResponsePacket.java");
    private static final Path NETWORK = source("network", "ShanhaiNetwork.java");

    @Test
    void missingModeUsesDisplayedItemAndFluidIngredientsAndServerAeStorage() throws Exception {
        String buttons = Files.readString(BUTTONS);

        assertTrue(buttons.contains("getDisplayedIngredient()"));
        assertTrue(buttons.contains("GenericStack.fromItemStack"));
        assertTrue(buttons.contains("GenericStack.fromFluidStack"));
        assertTrue(buttons.contains("JeiBookmarkMissingItemsRequestPacket"));
        assertTrue(buttons.contains("[JEIBookmarkDiag]"));
        assertFalse(buttons.contains("countInInventory"));
        assertFalse(buttons.contains("player.getInventory().items"));
    }

    @Test
    void requestAndResponseCarryGenericStacksAndAreAppendedWithFixedDirections() throws Exception {
        assertTrue(Files.exists(REQUEST));
        assertTrue(Files.exists(RESPONSE));
        String request = Files.exists(REQUEST) ? Files.readString(REQUEST) : "";
        String response = Files.exists(RESPONSE) ? Files.readString(RESPONSE) : "";
        String network = Files.readString(NETWORK);

        assertTrue(request.contains("GenericStack.writeBuffer"));
        assertTrue(request.contains("GenericStack.readBuffer"));
        assertTrue(request.contains("MEStorageMenu"));
        assertTrue(request.contains("getNetworkNode"));
        assertTrue(request.contains("getCraftingFor"));
        assertTrue(request.contains("getPrimaryOutput"));
        assertTrue(request.contains("JeiBookmarkAmountParser.isEffectivelyEnough"));
        assertTrue(request.contains("case MISSING_ITEMS -> have < needed"));
        assertTrue(request.contains("!craftable && !enough"));
        assertTrue(request.contains("case NO_RECIPE_ITEMS -> !craftable"));
        assertTrue(request.contains("getStorageService().getInventory()"));
        assertTrue(response.contains("JeiBookmarkBridge.addItemStacks"));
        assertTrue(response.contains("JeiBookmarkBridge.addFluidStacks"));
        assertTrue(response.contains("GenericStack.writeBuffer"));
        assertTrue(response.contains("GenericStack.readBuffer"));
        int requestRegistration = network.indexOf("JeiBookmarkMissingItemsRequestPacket.class");
        int responseRegistration = network.indexOf("JeiBookmarkMissingItemsResponsePacket.class");
        assertTrue(requestRegistration >= 0);
        assertTrue(responseRegistration > requestRegistration);
        assertTrue(network.substring(requestRegistration).contains("NetworkDirection.PLAY_TO_SERVER"));
        assertTrue(network.substring(responseRegistration).contains("NetworkDirection.PLAY_TO_CLIENT"));
    }

    private static Path source(String... parts) {
        Path path = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");
        for (String part : parts) path = path.resolve(part);
        return path;
    }
}
