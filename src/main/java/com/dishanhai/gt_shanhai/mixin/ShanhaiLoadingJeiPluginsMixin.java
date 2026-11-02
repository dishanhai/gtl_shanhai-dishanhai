package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mezz.jei.api.IModPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.Consumer;

/** 山海：沿用 JEI 原例外處理，在各插件註冊邊界補幀。 */
@Mixin(targets = "mezz.jei.library.load.PluginCaller", remap = false)
public abstract class ShanhaiLoadingJeiPluginsMixin {
    @WrapOperation(method = "callOnPlugins", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"), remap = false)
    private static void shanhai$pluginCheckpoint(Consumer<IModPlugin> action, Object plugin, Operation<Void> original) {
        ShanhaiLoadingClient.checkpoint("JEI 插件：" + plugin.getClass().getName());
        try { original.call(action, plugin); }
        finally { ShanhaiLoadingClient.checkpoint("JEI 插件註冊 ..."); }
    }
}
