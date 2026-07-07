package com.dishanhai.gt_shanhai.common.machine.nebula;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 天界虹吸矩阵 — IV级无线电网取电，256~10240并行（按输入流体）。
 *
 * 遍历部件流体仓，用 setFluidInTank 手动扣减。
 */
public class NebulaSiphonMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    private static final long INTERVAL = 5;
    private static final int MB = 250;

    private int boostLevel = 0;
    private long lastTick = 0;
    private TickableSubscription subs;

    public NebulaSiphonMachine(IMachineBlockEntity holder, Object... args) { super(holder, args); }

    @Override
    public int getMaxParallel() {
        return switch (boostLevel) {
            case 3 -> 10240;
            case 2 -> 2048;
            case 1 -> 1024;
            default -> 256;
        };
    }

    @Override public NebulaSiphonLogic createRecipeLogic(Object... args) { return new NebulaSiphonLogic(this); }
    @Override public NebulaSiphonLogic getRecipeLogic() { return (NebulaSiphonLogic) super.getRecipeLogic(); }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        subs = subscribeServerTick(subs, this::tick);
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (subs != null) { subs.unsubscribe(); subs = null; }
        boostLevel = 0;
    }

    private void tick() {
        if (getLevel() == null) return;
        long now = getLevel().getGameTime();
        if (now - lastTick < INTERVAL) return;
        lastTick = now;

        if (tryConsume("matter_fluid_advanced", 3)) return;
        if (tryConsume("matter_fluid_basic", 2)) return;
        if (tryConsume("zero_point_energy", 1)) return;
        boostLevel = 0;
    }

    /**
     * 遍历部件，直接操作 FluidHatchPartMachine.tank 的内部 FluidStack。
     * 用 setFluidInTank 手动扣减，绕过输入仓不支持 drain 的限制。
     */
    private boolean tryConsume(String fluidId, int level) {
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(new net.minecraft.resources.ResourceLocation("dishanhai", fluidId));
        if (fluid == null || fluid == Fluids.EMPTY) return false;

        for (var part : getParts()) {
            if (!(part instanceof FluidHatchPartMachine hatch)) continue;
            NotifiableFluidTank tank = hatch.tank;

            for (int i = 0; i < tank.getTanks(); i++) {
                FluidStack inTank = tank.getFluidInTank(i);
                if (inTank == null || inTank.getFluid() != fluid) continue;
                if (inTank.getAmount() < MB) continue;

                // 直接扣减
                long remaining = inTank.getAmount() - MB;
                if (remaining <= 0) {
                    tank.setFluidInTank(i, FluidStack.empty());
                } else {
                    tank.setFluidInTank(i, FluidStack.create(fluid, remaining));
                }
                boostLevel = level;
                return true;
            }
        }
        return false;
    }
}
