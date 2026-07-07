package com.dishanhai.gt_shanhai.client;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * 捕获 JEI 运行时引用，供 RecipeSyncPacket 客户端刷新用。
 */
@JeiPlugin
public class ShanhaiJEIPlugin implements IModPlugin {

    private static IJeiRuntime jeiRuntime;

    public static IJeiRuntime getRuntime() {
        return jeiRuntime;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation("gt_shanhai", "runtime_jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
    }
}
