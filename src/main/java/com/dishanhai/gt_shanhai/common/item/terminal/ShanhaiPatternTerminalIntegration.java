package com.dishanhai.gt_shanhai.common.item.terminal;

import appeng.api.features.GridLinkables;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;
import appeng.items.tools.powered.WirelessTerminalItem;
import de.mari_023.ae2wtlib.AE2wtlib;
import de.mari_023.ae2wtlib.terminal.IUniversalWirelessTerminalItem;
import de.mari_023.ae2wtlib.wut.WUTHandler;

public final class ShanhaiPatternTerminalIntegration {

    public static final String MODULE_ID = "shanhai_pattern_management";

    private static ShanhaiWirelessPatternManagementTerminalItem registeredItem;
    private static boolean ae2wtlibReady;
    private static boolean initialized;

    public static void onItemRegistered(ShanhaiWirelessPatternManagementTerminalItem item) {
        registeredItem = item;
        tryRegister();
    }

    public static void onAe2wtlibReady() {
        ae2wtlibReady = true;
        tryRegister();
    }

    private static void tryRegister() {
        if (initialized || !ae2wtlibReady || registeredItem == null || AE2wtlib.UNIVERSAL_TERMINAL == null) return;
        ShanhaiStellarRemoteUIFactory.register();

        ShanhaiWirelessPatternManagementTerminalItem item = registeredItem;
        IUniversalWirelessTerminalItem terminalItem = item;
        WUTHandler.addTerminal(
                MODULE_ID,
                terminalItem::tryOpen,
                ShanhaiPatternManagementMenuHost::new,
                ShanhaiPatternManagementMenu.TYPE,
                terminalItem,
                "wireless_pattern_management_terminal",
                "item.gt_shanhai.wireless_pattern_management_terminal");
        GridLinkables.register(item, WirelessTerminalItem.LINKABLE_HANDLER);
        Upgrades.add(AEItems.ENERGY_CARD, item, 2, GuiText.WirelessTerminals.getTranslationKey());
        Upgrades.add(AE2wtlib.QUANTUM_BRIDGE_CARD, item, 1, GuiText.WirelessTerminals.getTranslationKey());
        initialized = true;
    }

    private ShanhaiPatternTerminalIntegration() {}
}
