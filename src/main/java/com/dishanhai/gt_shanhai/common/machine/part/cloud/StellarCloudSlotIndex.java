package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** 山海：通配重建只能回收自己的空地址，不能挪動其他原件的執行材料。 */
public final class StellarCloudSlotIndex {
    private final Map<String, Integer> addresses = new HashMap<>();
    private final BitSet occupied = new BitSet();
    public int allocate(String identity) {
        return addresses.computeIfAbsent(identity, key -> {
            int slot = occupied.nextClearBit(0);
            if (slot > 65535) throw new IllegalStateException("Stellar extended slot address space exhausted");
            occupied.set(slot); return slot;
        });
    }
    public boolean restore(String identity, int address) {
        if (address < 0 || address > 65535) return false;
        Integer existing = addresses.get(identity);
        if (existing != null) return existing == address;
        if (occupied.get(address)) return false;
        addresses.put(identity, address); occupied.set(address); return true;
    }
    public boolean release(String identity, boolean busy) {
        if (busy) return false;
        Integer slot = addresses.remove(identity);
        if (slot == null) return false;
        occupied.clear(slot); return true;
    }
    public int extent() { return occupied.length(); }
    public boolean hasFreeSlot() { return occupied.nextClearBit(0) <= 65535; }
}
