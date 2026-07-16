package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class FancyConfiguratorSidebarPage implements IFancyUIProvider {

    private static final int MIN_PAGE_WIDTH = 176;
    private static final int PAGE_PADDING = 4;
    private static final int SECTION_GAP = 4;

    private final Component title;
    private final IGuiTexture icon;
    private final List<Component> tooltips;
    private final List<IFancyConfigurator> configurators;
    private final int firstRowColumns;

    public FancyConfiguratorSidebarPage(Component title, IGuiTexture icon, List<Component> tooltips,
                                        List<IFancyConfigurator> configurators) {
        this(title, icon, tooltips, configurators, 1);
    }

    public FancyConfiguratorSidebarPage(Component title, IGuiTexture icon, List<Component> tooltips,
                                        List<IFancyConfigurator> configurators, int firstRowColumns) {
        this.title = title;
        this.icon = icon;
        this.tooltips = List.copyOf(tooltips);
        this.configurators = List.copyOf(configurators);
        this.firstRowColumns = Math.max(1, firstRowColumns);
    }

    public static FancyConfiguratorSidebarPage single(IFancyConfigurator configurator) {
        return new FancyConfiguratorSidebarPage(configurator.getTitle(), configurator.getIcon(),
                configurator.getTooltips(), List.of(configurator));
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        List<Widget> sections = new ArrayList<>(configurators.size());
        int firstRowCount = Math.min(firstRowColumns, configurators.size());
        for (int index = 0; index < configurators.size(); index++) {
            IFancyConfigurator configurator = configurators.get(index);
            boolean compact = firstRowColumns > 1 && index < firstRowCount;
            Widget section;
            if (configurator instanceof IFancyConfiguratorButton button) {
                section = new SyncedConfiguratorButton(button, MIN_PAGE_WIDTH - PAGE_PADDING * 2);
            } else {
                section = new SyncedConfiguratorWidget(configurator, compact);
            }
            sections.add(section);
        }

        int firstRowWidth = 0;
        int firstRowHeight = 0;
        for (int index = 0; index < firstRowCount; index++) {
            Widget section = sections.get(index);
            if (index > 0) firstRowWidth += SECTION_GAP;
            firstRowWidth += section.getSize().width;
            firstRowHeight = Math.max(firstRowHeight, section.getSize().height);
        }

        int pageWidth = Math.max(MIN_PAGE_WIDTH, firstRowWidth + PAGE_PADDING * 2);
        int pageHeight = PAGE_PADDING + firstRowHeight;
        for (int index = firstRowCount; index < sections.size(); index++) {
            Widget section = sections.get(index);
            pageWidth = Math.max(pageWidth, section.getSize().width + PAGE_PADDING * 2);
            pageHeight += SECTION_GAP + section.getSize().height;
        }
        pageHeight = Math.max(40, pageHeight + PAGE_PADDING);

        WidgetGroup page = new WidgetGroup(0, 0, pageWidth, pageHeight);
        page.setBackground(GuiTextures.BACKGROUND_INVERSE);

        int x = (pageWidth - firstRowWidth) / 2;
        for (int index = 0; index < firstRowCount; index++) {
            Widget section = sections.get(index);
            section.setSelfPosition(new Position(x, PAGE_PADDING));
            page.addWidget(section);
            x += section.getSize().width + SECTION_GAP;
        }

        int y = PAGE_PADDING + firstRowHeight + SECTION_GAP;
        for (int index = firstRowCount; index < sections.size(); index++) {
            Widget section = sections.get(index);
            section.setSelfPosition(new Position((pageWidth - section.getSize().width) / 2, y));
            page.addWidget(section);
            y += section.getSize().height + SECTION_GAP;
        }
        return page;
    }

    @Override
    public IGuiTexture getTabIcon() {
        return icon;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public List<Component> getTabTooltips() {
        return tooltips;
    }

    private abstract static class SyncedConfiguratorContainer extends WidgetGroup {

        private static final int CONFIGURATOR_UPDATE_ID = 0;
        protected final IFancyConfigurator configurator;

        protected SyncedConfiguratorContainer(int width, int height, IFancyConfigurator configurator) {
            super(0, 0, width, height);
            this.configurator = configurator;
        }

        @Override
        public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            configurator.writeInitialData(buffer);
        }

        @Override
        public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            configurator.readInitialData(buffer);
        }

        @Override
        public void detectAndSendChanges() {
            super.detectAndSendChanges();
            configurator.detectAndSendChange((id, sender) -> writeUpdateInfo(CONFIGURATOR_UPDATE_ID, buf -> {
                buf.writeVarInt(id);
                sender.accept(buf);
            }));
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == CONFIGURATOR_UPDATE_ID) {
                configurator.readUpdateInfo(buffer.readVarInt(), buffer);
            } else {
                super.readUpdateInfo(id, buffer);
            }
        }
    }

    private static final class SyncedConfiguratorWidget extends SyncedConfiguratorContainer {

        private static final int TITLE_HEIGHT = 14;

        private SyncedConfiguratorWidget(IFancyConfigurator configurator, boolean compact) {
            this(configurator, configurator.createConfigurator(), compact);
        }

        private SyncedConfiguratorWidget(IFancyConfigurator configurator, Widget content, boolean compact) {
            super(compact ? content.getSize().width + PAGE_PADDING * 2 :
                    Math.max(MIN_PAGE_WIDTH - PAGE_PADDING * 2, content.getSize().width + PAGE_PADDING * 2),
                    content.getSize().height + TITLE_HEIGHT + PAGE_PADDING, configurator);
            addWidget(new LabelWidget(PAGE_PADDING, 3, () -> configurator.getTitle().getString()));
            content.setSelfPosition(new Position((getSize().width - content.getSize().width) / 2, TITLE_HEIGHT));
            addWidget(content);
        }
    }

    private static final class SyncedConfiguratorButton extends SyncedConfiguratorContainer {

        private SyncedConfiguratorButton(IFancyConfiguratorButton button, int width) {
            super(width, 24, button);
            ButtonWidget action = new ButtonWidget(0, 0, width, 24, GuiTextures.BUTTON,
                    clickData -> button.onClick(clickData)) {
                @Override
                public void updateScreen() {
                    super.updateScreen();
                    setHoverTooltips(button.getTooltips());
                }
            };
            action.setHoverTooltips(button.getTooltips());
            addWidget(action);
            addWidget(new ImageWidget(4, 4, 16, 16, new DynamicConfiguratorIcon(button)));
            addWidget(new ImageWidget(24, 4, width - 28, 16,
                    new TextTexture(() -> buttonLabel(button).getString())
                            .setType(TextTexture.TextType.LEFT_HIDE)
                            .setWidth(width - 28)));
        }

        private static Component buttonLabel(IFancyConfiguratorButton button) {
            List<Component> tooltips = button.getTooltips();
            return tooltips.isEmpty() ? Component.empty() : tooltips.get(0);
        }
    }

    private record DynamicConfiguratorIcon(IFancyConfigurator configurator) implements IGuiTexture {

        @Override
        @OnlyIn(Dist.CLIENT)
        public void draw(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float x, float y,
                         int width, int height) {
            configurator.getIcon().draw(graphics, mouseX, mouseY, x, y, width, height);
        }
    }
}
