package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Presentation roles for registered Star Lifter blocks and their installed GT hatches. */
public enum StarLifterHudTarget {
    CORE(Icon.CORE), TERMINAL(Icon.CHAMBER), INTERFACE(Icon.PORT), MANIFOLD(Icon.FLOW), INNER_LATCH(Icon.CHAMBER),
    BEAM(Icon.RING), ARMOR(Icon.RING), CERAMIC(Icon.RING), SOCKET(Icon.PORT), CONDUIT(Icon.FLOW),
    MAGNET(Icon.FIELD), EXTRACTOR(Icon.FLOW), STELLAR(Icon.FIELD), HATCH(Icon.CHAMBER);

    public enum Icon { CORE, CHAMBER, PORT, RING, FLOW, FIELD }

    private static final Map<ResourceLocation, StarLifterHudTarget> BLOCKS = blockRoles();
    public final Icon icon;
    public final String key;

    StarLifterHudTarget(Icon icon) {
        this.icon = icon;
        key = name().toLowerCase(Locale.ROOT);
    }

    private static Map<ResourceLocation, StarLifterHudTarget> blockRoles() {
        var roles = new HashMap<ResourceLocation, StarLifterHudTarget>();
        for (var role : values()) {
            if (role != HATCH) {
                String path = role == CORE ? StarLifterStructure.ID : StarLifterStructure.ID + "_" + role.key;
                roles.put(new ResourceLocation("gt_shanhai", path), role);
            }
        }
        return Map.copyOf(roles);
    }

    public static StarLifterHudTarget forBlock(Block block) {
        var id = ForgeRegistries.BLOCKS.getKey(block);
        return id == null ? null : BLOCKS.get(id);
    }

    public static StarLifterHudTarget forClientBlock(Block block, BlockEntity entity) {
        var direct = forBlock(block);
        if (direct != null) return direct;
        if (entity instanceof MetaMachineBlockEntity holder && holder.getMetaMachine() instanceof IMultiPart part) {
            var controllers = part.getControllers();
            if (controllers.size() == 1 && controllers.get(0) instanceof StarLifterControllerMachine) return HATCH;
        }
        return null;
    }
}
