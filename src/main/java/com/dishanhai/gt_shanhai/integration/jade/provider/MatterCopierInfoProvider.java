package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.misc.MatterCopierMachine;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

public enum MatterCopierInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "matter_copier_info");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        MatterCopierMachine machine = getMachine(accessor);
        if (machine == null) return;

        CompoundTag tag = new CompoundTag();
        tag.putBoolean("formed", machine.isFormed());
        tag.putBoolean("itemMode", machine.isJadeItemMode());
        tag.putLong("copyAmount", machine.getJadeCopyAmount());
        tag.putLong("maxOutputPerTick", machine.getJadeMaxOutputPerTick());
        tag.putString("status", machine.getJadeStatus());
        tag.putString("prototype", machine.getJadePrototypeName());
        tag.putString("wireless", machine.getJadeWirelessStatus());
        tag.putBoolean("wirelessBound", machine.getUuid() != null);
        tag.putBoolean("wirelessOnline", machine.getWirelessNetworkEnergyHandler().isOnline());
        data.put("matterCopier", tag);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (getMachine(accessor) == null) return;
        CompoundTag data = accessor.getServerData().getCompound("matterCopier");
        if (data == null || data.isEmpty()) return;

        IElementHelper helper = IElementHelper.get();
        tooltip.add(helper.text(Component.literal("§b§l物质定增")));
        tooltip.add(helper.text(Component.literal(data.getBoolean("formed") ? "§a◆ 结构已成型" : "§c◆ 结构未成型")));
        tooltip.add(helper.text(Component.literal("§7◆ 模式: §f" + (data.getBoolean("itemMode") ? "物品" : "流体"))));
        tooltip.add(helper.text(Component.literal("§7◆ 原型: §f" + data.getString("prototype"))));
        tooltip.add(helper.text(Component.literal("§7◆ 单次复制: §f" + formatLong(data.getLong("copyAmount")))));
        tooltip.add(helper.text(Component.literal("§7◆ 批次上限: §f" + formatLong(data.getLong("maxOutputPerTick")))));
        tooltip.add(helper.text(Component.literal("§7◆ 无线电网: §f" + data.getString("wireless"))));
        tooltip.add(helper.text(Component.literal("§7◆ 状态: §f" + data.getString("status"))));
    }

    private static MatterCopierMachine getMachine(BlockAccessor accessor) {
        if (accessor == null || accessor.getBlockEntity() == null) return null;
        if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe)) return null;
        if (!(mbe.getMetaMachine() instanceof MatterCopierMachine machine)) return null;
        return machine;
    }

    private static String formatLong(long value) {
        return String.format("%,d", value);
    }
}
