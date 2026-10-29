package com.dishanhai.gt_shanhai.common.machine.steam;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.taixu.PrimordialTaixuCosmicForgeModule;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockDisplayText;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.Size;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.api.recipe.IAdvancedContentModifier;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.WorkableElectricMultipleRecipesMachine;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;

import java.util.List;

/** 山海：蒸汽階段水轉蒸汽；無電力、燃料或超頻，原初物質模組並行乘四。 */
public class ShanhaiSteamGeneratorMachine extends WorkableElectricMultipleRecipesMachine implements IMachineLife {
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ShanhaiSteamGeneratorMachine.class, WorkableElectricMultipleRecipesMachine.MANAGED_FIELD_HOLDER);
    @Persisted
    @DescSynced
    private final NotifiableItemStackHandler moduleSlot;

    public ShanhaiSteamGeneratorMachine(IMachineBlockEntity holder) {
        super(holder);
        moduleSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.NONE) {
            @Override
            public void onContentsChanged() {
                super.onContentsChanged();
                if (!isRemote()) getRecipeLogic().updateTickSubscription();
            }
        };
        moduleSlot.setFilter(PrimordialOmegaEngineMachine::isMatterModuleStack);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    public static MultiblockMachineDefinition register() {
        return GTDishanhaiRegistration.REGISTRATE
                .multiblock("steam_generator", ShanhaiSteamGeneratorMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(DShanhaiRecipeTypes.STEAM_GENERATION)
                .appearanceBlock(GTBlocks.BRONZE_HULL)
                .pattern(ShanhaiSteamGeneratorMachine::createPattern)
                .sidedWorkableCasingRenderer("block/casings/steam/bronze",
                        new ResourceLocation("gtceu", "block/multiblock/steam_oven"), false)
                .tooltips(Component.translatable("gt_shanhai.steam_generator.conversion"),
                        Component.translatable("gt_shanhai.steam_generator.no_energy"),
                        Component.translatable("gt_shanhai.steam_generator.parallel_rule"),
                        Component.translatable("gt_shanhai.steam_generator.structure"))
                .register();
    }

    private static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        // 每個 aisle 由上至下：齒輪上蓋、流體管道層、燃燒室底座。
        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.DOWN, RelativeDirection.BACK)
                .aisle("GGG", "CSC", "FFF")
                .aisle("GPG", "IPO", "FFF")
                .aisle("GGG", "CCC", "FFF")
                .where('S', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('C', Predicates.blocks(GTBlocks.BRONZE_HULL.get()))
                .where('G', Predicates.blocks(GTBlocks.CASING_BRONZE_GEARBOX.get()))
                .where('P', Predicates.blocks(GTBlocks.CASING_BRONZE_PIPE.get()))
                .where('F', Predicates.blocks(GTBlocks.FIREBOX_BRONZE.get()))
                .where('I', Predicates.abilities(PartAbility.IMPORT_FLUIDS))
                .where('O', Predicates.abilities(PartAbility.EXPORT_FLUIDS))
                .build();
    }

    @Override
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new SteamGenerationLogic(this);
    }

    @Override
    public int getMaxParallel() {
        // 原生 int 介面供顯示及相容；實際配方使用 long 模組上限。
        return (int) Math.min(Integer.MAX_VALUE, getParallelLimit());
    }

    public long getParallelLimit() {
        long primordialParallel = moduleSlot == null ? PrimordialTaixuCosmicForgeModule.DEFAULT_PARALLEL
                : PrimordialTaixuCosmicForgeModule.getMatterModuleParallel(moduleSlot.storage.getStackInSlot(0));
        return scaleModuleParallel(primordialParallel);
    }

    static long scaleModuleParallel(long primordialParallel) {
        return primordialParallel > Long.MAX_VALUE / 4L ? Long.MAX_VALUE : primordialParallel * 4L;
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            // 保留原生狀態內容與捲動，底部另留完整模組槽列，避免與狀態文字重疊。
            for (Widget child : group.widgets) {
                if (child instanceof DraggableScrollableWidgetGroup scroll) scroll.setSize(new Size(182, 88));
            }
            group.addWidget(new SlotWidget(moduleSlot.storage, 0, 8, 99, true, true)
                    .setBackground(SlotWidget.ITEM_SLOT_TEXTURE)
                    .setHoverTooltips(Component.translatable("gt_shanhai.steam_generator.parallel_rule")));
            group.addWidget(new LabelWidget(32, 103, "gt_shanhai.steam_generator.module_slot"));
        }
        return widget;
    }

    @Override
    public void onMachineRemoved() {
        clearInventory(moduleSlot.storage);
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        textList.add(Component.translatable("gt_shanhai.steam_generator.conversion"));
        textList.add(Component.translatable("gt_shanhai.steam_generator.no_energy"));
        textList.add(Component.translatable("gt_shanhai.steam_generator.parallel_limit",
                String.format("%,d", getParallelLimit())));
        MultiblockDisplayText.builder(textList, isFormed())
                .setWorkingStatus(getRecipeLogic().isWorkingEnabled(), getRecipeLogic().isActive())
                .addWorkingStatusLine()
                .addProgressLine(getRecipeLogic().getProgressPercent());
    }

    /** 保留原生持久化／扣料／tick；以 long 流體並行處理，不用總 EU 重算耗時。 */
    static class SteamGenerationLogic extends MultipleRecipesLogic {
        SteamGenerationLogic(ShanhaiSteamGeneratorMachine machine) {
            super(machine);
        }

        @Override
        public void findAndHandleRecipe() {
            lastRecipe = null;
            lastOriginRecipe = null;
            GTRecipe recipe = machine.getRecipeType().getLookup().find(machine,
                    candidate -> candidate.checkConditions(this).isSuccess()
                            && RecipeRunnerHelper.matchRecipe(machine, candidate));
            if (recipe != null) {
                long parallel = IParallelLogic.getParallel(machine, recipe,
                        ((ShanhaiSteamGeneratorMachine) machine).getParallelLimit());
                if (parallel > 0L) {
                    GTRecipe batch = recipe.copy(IAdvancedContentModifier.preciseMultiplier(parallel), false);
                    IGTRecipe.of(batch).setRealParallels(parallel);
                    // getParallel 在單並行時不檢查輸出；扣水前仍需完整預檢。
                    if (RecipeRunnerHelper.matchRecipeOutput(machine, batch)) {
                        setupRecipe(batch);
                    }
                }
            }
        }

        @Override
        public void onRecipeFinish() {
            if (lastRecipe == null) {
                return;
            }
            // 加工中外部可以填滿輸出倉；保留已扣水的完成批次，待能輸出才結束。
            if (!tryOutput(lastRecipe)) {
                setWaiting(Component.translatable("gt_shanhai.steam_generator.output_full"));
                return;
            }
            lastRecipe.postWorking(machine);
            machine.afterWorking();
            setStatus(Status.IDLE);
            resetRecipeLogic();
            findAndHandleRecipe();
        }

        protected boolean tryOutput(GTRecipe recipe) {
            // GTLCore 將基類 handleRecipeIO（含 OUT）覆寫為扣輸入，不能用它完成批次。
            // 專用輸出入口支援普通流體倉及 ME 輸出矩陣的 long 持久化暫存。
            return RecipeRunnerHelper.matchRecipeOutput(machine, recipe)
                    && RecipeRunnerHelper.handleRecipeOutput(machine, recipe);
        }
    }
}
