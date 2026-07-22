package com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialRecipeOutputAmplifier;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.mixin.gtm.api.recipe.RecipeLogicAccessor;

import java.util.List;
import java.util.Map;

public class PrimordialMolecularAssemblerModuleLogic extends PrimordialModuleRecipeLogic {

    private static final long IDLE_LOG_INTERVAL_TICKS = 100L;
    private long lastIdleLogTick = Long.MIN_VALUE;

    public PrimordialMolecularAssemblerModuleLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine);
    }

    @Override
    public int getMultipleThreads() {
        return Integer.MAX_VALUE;
    }

    @Override
    protected boolean checkBeforeWorking() {
        return getMachine() instanceof PrimordialMolecularAssemblerModule module
                && module.canRunMolecularAssembly();
    }

    @Override
    public GTRecipe getGTRecipe() {
        if (!(getMachine() instanceof PrimordialMolecularAssemblerModule module)
                || !module.canRunMolecularAssembly()) {
            logIdleState("extract-unavailable", null);
            return null;
        }

        org.gtlcore.gtlcore.api.machine.trait.AECraft.IMolecularAssemblerHandler handler =
                module.getMolecularAssemblerHandler();
        if (handler == null) {
            logIdleState("extract-no-handler", module);
            return null;
        }

        logIdleState("extract-attempt", module);
        // 使用原生支持的正数上限。部分整合兼容模组会在 HEAD 无条件接管 extractGTRecipe，
        // 将 Long.MIN_VALUE 当成不可提取数量并直接返回 null，不能依赖 GTLAdd 的负数哨兵 Mixin。
        GTRecipe raw = handler.extractGTRecipe(Long.MAX_VALUE, 1);
        if (raw == null) {
            logIdleState("extract-empty", module);
            return null;
        }

        GTDishanhaiMod.LOGGER.info(
                "{} extract-result module={} handler={} raw={}",
                PrimordialMolecularAssemblerModule.LOG_PREFIX, module.getPos(),
                handler.getClass().getName(), describeRecipe(raw));
        // 原生分子操纵者直接运行 handler 返回的 raw recipe；只在此处复制并放大产出，
        // 不再经过无线配方包装，避免改变 MECraftHandler 的 LongIngredient 回传协议。
        int multiplier = module.getHostOutputMultiplier();
        GTRecipe amplified = PrimordialRecipeOutputAmplifier.apply(raw, multiplier);
        GTDishanhaiMod.LOGGER.info(
                "{} amplify-result module={} multiplier={} recipe={}",
                PrimordialMolecularAssemblerModule.LOG_PREFIX, module.getPos(), multiplier,
                describeRecipe(amplified));
        return amplified;
    }

    @Override
    public void findAndHandleRecipe() {
        GTRecipe before = lastRecipe;
        lastRecipe = null;
        setRecipeStatus(null);
        GTRecipe match = getGTRecipe();
        if (match != null) {
            GTDishanhaiMod.LOGGER.info(
                    "{} setup-before module={} previous={} match={} status={}",
                    PrimordialMolecularAssemblerModule.LOG_PREFIX, getMachine().getPos(),
                    describeRecipe(before), describeRecipe(match), getStatus());
            setRecipeStatus(RecipeResult.SUCCESS);
            setupRecipe(match);
            GTDishanhaiMod.LOGGER.info(
                    "{} setup-after module={} status={} progress={}/{} lastRecipe={}",
                    PrimordialMolecularAssemblerModule.LOG_PREFIX, getMachine().getPos(),
                    getStatus(), progress, duration, describeRecipe(lastRecipe));
        }
    }

    @Override
    public void serverTick() {
        GTRecipe beforeRecipe = lastRecipe;
        int beforeProgress = progress;
        int beforeDuration = duration;
        Status beforeStatus = getStatus();
        boolean traceTick = beforeRecipe != null
                && (beforeDuration <= 1 || getMachine().getOffsetTimer() % 20L == 0L);
        if (traceTick) {
            GTDishanhaiMod.LOGGER.info(
                    "{} tick-before module={} status={} progress={}/{} recipe={}",
                    PrimordialMolecularAssemblerModule.LOG_PREFIX, getMachine().getPos(),
                    beforeStatus, beforeProgress, beforeDuration, describeRecipe(beforeRecipe));
        }

        super.serverTick();

        if (traceTick || beforeRecipe != lastRecipe || beforeStatus != getStatus()) {
            GTDishanhaiMod.LOGGER.info(
                    "{} tick-after module={} status={} progress={}/{} recipe={} changedRecipe={} changedStatus={}",
                    PrimordialMolecularAssemblerModule.LOG_PREFIX, getMachine().getPos(),
                    getStatus(), progress, duration, describeRecipe(lastRecipe),
                    beforeRecipe != lastRecipe, beforeStatus != getStatus());
        }
    }

    @Override
    public void onRecipeFinish() {
        GTDishanhaiMod.LOGGER.info(
                "{} finish-enter module={} status={} progress={}/{} handlerPresent={} recipe={}",
                PrimordialMolecularAssemblerModule.LOG_PREFIX, getMachine().getPos(),
                getStatus(), progress, duration,
                getMachine() instanceof PrimordialMolecularAssemblerModule module
                        && module.getMolecularAssemblerHandler() != null,
                describeRecipe(lastRecipe));
        getMachine().afterWorking();
        if (lastRecipe != null && getMachine() instanceof PrimordialMolecularAssemblerModule module) {
            module.handleMolecularRecipeOutput(lastRecipe);
        }
        lastRecipe = null;
        setStatus(Status.IDLE);
        setRecipeStatus(null);
        progress = 0;
        duration = 0;
        ((RecipeLogicAccessor) this).setIsActive(false);
        GTDishanhaiMod.LOGGER.info(
                "{} finish-exit module={} status={} progress={}/{} lastRecipe={}",
                PrimordialMolecularAssemblerModule.LOG_PREFIX, getMachine().getPos(),
                getStatus(), progress, duration, describeRecipe(lastRecipe));
    }

    private void logIdleState(String event, PrimordialMolecularAssemblerModule module) {
        long tick = getMachine().getOffsetTimer();
        if (lastIdleLogTick != Long.MIN_VALUE && tick - lastIdleLogTick >= 0L
                && tick - lastIdleLogTick < IDLE_LOG_INTERVAL_TICKS) {
            return;
        }
        lastIdleLogTick = tick;
        GTDishanhaiMod.LOGGER.info(
                "{} {} module={} tick={} status={} progress={}/{} canRun={} handler={}",
                PrimordialMolecularAssemblerModule.LOG_PREFIX, event, getMachine().getPos(), tick,
                getStatus(), progress, duration, module != null && module.canRunMolecularAssembly(),
                module == null || module.getMolecularAssemblerHandler() == null
                        ? "null" : module.getMolecularAssemblerHandler().getClass().getName());
    }

    static String describeRecipe(GTRecipe recipe) {
        if (recipe == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder(160)
                .append("{id=").append(recipe.getId())
                .append(",duration=").append(recipe.duration)
                .append(",parallels=").append(recipe.parallels)
                .append(",outputs=[");
        boolean first = true;
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : recipe.outputs.entrySet()) {
            List<Content> contents = entry.getValue();
            if (contents == null) {
                continue;
            }
            for (Content content : contents) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                Object value = content == null ? null : content.content;
                result.append("{cap=").append(entry.getKey().getClass().getSimpleName())
                        .append(",type=").append(value == null ? "null" : value.getClass().getName());
                if (value instanceof LongIngredient ingredient) {
                    result.append(",actualAmount=").append(ingredient.getActualAmount());
                } else {
                    result.append(",value=").append(value);
                }
                result.append('}');
            }
        }
        return result.append("]}").toString();
    }
}
