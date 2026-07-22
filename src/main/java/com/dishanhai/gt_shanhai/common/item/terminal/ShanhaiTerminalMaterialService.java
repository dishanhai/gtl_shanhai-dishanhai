package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalAeBinding.Context;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

public final class ShanhaiTerminalMaterialService {

    public record Preflight(boolean success, Map<String, Long> missing, String reason) {}
    public record RequestTarget(AEKey key, long amount, String displayName) {}

    public final class BuildBatch {
        private final Map<AEItemKey, ReservedMaterial> reserved;
        private boolean closed;

        private BuildBatch(Map<AEItemKey, ReservedMaterial> reserved) {
            this.reserved = reserved;
        }

        public ItemStack takeOne(ItemStack wanted) {
            if (closed) return ItemStack.EMPTY;
            AEItemKey key = AEItemKey.of(wanted);
            if (key == null) return ItemStack.EMPTY;
            ReservedMaterial material = reserved.get(key);
            if (material == null || material.remaining <= 0) return ItemStack.EMPTY;
            material.remaining--;
            return material.representative.copyWithCount(1);
        }

        public boolean refundRemaining(ServerPlayer player, Context ae) {
            if (closed) return true;
            closed = true;
            for (ReservedMaterial material : reserved.values()) {
                if (material.remaining <= 0) continue;
                long remaining = material.remaining;
                material.remaining = 0;
                if (!refundAmount(player, ae, material.representative, remaining)) return false;
            }
            return true;
        }
    }

    private static final class ReservedMaterial {
        private final ItemStack representative;
        private long remaining;

        private ReservedMaterial(ItemStack representative, long remaining) {
            this.representative = representative.copyWithCount(1);
            this.remaining = remaining;
        }
    }

    public Preflight preflight(ShanhaiStructurePlan plan, ServerPlayer player, Context ae) {
        Map<String, Long> missing = new LinkedHashMap<>();
        for (Map.Entry<AEKey, Long> entry : shortages(plan, player, ae).entrySet()) {
            missing.put(entry.getKey().getDisplayName().getString(), entry.getValue());
        }
        return missing.isEmpty()
                ? new Preflight(true, Map.of(), "")
                : new Preflight(false, Collections.unmodifiableMap(missing), "材料不足");
    }

