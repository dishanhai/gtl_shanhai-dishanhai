package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import net.minecraft.network.chat.Component;

/** 永恒格雷工坊创世之眼模块。作为普通工坊模块运行，只吃主机汇总加成。 */
public class EternalGregTechWorkshopEyeOfHarmonyModuleMachine extends EternalGregTechWorkshopModuleMachine {

    public EternalGregTechWorkshopEyeOfHarmonyModuleMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        setModuleDefaults(1, 42_949_672_940L, 0.95D, 0.90D, 1, 0);
    }

    @Override
    public EternalGregTechWorkshopModuleType getWorkshopModuleType() {
        return EternalGregTechWorkshopModuleType.EYE_OF_HARMONY;
    }

    @Override
    public Component getWorkshopModuleName() {
        return Component.translatable("gt_shanhai.machine.eternal_gregtech_workshop_eye_of_harmony_module.name");
    }
}
