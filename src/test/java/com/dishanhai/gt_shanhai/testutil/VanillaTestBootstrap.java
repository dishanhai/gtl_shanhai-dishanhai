package com.dishanhai.gt_shanhai.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class VanillaTestBootstrap {
    private VanillaTestBootstrap() {}

    public static synchronized void initialize() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            // Neo/Forge's transforming loader is not present in the plain test JVM.
            // Forge 1.20.1 discovers this listener only after populating vanilla registries.
            if (!(error.getCause() instanceof RuntimeException listenerFailure)
                    || !(listenerFailure.getCause() instanceof NoSuchMethodException constructor)
                    || !"net.minecraftforge.network.NetworkEvent.<init>()".equals(constructor.getMessage())) {
                throw error;
            }
        }
    }
}
