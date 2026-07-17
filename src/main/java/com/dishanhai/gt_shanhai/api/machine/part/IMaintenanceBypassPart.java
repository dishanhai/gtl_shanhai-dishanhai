package com.dishanhai.gt_shanhai.api.machine.part;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
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

    /**
     * 遍历多方块所有部件，判断是否存在开启了电压/算力绕过的维护仓部件。
     * 放在此处（非 mixin 包）而非 mixin 类里的静态方法，供 mixin 安全调用——
     * mixin 包内的类一旦被"编织"进目标类的字节码后又被目标类反向调用，会触发
     * Mixin 的 IllegalClassLoadError（mixin 包下的类不允许被正常途径加载）。
     */
    static boolean anyVoltageBypassEnabled(IMultiController controller) {
        if (controller == null || !controller.isFormed()) return false;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof IMaintenanceBypassPart bp && bp.isVoltageBypassEnabled()) return true;
        }
        return false;
    }
}
