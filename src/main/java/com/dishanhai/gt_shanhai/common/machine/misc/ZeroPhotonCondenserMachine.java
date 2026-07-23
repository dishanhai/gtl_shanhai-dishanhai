package com.dishanhai.gt_shanhai.common.machine.misc;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import org.gtlcore.gtlcore.common.data.GTLRecipeModifiers;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;

public class ZeroPhotonCondenserMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    public ZeroPhotonCondenserMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public long getMaxVoltage() {
        return GTValues.VH[GTValues.HV];
    }

    @Override
    public int getMaxParallel() {
        int hatchParallel = GTLRecipeModifiers.getHatchParallel(this);
        return Math.max(4, hatchParallel);
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block casing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "hv_machine_casing"));

        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK)
                .aisle("BBB", "B~B", "BBB")
                .aisle("BBB", "BBB", "BBB")
                .aisle("BBB", "BBB", "BBB")
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('B', Predicates.blocks(casing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setPreviewCount(1)))
                .build();
    }
}
