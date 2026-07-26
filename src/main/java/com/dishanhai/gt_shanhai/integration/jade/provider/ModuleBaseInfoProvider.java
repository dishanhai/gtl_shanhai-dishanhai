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

            // 产出倍率（主机聚合万物增殖核心，1 倍时不写，客户端少一行噪音）
            int outputMultiplier = mod.getHostOutputMultiplier();
            if (outputMultiplier > 1) tag.putInt("outputMultiplier", outputMultiplier);

            // 无线电网余额：服务端读 gtmthings 账本，格式化后再下发，避免把巨大 BigInteger 塞进网络包
            java.math.BigInteger gridEu = mod.getWirelessGridEnergy();
            if (gridEu != null) tag.putString("gridEu", formatBigSafe(gridEu));

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

        // 产出倍率（万物增殖核心）
        int outputMultiplier = data.getInt("outputMultiplier");
        if (outputMultiplier > 1) {
            tooltip.add(helper.text(Component.literal("§6产出倍率: §f×" + outputMultiplier)));
        }

        // 电网电力详情
        if (data.contains("gridEu")) {
            tooltip.add(helper.text(Component.literal("§3电网能源总量: §b" + data.getString("gridEu") + " EU")));
        }

        // 条件错误
        if (data.contains("condErr")) {
            tooltip.add(helper.text(Component.literal(data.getString("condErr"))));
        }
    }

    /**
     * 安全格式化电网余额。绝不对巨大 BigInteger 直接调 toString()：零点能发生器灌满后十进制
     * 位数可达千万级，toString() 是 O(n log n) 起步的高开销转换，Jade 每次刷新都调一次会卡 tick。
     * 这里用廉价的 bitLength() + 高 64 位取尾数换算科学计数法。
     */
    private static String formatBigSafe(java.math.BigInteger value) {
        if (value == null || value.signum() <= 0) return "0";
        int bits = value.bitLength();
        if (bits <= 62) return formatNum(value.longValue());
        double mantissa = value.shiftRight(bits - 64).doubleValue();
        double log10 = Math.log10(mantissa) + (bits - 64) * 0.30102999566398120;
        int exp = (int) Math.floor(log10);
        double lead = Math.pow(10.0, log10 - exp);
        if (lead >= 10.0) { lead /= 10.0; exp++; }
        return String.format("%.2fE%d", lead, exp);
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
