package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingProfiler;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 山海：沿用原本的 executor 與 future，只包裝載入工作以直接量測。 */
@Mixin(value = SimpleReloadInstance.class, remap = false)
public abstract class ShanhaiLoadingReloadProfilerMixin {
    @Group(name = "shanhaiReloadFactory", min = 1, max = 1)
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", remap = false), require = 0, remap = false)
    private CompletableFuture<?> shanhai$profileNamed(@Coerce Object factory,
            PreparableReloadListener.PreparationBarrier barrier, ResourceManager resources,
            PreparableReloadListener listener, Executor prepare, Executor apply, Operation<CompletableFuture<?>> original) {
        return ShanhaiLoadingProfiler.listener(listener, prepare, apply,
                (p, a) -> original.call(factory, barrier, resources, listener, p, a));
    }

    @Group(name = "shanhaiReloadFactory", min = 1, max = 1)
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;m_10863_(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", remap = false), require = 0, remap = false)
    private CompletableFuture<?> shanhai$profileSrg(@Coerce Object factory,
            PreparableReloadListener.PreparationBarrier barrier, ResourceManager resources,
            PreparableReloadListener listener, Executor prepare, Executor apply, Operation<CompletableFuture<?>> original) {
        return ShanhaiLoadingProfiler.listener(listener, prepare, apply,
                (p, a) -> original.call(factory, barrier, resources, listener, p, a));
    }
}
