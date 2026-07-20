package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

public final class ShanhaiChamberClassifier {

    public enum Type {
        INPUT("gui.gt_shanhai.ultimate_terminal.chamber.input", 0xFF3B30),
        OUTPUT("gui.gt_shanhai.ultimate_terminal.chamber.output", 0x2F80ED),
        ENERGY("gui.gt_shanhai.ultimate_terminal.chamber.energy", 0xFFD43B),
        MAINTENANCE("gui.gt_shanhai.ultimate_terminal.chamber.maintenance", 0xA0A0A0),
        PARALLEL("gui.gt_shanhai.ultimate_terminal.chamber.parallel", 0xA855F7),
        MUFFLER("gui.gt_shanhai.ultimate_terminal.chamber.muffler", 0xFF8A2A),
        DATA("gui.gt_shanhai.ultimate_terminal.chamber.data", 0x22C7D6),
        OTHER("gui.gt_shanhai.ultimate_terminal.chamber.other", 0xFFFFFF);

        private final String translationKey;
        private final int color;

        Type(String translationKey, int color) {
            this.translationKey = translationKey;
            this.color = color;
        }

        public String translationKey() {
            return translationKey;
        }

        public int color() {
            return color;
        }
    }

    public record Selection(Type type, ItemStack candidate) {

        public Selection {
            candidate = candidate.copyWithCount(1);
        }
    }

    private static volatile Set<Block> chamberBlocks;

    private ShanhaiChamberClassifier() {}

    public static Optional<Selection> firstCandidate(List<ItemStack> candidates) {
        if (candidates == null) return Optional.empty();
        for (ItemStack candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            Block block = Block.byItem(candidate.getItem());
            if (!isChamberBlock(block)) continue;
            var id = ForgeRegistries.BLOCKS.getKey(block);
            Type type = id == null ? Type.OTHER : classifyPath(id.getPath());
            return Optional.of(new Selection(type, candidate));
        }
        return Optional.empty();
    }

    static Type classifyPath(String path) {
        String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (value.contains("parallel")) return Type.PARALLEL;
        if (value.contains("maintenance")) return Type.MAINTENANCE;
        if (containsAny(value, "energy", "power", "dynamo", "substation", "laser")) return Type.ENERGY;
        if (value.contains("muffler")) return Type.MUFFLER;
        if (containsAny(value, "data", "optical", "computation", "object_holder")) return Type.DATA;
        if (containsAny(value, "output", "export")) return Type.OUTPUT;
        if (containsAny(value, "input", "import")) return Type.INPUT;
        return Type.OTHER;
    }

    public static boolean isChamberBlock(Block block) {
        return block instanceof MetaMachineBlock && getChamberBlocks().contains(block);
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static Set<Block> getChamberBlocks() {
        Set<Block> cached = chamberBlocks;
        if (cached != null) return cached;
        Set<Block> result = new LinkedHashSet<>();
        GTRegistries.MACHINES.forEach(definition -> {
            if (definition.getRecipeTypes() != null || definition instanceof MultiblockMachineDefinition) return;
            Block block = definition.getBlock();
            var blockEntity = definition.getBlockEntityType().create(BlockPos.ZERO, block.defaultBlockState());
            if (blockEntity instanceof IMachineBlockEntity machineBlockEntity
                    && definition.createMetaMachine(machineBlockEntity) instanceof MultiblockPartMachine) {
                result.add(block);
            }
        });
        chamberBlocks = Collections.unmodifiableSet(result);
        return chamberBlocks;
    }
}
