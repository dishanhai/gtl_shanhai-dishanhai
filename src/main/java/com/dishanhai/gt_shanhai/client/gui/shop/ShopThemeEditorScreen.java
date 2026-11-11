package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import com.dishanhai.sh_better_ui.client.api.BackgroundSpec;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicDisplayLayout.Bounds;
import com.dishanhai.sh_better_ui.client.gui.scaled.PageTransition;
import com.dishanhai.sh_better_ui.client.gui.scaled.ThemeEffects;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiScreenReturn;
import com.dishanhai.sh_better_ui.kernel.api.UIApis;
import com.dishanhai.sh_better_ui.kernel.api.UnifiedUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 山海：在独立草稿里编辑 SH-Better-UI 参数，确认后交回商店设置页。 */
final class ShopThemeEditorScreen extends Screen {
    private final ShopSettingsScreen parent;
    private final String preset;
    private final boolean reducedMotion;
    private final ShopVisuals visuals = new ShopVisuals();
    private final UiScreenReturn handoff = new UiScreenReturn(this);
    private long crossfadeAt;
    private boolean crossfade;
    private ShopAppearance draft;
    private ShopAppearance previewDraft;
    private String group;
    private CycleButton<String> groupButton;
    private Bounds preview;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private boolean restart = true;
    private boolean restoreConfigureFocus;

    ShopThemeEditorScreen(ShopSettingsScreen parent, String preset, ShopAppearance appearance, boolean reducedMotion) {
        super(label("title"));
        this.parent = parent;
        this.preset = preset;
        this.draft = appearance;
        this.reducedMotion = reducedMotion;
        group = preferredGroup();
        updatePreview();
        if (appearance.effects().contains("screenReturn")) beginCrossfade(parent);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 8, 420);
        panelHeight = Math.min(height - 8, 224);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int x = left + 8;
        int w = panelWidth - 16;
        if (w < 172 || panelHeight < 168) {
            addRenderableWidget(Button.builder(label("cancel"), button -> onClose())
                    .bounds(width / 2 - 44, height / 2 + 8, 88, 20).build());
            preview = null;
            return;
        }
        int half = (w - 4) / 2;
        var backgrounds = new ArrayList<String>();
        backgrounds.add(ShopAppearance.NO_BACKGROUND);
        ShBetterUiApi.backgrounds().entries().stream().map(BackgroundSpec::id).forEach(backgrounds::add);
        addRenderableWidget(CycleButton.<String>builder(ShopThemeEditorScreen::backgroundLabel)
                .withValues(backgrounds).withInitialValue(backgroundChoice())
                .create(x, top + 20, half, 20, label("background"), (button, value) -> {
                    draft = draft.withBackground(value);
                    selectGroup(preferredGroup());
                }));
        var materials = new ArrayList<String>();
        materials.add(ShopAppearance.Material.PRESET.id);
        ShopTheme.choices().forEach(choice -> materials.add(choice.id()));
        addRenderableWidget(CycleButton.<String>builder(ShopThemeEditorScreen::materialLabel)
                .withValues(materials).withInitialValue(materialChoice(materials))
                .create(x + half + 4, top + 20, w - half - 4, 20, label("material"), (button, value) -> {
                    draft = draft.withMaterial(value);
                    selectGroup(preferredGroup());
                }));
        groupButton = addRenderableWidget(CycleButton.<String>builder(ShopThemeEditorScreen::groupLabel)
                .withValues(new ArrayList<>(ShBetterUiApi.settings().themeGroups())).withInitialValue(group)
                .create(x, top + 44, w, 20, label("group"), (button, value) -> {
                    selectGroup(value);
                }));
        var configure = addRenderableWidget(Button.builder(label("configure"), button -> configureGroup())
                .bounds(x, top + 68, w, 20).build());
        if (restoreConfigureFocus) {
            setInitialFocus(configure);
            restoreConfigureFocus = false;
        }

