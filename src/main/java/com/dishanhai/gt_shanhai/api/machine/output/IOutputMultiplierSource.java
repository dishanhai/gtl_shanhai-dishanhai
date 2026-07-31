package com.dishanhai.gt_shanhai.api.machine.output;

/**
 * 机器或部件提供的确定性产出倍率来源。
 * <p>
 * 同一个实际来源被主机、模块或部件从不同路径看到时，必须返回同一个 source key，
 * 由聚合器去重，避免同一倍率被重复叠乘。
 */
public interface IOutputMultiplierSource {

    Object getOutputMultiplierSourceKey();

    long getOutputMultiplierContribution();
}
