package com.dishanhai.gt_shanhai.api.machine.part;

/** 山海：普通 I/O 能力允许枢纽安装，但枢纽不提供材料仓名额。 */
public interface ShanhaiHubIoPredicate {
    boolean gtShanhai$isIoAdmissionOnly();

    void gtShanhai$setIoAdmissionOnly(boolean admissionOnly);
}
