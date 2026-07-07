package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.side.fluid.FluidTransferHelper;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEIORecipeHandlePart;
import org.gtlcore.gtlcore.api.machine.trait.RecipeHandlePart;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class MatterCopierMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine
        implements IInteractedMachine, IFancyUIMachine {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            MatterCopierMachine.class, GTLAddWirelessWorkableElectricMultipleRecipesMachine.getMANAGED_FIELD_HOLDER());
    private static final int COPY_INTERVAL = 20;
    private static final long EU_PER_ITEM = 1024L;
    private static final long MIN_COPY_AMOUNT = 1L;
    private static final long MAX_COPY_AMOUNT = Long.MAX_VALUE;
    private static final long MAX_OUTPUT_PER_TICK = Long.MAX_VALUE;
    private static final int MAX_COPY_AMOUNT_TEXT_LENGTH = 19;

    @Persisted
    @DescSynced
    public final NotifiableItemStackHandler prototypeSlot;

    @Persisted
    @DescSynced
    private long copyAmount = 1L;

    @Persisted
    @DescSynced
    private boolean itemMode = true;

    @DescSynced
    private String lastStatus = "待机";

    private TickableSubscription copyTickSubscription;

    public MatterCopierMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        ensureWirelessUuid();
        this.prototypeSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }
        };
        this.prototypeSlot.storage.setOnContentsChanged(() -> {
            markDirty();
            updateCopySubscription();
        });
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ensureWirelessUuid();
        updateCopySubscription();
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (copyTickSubscription != null) {
            copyTickSubscription.unsubscribe();
            copyTickSubscription = null;
        }
    }

    @Override
    public void onStructureFormed() {
        ensureWirelessUuid();
        super.onStructureFormed();
        ensureWirelessUuid();
        updateCopySubscription();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        updateCopySubscription();
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        normalizeCopyAmount();
        return true;
    }

    @Override
    public Widget createUIWidget() {
        normalizeCopyAmount();
        WidgetGroup group = new WidgetGroup(0, 0, 190, 116);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        group.addWidget(new LabelWidget(6, 5, "§b§l物质定增"));
        group.addWidget(new LabelWidget(6, 20, () -> isFormed() ? "§a结构已成型" : "§c结构未成型"));
        group.addWidget(new LabelWidget(6, 34, () -> "§7模式: §f" + (itemMode ? "物品" : "流体")));
        group.addWidget(new LabelWidget(6, 48, () -> "§7状态: §f" + lastStatus));
        group.addWidget(new LabelWidget(6, 62, () -> "§7单次复制: §f" + formatLong(copyAmount)));
        group.addWidget(new SlotWidget(prototypeSlot.storage, 0, 8, 88)
                .setBackground(GuiTextures.SLOT)
                .setHoverTooltips(Component.literal("放入要复制的物品原型或流体容器")));
        TextFieldWidget amountField = new TextFieldWidget(32, 89, 74, 14,
                () -> String.valueOf(copyAmount), this::setCopyAmountFromText);
        amountField.setNumbersOnly(MIN_COPY_AMOUNT, MAX_COPY_AMOUNT);
        amountField.setMaxStringLength(MAX_COPY_AMOUNT_TEXT_LENGTH);
        amountField.setCurrentString(String.valueOf(copyAmount));
        group.addWidget(amountField);
        group.addWidget(new ButtonWidget(112, 88, 34, 16, new TextTexture(itemMode ? "物品" : "流体", -1), cd -> toggleMode()));
        group.addWidget(new ButtonWidget(150, 88, 34, 16, new TextTexture("×10", -1), cd -> multiplyCopyAmount()));
        return group;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        normalizeCopyAmount();
        super.addDisplayText(textList);
        textList.add(Component.translatable("gt_shanhai.machine.matter_copier.name").withStyle(ChatFormatting.AQUA));
        textList.add(Component.literal("§7复制模式: §f" + (itemMode ? "物品" : "流体")));
        textList.add(Component.literal("§7单次复制: §f" + formatLong(copyAmount)));
        textList.add(Component.literal("§7无线电网: §f" + getWirelessStatusText()));
        textList.add(Component.literal("§7状态: §f" + lastStatus));
    }

    private void updateCopySubscription() {
        if (isRemote()) return;
        boolean shouldTick = isFormed();
        if (shouldTick && copyTickSubscription == null) {
            copyTickSubscription = subscribeServerTick(copyTickSubscription, this::copyTick);
        } else if (!shouldTick && copyTickSubscription != null) {
            copyTickSubscription.unsubscribe();
            copyTickSubscription = null;
        }
    }

    private void copyTick() {
        if (getLevel() == null || getLevel().isClientSide || !isFormed()) return;
        if ((getOffsetTimer() % COPY_INTERVAL) != 0) return;
        normalizeCopyAmount();
        ItemStack prototype = prototypeSlot.getStackInSlot(0);
        FluidStack fluidPrototype = FluidStack.empty();
        if (itemMode) {
            if (prototype.isEmpty()) {
                setLastStatus("缺少复制原型");
                return;
            }
        } else {
            fluidPrototype = getFluidPrototype(prototype);
            if (fluidPrototype == null || fluidPrototype.isEmpty()) {
                fluidPrototype = findFluidPrototypeFromInputItems();
            }
            if (fluidPrototype == null || fluidPrototype.isEmpty()) {
                fluidPrototype = findFluidPrototypeFromInputFluids();
            }
            if (fluidPrototype == null || fluidPrototype.isEmpty()) {
                setLastStatus("缺少流体容器或输入流体");
                return;
            }
        }
        long requestedAmount = clampCopyAmount(copyAmount);
        long amount = Math.min(requestedAmount, MAX_OUTPUT_PER_TICK);
        if (itemMode && !outputItem(prototype, amount, true)) {
            setLastStatus("输出已满");
            return;
        }
        if (!itemMode && !outputFluid(fluidPrototype, amount, true)) {
            setLastStatus("输出已满");
            return;
        }
        BigInteger cost = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(EU_PER_ITEM));
        ensureWirelessUuid();
        if (getUuid() == null) {
            setLastStatus("无线电网未绑定");
            return;
        }
        if (!consumeWirelessEnergy(cost)) {
            setLastStatus("无线电网能源不足");
            return;
        }
        if (itemMode) {
            outputItem(prototype, amount, false);
            setLastStatus(formatCopiedStatus(amount, requestedAmount, "x " + prototype.getHoverName().getString()));
        } else {
            outputFluid(fluidPrototype, amount, false);
            setLastStatus(formatCopiedStatus(amount, requestedAmount, "mB " + fluidPrototype.getDisplayName().getString()));
        }
    }

    private boolean outputItem(ItemStack prototype, long amount, boolean simulate) {
        GTRecipe recipe = new GTRecipe(GTRecipeTypes.DUMMY_RECIPES,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                Collections.emptyList(), Collections.emptyList(), new CompoundTag(), 1, false);

        List<Object> contents = new ArrayList<>();
        contents.add(createOutputIngredient(prototype, amount));
        if (this instanceof IRecipeCapabilityMachine machine) {
            Reference2ObjectOpenHashMap<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, List<Object>> map =
                    new Reference2ObjectOpenHashMap<>();
            map.put(ItemRecipeCapability.CAP, contents);
            for (MEIORecipeHandlePart<?> handler : machine.getMEOutputRecipeHandleParts()) {
                map = handler.meHandleOutput(map, simulate);
                List<Object> left = map.get(ItemRecipeCapability.CAP);
                if (left == null || left.isEmpty()) return true;
            }
            for (RecipeHandlePart handler : machine.getNormalRecipeHandlePart(IO.OUT)) {
                map = handler.handleRecipe(IO.OUT, recipe, map, simulate);
                List<Object> left = map.get(ItemRecipeCapability.CAP);
                if (left == null || left.isEmpty()) return true;
            }
            List<Object> left = map.get(ItemRecipeCapability.CAP);
            if (left == null || left.isEmpty()) return true;
            return outputItemToCapabilityProxy(recipe, left, simulate);
        }

        return outputItemToCapabilityProxy(recipe, contents, simulate);
    }

    private boolean outputItemToCapabilityProxy(GTRecipe recipe, List<Object> contents, boolean simulate) {
        List<IRecipeHandler<?>> handlers = getCapabilitiesProxy().get(IO.OUT, ItemRecipeCapability.CAP);
        if (handlers == null || handlers.isEmpty()) return false;
        List<Ingredient> left = new ArrayList<>();
        for (Object content : contents) {
            if (content instanceof Ingredient ingredient) {
                left.add(ingredient);
            }
        }
        if (left.isEmpty()) return true;
        for (IRecipeHandler<?> rawHandler : handlers) {
            @SuppressWarnings("unchecked")
            IRecipeHandler<Ingredient> handler = (IRecipeHandler<Ingredient>) rawHandler;
            left = handler.handleRecipeInner(IO.OUT, recipe, left, null, simulate);
            if (left == null || left.isEmpty()) return true;
        }
        return false;
    }

    private boolean outputFluid(FluidStack prototype, long amount, boolean simulate) {
        GTRecipe recipe = new GTRecipe(GTRecipeTypes.DUMMY_RECIPES,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                Collections.emptyList(), Collections.emptyList(), new CompoundTag(), 1, false);

        List<Object> contents = new ArrayList<>();
        contents.add(createOutputFluidIngredient(prototype, amount));
        if (this instanceof IRecipeCapabilityMachine machine) {
            Reference2ObjectOpenHashMap<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, List<Object>> map =
                    new Reference2ObjectOpenHashMap<>();
            map.put(FluidRecipeCapability.CAP, contents);
            for (MEIORecipeHandlePart<?> handler : machine.getMEOutputRecipeHandleParts()) {
                map = handler.meHandleOutput(map, simulate);
                List<Object> left = map.get(FluidRecipeCapability.CAP);
                if (left == null || left.isEmpty()) return true;
            }
            for (RecipeHandlePart handler : machine.getNormalRecipeHandlePart(IO.OUT)) {
                map = handler.handleRecipe(IO.OUT, recipe, map, simulate);
                List<Object> left = map.get(FluidRecipeCapability.CAP);
                if (left == null || left.isEmpty()) return true;
            }
            List<Object> left = map.get(FluidRecipeCapability.CAP);
            if (left == null || left.isEmpty()) return true;
            return outputFluidToCapabilityProxy(recipe, left, simulate);
        }

        return outputFluidToCapabilityProxy(recipe, contents, simulate);
    }

    private boolean outputFluidToCapabilityProxy(GTRecipe recipe, List<Object> contents, boolean simulate) {
        List<IRecipeHandler<?>> handlers = getCapabilitiesProxy().get(IO.OUT, FluidRecipeCapability.CAP);
        if (handlers == null || handlers.isEmpty()) return false;
        List<FluidIngredient> left = new ArrayList<>();
        for (Object content : contents) {
            if (content instanceof FluidIngredient ingredient) {
                left.add(ingredient);
            }
        }
        if (left.isEmpty()) return true;
        for (IRecipeHandler<?> rawHandler : handlers) {
            @SuppressWarnings("unchecked")
            IRecipeHandler<FluidIngredient> handler = (IRecipeHandler<FluidIngredient>) rawHandler;
            left = handler.handleRecipeInner(IO.OUT, recipe, left, null, simulate);
            if (left == null || left.isEmpty()) return true;
        }
        return false;
    }

    private Object createOutputIngredient(ItemStack prototype, long amount) {
        ItemStack key = prototype.copy();
        key.setCount(1);
        if (amount > Integer.MAX_VALUE) {
            return LongIngredient.create(Ingredient.of(key), amount);
        }
        ItemStack stack = key.copy();
        stack.setCount((int) amount);
        return SizedIngredient.create(stack);
    }

    private FluidIngredient createOutputFluidIngredient(FluidStack prototype, long amount) {
        FluidStack stack = FluidStack.create(prototype.getFluid(), amount, prototype.getTag());
        return FluidIngredient.of(stack);
    }

    private FluidStack getFluidPrototype(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return FluidStack.empty();
        ItemStack key = stack.copy();
        key.setCount(1);
        return FluidTransferHelper.getFluidContained(key);
    }

    private FluidStack findFluidPrototypeFromInputItems() {
        List<IRecipeHandler<?>> handlers = getCapabilitiesProxy().get(IO.IN, ItemRecipeCapability.CAP);
        if (handlers == null || handlers.isEmpty()) return FluidStack.empty();
        for (IRecipeHandler<?> handler : handlers) {
            for (Object content : handler.getContents()) {
                if (content instanceof ItemStack stack) {
                    FluidStack fluid = getFluidPrototype(stack);
                    if (fluid != null && !fluid.isEmpty()) return fluid;
                }
            }
        }
        return FluidStack.empty();
    }

    private FluidStack findFluidPrototypeFromInputFluids() {
        List<IRecipeHandler<?>> handlers = getCapabilitiesProxy().get(IO.IN, FluidRecipeCapability.CAP);
        if (handlers == null || handlers.isEmpty()) return FluidStack.empty();
        for (IRecipeHandler<?> handler : handlers) {
            for (Object content : handler.getContents()) {
                if (content instanceof FluidStack stack && !stack.isEmpty()) {
                    return stack.copy(1L);
                }
            }
        }
        return FluidStack.empty();
    }

    public boolean isJadeItemMode() {
        return itemMode;
    }

    public long getJadeCopyAmount() {
        return clampCopyAmount(copyAmount);
    }

    public long getJadeMaxOutputPerTick() {
        return MAX_OUTPUT_PER_TICK;
    }

    public String getJadeStatus() {
        return lastStatus;
    }

    public String getJadePrototypeName() {
        ItemStack prototype = prototypeSlot.getStackInSlot(0);
        if (prototype.isEmpty()) return "无";
        return prototype.getHoverName().getString();
    }

    public String getJadeWirelessStatus() {
        return getWirelessStatusText();
    }

    private void setCopyAmountFromText(String value) {
        copyAmount = parseCopyAmount(value);
        markDirty();
    }

    private void toggleMode() {
        itemMode = !itemMode;
        markDirty();
        notifyBlockUpdate();
    }

    private void multiplyCopyAmount() {
        copyAmount = saturatingMultiply(clampCopyAmount(copyAmount), 10L);
        markDirty();
        notifyBlockUpdate();
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        normalizeCopyAmount();
        super.saveCustomPersistedData(tag, forDrop);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        normalizeCopyAmount();
    }

    private void normalizeCopyAmount() {
        copyAmount = clampCopyAmount(copyAmount);
    }

    private boolean consumeWirelessEnergy(BigInteger cost) {
        if (cost == null || cost.signum() <= 0) return false;
        return getWirelessNetworkEnergyHandler().consumeEnergy(cost.negate());
    }

    private void ensureWirelessUuid() {
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
    }

    private String getWirelessStatusText() {
        if (getUuid() == null) return "未绑定";
        return getWirelessNetworkEnergyHandler().isOnline() ? "已绑定" : "已绑定但无能源";
    }

    private static long parseCopyAmount(String value) {
        if (value == null) return MIN_COPY_AMOUNT;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return MIN_COPY_AMOUNT;
        if (trimmed.length() > MAX_COPY_AMOUNT_TEXT_LENGTH) return MAX_COPY_AMOUNT;
        try {
            return clampCopyAmount(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
            return MAX_COPY_AMOUNT;
        }
    }

    private static long clampCopyAmount(long value) {
        if (value < MIN_COPY_AMOUNT) return MIN_COPY_AMOUNT;
        return Math.min(value, MAX_COPY_AMOUNT);
    }

    private void setLastStatus(String status) {
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            markDirty();
            notifyBlockUpdate();
        }
    }

    private static long saturatingMultiply(long a, long b) {
        if (a <= 0 || b <= 0) return 0;
        if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
        return a * b;
    }

    private static String formatLong(long value) {
        return String.format("%,d", value);
    }

    private static String formatCopiedStatus(long actualAmount, long requestedAmount, String suffix) {
        String status = "已复制 " + formatLong(actualAmount) + " " + suffix;
        if (actualAmount < requestedAmount) {
            status += "（分批）";
        }
        return status;
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block casing = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu", "uv_machine_casing"));
        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK)
                .aisle("CCC", "C~C", "CCC")
                .aisle("CCC", "C C", "CCC")
                .aisle("CCC", "CCC", "CCC")
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('C', Predicates.blocks(casing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                .where(' ', Predicates.any())
                .build();
    }

    public static MultiblockMachineDefinition register() {
        Block casing = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu", "uv_machine_casing"));
        MultiblockMachineDefinition def = GTDishanhaiRegistration.REGISTRATE
                .multiblock("matter_copier", MatterCopierMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(GTRecipeTypes.DUMMY_RECIPES)
                .appearanceBlock(() -> casing)
                .pattern(MatterCopierMachine::createPattern)
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/voltage/uv/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/zero_photon_condenser"))
                .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(Component.literal("§b§l物质定增"));
            tips.add(Component.literal("§7NXY Matter Copier 的 GTL 移植版"));
            tips.add(Component.literal("§7控制槽放入原型，设置数量后周期性复制到输出仓口"));
            tips.add(Component.literal("§7结构: 3x3x3 UV 机器外壳，可替换输入/输出总线与输入/输出仓"));
            tips.add(Component.literal("§e使用数据棒绑定无线电网供能，不需要能源仓室"));
        });
        return def;
    }
}
