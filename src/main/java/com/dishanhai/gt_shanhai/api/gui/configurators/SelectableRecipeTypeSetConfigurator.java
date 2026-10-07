package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.network.SelectableRecipeTypeSetPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class SelectableRecipeTypeSetConfigurator implements IFancyUIProvider {

    private static final int PAGE_WIDTH = 176;
    private static final int PAGE_HEIGHT = 170;
    private static final int ROW_HEIGHT = 16;
    private static final int ROW_STRIDE = 18;

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
        WidgetGroup page = new WidgetGroup(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
        fillPage(page);
        return page;
    }

    private void fillPage(WidgetGroup page) {
        GTRecipeType[] types = machine.getAllSelectableRecipeTypes();
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

        int selectedCount = 0;
        for (GTRecipeType type : types) {
            if (machine.isRecipeTypeSelected(type)) {
                selectedCount++;
            }
        }
        page.addWidget(new LabelWidget(6, 4, machine.getRecipeTypeSetHeaderText()));
        page.addWidget(new LabelWidget(6, 16, machine.getRecipeTypeSetDescriptionText()));
        int countColor = selectedCount == 0 ? 0xFFFF6666
                : selectedCount == types.length ? 0xFF66FF88
                : 0xFFFFFF66;
        page.addWidget(new ImageWidget(112, 3, 58, 12,
                new TextTexture(selectedCount + "/" + types.length, countColor)
                        .setType(TextTexture.TextType.RIGHT)
                        .setDropShadow(true))
                .setHoverTooltips("已选中 / 全部配方类型"));

        page.addWidget(framedButton(5, 30, 54, 14, "§a全选", cd -> {
            runAndRefresh(machine::selectAllRecipeTypes);
            sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_ALL, -1);
        }).setHoverTooltips("选中全部配方类型"));
        page.addWidget(framedButton(63, 30, 54, 14, "§e仅第一项", cd -> {
            runAndRefresh(machine::selectFirstRecipeType);
            sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_FIRST, -1);
        }).setHoverTooltips("只保留列表第一项"));
        page.addWidget(framedButton(121, 30, 50, 14, "§c全空", cd -> {
            runAndRefresh(machine::selectNoRecipeTypes);
            sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_NONE, -1);
        }).setHoverTooltips("清空选择"));

        if (types.length == 0) {
            page.addWidget(new LabelWidget(6, 50, "§7无可用配方类型"));
            return;
        }

        int listX = 4;
        int listY = 48;
        int listW = 168;
        int maxListH = PAGE_HEIGHT - listY - 4;
        int contentH = 3 + types.length * ROW_STRIDE;
        int listH = Math.min(maxListH, Math.max(ROW_HEIGHT + 6, contentH));
        boolean scrolling = contentH > listH;
        int barWidth = scrolling ? 4 : 0;

        recipeList = new DraggableScrollableWidgetGroup(listX, listY, listW, listH);
        recipeList.setBackground(GuiTextures.DISPLAY);
        if (scrolling) {
            recipeList.setYScrollBarWidth(barWidth);
            recipeList.setYBarStyle(new ColorRectTexture(0x66000000), new ColorRectTexture(0xFFDDDDDD).setRadius(1));
        }
        page.addWidget(recipeList);

        int usable = listW - barWidth - 4;
        int onlyW = 34;
        int nameW = usable - onlyW - 2;
        int y = 3;
        for (int i = 0; i < types.length; i++) {
            int typeIndex = i;
            GTRecipeType type = types[i];
            boolean selected = machine.isRecipeTypeSelected(type);
            if (selected) {
                recipeList.addWidget(new ImageWidget(2, y, nameW, ROW_HEIGHT, new ColorRectTexture(0xFF243E2C)));
            }
            recipeList.addWidget(new ImageWidget(4, y + 3, 2, 10,
                    new ColorRectTexture(selected ? 0xFF55FF77 : 0xFF6A6A6A)));

            String display = (selected ? "§a[x] §f" : "§8[ ] §7") + recipeTypeDisplayName(type);
            ButtonWidget toggle = new ButtonWidget(2, y, nameW, ROW_HEIGHT, IGuiTexture.EMPTY, cd -> {
                boolean nextSelected = !machine.isRecipeTypeSelected(type);
                runAndRefresh(() -> machine.setRecipeTypeSelected(type, nextSelected));
                sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SET_INDEX_SELECTED, typeIndex, nextSelected);
            });
            toggle.setHoverBorderTexture(1, selected ? 0xFF66FF88 : 0xFFBBBBBB);
            toggle.setHoverTooltips(selected ? "点击取消这一项" : "点击选中这一项");
            recipeList.addWidget(toggle);
            recipeList.addWidget(new ImageWidget(8, y + 1, nameW - 10, ROW_HEIGHT - 2,
                    new TextTexture(display, -1).setType(TextTexture.TextType.LEFT)));

            recipeList.addWidget(framedButton(nameW + 4, y, onlyW, ROW_HEIGHT, "§b仅此", cd -> {
                runAndRefresh(() -> machine.selectOnlyRecipeType(type));
                sendSelectionAction(SelectableRecipeTypeSetPacket.ACTION_SELECT_ONLY_INDEX, typeIndex);
            }).setHoverTooltips("只保留这一项"));
            y += ROW_STRIDE;
        }
    }

    private static ButtonWidget framedButton(int x, int y, int width, int height, String text, Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(text, -1).setDropShadow(true)),
                onPress);
        button.setHoverBorderTexture(1, 0xFFFFFFFF);
        return button;
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
