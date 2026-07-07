package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
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
 * 原初引擎主机 Jade 信息。
 * 并行和跨配方线程由 GTCEu/GTLAdd Jade 注入负责，这里只补主机自身状态。
 */
public enum PrimordialOmegaEngineInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "primordial_omega_engine_info");

    @Override
    public ResourceLocation getUid() { return UID; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
            if (!(mbe.getMetaMachine() instanceof PrimordialOmegaEngineMachine engine)) return;

            var tag = new CompoundTag();
            tag.putBoolean("formed", engine.isFormed());
            tag.putBoolean("working", engine.getRecipeLogic().isWorking());
            tag.putBoolean("hasModules", engine.hasModules);
            tag.putInt("moduleCount", engine.getModuleSet().size());
            tag.putBoolean("overdriver", engine.hasOverdriverInstalled());
            data.put("primordialOmegaEngine", tag);
        } catch (Exception ignored) {}
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return;
        if (!(mbe.getMetaMachine() instanceof PrimordialOmegaEngineMachine)) return;

        var data = accessor.getServerData().getCompound("primordialOmegaEngine");
        if (data == null || data.isEmpty()) return;

        var helper = IElementHelper.get();
        tooltip.add(helper.text(Component.literal("§6§l原初引擎")));

        if (!data.getBoolean("formed")) {
            tooltip.add(helper.text(Component.literal("§c◆ 结构未成型")));
            return;
        }

        int moduleCount = data.getInt("moduleCount");
        if (data.getBoolean("hasModules") && moduleCount > 0) {
            tooltip.add(helper.text(Component.literal("§b◆ 已连接模块: §f" + moduleCount + " §7个")));
        } else {
            tooltip.add(helper.text(Component.literal("§8◆ 未连接模块")));
        }

        tooltip.add(helper.text(Component.literal(data.getBoolean("working") ? "§a◆ 状态: 运行中" : "§7◆ 状态: 待机")));

        if (data.getBoolean("overdriver")) {
            tooltip.add(helper.text(Component.literal("§d◆ 超限模式 · 已激活")));
        }
    }
}
