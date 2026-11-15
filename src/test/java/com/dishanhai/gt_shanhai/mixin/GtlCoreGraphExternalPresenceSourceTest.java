package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GtlCoreGraphExternalPresenceSourceTest {

    @Test
    void graphPlanningCapturesSupplyPresenceBeforeTheWorkerRuns() throws Exception {
        String configs = MixinConfigs.readAll();
        for (String suffix : new String[] { "", "$Snapshot", "$RequestWork", "$Cpu" }) {
            assertTrue(configs.contains('"' + "GtlCoreGraphVirtualPresenceMixin" + suffix + '"'),
                    "GRAPH 必须注册完整的外部在场路径 " + suffix);
        }
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin/GtlCoreGraphVirtualPresenceMixin.java"));
        assertTrue(source.contains("VirtualProviderSoftLocks.snapshotSupplyPresence(this.grid)"));
        assertTrue(source.contains("this.gtShanhai$supplyMode ? this.gtShanhai$supplyPresence"));
        assertTrue(source.contains("GraphVirtualPresence.externalize(compiler, this.prepared.bindings()"));
        assertTrue(source.contains("GraphVirtualPresence.missingExternalPresence(supplied, grid, source)"));
        String adapter = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin/GtlCoreGraphPresenceExecutionAdapterMixin.java"));
        assertTrue(adapter.contains("GraphVirtualPresence.isExternalInput(pattern, recipe, inputSlot)"));
        assertTrue(adapter.contains("cir.getReturnValue(), this.host.grid(), this.host.source()"));
    }
}
