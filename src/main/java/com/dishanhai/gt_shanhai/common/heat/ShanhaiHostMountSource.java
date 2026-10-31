package com.dishanhai.gt_shanhai.common.heat;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import java.util.List;

/** 山海：歸墟提供給直接及遠端模組的掛載能力；普通引擎沒有自己的供源。 */
public interface ShanhaiHostMountSource {
    List<ShanhaiHeatGate.SlotContent> hostExtraMountContents();
    boolean canProvideHostMounts();

    static ShanhaiHostMountSource resolve(PrimordialOmegaEngineMachine host) {
        if (host == null || !host.isFormed() || host.isInValid()) return null;
        ShanhaiHostMountSource source = host instanceof ShanhaiHostMountSource direct
                ? direct : host.getLinkedGuixuHost();
        return source != null && source.canProvideHostMounts() ? source : null;
    }
}
