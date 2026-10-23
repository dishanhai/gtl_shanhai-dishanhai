package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import net.minecraft.server.level.ServerLevel;

/** 山海：主機／模組共用成型流程，等待主執行緒時不重複探測與排隊。 */
final class PrimordialStructureCheck {
    private final PrimordialStructureCheckGate gate = new PrimordialStructureCheckGate();

    void cancel() {
        gate.cancel();
    }

    void asyncCheck(MultiblockControllerMachine machine, long period) {
        if (!machine.getMultiblockState().hasError() && machine.isFormed()) return;
        if ((machine.getHolder().getOffset() + period) % 4 != 0) return;
        if (!(machine.getLevel() instanceof ServerLevel level) || machine.isInValid()) return;
        Object ticket = gate.tryStart();
        if (ticket == null) return;

        boolean submitted = false;
        boolean wasFormed = machine.isFormed();
        try {
            if (!machine.getPatternLock().tryLock()) return;
            try {
                if (!gate.isCurrent(ticket) || !machine.checkPattern()) return;
            } finally {
                machine.getPatternLock().unlock();
            }
            level.getServer().execute(() -> {
                try {
                    if (!gate.isCurrent(ticket) || machine.isInValid() || machine.getLevel() != level) return;
                    machine.getPatternLock().lock();
                    try {
                        if (!gate.isCurrent(ticket) || (!wasFormed && machine.isFormed())) return;
                        // 探測成功到真正成型之間可能拆除、旋轉或卸載；主執行緒必須重新驗證。
                        if (!machine.checkPattern()) return;
                        machine.setFlipped(machine.getMultiblockState().isNeededFlip());
                        machine.onStructureFormed();
                        MultiblockWorldSavedData worldData = MultiblockWorldSavedData.getOrCreate(level);
                        worldData.addMapping(machine.getMultiblockState());
                        worldData.removeAsyncLogic(machine);
                    } finally {
                        machine.getPatternLock().unlock();
                    }
                } finally {
                    gate.finish(ticket);
                }
            });
            submitted = true;
        } finally {
            if (!submitted) gate.finish(ticket);
        }
    }
}
