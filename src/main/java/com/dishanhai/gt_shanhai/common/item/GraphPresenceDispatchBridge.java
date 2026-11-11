package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Carries the current GRAPH pattern's presence keys across GTLCore's
 * package-private dispatch policy calls.
 */
public final class GraphPresenceDispatchBridge {

    private static final ThreadLocal<Set<AEKey>> PRESENCE_KEYS = new ThreadLocal<>();

    public static void enterInput(IPatternDetails.IInput input) {
        if (!VirtualPatternEncodingHelper.isPresenceInput(input)) {
            PRESENCE_KEYS.set(Set.of());
            return;
        }
        Set<AEKey> keys = new HashSet<>();
        for (GenericStack possible : input.getPossibleInputs()) {
            if (possible != null && possible.what() != null) {
                keys.add(possible.what());
            }
        }
        PRESENCE_KEYS.set(keys.isEmpty() ? Set.of() : Set.copyOf(keys));
    }

    public static boolean isPresenceKey(AEKey key) {
        Set<AEKey> keys = PRESENCE_KEYS.get();
        return key != null && keys != null && keys.contains(key);
    }

    public static void clear() {
        PRESENCE_KEYS.remove();
    }

    /** GRAPH models its reusable slot separately from the pattern's physical remainder. */
    public static AEKey physicalRemainder(IPatternDetails.IInput input, AEKey key,
            BiFunction<IPatternDetails.IInput, AEKey, AEKey> remainder) {
        return VirtualPatternEncodingHelper.isPresenceInput(input) ? null : remainder.apply(input, key);
    }

    private GraphPresenceDispatchBridge() {
    }
}
