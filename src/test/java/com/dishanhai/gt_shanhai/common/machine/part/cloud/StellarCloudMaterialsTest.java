package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：虛擬在場不阻擋退役，實料必須交付成功才移除來源。 */
class StellarCloudMaterialsTest {
    @Test void retainedPresenceIsNotPhysicalButSameKeyExcessIs() {
        var inventory = new Object2LongOpenHashMap<String>(); var virtual = new Object2LongOpenHashMap<String>();
        inventory.put("item",10); virtual.put("item",10);
        assertFalse(StellarCloudMaterials.hasPhysical(inventory, virtual, null));
        inventory.put("item",11); assertTrue(StellarCloudMaterials.hasPhysical(inventory, virtual, null));
    }
    @Test void legacyVirtualCircuitDoesNotBecomeARealRefund() {
        var inventory = new Object2LongOpenHashMap<String>(); inventory.put("circuit",1);
        assertFalse(StellarCloudMaterials.hasPhysical(inventory,new Object2LongOpenHashMap<>(),"circuit"));
        inventory.put("circuit",2); assertTrue(StellarCloudMaterials.hasPhysical(inventory,new Object2LongOpenHashMap<>(),"circuit"));
    }
    @Test void failedDeliveryKeepsAllExactAmounts() {
        Map<String,Long> buffer = new HashMap<>(Map.of("item",Long.MAX_VALUE,"fluid",8L));
        assertFalse(StellarCloudMaterials.deliverRefund(buffer, contents -> false));
        assertEquals(Map.of("item",Long.MAX_VALUE,"fluid",8L),buffer);
    }
    @Test void successfulDeliveryKeepsLongPrecisionAndOnlyClearsDeliveredSnapshot() {
        Map<String,Long> buffer = new HashMap<>(Map.of("item",Long.MAX_VALUE,"fluid",8L));
        assertTrue(StellarCloudMaterials.deliverRefund(buffer, contents -> {
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE),contents.get("item"));
            assertEquals(BigInteger.valueOf(8),contents.get("fluid"));
            buffer.put("fluid",11L); return true;
        }));
        assertEquals(Map.of("fluid",3L),buffer);
    }
    @Test void sinkExceptionCannotEraseSource() {
        Map<String,Long> buffer = new HashMap<>(Map.of("item",9L));
        assertThrows(IllegalStateException.class, () -> StellarCloudMaterials.deliverRefund(buffer, contents -> {
            throw new IllegalStateException("unavailable");
        }));
        assertEquals(9,buffer.get("item"));
    }
}
