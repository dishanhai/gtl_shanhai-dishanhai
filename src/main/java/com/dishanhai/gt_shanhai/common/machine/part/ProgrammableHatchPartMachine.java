package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.gui.configurators.ProgrammableHatchRecipeTypeConfigurator;
import com.dishanhai.gt_shanhai.common.item.VirtualItemProviderHelper;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.machine.trait.ItemHandlerProxyRecipeTrait;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.machine.multiblock.part.DualHatchPartMachine;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ProgrammableHatchPartMachine extends DualHatchPartMachine {

    public static final String HATCH_COMBINED_ID = "gt_shanhai:hatch_combined";
    private static int diagLogCount = 0;
    // 开发诊断日志开关：设为 0 关闭全部 [ProgrammableHatchDiag] 输出（保留调用点便于后续排查）
    private static final int DIAG_LOG_LIMIT = 0;

    @Persisted
    @DescSynced
    private String selectedRecipeTypeId = HATCH_COMBINED_ID;

    public ProgrammableHatchPartMachine(IMachineBlockEntity holder) {
        this(holder, 0);
    }

    public ProgrammableHatchPartMachine(IMachineBlockEntity holder, int tier) {
        super(holder, tier, IO.IN);
    }

    @Override
    public int getInventorySize() {
        int slotRoot = getSlotRoot();
        return slotRoot * slotRoot;
    }

    @Override
    protected NotifiableFluidTank createTank(long initialCapacity, int slots, Object... args) {
        return new ProgrammableFluidTank(initialCapacity);
    }

    @Override
    protected NotifiableItemStackHandler createInventory(Object... args) {
        return new NotifiableItemStackHandler(this, getInventorySize(), IO.IN, IO.IN, slots -> new ItemStackTransfer(slots) {
            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                if (stack == null || stack.isEmpty()) {
                    super.setStackInSlot(slot, ItemStack.EMPTY);
                    return;
                }
                if (VirtualItemProviderHelper.isBoundProvider(stack)) {
                    super.setStackInSlot(slot, ItemStack.EMPTY);
                    setProvidedStack(VirtualItemProviderHelper.getTarget(stack));
                    return;
                }
                if (isProgrammableSource(stack)) {
                    super.setStackInSlot(slot, stack);
                }
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate, boolean notifyChanges) {
                if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
                if (!VirtualItemProviderHelper.isBoundProvider(stack)) {
                    return super.insertItem(slot, stack, simulate, notifyChanges);
                }

                NotifiableItemStackHandler target = getCircuitInventory();
                if (target == null || target.getSlots() <= 0) return stack;
                ItemStack resolved = VirtualItemProviderHelper.getTarget(stack);
                if (resolved.isEmpty()) return stack;
                if (!simulate) {
                    setProvidedStack(resolved);
                }

                ItemStack left = stack.copy();
                left.shrink(1);
                return left.isEmpty() ? ItemStack.EMPTY : left;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return stack == null || stack.isEmpty() || isProgrammableSource(stack);
            }

            @Override
            public void onContentsChanged(int slot) {
                super.onContentsChanged(slot);
            }
        }) {
            @Override
            public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> ingredients,
                                                       String slotName, boolean simulate) {
                if (io != IO.IN || recipe == null || ingredients == null || ingredients.isEmpty()) {
                    return ingredients;
                }

                Iterator<Ingredient> iterator = ingredients.iterator();
                while (iterator.hasNext()) {
                    Ingredient ingredient = iterator.next();
                    if (!isNonConsumableInput(recipe, ingredient, slotName)) continue;
                    if (hasItemForIngredient(ingredient, this)) {
                        iterator.remove();
                        diag("inventoryHandle removedNonConsumable pos={} recipe={} slotName={} simulate={} remaining={}",
                                getPos(), recipeId(recipe), slotName, simulate, ingredients.size());
                    }
                }

                if (ingredients.isEmpty()) return null;
                return super.handleRecipeInner(io, recipe, ingredients, slotName, simulate);
            }
        };
    }

    @Override
    protected NotifiableItemStackHandler createCircuitItemHandler(Object... args) {
        return new NotifiableItemStackHandler(this, 1, IO.IN, IO.NONE, slots -> new ItemStackTransfer(slots) {
            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate, boolean notifyChanges) {
                if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
                if (!VirtualItemProviderHelper.isBoundProvider(stack)) return stack;
                ItemStack resolved = VirtualItemProviderHelper.getTarget(stack);
                ItemStack remainder = super.insertItem(slot, resolved, simulate, notifyChanges);
                if (!remainder.isEmpty()) return stack.copy();

                ItemStack left = stack.copy();
                left.shrink(1);
                return left.isEmpty() ? ItemStack.EMPTY : left;
            }

            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                super.setStackInSlot(slot, VirtualItemProviderHelper.resolveForRecipe(stack));
            }

            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return stack == null || stack.isEmpty() || VirtualItemProviderHelper.isBoundProvider(stack);
            }
        }) {

            @Override
            public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> ingredients,
                                                       String slotName, boolean simulate) {
                if (io != IO.IN || recipe == null || ingredients == null || ingredients.isEmpty()) {
                    return ingredients;
                }

                ItemStack provided = getStackInSlot(0);
                if (provided.isEmpty()) return ingredients;

                Iterator<Ingredient> iterator = ingredients.iterator();
                while (iterator.hasNext()) {
                    Ingredient ingredient = iterator.next();
                    if (!isNonConsumableInput(recipe, ingredient, slotName)) continue;
                    if (ingredient.test(provided)) {
                        iterator.remove();
                        diag("circuitHandle matched pos={} recipe={} slotName={} simulate={} before={} after={} provided={}",
                                getPos(), recipeId(recipe), slotName, simulate, ingredients.size() + 1, ingredients.size(), stackId(provided));
                        break;
                    }
                }

                return ingredients.isEmpty() ? null : ingredients;
            }
        };
    }

    @Override
    protected ItemHandlerProxyRecipeTrait createCombinedItemHandler(Object... args) {
        return new ProgrammableCombinedItemHandler();
    }

    private class ProgrammableFluidTank extends NotifiableFluidTank {
        private ProgrammableFluidTank(long initialCapacity) {
            super(ProgrammableHatchPartMachine.this, getSlotRoot(), getTankCapacitySafe(initialCapacity, getTier()), IO.IN);
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> ingredients,
                                                       String slotName, boolean simulate) {
            if (io != IO.IN || recipe == null || ingredients == null || ingredients.isEmpty()) {
                return ingredients;
            }

            Iterator<FluidIngredient> iterator = ingredients.iterator();
            while (iterator.hasNext()) {
                FluidIngredient ingredient = iterator.next();
                if (!isNonConsumableFluidInput(recipe, ingredient, slotName)) continue;
                if (hasFluidForIngredient(ingredient, getStorages())) {
                    iterator.remove();
                    diag("fluidHandle removedNonConsumable pos={} recipe={} slotName={} simulate={} remaining={}",
                            getPos(), recipeId(recipe), slotName, simulate, ingredients.size());
                }
            }
            if (ingredients.isEmpty()) return null;
            return super.handleRecipeInner(io, recipe, ingredients, slotName, simulate);
        }
    }

    private class ProgrammableCombinedItemHandler extends ItemHandlerProxyRecipeTrait {
        private ProgrammableCombinedItemHandler() {
            super(ProgrammableHatchPartMachine.this, Set.of(getInventory(), getCircuitInventory()), IO.IN, IO.NONE);
        }

        @Override
        public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> ingredients,
                                                   String slotName, boolean simulate) {
            if (io != IO.IN || recipe == null || ingredients == null || ingredients.isEmpty()) {
                return ingredients;
            }

            Iterator<Ingredient> iterator = ingredients.iterator();
            while (iterator.hasNext()) {
                Ingredient ingredient = iterator.next();
                if (!isNonConsumableInput(recipe, ingredient, slotName)) continue;
                if (hasItemForIngredient(ingredient, getInventory()) || hasItemForIngredient(ingredient, getCircuitInventory())) {
                    iterator.remove();
                    diag("combinedHandle removedNonConsumable pos={} recipe={} slotName={} simulate={} remaining={}",
                            getPos(), recipeId(recipe), slotName, simulate, ingredients.size());
                }
            }
            if (ingredients.isEmpty()) return null;
            return super.handleRecipeInner(io, recipe, ingredients, slotName, simulate);
        }

        @Override
        public boolean isProxy() {
            return false;
        }

        @Override
        public boolean isDistinct() {
            return false;
        }
    }

    @Override
    public List<IRecipeHandlerTrait> getRecipeHandlers() {
        List<IRecipeHandlerTrait> handlers = new ArrayList<>();
        handlers.add(getCircuitInventory());
        handlers.add(getInventory());
        handlers.add(tank);
        diag("getRecipeHandlers pos={} tier={} formed={} selected={} combinedMode={} handlers={} details={}",
                getPos(), getTier(), isFormed(), selectedRecipeTypeId, isCombinedMode(), handlers.size(), handlerSummary(handlers));
        return handlers;
    }

    @Override
    public boolean isDistinct() {
        return false;
    }

    @Override
    public void setDistinct(boolean isDistinct) {
        getInventory().setDistinct(false);
        getCircuitInventory().setDistinct(false);
        tank.setDistinct(false);
        getCombinedInventory().setDistinct(false);
    }

    @Override
    public void attachSideTabs(TabsWidget tabsWidget) {
        super.attachSideTabs(tabsWidget);
        tabsWidget.attachSubTab(new ProgrammableHatchRecipeTypeConfigurator(this));
    }

    @Override
    public void onLoad() {
        super.onLoad();
    }

    @Override
    public void addedToController(@NotNull IMultiController controller) {
        super.addedToController(controller);
        diag("addedToController pos={} tier={} controller={} recipeTypes={} selectedBefore={}",
                getPos(), getTier(), controllerInfo(controller), recipeTypesInfo(controller), selectedRecipeTypeId);
        pruneSelectedRecipeType();
        diag("addedToControllerDone pos={} selectedAfter={} selectable={}",
                getPos(), selectedRecipeTypeId, recipeTypesInfo(controller));
    }

    @Override
    public void removedFromController(@NotNull IMultiController controller) {
        super.removedFromController(controller);
        diag("removedFromController pos={} controller={} selectedBefore={}", getPos(), controllerInfo(controller), selectedRecipeTypeId);
        pruneSelectedRecipeType();
    }

    public List<GTRecipeType> getSelectableRecipeTypes() {
        LinkedHashSet<GTRecipeType> types = new LinkedHashSet<>();
        for (IMultiController controller : getControllers()) {
            if (controller instanceof IRecipeLogicMachine machine) {
                GTRecipeType[] recipeTypes = machine.getRecipeTypes();
                if (recipeTypes == null) continue;
                for (GTRecipeType type : recipeTypes) {
                    if (type != null) types.add(type);
                }
            }
        }
        return new ArrayList<>(types);
    }

    public GTRecipeType getSelectedRecipeType() {
        if (isCombinedMode()) return null;
        for (GTRecipeType type : getSelectableRecipeTypes()) {
            if (recipeTypeIdEquals(type, selectedRecipeTypeId)) return type;
        }
        return null;
    }

    public boolean isCombinedMode() {
        return selectedRecipeTypeId == null || selectedRecipeTypeId.isEmpty() || HATCH_COMBINED_ID.equals(selectedRecipeTypeId);
    }

    public boolean hasSelectedRecipeType() {
        return getSelectedRecipeType() != null;
    }

    public static GTRecipeType getSelectedRecipeTypeFor(IRecipeLogicMachine machine) {
        if (!(machine instanceof IMultiController controller)) return null;
        GTRecipeType resolved = null;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof ProgrammableHatchPartMachine programmableHatch) {
                GTRecipeType selected = programmableHatch.getSelectedRecipeType();
                if (selected != null && canMachineUseRecipeType(machine, selected)) {
                    if (resolved == null) {
                        resolved = selected;
                    } else if (!recipeTypeEquals(resolved, selected)) {
                        return null;
                    }
                }
            }
        }
        return resolved;
    }

    public static GTRecipeType getEffectiveRecipeType(IRecipeLogicMachine machine, GTRecipeType fallback) {
        GTRecipeType selected = getSelectedRecipeTypeFor(machine);
        return selected != null ? selected : fallback;
    }

    public static boolean switchRecipeTypeFor(IRecipeLogicMachine machine, GTRecipeType target, boolean requireProgrammableHatch) {
        if (!(machine instanceof IMultiController controller) || target == null || !canMachineUseRecipeType(machine, target)) {
            return false;
        }
        boolean foundHatch = false;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof ProgrammableHatchPartMachine programmableHatch) {
                foundHatch = true;
                if (programmableHatch.canSelectRecipeType(target)) {
                    programmableHatch.setSelectedRecipeType(target);
                }
            }
        }
        if (foundHatch) {
            return recipeTypeEquals(getSelectedRecipeTypeFor(machine), target);
        }
        if (requireProgrammableHatch) {
            return false;
        }
        GTRecipeType[] types = machine.getRecipeTypes();
        if (types == null) return false;
        for (int i = 0; i < types.length; i++) {
            if (recipeTypeEquals(types[i], target)) {
                machine.setActiveRecipeType(i);
                if (machine.getRecipeLogic() != null) {
                    machine.getRecipeLogic().resetRecipeLogic();
                }
                return true;
            }
        }
        return false;
    }

    public void selectRecipeTypeByIndex(int index) {
        if (index < 0) {
            setCombinedMode();
            return;
        }
        List<GTRecipeType> types = getSelectableRecipeTypes();
        if (index >= types.size()) return;
        setSelectedRecipeType(types.get(index));
    }

    public void setSelectedRecipeType(GTRecipeType type) {
        String next = type == null || type.registryName == null ? HATCH_COMBINED_ID : type.registryName.toString();
        diag("setSelectedRecipeType pos={} old={} next={} type={} controllers={}",
                getPos(), selectedRecipeTypeId, next, recipeTypeId(type), getControllers().size());
        if (next.equals(selectedRecipeTypeId)) {
            syncControllerActiveRecipeTypes();
            resetControllerRecipeLogic();
            return;
        }
        selectedRecipeTypeId = next;
        markDirty();
        if (getLevel() != null && !getLevel().isClientSide) {
            notifyBlockUpdate();
        }
        scheduleRenderUpdate();
        syncControllerActiveRecipeTypes();
        resetControllerRecipeLogic();
    }

    private boolean canSelectRecipeType(GTRecipeType type) {
        for (GTRecipeType selectable : getSelectableRecipeTypes()) {
            if (recipeTypeEquals(selectable, type)) {
                return true;
            }
        }
        return false;
    }

    public void setCombinedMode() {
        setSelectedRecipeType(null);
    }

    public ItemStack getCircuitStack() {
        return getProvidedStack();
    }

    public ItemStack getProvidedStack() {
        NotifiableItemStackHandler h = getCircuitInventory();
        return (h != null && h.getSlots() > 0) ? h.getStackInSlot(0) : ItemStack.EMPTY;
    }

    public int getCircuitConfig() {
        ItemStack s = getProvidedStack();
        if (s.isEmpty()) return -1;
        return IntCircuitBehaviour.getCircuitConfiguration(s);
    }

    private void setProvidedStack(ItemStack stack) {
        NotifiableItemStackHandler target = getCircuitInventory();
        if (target == null || target.getSlots() <= 0) return;
        target.storage.setStackInSlot(0, stack == null ? ItemStack.EMPTY : stack);
    }

    private int getSlotRoot() {
        return Math.max(1, getTier());
    }

    private static long getTankCapacitySafe(long initialCapacity, int tier) {
        int capacityTier = Math.max(0, tier - 1);
        return initialCapacity * (1L << Math.min(30, capacityTier));
    }

    private static boolean isProgrammableSource(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        if (VirtualItemProviderHelper.isProviderItem(stack)) {
            return VirtualItemProviderHelper.isBoundProvider(stack);
        }
        return true;
    }

    private static boolean isNonConsumableInput(GTRecipe recipe, Ingredient ingredient, String slotName) {
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return false;
        for (Content content : contents) {
            if (content == null || content.chance != 0) continue;
            if (slotName != null && content.slotName != null && !slotName.equals(content.slotName)) continue;
            Ingredient nonConsumable = ItemRecipeCapability.CAP.of(content.getContent());
            if (ingredientsHaveSameCandidates(nonConsumable, ingredient)) return true;
        }
        return false;
    }

    private static boolean isNonConsumableFluidInput(GTRecipe recipe, FluidIngredient ingredient, String slotName) {
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return false;
        for (Content content : contents) {
            if (content == null || content.chance != 0) continue;
            if (slotName != null && content.slotName != null && !slotName.equals(content.slotName)) continue;
            FluidIngredient nonConsumable = FluidRecipeCapability.CAP.of(content.getContent());
            if (fluidIngredientsHaveSameCandidates(nonConsumable, ingredient)) return true;
        }
        return false;
    }

    private static boolean hasFluidForIngredient(FluidIngredient ingredient, FluidStorage[] storages) {
        if (ingredient == null || ingredient.isEmpty() || storages == null) return false;
        for (FluidStorage storage : storages) {
            if (storage == null) continue;
            for (int i = 0; i < storage.getTanks(); i++) {
                FluidStack stored = storage.getFluidInTank(i);
                if (stored != null && !stored.isEmpty() && ingredient.test(stored)) return true;
            }
        }
        return false;
    }

    private static boolean hasItemForIngredient(Ingredient ingredient, NotifiableItemStackHandler handler) {
        if (ingredient == null || handler == null) return false;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack == null || stack.isEmpty()) continue;
            if (ingredient.test(stack)) return true;
            ItemStack resolved = VirtualItemProviderHelper.resolveForRecipe(stack);
            if (!resolved.isEmpty() && ingredient.test(resolved)) return true;
        }
        return false;
    }

    private static boolean fluidIngredientsHaveSameCandidates(FluidIngredient left, FluidIngredient right) {
        if (left == null || right == null) return false;
        FluidStack[] leftStacks = left.getStacks();
        FluidStack[] rightStacks = right.getStacks();
        if (leftStacks == null || rightStacks == null || leftStacks.length != rightStacks.length) return false;
        for (FluidStack leftStack : leftStacks) {
            boolean matched = false;
            for (FluidStack rightStack : rightStacks) {
                if (leftStack != null && rightStack != null
                        && leftStack.getFluid() == rightStack.getFluid()
                        && java.util.Objects.equals(leftStack.getTag(), rightStack.getTag())
                        && leftStack.getAmount() == rightStack.getAmount()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private static boolean ingredientsHaveSameCandidates(Ingredient left, Ingredient right) {
        if (left == null || right == null) return false;
        ItemStack[] leftItems = left.getItems();
        ItemStack[] rightItems = right.getItems();
        if (leftItems == null || rightItems == null || leftItems.length != rightItems.length) return false;
        for (ItemStack leftStack : leftItems) {
            boolean matched = false;
            for (ItemStack rightStack : rightItems) {
                if (ItemStack.isSameItemSameTags(leftStack, rightStack)
                        && leftStack.getCount() == rightStack.getCount()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private void pruneSelectedRecipeType() {
        if (selectedRecipeTypeId == null || selectedRecipeTypeId.isEmpty()) {
            selectedRecipeTypeId = HATCH_COMBINED_ID;
            markDirty();
            return;
        }
        if (HATCH_COMBINED_ID.equals(selectedRecipeTypeId)) return;
        if (getSelectedRecipeType() == null) {
            setCombinedMode();
        }
    }

    private void resetControllerRecipeLogic() {
        for (IMultiController controller : getControllers()) {
            if (controller instanceof IRecipeLogicMachine machine && machine.getRecipeLogic() != null) {
                machine.getRecipeLogic().resetRecipeLogic();
            }
        }
    }

    private void syncControllerActiveRecipeTypes() {
        for (IMultiController controller : getControllers()) {
            if (!(controller instanceof IRecipeLogicMachine machine)) continue;
            GTRecipeType selected = getSelectedRecipeTypeFor(machine);
            if (selected == null && hasAnySelectedRecipeTypeFor(machine)) {
                machine.setActiveRecipeType(0);
                continue;
            }
            if (selected == null) continue;
            GTRecipeType[] types = machine.getRecipeTypes();
            if (types == null) continue;
            for (int i = 0; i < types.length; i++) {
                if (recipeTypeEquals(types[i], selected)) {
                    machine.setActiveRecipeType(i);
                    break;
                }
            }
        }
    }

    private static boolean recipeTypeIdEquals(GTRecipeType type, String id) {
        return type != null && type.registryName != null && type.registryName.toString().equals(id);
    }

    private static boolean hasAnySelectedRecipeTypeFor(IRecipeLogicMachine machine) {
        if (!(machine instanceof IMultiController controller)) return false;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof ProgrammableHatchPartMachine programmableHatch) {
                GTRecipeType selected = programmableHatch.getSelectedRecipeType();
                if (selected != null && canMachineUseRecipeType(machine, selected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean recipeTypeEquals(GTRecipeType left, GTRecipeType right) {
        return left == right || (left != null && right != null && left.registryName != null
                && left.registryName.equals(right.registryName));
    }

    private static boolean canMachineUseRecipeType(IRecipeLogicMachine machine, GTRecipeType type) {
        if (machine == null || type == null) return false;
        GTRecipeType[] recipeTypes = machine.getRecipeTypes();
        if (recipeTypes == null) return false;
        for (GTRecipeType candidate : recipeTypes) {
            if (recipeTypeEquals(candidate, type)) {
                return true;
            }
        }
        return false;
    }

    public static void logRecipeTypeRedirect(IRecipeLogicMachine machine, GTRecipeType selected, GTRecipeType fallback) {
        if (selected == null) return;
        diag("recipeTypeRedirect machine={} fallback={} selected={}",
                machine == null ? "null" : machine.getClass().getName(), recipeTypeId(fallback), recipeTypeId(selected));
    }

    private static void diag(String message, Object... args) {
        if (diagLogCount >= DIAG_LOG_LIMIT) return;
        diagLogCount++;
        GTDishanhaiMod.LOGGER.info("[ProgrammableHatchDiag] " + message, args);
    }

    private static String handlerSummary(List<IRecipeHandlerTrait> handlers) {
        if (handlers == null) return "null";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < handlers.size(); i++) {
            IRecipeHandlerTrait handler = handlers.get(i);
            if (i > 0) builder.append(" | ");
            if (handler == null) {
                builder.append("null");
            } else {
                builder.append(handler.getClass().getName())
                        .append(" io=").append(handler.getHandlerIO())
                        .append(" cap=").append(handler.getCapability());
            }
        }
        return builder.toString();
    }

    private static String controllerInfo(IMultiController controller) {
        if (controller == null) return "null";
        return controller.getClass().getName();
    }

    private static String recipeTypesInfo(IMultiController controller) {
        if (!(controller instanceof IRecipeLogicMachine machine)) return "notRecipeLogic";
        GTRecipeType[] types = machine.getRecipeTypes();
        if (types == null) return "null";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(i).append('=').append(recipeTypeId(types[i]));
        }
        return builder.toString();
    }

    private static String recipeId(GTRecipe recipe) {
        return recipe == null || recipe.getId() == null ? "null" : recipe.getId().toString();
    }

    private static String recipeTypeId(GTRecipeType type) {
        return type == null || type.registryName == null ? "null" : type.registryName.toString();
    }

    private static String stackId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "empty" : stack.getItem().toString() + "x" + stack.getCount();
    }
}
