package com.dishanhai.gt_shanhai.common.block;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingBlockEntity;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitBlock;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class DShanhaiAE2Blocks {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);

    private static final QuantumCraftingUnitBlock QUANTUM_COMPUTER_BLOCK = new QuantumCraftingUnitBlock(QuantumCraftingUnitTypes.COMPUTER_UNIT);
    private static final QuantumCraftingUnitBlock QUANTUM_COMPUTER_UNIT_BLOCK = new QuantumCraftingUnitBlock(QuantumCraftingUnitTypes.COMPUTER_UNIT);
    private static final QuantumCraftingUnitBlock QUANTUM_PARALLEL_PROCESSOR_BLOCK = new QuantumCraftingUnitBlock(QuantumCraftingUnitTypes.PARALLEL_PROCESSOR);
    private static final QuantumCraftingUnitBlock QUANTUM_CRAFTING_STORAGE_BLOCK = new QuantumCraftingUnitBlock(QuantumCraftingUnitTypes.CRAFTING_STORAGE);
    private static final QuantumCraftingUnitBlock QUANTUM_STRUCTURE_BLOCK = new QuantumCraftingUnitBlock(QuantumCraftingUnitTypes.STRUCTURE);

    public static final RegistryObject<QuantumCraftingUnitBlock> QUANTUM_COMPUTER = BLOCKS.register("quantum_computer",
        () -> QUANTUM_COMPUTER_BLOCK);
    public static final RegistryObject<QuantumCraftingUnitBlock> QUANTUM_COMPUTER_UNIT = BLOCKS.register("quantum_computer_unit",
        () -> QUANTUM_COMPUTER_UNIT_BLOCK);
    public static final RegistryObject<QuantumCraftingUnitBlock> QUANTUM_PARALLEL_PROCESSOR = BLOCKS.register("quantum_parallel_processor",
        () -> QUANTUM_PARALLEL_PROCESSOR_BLOCK);
    public static final RegistryObject<QuantumCraftingUnitBlock> QUANTUM_CRAFTING_STORAGE = BLOCKS.register("quantum_crafting_storage",
        () -> QUANTUM_CRAFTING_STORAGE_BLOCK);
    public static final RegistryObject<QuantumCraftingUnitBlock> QUANTUM_STRUCTURE = BLOCKS.register("quantum_structure",
        () -> QUANTUM_STRUCTURE_BLOCK);

    public static final RegistryObject<Item> QUANTUM_COMPUTER_ITEM = ITEMS.register("quantum_computer",
        () -> new QuantumCraftingUnitItem(QUANTUM_COMPUTER_BLOCK, new Item.Properties(), "quantum_computer"));
    public static final RegistryObject<Item> QUANTUM_COMPUTER_UNIT_ITEM = ITEMS.register("quantum_computer_unit",
        () -> new QuantumCraftingUnitItem(QUANTUM_COMPUTER_UNIT_BLOCK, new Item.Properties(), "quantum_computer_unit"));
    public static final RegistryObject<Item> QUANTUM_PARALLEL_PROCESSOR_ITEM = ITEMS.register("quantum_parallel_processor",
        () -> new QuantumCraftingUnitItem(QUANTUM_PARALLEL_PROCESSOR_BLOCK, new Item.Properties(), "quantum_parallel_processor"));
    public static final RegistryObject<Item> QUANTUM_CRAFTING_STORAGE_ITEM = ITEMS.register("quantum_crafting_storage",
        () -> new QuantumCraftingUnitItem(QUANTUM_CRAFTING_STORAGE_BLOCK, new Item.Properties(), "quantum_crafting_storage"));
    public static final RegistryObject<Item> QUANTUM_STRUCTURE_ITEM = ITEMS.register("quantum_structure",
        () -> new QuantumCraftingUnitItem(QUANTUM_STRUCTURE_BLOCK, new Item.Properties(), "quantum_structure"));

    public static final RegistryObject<BlockEntityType<QuantumCraftingBlockEntity>> QUANTUM_CRAFTING_UNIT = BLOCK_ENTITIES.register(
        "quantum_crafting_unit",
        () -> createCraftingUnitBlockEntity(
            QUANTUM_COMPUTER_UNIT_BLOCK,
            QUANTUM_COMPUTER_BLOCK,
            QUANTUM_PARALLEL_PROCESSOR_BLOCK,
            QUANTUM_CRAFTING_STORAGE_BLOCK,
            QUANTUM_STRUCTURE_BLOCK
        )
    );

    private static BlockEntityType<QuantumCraftingBlockEntity> createCraftingUnitBlockEntity(QuantumCraftingUnitBlock computerUnit,
            QuantumCraftingUnitBlock computer, QuantumCraftingUnitBlock processor, QuantumCraftingUnitBlock storage, QuantumCraftingUnitBlock structure) {
        BlockEntityType<QuantumCraftingBlockEntity> type = BlockEntityType.Builder.of(
            (BlockPos pos, BlockState state) -> new QuantumCraftingBlockEntity(QUANTUM_CRAFTING_UNIT.get(), pos, state),
            computerUnit,
            computer,
            processor,
            storage,
            structure
        ).build(null);
        computerUnit.setBlockEntity(QuantumCraftingBlockEntity.class, type, null, null);
        computer.setBlockEntity(QuantumCraftingBlockEntity.class, type, null, null);
        processor.setBlockEntity(QuantumCraftingBlockEntity.class, type, null, null);
        storage.setBlockEntity(QuantumCraftingBlockEntity.class, type, null, null);
        structure.setBlockEntity(QuantumCraftingBlockEntity.class, type, null, null);
        return type;
    }

    public static void init(net.minecraftforge.eventbus.api.IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }

    public static final class QuantumCraftingUnitItem extends BlockItem {

        private final String tooltipKey;

        private QuantumCraftingUnitItem(Block block, Properties properties, String tooltipKey) {
            super(block, properties);
            this.tooltipKey = tooltipKey;
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
            super.appendHoverText(stack, level, tooltip, flag);
            tooltip.add(Component.translatable("gt_shanhai.quantum_computer." + tooltipKey + ".tooltip.0"));
            tooltip.add(Component.translatable("gt_shanhai.quantum_computer." + tooltipKey + ".tooltip.1"));
        }
    }

    private DShanhaiAE2Blocks() {}
}
