package com.dishanhai.gt_shanhai.common.machine.part;

import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** A rewind restores historical state and must not also hand off current material. */
final class StellarRemovalPolicy {
    private StellarRemovalPolicy() {}

    static void handoff(boolean replayWrite, Runnable externalHandoff) {
        if (!replayWrite) externalHandoff.run();
    }

    static boolean canDeliver(boolean worldRunning, boolean saveBlocked) {
        return worldRunning && !saveBlocked;
    }

    static void clearInventory(boolean suppressDrops, Runnable clearSilently, Runnable clearNormally) {
        if (suppressDrops) clearSilently.run();
        else clearNormally.run();
    }

    static void clearSlots(int count, IntPredicate occupied, IntConsumer clear, Runnable changed) {
        for (int i = 0; i < count; i++) {
            if (!occupied.test(i)) continue;
            clear.accept(i);
            changed.run();
        }
    }
}
