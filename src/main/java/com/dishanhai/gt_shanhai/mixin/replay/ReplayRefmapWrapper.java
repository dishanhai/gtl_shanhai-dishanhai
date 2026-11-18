package com.dishanhai.gt_shanhai.mixin.replay;

import net.minecraftforge.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;

/** Keep Mojang names in development and use the checked-in SRG map in production. */
public final class ReplayRefmapWrapper implements IReferenceMapper {
    private final IReferenceMapper delegate;
    private final boolean production;

    public ReplayRefmapWrapper(MixinEnvironment environment, IReferenceMapper delegate) {
        this(delegate, FMLEnvironment.production);
    }

    ReplayRefmapWrapper(IReferenceMapper delegate, boolean production) {
        this.delegate = delegate;
        this.production = production;
    }

    @Override public boolean isDefault() { return delegate.isDefault(); }
    @Override public String getResourceName() { return delegate.getResourceName(); }
    @Override public String getStatus() { return delegate.getStatus(); }
    @Override public String getContext() { return delegate.getContext(); }
    @Override public void setContext(String context) { delegate.setContext(context); }
    @Override public String remap(String className, String reference) {
        return production ? delegate.remap(className, reference) : reference;
    }
    @Override public String remapWithContext(String context, String className, String reference) {
        return production ? delegate.remapWithContext(context, className, reference) : reference;
    }
}
