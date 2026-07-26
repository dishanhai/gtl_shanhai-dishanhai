package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.api.machine.IDShanhaiBatchToggle;
import com.dishanhai.gt_shanhai.api.machine.part.IUniversalGravityMaintenancePart;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.GTLCleaningMaintenanceHatchPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.ICleaningRoom;

public class CosmicCleanGravityMaintenanceHatchMachine
        extends GTLCleaningMaintenanceHatchPartMachine
        implements IUniversalGravityMaintenancePart, IDShanhaiBatchToggle {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            CosmicCleanGravityMaintenanceHatchMachine.class,
            GTLCleaningMaintenanceHatchPartMachine.MANAGED_FIELD_HOLDER);

    // 批处理：装本仓的多方块，<20t 配方在修饰链尾合并为 ≥20t（DShanhaiBatchPartMixin 消费）
    @Persisted
    private boolean batchModeEnabled = true;

    public CosmicCleanGravityMaintenanceHatchMachine(IMachineBlockEntity holder) {
        super(holder, ICleaningRoom.LAW_DUMMY_CLEANROOM);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public boolean isBatchModeEnabled() {
        return batchModeEnabled;
    }

    @Override
    public void setBatchModeEnabled(boolean enabled) {
        this.batchModeEnabled = enabled;
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        IDShanhaiBatchToggle.attachBatchConfigurator(configuratorPanel, this);
    }
}
