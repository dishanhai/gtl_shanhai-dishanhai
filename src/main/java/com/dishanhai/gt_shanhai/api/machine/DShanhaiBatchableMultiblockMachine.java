package com.dishanhai.gt_shanhai.api.machine;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 自带批处理能力的多方块电力机器基类：extends 即接入，无需再写任何批处理代码。
 * <p>
 * 挂载点是 {@link #getRealRecipe}（definition 修饰链之后）——若整合包装有 GTMAdvancedHatch，
 * 其 mixin 已在 super 内部的 RecipeModifierList#apply RETURN 处跑过；二者触发条件同为
 * duration&lt;20，GTMA 先合并成功则此处不再满足条件，天然不会双重批处理。
 * <p>
 * 父类不在本继承线上的机器（Selectable 系、原初模块系等）请直接 implements
 * {@link IDShanhaiBatchable} 并照其类注释三步接入。
 */
public class DShanhaiBatchableMultiblockMachine extends WorkableElectricMultiblockMachine
                                               implements IDShanhaiBatchable {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            DShanhaiBatchableMultiblockMachine.class, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    private boolean batchModeEnabled = true;

    public DShanhaiBatchableMultiblockMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
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
    @Nullable
    protected GTRecipe getRealRecipe(GTRecipe recipe, @NotNull OCParams params, @NotNull OCResult result) {
        return applyBatchMode(super.getRealRecipe(recipe, params, result));
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        IDShanhaiBatchToggle.attachBatchConfigurator(configuratorPanel, this);
    }
}
