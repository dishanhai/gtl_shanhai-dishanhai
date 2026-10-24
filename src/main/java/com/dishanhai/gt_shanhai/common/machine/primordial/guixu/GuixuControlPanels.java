package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.network.ShanhaiStructureHighlightPacket;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import java.util.List;

/** 山海：專屬模式與 24 槽控制頁，動態文字由服務端取樣同步。 */
public final class GuixuControlPanels {
    private GuixuControlPanels() {}

    public static IFancyUIProvider modes(GuixuVisionCoreMachine host) {
        return new IFancyUIProvider() {
            @Override public Component getTitle() { return Component.literal("工作模式"); }
            @Override public IGuiTexture getTabIcon() { return new ItemStackTexture(Items.COMPARATOR); }
            @Override public Widget createMainPage(FancyMachineUIWidget widget) {
                WidgetGroup page = new WidgetGroup(0, 0, 210, 150);
                page.setBackground(GuiTextures.BACKGROUND_INVERSE);
                page.addWidget(new LabelWidget(6, 5, "§6歸墟工作模式"));
                GTRecipeType[] types = host.getAllSelectableRecipeTypes();
                for (int i = 0; i < types.length; i++) {
                    GTRecipeType type = types[i];
                    int y = 25 + i * 27;
                    page.addWidget(new ButtonWidget(6, y, 198, 22, GuiTextures.BUTTON,
                            click -> host.selectOnlyRecipeType(type)));
                    page.addWidget(new LabelWidget(12, y + 7, () ->
                            (host.isRecipeTypeSelected(type) ? "§a▶ " : "§7  ")
                                    + Component.translatable("gtceu." + type.registryName.getPath()).getString()));
                }
                page.addWidget(new LabelWidget(6, 113, "§7切換僅影響下一批，不中斷已扣料配方"));
                page.addWidget(new LabelWidget(6, 128, "§7加工由能源倉供電；發電存入動力倉"));
                return page;
            }
        };
    }

    public static IFancyUIProvider modules(GuixuVisionCoreMachine host) { return new ModulePanel(host); }

    private static final class ModulePanel implements IFancyUIProvider {
        private final GuixuVisionCoreMachine host;
        private int selected;
        private long cachedTick = Long.MIN_VALUE;
        private final SlotInfo[] cache = new SlotInfo[GuixuHostGeometry.SLOT_COUNT];

        private ModulePanel(GuixuVisionCoreMachine host) { this.host = host; }
        @Override public Component getTitle() { return Component.literal("模組管理"); }
        @Override public IGuiTexture getTabIcon() { return new ItemStackTexture(Items.END_CRYSTAL); }

        @Override
        public Widget createMainPage(FancyMachineUIWidget widget) {
            WidgetGroup page = new WidgetGroup(0, 0, 344, 220);
            page.setBackground(GuiTextures.BACKGROUND_INVERSE);
            page.addWidget(new LabelWidget(6, 5, "§6外環模組 1–16"));
            page.addWidget(new LabelWidget(6, 116, "§b上柱 17–20"));
            page.addWidget(new LabelWidget(6, 158, "§d下柱 21–24"));
            for (int i = 0; i < GuixuHostGeometry.SLOT_COUNT; i++) {
                int slot = i;
                int x = 6 + i % 4 * 23;
                int y = i < 16 ? 24 + i / 4 * 22 : (i < 20 ? 131 : 173);
                ButtonWidget button = new ButtonWidget(x, y, 21, 19, GuiTextures.BUTTON,
                        click -> selected = slot);
                button.setHoverBorderTexture(1, 0xFF99CCFF);
                page.addWidget(button);
                page.addWidget(new LabelWidget(x + 3, y + 6,
                        () -> info(slot).color + (selected == slot ? "§l" : "") + (slot + 1)));
            }
            page.addWidget(new LabelWidget(6, 202, "§7點選槽位查看完整資料"));
            boolean remote = host.isRemote();
            var details = new DraggableScrollableWidgetGroup(112, 5, 226, 186);
            details.setBackground(GuiTextures.DISPLAY);
            details.setYScrollBarWidth(4);
            details.addWidget(new ComponentPanelWidget(4, 4, this::details)
                    .textSupplier(remote ? null : this::details).setMaxWidthLimit(216));
            page.addWidget(details);
            page.addWidget(new ButtonWidget(240, 198, 96, 17,
                    new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("定位所選模組", -1)), click -> {
                        if (!click.isRemote && page.getGui() != null
                                && page.getGui().entityPlayer instanceof ServerPlayer player) {
                            if (host.getLevel() != player.level()) return;
                            SlotInfo info = info(selected);
                            ShanhaiStructureHighlightPacket.sendTo(player, player.level().dimension(),
                                    System.currentTimeMillis() + 15000,
                                    List.of(new ShanhaiStructureHighlightPacket.Marker(info.pos, 0x66CCFF)));
                            player.displayClientMessage(Component.literal("模組 " + (selected + 1)
                                    + "：" + info.pos.toShortString() + "（定位 15 秒）"), false);
                        }
                    }));
            return page;
        }

