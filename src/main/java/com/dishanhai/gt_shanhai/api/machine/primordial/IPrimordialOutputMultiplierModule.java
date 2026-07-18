package com.dishanhai.gt_shanhai.api.machine.primordial;

/**
 * 原初模块产出倍率能力。模块返回期望倍率，宿主统一将其钳制到 1..1000。
 */
public interface IPrimordialOutputMultiplierModule {

    int getCurrentOutputMultiplier();
}
