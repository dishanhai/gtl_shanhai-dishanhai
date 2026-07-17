package com.dishanhai.gt_shanhai.jei;

import com.dishanhai.gt_shanhai.api.DShanhaiFluidTooltipAPI;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.client.gui.shop.JeiItemOrderHolder;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
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

    /**
     * JEI 运行时就绪回调：把 JEI 最终成品摄取管理器里的全物品列表（已按 JEI 自身顺序排好、
     * 已排除隐藏黑名单、含本模组通过 {@code DShanhaiJEIPlugin} 额外注册的摄取物如 SDA 包）
     * 转成纯 {@link ItemStack} 列表存进 {@link JeiItemOrderHolder}，供 {@code MultiPickerScreen}
     * 「多选资源」物品排序用（见反馈：物品排序要用 JEI 的基类而不是自己拼创造栏顺序）。
     *
     * <p>本类只在 JEI 装了、由 JEI 自己的插件扫描器实例化时才会被触碰，直接用 JEI API 类型安全；
     * {@link JeiItemOrderHolder} 是纯 Java 中转站，未装 JEI 时 {@code MultiPickerScreen}
     * 读到的恒为 null，按原逻辑回退，不受影响。</p>
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        try {
            java.util.Collection<ItemStack> all = runtime.getIngredientManager().getAllIngredients(VanillaTypes.ITEM_STACK);
            JeiItemOrderHolder.set(new ArrayList<>(all));
        } catch (Throwable t) {
            // JEI API 版本差异/未知异常：静默降级，MultiPickerScreen 走原有创造栏顺序兜底
        }
        // 塞运行时引用给书签桥接（见 JeiBookmarkBridge），供 MultiPickerScreen 的「JEI书签」浏览模式反射读取
        JeiBookmarkBridge.setRuntime(runtime);
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
