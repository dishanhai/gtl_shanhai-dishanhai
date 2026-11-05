package com.dishanhai.gt_shanhai.integration.enhancedcore;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.FMLLoader;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** 山海：Mixin 選擇階段只讀位元碼，避免提早載入可選模組或 Minecraft 類別。 */
public final class EnhancedCoreCloudMixinPlugin implements IMixinConfigPlugin {

    private Boolean cloudAvailable;

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (!name.startsWith("EnhancedCoreCloud") && !name.startsWith("EnhancedCorePrimordial")) return true;
        if (cloudAvailable == null) {
            var mods = FMLLoader.getLoadingModList();
            cloudAvailable = EnhancedCoreCloudApi.supports(
                    mods != null && mods.getModFileById(EnhancedCoreCloudCompat.MOD_ID) != null,
                    method -> {
                        try {
                            ClassNode owner = MixinService.getService().getBytecodeProvider()
                                    .getClassNode(method.owner());
                            return owner.methods.stream().anyMatch(found -> found.name.equals(method.name())
                                    && found.desc.equals(method.descriptor()));
                        } catch (Exception unavailable) {
                            return false;
                        }
                    });
            if (!cloudAvailable && mods != null && mods.getModFileById(EnhancedCoreCloudCompat.MOD_ID) != null) {
                org.apache.logging.log4j.LogManager.getLogger("gt_shanhai:enhancedcore")
                        .warn("Enhancedcore cloud API differs from the supported bridge; cloud compatibility is disabled");
            }
        }
        return cloudAvailable;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode target, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode target, String mixinClassName, IMixinInfo info) {}
}
