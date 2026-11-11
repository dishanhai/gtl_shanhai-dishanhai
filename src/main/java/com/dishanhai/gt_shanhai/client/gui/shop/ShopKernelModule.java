package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.kernel.api.ModuleContext;
import com.dishanhai.sh_better_ui.kernel.api.ModuleDescriptor;
import com.dishanhai.sh_better_ui.kernel.api.ScopeHandle;
import com.dishanhai.sh_better_ui.kernel.api.ScreenService;
import com.dishanhai.sh_better_ui.kernel.api.UIApis;
import com.dishanhai.sh_better_ui.kernel.api.UIEventPhase;
import com.dishanhai.sh_better_ui.kernel.api.UIModule;
import com.dishanhai.sh_better_ui.kernel.api.UiId;
import com.dishanhai.sh_better_ui.kernel.api.UiVersion;
import com.dishanhai.sh_better_ui.kernel.api.VersionRange;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Set;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

/** 山海：由 SH-Better-UI 发现并管理商店原生页面的生命周期。 */
public final class ShopKernelModule implements UIModule {
    private static final UiId MODULE = new UiId(MOD_ID, "shop");
    private static final UiId SCREEN_TREE = new UiId(MOD_ID, "shop/screen");

    private ModuleContext module;
    private ScreenService screens;
    private ScopeHandle tree;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder().id(MODULE).version(UiVersion.parse("1.0.0"))
                .platforms(Set.of(ModuleDescriptor.Platform.FORGE_1_20_1))
                .requiredCapability(UIApis.NATIVE_SCREEN, VersionRange.ANY)
                .build();
    }

    @Override
    public void register(ModuleContext context) {}

    @Override
    public void start(ModuleContext context) {
        screens = context.engine().require(UIApis.SCREENS);
        module = context;
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void stop(ModuleContext context) {
        MinecraftForge.EVENT_BUS.unregister(this);
        tree = null;
        module = null;
        screens = null;
    }

    @SubscribeEvent
    public void onScreenRender(ScreenEvent.Render.Pre event) {
        var screen = event.getScreen();
        if (!(screen instanceof ShopScaledScreen || screen instanceof ShopThemeEditorScreen)
                || screen != Minecraft.getInstance().screen
                || screen.getClass().getModule() != ShopKernelModule.class.getModule()) {
            return;
        }
        if (tree != null && tree.isOpen()) return;

        // Forge 会重用已经初始化的父页。只挂载活动页，并由 Kernel 在换屏时关闭旧树。
        tree = screens.mount(module, screen, SCREEN_TREE);
        if (screen instanceof ShopScreen shop) {
            module.engine().events().subscribe(ScreenService.VIEWPORT_CHANGED, tree, UIEventPhase.TARGET, 0, change -> {
                if (!change.payload().focused()) shop.cancelPointerInteraction();
            });
        }
    }
}
