package com.dishanhai.gt_shanhai.jei;

import com.dishanhai.gt_shanhai.api.DShanhaiFluidTooltipAPI;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

@JeiPlugin
public class ShanhaiJEIPlugin implements IModPlugin {

    private static IRecipeRegistration cachedRegistration = null;
    private static IPlatformFluidHelper<?> fluidHelper = null;
    private static boolean registered = false;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // 保留：以后可能需要注册 JEI 信息时启用
        // cachedRegistration = registration;
        // fluidHelper = registration.getJeiHelpers().getPlatformFluidHelper();
    }

    /** 由 DShanhaiFluidTooltipAPI.register() 在 KubeJS 脚本注册流体后调用 */
    @SuppressWarnings("unchecked")
    public static void tryRegisterNow() {
        if (registered) return;
        if (cachedRegistration == null || fluidHelper == null) return;

        String[] ids = DShanhaiFluidTooltipAPI.getRegisteredIds();
        if (ids == null || ids.length == 0) return;

        IIngredientType<?> fluidType = fluidHelper.getFluidIngredientType();
        int count = 0;

        for (String fluidId : ids) {
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(fluidId));
            if (fluid == null) continue;

            String[] lines = DShanhaiFluidTooltipAPI.getEntryLines(fluidId);
            if (lines == null || lines.length == 0) continue;

            List<Component> components = new ArrayList<>();
            for (String line : lines) {
                try {
                    components.add(ShanhaiTextAPI.inline(line));
                } catch (Exception e) {
                    components.add(Component.literal(line));
                }
            }
            if (!components.isEmpty()) {
                Object fluidStack = fluidHelper.create(fluid, fluidHelper.bucketVolume());
                addInfoRaw(cachedRegistration, fluidStack, fluidType, components.toArray(new Component[0]));
                count++;
            }
        }

        registered = true;
        dev.latvian.mods.kubejs.KubeJS.LOGGER.info("[山海流体] JEI 流体 info 已注册: {} 种流体", count);
    }

    /** 类型擦除桥接，绕过 wildcard capture 编译错误 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addInfoRaw(IRecipeRegistration reg, Object stack, IIngredientType type, Component[] comps) {
        reg.addIngredientInfo(stack, type, comps);
    }
}
