package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.world.item.ItemStack;

public final class ShanhaiSdaPatternTransfer {

    public static TransferResult transfer(
            SuperDiskArrayInventory source, InternalInventory target) {
        if (target == null) return new TransferResult(0, 0, false);
        return transfer(source, new PatternTransferTarget() {
            @Override
            public TransferDecision simulate(AEKey key) {
                if (!(key instanceof AEItemKey itemKey)) return TransferDecision.IGNORE;
                ItemStack pattern = itemKey.toStack();
                if (pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)) {
                    return TransferDecision.IGNORE;
                }
                return target.simulateAdd(pattern).isEmpty()
                        ? TransferDecision.ACCEPT : TransferDecision.REJECT;
            }

            @Override
            public boolean insert(AEKey key) {
                return key instanceof AEItemKey itemKey
                        && target.addItems(itemKey.toStack()).isEmpty();
            }
        });
    }

    static TransferResult transfer(
            SuperDiskArrayInventory source, PatternTransferTarget target) {
        if (source == null || target == null) {
            return new TransferResult(0, 0, false);
        }

        KeyCounter available = new KeyCounter();
        source.getAvailableStacks(available);
        int transferred = 0;
        int failed = 0;
        boolean foundPattern = false;

        source.beginBatchChanges();
        try {
            for (Object2LongMap.Entry<AEKey> entry : available) {
                AEKey key = entry.getKey();
                long remaining = entry.getLongValue();
                while (remaining-- > 0L) {
                    TransferDecision decision = target.simulate(key);
                    if (decision == TransferDecision.IGNORE) break;
                    foundPattern = true;
                    if (decision == TransferDecision.REJECT) break;

                    long extracted = source.extract(
                            key, 1L, Actionable.MODULATE, IActionSource.empty());
                    if (extracted != 1L) {
                        failed++;
                        break;
                    }

                    if (target.insert(key)) {
                        transferred++;
                        continue;
                    }

                    source.restoreExtractedKey(key);
                    failed++;
                    break;
                }
            }
            return new TransferResult(transferred, failed, foundPattern);
        } finally {
            source.endBatchChanges();
        }
    }

    public record TransferResult(int transferred, int failed, boolean foundPattern) {}

    enum TransferDecision {
        IGNORE,
        REJECT,
        ACCEPT
    }

    interface PatternTransferTarget {
        TransferDecision simulate(AEKey key);

        boolean insert(AEKey key);
    }

    private ShanhaiSdaPatternTransfer() {}
}
