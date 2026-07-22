package com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftPatternContainer;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMolecularAssemblerHandler;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.NotifiableMAHandlerTrait;
import com.lowdragmc.lowdraglib.syncdata.ISubscription;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PrimordialMolecularAssemblerModule extends PrimordialOmegaEngineModuleBase {

    static final String LOG_PREFIX = "[PrimordialMolecular]";

    private IMolecularAssemblerHandler molecularAssemblerHandler;
    private ISubscription molecularAssemblerSubscription;

    public PrimordialMolecularAssemblerModule(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialMolecularAssemblerModuleLogic createRecipeLogic(Object... args) {
        return new PrimordialMolecularAssemblerModuleLogic(this);
    }

    @Override
    public PrimordialMolecularAssemblerModuleLogic getRecipeLogic() {
        return (PrimordialMolecularAssemblerModuleLogic) recipeLogic;
    }

    @Override
    public int getMaxParallel() {
        return Integer.MAX_VALUE;
    }

    @Override
    public long getCurrentParallel() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;
    }

    @Override
    public int getTier() {
        return 9;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        if (isFormed()) {
            textList.add(Component.translatable(
                    "gt_shanhai.machine.primordial_molecular_assembler_module.mode")
                    .withStyle(ChatFormatting.GREEN));
        }
        textList.add(Component.translatable(
                "gt_shanhai.machine.primordial_molecular_assembler_module.name")
                .withStyle(ChatFormatting.GOLD));
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        GTDishanhaiMod.LOGGER.info(
                "{} structure-formed module={} parts={} host={} hostPos={} canModuleWork={}",
                LOG_PREFIX, getPos(), getParts().size(),
                getHost() == null ? "null" : getHost().getClass().getName(),
                getHostPosition(), canModuleWork());
        initializeMolecularAssemblerParts();
    }

    @Override
    public void onStructureInvalid() {
        GTDishanhaiMod.LOGGER.info(
                "{} structure-invalid module={} handler={} subscription={}",
                LOG_PREFIX, getPos(), describeHandler(), molecularAssemblerSubscription != null);
        clearMolecularAssemblerParts();
        super.onStructureInvalid();
    }

    public boolean canRunMolecularAssembly() {
        return canModuleWork() && molecularAssemblerHandler != null;
    }

    public IMolecularAssemblerHandler getMolecularAssemblerHandler() {
        return molecularAssemblerHandler;
    }

    public void handleMolecularRecipeOutput(com.gregtechceu.gtceu.api.recipe.GTRecipe recipe) {
        if (molecularAssemblerHandler == null || recipe == null) {
            GTDishanhaiMod.LOGGER.warn(
                    "{} output-return-skipped module={} handler={} recipe={}",
                    LOG_PREFIX, getPos(), describeHandler(),
                    PrimordialMolecularAssemblerModuleLogic.describeRecipe(recipe));
            return;
        }
        GTDishanhaiMod.LOGGER.info(
                "{} output-return-before module={} handler={} recipe={}",
                LOG_PREFIX, getPos(), describeHandler(),
                PrimordialMolecularAssemblerModuleLogic.describeRecipe(recipe));
        molecularAssemblerHandler.handleRecipeOutput(recipe);
        GTDishanhaiMod.LOGGER.info(
                "{} output-return-after module={} handler={}",
                LOG_PREFIX, getPos(), describeHandler());
    }

    private void initializeMolecularAssemblerParts() {
        GTDishanhaiMod.LOGGER.info(
                "{} initialize-start module={} parts={}",
                LOG_PREFIX, getPos(), getParts().size());
        clearMolecularAssemblerParts();

        Set<net.minecraft.core.BlockPos> patternContainers = new LinkedHashSet<>();
        IMECraftIOPart ioPart = null;
        int ioPartCount = 0;
        for (IMultiPart part : getParts()) {
            if (part instanceof IMECraftPatternContainer container) {
                patternContainers.add(container.getBlockPos());
                GTDishanhaiMod.LOGGER.info(
                        "{} pattern-container module={} pos={} class={}",
                        LOG_PREFIX, getPos(), container.getBlockPos(), part.getClass().getName());
            }
            if (part instanceof IMECraftIOPart candidate) {
                ioPartCount++;
                GTDishanhaiMod.LOGGER.info(
                        "{} io-part module={} pos={} class={} selected={}",
                        LOG_PREFIX, getPos(), part.self().getPos(), part.getClass().getName(), ioPart == null);
                if (ioPart == null) {
                    ioPart = candidate;
                }
            }
        }

        GTDishanhaiMod.LOGGER.info(
                "{} scan-result module={} ioParts={} patternContainers={} positions={}",
                LOG_PREFIX, getPos(), ioPartCount, patternContainers.size(), patternContainers);
        if (ioPart == null || patternContainers.isEmpty()) {
            GTDishanhaiMod.LOGGER.warn(
                    "{} initialize-aborted module={} ioPartPresent={} patternContainers={}",
                    LOG_PREFIX, getPos(), ioPart != null, patternContainers.size());
            return;
        }

        GTDishanhaiMod.LOGGER.info(
                "{} io-init-before module={} ioClass={} patternContainers={}",
                LOG_PREFIX, getPos(), ioPart.getClass().getName(), patternContainers);
        ioPart.init(patternContainers);
        GTDishanhaiMod.LOGGER.info(
                "{} io-init-after module={} ioClass={}",
                LOG_PREFIX, getPos(), ioPart.getClass().getName());
        NotifiableMAHandlerTrait handler = ioPart.getNotifiableMAHandlerTrait();
        if (handler == null) {
            GTDishanhaiMod.LOGGER.warn(
                    "{} initialize-no-handler module={} ioClass={}",
                    LOG_PREFIX, getPos(), ioPart.getClass().getName());
            return;
        }
        molecularAssemblerHandler = handler;
        molecularAssemblerSubscription = handler.addChangedListener(() -> {
            GTDishanhaiMod.LOGGER.info(
                    "{} handler-changed module={} handler={} logicStatus={} progress={}/{} lastRecipe={}",
                    LOG_PREFIX, getPos(), describeHandler(), getRecipeLogic().getStatus(),
                    getRecipeLogic().getProgress(), getRecipeLogic().getMaxProgress(),
                    PrimordialMolecularAssemblerModuleLogic.describeRecipe(getRecipeLogic().getLastRecipe()));
            getRecipeLogic().updateTickSubscription();
        });
        GTDishanhaiMod.LOGGER.info(
                "{} initialize-ready module={} handler={} listenerRegistered=true canRun={}",
                LOG_PREFIX, getPos(), describeHandler(), canRunMolecularAssembly());
        getRecipeLogic().updateTickSubscription();
    }

    private void clearMolecularAssemblerParts() {
        GTDishanhaiMod.LOGGER.info(
                "{} clear-parts module={} parts={} handler={} subscription={}",
                LOG_PREFIX, getPos(), getParts().size(), describeHandler(), molecularAssemblerSubscription != null);
        for (IMultiPart part : getParts()) {
            if (part instanceof IMECraftIOPart ioPart) {
                GTDishanhaiMod.LOGGER.info(
                        "{} io-clear module={} pos={} class={}",
                        LOG_PREFIX, getPos(), part.self().getPos(), part.getClass().getName());
                ioPart.init(Collections.emptySet());
            }
        }
        if (molecularAssemblerSubscription != null) {
            molecularAssemblerSubscription.unsubscribe();
            molecularAssemblerSubscription = null;
        }
        molecularAssemblerHandler = null;
    }

    private String describeHandler() {
        return molecularAssemblerHandler == null ? "null" : molecularAssemblerHandler.getClass().getName();
    }
}
