package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.network.SelectableRecipeTypeSetPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

public class SelectableRecipeTypeSetConfigurator implements IFancyUIProvider {

    private final SelectableRecipeTypeSetMachine machine;
    private WidgetGroup mainPage;
    private DraggableScrollableWidgetGroup recipeList;

    public SelectableRecipeTypeSetConfigurator(SelectableRecipeTypeSetMachine machine) {
        this.machine = machine;
    }

    @Override
    public Component getTitle() {
        return machine.getRecipeTypeSetTabTitle();
    }

    @Override
    public IGuiTexture getTabIcon() {
        return machine.getRecipeTypeSetTabIcon();
    }

    @Override
    public IFancyUIProvider.PageGroupingData getPageGroupingData() {
        return machine.getRecipeTypeSetPageGroupingData();
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        this.mainPage = buildPage();
        return this.mainPage;
    }

    private WidgetGroup buildPage() {
        WidgetGroup page = new WidgetGroup(0, 0, 176, 170);
        fillPage(page);
        return page;
    }

    private void fillPage(WidgetGroup page) {
        GTRecipeType[] types = machine.getAllSelectableRecipeTypes();
        int pageHeight = 170;
        page.setBackground(GuiTextures.BACKGROUND_INVERSE);

        // 轮询检测服务端选择变化（服务端剪枝/其他玩家操作/数据包回执与本地乐观状态不一致），
        // 同步串变化即重建页面，保证打开期间勾选状态始终跟随权威数据
        page.addWidget(new Widget(0, 0, 0, 0) {
            private String lastSeenSelection = machine.getSelectedRecipeTypesSyncValue();

            @Override
            public void updateScreen() {
                super.updateScreen();
                String now = machine.getSelectedRecipeTypesSyncValue();
                if (!now.equals(lastSeenSelection)) {
                    lastSeenSelection = now;
                    rebuildPage();
                }
            }
        });

        page.addWidget(new LabelWidget(5, 5, machine.getRecipeTypeSetHeaderText()));
        page.addWidget(new LabelWidget(5, 18, machine.getRecipeTypeSetDescriptionText()));

        page.addWidget(new ButtonWidget(5, 34, 48, 16, new TextTexture("§a全选", -1), cd -> {
            runAndRefresh(() -> machine.selectAllRecipeTypes());
            sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_ALL, -1);
        }));
        page.addWidget(new ButtonWidget(57, 34, 64, 16, new TextTexture("§e仅第一项", -1), cd -> {
            runAndRefresh(() -> machine.selectFirstRecipeType());
            sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_FIRST, -1);
        }));
        page.addWidget(new ButtonWidget(125, 34, 42, 16, new TextTexture("§c全空", -1), cd -> {
            runAndRefresh(() -> machine.selectNoRecipeTypes());
            sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_NONE, -1);
        }));

        if (types.length == 0) {
            page.addWidget(new ImageWidget(5, 56, 166, 16, new TextTexture("§7无可用配方类型")));
            return;
        }

        recipeList = new DraggableScrollableWidgetGroup(4, 56, 168, pageHeight - 60);
        recipeList.setBackground(GuiTextures.DISPLAY);
        page.addWidget(recipeList);

        int y = 4;
        for (int i = 0; i < types.length; i++) {
            int typeIndex = i;
            GTRecipeType type = types[i];
            boolean selected = machine.isRecipeTypeSelected(type);
            String display = (selected ? "§a[x] " : "§7[ ] ") + recipeTypeDisplayName(type);

            recipeList.addWidget(new ButtonWidget(0, y, 122, 18, IGuiTexture.EMPTY, cd -> {
                boolean nextSelected = !machine.isRecipeTypeSelected(type);
                runAndRefresh(() -> {
                    machine.setRecipeTypeSelected(type, nextSelected);
                });
                sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SET_INDEX_SELECTED, typeIndex, nextSelected);
            }));
            recipeList.addWidget(new ImageWidget(2, y + 2, 118, 14, new TextTexture(display, -1)));
            recipeList.addWidget(new ButtonWidget(126, y, 38, 18, new TextTexture("§b仅此", -1), cd -> {
                runAndRefresh(() -> machine.selectOnlyRecipeType(type));
                sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_ONLY_INDEX, typeIndex);
            }));
            y += 22;
        }
    }

    private void sendSelectionAction(int action, int typeIndex) {
        sendSelectionAction(action, typeIndex, false);
    }

    private void sendSelectionAction(int action, int typeIndex, boolean selected) {
        if (machine.getLevel() == null || !machine.getLevel().isClientSide) {
            return;
        }
        ShanhaiNetwork.CHANNEL.sendToServer(new SelectableRecipeTypeSetPacket(machine.getPos(), action, typeIndex, selected));
    }

    private void runAndRefresh(Runnable action) {
        action.run();
        rebuildPage();
    }

    private void rebuildPage() {
        if (mainPage == null) {
            return;
        }
        int scrollY = recipeList == null ? 0 : recipeList.getScrollYOffset();
        mainPage.clearAllWidgets();
        fillPage(mainPage);
        if (recipeList != null) {
            recipeList.setScrollYOffset(scrollY);
        }
    }

    private static String recipeTypeDisplayName(GTRecipeType type) {
        String name = SelectableRecipeTypeSetMachine.recipeTypeName(type);
        if (name == null) {
            return "unknown";
        }
        for (String key : recipeTypeLanguageKeys(type)) {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) {
                return translated;
            }
        }
        return name;
    }

    private static String[] recipeTypeLanguageKeys(GTRecipeType type) {
        String namespace = type.registryName.getNamespace();
        String path = type.registryName.getPath();
        return new String[] {
                type.registryName.toLanguageKey(),
                "gtceu." + path,
                "gtceu.recipe_type." + path,
                "recipe_type." + path,
                "gtceu.recipe_type." + namespace + "." + path
        };
    }
}
