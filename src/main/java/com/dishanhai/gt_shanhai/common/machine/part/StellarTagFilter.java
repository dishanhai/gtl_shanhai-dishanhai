package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.stacks.AEItemKey;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 与特大号标签过滤总线相同的通配规则：{@code forge:ores*}、{@code *raw*}、{@code @modid}。
 * 星空库存总成和星律样板总成的标签拉取共用这一份。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class StellarTagFilter {

    private static final AtomicLong TAG_REVISION = new AtomicLong();

    private final String[] whiteTags;
    private final String[] blackTags;
    private final boolean hasWhite;
    private final HashMap<Object, Boolean> cache = new HashMap<>();
    private long cacheRevision = revision();

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        TAG_REVISION.incrementAndGet();
    }

    static long revision() {
        return TAG_REVISION.get();
    }

    StellarTagFilter(String white, String black) {
        String whitelist = white == null ? "" : white.trim();
        String blacklist = black == null ? "" : black.trim();
        whiteTags = whitelist.isEmpty() ? new String[0] : whitelist.split("[|,]");
        blackTags = blacklist.isEmpty() ? new String[0] : blacklist.split("[|,]");
        hasWhite = whiteTags.length > 0;
    }

    boolean isListed(AEItemKey itemKey) {
        long revision = revision();
        if (cacheRevision != revision) {
            cache.clear();
            cacheRevision = revision;
        }
        Boolean cached = cache.get(itemKey.getPrimaryKey());
        if (cached != null) {
            return cached;
        }
        Set<String> tags = itemKey.getItem().builtInRegistryHolder().tags()
                .map(tag -> tag.location().toString())
                .collect(Collectors.toSet());
        boolean listed = matches(itemKey, tags);
        cache.put(itemKey.getPrimaryKey(), listed);
        return listed;
    }

    private boolean matches(AEItemKey itemKey, Set<String> tags) {
        for (String black : blackTags) {
            String pattern = black.trim();
            if (!pattern.isEmpty() && matchesAny(pattern, itemKey, tags)) {
                return false;
            }
        }
        if (!hasWhite) {
            return true;
        }
        for (String white : whiteTags) {
            String pattern = white.trim();
            if (!pattern.isEmpty() && matchesAny(pattern, itemKey, tags)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String pattern, AEItemKey key, Set<String> tags) {
        if (pattern.startsWith("@")) {
            return modIdMatches(pattern.substring(1),
                    key.getItem().builtInRegistryHolder().key().location().getNamespace());
        }
        return tagMatchesAny(pattern, tags);
    }

    private static boolean modIdMatches(String pattern, String modId) {
        boolean startWild = pattern.startsWith("*");
        boolean endWild = pattern.endsWith("*");
        if (startWild && endWild && pattern.length() > 2) {
            return modId.contains(pattern.substring(1, pattern.length() - 1));
        }
        if (startWild && pattern.length() > 1) {
            return modId.endsWith(pattern.substring(1));
        }
        if (endWild && pattern.length() > 1) {
            return modId.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return modId.equals(pattern);
    }

    private static boolean tagMatchesAny(String pattern, Set<String> tags) {
        boolean startWild = pattern.startsWith("*");
        boolean endWild = pattern.endsWith("*");
        if (startWild && endWild && pattern.length() > 2) {
            String middle = pattern.substring(1, pattern.length() - 1);
            for (String tag : tags) {
                if (tag.contains(middle)) {
                    return true;
                }
            }
            return false;
        }
        if (startWild && pattern.length() > 1) {
            String suffix = pattern.substring(1);
            for (String tag : tags) {
                if (tag.endsWith(suffix)) {
                    return true;
                }
            }
            return false;
        }
        if (endWild && pattern.length() > 1) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            for (String tag : tags) {
                if (tag.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
        return tags.contains(pattern);
    }
}
