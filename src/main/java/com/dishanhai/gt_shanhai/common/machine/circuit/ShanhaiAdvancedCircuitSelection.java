package com.dishanhai.gt_shanhai.common.machine.circuit;

import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 电路设置槽上的多选状态。位 0 是 0 号电路，全选掩码覆盖 0–32 共 33 个。
 * 查找树必须拿到各自的 {@link IntCircuitIngredient}，不能把多个电路压成同一个物品堆。
 */
public final class ShanhaiAdvancedCircuitSelection {

    public static final String TAG = "gt_shanhai_circuits";
    public static final int CIRCUIT_MIN = 0;
    public static final int CIRCUIT_MAX = 32;
    public static final int CIRCUIT_COUNT = 33;
    /** 含 0 号在内的 33 位。 */
    public static final long ALL_CIRCUITS = (1L << CIRCUIT_COUNT) - 1L;

    private ShanhaiAdvancedCircuitSelection() {}

    public static long toggleBit(long mask, int configuration) {
        if (configuration < CIRCUIT_MIN || configuration > CIRCUIT_MAX) return mask;
        return mask ^ (1L << configuration);
    }

    public static boolean isBitSet(long mask, int configuration) {
        return configuration >= CIRCUIT_MIN && configuration <= CIRCUIT_MAX
                && (mask & (1L << configuration)) != 0L;
    }

    public static boolean hasAdvancedMask(ItemStack stack) {
        if (!isCircuitItem(stack)) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG);
    }

    /** 界面显示用。没写过多选标签时，沿用槽里那一颗电路。 */
    public static long currentMask(ItemStack stack) {
        if (!isCircuitItem(stack)) return 0L;
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG)) return tag.getLong(TAG) & ALL_CIRCUITS;
        int configuration = IntCircuitBehaviour.getCircuitConfiguration(stack);
        if (configuration < CIRCUIT_MIN || configuration > CIRCUIT_MAX) return 0L;
        return 1L << configuration;
    }

    public static long maskOf(ItemStack stack) {
        if (!hasAdvancedMask(stack)) return 0L;
        return stack.getTag().getLong(TAG) & ALL_CIRCUITS;
    }

    public static int selectedCount(ItemStack stack) {
        return Long.bitCount(currentMask(stack));
    }

    public static void toggle(ItemStackTransfer slot, int configuration) {
        write(slot, toggleBit(currentMask(slot.getStackInSlot(0)), configuration));
    }

    public static void selectAll(ItemStackTransfer slot) {
        write(slot, ALL_CIRCUITS);
    }

    public static void clear(ItemStackTransfer slot) {
        write(slot, 0L);
    }

    public static void removeSatisfied(List<Ingredient> left, long mask) {
        if (left == null || left.isEmpty() || mask == 0L) return;
        Iterator<Ingredient> iterator = left.iterator();
        while (iterator.hasNext()) {
            int configuration = configurationOf(iterator.next());
            if (isBitSet(mask, configuration)) iterator.remove();
        }
    }

    /**
     * 只展开单格电路槽。返回 null 表示调用方应走原来的单电路逻辑。
     */
    public static List<Object> expandSingleSlot(ItemStackTransfer storage) {
        if (storage == null || storage.getSlots() != 1) return null;
        ItemStack stack = storage.getStackInSlot(0);
        long mask = maskOf(stack);
        if (mask == 0L) return null;
        List<Object> contents = new ArrayList<>(Long.bitCount(mask));
        for (int configuration = CIRCUIT_MIN; configuration <= CIRCUIT_MAX; configuration++) {
            if (isBitSet(mask, configuration)) {
                contents.add(IntCircuitIngredient.circuitInput(configuration));
            }
        }
        return contents;
    }

    public static int configurationOf(Ingredient ingredient) {
        if (ingredient == null) return -1;
        Ingredient inner = ingredient instanceof SizedIngredient sized ? sized.getInner() : ingredient;
        ItemStack[] items = inner.getItems();
        if (items.length == 0 || items[0].isEmpty() || !isCircuitItem(items[0])) return -1;
        return IntCircuitBehaviour.getCircuitConfiguration(items[0]);
    }

    /**
     * 只改客戶端槽裡的顯示堆，不通知機器。重開界面時客戶端槽經常還是空的，標籤圖示會畫成屏障。
     */
    public static void mirror(ItemStackTransfer slot, long mask) {
        mask &= ALL_CIRCUITS;
        if (slot == null || currentMask(slot.getStackInSlot(0)) == mask) return;
        if (mask == 0L) {
            slot.setStackInSlot(0, ItemStack.EMPTY);
            return;
        }
        int lowest = Long.numberOfTrailingZeros(mask);
        ItemStack stack = IntCircuitBehaviour.stack(lowest);
        stack.getOrCreateTag().putLong(TAG, mask);
        slot.setStackInSlot(0, stack);
    }

    private static void write(ItemStackTransfer slot, long mask) {
        mask &= ALL_CIRCUITS;
        if (mask == 0L) {
            slot.setStackInSlot(0, ItemStack.EMPTY);
            slot.onContentsChanged(0);
            return;
        }
        int lowest = Long.numberOfTrailingZeros(mask);
        ItemStack existing = slot.getStackInSlot(0);
        ItemStack stack;
        if (isCircuitItem(existing)) {
            stack = existing.copy();
        } else if (ConfigHolder.INSTANCE.machines.ghostCircuit) {
            stack = IntCircuitBehaviour.stack(lowest);
        } else {
            return;
        }
        IntCircuitBehaviour.setCircuitConfiguration(stack, lowest);
        stack.getOrCreateTag().putLong(TAG, mask);
        slot.setStackInSlot(0, stack);
        slot.onContentsChanged(0);
    }

    private static boolean isCircuitItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && GTItems.INTEGRATED_CIRCUIT.isIn(stack);
    }
}
