package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * GTLCore 的 handleSpecialPart 遇到 IParallelHatch 会提前 return。
 * 终焉聚合枢纽同时实现并行、维护和数据访问接口，因此在工作机 upDate 完成后
 * 补回后两项字段，保留 GTLCore 原本识别出的 parallelHatch。
 * 此修复最初用于奥术星图；接口冲突存在于所有工作机，故共享同一条补齐路径。
 */
@Mixin(value = WorkableMultiblockMachine.class, remap = false, priority = 1500)
public abstract class ArcanicAstrographMaintenanceCapabilityMixin {

    @Unique
    private static final Logger GT_SHANHAI$LOG =
            LoggerFactory.getLogger("gt_shanhai:hub_capabilities");

    @Inject(method = "upDate", at = @At("TAIL"), remap = false)
    private void gtShanhai$restoreAstrographHubCapabilities(CallbackInfo ci) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;
        Object self = this;
        if (!(self instanceof IMultiController controller) || !controller.isFormed()) {
            return;
        }
        for (var part : controller.getParts()) {
            if (part instanceof DShanhaiMaintenanceHatchMachine hatch) {
                boolean maintenance = gtShanhai$setField(self, "maintenanceMachine", hatch);
                boolean data = gtShanhai$setField(self, "dataAccessHatch", hatch);
                GT_SHANHAI$LOG.debug(
                        "[hubCapabilities] hub=true maintenance={} data={} parallelPreserved=true",
                        maintenance, data);
                return;
            }
        }
    }

    @Unique
    private static boolean gtShanhai$setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                GT_SHANHAI$LOG.warn("[hubCapabilities] failed to set {}", name, e);
                return false;
            }
        }
        GT_SHANHAI$LOG.warn("[hubCapabilities] field not found: {}", name);
        return false;
    }
}
