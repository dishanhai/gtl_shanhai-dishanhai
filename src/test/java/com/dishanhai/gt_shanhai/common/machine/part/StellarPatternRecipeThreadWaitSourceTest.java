package com.dishanhai.gt_shanhai.common.machine.part;

// Author: dishanhai

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StellarPatternRecipeThreadWaitSourceTest {
    private static final Path MAIN = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test void watchReadsSharedThreadStateInsteadOfTreatingParallelAsRecipeThreads() throws Exception {
        String watch = Files.readString(MAIN.resolve("common/machine/part/StellarPatternStuckWatch.java"));
        assertTrue(watch.contains("getRecipeThreadSnapshot()"), "原生與原初主機都必須提供批次快照");
        assertTrue(watch.contains("StellarPatternThreadWaitPolicy"));
        assertTrue(watch.contains("threadAssessment.parallelCapacity()"));
        assertTrue(watch.contains("threadAssessment.reservedParallel()"));
        assertTrue(watch.contains("recipe_thread_capacity"));
        assertTrue(watch.contains("assessment_thread_wait"));
        assertTrue(watch.contains("host.aggregateAdmissionBlocked"));
        assertTrue(watch.contains("assessment_batch_wait"));
    }

    @Test void bothSchedulersCountCompletedBatchesWithoutRunningAnotherRecipeSearch() throws Exception {
        for (String file : new String[]{"common/machine/thread/NativeRecipeThreadRuntime.java",
                "common/machine/primordial/PrimordialThreadedRecipeLogic.java"}) {
            String source = Files.readString(MAIN.resolve(file));
            assertTrue(source.contains("getRecipeThreadSnapshot()"), file);
            assertTrue(source.contains("completedThreadBatches++")
                    || source.contains("completedThreadBatches += frame.batchCount;"), file);
        }
        String adapter = Files.readString(MAIN.resolve("mixin/RecipeThreadLogicMixin.java"));
        assertTrue(adapter.contains("shanhai$recipeThreads().getRecipeThreadSnapshot()"));
    }

    @Test void lightweightSnapshotCannotReconnectModulesAndUsesTheSchedulerAdmissionFormula() throws Exception {
        String source = Files.readString(MAIN.resolve("common/machine/primordial/PrimordialThreadedRecipeLogic.java"));
        String snapshot = source.substring(source.indexOf("public final RecipeThreadSnapshot getRecipeThreadSnapshot()"),
                source.indexOf("public final CompoundTag getRecipeThreadDisplay"));
        assertFalse(snapshot.contains("canModuleWork()"), "模組可工作檢查會嘗試重連，不是唯讀 getter");
        assertTrue(snapshot.contains("threads.admissionShare("));
        String nativeSource = Files.readString(MAIN.resolve("common/machine/thread/NativeRecipeThreadRuntime.java"));
        assertTrue(nativeSource.contains("scheduler.admissionShare("));
    }
}
