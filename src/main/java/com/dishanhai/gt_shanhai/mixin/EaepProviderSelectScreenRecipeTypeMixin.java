package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.compat.eaep.EaepProviderRecipeTypeBridge;
import com.dishanhai.gt_shanhai.common.compat.eaep.EaepProviderRecipeTypeBridge.IncomingProviderWarningMetadata;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternWarningPolicy;
import com.extendedae_plus.client.screen.ProviderSelectScreen;
import com.extendedae_plus.mixin.minecraft.accessor.ScreenAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(value = ProviderSelectScreen.class, remap = false)
public abstract class EaepProviderSelectScreenRecipeTypeMixin {

    @Shadow
    private String query;

    @Shadow
    private List<Long> fIds;

    @Shadow
    private List<String> fNames;

    @Shadow
    private List<Integer> fTotalSlots;

    @Shadow
    private List<Integer> fCount;

    @Shadow
    private List<Long> gIds;

    @Shadow
    private List<String> gNames;

    @Shadow
    private List<Integer> gTotalSlots;

    @Shadow
    private List<Integer> gCount;

    @Shadow
    @Final
    private List<Button> entryButtons;

    @Shadow
    @Final
    private int[] buttonIndexMap;

    @Shadow(prefix = "gtShanhai$")
    private void gtShanhai$onChoose(int idx, boolean showStatusMessage) {
        throw new AssertionError();
    }

    @Unique
    private Map<String, Set<String>> gtShanhai$recipeTypesByProviderName = Map.of();

    @Unique
    private Map<Long, ProviderWarningTarget> gtShanhai$warningTargetsById = Map.of();

    @Unique
    private String gtShanhai$uploadRecipeTypeQuery = "";

    @Unique
    private String gtShanhai$uploadRecipeTypeId = "";

    @Unique
    private boolean gtShanhai$warningMetadataKnown;

    @Unique
    private boolean gtShanhai$bypassNextWrongHostWarning;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtShanhai$captureProviderRecipeTypes(Screen parent, List<Long> ids, List<String> names,
            List<Integer> emptySlots, CallbackInfo ci) {
        IncomingProviderWarningMetadata metadata =
                EaepProviderRecipeTypeBridge.consumeIncomingProviderWarningMetadata();
        this.gtShanhai$uploadRecipeTypeQuery = this.query == null ? "" : this.query;
        this.gtShanhai$uploadRecipeTypeId = metadata.uploadRecipeTypeId();
        this.gtShanhai$warningMetadataKnown = metadata.metadataKnown();
        this.gtShanhai$recipeTypesByProviderName = EaepProviderRecipeTypeBridge.buildRecipeTypeMapByProviderName(
                names, metadata.providerRecipeTypeIds());
        this.gtShanhai$warningTargetsById = gtShanhai$buildWarningTargets(ids, metadata);
        gtShanhai$refreshRecipeTypeRecommendations();
    }

    @Inject(method = "applyFilter", at = @At("TAIL"), remap = false)
    private void gtShanhai$prioritizeAfterFilter(CallbackInfo ci) {
        gtShanhai$refreshRecipeTypeRecommendations();
    }

    @Inject(method = "m_7856_", at = @At("TAIL"), remap = true)
    private void gtShanhai$registerRecipeTypeHints(CallbackInfo ci) {
        ((ScreenAccessor) (Object) this).eap$getRenderables().add(this::gtShanhai$renderRecipeTypeHints);
    }

