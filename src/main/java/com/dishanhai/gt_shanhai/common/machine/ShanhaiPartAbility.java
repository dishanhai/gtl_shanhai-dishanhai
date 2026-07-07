package com.dishanhai.gt_shanhai.common.machine;

import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;

/**
 * 山海模组自定义 PartAbility。
 * 遵循 GTLPartAbility（gtlcore）和 GTLAddPartAbility（gtladditions）的扩展模式。
 */
public class ShanhaiPartAbility {

    /** 可编程仓 — 接受编程电路或虚拟物品提供器，只向配方系统提供内部电路 */
    public static final PartAbility PROGRAMMABLE_HATCH = new PartAbility("programmable_hatch");

    /** 原初模块 — 多功能模块化机器，安装到引擎主机模块位 */
    public static final PartAbility PRIMORDIAL_MODULE = new PartAbility("primordial_module");

    /** ME 磁盘仓室 — 接入 AE2 网络的存储元件挂载点 */
    public static final PartAbility ME_DISK_HATCH = new PartAbility("me_disk_hatch");

    private ShanhaiPartAbility() {}
}