        int footer = top + panelHeight - 24;
        preview = new Bounds(x, top + 94, w, Math.max(0, footer - top - 98));
        addRenderableWidget(Button.builder(label("cancel"), button -> onClose())
                .bounds(x, footer, half, 20).build());
        addRenderableWidget(Button.builder(label("done"), button -> {
            parent.setThemeDraft(draft);
            onClose();
        }).bounds(x + half + 4, footer, w - half - 4, 20).build());
    }

    private String backgroundChoice() {
        return draft.hasBackground() ? draft.backgroundId() : ShopAppearance.NO_BACKGROUND;
    }

    private String materialChoice(List<String> materials) {
        String id = ShopTheme.canonicalMaterialId(draft.materialId());
        return materials.contains(id) ? id : ShopAppearance.Material.PRESET.id;
    }

    private String preferredGroup() {
        var groups = ShBetterUiApi.settings().themeGroups();
        return ShBetterUiApi.backgrounds().find(draft.backgroundId()).map(BackgroundSpec::settingsGroup)
                .filter(groups::contains)
                .orElseGet(() -> {
                    String materialGroup = ShopTheme.of(preset, draft.materialId()).materialGroup();
                    return groups.contains(materialGroup) ? materialGroup : "classic";
                });
    }

    private void selectGroup(String id) {
        group = id;
        groupButton.setValue(id);
        updatePreview();
    }

    private void updatePreview() {
        var backgrounds = ShBetterUiApi.backgrounds();
        boolean selectedMatches = backgrounds.find(draft.backgroundId())
                .map(spec -> group.equals(spec.settingsGroup())).orElse(false);
        previewDraft = selectedMatches ? draft : backgrounds.entries().stream()
                .filter(spec -> group.equals(spec.settingsGroup()))
                .findFirst().map(spec -> draft.withBackground(spec.id())).orElse(draft);
        restart = true;
    }

    private void configureGroup() {
        String selected = group;
        var form = UnifiedUI.get().require(UIApis.APPEARANCE).edit(this, ShopVisuals.settings(draft, reducedMotion), selected,
                ShopAppearance.inheritedEffectGroup(selected), draft.effects().group(selected), values -> {
                    draft = draft.withGroup(selected, values);
                    updatePreview();
                    if (selected.equals("screenReturn") && draft.effects().contains(selected)) beginCrossfade(this);
                });
        restoreConfigureFocus = true;
        minecraft.setScreen(form);
    }

    private void beginCrossfade(Screen previous) {
        ShopVisuals.scoped(draft, reducedMotion, () -> {
            UiPreferences prefs = UiPreferences.configured();
            crossfade = !prefs.reducedMotion() && ThemeEffects.local("screenReturn").enabled();
            if (crossfade) {
                crossfadeAt = PageTransition.nowMs();
                handoff.beginHandoff(previous, prefs);
            } else {
                handoff.clearHandoff();
            }
        });
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ShopVisuals.scoped(draft, reducedMotion, () -> renderDraft(g, mouseX, mouseY, partialTick));
    }

    private void renderDraft(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        UiPreferences prefs = UiPreferences.configured();
        if (crossfade) {
            var effect = ThemeEffects.local("screenReturn");
            float progress = (PageTransition.nowMs() - crossfadeAt) * effect.speed() / 240f;
            if (progress < 1f && effect.enabled() && !prefs.reducedMotion()) {
                handoff.renderCrossfade(parent, g, partialTick, prefs, progress, 1f, () -> renderBackground(g));
                int color = effect.tint(0x509ACDFF);
                int alpha = Math.round((color >>> 24) * (1f - progress));
                if (alpha > 0) g.fill(0, 0, width, height, alpha << 24 | color & 0xFFFFFF);
            } else {
                crossfade = false;
                handoff.clearHandoff();
                renderBackground(g);
            }
        } else {
            renderBackground(g);
        }
        if (preview == null) {
            g.drawCenteredString(font, label("small"), width / 2, height / 2 - 10, 0xFFFFFF);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        ShopTheme theme = ShopTheme.of(preset, draft.materialId());
        Bounds area = new Bounds(left, top, panelWidth, panelHeight);
        if (!ShopVisuals.drawMaterial(g, area, theme)) {
            theme.rootPanel(g, left, top, panelWidth, panelHeight, left + 5, top + 17, panelWidth - 10, panelHeight - 22);
        }
        g.drawCenteredString(font, title, width / 2, top + 6, 0xFFFFFF);
        if (preview.height() > 0) {
            theme.box(g, preview.x(), preview.y(), preview.width(), preview.height(), theme.frameDark, theme.boxBg);
            if (restart) {
                restart = false;
                visuals.transition(previewDraft, preview, prefs);
            }
            visuals.draw(g, font, preview, previewDraft, mouseX, mouseY, preview.contains(mouseX, mouseY), prefs);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && preview != null) visuals.click(preview, previewDraft, x, y);
        return super.mouseClicked(x, y, button);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        handoff.clearHandoff();
        visuals.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component backgroundLabel(String id) {
        return ShBetterUiApi.backgrounds().find(id)
                .<Component>map(spec -> Component.translatable(spec.titleKey())).orElseGet(() -> label("none"));
    }

    private static Component materialLabel(String id) {
        return id.equals(ShopAppearance.Material.PRESET.id) ? label("preset") : ShopTheme.name(id);
    }

    private static Component groupLabel(String id) {
        return ShBetterUiApi.backgrounds().entries().stream().filter(spec -> id.equals(spec.settingsGroup()))
                .findFirst().<Component>map(spec -> Component.translatable(spec.titleKey()))
                .orElseGet(() -> Component.translatableWithFallback("sh_better_ui.panel.theme.group." + id, id));
    }

    private static Component label(String id) {
        return Component.translatable("gt_shanhai.shop.appearance." + id);
    }
}