    @Inject(method = "onChoose(IZ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$onChooseWithWrongHostWarning(int idx, boolean showStatusMessage, CallbackInfo ci) {
        if (this.gtShanhai$bypassNextWrongHostWarning) {
            this.gtShanhai$bypassNextWrongHostWarning = false;
            return;
        }
        ProviderWarningTarget target = gtShanhai$warningTarget(idx);
        if (target == null || !StellarPatternWarningPolicy.isWrongHost(
                this.gtShanhai$uploadRecipeTypeId,
                this.gtShanhai$warningMetadataKnown,
                target.stellar(),
                target.recipeTypeIds(),
                RecipeTypeSharedSearchSets::isShared)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Screen self = (Screen) (Object) this;
        String providerName = idx >= 0 && idx < this.fNames.size() ? this.fNames.get(idx) : "#" + idx;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                minecraft.setScreen(self);
                this.gtShanhai$bypassNextWrongHostWarning = true;
                gtShanhai$onChoose(idx, showStatusMessage);
            } else {
                minecraft.setScreen(self);
            }
        }, Component.translatable("gui.gt_shanhai.eaep_wrong_host_confirm.title"),
                Component.translatable("gui.gt_shanhai.eaep_wrong_host_confirm.message",
                        this.gtShanhai$uploadRecipeTypeId, providerName,
                        gtShanhai$hostTypeText(target.recipeTypeIds()))));
        ci.cancel();
    }

    @Unique
    private void gtShanhai$renderRecipeTypeHints(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.gtShanhai$recipeTypesByProviderName.isEmpty() || this.entryButtons == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }
        Font font = minecraft.font;
        int screenRight = minecraft.getWindow().getGuiScaledWidth() - 4;
        for (int i = 0; i < this.entryButtons.size() && i < this.buttonIndexMap.length; i++) {
            Button button = this.entryButtons.get(i);
            int idx = this.buttonIndexMap[i];
            if (button == null || !button.visible || idx < 0 || idx >= this.fNames.size()) {
                continue;
            }
            int textX = button.getX() + button.getWidth() + 8;
            int maxWidth = screenRight - textX;
            if (maxWidth < 20) {
                continue;
            }
            String text = EaepProviderRecipeTypeBridge.buildProviderRecipeTypeText(
                    this.fNames.get(idx),
                    this.gtShanhai$recipeTypesByProviderName,
                    gtShanhai$effectiveRecipeTypeQuery(),
                    5);
            if (text.isEmpty()) {
                continue;
            }
            text = gtShanhai$trimText(font, text, maxWidth);
            int textY = button.getY() + (button.getHeight() - font.lineHeight) / 2 + 1;
            guiGraphics.enableScissor(textX, button.getY(), screenRight, button.getY() + button.getHeight());
            guiGraphics.drawString(font, text, textX, textY, 0xFFD8E7FF, true);
            guiGraphics.disableScissor();
        }
    }

    @Unique
    private void gtShanhai$refreshRecipeTypeRecommendations() {
        gtShanhai$includeRecipeTypeMatches();
        gtShanhai$prioritizeMatchingProviders();
    }

    @Unique
    private void gtShanhai$includeRecipeTypeMatches() {
        String search = this.query == null ? "" : this.query.trim();
        if (search.isEmpty() || this.gtShanhai$recipeTypesByProviderName.isEmpty()) {
            return;
        }
        for (int i = 0; i < this.gNames.size(); i++) {
            String name = this.gNames.get(i);
            if (this.fNames.contains(name)
                    || !EaepProviderRecipeTypeBridge.providerMatchesRecipeType(
                            name, this.gtShanhai$recipeTypesByProviderName, search)) {
                continue;
            }
            this.fIds.add(this.gIds.get(i));
            this.fNames.add(name);
            this.fTotalSlots.add(this.gTotalSlots.get(i));
            this.fCount.add(this.gCount.get(i));
        }
    }

    @Unique
    private void gtShanhai$prioritizeMatchingProviders() {
        EaepProviderRecipeTypeBridge.sortProvidersByRecipeTypeMatch(
                this.fIds,
                this.fNames,
                this.fTotalSlots,
                this.fCount,
                this.gtShanhai$recipeTypesByProviderName,
                gtShanhai$effectiveRecipeTypeQuery());
    }

    @Unique
    private String gtShanhai$effectiveRecipeTypeQuery() {
        if (this.query != null && !this.query.isBlank()) {
            return this.query;
        }
        if (this.gtShanhai$uploadRecipeTypeId != null && !this.gtShanhai$uploadRecipeTypeId.isBlank()) {
            return this.gtShanhai$uploadRecipeTypeId;
        }
        return this.gtShanhai$uploadRecipeTypeQuery == null ? "" : this.gtShanhai$uploadRecipeTypeQuery;
    }

    @Unique
    private ProviderWarningTarget gtShanhai$warningTarget(int idx) {
        if (idx < 0 || idx >= this.fIds.size()) {
            return null;
        }
        Long providerId = this.fIds.get(idx);
        return providerId == null ? null : this.gtShanhai$warningTargetsById.get(providerId);
    }

    @Unique
    private static Map<Long, ProviderWarningTarget> gtShanhai$buildWarningTargets(
            List<Long> ids, IncomingProviderWarningMetadata metadata) {
        if (ids == null || ids.isEmpty() || metadata == null) {
            return Map.of();
        }
        Map<Long, ProviderWarningTarget> result = new HashMap<>();
        List<List<String>> recipeTypeIds = metadata.providerRecipeTypeIds();
        List<Boolean> stellarProviders = metadata.stellarProviders();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            if (id == null) {
                continue;
            }
            boolean stellar = i < stellarProviders.size() && Boolean.TRUE.equals(stellarProviders.get(i));
            List<String> types = i < recipeTypeIds.size() ? recipeTypeIds.get(i) : List.of();
            result.put(id, new ProviderWarningTarget(stellar,
                    types == null || types.isEmpty() ? Set.of() : new LinkedHashSet<>(types)));
        }
        return result;
    }

    @Unique
    private static String gtShanhai$hostTypeText(Set<String> hostTypeIds) {
        return hostTypeIds == null || hostTypeIds.isEmpty()
                ? Component.translatable("gui.gt_shanhai.eaep_wrong_host_confirm.no_host_types").getString()
                : String.join(", ", hostTypeIds);
    }

    @Unique
    private static String gtShanhai$trimText(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int ellipsisWidth = font.width("...");
        if (maxWidth <= ellipsisWidth) {
            return "";
        }
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + "...";
    }

    @Unique
    private record ProviderWarningTarget(boolean stellar, Set<String> recipeTypeIds) {
    }
}
