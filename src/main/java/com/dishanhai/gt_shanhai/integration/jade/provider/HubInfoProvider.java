package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * 终焉聚合枢纽 Jade 信息增强 — 展示模块/并行/线程/绕过开关/电压等全部状态。
 */
public enum HubInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "hub_info");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    // === 服务端：打包数据 ===
    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            if (!(mbe.getMetaMachine() instanceof DShanhaiMaintenanceHatchMachine hub)) return;

            var tag = new CompoundTag();
            // 模块
            ItemStack mod = hub.getModuleSlot().getStackInSlot(0);
            if (!mod.isEmpty()) tag.putString("module", mod.getHoverName().getString());

            // 耗时倍率
            tag.putFloat("durMul", hub.getDurationMultiplier());
            // 并行
            tag.putLong("parallel", hub.getRawParallel());
            // 线程
            tag.putLong("thread", hub.getRawThreadCount());
            tag.putBoolean("threadOn", hub.getThreadCount() > 0);

            // 绕过状态
            tag.putBoolean("volt", hub.isVoltageBypassEnabled());
            tag.putBoolean("cond", hub.isConditionBypassEnabled());
            tag.putBoolean("temp", hub.isTemperatureBypassEnabled());
            tag.putBoolean("research", hub.isCreative());
            tag.putBoolean("chance", hub.isChanceBypassUnlocked());

            // 电压等级
            tag.putInt("energyTier", hub.getEnergyTier());
            // 产出倍率
            tag.putBoolean("outUnlocked", hub.isOutputUnlocked());
            if (hub.isOutputUnlocked()) tag.putFloat("outMul", hub.getOutputMultiplier());

            // 线程增强物
            var boost = hub.getThreadBoostSlot().getStackInSlot(0);
            if (!boost.isEmpty() && !"dishanhai:universal_parallel_overdriver".equals(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(boost.getItem()).toString())) {
                tag.putString("boost", boost.getHoverName().getString());
                tag.putInt("boostCt", boost.getCount());
            }
            boolean hasOverdriver = hub.hasParallelOverdriver();
            tag.putBoolean("overdriver", hasOverdriver);

            data.put("hub", tag);
        } catch (Exception ignored) {}
    }

    // === 客户端：渲染 ===
    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
        if (!(mbe.getMetaMachine() instanceof DShanhaiMaintenanceHatchMachine)) return;

        var data = accessor.getServerData().getCompound("hub");
        if (data == null || data.isEmpty()) return;
        var helper = IElementHelper.get();

        if (data.contains("module")) {
            tooltip.add(helper.text(Component.literal("§d◇ " + data.getString("module"))));
        }

        float dur = data.getFloat("durMul");
        tooltip.add(helper.text(Component.literal(String.format("§6耗时倍率: §f%.2fx §7[%.2f~%.2f]",
                dur, 0.01f, 10000.0f))));

        long par = data.getLong("parallel");
        tooltip.add(helper.text(Component.literal("§e并行: §f" + (data.getBoolean("overdriver") ? "§d∞ MAX" : formatNum(par)))));

        long thr = data.getLong("thread");
        boolean thrOn = data.getBoolean("threadOn");
        tooltip.add(helper.text(Component.literal("§5跨配方线程: " + (thrOn ? ("§f" + (data.getBoolean("overdriver") ? "§d∞ MAX" : formatNum(thr))) : "§8未启用"))));

        // 绕过状态一行
        var bypassLine = new StringBuilder("§c绕过: ");
        bypassLine.append(data.getBoolean("volt") ? "§a电" : "§7电");
        bypassLine.append(data.getBoolean("temp") ? "§a温" : "§7温");
        bypassLine.append(data.getBoolean("cond") ? "§a环" : "§7环");
        bypassLine.append(data.getBoolean("research") ? "§a研" : "§7研");
        if (data.getBoolean("chance")) bypassLine.append("§a率");
        tooltip.add(helper.text(Component.literal(bypassLine.toString())));

        // 电压等级
        int tier = data.getInt("energyTier");
        String tierName = tier == 15 ? "MAX+16" : tier == 14 ? "MAX" :
                com.gregtechceu.gtceu.api.GTValues.VN[tier >= 0 && tier < com.gregtechceu.gtceu.api.GTValues.VN.length ? tier : 14];
        tooltip.add(helper.text(Component.literal("§e电压: §f" + tierName)));

        // 产出倍率
        if (data.getBoolean("outUnlocked")) {
            tooltip.add(helper.text(Component.literal("§d产出倍率: §f×" + String.format("%.1f", data.getFloat("outMul")))));
        }

        // 线程增强物
        if (data.contains("boost")) {
            tooltip.add(helper.text(Component.literal("§a线程增强: §f" + data.getString("boost") + "×" + data.getInt("boostCt"))));
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