    public Map<AEKey, Long> shortages(ShanhaiStructurePlan plan, ServerPlayer player, Context ae) {
        Map<AEItemKey, Long> required = new LinkedHashMap<>();
        Map<AEItemKey, ItemStack> representatives = new LinkedHashMap<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (!entry.requiresMaterial()) continue;
            AEItemKey itemKey = AEItemKey.of(entry.desired());
            if (itemKey == null) continue;
            required.merge(itemKey, 1L, ShanhaiTerminalMaterialService::saturatedAdd);
            representatives.putIfAbsent(itemKey, entry.desired());
        }
        Map<AEKey, Long> result = new LinkedHashMap<>();
        for (Map.Entry<AEItemKey, Long> entry : required.entrySet()) {
            ItemStack representative = representatives.get(entry.getKey());
            long available = saturatedAdd(countPlayer(player, representative), countAe(ae, representative));
            if (available < entry.getValue()) result.put(entry.getKey(), entry.getValue() - available);
        }
        return Collections.unmodifiableMap(result);
    }

    public List<RequestTarget> requestableShortages(ShanhaiStructurePlan plan, ServerPlayer player, Context ae) {
        Map<AEKey, RequestTarget> result = new LinkedHashMap<>();
        for (Map.Entry<AEKey, Long> shortage : shortages(plan, player, ae).entrySet()) {
            if (!(shortage.getKey() instanceof AEItemKey itemKey)) continue;
            AEKey requestKey = resolveCraftingKey(ae, itemKey.toStack());
            if (requestKey == null) requestKey = shortage.getKey();
            RequestTarget previous = result.get(requestKey);
            if (previous == null) {
                result.put(requestKey, new RequestTarget(
                        requestKey, shortage.getValue(), shortage.getKey().getDisplayName().getString()));
            } else {
                result.put(requestKey, new RequestTarget(
                        requestKey, saturatedAdd(previous.amount(), shortage.getValue()), previous.displayName()));
            }
        }
        return List.copyOf(result.values());
    }

    public int candidatePriority(ServerPlayer player, Context ae, ItemStack candidate) {
        if (countPlayer(player, candidate) > 0) return 0;
        if (countAe(ae, candidate) > 0) return 1;
        if (resolveCraftingKey(ae, candidate) != null) return 2;
        return 3;
    }

    public ShanhaiStructurePlanner.CandidatePriority prioritizer(ServerPlayer player, Context ae) {
        Map<AEItemKey, Integer> cache = new HashMap<>();
        return candidate -> {
            AEItemKey key = AEItemKey.of(candidate);
            if (key == null) return 3;
            return cache.computeIfAbsent(key, ignored -> candidatePriority(player, ae, candidate));
        };
    }

    public BuildBatch prepareBuildBatch(ServerPlayer player, Context ae, ShanhaiStructurePlan plan) {
        Map<AEItemKey, Long> required = new LinkedHashMap<>();
        Map<AEItemKey, ItemStack> representatives = new LinkedHashMap<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (!entry.requiresMaterial()) continue;
            AEItemKey key = AEItemKey.of(entry.desired());
            if (key == null) continue;
            required.merge(key, 1L, ShanhaiTerminalMaterialService::saturatedAdd);
            representatives.putIfAbsent(key, entry.desired());
        }
        Map<AEItemKey, ReservedMaterial> reserved = new LinkedHashMap<>();
        for (Map.Entry<AEItemKey, Long> entry : required.entrySet()) {
            ItemStack representative = representatives.get(entry.getKey());
            long need = entry.getValue();
            long fromPlayer = extractFromPlayer(player, representative, need);
            long fromAe = bulkExtractFromAe(ae, representative, need - fromPlayer);
            long total = saturatedAdd(fromPlayer, fromAe);
            if (total > 0) {
                reserved.put(entry.getKey(), new ReservedMaterial(representative, total));
            }
            if (total < need) {
                new BuildBatch(reserved).refundRemaining(player, ae);
                return null;
            }
        }
        return new BuildBatch(reserved);
    }

    public ItemStack takeOne(ServerPlayer player, Context ae, ItemStack wanted) {
        ItemStack fromPlayer = extractFromPlayer(player, wanted);
        if (!fromPlayer.isEmpty()) return fromPlayer;
        return extractFromAe(ae, wanted);
    }

    private long extractFromPlayer(ServerPlayer player, ItemStack wanted, long amount) {
        long extracted = 0;
        while (extracted < amount) {
            ItemStack stack = extractFromPlayer(player, wanted);
            if (stack.isEmpty()) break;
            extracted = saturatedAdd(extracted, stack.getCount());
        }
        return Math.min(extracted, amount);
    }

    private ItemStack extractFromPlayer(ServerPlayer player, ItemStack wanted) {
        IItemHandler root = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        return extractRecursive(root, wanted, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private long bulkExtractFromAe(Context ae, ItemStack wanted, long amount) {
        if (ae == null || amount <= 0) return 0;
        AEItemKey key = AEItemKey.of(wanted);
        if (key == null) return 0;
        long available = ae.storage().extract(key, amount, Actionable.SIMULATE, ae.source());
        if (available < amount) return 0;
        return ae.storage().extract(key, amount, Actionable.MODULATE, ae.source());
    }

    private ItemStack extractFromAe(Context ae, ItemStack wanted) {
        if (ae == null) return ItemStack.EMPTY;
        AEItemKey key = AEItemKey.of(wanted);
        if (key == null || ae.storage().extract(key, 1, Actionable.SIMULATE, ae.source()) < 1) {
            return ItemStack.EMPTY;
        }
        return ae.storage().extract(key, 1, Actionable.MODULATE, ae.source()) == 1
                ? wanted.copyWithCount(1) : ItemStack.EMPTY;
    }

    public boolean canReturn(ServerPlayer player, Context ae, ItemStack stack) {
        if (stack.isEmpty()) return true;
        IItemHandler root = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        ItemStack playerRemainder = root == null
                ? stack.copy() : ItemHandlerHelper.insertItemStacked(root, stack.copy(), true);
        if (playerRemainder.isEmpty()) return true;
        AEItemKey key = AEItemKey.of(playerRemainder);
        return ae != null && key != null
                && ae.storage().insert(key, playerRemainder.getCount(), Actionable.SIMULATE, ae.source())
                >= playerRemainder.getCount();
    }

    public boolean canReturnAll(ServerPlayer player, Context ae, List<ItemStack> stacks) {
        IItemHandler root = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        ItemStackHandler shadow = new ItemStackHandler(root == null ? 0 : root.getSlots()) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return root != null && root.isItemValid(slot, stack);
            }

            @Override
            public int getSlotLimit(int slot) {
                return root == null ? 0 : root.getSlotLimit(slot);
            }
        };
        if (root != null) {
            for (int slot = 0; slot < root.getSlots(); slot++) {
                shadow.setStackInSlot(slot, root.getStackInSlot(slot).copy());
            }
        }
        Map<AEItemKey, Long> aeRemainders = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(shadow, stack.copy(), false);
            if (remainder.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(remainder);
            if (key == null) return false;
            aeRemainders.merge(key, (long) remainder.getCount(), ShanhaiTerminalMaterialService::saturatedAdd);
        }
        if (aeRemainders.isEmpty()) return true;
        if (ae == null) return false;
        for (Map.Entry<AEItemKey, Long> entry : aeRemainders.entrySet()) {
            if (ae.storage().insert(entry.getKey(), entry.getValue(), Actionable.SIMULATE, ae.source())
                    < entry.getValue()) return false;
        }
        return true;
    }

    public boolean canStoreDismantled(ServerPlayer player, Context ae, List<ItemStack> stacks) {
        Map<AEItemKey, Long> itemAmounts = aggregateItemAmounts(stacks);
        if (itemAmounts == null) return false;
        if (itemAmounts.isEmpty()) return true;
        if (ae != null) return canInsertAllToAe(ae, itemAmounts);
        return player.getServer() != null && canReturnWithoutAeUsingSda(player, stacks);
    }

    public boolean storeDismantled(ServerPlayer player, Context ae, List<ItemStack> stacks) {
        Map<AEItemKey, Long> itemAmounts = aggregateItemAmounts(stacks);
        if (itemAmounts == null) return false;
        if (itemAmounts.isEmpty()) return true;
        if (ae != null) return insertAllToAe(ae, itemAmounts);
        return returnWithoutAeUsingSda(player, stacks);
    }

    public boolean refund(ServerPlayer player, Context ae, ItemStack stack) {
        ItemStack remainder = returnToPlayer(player, stack);
        remainder = returnToAe(ae, remainder);
        return remainder.isEmpty();
    }

    private ItemStack returnToPlayer(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        IItemHandler root = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        return root == null ? stack.copy() : ItemHandlerHelper.insertItemStacked(root, stack.copy(), false);
    }

    private ItemStack returnToAe(Context ae, ItemStack stack) {
        if (ae == null || stack.isEmpty()) return stack;
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) return stack;
        long accepted = ae.storage().insert(key, stack.getCount(), Actionable.SIMULATE, ae.source());
        if (accepted <= 0) return stack;
        long inserted = ae.storage().insert(key, Math.min(accepted, stack.getCount()),
                Actionable.MODULATE, ae.source());
        ItemStack remainder = stack.copy();
        remainder.shrink((int) Math.min(inserted, remainder.getCount()));
        return remainder;
    }

    private boolean refundAmount(ServerPlayer player, Context ae, ItemStack representative, long amount) {
        long remaining = amount;
        int maxStackSize = Math.max(1, representative.getMaxStackSize());
        while (remaining > 0) {
            int count = (int) Math.min(remaining, maxStackSize);
            if (!refund(player, ae, representative.copyWithCount(count))) return false;
            remaining -= count;
        }
        return true;
    }

    private boolean canInsertAllToAe(Context ae, Map<AEItemKey, Long> amounts) {
        if (ae == null) return false;
        for (Map.Entry<AEItemKey, Long> entry : amounts.entrySet()) {
            if (ae.storage().insert(entry.getKey(), entry.getValue(), Actionable.SIMULATE, ae.source())
                    < entry.getValue()) return false;
        }
        return true;
    }

    private boolean insertAllToAe(Context ae, Map<AEItemKey, Long> amounts) {
        if (!canInsertAllToAe(ae, amounts)) return false;
        for (Map.Entry<AEItemKey, Long> entry : amounts.entrySet()) {
            if (ae.storage().insert(entry.getKey(), entry.getValue(), Actionable.MODULATE, ae.source())
                    < entry.getValue()) return false;
        }
        return true;
    }

    private boolean canReturnWithoutAeUsingSda(ServerPlayer player, List<ItemStack> stacks) {
        Map<AEKey, BigInteger> allAsSda = aggregateSdaAmounts(stacks);
        if (allAsSda == null) return false;
        if (isAtSdaThreshold(allAsSda)) return true;
        Map<AEKey, BigInteger> remainders = simulatePlayerRemainders(player, stacks);
        return remainders != null;
    }

    private boolean returnWithoutAeUsingSda(ServerPlayer player, List<ItemStack> stacks) {
        Map<AEKey, BigInteger> allAsSda = aggregateSdaAmounts(stacks);
        if (allAsSda == null) return false;
        if (isAtSdaThreshold(allAsSda)) return packDismantledAsSda(player, allAsSda);

        Map<AEKey, BigInteger> sdaRemainders = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack remainder = returnToPlayer(player, stack);
            if (remainder.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(remainder);
            if (key == null) return false;
            sdaRemainders.merge(key, BigInteger.valueOf(remainder.getCount()), BigInteger::add);
        }
        return sdaRemainders.isEmpty() || packDismantledAsSda(player, sdaRemainders);
    }

    private Map<AEKey, BigInteger> simulatePlayerRemainders(ServerPlayer player, List<ItemStack> stacks) {
        IItemHandler root = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        ItemStackHandler shadow = shadowInventory(root);
        Map<AEKey, BigInteger> remainders = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(shadow, stack.copy(), false);
            if (remainder.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(remainder);
            if (key == null) return null;
            remainders.merge(key, BigInteger.valueOf(remainder.getCount()), BigInteger::add);
        }
        return remainders;
    }

    private ItemStackHandler shadowInventory(IItemHandler root) {
        ItemStackHandler shadow = new ItemStackHandler(root == null ? 0 : root.getSlots()) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return root != null && root.isItemValid(slot, stack);
            }

            @Override
            public int getSlotLimit(int slot) {
                return root == null ? 0 : root.getSlotLimit(slot);
            }
        };
        if (root != null) {
            for (int slot = 0; slot < root.getSlots(); slot++) {
                shadow.setStackInSlot(slot, root.getStackInSlot(slot).copy());
            }
        }
        return shadow;
    }

    private Map<AEItemKey, Long> aggregateItemAmounts(List<ItemStack> stacks) {
        Map<AEItemKey, Long> result = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return null;
            result.merge(key, (long) stack.getCount(), ShanhaiTerminalMaterialService::saturatedAdd);
        }
        return result;
    }

    private Map<AEKey, BigInteger> aggregateSdaAmounts(List<ItemStack> stacks) {
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return null;
            result.merge(key, BigInteger.valueOf(stack.getCount()), BigInteger::add);
        }
        return result;
    }

    private boolean isAtSdaThreshold(Map<AEKey, BigInteger> amounts) {
        BigInteger total = BigInteger.ZERO;
        for (BigInteger amount : amounts.values()) {
            if (amount != null && amount.signum() > 0) total = total.add(amount);
        }
        return total.compareTo(BigInteger.valueOf(DShanhaiConfig.COMMON.shopSdaPackThreshold.get())) >= 0;
    }

    private boolean packDismantledAsSda(ServerPlayer player, Map<AEKey, BigInteger> amounts) {
        if (player == null || amounts == null || amounts.isEmpty()) return true;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        UUID uuid = UUID.randomUUID();
        ItemStack sda = new ItemStack(GTDishanhaiMod.SUPER_DISK_ARRAY.get());
        sda.getOrCreateTag().putUUID(SuperDiskArrayInventory.TAG_UUID, uuid);
        DShanhaiVirtualCellSavedData.get(server)
                .updateCellBig(uuid, "sda", SuperDiskArrayItem.TOTAL_BYTES, amounts);
        if (!player.getInventory().add(sda)) player.drop(sda, false);
        return true;
    }

    private long countPlayer(ServerPlayer player, ItemStack wanted) {
        IItemHandler root = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        return countRecursive(root, wanted, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private long countAe(Context ae, ItemStack wanted) {
        if (ae == null) return 0;
        AEKey key = AEItemKey.of(wanted);
        return key == null ? 0 : ae.storage().extract(key, Long.MAX_VALUE, Actionable.SIMULATE, ae.source());
    }

    private AEKey resolveCraftingKey(Context ae, ItemStack wanted) {
        if (ae == null || wanted.isEmpty()) return null;
        AEItemKey key = AEItemKey.of(wanted);
        if (key == null) return null;
        var crafting = ae.grid().getCraftingService();
        if (crafting.isCraftable(key)) return key;
        AEKey fuzzy = crafting.getFuzzyCraftable(key.dropSecondary(), candidate ->
                candidate instanceof AEItemKey itemKey && itemKey.getItem() == key.getItem());
        return fuzzy == null ? null : fuzzy;
    }

    private long countRecursive(IItemHandler handler, ItemStack wanted, Set<IItemHandler> visited) {
        if (handler == null || !visited.add(handler)) return 0;
        long count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (ItemStack.isSameItemSameTags(stack, wanted)) count = saturatedAdd(count, stack.getCount());
            IItemHandler nested = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
            count = saturatedAdd(count, countRecursive(nested, wanted, visited));
        }
        return count;
    }

    private ItemStack extractRecursive(IItemHandler handler, ItemStack wanted, Set<IItemHandler> visited) {
        if (handler == null || !visited.add(handler)) return ItemStack.EMPTY;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (ItemStack.isSameItemSameTags(stack, wanted)) {
                ItemStack extracted = handler.extractItem(slot, 1, false);
                if (!extracted.isEmpty()) return extracted;
            }
            IItemHandler nested = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
            ItemStack extracted = extractRecursive(nested, wanted, visited);
            if (!extracted.isEmpty()) return extracted;
        }
        return ItemStack.EMPTY;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
