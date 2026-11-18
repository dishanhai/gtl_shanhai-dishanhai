package com.dishanhai.gt_shanhai.common.shop;

import appeng.api.config.Actionable;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Immutable simulation-only stock reservation, preserving the original identity (dishanhai). */
public final class ShopAutoCraftActionSource implements IActionSource {
    private final IActionSource delegate;
    private final Map<AEKey, Long> reserved;
    private final Map<AEKey, Long> stock;

    public ShopAutoCraftActionSource(IActionSource delegate, Map<AEKey, Long> reserved) {
        this(delegate, reserved, null);
    }

    public ShopAutoCraftActionSource(IActionSource delegate, Map<AEKey, Long> reserved, Map<AEKey, Long> stock) {
        this.delegate = delegate;
        this.reserved = Map.copyOf(reserved);
        this.stock = stock == null ? null : Map.copyOf(stock);
    }

    public Map<AEKey, Long> reserved() { return reserved; }

    /** Called during native simulation construction, before the async calculation is scheduled. */
    public Map<AEKey, Long> stockSnapshot(IStorageService storage) {
        return stock == null ? captureStock(storage, delegate) : stock;
    }

    public static Map<AEKey, Long> captureStock(IStorageService storage, IActionSource source) {
        Map<AEKey, Long> cached = new LinkedHashMap<>();
        for (var stack : storage.getCachedInventory()) cached.put(stack.getKey(), stack.getLongValue());
        var inventory = storage.getInventory();
        return ShopCraftingStockSnapshot.capture(cached,
                (key, amount) -> inventory.extract(key, amount, Actionable.SIMULATE, source));
    }

    @Override
    public Optional<Player> player() { return delegate.player(); }

    @Override
    public Optional<IActionHost> machine() { return delegate.machine(); }

    @Override
    public <T> Optional<T> context(Class<T> type) { return delegate.context(type); }
}
