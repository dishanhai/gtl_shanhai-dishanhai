package com.dishanhai.gt_shanhai.api.machine.part;

/** 山海：区分能力仓的安装条件和计数条件。 */
public interface ShanhaiHubIoPredicate {
    boolean gtShanhai$isIoAdmissionOnly();

    void gtShanhai$setIoAdmissionOnly(boolean admissionOnly);

    void gtShanhai$markHubAbility();

    boolean gtShanhai$defersHubCount();

    void gtShanhai$markParallelAbility();

    boolean gtShanhai$isParallelAbility();
}
