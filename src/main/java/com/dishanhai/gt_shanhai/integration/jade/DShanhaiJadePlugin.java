package com.dishanhai.gt_shanhai.integration.jade;

import com.dishanhai.gt_shanhai.integration.jade.provider.BHCInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.EternalGregTechWorkshopInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.HubInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.InfiniteParallelProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.DShanhaiAENetworkInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.MatterCopierInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.ModuleBaseInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.NineIndustrialInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.PrimordialOmegaEngineInfoProvider;
import com.dishanhai.gt_shanhai.integration.jade.provider.PrimordialVoidInductionArmatureInfoProvider;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public class DShanhaiJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration reg) {
        reg.registerBlockDataProvider(HubInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(new InfiniteParallelProvider(), MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(ModuleBaseInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(PrimordialOmegaEngineInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(PrimordialVoidInductionArmatureInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(NineIndustrialInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(BHCInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(EternalGregTechWorkshopInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(DShanhaiAENetworkInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
        reg.registerBlockDataProvider(MatterCopierInfoProvider.INSTANCE, MetaMachineBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration reg) {
        reg.registerBlockComponent(HubInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(ModuleBaseInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(PrimordialOmegaEngineInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(PrimordialVoidInductionArmatureInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(NineIndustrialInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(BHCInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(EternalGregTechWorkshopInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(DShanhaiAENetworkInfoProvider.INSTANCE, MetaMachineBlock.class);
        reg.registerBlockComponent(MatterCopierInfoProvider.INSTANCE, MetaMachineBlock.class);
    }
}
