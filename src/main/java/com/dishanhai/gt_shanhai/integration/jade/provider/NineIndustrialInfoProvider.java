package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.misc.ShanhaiNineIndustrialMachine;

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
 * 大明科技 Jade 信息 — 仅显示水浒传模式名，配方类型列表由标准 GTCEu Jade 负责
 */
public enum NineIndustrialInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "nine_industrial_info");

    @Override
    public ResourceLocation getUid() { return UID; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            if (!(mbe.getMetaMachine() instanceof ShanhaiNineIndustrialMachine machine)) return;

            var tag = new CompoundTag();
            tag.putInt("mode", machine.getCurrentMode());
            tag.putString("name", machine.getModeName());
            data.put("nine", tag);
        } catch (Exception ignored) {}
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
        if (!(mbe.getMetaMachine() instanceof ShanhaiNineIndustrialMachine machine)) return;

        var data = accessor.getServerData().getCompound("nine");
        if (data == null || data.isEmpty()) return;
        var helper = IElementHelper.get();

        if (machine.isFormed()) {
            int mode = data.getInt("mode");
            String name = data.getString("name");
            // 只显示水浒传大类名，配方列表由标准 GTCEu Jade 展示
            tooltip.add(helper.text(Component.literal("§6§l" + name + " §7[" + mode + "/35]")));
        }
    }
}
