package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.gregtechceu.gtceu.integration.ae2.utils.KeyStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Iterator;

@Mixin(value = KeyStorage.class, remap = false)
public abstract class KeyStorageInsertThrottleMixin {

    private static final long MAX_INSERT_PER_KEY = 1000000000L;

    @Shadow
    public abstract Iterator<Object2LongMap.Entry<AEKey>> iterator();

    @Shadow
    public abstract void onChanged();

    /**
     * Limits huge pending ME output batches so a single autoIO tick cannot stall the server thread.
     */
    @Overwrite(remap = false)
    public void insertInventory(MEStorage inventory, IActionSource source) {
        Iterator<Object2LongMap.Entry<AEKey>> it = iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Object2LongMap.Entry<AEKey> entry = it.next();
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            long tryInsert = Math.min(amount, MAX_INSERT_PER_KEY);
            long inserted = inventory.insert(key, tryInsert, Actionable.MODULATE, source);
            if (inserted > 0) {
                changed = true;
                if (inserted >= amount) {
                    it.remove();
                } else {
                    entry.setValue(amount - inserted);
                }
            }
        }
        if (changed) {
            onChanged();
        }
    }
}
