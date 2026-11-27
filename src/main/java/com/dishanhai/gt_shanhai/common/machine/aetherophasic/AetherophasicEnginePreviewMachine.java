package com.dishanhai.gt_shanhai.common.machine.aetherophasic;

import com.dishanhai.gt_shanhai.client.renderer.machine.AetherophasicEngineRenderer;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.WorkableElectricMultipleRecipesMachine;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;

import java.util.List;

/** 山海：沿用旧预览控制器的注册 ID，成型后运行以太相跃迁配方。 */
public final class AetherophasicEnginePreviewMachine extends WorkableElectricMultipleRecipesMachine {
    public static final String ID = "aetherophasic_engine_preview";
    private static final String PREFIX = "gt_shanhai.multiblock." + ID + ".";
    private static final ManagedFieldHolder FIELDS = new ManagedFieldHolder(
            AetherophasicEnginePreviewMachine.class, WorkableElectricMultipleRecipesMachine.MANAGED_FIELD_HOLDER);
    private static final int VISUAL_STEPS = 80;
    @DescSynced private int visualProgress;
    @DescSynced private long lastCompletionTick = -1;

    public AetherophasicEnginePreviewMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return FIELDS;
    }

    @Override
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new TransitionLogic(this);
    }

    @Override
    public int getMaxParallel() {
        return 1;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        textList.add(Component.translatable(PREFIX + "dimensions"));
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        visualProgress = 0;
        lastCompletionTick = -1;
    }

    public double getVisualProgress() {
        return (double) visualProgress / VISUAL_STEPS;
    }

    public long getLastCompletionTick() {
        return lastCompletionTick;
    }

    public static MultiblockMachineDefinition register() {
        var casing = new ResourceLocation("gt_shanhai", "block/aetherophasic_casing");
        var overlay = new ResourceLocation("gtceu", "block/multiblock/fusion_reactor");
        return GTDishanhaiRegistration.REGISTRATE
                .multiblock(ID, AetherophasicEnginePreviewMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .tier(GTValues.OpV)
                .recipeTypes(DShanhaiRecipeTypes.AETHEROPHASIC_TRANSITION)
                .pattern(AetherophasicEngineStructure::createPattern)
                .appearanceBlock(AetherophasicEngineStructure::appearanceBlock)
                .renderer(() -> new AetherophasicEngineRenderer(casing, overlay))
                .hasTESR(true)
                .tooltips(Component.translatable(PREFIX + "tooltip.0"),
                        Component.translatable(PREFIX + "tooltip.1"),
                        Component.translatable(PREFIX + "tooltip.2"))
                .register();
    }

    private static final class TransitionLogic extends MultipleRecipesLogic {
        private final AetherophasicEnginePreviewMachine engine;

        private TransitionLogic(AetherophasicEnginePreviewMachine engine) {
            super(engine);
            this.engine = engine;
        }

        @Override
        public void handleRecipeWorking() {
            super.handleRecipeWorking();
            engine.visualProgress = (int) (getProgressPercent() * VISUAL_STEPS);
        }

        @Override
        public void onRecipeFinish() {
            if (lastRecipe == null) return;
            // 输出仓在运行中也可能被填满，保留已扣料的批次直到能完整输出。
            if (!RecipeRunnerHelper.matchRecipeOutput(engine, lastRecipe)
                    || !RecipeRunnerHelper.handleRecipeOutput(engine, lastRecipe)) {
                setWaiting(Component.translatable(PREFIX + "output_full"));
                return;
            }
            lastRecipe.postWorking(engine);
            engine.afterWorking();
            engine.lastCompletionTick = engine.getLevel().getGameTime();
            engine.visualProgress = 0;
            setStatus(Status.IDLE);
            resetRecipeLogic();
            findAndHandleRecipe();
        }
    }
}
