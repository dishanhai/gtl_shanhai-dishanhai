package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.me.crafting.CraftingStatusMenu;

import com.google.common.collect.ImmutableSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.WeakHashMap;

@Mixin(value = CraftingStatusMenu.class, remap = false)
public interface CraftingStatusMenuAccessor {

    @Accessor("cpuSerialMap")
    WeakHashMap<ICraftingCPU, Integer> gtShanhai$getCpuSerialMapRaw();

    @Accessor("lastCpuSet")
    ImmutableSet<ICraftingCPU> gtShanhai$getLastCpuSetRaw();

    @Accessor("selectedCpuSerial")
    int gtShanhai$getSelectedCpuSerialRaw();

    @Accessor("selectedCpuSerial")
    void gtShanhai$setSelectedCpuSerialRaw(int selectedCpuSerial);

    @Accessor("selectedCpu")
    void gtShanhai$setSelectedCpuRaw(ICraftingCPU selectedCpu);

    @Accessor("selectedCpu")
    ICraftingCPU gtShanhai$getSelectedCpuRaw();

    @Accessor("cpuList")
    void gtShanhai$setCpuListRaw(CraftingStatusMenu.CraftingCpuList cpuList);

    @Invoker("getOrAssignCpuSerial")
    int gtShanhai$callGetOrAssignCpuSerial(ICraftingCPU cpu);

    @Invoker("createCpuList")
    CraftingStatusMenu.CraftingCpuList gtShanhai$callCreateCpuList();
}
