package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;

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
 * 原初引擎模块 Jade 信息 — 显示模块搭载、线程倍率、主机连接、条件错误。
 */
public enum ModuleBaseInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "module_base_info");

    @Override
    public ResourceLocation getUid() { return UID; }

    // === 服务端：打包数据 ===
    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            if (!(mbe.getMetaMachine() instanceof PrimordialOmegaEngineModuleBase mod)) return;
            if (mod instanceof com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.PrimordialOmegaVoidInductionArmature) return;

            var tag = new CompoundTag();

            // 模块信息
            String modName = mod.getModuleDisplayName();
            int modLevel = mod.getModuleLevel();
            if (modName != null) {
                tag.putString("moduleName", modName);
                tag.putInt("moduleLevel", modLevel);
                tag.putInt("moduleCount", mod.getModuleCount());
            }

            // 线程倍率
            long boost = mod.getThreadBoost();
            tag.putLong("threadBoost", boost);

            // 超限器
            tag.putBoolean("overdriver", mod.hasParallelOverdriver());

            // 额外挂载
            tag.putBoolean("darkEnergyMultiplier", mod.hasDarkEnergyMultiplierMounted());
            tag.putBoolean("annihilationCore", mod.hasAnnihilationCoreMounted());
            tag.putBoolean("annihilationStable", mod.isAnnihilationRiskSuppressed());
            tag.putBoolean("hyperstableBlackHoleSeed", mod.hasHyperstableBlackHoleSeedMounted());

            // 主机连接
            tag.putBoolean("hostConnected", mod.isHostConnected());

            // 独立模式
            tag.putBoolean("canWorkAlone", mod.canWorkWithoutHost());

            // 当前条件错误
            String err = mod.getModuleConditionError();
            if (err != null) tag.putString("condErr", err);

            data.put("moduleBase", tag);
        } catch (Exception ignored) {}
    }

    // === 客户端：渲染 ===
    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
        if (!(mbe.getMetaMachine() instanceof PrimordialOmegaEngineModuleBase)) return;
        if (mbe.getMetaMachine() instanceof com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.PrimordialOmegaVoidInductionArmature) return;

        var data = accessor.getServerData().getCompound("moduleBase");
        if (data == null || data.isEmpty()) return;
        var helper = IElementHelper.get();

        // 主机连接状态
        if (data.getBoolean("hostConnected")) {
            tooltip.add(helper.text(Component.literal("§a◆ 已连接主机")));
        } else {
            String status = data.getBoolean("canWorkAlone") ? "§e◆ 独立运行" : "§c◆ 未连接主机";
            tooltip.add(helper.text(Component.literal(status)));
        }

        // 物质模块
        if (data.contains("moduleName")) {
            String name = data.getString("moduleName");
            int level = data.getInt("moduleLevel");
            int count = data.getInt("moduleCount");
            String cntStr = count > 1 ? " §7×" + count : "";
            tooltip.add(helper.text(Component.literal("§d◇ " + name + " §7Lv." + level + cntStr)));
        } else {
            tooltip.add(helper.text(Component.literal("§8◇ 未搭载物质模块")));
        }

        // 线程倍率
        long boost = data.getLong("threadBoost");
        if (boost > 0) {
            tooltip.add(helper.text(Component.literal("§5跨配方线程: §f×" + formatNum(boost))));
        }
        if (data.getBoolean("overdriver")) {
            tooltip.add(helper.text(Component.literal("§d∞ 超限模式已激活")));
        }
        if (data.getBoolean("darkEnergyMultiplier")) {
            tooltip.add(helper.text(Component.literal("§b暗能量倍增器: §fEU消耗 -50%")));
        }
        if (data.getBoolean("annihilationCore")) {
            tooltip.add(helper.text(Component.literal(data.getBoolean("annihilationStable")
                    ? "§c湮灭核心: §f耗时 -90%, 高级模块稳定"
                    : "§c湮灭核心: §f耗时 -90%, 产物湮灭 1%")));
        }
        if (data.getBoolean("hyperstableBlackHoleSeed")) {
            tooltip.add(helper.text(Component.literal("§d超稳态黑洞种子: §f输出堵塞时吞噬溢出产物")));
        }

        // 条件错误
        if (data.contains("condErr")) {
            tooltip.add(helper.text(Component.literal(data.getString("condErr"))));
        }
    }

    private static String formatNum(long n) {
        if (n == Long.MAX_VALUE) return "∞";
        if (n >= 1_000_000_000_000_000_000L) return String.format("%.1fE", n / 1e18);
        if (n >= 1_000_000_000_000_000L) return String.format("%.1fP", n / 1e15);
        if (n >= 1_000_000_000_000L) return String.format("%.1fT", n / 1e12);
        if (n >= 1_000_000_000) return String.format("%.1fG", n / 1e9);
        if (n >= 1_000_000) return String.format("%.1fM", n / 1e6);
        if (n >= 1_000) return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }
}
