package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import net.minecraft.network.chat.Component;

/** 永恒格雷工坊额外模块。 */
public class EternalGregTechWorkshopExtraModuleMachine extends EternalGregTechWorkshopModuleMachine {

    public EternalGregTechWorkshopExtraModuleMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        setModuleDefaults(1, 42_949_672_940L, 0.95D, 0.90D, 1, 0);
    }

    @Override
    public EternalGregTechWorkshopModuleType getWorkshopModuleType() {
        return EternalGregTechWorkshopModuleType.EXTRA;
    }

    @Override
    public Component getWorkshopModuleName() {
        return Component.translatable("gt_shanhai.machine.eternal_gregtech_workshop_extra_module.name");
    }
}
