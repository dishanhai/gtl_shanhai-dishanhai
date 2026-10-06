package com.dishanhai.gt_shanhai.common.heat;

import com.gregtechceu.gtceu.common.block.CoilBlock;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * 把额外挂载槽中的物品解析为判定核心使用的能力快照。
 */
public final class ShanhaiHeatSources {

    private ShanhaiHeatSources() {}

    public static final String SC_BASIC_ID = "gtlcore:stellar_containment_casing";
    public static final String SC_ADVANCED_ID = "gtlcore:advanced_stellar_containment_casing";
    public static final String SC_ULTIMATE_ID = "gtlcore:ultimate_stellar_containment_casing";
    public static final int MAX_CONTAINMENT_TIER = 3;

    public static final Map<String, Integer> HATCH_CLEANROOM_TIER = Map.ofEntries(
            Map.entry("gtceu:cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_PLAIN),
            Map.entry("gtceu:cleaning_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_PLAIN),
            Map.entry("gtceu:cleaning_gravity_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_PLAIN),
            Map.entry("gtceu:sterile_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_STERILE),
            Map.entry("gtceu:sterile_configuration_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_STERILE),
            Map.entry("gtceu:sterile_cleaning_gravity_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_STERILE),
            Map.entry("gtceu:law_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_LAW),
            Map.entry("gtceu:law_configuration_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_LAW),
            Map.entry("gtceu:law_cleaning_gravity_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_LAW),
            Map.entry("gt_shanhai:cosmic_clean_gravity_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_LAW));

    public static final Set<String> HATCH_GRAVITY_IDS = Set.of(
            "gtceu:gravity_hatch",
            "gtceu:gravity_configuration_hatch",
            "gtceu:cleaning_gravity_configuration_maintenance_hatch",
            "gtceu:sterile_cleaning_gravity_configuration_maintenance_hatch",
            "gtceu:law_cleaning_gravity_configuration_maintenance_hatch",
            "gt_shanhai:cosmic_clean_gravity_maintenance_hatch");

    public static final String CREATIVE_DATA_ACCESS_HATCH_ID = "gtceu:creative_data_access_hatch";

    @NotNull
    public static ShanhaiHeatGate.SlotContent slotContentOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ShanhaiHeatGate.SlotContent.EMPTY;
        }
        int count = stack.getCount();
        Block block = blockOf(stack);
        if (block instanceof CoilBlock coilBlock && coilBlock.coilType != null) {
            return new ShanhaiHeatGate.SlotContent(
                    count, coilBlock.coilType.getCoilTemperature(), 0,
                    ShanhaiHeatGate.CLEANROOM_NONE, false, false, Set.of());
        }

        int containmentTier = containmentTierOf(block);
        if (containmentTier > 0) {
            return new ShanhaiHeatGate.SlotContent(
                    count, 0, containmentTier, ShanhaiHeatGate.CLEANROOM_NONE,
                    false, false, Set.of());
        }

        String itemId = itemIdOf(stack);
        if (itemId == null) {
            return ShanhaiHeatGate.SlotContent.EMPTY;
        }
        Integer cleanroomTier = HATCH_CLEANROOM_TIER.get(itemId);
        boolean gravity = HATCH_GRAVITY_IDS.contains(itemId);
        boolean research = CREATIVE_DATA_ACCESS_HATCH_ID.equals(itemId);
        String dimension = ShanhaiHeatGate.dimensionOfFragment(itemId);
        if (cleanroomTier == null && !gravity && !research && dimension == null) {
            return new ShanhaiHeatGate.SlotContent(
                    count, 0, 0, ShanhaiHeatGate.CLEANROOM_NONE,
                    false, false, Set.of());
        }
        return new ShanhaiHeatGate.SlotContent(
                count, 0, 0,
                cleanroomTier == null ? ShanhaiHeatGate.CLEANROOM_NONE : cleanroomTier,
                gravity, research,
                dimension == null ? Set.of() : Set.of(dimension));
    }

    @Nullable
    private static Block blockOf(@NotNull ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    @Nullable
    public static String itemIdOf(@NotNull ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    public static int containmentTierOf(@Nullable Block block) {
        if (block == null) {
            return 0;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) {
            return 0;
        }
        return switch (key.toString()) {
            case SC_BASIC_ID -> 1;
            case SC_ADVANCED_ID -> 2;
            case SC_ULTIMATE_ID -> 3;
            default -> 0;
        };
    }
}
