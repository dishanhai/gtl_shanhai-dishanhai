package com.dishanhai.gt_shanhai.api.machine.part;

import com.gregtechceu.gtceu.api.misc.EnergyContainerList;

import java.util.function.LongSupplier;

/**
 * 标记接口：实现了此接口的机器部件是维护仓，
 * 安装后使主机的所有配方要求被无视（EU/耗时/研究等）
 */
public interface IMaintenanceBypassPart {

    /** 是否绕过电压/算力要求 */
    default boolean isVoltageBypassEnabled() { return true; }

    /** 是否绕过环境条件（洁净室/维度等） */
    default boolean isConditionBypassEnabled() { return true; }

    /** 返回设定的绕过电压值（EU/t）。默认 Long.MAX_VALUE 表示完全绕过 */
    default long getBypassVoltage() { return Long.MAX_VALUE; }

    /**
     * 工厂方法：创建无限能源容器列表。
     * 放在此处而非 mixin 直接 new，避免 Forge ModuleClassLoader
     * 跨模块类加载时找不到我们的类。
     */
    default EnergyContainerList createInfinityEnergyContainer(EnergyContainerList original, LongSupplier voltageSupplier) {
        return new com.dishanhai.gt_shanhai.common.machine.trait.InfinityEnergyContainerList(original, voltageSupplier);
    }
}
