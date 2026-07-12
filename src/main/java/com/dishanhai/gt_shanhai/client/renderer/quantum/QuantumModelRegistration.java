package com.dishanhai.gt_shanhai.client.renderer.quantum;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 对齐 ExtendedAE ClientRegistryHandler.registerModels 的注册方式：
// ModelEvent.RegisterGeometryLoaders 在 MOD 总线、模型烘焙之前触发，
// 注册后 blockstate/model json 里用 "loader": "gt_shanhai:xxx" 直接引用。
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class QuantumModelRegistration {

    @SubscribeEvent
    public static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register("quantum_computer_formed", new QuantumConnectedModel.Loader(
                () -> new QuantumInternalModelProvider(QuantumCraftingUnitTypes.COMPUTER_UNIT)));
        event.register("quantum_computer_unit_formed", new QuantumConnectedModel.Loader(
                () -> new QuantumInternalModelProvider(QuantumCraftingUnitTypes.COMPUTER_UNIT)));
        event.register("quantum_parallel_processor_formed", new QuantumConnectedModel.Loader(
                () -> new QuantumInternalModelProvider(QuantumCraftingUnitTypes.PARALLEL_PROCESSOR)));
        event.register("quantum_crafting_storage_formed", new QuantumConnectedModel.Loader(
                () -> new QuantumInternalModelProvider(QuantumCraftingUnitTypes.CRAFTING_STORAGE)));
        event.register("quantum_structure_formed", new QuantumConnectedModel.Loader(
                () -> new QuantumStructureModelProvider()));
    }
}
