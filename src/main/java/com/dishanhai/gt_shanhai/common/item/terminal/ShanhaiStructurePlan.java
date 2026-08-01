package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

public final class ShanhaiStructurePlan {

    public enum Kind {
        SATISFIED,
        PLACE,
        REPLACE,
        FORCE_REPLACE,
        CHAMBER_HINT,
        BLOCKED,
        MANUAL
    }

    public record Entry(
            BlockPos pos,
            Kind kind,
            ItemStack desired,
            BlockState desiredState,
            ItemStack current,
            BlockState currentState,
            List<ItemStack> candidates,
            boolean chamberCapable) {

        public Entry(BlockPos pos, Kind kind, ItemStack desired, ItemStack current,
                     List<ItemStack> candidates, boolean chamberCapable) {
            this(pos, kind, desired, stateFromItem(desired), current, stateFromItem(current),
                    candidates, chamberCapable);
        }

        public Entry {
            pos = pos.immutable();
            desired = desired == null ? ItemStack.EMPTY : desired.copyWithCount(1);
            desiredState = desiredState == null ? Blocks.AIR.defaultBlockState() : desiredState;
            current = current == null ? ItemStack.EMPTY : current.copyWithCount(1);
            currentState = currentState == null ? Blocks.AIR.defaultBlockState() : currentState;
            List<ItemStack> copies = new ArrayList<>();
            if (candidates != null) {
                for (ItemStack candidate : candidates) {
                    if (candidate != null && !candidate.isEmpty()) copies.add(candidate.copyWithCount(1));
                }
            }
            candidates = Collections.unmodifiableList(copies);
        }

        public boolean requiresMaterial() {
            return requiresBuild() && !requiresFluid() && !desired.isEmpty();
        }

        public boolean requiresFluid() {
            return requiresBuild() && desiredFluid() != Fluids.EMPTY;
        }

        public boolean requiresBuild() {
            return (kind == Kind.PLACE || kind == Kind.REPLACE || kind == Kind.FORCE_REPLACE)
                    && (!desired.isEmpty() || desiredFluid() != Fluids.EMPTY);
        }

        public Fluid desiredFluid() {
            if (desiredState == null || !(desiredState.getBlock() instanceof LiquidBlock)
                    || desiredState.getFluidState().isEmpty()) return Fluids.EMPTY;
            return desiredState.getFluidState().getType();
        }

        public Fluid currentFluid() {
            if (currentState == null || !(currentState.getBlock() instanceof LiquidBlock)
                    || currentState.getFluidState().isEmpty()) return Fluids.EMPTY;
            return currentState.getFluidState().getType();
        }

        private static BlockState stateFromItem(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return Blocks.AIR.defaultBlockState();
            var block = net.minecraft.world.level.block.Block.byItem(stack.getItem());
            return block == Blocks.AIR ? Blocks.AIR.defaultBlockState() : block.defaultBlockState();
        }
    }

    private final GlobalPos target;
    private final String machineId;
    private final boolean mirrored;
    private final int repeatCount;
    private final List<Entry> entries;
    private final String fingerprint;

    public ShanhaiStructurePlan(GlobalPos target, String machineId, boolean mirrored,
                                int repeatCount, List<Entry> entries) {
        this.target = target;
        this.machineId = machineId == null ? "" : machineId;
        this.mirrored = mirrored;
        this.repeatCount = repeatCount;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.fingerprint = createFingerprint();
    }

    public GlobalPos target() {
        return target;
    }

    public String machineId() {
        return machineId;
    }

    public boolean mirrored() {
        return mirrored;
    }

    public int repeatCount() {
        return repeatCount;
    }

    public List<Entry> entries() {
        return entries;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public ShanhaiTerminalMaterialPlan materials() {
        ShanhaiTerminalMaterialPlan materials = new ShanhaiTerminalMaterialPlan();
        for (Entry entry : entries) {
            if (entry.requiresMaterial()) {
                var id = ForgeRegistries.ITEMS.getKey(entry.desired().getItem());
                if (id != null) materials.require(id.toString(), 1);
            } else if (entry.requiresFluid()) {
                var id = ForgeRegistries.FLUIDS.getKey(entry.desiredFluid());
                if (id != null) materials.require("fluid:" + id, 1000);
            }
        }
        return materials;
    }

    private String createFingerprint() {
        StringBuilder value = new StringBuilder();
        value.append(target.dimension().location()).append('|').append(target.pos().asLong())
                .append('|').append(machineId).append('|').append(mirrored).append('|').append(repeatCount);
        for (Entry entry : entries) {
            value.append(';').append(entry.pos().asLong()).append(':').append(entry.kind());
            value.append(':').append(entry.chamberCapable());
        }
        return Integer.toUnsignedString(value.toString().hashCode(), 16);
    }
}
