package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.PrimordialOmegaVoidInductionArmature;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * 原始真空零点能发生器 Jade 信息。
 */
public enum PrimordialVoidInductionArmatureInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "primordial_void_induction_armature_info");

    @Override
    public ResourceLocation getUid() { return UID; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            if (!(mbe.getMetaMachine() instanceof PrimordialOmegaVoidInductionArmature armature)) return;

            var tag = new CompoundTag();
            tag.putBoolean("formed", armature.isFormed());
            tag.putBoolean("working", armature.getRecipeLogic().isWorking());
            tag.putBoolean("hostConnected", armature.isHostConnected());
            tag.putBoolean("canWorkAlone", armature.canWorkWithoutHost());
            tag.putBoolean("canStart", armature.canStartGeneratingRecipe());
            tag.putBoolean("hasProxies", armature.hasProxies());
            tag.putBoolean("hasGrid", armature.hasTargetWirelessGrid());
            tag.putInt("circuit", armature.getCircuitNumber());
            tag.putInt("parallel", armature.getMaxParallel());
            tag.putString("euPerTick", formatBig(armature.getGeneratedEuPerTick()));
            tag.putString("euPerCycle", formatBig(armature.getGeneratedEuPerCycle()));
            tag.putLong("threadBoost", armature.getThreadBoost());

            String moduleName = armature.getModuleDisplayName();
            if (moduleName != null) {
                tag.putString("moduleName", moduleName);
                tag.putInt("moduleLevel", armature.getModuleLevel());
                tag.putInt("moduleCount", armature.getModuleCount());
            }

            data.put("primordialVoidInductionArmature", tag);
        } catch (Exception ignored) {}
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
        if (!(mbe.getMetaMachine() instanceof PrimordialOmegaVoidInductionArmature)) return;

        var data = accessor.getServerData().getCompound("primordialVoidInductionArmature");
        if (data == null || data.isEmpty()) return;

        var helper = IElementHelper.get();
        tooltip.add(helper.text(Component.literal("§6§l原始真空零点能发生器")));

        if (!data.getBoolean("formed")) {
            tooltip.add(helper.text(Component.literal("§c◆ 结构未成型")));
            return;
        }

        if (data.getBoolean("hostConnected")) {
            tooltip.add(helper.text(Component.literal("§a◆ 已连接主机")));
        } else if (data.getBoolean("canWorkAlone")) {
            tooltip.add(helper.text(Component.literal("§e◆ 独立运行")));
        } else {
            tooltip.add(helper.text(Component.literal("§c◆ 未连接主机")));
        }

        tooltip.add(helper.text(Component.literal(data.getBoolean("hasGrid")
                ? "§a◆ 无线电网: 已绑定"
                : "§c◆ 无线电网: 未绑定")));
        if (!data.getBoolean("hasProxies")) {
            tooltip.add(helper.text(Component.literal("§c◆ 仓室代理: 未就绪")));
        }

        int circuit = data.getInt("circuit");
        if (circuit <= 0) {
            tooltip.add(helper.text(Component.literal("§c◆ 未检测到编程电路")));
        } else {
            tooltip.add(helper.text(Component.literal("§b◆ 编程电路: §f" + circuit)));
            tooltip.add(helper.text(Component.literal("§b◆ 实际并行: §f" + formatIntParallel(data.getInt("parallel")))));
            tooltip.add(helper.text(Component.literal("§d◆ 每tick注入: §f" + data.getString("euPerTick") + " EU")));
            tooltip.add(helper.text(Component.literal("§d◆ 单周期产能: §f" + data.getString("euPerCycle") + " EU")));
        }

        long threadBoost = data.getLong("threadBoost");
        if (threadBoost > 0) {
            tooltip.add(helper.text(Component.literal("§5◆ 跨配方线程: §f" + formatLong(threadBoost))));
        }

        if (data.contains("moduleName")) {
            String count = data.getInt("moduleCount") > 1 ? " §7×" + data.getInt("moduleCount") : "";
            tooltip.add(helper.text(Component.literal("§d◇ 物质模块: " + data.getString("moduleName")
                    + " §7Lv." + data.getInt("moduleLevel") + count)));
        } else {
            tooltip.add(helper.text(Component.literal("§8◇ 物质模块: 未搭载")));
        }

        if (data.getBoolean("working")) {
            tooltip.add(helper.text(Component.literal("§a◆ 零点能提取中")));
        } else if (data.getBoolean("canStart")) {
            tooltip.add(helper.text(Component.literal("§7◆ 待机 · 输入/输出条件未满足")));
        } else {
            tooltip.add(helper.text(Component.literal("§c◆ 无法启动 · 检查主机连接/电路/结构")));
        }
    }

    private static String formatIntParallel(int n) {
        return n >= Integer.MAX_VALUE ? "无限" : formatLong(n);
    }

    private static String formatLong(long n) {
        if (n >= 1_000_000_000_000_000_000L) return String.format("%.1fE", n / 1e18);
        if (n >= 1_000_000_000_000_000L) return String.format("%.1fP", n / 1e15);
        if (n >= 1_000_000_000_000L) return String.format("%.1fT", n / 1e12);
        if (n >= 1_000_000_000L) return String.format("%.1fG", n / 1e9);
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1e6);
        if (n >= 1_000L) return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    private static String formatBig(java.math.BigInteger value) {
        if (value == null || value.signum() <= 0) return "0";
        // 绝不对巨大 BigInteger 调用 toString()：高电路号下十进制位数可达千万级，
        // toString() 是 O(n log n) 起步的高开销转换，Jade 每次悬停刷新都会调一次，直接卡死 tick。
        // bitLength() 是廉价的位扫描，用它估算十进制位数（bitLength × log10(2)）来判断走哪条分支。
        int bitLength = value.bitLength();
        if (bitLength > 63) {
            long digits = (long) (bitLength * 0.3010299956639812) + 1;
            return "1.0E" + (digits - 1);
        }
        return value.toString();
    }
}
