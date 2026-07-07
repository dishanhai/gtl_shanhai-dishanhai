package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopModuleMachine;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopModuleState;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopModuleType;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopUpgrade;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopUpgradeStorage;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.advancements.Advancement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;

import java.math.BigInteger;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 永恒格雷工坊主机。
 *
 * 第一阶段承载主机结构、持久化状态与模块状态容器；升级、燃料与专用 UI 后续分层接入。
 */
public class EternalGregTechWorkshopMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(EternalGregTechWorkshopMachine.class, getMANAGED_FIELD_HOLDER());

    private static final String KEY_ROOT = "sh_eternal_workshop";
    private static final String KEY_VERSION = "version";
    private static final String KEY_MACHINE_TIER = "machineTier";
    private static final String KEY_MODULE_TIER = "moduleTier";
    private static final String KEY_MAX_USE_EUT = "maxUseEUt";
    private static final String KEY_EUT_DISCOUNT = "eutDiscount";
    private static final String KEY_DURATION_MODIFIER = "durationModifier";
    private static final String KEY_EXTRA_MODULE = "extraModule";
    private static final String KEY_ENABLE_EXTRA_MODULE = "enableExtraModule";
    private static final String KEY_SECRET_UPGRADE = "secretUpgrade";
    private static final String KEY_ENABLE_RENDER = "enableRender";
    private static final String KEY_RENDER_ACTIVE = "renderActive";
    private static final String KEY_TOTAL_POWER_CONSUMED = "totalPowerConsumed";
    private static final String KEY_TOTAL_FUEL_CONSUMED = "totalFuelConsumed";
    private static final String KEY_TOTAL_FUEL_MILESTONE_POINTS = "totalFuelMilestonePoints";
    private static final String KEY_FUEL_MILESTONE_WEIGHTED = "fuelMilestoneWeighted";
    private static final String KEY_TOTAL_RECIPES_PROCESSED = "totalRecipesProcessed";
    private static final String KEY_TOTAL_RUN_TIME = "totalRunTime";
    private static final String KEY_FUEL_CONSUMPTION = "fuelConsumption";
    private static final String KEY_FUEL_CONSUMPTION_FACTOR = "fuelConsumptionFactor";
    private static final String KEY_SELECTED_FUEL_TYPE = "selectedFuelType";
    private static final String KEY_GRAVITON_SHARDS_AVAILABLE = "gravitonShardsAvailable";
    private static final String KEY_GRAVITON_SHARDS_SPENT = "gravitonShardsSpent";
    private static final String KEY_GRAVITON_SHARD_EJECTION = "gravitonShardEjection";
    private static final String KEY_GRAVITON_SHARDS_DEPOSITED = "gravitonShardsDeposited";
    private static final String KEY_GRAVITON_SHARDS_EJECTED = "gravitonShardsEjected";
    private static final String KEY_CURRENT_UPGRADE = "currentUpgrade";
    private static final String KEY_POWER_MILESTONE_PERCENTAGE = "powerMilestonePercentage";
    private static final String KEY_RECIPE_MILESTONE_PERCENTAGE = "recipeMilestonePercentage";
    private static final String KEY_FUEL_MILESTONE_PERCENTAGE = "fuelMilestonePercentage";
    private static final String KEY_STRUCTURE_MILESTONE_PERCENTAGE = "structureMilestonePercentage";
    private static final String KEY_MILESTONE_PROGRESS = "milestoneProgress";
    private static final String KEY_MODULES = "modules";
    private static final long GTNL_BASE_MAX_USE_EUT = 21_474_836_470L;
    private static final long POWER_MILESTONE_CONSTANT = 1_000_000_000_000_000L;
    private static final long RECIPE_MILESTONE_CONSTANT = 10_000_000L;
    private static final long FUEL_MILESTONE_CONSTANT = 10_000L;
    private static final double POWER_LOG_CONSTANT = Math.log(9.0D);
    private static final double RECIPE_LOG_CONSTANT = Math.log(4.0D);
    private static final double FUEL_LOG_CONSTANT = Math.log(3.0D);
    private static final long[] FUEL_MILESTONE_WEIGHTS = {1L, 160L, 1800L};
    private static final int MAX_SAFE_PARALLEL = Integer.MAX_VALUE - 1;
    private static final double WORKSHOP_EFFECT_BACK_DISTANCE = 26.0D;
    private static final double WORKSHOP_ADVANCEMENT_RADIUS = 96.0D;
    private static final long ACTIVITY_FEEDBACK_INTERVAL = 40L;
    private static final long MODULE_STATE_STALE_TICKS = 400L;
    private static final ResourceLocation ADVANCEMENT_WORKSHOP_FORMED =
            new ResourceLocation("gt_shanhai", "eternal_gregtech_workshop/formed");
    private static final ResourceLocation ADVANCEMENT_WORKSHOP_RUNNING =
            new ResourceLocation("gt_shanhai", "eternal_gregtech_workshop/running");
    private static final ResourceLocation[] FUEL_FLUIDS = {
            new ResourceLocation("gtceu", "dimensionallytranscendentresidue"),
            new ResourceLocation("gtceu", "raw_star_matter_plasma"),
            new ResourceLocation("gtceu", "magnetohydrodynamicallyconstrainedstarmatter")
    };
    private static final String[] FUEL_NAMES = {
            "超维度残留",
            "原始恒星混合物等离子体",
            "液态磁流体约束恒星物质"
    };

    @Persisted @DescSynced private int machineTier;
    @Persisted @DescSynced private int moduleTier;
    private long maxUseEUt;
    private double eutDiscount = 1.0D;
    private double durationModifier = 1.0D;
    private boolean extraModule;
    private boolean enableExtraModule;
    private boolean secretUpgrade;
    @Persisted @DescSynced private boolean enableRender = true;
    @Persisted @DescSynced private boolean renderActive;
    private String totalPowerConsumed = "0";
    private long totalFuelConsumed;
    private long totalFuelMilestonePoints;
    private long totalRecipesProcessed;
    private long totalRunTime;
    private long fuelConsumption;
    private int fuelConsumptionFactor = 1;
    private int selectedFuelType;
    private int gravitonShardsAvailable;
    private int gravitonShardsSpent;
    private boolean gravitonShardEjection;
    private boolean fuelAvailable;
    private int gravitonShardsDeposited;
    private int gravitonShardsEjected;
    private float powerMilestonePercentage;
    private float recipeMilestonePercentage;
    private float fuelMilestonePercentage;
    private float structureMilestonePercentage;
    private final int[] milestoneProgress = new int[] {0, 0, 0, 0};
    private final EternalGregTechWorkshopUpgradeStorage upgrades = new EternalGregTechWorkshopUpgradeStorage();
    private EternalGregTechWorkshopUpgrade currentUpgrade = EternalGregTechWorkshopUpgrade.START;
    private TickableSubscription workshopTickSubscription;
    private long lastActivityFeedbackTick = Long.MIN_VALUE;
    private final EnumMap<EternalGregTechWorkshopModuleType, EternalGregTechWorkshopModuleState> moduleStates =
            new EnumMap<>(EternalGregTechWorkshopModuleType.class);

    public EternalGregTechWorkshopMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        for (EternalGregTechWorkshopModuleType type : EternalGregTechWorkshopModuleType.values()) {
            moduleStates.put(type, new EternalGregTechWorkshopModuleState());
        }
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public int getMaxParallel() {
        return getEffectiveModuleMaxParallel();
    }

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        if (machineTier <= 0) {
            machineTier = 1;
        }
        if (moduleTier <= 0) {
            moduleTier = 1;
        }
        refreshBaseModuleBonuses();
        renderActive = enableRender;
        updateWorkshopTickSubscription();
        awardNearbyWorkshopAdvancement(ADVANCEMENT_WORKSHOP_FORMED);
        markDirty();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        renderActive = false;
        if (workshopTickSubscription != null) {
            workshopTickSubscription.unsubscribe();
            workshopTickSubscription = null;
        }
        markDirty();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() != null && isFormed()) {
            renderActive = enableRender;
            if (!getLevel().isClientSide) {
                updateWorkshopTickSubscription();
            }
        }
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (workshopTickSubscription != null) {
            workshopTickSubscription.unsubscribe();
            workshopTickSubscription = null;
        }
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_VERSION, 1);
        root.putInt(KEY_MACHINE_TIER, machineTier);
        root.putInt(KEY_MODULE_TIER, moduleTier);
        root.putLong(KEY_MAX_USE_EUT, maxUseEUt);
        root.putDouble(KEY_EUT_DISCOUNT, eutDiscount);
        root.putDouble(KEY_DURATION_MODIFIER, durationModifier);
        root.putBoolean(KEY_EXTRA_MODULE, extraModule);
        root.putBoolean(KEY_ENABLE_EXTRA_MODULE, enableExtraModule);
        root.putBoolean(KEY_SECRET_UPGRADE, secretUpgrade);
        root.putBoolean(KEY_ENABLE_RENDER, enableRender);
        root.putBoolean(KEY_RENDER_ACTIVE, renderActive);
        root.putString(KEY_TOTAL_POWER_CONSUMED, totalPowerConsumed);
        root.putLong(KEY_TOTAL_FUEL_CONSUMED, totalFuelConsumed);
        root.putLong(KEY_TOTAL_FUEL_MILESTONE_POINTS, totalFuelMilestonePoints);
        root.putBoolean(KEY_FUEL_MILESTONE_WEIGHTED, true);
        root.putLong(KEY_TOTAL_RECIPES_PROCESSED, totalRecipesProcessed);
        root.putLong(KEY_TOTAL_RUN_TIME, totalRunTime);
        root.putLong(KEY_FUEL_CONSUMPTION, fuelConsumption);
        root.putInt(KEY_FUEL_CONSUMPTION_FACTOR, fuelConsumptionFactor);
        root.putInt(KEY_SELECTED_FUEL_TYPE, selectedFuelType);
        root.putInt(KEY_GRAVITON_SHARDS_AVAILABLE, gravitonShardsAvailable);
        root.putInt(KEY_GRAVITON_SHARDS_SPENT, gravitonShardsSpent);
        root.putBoolean(KEY_GRAVITON_SHARD_EJECTION, gravitonShardEjection);
        root.putBoolean("fuelAvailable", fuelAvailable);
        root.putInt(KEY_GRAVITON_SHARDS_DEPOSITED, gravitonShardsDeposited);
        root.putInt(KEY_GRAVITON_SHARDS_EJECTED, gravitonShardsEjected);
        root.putInt(KEY_CURRENT_UPGRADE, currentUpgrade.ordinal());
        root.putFloat(KEY_POWER_MILESTONE_PERCENTAGE, powerMilestonePercentage);
        root.putFloat(KEY_RECIPE_MILESTONE_PERCENTAGE, recipeMilestonePercentage);
        root.putFloat(KEY_FUEL_MILESTONE_PERCENTAGE, fuelMilestonePercentage);
        root.putFloat(KEY_STRUCTURE_MILESTONE_PERCENTAGE, structureMilestonePercentage);
        CompoundTag milestones = new CompoundTag();
        for (int i = 0; i < milestoneProgress.length; i++) {
            milestones.putInt("milestone" + i, milestoneProgress[i]);
        }
        root.put(KEY_MILESTONE_PROGRESS, milestones);
        upgrades.save(root);

        CompoundTag modules = new CompoundTag();
        for (Map.Entry<EternalGregTechWorkshopModuleType, EternalGregTechWorkshopModuleState> entry : moduleStates.entrySet()) {
            modules.put(entry.getKey().id(), entry.getValue().save());
        }
        root.put(KEY_MODULES, modules);
        tag.put(KEY_ROOT, root);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (!tag.contains(KEY_ROOT)) {
            return;
        }

        CompoundTag root = tag.getCompound(KEY_ROOT);
        if (root.contains(KEY_MACHINE_TIER)) machineTier = Math.max(0, root.getInt(KEY_MACHINE_TIER));
        if (root.contains(KEY_MODULE_TIER)) moduleTier = Math.max(0, root.getInt(KEY_MODULE_TIER));
        if (root.contains(KEY_MAX_USE_EUT)) maxUseEUt = Math.max(0L, root.getLong(KEY_MAX_USE_EUT));
        if (root.contains(KEY_EUT_DISCOUNT)) eutDiscount = Math.max(0.0D, root.getDouble(KEY_EUT_DISCOUNT));
        if (root.contains(KEY_DURATION_MODIFIER)) durationModifier = Math.max(0.0D, root.getDouble(KEY_DURATION_MODIFIER));
        if (root.contains(KEY_EXTRA_MODULE)) extraModule = root.getBoolean(KEY_EXTRA_MODULE);
        if (root.contains(KEY_ENABLE_EXTRA_MODULE)) enableExtraModule = root.getBoolean(KEY_ENABLE_EXTRA_MODULE);
        if (root.contains(KEY_SECRET_UPGRADE)) secretUpgrade = root.getBoolean(KEY_SECRET_UPGRADE);
        if (root.contains(KEY_ENABLE_RENDER)) enableRender = root.getBoolean(KEY_ENABLE_RENDER);
        if (root.contains(KEY_RENDER_ACTIVE)) renderActive = root.getBoolean(KEY_RENDER_ACTIVE);
        if (root.contains(KEY_TOTAL_POWER_CONSUMED)) totalPowerConsumed = root.getString(KEY_TOTAL_POWER_CONSUMED);
        if (root.contains(KEY_TOTAL_FUEL_CONSUMED)) totalFuelConsumed = root.getLong(KEY_TOTAL_FUEL_CONSUMED);
        boolean hasFuelMilestonePoints = root.contains(KEY_TOTAL_FUEL_MILESTONE_POINTS);
        if (hasFuelMilestonePoints) totalFuelMilestonePoints = Math.max(0L, root.getLong(KEY_TOTAL_FUEL_MILESTONE_POINTS));
        if (root.contains(KEY_TOTAL_RECIPES_PROCESSED)) totalRecipesProcessed = Math.max(0L, root.getLong(KEY_TOTAL_RECIPES_PROCESSED));
        if (root.contains(KEY_TOTAL_RUN_TIME)) totalRunTime = Math.max(0L, root.getLong(KEY_TOTAL_RUN_TIME));
        if (root.contains(KEY_FUEL_CONSUMPTION)) fuelConsumption = Math.max(0L, root.getLong(KEY_FUEL_CONSUMPTION));
        if (root.contains(KEY_FUEL_CONSUMPTION_FACTOR)) fuelConsumptionFactor = Math.max(1, root.getInt(KEY_FUEL_CONSUMPTION_FACTOR));
        if (root.contains(KEY_SELECTED_FUEL_TYPE)) {
            selectedFuelType = clampFuelType(root.getInt(KEY_SELECTED_FUEL_TYPE));
        }
        if (!hasFuelMilestonePoints && totalFuelConsumed > 0L) {
            totalFuelMilestonePoints = totalFuelConsumed;
        }
        int savedGravitonShardsAvailable = -1;
        if (root.contains(KEY_GRAVITON_SHARDS_AVAILABLE)) {
            savedGravitonShardsAvailable = Math.max(0, root.getInt(KEY_GRAVITON_SHARDS_AVAILABLE));
            gravitonShardsAvailable = savedGravitonShardsAvailable;
        }
        if (root.contains(KEY_GRAVITON_SHARDS_SPENT)) gravitonShardsSpent = Math.max(0, root.getInt(KEY_GRAVITON_SHARDS_SPENT));
        if (root.contains(KEY_GRAVITON_SHARD_EJECTION)) gravitonShardEjection = root.getBoolean(KEY_GRAVITON_SHARD_EJECTION);
        if (root.contains("fuelAvailable")) fuelAvailable = root.getBoolean("fuelAvailable");
        if (root.contains(KEY_GRAVITON_SHARDS_DEPOSITED)) gravitonShardsDeposited = Math.max(0, root.getInt(KEY_GRAVITON_SHARDS_DEPOSITED));
        boolean hasEjectedShards = root.contains(KEY_GRAVITON_SHARDS_EJECTED);
        if (hasEjectedShards) gravitonShardsEjected = Math.max(0, root.getInt(KEY_GRAVITON_SHARDS_EJECTED));
        if (root.contains(KEY_CURRENT_UPGRADE)) {
            int index = Math.max(0, Math.min(EternalGregTechWorkshopUpgrade.VALUES.length - 1, root.getInt(KEY_CURRENT_UPGRADE)));
            currentUpgrade = EternalGregTechWorkshopUpgrade.VALUES[index];
        }
        if (root.contains(KEY_POWER_MILESTONE_PERCENTAGE)) powerMilestonePercentage = clampProgress(root.getFloat(KEY_POWER_MILESTONE_PERCENTAGE));
        if (root.contains(KEY_RECIPE_MILESTONE_PERCENTAGE)) recipeMilestonePercentage = clampProgress(root.getFloat(KEY_RECIPE_MILESTONE_PERCENTAGE));
        if (root.contains(KEY_FUEL_MILESTONE_PERCENTAGE)) fuelMilestonePercentage = clampProgress(root.getFloat(KEY_FUEL_MILESTONE_PERCENTAGE));
        if (root.contains(KEY_STRUCTURE_MILESTONE_PERCENTAGE)) structureMilestonePercentage = clampProgress(root.getFloat(KEY_STRUCTURE_MILESTONE_PERCENTAGE));
        if (root.contains(KEY_MILESTONE_PROGRESS)) {
            CompoundTag milestones = root.getCompound(KEY_MILESTONE_PROGRESS);
            for (int i = 0; i < milestoneProgress.length; i++) {
                milestoneProgress[i] = Math.max(0, Math.min(7, milestones.getInt("milestone" + i)));
            }
        }
        upgrades.load(root);
        if (!hasEjectedShards && savedGravitonShardsAvailable >= 0) {
            int expectedAvailable = calculateAvailableGravitonShards(
                    getTotalGravitonShardsEarned(), gravitonShardsSpent, gravitonShardsDeposited, 0);
            if (savedGravitonShardsAvailable < expectedAvailable) {
                gravitonShardsEjected = expectedAvailable - savedGravitonShardsAvailable;
            }
        }
        recalculateGravitonShardsAvailable();

        if (root.contains(KEY_MODULES)) {
            CompoundTag modules = root.getCompound(KEY_MODULES);
            for (EternalGregTechWorkshopModuleType type : EternalGregTechWorkshopModuleType.values()) {
                if (modules.contains(type.id())) {
                    moduleStates.get(type).load(modules.getCompound(type.id()));
                }
            }
        }
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        textList.add(Component.translatable("gt_shanhai.machine.eternal_gregtech_workshop.name")
                .withStyle(ChatFormatting.GOLD));
        textList.add(Component.literal("结构已成型"));
        textList.add(Component.literal("等级: " + getMachineTier() + " / 模块等级: " + getModuleTier()));
        textList.add(Component.literal("运行时间: " + getTotalRunTimeHoursText()));
        textList.add(Component.literal("累计配方: " + formatLong(getTotalRecipesProcessed())
                + " / 累计EU: " + getTotalPowerConsumed()));
        textList.add(Component.literal("燃料累计: " + formatLong(getTotalFuelConsumed())
                + " / 本次结算: " + formatLong(getFuelConsumption())
                + " / 当前消耗: " + formatLong(getCurrentFuelConsumption())));
        textList.add(Component.literal("燃料计数: " + formatLong(getTotalFuelMilestonePoints())
                + " / 当前权重: x" + getCurrentFuelMilestoneWeight()));
        textList.add(Component.literal("渲染: " + (isRenderActive() ? "已启用" : "未启用")));
        textList.add(Component.literal("已连接模块: " + getConnectedModuleCount()
                + " / 上限: " + getMaxConnectedModules()));
        textList.add(Component.literal("引力子碎片: " + getGravitonShardsAvailable()
                + " 可用 / " + getTotalGravitonShardsEarned() + " 已获得 / "
                + getDepositedGravitonShards() + " 已回存 / "
                + getEjectedGravitonShards() + " 已弹出 / "
                + getGravitonShardsSpent() + " 已花费"));
        textList.add(Component.literal("里程碑: 功率 " + getMilestoneProgress(0)
                + " 配方 " + getMilestoneProgress(1)
                + " 燃料 " + getMilestoneProgress(2)
                + " 结构 " + getMilestoneProgress(3)));
        textList.add(Component.literal("当前升级: " + currentUpgrade.shortName()
                + (isUpgradeActive(currentUpgrade) ? " 已激活" : " 未激活")));
        textList.add(Component.literal("有效并行: " + formatParallel(getEffectiveModuleMaxParallel())
                + " / 最大EU: " + formatLong(getEffectiveModuleMaxUseEUt())));
        textList.add(Component.literal("EU折扣: " + formatMultiplier(getEffectiveModuleEUtDiscount())
                + " / 时长系数: " + formatMultiplier(getEffectiveModuleDurationModifier())
                + " / 热量: " + getEffectiveModuleHeat()));
        textList.add(DShanhaiTextUtil.createAuroraText("主机已汇总已连接成型模块的实际增益"));
    }

    public int getMachineTier() {
        return Math.max(0, machineTier);
    }

    public void setMachineTier(int machineTier) {
        this.machineTier = Math.max(0, machineTier);
        markDirty();
    }

    public int getModuleTier() {
        return Math.max(0, moduleTier);
    }

    public void setModuleTier(int moduleTier) {
        this.moduleTier = Math.max(0, moduleTier);
        refreshBaseModuleBonuses(true);
        markDirty();
    }

    public long getMaxUseEUt() {
        return maxUseEUt;
    }

    public double getEUtDiscount() {
        return eutDiscount;
    }

    public double getDurationModifier() {
        return durationModifier;
    }

    public boolean hasExtraModule() {
        return extraModule;
    }

    public boolean isEnableExtraModule() {
        return enableExtraModule;
    }

    public boolean hasSecretUpgrade() {
        return secretUpgrade;
    }

    public boolean isRenderEnabled() {
        return enableRender;
    }

    public void setRenderEnabled(boolean enableRender) {
        this.enableRender = enableRender;
        this.renderActive = enableRender && isFormed();
        markDirty();
        if (getLevel() != null) {
            notifyBlockUpdate();
        }
        scheduleRenderUpdate();
    }

    public boolean isRenderActive() {
        if (getLevel() != null && getLevel().isClientSide) {
            return enableRender && isFormed();
        }
        return renderActive && enableRender && isFormed();
    }

    public String getTotalPowerConsumed() {
        return totalPowerConsumed;
    }

    public long getTotalFuelConsumed() {
        return totalFuelConsumed;
    }

    public long getTotalFuelMilestonePoints() {
        return Math.max(0L, totalFuelMilestonePoints);
    }

    public long getTotalRecipesProcessed() {
        return totalRecipesProcessed;
    }

    public long getTotalRunTime() {
        return totalRunTime;
    }

    public String getTotalRunTimeHoursText() {
        return String.format(Locale.ROOT, "%.2fh", totalRunTime / 72000.0D);
    }

    public int getFuelConsumptionFactor() {
        return Math.max(1, fuelConsumptionFactor);
    }

    public long getFuelConsumption() {
        return Math.max(0L, fuelConsumption);
    }

    public long getCurrentFuelConsumption() {
        return calculateFuelConsumption(getSelectedFuelType(), getFuelConsumptionFactor());
    }

    public int getSelectedFuelType() {
        return selectedFuelType;
    }

    public String getFuelName() {
        int index = Math.max(0, Math.min(FUEL_NAMES.length - 1, getSelectedFuelType()));
        return FUEL_NAMES[index];
    }

    public long getCurrentFuelMilestoneWeight() {
        return getFuelMilestoneWeight(getSelectedFuelType());
    }

    private static long getFuelMilestoneWeight(int fuelType) {
        int index = Math.max(0, Math.min(FUEL_MILESTONE_WEIGHTS.length - 1, fuelType));
        return FUEL_MILESTONE_WEIGHTS[index];
    }

    public int getGravitonShardsAvailable() {
        return Math.max(0, gravitonShardsAvailable);
    }

    public int getGravitonShardsSpent() {
        return Math.max(0, gravitonShardsSpent);
    }

    public int getEjectedGravitonShards() {
        return Math.max(0, gravitonShardsEjected);
    }

    public boolean isGravitonShardEjectionEnabled() {
        return gravitonShardEjection;
    }

    public int getMilestoneProgress(int index) {
        if (index < 0 || index >= milestoneProgress.length) {
            return 0;
        }
        return Math.max(0, Math.min(7, milestoneProgress[index]));
    }

    public float getMilestonePercentage(int index) {
        return switch (index) {
            case 0 -> powerMilestonePercentage;
            case 1 -> recipeMilestonePercentage;
            case 2 -> fuelMilestonePercentage;
            case 3 -> structureMilestonePercentage;
            default -> 0.0F;
        };
    }

    public int getTotalGravitonShardsEarned() {
        int total = 0;
        for (int progress : milestoneProgress) {
            int level = Math.max(0, Math.min(7, progress));
            total += level * (level + 1) / 2;
        }
        return total;
    }

    public EternalGregTechWorkshopUpgrade getCurrentUpgrade() {
        return currentUpgrade;
    }

    public int getTotalActiveUpgrades() {
        return upgrades.getTotalActiveUpgrades();
    }

    public boolean isUpgradeActive(EternalGregTechWorkshopUpgrade upgrade) {
        return upgrades.isUpgradeActive(upgrade);
    }

    public boolean completeUpgrade(EternalGregTechWorkshopUpgrade upgrade) {
        if (upgrade == null || isUpgradeActive(upgrade)) {
            return false;
        }
        recalculateGravitonShardsAvailable();
        if (!upgrades.checkPrerequisites(upgrade) || !upgrades.checkSplit(upgrade, getMaxSplitUpgrades())) {
            return false;
        }
        if (!upgrades.checkCost(upgrade, getGravitonShardsAvailable())) {
            return false;
        }
        upgrades.unlockUpgrade(upgrade);
        gravitonShardsSpent = saturatingAddInt(gravitonShardsSpent, upgrade.shardCost());
        recalculateGravitonShardsAvailable();
        refreshBaseModuleBonuses(true);
        markDirty();
        return true;
    }

    public boolean respecUpgrade(EternalGregTechWorkshopUpgrade upgrade) {
        if (upgrade == null || !isUpgradeActive(upgrade) || !upgrades.checkDependents(upgrade)) {
            return false;
        }
        upgrades.respecUpgrade(upgrade);
        gravitonShardsSpent = Math.max(0, gravitonShardsSpent - upgrade.shardCost());
        if (upgrade == EternalGregTechWorkshopUpgrade.END) {
            gravitonShardEjection = false;
        }
        recalculateGravitonShardsAvailable();
        refreshBaseModuleBonuses(true);
        markDirty();
        return true;
    }

    public void recordModuleRecipeStarted(GTRecipe recipe) {
        recordModuleRecipeStarted(recipe, null);
    }

    public void recordModuleRecipeStarted(GTRecipe recipe, EternalGregTechWorkshopModuleMachine module) {
        if (recipe == null || getLevel() == null || getLevel().isClientSide) {
            return;
        }
        long fuelUse = getCurrentFuelConsumption();
        if (fuelUse > 0L) {
            if (!drainWorkshopFuel(fuelUse, module)) {
                fuelAvailable = false;
                markDirty();
                return;
            }
            totalFuelConsumed = saturatingAdd(totalFuelConsumed, fuelUse);
            totalFuelMilestonePoints = saturatingAdd(totalFuelMilestonePoints,
                    saturatingMultiply(fuelUse, getCurrentFuelMilestoneWeight()));
            fuelConsumption = fuelUse;
            fuelAvailable = checkFuelAvailable(module);
        }

        long recipeCount = getRecipeParallelCount(recipe);
        totalRecipesProcessed = saturatingAdd(totalRecipesProcessed, recipeCount);

        long eut = Math.max(0L, RecipeHelper.getInputEUt(recipe));
        if (eut > 0L && recipe.duration > 0) {
            BigInteger energy = BigInteger.valueOf(eut)
                    .multiply(BigInteger.valueOf(recipe.duration))
                    .multiply(BigInteger.valueOf(recipeCount));
            totalPowerConsumed = addDecimalString(totalPowerConsumed, energy);
        }

        emitWorkshopActivityFeedback(recipeCount);
        awardNearbyWorkshopAdvancement(ADVANCEMENT_WORKSHOP_RUNNING);
        determineCompositionMilestoneLevel();
        determineMilestoneProgress();
        markDirty();
    }

    @Override
    public Widget createUIWidget() {
        int width = 190;
        int height = 170;
        WidgetGroup group = new WidgetGroup(0, 0, width, height);
        DraggableScrollableWidgetGroup scrollGroup = new DraggableScrollableWidgetGroup(4, 4, width - 8, height - 8);
        scrollGroup.setBackground(GuiTextures.DISPLAY);

        ComponentPanelWidget textPanel = new ComponentPanelWidget(4, 5, this::buildWorkshopControlText);
        textPanel.setMaxWidthLimit(width - 20);
        textPanel.clickHandler((cmd, cd) -> {
            if (cd.isRemote) return;
            int step = getClickStep(cd.isCtrlClick, cd.isShiftClick);
            switch (cmd) {
                case "machine_up" -> setMachineTier(Math.min(64, getMachineTier() + step));
                case "machine_down" -> setMachineTier(Math.max(1, getMachineTier() - step));
                case "module_up" -> setModuleTier(Math.min(64, getModuleTier() + step));
                case "module_down" -> setModuleTier(Math.max(1, getModuleTier() - step));
                case "fuel_up" -> setFuelConsumptionFactor(Math.min(Integer.MAX_VALUE, saturatingAddInt(getFuelConsumptionFactor(), step)));
                case "fuel_down" -> setFuelConsumptionFactor(Math.max(1, getFuelConsumptionFactor() - step));
                case "fuel_type" -> setSelectedFuelType((getSelectedFuelType() + 1) % FUEL_FLUIDS.length);
                case "render_toggle" -> setRenderEnabled(!isRenderEnabled());
                case "upgrade_next" -> setCurrentUpgrade(nextUpgrade(currentUpgrade));
                case "upgrade_prev" -> setCurrentUpgrade(previousUpgrade(currentUpgrade));
                case "upgrade_unlock" -> completeUpgrade(currentUpgrade);
                case "upgrade_respec" -> respecUpgrade(currentUpgrade);
                case "shard_eject" -> setGravitonShardEjection(!isGravitonShardEjectionEnabled());
            }
        });
        scrollGroup.addWidget(textPanel);
        group.addWidget(scrollGroup);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    public EternalGregTechWorkshopModuleState getModuleState(EternalGregTechWorkshopModuleType type) {
        return moduleStates.get(type);
    }

    public int getConnectedModuleCount() {
        int count = 0;
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state)) {
                count++;
            }
        }
        return count;
    }

    public boolean isWorkshopWorking() {
        if (getRecipeLogic() != null && getRecipeLogic().isWorking()) {
            return true;
        }
        long now = getWorkshopGameTime();
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state) && state.isWorking() && isRecentModuleState(state, now)) {
                return true;
            }
        }
        return false;
    }

    public double getEffectiveModuleEUtDiscount() {
        double result = getBaseEUtDiscount();
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state)) {
                result *= Math.max(0.0D, state.getEUtDiscount());
            }
        }
        result *= getUpgradeEutDiscountMultiplier();
        return Math.max(0.0D, result);
    }

    public double getEffectiveModuleDurationModifier() {
        double result = getBaseDurationModifier();
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state)) {
                result *= Math.max(0.0D, state.getDurationModifier());
            }
        }
        result *= getUpgradeDurationMultiplier();
        return Math.max(0.0D, result);
    }

    public int getEffectiveModuleMaxParallel() {
        long maxParallel = 1L;
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state)) {
                maxParallel = Math.max(maxParallel, state.getMaxParallel());
            }
        }
        maxParallel = saturatingMultiply(maxParallel, getUpgradeParallelMultiplier());
        if (maxParallel >= Integer.MAX_VALUE) {
            return MAX_SAFE_PARALLEL;
        }
        return Math.max(1, (int) maxParallel);
    }

    public long getEffectiveModuleMaxUseEUt() {
        long result = getBaseMaxUseEUt();
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state)) {
                result = Math.max(result, state.getMaxUseEUt());
            }
        }
        double mult = getUpgradeMaxUseEUtMultiplier();
        if (mult > 1.0D && result > 0L && result < Long.MAX_VALUE) {
            double scaled = result * mult;
            result = Double.isInfinite(scaled) || scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(scaled);
        }
        return Math.max(0L, result);
    }

    public int getEffectiveModuleHeat() {
        int heat = 0;
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state)) {
                heat = Math.max(heat, state.getHeat());
            }
        }
        return Math.max(0, heat);
    }

    public void setModuleConnected(EternalGregTechWorkshopModuleType type, boolean connected, boolean formed, int level) {
        EternalGregTechWorkshopModuleState state = moduleStates.get(type);
        if (state == null) {
            return;
        }
        state.setConnected(connected);
        state.setFormed(formed);
        state.setLevel(level);
        markDirty();
    }

    public void applyModuleState(EternalGregTechWorkshopModuleType type, EternalGregTechWorkshopModuleState incoming) {
        EternalGregTechWorkshopModuleState state = moduleStates.get(type);
        if (state == null) {
            return;
        }
        state.copyFrom(incoming);
        markDirty();
    }

    public void clearModuleState(EternalGregTechWorkshopModuleType type) {
        EternalGregTechWorkshopModuleState state = moduleStates.get(type);
        if (state == null) {
            return;
        }
        state.clear();
        markDirty();
    }

    private void updateWorkshopTickSubscription() {
        if (getLevel() == null || getLevel().isClientSide || !isFormed()) {
            return;
        }
        if (workshopTickSubscription == null) {
            workshopTickSubscription = subscribeServerTick(this::doWorkshopServerTick);
        }
    }

    private static final long SHARD_EJECTION_INTERVAL = 1200L; // 60 秒
    private long lastShardEjectionTick;

    private boolean checkFuelAvailable() {
        return checkFuelAvailable(null);
    }

    private boolean checkFuelAvailable(EternalGregTechWorkshopModuleMachine module) {
        long amount = getCurrentFuelConsumption();
        if (amount <= 0L) return true;
        ResourceLocation fuelId = FUEL_FLUIDS[Math.max(0, Math.min(FUEL_FLUIDS.length - 1, getSelectedFuelType()))];
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(fuelId);
        if (fluid == null || fluid == Fluids.EMPTY) return false;
        return consumeWorkshopFuelFromCapabilities(amount, fluid, true, module) <= 0L;
    }
    private void doWorkshopServerTick() {
        if (!isFormed()) {
            return;
        }
        totalRunTime++;
        if (totalRunTime % 100L == 0L) {
            pruneStaleModuleStates();
            determineCompositionMilestoneLevel();
            determineMilestoneProgress();
        }
        // 更新燃料可用状态（每 20 tick 检查一次）
        if (totalRunTime % 20L == 0L) {
            fuelAvailable = checkFuelAvailable();
        }
        if (gravitonShardEjection && gravitonShardsAvailable > 0
                && totalRunTime - lastShardEjectionTick >= SHARD_EJECTION_INTERVAL) {
            ejectGravitonShards();
        }
        if ((totalRunTime & 0x3fL) == 0L) {
            markDirty();
        }
    }

    private void pruneStaleModuleStates() {
        long now = getWorkshopGameTime();
        if (now <= 0L) {
            return;
        }
        boolean changed = false;
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (state == null || !state.isConnected()) {
                continue;
            }
            if (!isRecentModuleState(state, now)) {
                state.clear();
                changed = true;
            }
        }
        if (changed) {
            markDirty();
        }
    }

    private void ejectGravitonShards() {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        int toEject = Math.min(gravitonShardsAvailable, 1);
        if (toEject <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(GTDishanhaiMod.GRAVITON_SHARD.get(), toEject);
        double x = getPos().getX() + 0.5D;
        double y = getPos().getY() + 1.5D;
        double z = getPos().getZ() + 0.5D;
        ItemEntity entity = new ItemEntity(serverLevel, x, y, z, stack);
        entity.setDeltaMovement(0.0D, 0.2D, 0.0D);
        serverLevel.addFreshEntity(entity);
        gravitonShardsEjected = saturatingAddInt(gravitonShardsEjected, toEject);
        recalculateGravitonShardsAvailable();
        lastShardEjectionTick = totalRunTime;
        markDirty();
    }

    private void buildWorkshopControlText(List<Component> textList) {
        textList.add(Component.literal("§6§l永恒格雷工坊"));
        textList.add(Component.literal(isFormed() ? "§a结构已成型" : "§c结构未成型"));
        textList.add(Component.literal("§7运行时间: §f" + getTotalRunTimeHoursText()));
        textList.add(Component.literal("§7累计配方: §f" + formatLong(getTotalRecipesProcessed())));
        textList.add(Component.literal("§7累计EU: §f" + getTotalPowerConsumed()));
        textList.add(Component.literal("§7引力子碎片: §f" + getGravitonShardsAvailable()
                + " §7可用 / §f" + getGravitonShardsSpent() + " §7已花费"));
        textList.add(Component.literal("§7里程碑: §cP" + getMilestoneProgress(0)
                + " §dR" + getMilestoneProgress(1)
                + " §9F" + getMilestoneProgress(2)
                + " §aS" + getMilestoneProgress(3)));
        textList.add(Component.literal(""));

        textList.add(Component.literal("§e主机等级: §f" + getMachineTier() + " ")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[+]"), "machine_up"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§c[-]"), "machine_down")));
        textList.add(Component.literal("§b模块等级: §f" + getModuleTier() + " ")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[+]"), "module_up"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§c[-]"), "module_down")));
        textList.add(Component.literal("§6燃料因子: §f" + getFuelConsumptionFactor() + " ")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[+]"), "fuel_up"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§c[-]"), "fuel_down")));
        textList.add(Component.literal("§6燃料类型: §f" + getSelectedFuelType()
                + " §7" + getFuelName() + " ")
                .append(ComponentPanelWidget.withButton(Component.literal("§e[切换]"), "fuel_type")));
        textList.add(Component.literal("§d渲染: " + (isRenderEnabled() ? "§a已启用 " : "§8未启用 "))
                .append(ComponentPanelWidget.withButton(Component.literal("§e[开/关]"), "render_toggle")));
        textList.add(Component.literal("§5碎片弹出: " + (isGravitonShardEjectionEnabled() ? "§aON " : "§8OFF "))
                .append(ComponentPanelWidget.withButton(Component.literal("§e[切换]"), "shard_eject")));
        textList.add(Component.literal(""));

        textList.add(Component.literal("§d当前升级: §f" + currentUpgrade.shortName()
                + " §7(" + currentUpgrade.getShortDesc() + ")"));
        textList.add(Component.literal("  §7效果: §f" + currentUpgrade.getEffectDesc()));
        textList.add(Component.literal("  §7碎片: §b" + currentUpgrade.shardCost()
                + " §7状态: " + (isUpgradeActive(currentUpgrade) ? "§a已激活" : "§8未激活")));
        textList.add(Component.literal("")
                .append(ComponentPanelWidget.withButton(Component.literal("§e[上一个]"), "upgrade_prev"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§e[下一个]"), "upgrade_next"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[解锁]"), "upgrade_unlock"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§c[返还]"), "upgrade_respec")));
        textList.add(Component.literal("§7激活升级: §f" + getTotalActiveUpgrades()
                + "§7/" + EternalGregTechWorkshopUpgrade.VALUES.length
                + " §7前置: " + getPrerequisiteText(currentUpgrade)));
        textList.add(Component.literal(""));

        textList.add(Component.literal("§7已连接模块: §f" + getConnectedModuleCount()
                + "§7/" + getMaxConnectedModules()));
        textList.add(Component.literal("§7实际并行: §f" + formatParallel(getEffectiveModuleMaxParallel())));
        textList.add(Component.literal("§7EU折扣: §f" + formatMultiplier(getEffectiveModuleEUtDiscount())
                + " §7时长: §f" + formatMultiplier(getEffectiveModuleDurationModifier())));
        textList.add(Component.literal("§8Ctrl=1000 Shift=100 Ctrl+Shift=1000000"));
    }

    private void refreshBaseModuleBonuses() {
        refreshBaseModuleBonuses(false);
    }

    private void refreshBaseModuleBonuses(boolean force) {
        int tier = Math.max(1, moduleTier);
        if (force || eutDiscount >= 1.0D) {
            eutDiscount = Math.pow(0.95D, tier);
        }
        if (force || durationModifier >= 1.0D) {
            durationModifier = Math.pow(0.90D, tier);
        }
        if (force || maxUseEUt <= 0L) {
            maxUseEUt = calculateBaseMaxUseEUt(tier);
        }
    }

    private void setFuelConsumptionFactor(int fuelConsumptionFactor) {
        this.fuelConsumptionFactor = Math.max(1, Math.min(getMaxFuelFactor(), fuelConsumptionFactor));
        markDirty();
    }

    private void setSelectedFuelType(int selectedFuelType) {
        int max = Math.max(1, FUEL_FLUIDS.length);
        this.selectedFuelType = Math.max(0, Math.min(max - 1, selectedFuelType));
        markDirty();
    }

    private void setCurrentUpgrade(EternalGregTechWorkshopUpgrade upgrade) {
        if (upgrade == null) {
            return;
        }
        currentUpgrade = upgrade;
        markDirty();
    }

    private void setGravitonShardEjection(boolean enabled) {
        gravitonShardEjection = enabled && isUpgradeActive(EternalGregTechWorkshopUpgrade.END);
        markDirty();
    }

    public int getMaxConnectedModules() {
        if (!isFormed()) {
            return 0;
        }
        return Math.max(1, getMachineTier() * 4);
    }

    /** 工坊是否有足够燃料维持运行。模块启动前实时检查，避免无燃料继续运行。 */
    public boolean isFuelAvailable() {
        fuelAvailable = checkFuelAvailable();
        return fuelAvailable;
    }

    public boolean isFuelAvailableForModule(EternalGregTechWorkshopModuleMachine module) {
        fuelAvailable = checkFuelAvailable(module);
        return fuelAvailable;
    }

    public boolean canAcceptModuleConnection(EternalGregTechWorkshopModuleType type) {
        return canAcceptModuleConnection(type, null);
    }

    public boolean canAcceptModuleConnection(EternalGregTechWorkshopModuleType type, UUID moduleId) {
        EternalGregTechWorkshopModuleState state = moduleStates.get(type);
        if (state != null && isEffectiveModuleState(state)) {
            UUID existingOwner = state.getOwnerUUID();
            return existingOwner == null || (moduleId != null && moduleId.equals(existingOwner));
        }
        return getConnectedModuleCount() < getMaxConnectedModules();
    }

    private double getBaseEUtDiscount() {
        if (eutDiscount > 0.0D && eutDiscount < 1.0D) {
            return eutDiscount;
        }
        return Math.pow(0.95D, Math.max(1, moduleTier));
    }

    private double getBaseDurationModifier() {
        if (durationModifier > 0.0D && durationModifier < 1.0D) {
            return durationModifier;
        }
        return Math.pow(0.90D, Math.max(1, moduleTier));
    }

    private long getBaseMaxUseEUt() {
        if (maxUseEUt > 0L) {
            return maxUseEUt;
        }
        return calculateBaseMaxUseEUt(Math.max(1, moduleTier));
    }

    private static long calculateBaseMaxUseEUt(int tier) {
        int shift = Math.min(Math.max(1, tier), 28);
        try {
            return Math.multiplyExact(1L << shift, GTNL_BASE_MAX_USE_EUT);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private boolean drainWorkshopFuel(long amount) {
        return drainWorkshopFuel(amount, null);
    }

    private boolean drainWorkshopFuel(long amount, EternalGregTechWorkshopModuleMachine module) {
        if (amount <= 0L) {
            return true;
        }
        ResourceLocation fuelId = FUEL_FLUIDS[Math.max(0, Math.min(FUEL_FLUIDS.length - 1, getSelectedFuelType()))];
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(fuelId);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return false;
        }
        long remaining = consumeWorkshopFuelFromCapabilities(amount, fluid, false, module);
        return remaining <= 0L;
    }

    private long consumeWorkshopFuelFromCapabilities(long amount, Fluid fluid, boolean simulate,
                                                     EternalGregTechWorkshopModuleMachine module) {
        long remaining = amount;
        remaining = consumeWorkshopFuelFromHolder(this, fluid, remaining, simulate);
        if (remaining > 0L && module != null) {
            remaining = consumeWorkshopFuelFromHolder(module, fluid, remaining, simulate);
        }
        return remaining;
    }

    private static long consumeWorkshopFuelFromHolder(IRecipeCapabilityHolder holder, Fluid fluid,
                                                      long amount, boolean simulate) {
        long remaining = amount;
        if (holder == null || fluid == null || fluid == Fluids.EMPTY || remaining <= 0L) {
            return remaining;
        }
        List<IRecipeHandler<?>> handlers = getFluidInputHandlers(holder, IO.IN);
        remaining = consumeWorkshopFuelFromHandlers(handlers, fluid, remaining, simulate);
        if (remaining <= 0L) {
            return 0L;
        }
        return consumeWorkshopFuelFromHandlers(getFluidInputHandlers(holder, IO.BOTH), fluid, remaining, simulate);
    }

    private static List<IRecipeHandler<?>> getFluidInputHandlers(IRecipeCapabilityHolder holder, IO io) {
        if (holder == null || holder.getCapabilitiesProxy() == null
                || !holder.getCapabilitiesProxy().contains(io, FluidRecipeCapability.CAP)) {
            return Collections.emptyList();
        }
        List<IRecipeHandler<?>> handlers = holder.getCapabilitiesProxy().get(io, FluidRecipeCapability.CAP);
        return handlers == null ? Collections.emptyList() : handlers;
    }

    private static long consumeWorkshopFuelFromHandlers(List<IRecipeHandler<?>> handlers, Fluid fluid,
                                                       long amount, boolean simulate) {
        long remaining = amount;
        if (handlers == null || handlers.isEmpty()) {
            return remaining;
        }
        for (IRecipeHandler<?> handler : handlers) {
            if (remaining <= 0L) break;
            if (handler == null) {
                continue;
            }
            List<?> left = handler.handleRecipe(IO.IN, null,
                    List.of(FluidIngredient.of(remaining, fluid)), null, simulate);
            remaining = getRemainingWorkshopFuel(left);
        }
        return remaining;
    }

    private static long getRemainingWorkshopFuel(List<?> left) {
        if (left == null || left.isEmpty()) {
            return 0L;
        }
        long remaining = 0L;
        for (Object object : left) {
            if (object instanceof FluidIngredient ingredient) {
                remaining = saturatingAdd(remaining, ingredient.getAmount());
            }
        }
        return remaining;
    }

    private void emitWorkshopActivityFeedback(long recipeCount) {
        if (!(getLevel() instanceof ServerLevel serverLevel) || !renderActive) {
            return;
        }
        long gameTime = serverLevel.getGameTime();
        if (gameTime - lastActivityFeedbackTick < ACTIVITY_FEEDBACK_INTERVAL) {
            return;
        }
        lastActivityFeedbackTick = gameTime;

        Direction back = getFrontFacing().getOpposite();
        double x = getPos().getX() + 0.5D + back.getStepX() * WORKSHOP_EFFECT_BACK_DISTANCE;
        double y = getPos().getY() + 0.5D + back.getStepY() * WORKSHOP_EFFECT_BACK_DISTANCE;
        double z = getPos().getZ() + 0.5D + back.getStepZ() * WORKSHOP_EFFECT_BACK_DISTANCE;
        int particleCount = (int) Math.max(12L, Math.min(64L, 12L + recipeCount));

        serverLevel.playSound(null, getPos(), SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS,
                0.45F, 0.72F + Math.min(getMachineTier(), 16) * 0.015F);
        serverLevel.sendParticles(ParticleTypes.END_ROD, x, y, z, particleCount,
                1.6D, 0.9D, 1.6D, 0.045D);
        serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, Math.max(4, particleCount / 3),
                1.1D, 0.6D, 1.1D, 0.020D);
    }

    private void awardNearbyWorkshopAdvancement(ResourceLocation advancementId) {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Advancement advancement = serverLevel.getServer().getAdvancements().getAdvancement(advancementId);
        if (advancement == null) {
            return;
        }
        double x = getPos().getX() + 0.5D;
        double y = getPos().getY() + 0.5D;
        double z = getPos().getZ() + 0.5D;
        double radiusSqr = WORKSHOP_ADVANCEMENT_RADIUS * WORKSHOP_ADVANCEMENT_RADIUS;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(x, y, z) <= radiusSqr) {
                player.getAdvancements().award(advancement, "granted");
            }
        }
    }

    private long calculateFuelConsumption(int fuelType, int fuelFactor) {
        double factor = Math.max(1, fuelFactor);
        double amount;
        if (fuelType == 0) {
            amount = factor * 300.0D * Math.pow(1.15D, factor);
        } else if (fuelType == 1) {
            amount = factor * 2.0D * Math.pow(1.08D, factor);
        } else {
            amount = factor / 25.0D;
        }
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.STEM)) {
            amount *= 0.8D;
        }
        amount *= 5.0D;
        if (Double.isNaN(amount) || amount <= 0.0D) {
            return 1L;
        }
        if (Double.isInfinite(amount) || amount >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) Math.ceil(amount));
    }

    private int getMaxFuelFactor() {
        int fuelCap = 5;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.TSE)) {
            return Integer.MAX_VALUE;
        }
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.GEM)) {
            fuelCap = saturatingAddInt(fuelCap, getTotalActiveUpgrades());
        }
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.CFCE)) {
            fuelCap = Math.max(1, (int) Math.floor(fuelCap * 1.2D));
        }
        return Math.max(1, fuelCap);
    }

    private int getMaxSplitUpgrades() {
        return 1;
    }

    private void determineCompositionMilestoneLevel() {
        int uniqueModules = 0;
        for (EternalGregTechWorkshopModuleState state : moduleStates.values()) {
            if (isEffectiveModuleState(state)) {
                uniqueModules++;
            }
        }
        milestoneProgress[3] = Math.max(0, Math.min(7, uniqueModules));
        structureMilestonePercentage = clampProgress(uniqueModules / 7.0F);
        recalculateGravitonShardsAvailable();
    }

    private void determineMilestoneProgress() {
        double accel = getMilestoneAccelerationFactor();
        if (milestoneProgress[0] < 7) {
            BigInteger power = parsePositiveBigInteger(totalPowerConsumed);
            BigInteger divided = power.divide(BigInteger.valueOf(POWER_MILESTONE_CONSTANT));
            double progress = divided.signum() <= 0 ? 0.0D
                    : Math.max(Math.log(Math.max(1L, divided.longValue())) / POWER_LOG_CONSTANT + 1.0D, 0.0D) / 7.0D;
            powerMilestonePercentage = clampProgress((float) (progress * accel));
            milestoneProgress[0] = Math.max(milestoneProgress[0], Math.min(7, (int) Math.floor(powerMilestonePercentage * 7.0F)));
        }
        if (milestoneProgress[1] < 7) {
            double progress = totalRecipesProcessed <= 0L ? 0.0D
                    : Math.max(Math.log((double) totalRecipesProcessed / RECIPE_MILESTONE_CONSTANT) / RECIPE_LOG_CONSTANT + 1.0D, 0.0D) / 7.0D;
            recipeMilestonePercentage = clampProgress((float) (progress * accel));
            milestoneProgress[1] = Math.max(milestoneProgress[1], Math.min(7, (int) Math.floor(recipeMilestonePercentage * 7.0F)));
        }
        if (milestoneProgress[2] < 7) {
            long fuelPoints = getTotalFuelMilestonePoints();
            double progress = fuelPoints <= 0L ? 0.0D
                    : Math.max(Math.log((double) fuelPoints / FUEL_MILESTONE_CONSTANT) / FUEL_LOG_CONSTANT + 1.0D, 0.0D) / 7.0D;
            fuelMilestonePercentage = clampProgress((float) (progress * accel));
            milestoneProgress[2] = Math.max(milestoneProgress[2], Math.min(7, (int) Math.floor(fuelMilestonePercentage * 7.0F)));
        }
        recalculateGravitonShardsAvailable();
    }

    public void depositGravitonShards(int count) {
        if (count <= 0) {
            return;
        }
        gravitonShardsDeposited = saturatingAddInt(gravitonShardsDeposited, count);
        recalculateGravitonShardsAvailable();
        markDirty();
    }

    public int getDepositedGravitonShards() {
        return Math.max(0, gravitonShardsDeposited);
    }


    private void recalculateGravitonShardsAvailable() {
        gravitonShardsSpent = Math.max(0, gravitonShardsSpent);
        gravitonShardsDeposited = Math.max(0, gravitonShardsDeposited);
        gravitonShardsEjected = Math.max(0, gravitonShardsEjected);
        gravitonShardsAvailable = calculateAvailableGravitonShards(
                getTotalGravitonShardsEarned(), gravitonShardsSpent, gravitonShardsDeposited, gravitonShardsEjected);
    }

    private static int calculateAvailableGravitonShards(int earned, int spent, int deposited, int ejected) {
        int base = Math.max(0, earned);
        base = saturatingAddInt(base, Math.max(0, deposited));
        base = Math.max(0, base - Math.max(0, spent));
        return Math.max(0, base - Math.max(0, ejected));
    }

    private static EternalGregTechWorkshopUpgrade nextUpgrade(EternalGregTechWorkshopUpgrade upgrade) {
        int index = upgrade == null ? 0 : upgrade.ordinal() + 1;
        return EternalGregTechWorkshopUpgrade.VALUES[index % EternalGregTechWorkshopUpgrade.VALUES.length];
    }

    private static EternalGregTechWorkshopUpgrade previousUpgrade(EternalGregTechWorkshopUpgrade upgrade) {
        int index = upgrade == null ? 0 : upgrade.ordinal() - 1;
        if (index < 0) {
            index = EternalGregTechWorkshopUpgrade.VALUES.length - 1;
        }
        return EternalGregTechWorkshopUpgrade.VALUES[index];
    }

    private String getPrerequisiteText(EternalGregTechWorkshopUpgrade upgrade) {
        EternalGregTechWorkshopUpgrade[] prerequisites = upgrade.prerequisites();
        if (prerequisites.length == 0) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < prerequisites.length; i++) {
            if (i > 0) {
                builder.append(upgrade.requiresAllPrerequisites() ? "+" : "/");
            }
            builder.append(prerequisites[i].shortName());
        }
        return builder.toString();
    }


    // ===== 升级效果计算 =====

    /**
     * 根据已激活升级计算并行乘数。
     * SEFCP ×2, TPTP ×4, IMKG ×8, DOR ×16, SEDS ×32, EE ×64
     */
    public int getUpgradeParallelMultiplier() {
        int mult = 1;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.SEFCP)) mult *= 2;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.TPTP)) mult *= 4;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.IMKG)) mult *= 8;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.DOR)) mult *= 16;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.SEDS)) mult *= 32;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.EE)) mult *= 64;
        return Math.max(1, mult);
    }

    /**
     * 根据已激活升级计算时长系数附加乘数。
     * TCT ×0.95, POS ×0.90, TBF ×0.85, NGMS ×0.97
     */
    public double getUpgradeDurationMultiplier() {
        double mult = 1.0D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.TCT)) mult *= 0.95D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.POS)) mult *= 0.90D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.TBF)) mult *= 0.85D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.NGMS)) mult *= 0.97D;
        return Math.max(0.01D, mult);
    }

    /**
     * 根据已激活升级计算 EU 折扣附加乘数。
     * EPEC ×0.97, CD ×0.95, NGMS ×0.98
     */
    public double getUpgradeEutDiscountMultiplier() {
        double mult = 1.0D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.EPEC)) mult *= 0.97D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.CD)) mult *= 0.95D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.NGMS)) mult *= 0.98D;
        return Math.max(0.01D, mult);
    }

    /**
     * 根据已激活升级计算最大 EU/t 乘数。
     * GGEBE ×1.5, DOP ×2, NDPE ×3, PA ×4, NGMS ×1.25
     */
    public double getUpgradeMaxUseEUtMultiplier() {
        double mult = 1.0D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.GGEBE)) mult *= 1.5D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.DOP)) mult *= 2.0D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.NDPE)) mult *= 3.0D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.PA)) mult *= 4.0D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.NGMS)) mult *= 1.25D;
        return Math.max(1.0D, mult);
    }

    /**
     * 里程碑加速系数。
     * GPCI +10%, CNTI +25%, SEDS +50%
     */
    public double getMilestoneAccelerationFactor() {
        double factor = 1.0D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.GPCI)) factor += 0.10D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.CNTI)) factor += 0.25D;
        if (isUpgradeActive(EternalGregTechWorkshopUpgrade.SEDS)) factor += 0.50D;
        return Math.max(1.0D, factor);
    }
    private static boolean isEffectiveModuleState(EternalGregTechWorkshopModuleState state) {
        return state != null && state.isConnected() && state.isFormed();
    }

    private boolean isRecentModuleState(EternalGregTechWorkshopModuleState state, long now) {
        return state != null
                && state.getLastSeenGameTime() > 0L
                && now >= state.getLastSeenGameTime()
                && now - state.getLastSeenGameTime() <= MODULE_STATE_STALE_TICKS;
    }

    private long getWorkshopGameTime() {
        return getLevel() == null ? 0L : getLevel().getGameTime();
    }

    private static int clampFuelType(int value) {
        return Math.max(0, Math.min(FUEL_FLUIDS.length - 1, value));
    }

    private static long getRecipeParallelCount(GTRecipe recipe) {
        if (recipe instanceof IGTRecipe gtlRecipe) {
            return Math.max(1L, gtlRecipe.getRealParallels());
        }
        return Math.max(1L, recipe.parallels);
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return Math.max(0L, left);
        }
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, left + right);
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static int saturatingAddInt(int left, int right) {
        long value = (long) left + Math.max(0, right);
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int getClickStep(boolean ctrl, boolean shift) {
        if (ctrl && shift) return 1_000_000;
        if (ctrl) return 1_000;
        if (shift) return 100;
        return 1;
    }

    private static String addDecimalString(String current, BigInteger delta) {
        BigInteger base;
        try {
            base = new BigInteger(current == null || current.isBlank() ? "0" : current);
        } catch (NumberFormatException ignored) {
            base = BigInteger.ZERO;
        }
        return base.add(delta.max(BigInteger.ZERO)).toString();
    }

    private static BigInteger parsePositiveBigInteger(String value) {
        try {
            BigInteger parsed = new BigInteger(value == null || value.isBlank() ? "0" : value);
            return parsed.max(BigInteger.ZERO);
        } catch (NumberFormatException ignored) {
            return BigInteger.ZERO;
        }
    }

    private static float clampProgress(float value) {
        if (Float.isNaN(value) || value <= 0.0F) {
            return 0.0F;
        }
        return Math.min(1.0F, value);
    }

    private static String formatMultiplier(double value) {
        return String.format(Locale.ROOT, "%.4fx", value);
    }

    private static String formatParallel(int parallel) {
        return parallel >= Integer.MAX_VALUE ? "∞" : String.format(Locale.ROOT, "%,d", parallel);
    }

    private static String formatLong(long value) {
        return value >= Long.MAX_VALUE ? "∞" : String.format(Locale.ROOT, "%,d", value);
    }
}













