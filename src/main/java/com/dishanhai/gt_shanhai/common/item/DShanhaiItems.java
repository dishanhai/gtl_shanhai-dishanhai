package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.item.ComponentItem;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorBehavior;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiWirelessPatternManagementTerminalItem;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalIntegration;

import static com.dishanhai.gt_shanhai.GTDishanhaiRegistration.REGISTRATE;

public final class DShanhaiItems {

    public static ItemEntry<ComponentItem> DEBUG_PATTERN_TEST;
    public static ItemEntry<ComponentItem> AE_STEAM_COVER;
    public static ItemEntry<ComponentItem> AE_STEAM_BINDER;
    public static ItemEntry<ComponentItem> ULTIMATE_TERMINAL;
    public static ItemEntry<ComponentItem> ADVANCED_PATTERN_BOX;
    public static ItemEntry<ComponentItem> PATTERN_BUFFER_TOOLKIT;
    public static ItemEntry<ComponentItem> PATTERN_GENERATOR;
    public static ItemEntry<SelectedRecipePatternItem> SELECTED_RECIPE_PATTERN;
    public static ItemEntry<ShanhaiWirelessPatternManagementTerminalItem> WIRELESS_PATTERN_MANAGEMENT_TERMINAL;

    public static void init() {
        var steamCover = com.dishanhai.gt_shanhai.common.cover.ShanhaiAeSteamCover.register();
        AE_STEAM_COVER = REGISTRATE.item("ae_steam_cover", ComponentItem::create)
                .onRegister(GTItems.attach(new com.gregtechceu.gtceu.common.item.CoverPlaceBehavior(steamCover),
                        com.dishanhai.gt_shanhai.common.cover.ShanhaiAeSteamCover.ITEM_TOOLTIP))
                .model(NonNullBiConsumer.noop()).register();
        AE_STEAM_BINDER = REGISTRATE.item("ae_steam_binder", ComponentItem::create)
                .properties(properties -> properties.stacksTo(1))
                .onRegister(GTItems.attach(com.dishanhai.gt_shanhai.common.cover.ShanhaiAeSteamBinderBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop()).register();
        DEBUG_PATTERN_TEST = REGISTRATE.item("debug_pattern_test", ComponentItem::create)
                .onRegister(GTItems.attach(ShanhaiPatternTestBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop())
                .register();
        ULTIMATE_TERMINAL = REGISTRATE.item("ultimate_terminal", ComponentItem::create)
                .properties(properties -> properties.stacksTo(1))
                .onRegister(GTItems.attach(ShanhaiUltimateTerminalBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop())
                .register();
        ADVANCED_PATTERN_BOX = REGISTRATE.item("advanced_pattern_box", ComponentItem::create)
                .properties(properties -> properties.stacksTo(1))
                .onRegister(GTItems.attach(AdvancedPatternBoxBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop())
                .register();
        PATTERN_BUFFER_TOOLKIT = REGISTRATE.item("pattern_buffer_toolkit", ComponentItem::create)
                .properties(properties -> properties.stacksTo(1))
                .onRegister(GTItems.attach(PatternBufferToolkitBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop())
                .register();
        PATTERN_GENERATOR = REGISTRATE.item("pattern_generator", ComponentItem::create)
                .properties(properties -> properties.stacksTo(1))
                .onRegister(GTItems.attach(PatternGeneratorBehavior.INSTANCE))
                .model(NonNullBiConsumer.noop())
                .register();
        SELECTED_RECIPE_PATTERN = REGISTRATE.item("selected_recipe_pattern", SelectedRecipePatternItem::new)
                .properties(properties -> properties.stacksTo(1))
                .onRegister(GTItems.attach(SelectedRecipePatternBehavior.INSTANCE))
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
