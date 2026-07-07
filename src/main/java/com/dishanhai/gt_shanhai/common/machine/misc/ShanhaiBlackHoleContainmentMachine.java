package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleTypeWirelessRecipesLogic;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;
import java.util.Set;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import com.dishanhai.gt_shanhai.api.BHCRecipeCondition;
import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

/**
 * 亚稳态黑洞遏制场 — GTNH MTEBlackHoleCompressor 移植
 *
 * GTNH 原版逻辑:
 *   onPostTick:   每20tick衰减稳定度/时空催化/生命周期
 *   setupProcessingLogic → searchAndDecrementCatalysts: 扫描输入总线消耗种子/坍缩器
 *   onRunningTick: 失稳(status=3)时摧毁输出产物
 *   getModeFromCircuit: 电路20→压缩机 电路≤21→黑洞模式
 *
 * 催化剂物品用 GTNH 原版 MetaGeneratedItem01 的 damage 值:
 *   32418=超稳态黑洞种子  32419=黑洞坍缩器  32420=特殊种子
 */
public class ShanhaiBlackHoleContainmentMachine
        extends GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(ShanhaiBlackHoleContainmentMachine.class, getMANAGED_FIELD_HOLDER());

    private static final int STABILITY_MAX = 100;
    private static final long SPACETIME_MB = 10000; // 10B 每秒
    private static final int STATUS_CLOSED = 1;
    private static final int STATUS_ACTIVE = 2;
    private static final int STATUS_UNSTABLE = 3;
    private static final int STATUS_HYPER_STABLE = 4;

    // BHC 需要的催化剂物品
    private static final ResourceLocation ID_HYPER_SEED   = new ResourceLocation("dishanhai", "bhd_hyper_seed");
    private static final ResourceLocation ID_COLLAPSER     = new ResourceLocation("dishanhai", "bhd_collapser");
    private static final ResourceLocation ID_BLACK_HOLE_SEED = new ResourceLocation("gtladditions", "black_hole_seed");

    @Persisted @DescSynced private float blackHoleStability = STABILITY_MAX;
    @Persisted @DescSynced private int blackHoleStatus = STATUS_CLOSED; // 1=关闭 2=运行 3=失稳 4=特殊
    @Persisted @DescSynced private int catalyzingCounter;
    @Persisted @DescSynced private int catalyzingCostModifier = 1;
    @Persisted @DescSynced private int collapseTimer = -1;
    @Persisted @DescSynced private boolean catalyticBurstEnabled = false; // 催化爆冲开关
    @Persisted @DescSynced private long animationStartTick;
    @Persisted @DescSynced private boolean animationScaling = true;
    @Persisted @DescSynced private boolean shouldRender = false;
    private TickableSubscription tickSub;

    private boolean recipeLogicSetup; // 标记是否需要在下次 setupProcessingLogic 时检查催化剂

    public ShanhaiBlackHoleContainmentMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, DShanhaiRecipeTypes.BLACK_HOLE_COMPRESSOR, args);
        recipeLogicSetup = true;
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ==================== getters for renderer ====================

    public float getBlackHoleStability() { return blackHoleStability; }
    public int getBlackHoleStatus() { return blackHoleStatus; }
    public int getBlackHoleCatalyzingCostModifier() { return catalyzingCostModifier; }
    public int getCatalyzingCounter() { return catalyzingCounter; }
    public int getCollapseTimer() { return collapseTimer; }
    public boolean isCatalyticBurstEnabled() { return catalyticBurstEnabled; }
    public boolean isExtraLasersUnlocked() { return catalyticBurstEnabled && catalyzingCostModifier >= 2; }
    public boolean isShouldRender() { return shouldRender; }
    public net.minecraft.world.phys.Vec3 getRenderCenter() {
        var d = getFrontFacing().getOpposite(); var p = getPos();
        return new net.minecraft.world.phys.Vec3(
            p.getX() + 7 * d.getStepX() + 0.5,
            p.getY() + 11 + 0.5,
            p.getZ() + 7 * d.getStepZ() + 0.5);
    }
    public long getAnimationStartTick() { return animationStartTick; }
    public boolean isAnimationScaling() { return animationScaling; }
    public float getSmoothTick(float pt) {
        if (getLevel() != null) return getLevel().getGameTime() + pt;
        return 0;
    }

    // ==================== lifecycle ====================

    @Override public void onStructureFormed() {
        super.onStructureFormed();
        GTDishanhaiMod.LOGGER.info("[BHC] onStructureFormed server=" + (getLevel() != null && !getLevel().isClientSide));
        if (getLevel() != null && !getLevel().isClientSide) {
            if (!hasSavedBlackHoleState()) {
                resetClosedState(false);
            } else {
                shouldRender = blackHoleStatus != STATUS_CLOSED || collapseTimer > 0;
                syncStateChange();
            }
            recipeLogicSetup = true;
            if (tickSub != null) tickSub.unsubscribe();
            tickSub = subscribeServerTick(tickSub, this::doServerTick);
            GTDishanhaiMod.LOGGER.info("[BHC] tick subscribed: " + (tickSub != null));
            getLevel().playSound(null, getPos(),
                SoundEvent.createVariableRangeEvent(new ResourceLocation("gt_shanhai", "blackhole_activate")),
                SoundSource.BLOCKS, 1.0f, 1.0f);
        }
    }

    @Override public void onStructureInvalid() {
        super.onStructureInvalid();
        if (tickSub != null) { tickSub.unsubscribe(); tickSub = null; }
        resetClosedState(false);
    }

    @Override public void onLoad() {
        super.onLoad();
        if (getLevel() != null && !getLevel().isClientSide) {
            shouldRender = blackHoleStatus != STATUS_CLOSED || collapseTimer > 0;
            if (isFormed()) {
                if (tickSub != null) tickSub.unsubscribe();
                tickSub = subscribeServerTick(tickSub, this::doServerTick);
            }
        }
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putFloat("BHCStability", blackHoleStability);
        tag.putInt("BHCStatus", blackHoleStatus);
        tag.putInt("BHCCatalyzingCounter", catalyzingCounter);
        tag.putInt("BHCCatalyzingCostModifier", catalyzingCostModifier);
        tag.putInt("BHCCollapseTimer", collapseTimer);
        tag.putBoolean("BHCCatalyticBurstEnabled", catalyticBurstEnabled);
        tag.putLong("BHCAnimationStartTick", animationStartTick);
        tag.putBoolean("BHCAnimationScaling", animationScaling);
        tag.putBoolean("BHCShouldRender", shouldRender);
        tag.putBoolean("BHCHasSavedState", true);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("BHCStability")) blackHoleStability = tag.getFloat("BHCStability");
        if (tag.contains("BHCStatus")) blackHoleStatus = tag.getInt("BHCStatus");
        if (tag.contains("BHCCatalyzingCounter")) catalyzingCounter = tag.getInt("BHCCatalyzingCounter");
        if (tag.contains("BHCCatalyzingCostModifier")) catalyzingCostModifier = Math.max(1, tag.getInt("BHCCatalyzingCostModifier"));
        if (tag.contains("BHCCollapseTimer")) collapseTimer = tag.getInt("BHCCollapseTimer");
        if (tag.contains("BHCCatalyticBurstEnabled")) catalyticBurstEnabled = tag.getBoolean("BHCCatalyticBurstEnabled");
        if (tag.contains("BHCAnimationStartTick")) animationStartTick = tag.getLong("BHCAnimationStartTick");
        if (tag.contains("BHCAnimationScaling")) animationScaling = tag.getBoolean("BHCAnimationScaling");
        if (tag.contains("BHCShouldRender")) shouldRender = tag.getBoolean("BHCShouldRender");
        shouldRender = shouldRender || blackHoleStatus != STATUS_CLOSED || collapseTimer > 0;
    }

    private boolean hasSavedBlackHoleState() {
        return blackHoleStatus != STATUS_CLOSED
                || collapseTimer > 0
                || shouldRender
                || catalyticBurstEnabled
                || catalyzingCounter != 0
                || catalyzingCostModifier != 1
                || blackHoleStability != STABILITY_MAX;
    }

    // ==================== 交互 ====================

    @Override
    protected InteractionResult onScrewdriverClick(Player player, InteractionHand hand, Direction dir, BlockHitResult hit) {
        if (getLevel() != null && !getLevel().isClientSide) {
            toggleCatalyticBurst();
            player.displayClientMessage(Component.literal(
                catalyticBurstEnabled ? "§6催化爆冲: §a§lON §7并行×催化倍率"
                                     : "§7催化爆冲: §8OFF"), true);
        }
        return InteractionResult.SUCCESS;
    }

    void toggleCatalyticBurst() {
        catalyticBurstEnabled = !catalyticBurstEnabled;
        syncStateChange();
        GTDishanhaiMod.LOGGER.info("[BHC] catalytic burst {}", catalyticBurstEnabled ? "ON" : "OFF");
    }

    // ==================== GTNH onPostTick — 每秒执行 ====================

    private void resetClosedState(boolean keepClosingAnimation) {
        blackHoleStatus = STATUS_CLOSED;
        blackHoleStability = STABILITY_MAX;
        catalyzingCostModifier = 1;
        catalyzingCounter = 0;
        if (!keepClosingAnimation) {
            collapseTimer = -1;
            shouldRender = false;
            animationScaling = true;
        }
        syncStateChange();
    }

    private void openBlackHole(boolean hyperStable) {
        blackHoleStatus = hyperStable ? STATUS_HYPER_STABLE : STATUS_ACTIVE;
        blackHoleStability = STABILITY_MAX;
        catalyzingCostModifier = 1;
        catalyzingCounter = 0;
        collapseTimer = -1;
        shouldRender = true;
        animationStartTick = currentGameTime();
        animationScaling = true;
        recipeLogicSetup = true;
        syncStateChange();
    }

    private void setUnstable() {
        if (blackHoleStatus == STATUS_UNSTABLE || blackHoleStatus == STATUS_CLOSED) return;
        blackHoleStatus = STATUS_UNSTABLE;
        shouldRender = true;
        syncStateChange();
        GTDishanhaiMod.LOGGER.info("[BHC] black hole became unstable");
    }

    private void collapseBlackHole() {
        resetClosedState(true);
        collapseTimer = 40;
        shouldRender = true;
        animationStartTick = currentGameTime();
        animationScaling = false;
        recipeLogicSetup = true;
        var logic = getRecipeLogic();
        if (logic != null) logic.resetRecipeLogic();
        syncStateChange();
    }

    private long currentGameTime() {
        return getLevel() != null ? getLevel().getGameTime() : 0L;
    }

    private void syncStateChange() {
        markDirty();
        if (getLevel() != null && !getLevel().isClientSide) {
            notifyBlockUpdate();
        }
    }

    private void doServerTick() {
        if (!isFormed() || getLevel() == null || getLevel().isClientSide) return;

        long t = getLevel().getGameTime();

        // 每 tick 检查催化剂 (GTNH: setupProcessingLogic → searchAndDecrementCatalysts 每次配方开始都会调用,
        // 但 GTCEu 的 findAndHandleRecipe 不一定在空闲时触发, 所以 tick 中也调用确保检测到)
        checkCatalystItems();

        // collapseTimer 递减
        if (collapseTimer > 0) {
            collapseTimer--;
            if (collapseTimer == 0) {
                collapseTimer = -1;
                if (blackHoleStatus == STATUS_CLOSED) shouldRender = false;
                syncStateChange();
            }
        }

        if (t % 20L != 0L) return;

        // status==1(关闭) 不执行衰减
        if (blackHoleStatus == STATUS_CLOSED) return;

        if (catalyticBurstEnabled) {
            if (!drainSpaceTime()) {
                collapseBlackHole();
                GTDishanhaiMod.LOGGER.info("[BHC] catalytic burst starved — black hole collapsed");
                return;
            }
            recordCatalysis();
            if (blackHoleStatus == STATUS_HYPER_STABLE) return;
        }

        // status==4(特殊) 不衰减，直接返回
        if (blackHoleStatus == STATUS_HYPER_STABLE) return;

        // === 稳定度衰减 ===
        float decay = 1.0f; // GTNH: 默认每秒衰减 1.0

        if (blackHoleStability > 0.0f) {
            // 尝试消耗时空流体
            boolean catalyzed = catalyticBurstEnabled || drainSpaceTime();
            if (catalyzed) {
                decay = 0.0f; // GTNH: 成功消耗时空 → 不衰减
                if (!catalyticBurstEnabled) recordCatalysis();
            }
        } else {
            setUnstable(); // GTNH: 稳定性降到负 → 失稳
        }

        blackHoleStability -= decay;
        if (blackHoleStability <= 0.0f && blackHoleStatus == STATUS_ACTIVE) {
            setUnstable();
        } else if (decay != 0.0f) {
            syncStateChange();
        }

        // 稳定度 <= -900 → 自动关闭
        if (blackHoleStability <= -900.0f) {
            collapseBlackHole();
            GTDishanhaiMod.LOGGER.info("[BHC] stability <= -900 — black hole collapsed");
        }
    }

    private void recordCatalysis() {
        catalyzingCounter++;
        if (catalyzingCounter >= 30) {
            if (catalyzingCostModifier < 1000000000) {
                catalyzingCostModifier *= 2;
            }
            catalyzingCounter = 0;
        }
        syncStateChange();
    }

    // ==================== 时空流体消耗 ====================

    /** GTNH: 遍历 spacetimeHatches 消耗 SpaceTime 流体，消耗量=cost × modifier */
    private boolean drainSpaceTime() {
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation("gtceu", "spacetime"));
        if (fluid == null || fluid == Fluids.EMPTY) return false;

        for (var part : getParts()) {
            com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank tank = null;
            if (part instanceof FluidHatchPartMachine hatch) {
                tank = hatch.tank;
            } else if (part instanceof MetaMachine mm) {
                for (var t : mm.getTraits()) {
                    if (t instanceof com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank nft) {
                        tank = nft; break;
                    }
                }
            }
            if (tank == null) continue;

            for (int i = 0; i < tank.getTanks(); i++) {
                var inTank = tank.getFluidInTank(i);
                if (inTank == null || inTank.isEmpty()) continue;
                var fid = ForgeRegistries.FLUIDS.getKey(inTank.getFluid());
                if (!"gtceu:spacetime".equals(fid != null ? fid.toString() : null)) continue;
                long need = SPACETIME_MB * catalyzingCostModifier;
                if (inTank.getAmount() < need) continue;
                long remaining = inTank.getAmount() - need;
                if (remaining <= 0) tank.setFluidInTank(i, FluidStack.empty());
                else tank.setFluidInTank(i, FluidStack.create(fluid, remaining));
                return true;
            }
        }
        return false;
    }

    // ==================== 催化剂检测 (GTNH searchAndDecrementCatalysts) ====================

    /** 扫描所有输入总线, 通过 traits 遍历匹配 NotifiableItemStackHandler (兼容任何模组的输入总线) */
    void checkCatalystItems() {
        if (getRecipeLogic().isWorking()) return;

        for (var part : getParts()) {
            if (!(part instanceof MetaMachine mm)) continue;
            for (var trait : mm.getTraits()) {
                if (!(trait instanceof NotifiableItemStackHandler nish)) continue;
                for (int i = 0; i < nish.getSlots(); i++) {
                    ItemStack stack = nish.getStackInSlot(i);
                    if (stack.isEmpty()) continue;
                    var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (rl == null) continue;

                    // gtladditions:black_hole_seed → 普通模式 (status=2, 需时空流体维持)
                    // dishanhai:bhd_hyper_seed → 超稳态模式 (status=4, 不衰减, 4x并行)
                    boolean isHyper = rl.equals(ID_HYPER_SEED);
                    boolean isNormal = rl.equals(ID_BLACK_HOLE_SEED);
                    if ((isNormal || isHyper) && blackHoleStatus == STATUS_CLOSED) {
                        stack.shrink(1);
                        if (stack.isEmpty()) nish.setStackInSlot(i, ItemStack.EMPTY);
                        else nish.setStackInSlot(i, stack);
                        nish.onContentsChanged();
                        openBlackHole(isHyper);
                        GTDishanhaiMod.LOGGER.info("[BHC] *** {} consumed → status={} ***", rl, blackHoleStatus);
                        return;
                    }
                    if (rl.equals(ID_COLLAPSER) && blackHoleStatus != STATUS_CLOSED) {
                        stack.shrink(1);
                        if (stack.isEmpty()) nish.setStackInSlot(i, ItemStack.EMPTY);
                        else nish.setStackInSlot(i, stack);
                        nish.onContentsChanged();
                        collapseBlackHole();
                        GTDishanhaiMod.LOGGER.info("[BHC] *** COLLAPSER consumed → CLOSING ***");
                        return;
                    }
                }
            }
        }
    }

    // ==================== 并行 ====================

    private long totalParallel; // 缓存供 getAdditionalThread 使用

    @Override public int getMaxParallel() {
        int t = Math.min(Math.max(1, getTier()), 30);
        long p = 8L * t;

        if (blackHoleStatus == STATUS_HYPER_STABLE) p *= 4;
        else {
            if (blackHoleStability < 50) p *= 2;
            if (blackHoleStability < 20) p *= 2;
        }

        if (catalyticBurstEnabled) p *= catalyzingCostModifier;

        totalParallel = p;
        return (int) Math.min(p, Integer.MAX_VALUE);
    }

    @Override public int getAdditionalThread() {
        if (totalParallel <= Integer.MAX_VALUE) return 0;
        int threads = (int) Math.min(totalParallel / Integer.MAX_VALUE, (long) Integer.MAX_VALUE * 2);
        return Math.max(0, threads - 1);
    }

    // ==================== 配方逻辑 ====================

    @Override
    public GTLAddMultipleTypeWirelessRecipesLogic createRecipeLogic(Object... args) {
        return new BHCRecipeLogic(this);
    }

    public static class BHCRecipeLogic extends GTLAddMultipleTypeWirelessRecipesLogic {
        public BHCRecipeLogic(GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine m) { super(m); }
        @Override public ShanhaiBlackHoleContainmentMachine getMachine() { return (ShanhaiBlackHoleContainmentMachine) super.getMachine(); }

        /** GTNH setupProcessingLogic: 每个配方周期开始时检查催化剂 */
        @Override public void findAndHandleRecipe() {
            var m = getMachine();
            if (m.recipeLogicSetup) {
                m.checkCatalystItems();
                m.recipeLogicSetup = false;
            }
            if (m.blackHoleStatus == STATUS_CLOSED) {
                setWaiting(Component.literal("需要先开启黑洞"));
                return;
            }
            super.findAndHandleRecipe();
        }

        /** 无线路径: getGTRecipe 返回前检查 BHC 条件 */
        @Override
        public GTRecipe getGTRecipe() {
            GTRecipe recipe = super.getGTRecipe();
            if (recipe != null && !checkBHCCondition(recipe)) return null;
            return recipe;
        }

        /** 有线路径: lookupRecipeIterator 过滤不满足 BHC 条件的配方 */
        @Override
        protected Set<GTRecipe> lookupRecipeIterator() {
            Set<GTRecipe> result = new ObjectOpenHashSet<>();
            Set<GTRecipe> normal = super.lookupRecipeIterator();
            if (normal != null) {
                for (GTRecipe r : normal) {
                    if (r != null && checkBHCCondition(r)) result.add(r);
                }
            }
            return result;
        }

        /** 检查 BHC 配方条件: 优先静态表, 回退 recipe.conditions */
        private boolean checkBHCCondition(GTRecipe recipe) {
            String recipeId = recipe.getId() != null ? recipe.getId().toString() : "";
            // 1. 静态注册表 (绕过 KubeJS 序列化类型丢失)
            java.util.List<BHCRecipeCondition> staticReqs = BHCRecipeCondition.getRequirements(recipeId);
            if (staticReqs != null && !staticReqs.isEmpty()) {
                for (var cond : staticReqs) {
                    if (!cond.test(recipe, this)) return false;
                }
                return true;
            }
            // 2. recipe.conditions 回退
            if (recipe.conditions == null || recipe.conditions.isEmpty()) return true;
            for (var cond : recipe.conditions) {
                if (cond instanceof BHCRecipeCondition bhc && !bhc.test(recipe, this)) return false;
                if (cond.getType() == BHCRecipeCondition.TYPE && cond instanceof BHCRecipeCondition bhc2 && !bhc2.test(recipe, this))
                    return false;
            }
            return true;
        }

        /** GTNH onRunningTick: 失稳时摧毁输出产物 */
        @Override protected boolean handleRecipeIO(GTRecipe recipe, IO io) {
            if (io == IO.OUT && getMachine().blackHoleStatus == STATUS_UNSTABLE) {
                GTDishanhaiMod.LOGGER.info("[BHC] unstable black hole consumed recipe outputs");
                return true;
            }
            return super.handleRecipeIO(recipe, io);
        }

        @Override public void onRecipeFinish() {
            var m = getMachine();
            // 事件视界爆破: 完成后湮灭黑洞
            if (getMachine().getRecipeType() == DShanhaiRecipeTypes.BLACK_HOLE_EVENT_HORIZON_BLAST) {
                m.collapseBlackHole();
                GTDishanhaiMod.LOGGER.info("[BHC] Event Horizon Blast — black hole annihilated");
            }
            super.onRecipeFinish();
        }
    }

    // ==================== Jade 显示 ====================

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            textList.add(Component.literal(ChatFormatting.GRAY + "稳定度: " + ChatFormatting.YELLOW
                + String.format("%.1f", blackHoleStability) + "%  "
                + (blackHoleStability > 50 ? ChatFormatting.GREEN : blackHoleStability > 20 ? ChatFormatting.YELLOW : ChatFormatting.RED)
                + buildStabilityBar()));

            String modeText = switch (blackHoleStatus) {
                case 2 -> ChatFormatting.GREEN + "黑洞活跃 · 时空催化×" + catalyzingCostModifier;
                case 3 -> ChatFormatting.RED + "⚠ 失稳! 吞噬产物 · 倍率×" + catalyzingCostModifier;
                case 4 -> catalyticBurstEnabled
                    ? (ChatFormatting.LIGHT_PURPLE + "超稳态 · 时空催化×" + catalyzingCostModifier)
                    : (ChatFormatting.LIGHT_PURPLE + "超稳态 · 不衰减");
                default -> ChatFormatting.DARK_GRAY + "黑洞关闭 — 放入黑洞种子";
            };
            textList.add(Component.literal(modeText));
            textList.add(Component.literal(ChatFormatting.GRAY + "并行: " + ChatFormatting.YELLOW + getMaxParallel()
                + ChatFormatting.GRAY + " · " + ChatFormatting.AQUA + "8×/tier"));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
        }
        textList.add(Component.literal(ChatFormatting.DARK_PURPLE + "" + ChatFormatting.BOLD + "亚稳态黑洞遏制场"));
    }

    private String buildStabilityBar() {
        int barLen = 10, filled = Math.round(Math.max(0, blackHoleStability) / STABILITY_MAX * barLen);
        var sb = new StringBuilder();
        for (int i = 0; i < barLen; i++) sb.append(i < filled ? "§a|" : "§8|");
        return sb.toString();
    }

    // ==================== 多方块注册 ====================

    public static BlockPattern createPattern(MultiblockMachineDefinition def) {
        return com.dishanhai.gt_shanhai.common.machine.structure.BHCStructure.createPattern(def);
    }

    public static MultiblockMachineDefinition register() {
        return GTDishanhaiRegistration.REGISTRATE
            .multiblock("black_hole_containment", ShanhaiBlackHoleContainmentMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .recipeTypes(
                DShanhaiRecipeTypes.BLACK_HOLE_COMPRESSOR,
                DShanhaiRecipeTypes.BLACK_HOLE_NEUTRONIUM_COMPRESSOR,
                DShanhaiRecipeTypes.BLACK_HOLE_EVENT_HORIZON_BLAST)
            .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gt_shanhai","active_neutron_casing")))
            .pattern(ShanhaiBlackHoleContainmentMachine::createPattern)
            .renderer(() -> new com.dishanhai.gt_shanhai.client.renderer.machine.BlackHoleContainmentRenderer(
                new ResourceLocation("gtceu", "block/casings/hpca/high_power_casing"),
                new ResourceLocation(MOD_ID, "block/multiblock/blackhole_closed")))
            .hasTESR(true).register();
    }
}
