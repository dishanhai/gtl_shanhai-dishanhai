package com.dishanhai.gt_shanhai.integration.enhancedcore;

import net.minecraftforge.fml.ModList;

import org.objectweb.asm.Type;

/** 山海：共用入口只經此無 Enhancedcore 型別簽章的門面檢查相容能力。 */
public final class EnhancedCoreCloudCompat {

    public static final String MOD_ID = "gtl_enhancedcore";

    private EnhancedCoreCloudCompat() {}

    public static boolean isAvailable() {
        return Available.VALUE;
    }

    public static EnhancedCoreCloudUploadBatch.Result tryUpload(
            net.minecraft.server.level.ServerPlayer player, appeng.menu.me.items.PatternEncodingTermMenu menu,
            java.util.List<net.minecraft.world.item.ItemStack> patterns, java.util.function.BooleanSupplier consumeBlank) {
        return isAvailable() ? EnhancedCoreCloudUpload.tryUpload(player, menu, patterns, consumeBlank) : null;
    }

    private static final class Available {
        private static final boolean VALUE = EnhancedCoreCloudApi.supports(
                ModList.get() != null && ModList.get().isLoaded(MOD_ID), method -> {
                    try {
                        Class<?> owner = Class.forName(method.owner(), false,
                                EnhancedCoreCloudCompat.class.getClassLoader());
                        for (java.lang.reflect.Method declared : owner.getDeclaredMethods()) {
                            if (declared.getName().equals(method.name())
                                    && Type.getMethodDescriptor(declared).equals(method.descriptor())) return true;
                        }
                    } catch (ClassNotFoundException unavailable) {
                        return false;
                    }
                    return false;
                });
    }
}
