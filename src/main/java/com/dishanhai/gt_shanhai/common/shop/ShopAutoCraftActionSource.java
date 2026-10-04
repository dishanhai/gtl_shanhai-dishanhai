package com.dishanhai.gt_shanhai.common.shop;

import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Optional;

/** Immutable simulation-only stock reservation, preserving the original identity (dishanhai). */
public final class ShopAutoCraftActionSource implements IActionSource {
    private final IActionSource delegate;
    private final Map<AEKey, Long> reserved;

    public ShopAutoCraftActionSource(IActionSource delegate, Map<AEKey, Long> reserved) {
        this.delegate = delegate;
        this.reserved = Map.copyOf(reserved);
    }

    public Map<AEKey, Long> reserved() { return reserved; }

    @Override
    public Optional<Player> player() { return delegate.player(); }

    @Override
    public Optional<IActionHost> machine() { return delegate.machine(); }

    @Override
    public <T> Optional<T> context(Class<T> type) { return delegate.context(type); }
}
