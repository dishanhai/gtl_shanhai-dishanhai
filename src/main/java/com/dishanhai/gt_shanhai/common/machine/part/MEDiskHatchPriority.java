package com.dishanhai.gt_shanhai.common.machine.part;

final class MEDiskHatchPriority {

    private MEDiskHatchPriority() {
    }

    static int forSlot(int basePriority, int slot) {
        long value = (long) basePriority - slot;
        return value < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
    }

    static int add(int priority, int delta) {
        long value = (long) priority + delta;
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }
}
