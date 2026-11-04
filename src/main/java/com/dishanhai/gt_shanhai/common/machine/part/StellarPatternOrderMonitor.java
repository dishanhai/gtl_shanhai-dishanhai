package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.networking.crafting.ICraftingLink;

import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 山海：告警發出後繼續盯同一條 AE 訂單，直到完成或取消。 */
public final class StellarPatternOrderMonitor {
    private static final Map<String, ICraftingLink> OPEN = new HashMap<>();

    private StellarPatternOrderMonitor() {}

    public static void watch(StellarPatternCraftingContext.Order order) {
        if (order == null || order.link() == null) return;
        if (StellarPatternOrderInfo.stateOf(order.link()).terminal()) return;
        String id = order.link().getCraftingID() == null ? "" : order.link().getCraftingID().toString();
        if (id.isBlank()) return;
        OPEN.put(id, order.link());
    }

    public static void poll(MinecraftServer server) {
        if (server == null || OPEN.isEmpty()) return;
        Iterator<Map.Entry<String, ICraftingLink>> iterator = OPEN.entrySet().iterator();
        while (iterator.hasNext()) {
            ICraftingLink link = iterator.next().getValue();
            StellarPatternOrderInfo.State state = StellarPatternOrderInfo.stateOf(link);
            if (!state.terminal()) continue;
            iterator.remove();
            StellarPatternWarningHistory.observeTerminal(server, link, state, state.resolved() ? 0L : -1L);
        }
    }

    public static void clear() {
        OPEN.clear();
    }
}
