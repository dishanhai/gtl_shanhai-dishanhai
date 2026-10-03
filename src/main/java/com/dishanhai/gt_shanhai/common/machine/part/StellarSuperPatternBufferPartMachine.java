package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.gui.AdvancedMEConfigurator;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.ExportOnlyAEConfigureFluidSlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.ExportOnlyAEConfigureItemSlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.common.CraftAmountLimits;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 星律樣板總成的 EnhancedCore 相容基類。
 *
 * <p>EnhancedCore 2.9.x 的 IV 執行鏈以方塊註冊 ID 作為入口；這個類把 GTLCore
 * 庫存輸入功能放在可繼承的樣板總成基類上，讓星律本體同時保留庫存輸入與
 * EnhancedCore 的結構相容性。</p>
 */
public class StellarSuperPatternBufferPartMachine extends MEPatternBufferPartMachine
        implements IModifiableSyncOffset {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            StellarSuperPatternBufferPartMachine.class, MEPatternBufferPartMachine.MANAGED_FIELD_HOLDER);
    protected static final int CONFIG_SIZE = 32;

    @Persisted
    protected final ExportOnlyAEStockingItemList stockItemHandler;
    @Persisted
    protected final ExportOnlyAEStockingFluidList stockFluidHandler;
    @Persisted
    private int syncOffset;
    @DescSynced
    protected int page;

    public StellarSuperPatternBufferPartMachine(@Nullable IMachineBlockEntity holder, int maxPatternCount, IO io) {
        super(holder, maxPatternCount, io);
        this.page = 1;
        this.stockItemHandler = new ExportOnlyAEStockingItemList(this, CONFIG_SIZE);
        this.stockFluidHandler = new ExportOnlyAEStockingFluidList(this, CONFIG_SIZE);
    }

    @Override
    protected MEPatternBufferPartMachineBase.InternalSlot createInternalSlot(int slotIndex) {
        return new StockingPatternBufferInternalSlot(slotIndex);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof ServerLevel level) {
            level.getServer().tell(new TickTask(1, new Runnable() {
                @Override
                public void run() {
                    stockItemHandler.onConfigChanged();
                    stockFluidHandler.onConfigChanged();
                    syncStockInput();
                }
            }));
        }
    }

    @Override
    protected void update() {
        super.update();
        int offset = getOffset();
        if (getOffsetTimer() % (offset == 0 ? ME_UPDATE_INTERVAL : offset) == 0) {
            syncStockInput();
        }
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(new IFancyConfigurator[] {
                new AdvancedMEConfigurator(new java.util.function.Consumer<Integer>() {
                    @Override
                    public void accept(Integer value) {
                        setOffset(value);
                    }
                }, new java.util.function.Supplier<Integer>() {
                    @Override
                    public Integer get() {
                        return getOffset();
                    }
                })
        });
    }

    @Override
    public int getOffset() {
        return syncOffset;
    }

    @Override
    public void setOffset(int offset) {
        syncOffset = Math.max(0, offset);
    }

    public void setPage(int page) {
        this.page = page;
    }

    @Override
    @NotNull
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    protected void onStockInputConfigChanged(boolean removedConfig) {
        if (!isRemote()) {
            syncStockInput();
            if (removedConfig) {
                invalidateRecipeCaches();
            }
        }
    }

    protected void invalidateRecipeCaches() {
        for (int slot = 0; slot < maxPatternCount; slot++) {
            removeSlotFromGTRecipeCache(slot);
        }
    }

    protected void syncStockInput() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            stockItemHandler.clearStocks();
            stockFluidHandler.clearStocks();
            return;
        }
        IStorageService service = grid.getStorageService();
        MEStorage storage = service.getInventory();
        stockItemHandler.syncStock(storage);
        stockFluidHandler.syncStock(storage);
    }

    @Nullable
    protected AEItemKey findStockItemKey(Ingredient ingredient, Object2LongMap<AEItemKey> internal,
            Object2LongMap<AEItemKey> catalyst, long needAmount, boolean includeCatalyst) {
        for (ItemStack item : ingredient.getItems()) {
            if (!item.isEmpty()) {
                AEItemKey key = AEItemKey.of(item);
                long amount = NumberUtils.saturatedAdd(internal.getLong(key), stockItemHandler.getAvailableAmount(key));
                if (includeCatalyst) amount = NumberUtils.saturatedAdd(amount, catalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
        }
        java.util.Iterator<AEItemKey> iterator = stockItemHandler.configList.iterator();
        while (iterator.hasNext()) {
            AEItemKey key = iterator.next();
            if (key.matches(ingredient)) {
                long amount = NumberUtils.saturatedAdd(internal.getLong(key), stockItemHandler.getAvailableAmount(key));
                if (includeCatalyst) amount = NumberUtils.saturatedAdd(amount, catalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
        }
        return null;
    }

    @Nullable
    protected AEFluidKey findStockFluidKey(FluidIngredient ingredient, Object2LongMap<AEFluidKey> internal,
            Object2LongMap<AEFluidKey> catalyst, long needAmount, boolean includeCatalyst) {
        for (FluidStack stack : ingredient.getStacks()) {
            if (!stack.isEmpty()) {
                AEFluidKey key = AEFluidKey.of(stack.getFluid());
                long amount = NumberUtils.saturatedAdd(internal.getLong(key), stockFluidHandler.getAvailableAmount(key));
                if (includeCatalyst) amount = NumberUtils.saturatedAdd(amount, catalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
        }
        java.util.Iterator<AEFluidKey> iterator = stockFluidHandler.configList.iterator();
        while (iterator.hasNext()) {
            AEFluidKey key = iterator.next();
            if (AEUtils.testFluidIngredient(ingredient, key)) {
                long amount = NumberUtils.saturatedAdd(internal.getLong(key), stockFluidHandler.getAvailableAmount(key));
                if (includeCatalyst) amount = NumberUtils.saturatedAdd(amount, catalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
        }
        return null;
    }

    protected boolean consumeStockItem(Object2LongMap<AEItemKey> internal, AEItemKey key, long needAmount) {
        long internalAmount = internal.getLong(key);
        long consumed = Math.min(internalAmount, needAmount);
        if (consumed > 0) {
            long left = internalAmount - consumed;
            if (left <= 0) internal.removeLong(key); else internal.put(key, left);
            needAmount -= consumed;
        }
        return needAmount <= 0 || stockItemHandler.extractStock(key, needAmount) >= needAmount;
    }

    protected boolean consumeStockFluid(Object2LongMap<AEFluidKey> internal, AEFluidKey key, long needAmount) {
        long internalAmount = internal.getLong(key);
        long consumed = Math.min(internalAmount, needAmount);
        if (consumed > 0) {
            long left = internalAmount - consumed;
            if (left <= 0) internal.removeLong(key); else internal.put(key, left);
            needAmount -= consumed;
        }
        return needAmount <= 0 || stockFluidHandler.extractStock(key, needAmount) >= needAmount;
    }

    protected void appendStockItemContents(List<ItemStack> inputs) {
        for (Object2LongMap.Entry<AEItemKey> entry : stockItemHandler.stockMap.object2LongEntrySet()) {
            long amount = entry.getLongValue();
            if (amount > 0) inputs.add(entry.getKey().toStack((int) Math.min(amount, Integer.MAX_VALUE)));
        }
    }

    protected void appendStockFluidContents(List<FluidStack> inputs) {
        for (Object2LongMap.Entry<AEFluidKey> entry : stockFluidHandler.stockMap.object2LongEntrySet()) {
            if (entry.getLongValue() > 0) inputs.add(FluidStack.create(entry.getKey().getFluid(), entry.getLongValue()));
        }
    }

    protected void addStockItemMap(Object2LongOpenHashMap<ItemStack> map) {
        for (Object2LongMap.Entry<AEItemKey> entry : stockItemHandler.stockMap.object2LongEntrySet()) {
            if (entry.getLongValue() > 0) map.addTo(entry.getKey().toStack(), entry.getLongValue());
        }
    }

    protected void addStockFluidMap(Object2LongOpenHashMap<FluidStack> map) {
        for (Object2LongMap.Entry<AEFluidKey> entry : stockFluidHandler.stockMap.object2LongEntrySet()) {
            if (entry.getLongValue() > 0) map.addTo(FluidStack.create(entry.getKey().getFluid(), 1L), entry.getLongValue());
        }
    }

    protected class StockingPatternBufferInternalSlot extends MEPatternBufferPartMachine.PatternBufferInternalSlot {
        protected StockingPatternBufferInternalSlot(int slotIndex) {
            super(slotIndex);
        }

        @Override
        public boolean isItemActive(boolean simulate) {
            return hasPatternInSlot(getSlotIndex()) && (!simulate
                    ? !(!getItemInventory().isEmpty() || stockItemHandler.hasConfig())
                    : getItemInventory().isEmpty() && sharedCatalystInventory.isEmpty()
                            && getCircuitForRecipe(getSlotIndex()).isEmpty()
                            && !hasItemCatalystInventory() && !stockItemHandler.hasConfig());
        }

        @Override
        public boolean isFluidActive(boolean simulate) {
            return hasPatternInSlot(getSlotIndex()) && (!simulate
                    ? !(!getFluidInventory().isEmpty() || stockFluidHandler.hasConfig())
                    : getFluidInventory().isEmpty() && sharedCatalystTank.isEmpty()
                            && !hasFluidCatalystInventory() && !stockFluidHandler.hasConfig());
        }

        @Override
        public ObjectList<ItemStack> getLimitItemStackInput() {
            ObjectList<ItemStack> result = super.getLimitItemStackInput();
            appendStockItemContents(result);
            return result;
        }

        @Override
        public ObjectList<FluidStack> getLimitFluidStackInput() {
            ObjectList<FluidStack> result = super.getLimitFluidStackInput();
            appendStockFluidContents(result);
            return result;
        }

        @Override
        public Object2LongMap<ItemStack> getItemStackInputMap() {
            Object2LongOpenHashMap<ItemStack> result = new Object2LongOpenHashMap<>();
            for (Object2LongMap.Entry<ItemStack> entry : super.getItemStackInputMap().object2LongEntrySet()) {
                result.addTo(entry.getKey(), entry.getLongValue());
            }
            addStockItemMap(result);
            return result;
        }

        @Override
        public Object2LongMap<FluidStack> getFluidStackInputMap() {
            Object2LongOpenHashMap<FluidStack> result = new Object2LongOpenHashMap<>();
            for (Object2LongMap.Entry<FluidStack> entry : super.getFluidStackInputMap().object2LongEntrySet()) {
                result.addTo(entry.getKey(), entry.getLongValue());
            }
            addStockFluidMap(result);
            return result;
        }

        @Override
        public boolean handleItemInternal(Object2LongMap<Ingredient> left, int leftCircuit, boolean simulate) {
            if (!stockItemHandler.hasConfig()) return super.handleItemInternal(left, leftCircuit, simulate);
            if (left.isEmpty() && leftCircuit < 0) return true;
            if (simulate && leftCircuit > 0 && leftCircuit != getCacheManager().getCircuitCache()) return false;
            Object2LongMap<AEItemKey> internal = getItemInventory();
            Object2LongMap<AEItemKey> catalyst = getItemCatalystInventory();
            for (Object2LongMap.Entry<Ingredient> entry : left.object2LongEntrySet()) {
                if (entry.getLongValue() > 0 && findStockItemKey(entry.getKey(), internal, catalyst,
                        entry.getLongValue(), simulate) == null) return false;
            }
            if (!simulate) {
                ObjectIterator<Object2LongMap.Entry<Ingredient>> iterator = Object2LongMaps.fastIterator(left);
                while (iterator.hasNext()) {
                    Object2LongMap.Entry<Ingredient> entry = iterator.next();
                    long amount = entry.getLongValue();
                    if (amount <= 0) iterator.remove();
                    else {
                        AEItemKey key = findStockItemKey(entry.getKey(), internal,
                                Object2LongMaps.emptyMap(), amount, false);
                        if (key == null || !consumeStockItem(internal, key, amount)) return false;
                        iterator.remove();
                    }
                }
            }
            return true;
        }

        @Override
        public boolean handleFluidInternal(Object2LongMap<FluidIngredient> left, boolean simulate) {
            if (!stockFluidHandler.hasConfig()) return super.handleFluidInternal(left, simulate);
            if (left.isEmpty()) return true;
            Object2LongMap<AEFluidKey> internal = getFluidInventory();
            Object2LongMap<AEFluidKey> catalyst = getFluidCatalystInventory();
            for (Object2LongMap.Entry<FluidIngredient> entry : left.object2LongEntrySet()) {
                if (entry.getLongValue() > 0 && findStockFluidKey(entry.getKey(), internal, catalyst,
                        entry.getLongValue(), simulate) == null) return false;
            }
            if (!simulate) {
                ObjectIterator<Object2LongMap.Entry<FluidIngredient>> iterator = Object2LongMaps.fastIterator(left);
                while (iterator.hasNext()) {
                    Object2LongMap.Entry<FluidIngredient> entry = iterator.next();
                    long amount = entry.getLongValue();
                    if (amount <= 0) iterator.remove();
                    else {
                        AEFluidKey key = findStockFluidKey(entry.getKey(), internal,
                                Object2LongMaps.emptyMap(), amount, false);
                        if (key == null || !consumeStockFluid(internal, key, amount)) return false;
                        iterator.remove();
                    }
                }
            }
            return true;
        }
    }

    protected class ExportOnlyAEStockingItemList extends ExportOnlyAEItemList {
        protected final ObjectArrayList<AEItemKey> configList = new ObjectArrayList<>();
        protected final IntArrayList configIndexList = new IntArrayList();
        protected final Object2LongOpenHashMap<AEItemKey> stockMap = new Object2LongOpenHashMap<>();

        protected ExportOnlyAEStockingItemList(MetaMachine holder, int slots) {
            super(holder, slots, ExportOnlyAEStockingItemSlot::new);
            stockMap.defaultReturnValue(0L);
            for (ExportOnlyAEItemSlot slot : inventory) {
                ((IMESlot) slot).setOnConfigChanged(new Runnable() {
                    @Override
                    public void run() {
                        onStockInputConfigChanged(onConfigChanged());
                    }
                });
            }
        }

        public void clearStocks() {
            stockMap.clear();
            for (ExportOnlyAEItemSlot slot : inventory) slot.setStock(null);
        }

        public void syncStock(MEStorage storage) {
            stockMap.clear();
            for (ExportOnlyAEItemSlot slot : inventory) {
                GenericStack config = slot.getConfig();
                if (config != null && config.what() instanceof AEItemKey key) {
                    long amount = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
                    if (amount > 0) {
                        slot.setStock(new GenericStack(key, amount));
                        stockMap.addTo(key, amount);
                        continue;
                    }
                }
                slot.setStock(null);
            }
        }

        public boolean onConfigChanged() {
            ObjectOpenHashSet<AEItemKey> previous = new ObjectOpenHashSet<>(configList);
            configList.clear();
            configIndexList.clear();
            for (int i = 0; i < inventory.length; i++) {
                GenericStack config = inventory[i].getConfig();
                if (config != null && config.what() instanceof AEItemKey key) {
                    configList.add(key);
                    configIndexList.add(i);
                    previous.remove(key);
                }
            }
            return !previous.isEmpty();
        }

        public boolean hasConfig() { return !configList.isEmpty(); }

        public long getAvailableAmount(AEItemKey key) {
            IGrid grid = getMainNode().getGrid();
            return grid != null && configList.contains(key)
                    ? grid.getStorageService().getInventory().extract(key, CraftAmountLimits.MAX_MANUAL_CRAFT_AMOUNT,
                            Actionable.SIMULATE, actionSource) : 0L;
        }

        public long extractStock(AEItemKey key, long amount) {
            IGrid grid = getMainNode().getGrid();
            if (amount <= 0 || grid == null) return 0L;
            long extracted = grid.getStorageService().getInventory().extract(key, amount,
                    Actionable.MODULATE, actionSource);
            if (extracted <= 0) return 0L;
            long left = getAvailableAmount(key);
            if (left <= 0) stockMap.removeLong(key); else stockMap.put(key, left);
            return extracted;
        }
    }

    protected static class ExportOnlyAEStockingItemSlot extends ExportOnlyAEConfigureItemSlot {
        public ExportOnlyAEStockingItemSlot() {}
        public ExportOnlyAEStockingItemSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
            super(config, stock);
        }
        @Override
        public ExportOnlyAEStockingItemSlot copy() {
            return new ExportOnlyAEStockingItemSlot(getConfig(), getStock());
        }
    }

    protected class ExportOnlyAEStockingFluidList extends ExportOnlyAEFluidList {
        protected final ObjectArrayList<AEFluidKey> configList = new ObjectArrayList<>();
        protected final IntArrayList configIndexList = new IntArrayList();
        protected final Object2LongOpenHashMap<AEFluidKey> stockMap = new Object2LongOpenHashMap<>();

        protected ExportOnlyAEStockingFluidList(MetaMachine holder, int slots) {
            super(holder, slots, ExportOnlyAEStockingFluidSlot::new);
            stockMap.defaultReturnValue(0L);
            for (ExportOnlyAEFluidSlot slot : inventory) {
                ((IMESlot) slot).setOnConfigChanged(new Runnable() {
                    @Override
                    public void run() {
                        onStockInputConfigChanged(onConfigChanged());
                    }
                });
            }
        }

        public void clearStocks() {
            stockMap.clear();
            for (ExportOnlyAEFluidSlot slot : inventory) slot.setStock(null);
        }

        public void syncStock(MEStorage storage) {
            stockMap.clear();
            for (ExportOnlyAEFluidSlot slot : inventory) {
                GenericStack config = slot.getConfig();
                if (config != null && config.what() instanceof AEFluidKey key) {
                    long amount = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
                    if (amount > 0) {
                        slot.setStock(new GenericStack(key, amount));
                        stockMap.addTo(key, amount);
                        continue;
                    }
                }
                slot.setStock(null);
            }
        }

        public boolean onConfigChanged() {
            ObjectOpenHashSet<AEFluidKey> previous = new ObjectOpenHashSet<>(configList);
            configList.clear();
            configIndexList.clear();
            for (int i = 0; i < inventory.length; i++) {
                GenericStack config = inventory[i].getConfig();
                if (config != null && config.what() instanceof AEFluidKey key) {
                    configList.add(key);
                    configIndexList.add(i);
                    previous.remove(key);
                }
            }
            return !previous.isEmpty();
        }

        public boolean hasConfig() { return !configList.isEmpty(); }

        public long getAvailableAmount(AEFluidKey key) {
            IGrid grid = getMainNode().getGrid();
            return grid != null && configList.contains(key)
                    ? grid.getStorageService().getInventory().extract(key, CraftAmountLimits.MAX_MANUAL_CRAFT_AMOUNT,
                            Actionable.SIMULATE, actionSource) : 0L;
        }

        public long extractStock(AEFluidKey key, long amount) {
            IGrid grid = getMainNode().getGrid();
            if (amount <= 0 || grid == null) return 0L;
            long extracted = grid.getStorageService().getInventory().extract(key, amount,
                    Actionable.MODULATE, actionSource);
            if (extracted <= 0) return 0L;
            long left = getAvailableAmount(key);
            if (left <= 0) stockMap.removeLong(key); else stockMap.put(key, left);
            return extracted;
        }
    }

    protected static class ExportOnlyAEStockingFluidSlot extends ExportOnlyAEConfigureFluidSlot {
        public ExportOnlyAEStockingFluidSlot() {}
        public ExportOnlyAEStockingFluidSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
            super(config, stock);
        }
        @Override
        public ExportOnlyAEStockingFluidSlot copy() {
            return new ExportOnlyAEStockingFluidSlot(getConfig(), getStock());
        }
    }
}
