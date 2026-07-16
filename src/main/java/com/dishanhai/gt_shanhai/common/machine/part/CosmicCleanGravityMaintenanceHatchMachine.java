package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.api.machine.part.IUniversalGravityMaintenancePart;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.GTLCleaningMaintenanceHatchPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.ICleaningRoom;

public class CosmicCleanGravityMaintenanceHatchMachine
        extends GTLCleaningMaintenanceHatchPartMachine
        implements IUniversalGravityMaintenancePart {

    public CosmicCleanGravityMaintenanceHatchMachine(IMachineBlockEntity holder) {
        super(holder, ICleaningRoom.LAW_DUMMY_CLEANROOM);
    }
}
