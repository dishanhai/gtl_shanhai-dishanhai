package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import java.util.Locale;

/** 永恒格雷工坊模块类型。先作为主机状态容器，具体模块机器后续接入。 */
public enum EternalGregTechWorkshopModuleType {
    FUSION("fusion"),
    EYE_OF_HARMONY("eye_of_harmony"),
    EXTRA("extra");

    private final String id;

    EternalGregTechWorkshopModuleType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static EternalGregTechWorkshopModuleType byId(String id) {
        if (id == null) return null;
        String normalized = id.toLowerCase(Locale.ROOT);
        for (EternalGregTechWorkshopModuleType type : values()) {
            if (type.id.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
