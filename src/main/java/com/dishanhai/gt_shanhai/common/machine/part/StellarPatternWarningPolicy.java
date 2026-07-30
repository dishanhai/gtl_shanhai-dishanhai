package com.dishanhai.gt_shanhai.common.machine.part;

import java.util.BitSet;
import java.util.Collection;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public final class StellarPatternWarningPolicy {

    private StellarPatternWarningPolicy() {}

    public static boolean isWrongHost(String patternTypeId, boolean metadataKnown, boolean stellar,
            Collection<String> hostTypeIds, BiPredicate<String, String> shared) {
        if (!metadataKnown || !stellar || patternTypeId == null || patternTypeId.isBlank()) {
            return false;
        }
        if (hostTypeIds != null) {
            for (String hostTypeId : hostTypeIds) {
                if (patternTypeId.equals(hostTypeId) || shared.test(patternTypeId, hostTypeId)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static String encodeWarningSlots(BitSet slots) {
        return slots.stream()
                .mapToObj(Integer::toString)
                .collect(Collectors.joining(","));
    }

    public static BitSet decodeWarningSlots(String encoded) {
        BitSet result = new BitSet();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        for (String token : encoded.split(",")) {
            result.set(Integer.parseInt(token));
        }
        return result;
    }
}
