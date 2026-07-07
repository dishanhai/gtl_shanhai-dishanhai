package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

public final class VirtualItemProviderHelper {

    public static final String TARGET_ITEM_KEY = "targetItem";
    public static final String LEGACY_TARGET_CIRCUIT_KEY = "targetCircuit";
    public static final String GTO_NAMESPACE_KEY = "m";
    public static final String GTO_PATH_KEY = "n";
    public static final String GTO_TAG_KEY = "t";
    public static final String MARKED_KEY = "marked";

    private static final String LEGACY_ROOT_KEY = "gt_shanhai";
    private static final String LEGACY_CIRCUIT_KEY = "circuit";
    private static final String LEGACY_CONFIG_KEY = "config";
    private static final String GT_CIRCUIT_CONFIG_KEY = "Configuration";

    public static ItemStack createBoundProvider(ItemStack target) {
        if (target == null || target.isEmpty()) return ItemStack.EMPTY;
        ItemStack provider = new ItemStack(GTDishanhaiMod.VIRTUAL_ITEM_PROVIDER.get(), 1);
        bindTarget(provider, target);
        provider.getOrCreateTag().putBoolean(MARKED_KEY, true);
        return provider;
    }

    public static ItemStack createUnmarkedBoundProvider(ItemStack target) {
        if (target == null || target.isEmpty()) return ItemStack.EMPTY;
        ItemStack provider = new ItemStack(GTDishanhaiMod.VIRTUAL_ITEM_PROVIDER.get(), 1);
        bindTarget(provider, target, false);
        return provider;
    }

    public static ItemStack createEmptyMarkedProvider() {
        ItemStack provider = new ItemStack(GTDishanhaiMod.VIRTUAL_ITEM_PROVIDER.get(), 1);
        CompoundTag tag = provider.getOrCreateTag();
        tag.putBoolean(MARKED_KEY, true);
        tag.putString(GTO_NAMESPACE_KEY, "minecraft");
        tag.putString(GTO_PATH_KEY, "air");
        tag.remove(TARGET_ITEM_KEY);
        tag.remove(LEGACY_TARGET_CIRCUIT_KEY);
        tag.remove(GTO_TAG_KEY);
        return provider;
    }

    public static boolean bindTarget(ItemStack provider, ItemStack target) {
        return bindTarget(provider, target, false);
    }

