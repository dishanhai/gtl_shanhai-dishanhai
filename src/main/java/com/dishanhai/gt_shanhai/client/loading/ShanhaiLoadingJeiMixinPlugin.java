package com.dishanhai.gt_shanhai.client.loading;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import org.apache.logging.log4j.LogManager;
import java.util.List;
import java.util.Set;

/** 山海：等其他 Mixin handler 合併後，才對可選的 JEI Optimizer 接補幀等待。 */
public final class ShanhaiLoadingJeiMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.endsWith(".ShanhaiLoadingJeiWaitMixin")) return true;
        try {
            MixinService.getService().getBytecodeProvider().getClassNode("com.piglinmine.jeioptimizer.mixin.ElementSearchMixin");
            return true;
        } catch (Exception absent) { return false; }
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode target, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode target, String mixinClassName, IMixinInfo info) {
        if (!mixinClassName.endsWith(".ShanhaiLoadingJeiWaitMixin")) return;
        if (ShanhaiLoadingJeiWaitPatch.apply(target) == 1) {
            LogManager.getLogger("gt_shanhai:loading").info("山海載入：已接入 JEI Optimizer 響應式等待 {}", name);
        } else {
            LogManager.getLogger("gt_shanhai:loading").warn("山海載入：JEI Optimizer 等待結構不符，保留原始行為 {}", name);
        }
    }
}
