package com.dishanhai.gt_shanhai.api.machine;

import com.dishanhai.gt_shanhai.api.gui.configurators.SelectableRecipeTypeSetConfigurator;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.trait.ICheckPatternMachine;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;

import java.util.Collections;
import java.util.List;

/**
 * 干净 Fancy UI 机器基类。
 * 不继承父类侧边页/额外配置器，只保留核心可控入口。
 */
public class CleanSelectableRecipeTypeSetMachine extends SelectableRecipeTypeSetMachine {

    public CleanSelectableRecipeTypeSetMachine(IMachineBlockEntity holder, GTRecipeType dummyRecipeType, Object... args) {
        super(holder, dummyRecipeType, args);
    }

    @Override
    public List<IFancyUIProvider> getSubTabs() {
        return Collections.emptyList();
    }

    @Override
    public void attachSideTabs(TabsWidget tabsWidget) {
        tabsWidget.setMainTab(this);
        if (shouldShowRecipeTypeSetTab()) {
            tabsWidget.attachSubTab(new SelectableRecipeTypeSetConfigurator(this));
        }
        attachCleanSideTabs(tabsWidget);
    }

    protected void attachCleanSideTabs(TabsWidget tabsWidget) {
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        if (shouldAttachPowerConfigurator()) {
            attachPowerConfigurator(panel);
        }
        if (shouldAttachStructureCheckConfigurator()) {
            ICheckPatternMachine.attachConfigurators(panel, this);
        }
        if (shouldAttachRecipeCapabilityConfigurator()) {
            IRecipeCapabilityMachine.attachConfigurators(panel, this);
        }
        if (shouldAttachRecipeLockConfigurator()) {
            ILockRecipe.attachRecipeLockable(panel, getRecipeLogic());
        }
        attachCleanConfigurators(panel);
    }

    protected boolean shouldAttachPowerConfigurator() {
        return true;
    }

    protected boolean shouldAttachStructureCheckConfigurator() {
        return true;
    }

    protected boolean shouldAttachRecipeCapabilityConfigurator() {
        return true;
    }

    protected boolean shouldAttachRecipeLockConfigurator() {
        return true;
    }

    protected void attachCleanConfigurators(ConfiguratorPanel panel) {
    }

    private void attachPowerConfigurator(ConfiguratorPanel panel) {
        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                GuiTextures.BUTTON_POWER.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D),
                GuiTextures.BUTTON_POWER.getSubTexture(0.0D, 0.5D, 1.0D, 0.5D),
                this::isWorkingEnabled,
                (clickData, pressed) -> setWorkingEnabled(pressed.booleanValue()))
                .setTooltipsSupplier(pressed -> List.of(Component.translatable(
                        pressed.booleanValue() ? "behaviour.soft_hammer.enabled" : "behaviour.soft_hammer.disabled"))));
    }
}
