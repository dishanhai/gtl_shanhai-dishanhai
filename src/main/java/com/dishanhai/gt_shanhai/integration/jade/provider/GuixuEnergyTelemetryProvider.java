package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuTelemetryFormat;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import java.math.BigInteger;

public enum GuixuEnergyTelemetryProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;
    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "guixu_energy_telemetry");

    @Override public ResourceLocation getUid() { return UID; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity be)
                || !(be.getMetaMachine() instanceof GuixuVisionCoreMachine machine)) return;
        CompoundTag tag = new CompoundTag();
        write(tag, "generation", machine.actualGenerationPerTick());
        var in = machine.captureEnergyDisplay(IO.IN);
        var out = machine.captureEnergyDisplay(IO.OUT);
        write(tag, "inStored", in.stored());
        write(tag, "inCapacity", in.capacity());
        write(tag, "outStored", out.stored());
        write(tag, "outCapacity", out.capacity());
        tag.putInt("flags", in.flags() | out.flags() << 4);
        data.put("gtShanhaiGuixuEnergy", tag);
    }

    private static void write(CompoundTag tag, String key, BigInteger value) {
        if (value != null) tag.putByteArray(key, value.toByteArray());
    }

    private static BigInteger read(CompoundTag tag, String key) {
        byte[] value = tag.getByteArray(key);
        return value.length == 0 ? null : new BigInteger(value);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag tag = accessor.getServerData().getCompound("gtShanhaiGuixuEnergy");
        if (tag.isEmpty()) return;
        String unavailable = Component.translatable("gt_shanhai.guixu.telemetry.unavailable").getString();
        String noHatch = Component.translatable("gt_shanhai.guixu.telemetry.no_hatch").getString();
        int flags = tag.getInt("flags");
        tooltip.add(Component.translatable("gt_shanhai.guixu.telemetry.generation", GuixuTelemetryFormat.number(read(tag, "generation"))));
        tooltip.add(Component.translatable("gt_shanhai.guixu.telemetry.input", GuixuTelemetryFormat.energy(
                read(tag, "inStored"), read(tag, "inCapacity"), flags & 15, noHatch, unavailable)));
        tooltip.add(Component.translatable("gt_shanhai.guixu.telemetry.output", GuixuTelemetryFormat.energy(
                read(tag, "outStored"), read(tag, "outCapacity"), (flags >>> 4) & 15, noHatch, unavailable)));
    }
}