        private SlotInfo info(int index) {
            long tick = host.getLevel() == null ? 0L : host.getLevel().getGameTime() / 10;
            if (cachedTick != tick || cache[index] == null) {
                BlockPos[] positions = host.getModuleScanPositions();
                for (int i = 0; i < cache.length; i++) cache[i] = inspect(positions[i]);
                cachedTick = tick;
            }
            return cache[index];
        }

        private SlotInfo inspect(BlockPos pos) {
            var level = host.getLevel();
            if (host.isRemote()) return SlotInfo.empty(pos, "同步中", "§7");
            if (level == null || !level.hasChunkAt(pos)) return SlotInfo.empty(pos, "區塊未載入", "§8");
            MetaMachine machine = MetaMachine.getMachine(level, pos);
            if (!(machine instanceof PrimordialOmegaEngineModuleBase module)) {
                return SlotInfo.empty(pos, level.isEmptyBlock(pos) ? "空槽" : "非相容模組", "§7");
            }
            var logic = module.getRecipeLogic();
            boolean linked = host.isFormed() && module.getHost() == host && host.getModuleSet().contains(module);
            String status = !module.isFormed() ? "模組未成型" : !linked ? "未連接本主機"
                    : !logic.isWorkingEnabled() ? "已停用" : logic.isWorking() ? "工作中"
                    : GuixuVisionCoreMachine.isBlocked(logic) ? "等待資源／輸出" : "待機";
            String color = !module.isFormed() || !linked ? "§c" : logic.isWorking() ? "§a"
                    : GuixuVisionCoreMachine.isBlocked(logic) ? "§e" : "§7";
            RecipeResult result = ((IRecipeStatus) logic).getWorkingStatus();
            if (result == null || result.isSuccess()) result = ((IRecipeStatus) logic).getRecipeStatus();
            Component reason = result != null && !result.isSuccess() && result.reason() != null
                    ? result.reason() : Component.literal("無");
            if (logic.isWaiting() && !logic.getFancyTooltip().isEmpty()) {
                var waiting = Component.empty();
                for (Component line : logic.getFancyTooltip()) waiting.append(line).append("；");
                reason = waiting;
            }
            return new SlotInfo(pos, Component.translatable(module.getDefinition().getBlock().getDescriptionId()),
                    module.getDefinition().getId().toString(), status, color, module.getModuleLevel(),
                    module.getCurrentParallel(), logic.getProgress(), logic.getMaxProgress(), reason);
        }

        private void details(List<Component> text) {
            SlotInfo info = info(selected);
            text.add(Component.literal("§6槽位 " + (selected + 1) + "｜" + GuixuVisionCoreMachine.slotLabel(selected)));
            text.add(Component.literal("座標：" + info.pos.toShortString()));
            text.add(Component.literal(info.color + info.status));
            text.add(info.name);
            if (info.id.isEmpty()) return;
            text.add(Component.literal("ID：" + info.id));
            text.add(Component.literal("有效等級：Lv." + info.level));
            text.add(Component.literal("目前並行上限：" + info.parallel));
            text.add(Component.literal("進度：" + info.progress + " / " + info.duration + " tick"));
            text.add(Component.literal("原因：").append(info.reason));
        }
    }

    private record SlotInfo(BlockPos pos, Component name, String id, String status, String color,
                            int level, long parallel, int progress, int duration, Component reason) {
        private static SlotInfo empty(BlockPos pos, String status, String color) {
            return new SlotInfo(pos, Component.empty(), "", status, color, 0, 0L, 0, 0, Component.empty());
        }
    }
}
