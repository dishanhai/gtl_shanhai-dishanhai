package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** 山海：NBT 單字串受 modified UTF-8 64 KiB 限制，分段保存完整診斷而不裁字。 */
final class StellarPatternHistoryText {
    private StellarPatternHistoryText() {}
    static void put(CompoundTag tag, String key, String text) {
        var chunks = new ListTag();
        for (int start = 0; start < text.length(); start += 4096) {
            chunks.add(StringTag.valueOf(text.subSequence(start, Math.min(text.length(), start + 4096)).toString()));
        }
        tag.put(key, chunks);
    }
    static String get(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) return tag.getString(key);
        var chunks = tag.getList(key, Tag.TAG_STRING);
        var text = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) text.append(chunks.getString(i));
        return text.toString();
    }
}
