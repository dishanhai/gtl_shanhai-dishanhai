package com.dishanhai.gt_shanhai.common.recipe.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Per-save, per-player autosaved state for the recipe modifier editor. */
public final class ShanhaiRecipeEditorDraftSavedData extends SavedData {

    private static final String DATA_NAME = "gt_shanhai_recipe_editor_drafts";
    private static final String TAG_PLAYERS = "players";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_DRAFT = "draft";
    private static final int MAX_DRAFT_CHARACTERS = 131072;

    private final Map<UUID, CompoundTag> drafts = new LinkedHashMap<>();

    public static ShanhaiRecipeEditorDraftSavedData get(MinecraftServer server) {
        if (server == null || server.overworld() == null) {
            throw new IllegalStateException("Recipe editor drafts require a running world");
        }
        return server.overworld().getDataStorage().computeIfAbsent(
                ShanhaiRecipeEditorDraftSavedData::load,
                ShanhaiRecipeEditorDraftSavedData::new,
                DATA_NAME);
    }

    public static ShanhaiRecipeEditorDraftSavedData load(CompoundTag tag) {
        ShanhaiRecipeEditorDraftSavedData data = new ShanhaiRecipeEditorDraftSavedData();
        ListTag players = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag player = players.getCompound(i);
            try {
                UUID uuid = UUID.fromString(player.getString(TAG_UUID));
                CompoundTag draft = player.getCompound(TAG_DRAFT);
                if (!draft.isEmpty() && withinLimit(draft)) data.drafts.put(uuid, draft.copy());
            } catch (RuntimeException ignored) {
                // Ignore a malformed draft and keep the rest of the save usable.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, CompoundTag> entry : drafts.entrySet()) {
            CompoundTag player = new CompoundTag();
            player.putString(TAG_UUID, entry.getKey().toString());
            player.put(TAG_DRAFT, entry.getValue().copy());
            players.add(player);
        }
        tag.put(TAG_PLAYERS, players);
        return tag;
    }

    public CompoundTag getDraft(ServerPlayer player) {
        if (player == null) return new CompoundTag();
        CompoundTag draft = drafts.get(player.getUUID());
        return draft == null ? new CompoundTag() : draft.copy();
    }

    public boolean setDraft(ServerPlayer player, CompoundTag draft) {
        if (player == null || draft == null || draft.isEmpty() || !withinLimit(draft)) return false;
        drafts.put(player.getUUID(), draft.copy());
        setDirty();
        return true;
    }

    public void clear(ServerPlayer player) {
        if (player != null && drafts.remove(player.getUUID()) != null) setDirty();
    }

    private static boolean withinLimit(CompoundTag draft) {
        return draft.toString().length() <= MAX_DRAFT_CHARACTERS;
    }
}
