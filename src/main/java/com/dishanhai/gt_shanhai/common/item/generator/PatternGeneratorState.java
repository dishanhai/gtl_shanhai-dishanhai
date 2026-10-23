package com.dishanhai.gt_shanhai.common.item.generator;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 山海生成器的不可變客戶端快照；可變 NBT 和文字均以副本出入。 */
public record PatternGeneratorState(UUID sessionId, CompoundTag settings, int revision, int page,
                                    int count, int selected, int pendingCount, int generated,
                                    boolean running, boolean ready, int availablePatterns,
                                    Component feedback, Map<String, Integer> typeCounts,
                                    List<PatternGeneratorPreview> previewPage, int acknowledgedRequest) {
    public PatternGeneratorState(UUID sessionId, CompoundTag settings, int revision, int page,
                                  int count, int selected, int pendingCount, int generated,
                                  boolean running, boolean ready, int availablePatterns,
                                  Component feedback, Map<String, Integer> typeCounts,
                                  List<PatternGeneratorPreview> previewPage) {
        this(sessionId, settings, revision, page, count, selected, pendingCount, generated,
                running, ready, availablePatterns, feedback, typeCounts, previewPage, 0);
    }
    public PatternGeneratorState {
        if (acknowledgedRequest < 0) throw new IllegalArgumentException("Invalid pattern generator acknowledgement");
        Objects.requireNonNull(sessionId);
        settings = settings.copy();
        feedback = copyFeedback(feedback);
        typeCounts = Map.copyOf(typeCounts);
        previewPage = List.copyOf(previewPage);
    }
    @Override public CompoundTag settings() { return settings.copy(); }
    @Override public Component feedback() { return copyFeedback(feedback); }
    private static Component copyFeedback(Component feedback) {
        if (feedback == null) return Component.empty();
        Component copy = Component.Serializer.fromJson(Component.Serializer.toJson(feedback));
        return copy == null ? Component.empty() : copy;
    }
}

