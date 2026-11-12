package com.dishanhai.gt_shanhai.common.ae2;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.cgse.core.GraphJobRuntime;
import org.cgse.core.ReplanCoordinator;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuAccess;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuController;

/** 山海：只让 GRAPH 重新规划未发配的部分，保留引擎已经登记的投料和回流。 */
public interface GraphCraftingRedispatch {

    boolean gtShanhai$requestRedispatch();

    static boolean request(GraphCpuController controller) {
        return (Object) controller instanceof GraphCraftingRedispatch access && access.gtShanhai$requestRedispatch();
    }

    static GraphCraftingRedispatch forNativeCpu(CraftingCPUCluster cluster) {
        try {
            if (!((Object) cluster.craftingLogic instanceof GraphCpuAccess access)) return null;
            GraphCpuController controller = access.gtlcore$graphController();
            return controller != null && controller.ownsTask()
                    && (Object) controller instanceof GraphCraftingRedispatch redispatch ? redispatch : null;
        } catch (NoClassDefFoundError unavailable) {
            // 支持尚未引入 GRAPH 的 GTLCore。其他链接错误继续抛出。
            if (!"org/gtlcore/gtlcore/integration/ae2/graph/GraphCpuAccess".equals(unavailable.getMessage())
                    && !"org/gtlcore/gtlcore/integration/ae2/graph/GraphCpuController".equals(unavailable.getMessage())) {
                throw unavailable;
            }
            return null;
        }
    }

    static boolean canRetry(GraphJobRuntime<?> job) {
        return job != null && job.state() == GraphJobRuntime.State.RUNNING && !job.suspended()
                && job.remainingDelivery() > 0 && !job.pendingRuns().isEmpty();
    }

    static void retryPending(GraphJobRuntime<?> job, ReplanCoordinator<?, ?> replanning, Runnable startReplan) {
        if (!canRetry(job)) return;
        if (replanning.waitingForDependency()) {
            replanning.dependencyChanged();
        } else if (replanning.checkpoint() == null) {
            try {
                // 新版引擎会拒绝在尚有未确认的可变状态返还时生成库存预测。
                job.forecastInventory();
            } catch (IllegalStateException unresolvedReturns) {
                return;
            }
            startReplan.run();
        }
    }
}