    public static boolean bindTarget(ItemStack provider, ItemStack target, boolean marked) {
        if (!isProviderItem(provider) || target == null || target.isEmpty() || isProviderItem(target)) {
            return false;
        }

        ItemStack stored = normalizeTarget(target, false);
        CompoundTag tag = provider.getOrCreateTag();
        tag.put(TARGET_ITEM_KEY, stored.save(new CompoundTag()));
        if (marked) {
            tag.putBoolean(MARKED_KEY, true);
        } else {
            tag.remove(MARKED_KEY);
        }

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stored.getItem());
        if (id != null) {
            tag.putString(GTO_NAMESPACE_KEY, id.getNamespace());
            tag.putString(GTO_PATH_KEY, id.getPath());
            if (stored.hasTag()) {
                tag.put(GTO_TAG_KEY, stored.getTag().copy());
            } else {
                tag.remove(GTO_TAG_KEY);
            }
        }
        tag.remove(LEGACY_TARGET_CIRCUIT_KEY);
        return true;
    }

    public static void clearTarget(ItemStack provider) {
        if (provider == null || provider.isEmpty() || !provider.hasTag()) return;
        CompoundTag tag = provider.getTag();
        tag.remove(TARGET_ITEM_KEY);
        tag.remove(LEGACY_TARGET_CIRCUIT_KEY);
        tag.remove(GTO_NAMESPACE_KEY);
        tag.remove(GTO_PATH_KEY);
        tag.remove(GTO_TAG_KEY);
        tag.remove(MARKED_KEY);
        tag.remove(LEGACY_ROOT_KEY);
        if (tag.isEmpty()) {
            provider.setTag(null);
        }
    }

    public static boolean isProviderItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == GTDishanhaiMod.VIRTUAL_ITEM_PROVIDER.get();
    }

    public static boolean isBoundProvider(ItemStack stack) {
        return isProviderItem(stack) && !getTarget(stack).isEmpty();
    }

    public static boolean isMarkedProvider(ItemStack stack) {
        return isProviderItem(stack) && stack.hasTag() && stack.getTag().getBoolean(MARKED_KEY);
    }

    public static boolean isAutoWrapExcluded(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        String itemId = id.toString();
        for (String excluded : DShanhaiConfig.COMMON.virtualProviderAutoWrapExclusions.get()) {
            if (itemId.equals(excluded)) return true;
        }
        return false;
    }

    public static ItemStack getTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return ItemStack.EMPTY;
        CompoundTag tag = stack.getTag();
        if (tag == null) return ItemStack.EMPTY;

        if (tag.contains(TARGET_ITEM_KEY)) {
            return normalizeTarget(ItemStack.of(tag.getCompound(TARGET_ITEM_KEY)), false);
        }

        if (tag.contains(LEGACY_TARGET_CIRCUIT_KEY)) {
            return normalizeTarget(ItemStack.of(tag.getCompound(LEGACY_TARGET_CIRCUIT_KEY)));
        }

        ItemStack gtoTarget = readGtoTarget(tag);
        if (!gtoTarget.isEmpty()) return gtoTarget;

        ItemStack legacyTarget = readLegacyCircuitTarget(tag);
        if (!legacyTarget.isEmpty()) return legacyTarget;

        return ItemStack.EMPTY;
    }

    public static ItemStack resolveForRecipe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        if (isProviderItem(stack)) return getTarget(stack);
        return normalizeTarget(stack);
    }

    public static ItemStack normalizeTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        return normalizeTarget(stack, true);
    }

    public static AEItemKey createProviderKeyForTarget(AEKey targetKey) {
        if (!(targetKey instanceof AEItemKey itemKey)) return null;
        ItemStack target = normalizeTarget(itemKey.toStack());
        ItemStack provider = createBoundProvider(target);
        return provider.isEmpty() ? null : AEItemKey.of(provider);
    }

    public static boolean matchesTargetKey(AEKey availableKey, AEKey targetKey) {
        if (availableKey == null || targetKey == null) return false;
        if (availableKey.equals(targetKey)) return true;
        if (!(availableKey instanceof AEItemKey availableItemKey) || !(targetKey instanceof AEItemKey targetItemKey)) {
            return false;
        }

        ItemStack availableStack = availableItemKey.toStack();
        ItemStack targetStack = targetItemKey.toStack();
        if (isBoundProvider(availableStack)) {
            availableStack = getTarget(availableStack);
        }
        return matchesTargetStack(availableStack, targetStack);
    }

    private static boolean matchesTargetStack(ItemStack available, ItemStack target) {
        if (available == null || target == null || available.isEmpty() || target.isEmpty()) return false;
        ItemStack normalizedAvailable = normalizeTarget(available);
        ItemStack normalizedTarget = normalizeTarget(target);
        return normalizedAvailable.getItem() == normalizedTarget.getItem()
                && Objects.equals(normalizedAvailable.getTag(), normalizedTarget.getTag());
    }

    private static ItemStack normalizeTarget(ItemStack stack, boolean forceSingleCount) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stack.copy();
        if (forceSingleCount) {
            result.setCount(1);
        }
        normalizeLegacyCircuitConfig(result);
        result = normalizeProgrammedCircuit(result);
        return result;
    }

    private static ItemStack normalizeProgrammedCircuit(ItemStack stack) {
        if (!IntCircuitBehaviour.isIntegratedCircuit(stack) || !stack.hasTag()) return stack;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(GT_CIRCUIT_CONFIG_KEY)) return stack;
        int config = tag.getInt(GT_CIRCUIT_CONFIG_KEY);
        if (config < 0 || config > IntCircuitBehaviour.CIRCUIT_MAX) return stack;
        ItemStack normalized = IntCircuitBehaviour.stack(config);
        normalized.setCount(stack.getCount());
        return normalized;
    }

    private static ItemStack readGtoTarget(CompoundTag tag) {
        if (!tag.contains(GTO_NAMESPACE_KEY) || !tag.contains(GTO_PATH_KEY)) return ItemStack.EMPTY;
        ResourceLocation id = new ResourceLocation(tag.getString(GTO_NAMESPACE_KEY), tag.getString(GTO_PATH_KEY));
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(item, 1);
        if (tag.contains(GTO_TAG_KEY)) {
            result.setTag(tag.getCompound(GTO_TAG_KEY).copy());
        }
        return normalizeTarget(result);
    }

    private static ItemStack readLegacyCircuitTarget(CompoundTag tag) {
        if (!tag.contains(LEGACY_ROOT_KEY)) return ItemStack.EMPTY;
        CompoundTag data = tag.getCompound(LEGACY_ROOT_KEY);
        if (!data.contains(LEGACY_CIRCUIT_KEY)) return ItemStack.EMPTY;

        ResourceLocation id = ResourceLocation.tryParse(data.getString(LEGACY_CIRCUIT_KEY));
        if (id == null) return ItemStack.EMPTY;
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) return ItemStack.EMPTY;

        ItemStack result = new ItemStack(item, 1);
        if (data.contains(LEGACY_CONFIG_KEY)) {
            setCircuitConfigIfPossible(result, data.getInt(LEGACY_CONFIG_KEY));
        }
        return normalizeTarget(result);
    }

    private static void normalizeLegacyCircuitConfig(ItemStack stack) {
        if (!stack.hasTag()) return;
        CompoundTag tag = stack.getTag();
        if (tag.contains(LEGACY_CONFIG_KEY) && !tag.contains(GT_CIRCUIT_CONFIG_KEY)) {
            setCircuitConfigIfPossible(stack, tag.getInt(LEGACY_CONFIG_KEY));
        }
    }

    private static void setCircuitConfigIfPossible(ItemStack stack, int config) {
        if (config < 0 || config > IntCircuitBehaviour.CIRCUIT_MAX) return;
        if (IntCircuitBehaviour.isIntegratedCircuit(stack)) {
            IntCircuitBehaviour.setCircuitConfiguration(stack, config);
        }
    }

    private VirtualItemProviderHelper() {}
}
