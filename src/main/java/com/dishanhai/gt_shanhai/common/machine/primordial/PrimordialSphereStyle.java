package com.dishanhai.gt_shanhai.common.machine.primordial;

/**
 * 原始终焉引擎的球体渲染风格。
 * <p>
 * ordinal() 即持久化/同步值，禁止重排或插入中间项。
 */
public enum PrimordialSphereStyle {

    /** 宇宙渲染器：鸿蒙微型宇宙（恒星 + 三行星轨道 + 外层星空壳）。 */
    UNIVERSE,

    /** 中子星渲染：复用伪神之煅炉的星体渲染管线。 */
    NEUTRON_STAR;

    private static final PrimordialSphereStyle[] VALUES = values();

    public static PrimordialSphereStyle byIndex(int index) {
        return (index >= 0 && index < VALUES.length) ? VALUES[index] : UNIVERSE;
    }
}
