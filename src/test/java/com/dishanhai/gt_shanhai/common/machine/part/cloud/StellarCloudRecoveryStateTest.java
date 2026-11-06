package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：未解碼的待恢復資料也要能實際移出已拆除的 BE，不按未知格式解讀或清空。 */
class StellarCloudRecoveryStateTest {
    @Test void unknownSchemaAndRawInventorySurviveCarrierRoundTrip() {
        CompoundTag pending = new CompoundTag(); pending.putInt("schema",100);
        CompoundTag inventory = new CompoundTag(); inventory.putLong("futureFluidAmount",Long.MAX_VALUE);
        pending.put("futureInventory",inventory); UUID executor = UUID.randomUUID();
        var restored = StellarCloudRecoveryState.load(new StellarCloudRecoveryState(executor,pending).save());
        assertEquals(executor,restored.executor()); assertEquals(pending,restored.pending());
    }
    @Test void itemPayloadCannotMutateStoredPendingDataThroughReferences() {
        CompoundTag pending = new CompoundTag(); pending.putString("slot","original");
        var state = new StellarCloudRecoveryState(UUID.randomUUID(),pending);
        pending.putString("slot","changed"); state.pending().putString("slot","also changed");
        assertEquals("original",state.pending().getString("slot"));
    }
    @Test void missingExecutorCannotBeAcceptedAsAnotherLeaseOwner() {
        assertThrows(IllegalArgumentException.class, () -> StellarCloudRecoveryState.load(new CompoundTag()));
    }
}
