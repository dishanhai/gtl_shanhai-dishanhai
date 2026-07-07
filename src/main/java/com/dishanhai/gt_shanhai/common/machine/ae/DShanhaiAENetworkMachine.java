package com.dishanhai.gt_shanhai.common.machine.ae;

import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;

/**
 * 山海 AE 网络机器的通用状态接口。
 *
 * <p>不同 AE 仓室已经继承了各自的 GTCEu/山海基类，不能再强行抽象出 Java 基类。
 * 这里用接口收敛 Jade/诊断需要的只读状态，后续其它 gt_shanhai AE 仓室可直接实现。</p>
 */
public interface DShanhaiAENetworkMachine extends IGridConnectedMachine {

    default String getAeJadeKind() {
        return "AE 网络设备";
    }

    default int getAeTotalSlots() {
        return -1;
    }

    default int getAeConfiguredSlots() {
        return -1;
    }

    default int getAeStockedSlots() {
        return -1;
    }

    default int getAePendingSlots() {
        return -1;
    }

    default int getAeActiveJobs() {
        return -1;
    }

    default String getAeOutputModeName() {
        return "";
    }

    default int getAeFailedKeyCooldowns() {
        return -1;
    }

    default int getAeNetworkCooldownTicks() {
        return -1;
    }

    default String getAeFlushBudgetText() {
        return "";
    }

    default boolean isAeServiceCacheReady() {
        return false;
    }
}
