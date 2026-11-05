package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.data.pack.GTDynamicDataPack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** 山海：每個 namespace 僅列舉自己的資源，避免重複列舉與建立 supplier。 */
@Mixin(value = GTDynamicDataPack.class, remap = false)
public abstract class ShanhaiLoadingDataPackMixin {
    @Shadow @Final protected static Map<ResourceLocation, byte[]> DATA;

    @Inject(method = {"getNamespaces", "m_5698_"}, at = @At("RETURN"), cancellable = true, remap = false)
    private void shanhai$namespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (type != PackType.SERVER_DATA) return;
        Set<String> namespaces = new HashSet<>(cir.getReturnValue());
        for (ResourceLocation id : DATA.keySet()) if (id != null) namespaces.add(id.getNamespace());
        cir.setReturnValue(namespaces);
    }

    @WrapOperation(method = {"listResources", "m_8031_"}, at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;forEach(Ljava/util/function/Consumer;)V", remap = false), remap = false)
    private void shanhai$namespace(Stream<ResourceLocation> resources, Consumer<ResourceLocation> output,
                                    Operation<Void> original, PackType type, String namespace, String path,
                                    PackResources.ResourceOutput resourceOutput) {
        // 保留 GT 原有 path 篩選、getResource 覆寫及即時 DATA；不快取資源內容。
        original.call(resources.filter(id -> namespace.equals(id.getNamespace())), output);
    }
}
