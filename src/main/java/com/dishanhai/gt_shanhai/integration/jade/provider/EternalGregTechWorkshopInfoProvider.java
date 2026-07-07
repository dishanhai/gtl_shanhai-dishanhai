package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.misc.EternalGregTechWorkshopMachine;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopModuleMachine;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopModuleState;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.EternalGregTechWorkshopModuleType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

import java.util.Locale;

/** Jade information for Eternal GregTech Workshop host and modules. */
public enum EternalGregTechWorkshopInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "eternal_gregtech_workshop_info");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;

            if (mbe.getMetaMachine() instanceof EternalGregTechWorkshopMachine workshop) {
                CompoundTag tag = new CompoundTag();
                tag.putString("kind", "host");
                tag.putBoolean("formed", workshop.isFormed());
                tag.putBoolean("working", workshop.isWorkshopWorking());
                tag.putInt("machineTier", workshop.getMachineTier());
                tag.putInt("moduleTier", workshop.getModuleTier());
                tag.putLong("runTime", workshop.getTotalRunTime());
                tag.putString("powerConsumed", workshop.getTotalPowerConsumed());
                tag.putLong("fuelConsumed", workshop.getTotalFuelConsumed());
                tag.putLong("fuelMilestonePoints", workshop.getTotalFuelMilestonePoints());
                tag.putLong("recipesProcessed", workshop.getTotalRecipesProcessed());
                tag.putLong("lastFuelConsumption", workshop.getFuelConsumption());
                tag.putLong("currentFuelConsumption", workshop.getCurrentFuelConsumption());
                tag.putLong("fuelMilestoneWeight", workshop.getCurrentFuelMilestoneWeight());
                tag.putInt("fuelType", workshop.getSelectedFuelType());
                tag.putString("fuelName", workshop.getFuelName());
                tag.putInt("fuelFactor", workshop.getFuelConsumptionFactor());
                tag.putBoolean("renderEnabled", workshop.isRenderEnabled());
                tag.putBoolean("renderActive", workshop.isRenderActive());
                tag.putInt("connectedModules", workshop.getConnectedModuleCount());
                tag.putInt("maxConnectedModules", workshop.getMaxConnectedModules());
                tag.putInt("gravitonShardsAvailable", workshop.getGravitonShardsAvailable());
                tag.putInt("gravitonShardsSpent", workshop.getGravitonShardsSpent());
                tag.putInt("gravitonShardsDeposited", workshop.getDepositedGravitonShards());
                tag.putInt("gravitonShardsEjected", workshop.getEjectedGravitonShards());
                tag.putInt("gravitonShardsEarned", workshop.getTotalGravitonShardsEarned());
                tag.putString("currentUpgrade", workshop.getCurrentUpgrade().shortName());
                tag.putString("upgradeDesc", workshop.getCurrentUpgrade().getShortDesc());
                tag.putString("upgradeEffect", workshop.getCurrentUpgrade().getEffectDesc());
                tag.putBoolean("currentUpgradeActive", workshop.isUpgradeActive(workshop.getCurrentUpgrade()));
                tag.putInt("activeUpgrades", workshop.getTotalActiveUpgrades());
                tag.putInt("powerMilestone", workshop.getMilestoneProgress(0));
                tag.putInt("recipeMilestone", workshop.getMilestoneProgress(1));
                tag.putInt("fuelMilestone", workshop.getMilestoneProgress(2));
                tag.putInt("structureMilestone", workshop.getMilestoneProgress(3));
                tag.putDouble("effectiveEutDiscount", workshop.getEffectiveModuleEUtDiscount());
                tag.putDouble("effectiveDurationModifier", workshop.getEffectiveModuleDurationModifier());
                tag.putInt("effectiveParallel", workshop.getEffectiveModuleMaxParallel());
                tag.putLong("effectiveMaxUseEUt", workshop.getEffectiveModuleMaxUseEUt());
                tag.putInt("effectiveHeat", workshop.getEffectiveModuleHeat());

                CompoundTag modules = new CompoundTag();
                for (EternalGregTechWorkshopModuleType type : EternalGregTechWorkshopModuleType.values()) {
                    EternalGregTechWorkshopModuleState state = workshop.getModuleState(type);
                    if (state == null) continue;
                    CompoundTag stateTag = new CompoundTag();
                    stateTag.putBoolean("connected", state.isConnected());
                    stateTag.putBoolean("formed", state.isFormed());
                    stateTag.putInt("level", state.getLevel());
                    stateTag.putDouble("eutDiscount", state.getEUtDiscount());
                    stateTag.putDouble("durationModifier", state.getDurationModifier());
                    stateTag.putInt("parallel", state.getMaxParallel());
                    stateTag.putInt("heat", state.getHeat());
                    stateTag.putBoolean("working", state.isWorking());
                    stateTag.putLong("lastSeenGameTime", state.getLastSeenGameTime());
                    modules.put(type.id(), stateTag);
                }
                tag.put("modules", modules);
                data.put("eternalWorkshop", tag);
                return;
            }

            if (mbe.getMetaMachine() instanceof EternalGregTechWorkshopModuleMachine module) {
                CompoundTag tag = new CompoundTag();
                tag.putString("kind", "module");
                tag.putBoolean("formed", module.isFormed());
                tag.putBoolean("working", module.isWorkshopModuleWorking());
                tag.putString("moduleType", module.getWorkshopModuleType().id());
                tag.putInt("moduleLevel", module.getWorkshopModuleLevel());
                tag.putBoolean("connected", module.isConnectedToWorkshopHost());
                tag.putInt("activeRecipeType", module.getWorkshopActiveRecipeTypeIndex());
                tag.putInt("recipeTypeCount", module.getWorkshopRecipeTypeCount());
                tag.putString("activeRecipeTypeName", module.getWorkshopActiveRecipeTypeName());
                tag.putString("lastRecipeStatus", module.getLastWorkshopRecipeStatus());
                tag.putString("lastRecipeId", module.getLastWorkshopRecipeId());
                tag.putString("lastRecipeType", module.getLastWorkshopRecipeType());
                tag.putDouble("eutDiscount", module.getWorkshopEUtDiscount());
                tag.putDouble("durationModifier", module.getWorkshopDurationModifier());
                tag.putInt("parallel", module.getMaxParallel());
                tag.putInt("heat", module.getWorkshopHeat());
                tag.putDouble("effectiveEutDiscount", module.getEffectiveEUtDiscount());
                tag.putDouble("effectiveDurationModifier", module.getEffectiveDurationModifier());
                tag.putInt("effectiveParallel", module.getEffectiveMaxParallel());
                tag.putLong("effectiveMaxUseEUt", module.getEffectiveMaxUseEUt());
                tag.putInt("effectiveHeat", module.getEffectiveHeat());
                BlockPos hostPos = module.getWorkshopHostPosition();
                if (hostPos != null) {
                    tag.putInt("hostX", hostPos.getX());
                    tag.putInt("hostY", hostPos.getY());
                    tag.putInt("hostZ", hostPos.getZ());
                }
                ResourceLocation dimension = module.getWorkshopHostDimension();
                if (dimension != null) {
                    tag.putString("hostDimension", dimension.toString());
                }
                data.put("eternalWorkshop", tag);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity)) return;

        CompoundTag data = accessor.getServerData().getCompound("eternalWorkshop");
        if (data == null || data.isEmpty()) return;

        IElementHelper helper = IElementHelper.get();
        String kind = data.getString("kind");
        if ("host".equals(kind)) {
            appendHostTooltip(tooltip, helper, data);
        } else if ("module".equals(kind)) {
            appendModuleTooltip(tooltip, helper, data);
        }
    }

    private static void appendHostTooltip(ITooltip tooltip, IElementHelper helper, CompoundTag data) {
        tooltip.add(helper.text(Component.literal("§6§l永恒格雷工坊")));
        if (!data.getBoolean("formed")) {
            tooltip.add(helper.text(Component.literal("§c◆ 结构未成型")));
            return;
        }

        tooltip.add(helper.text(Component.literal("§a◆ 结构已成型")));
        tooltip.add(helper.text(Component.literal("§e◆ 等级: §f" + data.getInt("machineTier")
                + " §7/ 模块等级: §f" + data.getInt("moduleTier"))));
        tooltip.add(helper.text(Component.literal(data.getBoolean("working") ? "§a◆ 状态: 运行中" : "§7◆ 状态: 待机")));
        tooltip.add(helper.text(Component.literal("§b◆ 运行时间: §f" + formatRunTimeHours(data.getLong("runTime")))));
        tooltip.add(helper.text(Component.literal("§b◆ 累计配方: §f" + formatLong(data.getLong("recipesProcessed")))));
        tooltip.add(helper.text(Component.literal("§b◆ 累计EU: §f" + data.getString("powerConsumed"))));
        tooltip.add(helper.text(Component.literal("§d◆ 模块连接: §f" + data.getInt("connectedModules")
                + "§7/" + data.getInt("maxConnectedModules"))));
        tooltip.add(helper.text(Component.literal("§5◆ 引力子碎片: §f" + data.getInt("gravitonShardsAvailable")
                + " §7可用 / §f" + data.getInt("gravitonShardsEarned")
                + " §7获得 / §f" + data.getInt("gravitonShardsDeposited")
                + " §7回存 / §f" + data.getInt("gravitonShardsEjected")
                + " §7弹出 / §f" + data.getInt("gravitonShardsSpent") + " §7花费")));
        tooltip.add(helper.text(Component.literal("§5◆ 当前升级: §f" + data.getString("currentUpgrade")
                + " §7(" + data.getString("upgradeDesc") + ")"
                + (data.getBoolean("currentUpgradeActive") ? " §a已激活" : " §8未激活"))));
        tooltip.add(helper.text(Component.literal("§7  效果: §f" + data.getString("upgradeEffect")
                + " §7| 碎片: §b" + data.getInt("activeUpgrades"))));
        tooltip.add(helper.text(Component.literal("§6◆ 里程碑: §c功率 " + data.getInt("powerMilestone")
                + " §d配方 " + data.getInt("recipeMilestone")
                + " §9燃料 " + data.getInt("fuelMilestone")
                + " §a结构 " + data.getInt("structureMilestone"))));
        tooltip.add(helper.text(Component.literal(data.getBoolean("renderActive") ? "§a◆ 渲染: 已启用" : "§8◆ 渲染: 未启用")));
        tooltip.add(helper.text(Component.literal("§6◆ 燃料累计: §f" + data.getLong("fuelConsumed")
                + " §7| 本次 " + data.getLong("lastFuelConsumption")
                + " §7| 当前 " + data.getLong("currentFuelConsumption")
                + " §7| 类型 " + data.getInt("fuelType")
                + " " + data.getString("fuelName")
                + " §7| 因子 " + data.getInt("fuelFactor")
                + " §7| 计数 " + data.getLong("fuelMilestonePoints")
                + " §7| 权重 x" + data.getLong("fuelMilestoneWeight"))));
        tooltip.add(helper.text(Component.literal("§b◆ 实际增益: EU §f" + formatMultiplier(data.getDouble("effectiveEutDiscount"))
                + " §7| 时长 §f" + formatMultiplier(data.getDouble("effectiveDurationModifier")))));
        tooltip.add(helper.text(Component.literal("§d◆ 实际并行: §f" + formatParallel(data.getInt("effectiveParallel"))
                + " §7| 最大EU §f" + formatLong(data.getLong("effectiveMaxUseEUt"))
                + " §7| 热量 §f" + data.getInt("effectiveHeat"))));

        CompoundTag modules = data.getCompound("modules");
        for (EternalGregTechWorkshopModuleType type : EternalGregTechWorkshopModuleType.values()) {
            CompoundTag state = modules.getCompound(type.id());
            if (state == null || state.isEmpty() || !state.getBoolean("connected")) {
                tooltip.add(helper.text(Component.literal("§8◇ " + moduleLabel(type) + ": 未连接")));
            } else {
                tooltip.add(helper.text(Component.literal("§b◇ " + moduleLabel(type)
                        + ": " + (state.getBoolean("working") ? "§a运行中" : "§a已连接")
                        + " §7Lv." + state.getInt("level")
                        + " §7并行 " + state.getInt("parallel"))));
            }
        }
    }

    private static void appendModuleTooltip(ITooltip tooltip, IElementHelper helper, CompoundTag data) {
        tooltip.add(helper.text(Component.literal("§6§l永恒格雷工坊模块")));
        if (!data.getBoolean("formed")) {
            tooltip.add(helper.text(Component.literal("§c◆ 结构未成型")));
            return;
        }

        tooltip.add(helper.text(Component.literal("§a◆ 结构已成型")));
        tooltip.add(helper.text(Component.literal("§e◆ 模块类型: §f" + moduleLabel(data.getString("moduleType")))));
        tooltip.add(helper.text(Component.literal("§e◆ 模块等级: §f" + data.getInt("moduleLevel"))));
        tooltip.add(helper.text(Component.literal(data.getBoolean("connected") ? "§a◆ 已连接主机" : "§c◆ 未连接主机")));
        tooltip.add(helper.text(Component.literal("§e◆ 配方类型: §f" + data.getInt("activeRecipeType")
                + "§7/" + data.getInt("recipeTypeCount")
                + " §f" + data.getString("activeRecipeTypeName"))));
        tooltip.add(helper.text(Component.literal("§6◆ 配方诊断: §f" + data.getString("lastRecipeStatus"))));
        if (!data.getString("lastRecipeId").isEmpty()) {
            tooltip.add(helper.text(Component.literal("§7◆ 最近配方: " + data.getString("lastRecipeType")
                    + " / " + data.getString("lastRecipeId"))));
        }
        if (data.contains("hostX")) {
            tooltip.add(helper.text(Component.literal("§7◆ 主机坐标: "
                    + data.getInt("hostX") + ", " + data.getInt("hostY") + ", " + data.getInt("hostZ"))));
        }
        if (data.contains("hostDimension")) {
            tooltip.add(helper.text(Component.literal("§7◆ 主机维度: " + data.getString("hostDimension"))));
        }
        tooltip.add(helper.text(Component.literal("§b◆ EU折扣: §f" + data.getDouble("eutDiscount")
                + " §7| 时长系数: §f" + data.getDouble("durationModifier"))));
        tooltip.add(helper.text(Component.literal("§d◆ 最大并行: §f" + data.getInt("parallel")
                + " §7| 热量: §f" + data.getInt("heat"))));
        tooltip.add(helper.text(Component.literal("§b◆ 实际增益: EU §f" + formatMultiplier(data.getDouble("effectiveEutDiscount"))
                + " §7| 时长 §f" + formatMultiplier(data.getDouble("effectiveDurationModifier")))));
        tooltip.add(helper.text(Component.literal("§d◆ 实际并行: §f" + formatParallel(data.getInt("effectiveParallel"))
                + " §7| 最大EU §f" + formatLong(data.getLong("effectiveMaxUseEUt"))
                + " §7| 热量 §f" + data.getInt("effectiveHeat"))));
        tooltip.add(helper.text(Component.literal(data.getBoolean("working") ? "§a◆ 状态: 运行中" : "§7◆ 状态: 待机")));
    }

    private static String moduleLabel(EternalGregTechWorkshopModuleType type) {
        return moduleLabel(type.id());
    }

    private static String moduleLabel(String id) {
        if ("fusion".equals(id)) return "聚变模块";
        if ("eye_of_harmony".equals(id)) return "创世之眼模块";
        if ("extra".equals(id)) return "额外模块";
        return id;
    }

    private static String formatRunTimeHours(long ticks) {
        return String.format(Locale.ROOT, "%.2fh", ticks / 72000.0D);
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


