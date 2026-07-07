package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

/**
 * 绝对量子完美净化单元 — GTNH MTEPurificationUnitBaryonicPerfection 等价移植。
 */
public class AbsoluteQuantumPerfectPurificationUnitMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(AbsoluteQuantumPerfectPurificationUnitMachine.class, getMANAGED_FIELD_HOLDER());

    private static final ResourceLocation[] CATALYST_IDS = {
            new ResourceLocation("dishanhai", "up_quark_emission_catalyst"),
            new ResourceLocation("dishanhai", "down_quark_emission_catalyst"),
            new ResourceLocation("dishanhai", "bottom_quark_emission_catalyst"),
            new ResourceLocation("dishanhai", "top_quark_emission_catalyst"),
            new ResourceLocation("dishanhai", "strange_quark_emission_catalyst"),
            new ResourceLocation("dishanhai", "charm_quark_emission_catalyst")
    };
    private static final ResourceLocation INFINITY_FLUID_ID = new ResourceLocation("gtceu", "infinity");
    private static final ResourceLocation STABLE_QUANTUM_MATTER_ID =
            new ResourceLocation("gtceu", "quantumchromodynamically_confined_matter");
    private static final int CYCLE_TIME = 2400;
    private static final int CATALYST_BASE_COST = 144;
    private static final long BARYONIC_OUTPUT = 2000L;

    @Persisted @DescSynced private int catalystA = -1;
    @Persisted @DescSynced private int catalystB = -1;
    @Persisted @DescSynced private int correctStartIndex = -1;
    @Persisted @DescSynced private int cycleProgress;
    @Persisted @DescSynced private int insertedCount;
    @Persisted @DescSynced private int lastCost;
    @Persisted @DescSynced private boolean decoded;
    private int[] insertedCatalysts = new int[0];

    private TickableSubscription tickSub;

    public AbsoluteQuantumPerfectPurificationUnitMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public long getMaxVoltage() {
        return GTValues.V[GTValues.UEV];
    }

    @Override
    public int getMaxParallel() {
        return 1;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        if (getLevel() != null && !getLevel().isClientSide) {
            ensureCycle();
            updateTickSubscription();
        }
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (tickSub != null) {
            tickSub.unsubscribe();
            tickSub = null;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() != null && !getLevel().isClientSide && isFormed()) {
            ensureCycle();
            updateTickSubscription();
        }
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (tickSub != null) {
            tickSub.unsubscribe();
            tickSub = null;
        }
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putIntArray("AQPInsertedCatalysts", insertedCatalysts);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("AQPInsertedCatalysts")) {
            insertedCatalysts = tag.getIntArray("AQPInsertedCatalysts");
            insertedCount = insertedCatalysts.length;
        }
    }

    private void updateTickSubscription() {
        if (tickSub == null) {
            tickSub = subscribeServerTick(tickSub, this::serverTickHandler);
        }
    }

    private void serverTickHandler() {
        if (!isFormed() || getLevel() == null || getLevel().isClientSide) return;

        ensureCycle();
        cycleProgress++;
        if (cycleProgress % 20 == 0) {
            consumeCatalystsFromInputBuses();
        }
        if (cycleProgress >= CYCLE_TIME) {
            finishCycle();
        }
    }

    private void ensureCycle() {
        if (catalystA < 0 || catalystB < 0 || catalystA == catalystB) {
            startNewCycle();
        }
    }

    private void startNewCycle() {
        int first = GTValues.RNG.nextInt(CATALYST_IDS.length);
        int second;
        do {
            second = GTValues.RNG.nextInt(CATALYST_IDS.length);
        } while (second == first);
        catalystA = first;
        catalystB = second;
        correctStartIndex = -1;
        cycleProgress = 0;
        insertedCount = 0;
        lastCost = 0;
        decoded = false;
        insertedCatalysts = new int[0];
        markDirty();
    }

    private void finishCycle() {
        returnWrongCatalysts();
        startNewCycle();
    }

    private void consumeCatalystsFromInputBuses() {
        boolean changed = false;
        for (IMultiPart part : getParts()) {
            if (part instanceof ItemBusPartMachine bus) {
                if (consumeCatalystsFromHandler(bus.getInventory())) {
                    changed = true;
                }
            }
        }
        if (changed) {
            checkSequenceAndOutput();
            markDirty();
        }
    }

    private boolean consumeCatalystsFromHandler(NotifiableItemStackHandler handler) {
        boolean changed = false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            int catalystIndex = getCatalystIndex(stack);
            if (catalystIndex < 0) continue;

            int amount = stack.getCount();
            int cost = calculateCatalystCost(catalystIndex, amount);
            if (!drainInfinity(cost)) {
                lastCost = cost;
                return changed;
            }

            for (int i = 0; i < amount; i++) {
                addInsertedCatalyst(catalystIndex);
            }
            lastCost = cost;
            stack.shrink(amount);
            handler.setStackInSlot(slot, ItemStack.EMPTY);
            handler.onContentsChanged();
            changed = true;
        }
        return changed;
    }

    private int calculateCatalystCost(int catalystIndex, int amount) {
        int total = 0;
        int seen = countInsertedCatalyst(catalystIndex);
        for (int i = 0; i < amount; i++) {
            int multiplier = 1 << Math.min(20, seen + i);
            total += CATALYST_BASE_COST * multiplier;
        }
        return total;
    }

    private int countInsertedCatalyst(int catalystIndex) {
        int count = 0;
        for (int inserted : insertedCatalysts) {
            if (inserted == catalystIndex) count++;
        }
        return count;
    }

    private void addInsertedCatalyst(int catalystIndex) {
        int[] next = new int[insertedCatalysts.length + 1];
        System.arraycopy(insertedCatalysts, 0, next, 0, insertedCatalysts.length);
        next[next.length - 1] = catalystIndex;
        insertedCatalysts = next;
        insertedCount = next.length;
    }

    private void checkSequenceAndOutput() {
        if (decoded) return;
        for (int i = 0; i < insertedCatalysts.length - 1; i++) {
            int first = insertedCatalysts[i];
            int second = insertedCatalysts[i + 1];
            if ((first == catalystA && second == catalystB) || (first == catalystB && second == catalystA)) {
                if (outputStableQuantumMatter(BARYONIC_OUTPUT)) {
                    correctStartIndex = i;
                    decoded = true;
                }
                return;
            }
        }
    }

    private int getCatalystIndex(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return -1;
        for (int i = 0; i < CATALYST_IDS.length; i++) {
            if (CATALYST_IDS[i].equals(id)) return i;
        }
        return -1;
    }

    private Item getCatalystItem(int index) {
        if (index < 0 || index >= CATALYST_IDS.length) return null;
        return ForgeRegistries.ITEMS.getValue(CATALYST_IDS[index]);
    }

    private boolean drainInfinity(long amount) {
        if (amount <= 0L) return true;
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(INFINITY_FLUID_ID);
        if (fluid == null || fluid == Fluids.EMPTY) return false;
        long remaining = amount;
        for (IMultiPart part : getParts()) {
            if (!(part instanceof FluidHatchPartMachine hatch)) continue;
            NotifiableFluidTank tank = hatch.tank;
            if (tank.getHandlerIO() != IO.IN && tank.getHandlerIO() != IO.BOTH) continue;
            remaining = drainFluidFromTank(tank, fluid, remaining);
            if (remaining <= 0L) return true;
        }
        return false;
    }

    private long drainFluidFromTank(NotifiableFluidTank tank, Fluid fluid, long amount) {
        long remaining = amount;
        for (int i = 0; i < tank.getTanks(); i++) {
            FluidStack stack = tank.getFluidInTank(i);
            if (stack == null || stack.isEmpty() || stack.getFluid() != fluid) continue;
            long drained = Math.min(remaining, stack.getAmount());
            long left = stack.getAmount() - drained;
            if (left <= 0L) {
                tank.setFluidInTank(i, FluidStack.empty());
            } else {
                tank.setFluidInTank(i, FluidStack.create(fluid, left));
            }
            remaining -= drained;
            if (remaining <= 0L) return 0L;
        }
        return remaining;
    }

    private boolean outputStableQuantumMatter(long amount) {
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(STABLE_QUANTUM_MATTER_ID);
        if (fluid == null || fluid == Fluids.EMPTY) return false;
        long remaining = amount;
        for (IMultiPart part : getParts()) {
            if (!(part instanceof FluidHatchPartMachine hatch)) continue;
            NotifiableFluidTank tank = hatch.tank;
            if (tank.getHandlerIO() != IO.OUT && tank.getHandlerIO() != IO.BOTH) continue;
            remaining = outputFluidToTank(tank, fluid, remaining);
            if (remaining <= 0L) return true;
        }
        return false;
    }

    private long outputFluidToTank(NotifiableFluidTank tank, Fluid fluid, long amount) {
        long remaining = amount;
        for (int i = 0; i < tank.getTanks(); i++) {
            FluidStack stack = tank.getFluidInTank(i);
            if (stack == null || stack.isEmpty()) {
                tank.setFluidInTank(i, FluidStack.create(fluid, remaining));
                return 0L;
            }
            if (stack.getFluid() != fluid) continue;
            long capacity = tank.getTankCapacity(i);
            long canFill = Math.max(0L, capacity - stack.getAmount());
            if (canFill <= 0L) continue;
            long filled = Math.min(remaining, canFill);
            tank.setFluidInTank(i, FluidStack.create(fluid, stack.getAmount() + filled));
            remaining -= filled;
            if (remaining <= 0L) return 0L;
        }
        return remaining;
    }

    private void returnWrongCatalysts() {
        if (insertedCatalysts.length == 0) return;
        List<ItemStack> returns = new ArrayList<>();
        for (int i = 0; i < insertedCatalysts.length; i++) {
            if (correctStartIndex >= 0 && (i == correctStartIndex || i == correctStartIndex + 1)) continue;
            Item item = getCatalystItem(insertedCatalysts[i]);
            if (item != null) returns.add(new ItemStack(item));
        }
        for (ItemStack stack : returns) {
            outputItem(stack);
        }
    }

    private boolean outputItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        ItemStack remaining = stack.copy();
        for (IMultiPart part : getParts()) {
            if (!(part instanceof ItemBusPartMachine bus)) continue;
            NotifiableItemStackHandler handler = bus.getInventory();
            if (handler.getHandlerIO() != IO.OUT && handler.getHandlerIO() != IO.BOTH) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                remaining = handler.insertItem(slot, remaining, false);
                if (remaining.isEmpty()) {
                    handler.onContentsChanged();
                    return true;
                }
            }
            handler.onContentsChanged();
        }
        return false;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
            return;
        }
        textList.add(Component.literal("净水等级: 8").withStyle(ChatFormatting.AQUA));
        textList.add(Component.literal("循环进度: " + cycleProgress + " / " + CYCLE_TIME).withStyle(ChatFormatting.GRAY));
        textList.add(Component.literal("本轮目标组合: ")
                .append(catalystName(catalystA))
                .append(Component.literal(" + "))
                .append(catalystName(catalystB)));
        textList.add(Component.literal("已输入催化剂: " + insertedCount).withStyle(ChatFormatting.YELLOW));
        textList.add(Component.literal("夸克组合已解码: " + (decoded ? "是" : "否"))
                .withStyle(decoded ? ChatFormatting.GREEN : ChatFormatting.RED));
        if (lastCost > 0) {
            textList.add(Component.literal("最近一次消耗熔融无尽: " + lastCost + " L").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    private Component catalystName(int index) {
        Item item = getCatalystItem(index);
        if (item == null) return Component.literal("未知").withStyle(ChatFormatting.DARK_GRAY);
        return item.getDefaultInstance().getHoverName().copy().withStyle(getCatalystColor(index));
    }

    private ChatFormatting getCatalystColor(int index) {
        return switch (index) {
            case 0 -> ChatFormatting.BLUE;
            case 1 -> ChatFormatting.LIGHT_PURPLE;
            case 2 -> ChatFormatting.AQUA;
            case 3 -> ChatFormatting.RED;
            case 4 -> ChatFormatting.YELLOW;
            case 5 -> ChatFormatting.GREEN;
            default -> ChatFormatting.GRAY;
        };
    }

    public static MultiblockMachineDefinition register() {
        MultiblockMachineDefinition definition = GTDishanhaiRegistration.REGISTRATE
                .multiblock("absolute_quantum_perfect_purification_unit",
                        AbsoluteQuantumPerfectPurificationUnitMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(GTRecipeTypes.DUMMY_RECIPES)
                .pattern(AbsoluteQuantumPerfectPurificationUnitMachine::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtlcore", "dimensionally_transcendent_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/solid/machine_casing_stable_titanium"),
                        new ResourceLocation(MOD_ID, "block/multiblock/zero_photon_condenser"))
                .register();

        definition.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("绝对量子完美净化单元").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            tooltips.add(Component.literal("GTNH 绝对重子完美净化单元核心玩法移植").withStyle(ChatFormatting.GRAY));
            tooltips.add(Component.literal("净水等级: 8").withStyle(ChatFormatting.AQUA));
            tooltips.add(Component.literal("每 20 tick 消耗输入总线内全部夸克释放催化剂").withStyle(ChatFormatting.GRAY));
            tooltips.add(Component.literal("每个催化剂基础消耗 144L 熔融无尽，重复输入同类催化剂会翻倍").withStyle(ChatFormatting.LIGHT_PURPLE));
            tooltips.add(Component.literal("每轮随机两种催化剂，顺序不限；命中后立即输出 2000L 稳定量子物质").withStyle(ChatFormatting.GREEN));
            tooltips.add(Component.literal("循环结束时，非正确组合的催化剂会返还到输出总线").withStyle(ChatFormatting.GRAY));
            tooltips.add(Component.literal("需要: 输入总线、输出总线、输入仓、输出仓").withStyle(ChatFormatting.YELLOW));
        });
        return definition;
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block casing = block("gtlcore:dimensionally_transcendent_casing", "gtceu:stable_machine_casing");
        Block baryonicCore = block("gtceu:quantumchromodynamically_confined_matter_block", "gtceu:high_power_casing");
        Block quarkCore = block("gtceu:heavy_quark_degenerate_matter_block", "gtceu:high_power_casing");
        Block glass = block("gtlcore:infinity_glass", "gtceu:fusion_glass");
        Block frame = block("gtceu:infinity_frame", "gtceu:naquadah_alloy_frame");

        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK)
                .aisle("                 ", "                 ", "                 ", "                 ", "                 ", "                 ", "      AAAAA      ", "      AAAAA      ", "      AA~AA      ", "      AAAAA      ", "      AAAAA      ", "                 ", "                 ", "                 ", "                 ", "                 ", "                 ")
                .aisle("                 ", "        E        ", "        E        ", "        E        ", "        E        ", "        E        ", "      AAAAA      ", "      AAAAA      ", " EEEEEAAAAAEEEEE ", "      AAAAA      ", "      AAAAA      ", "        E        ", "        E        ", "        E        ", "        E        ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "      CCCCC      ", "      CDDDC      ", " E    CDBDC    E ", "      CDDDC      ", "      CCCCC      ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "                 ", "       DDD       ", " E     DBD     E ", "       DDD       ", "                 ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "                 ", "       DDD       ", " E     DBD     E ", "       DDD       ", "                 ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "                 ", "       DDD       ", " E     DBD     E ", "       DDD       ", "                 ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("      AAAAA      ", "      AAAAA      ", "      CCCCC      ", "                 ", "                 ", "                 ", "AAC   AAAAA   CAA", "AAC   ADDDA   CAA", "AAC   ADBDA   CAA", "AAC   ADDDA   CAA", "AAC   AAAAA   CAA", "                 ", "                 ", "                 ", "      CCCCC      ", "      AAAAA      ", "      AAAAA      ")
                .aisle("      AAAAA      ", "      AAAAA      ", "      CDDDC      ", "       DDD       ", "       DDD       ", "       DDD       ", "AAC   ADDDA   CAA", "AADDDDD   DDDDDAA", "AADDDDD B DDDDDAA", "AADDDDD   DDDDDAA", "AAC   ADDDA   CAA", "       DDD       ", "       DDD       ", "       DDD       ", "      CDDDC      ", "      AAAAA      ", "      AAAAA      ")
                .aisle("      AAAAA      ", " EEEEEAAAAAEEEEE ", " E    CDBDC    E ", " E     DBD     E ", " E     DBD     E ", " E     DBD     E ", "AAC   ADBDA   CAA", "AADDDDD B DDDDDAA", "AABBBBBBBBBBBBBAA", "AADDDDD B DDDDDAA", "AAC   ADBDA   CAA", " E     DBD     E ", " E     DBD     E ", " E     DBD     E ", " E    CDBDC    E ", " EEEEEAAAAAEEEEE ", "      AAAAA      ")
                .aisle("      AAAAA      ", "      AAAAA      ", "      CDDDC      ", "       DDD       ", "       DDD       ", "       DDD       ", "AAC   ADDDA   CAA", "AADDDDD   DDDDDAA", "AADDDDD B DDDDDAA", "AADDDDD   DDDDDAA", "AAC   ADDDA   CAA", "       DDD       ", "       DDD       ", "       DDD       ", "      CDDDC      ", "      AAAAA      ", "      AAAAA      ")
                .aisle("      AAAAA      ", "      AAAAA      ", "      CCCCC      ", "                 ", "                 ", "                 ", "AAC   AAAAA   CAA", "AAC   ADDDA   CAA", "AAC   ADBDA   CAA", "AAC   ADDDA   CAA", "AAC   AAAAA   CAA", "                 ", "                 ", "                 ", "      CCCCC      ", "      AAAAA      ", "      AAAAA      ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "                 ", "       DDD       ", " E     DBD     E ", "       DDD       ", "                 ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "                 ", "       DDD       ", " E     DBD     E ", "       DDD       ", "                 ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "                 ", "       DDD       ", " E     DBD     E ", "       DDD       ", "                 ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "                 ", "                 ", "                 ", "                 ", "      CCCCC      ", "      CDDDC      ", " E    CDBDC    E ", "      CDDDC      ", "      CCCCC      ", "                 ", "                 ", "                 ", "                 ", "        E        ", "                 ")
                .aisle("                 ", "        E        ", "        E        ", "        E        ", "        E        ", "        E        ", "      AAAAA      ", "      AAAAA      ", " EEEEEAAAAAEEEEE ", "      AAAAA      ", "      AAAAA      ", "        E        ", "        E        ", "        E        ", "        E        ", "        E        ", "                 ")
                .aisle("                 ", "                 ", "                 ", "                 ", "                 ", "                 ", "      AAAAA      ", "      AAAAA      ", "      AAAAA      ", "      AAAAA      ", "      AAAAA      ", "                 ", "                 ", "                 ", "                 ", "                 ", "                 ")
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('A', Predicates.blocks(casing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(1).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(1).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(2).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(2).setPreviewCount(1)))
                .where('B', Predicates.blocks(baryonicCore))
                .where('C', Predicates.blocks(quarkCore))
                .where('D', Predicates.blocks(glass))
                .where('E', Predicates.blocks(frame))
                .build();
    }

    private static Block block(String preferred, String fallback) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(preferred));
        if (block == null) {
            GTDishanhaiMod.LOGGER.warn("Missing block {}, fallback to {}", preferred, fallback);
            block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(fallback));
        }
        return block;
    }
}
