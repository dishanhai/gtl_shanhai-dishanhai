package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.item.ComponentItem;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiWirelessPatternManagementTerminalItem;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalIntegration;

import static com.dishanhai.gt_shanhai.GTDishanhaiRegistration.REGISTRATE;

public final class DShanhaiItems {

    public static ItemEntry<ComponentItem> DEBUG_PATTERN_TEST;
    public static ItemEntry<ComponentItem> ULTIMATE_TERMINAL;
    public static ItemEntry<ShanhaiWirelessPatternManagementTerminalItem> WIRELESS_PATTERN_MANAGEMENT_TERMINAL;

    public static void init() {
        DEBUG_PATTERN_TEST = REGISTRATE.item("debug_pattern_test", ComponentItem::create)
                .onRegister(GTItems.attach(ShanhaiPatternTestBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop())
                .register();
        ULTIMATE_TERMINAL = REGISTRATE.item("ultimate_terminal", ComponentItem::create)
                .properties(properties -> properties.stacksTo(1))
                .onRegister(GTItems.attach(ShanhaiUltimateTerminalBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop())
                .register();
        WIRELESS_PATTERN_MANAGEMENT_TERMINAL = REGISTRATE
                .item("wireless_pattern_management_terminal",
                        properties -> new ShanhaiWirelessPatternManagementTerminalItem())
                .onRegister(ShanhaiPatternTerminalIntegration::onItemRegistered)
                .model(NonNullBiConsumer.noop())
                .register();
    }

    private DShanhaiItems() {}
}
