package com.dishanhai.gt_shanhai.common;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.block.DShanhaiAE2Blocks;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import com.tterrag.registrate.util.entry.RegistryEntry;

import static com.dishanhai.gt_shanhai.GTDishanhaiRegistration.REGISTRATE;

public class DShanhaiCreativeModeTabs {

    public static RegistryEntry<CreativeModeTab> TAB_DISHANHAI;

    public static void init() {
        TAB_DISHANHAI = REGISTRATE.defaultCreativeTab(
                "machine",
                builder -> builder
                        .title(net.minecraft.network.chat.Component.literal("山海的神人私货"))
                        .icon(() -> {
                            var items = REGISTRATE.getAll(Registries.ITEM);
                            if (items.isEmpty()) return ItemStack.EMPTY;
                            return items.stream()
                                    .map(e -> e.get())
                                    .filter(i -> i instanceof net.minecraft.world.item.Item)
                                    .map(i -> new ItemStack((net.minecraft.world.item.Item) i))
                                    .findFirst()
                                    .orElse(ItemStack.EMPTY);
                        })
                        .displayItems((parameters, output) -> {
                            for (RegistryEntry<?> entry : REGISTRATE.getAll(Registries.ITEM)) {
                                if (REGISTRATE.isInCreativeTab(entry, TAB_DISHANHAI)) {
                                    Object obj = entry.get();
                                    if (obj instanceof net.minecraft.world.item.Item item) {
                                        output.accept(new ItemStack(item));
                                    } else if (obj instanceof net.minecraft.world.level.block.Block block) {
                                        output.accept(new ItemStack(block));
                                    }
                                }
                            }
                            output.accept(new ItemStack(GTDishanhaiMod.ETERNAL_WORKSHOP_DATA_MODULE.get()));
                            output.accept(new ItemStack(GTDishanhaiMod.GRAVITON_SHARD.get()));
                            output.accept(new ItemStack(GTDishanhaiMod.VIRTUAL_ITEM_PROVIDER.get()));
                            output.accept(new ItemStack(GTDishanhaiMod.GUIDE_BOOK.get()));
                            output.accept(new ItemStack(DShanhaiAE2Blocks.QUANTUM_COMPUTER_ITEM.get()));
                            output.accept(new ItemStack(DShanhaiAE2Blocks.QUANTUM_COMPUTER_UNIT_ITEM.get()));
                            output.accept(new ItemStack(DShanhaiAE2Blocks.QUANTUM_PARALLEL_PROCESSOR_ITEM.get()));
                            output.accept(new ItemStack(DShanhaiAE2Blocks.QUANTUM_CRAFTING_STORAGE_ITEM.get()));
                            output.accept(new ItemStack(DShanhaiAE2Blocks.QUANTUM_STRUCTURE_ITEM.get()));
                        })
        ).register();
    }

    private DShanhaiCreativeModeTabs() {}
}
