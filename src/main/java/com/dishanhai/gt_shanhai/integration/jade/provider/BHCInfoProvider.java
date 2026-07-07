package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.misc.ShanhaiBlackHoleContainmentMachine;

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
 * 亚稳态黑洞遏制场 Jade 信息 — 稳定度/状态/催化倍率
 */
public enum BHCInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "bhc_info");

    @Override public ResourceLocation getUid() { return UID; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            if (!(mbe.getMetaMachine() instanceof ShanhaiBlackHoleContainmentMachine bh)) return;
            if (!bh.isFormed()) return;

            var tag = new CompoundTag();
            tag.putFloat("stability", bh.getBlackHoleStability());
            tag.putInt("status", bh.getBlackHoleStatus());
            tag.putInt("costMod", bh.getBlackHoleCatalyzingCostModifier());
            tag.putInt("counter", bh.getCatalyzingCounter());
            tag.putBoolean("burst", bh.isCatalyticBurstEnabled());
            tag.putBoolean("shouldRender", bh.isShouldRender());
            data.put("bhc", tag);
        } catch (Exception ignored) {}
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
        if (!(mbe.getMetaMachine() instanceof ShanhaiBlackHoleContainmentMachine bh)) return;
        if (!bh.isFormed()) return;

        var data = accessor.getServerData().getCompound("bhc");
        if (data == null || data.isEmpty()) return;
        var helper = IElementHelper.get();

        float stability = data.getFloat("stability");
        int status = data.getInt("status");
        int costMod = data.getInt("costMod");
        int counter = data.getInt("counter");
        boolean burst = data.getBoolean("burst");

        int barLen = 10;
        int filled = Math.round(Math.max(0, stability) / 100.0f * barLen);
        var bar = new StringBuilder();
        for (int i = 0; i < barLen; i++) bar.append(i < filled ? "§a|" : "§8|");

        tooltip.add(helper.text(Component.literal("§7稳定度: §e" + String.format("%.1f", stability) + "% " + bar)));

        String statusText = switch (status) {
            case 2 -> "§a黑洞活跃 · 时空催化×" + costMod;
            case 3 -> "§c⚠ 失稳 · 倍率×" + costMod;
            case 4 -> burst ? ("§d超稳态黑洞 · 时空催化×" + costMod) : "§d超稳态黑洞 · 不衰减";
            default -> "§8黑洞关闭";
        };
        tooltip.add(helper.text(Component.literal(statusText)));

        // 催化爆冲进度条: counter/30
        String prog = burst ? ("§6§l爆冲 [" + counter + "/30] " + progressBar(counter, 30)) : "§7催化爆冲: §8OFF";
        tooltip.add(helper.text(Component.literal(prog)));
    }

    private static String progressBar(int val, int max) {
        int n = 10, f = Math.round((float) val / max * n);
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i < f ? "§e|" : "§8|");
        return sb.toString();
    }
}
