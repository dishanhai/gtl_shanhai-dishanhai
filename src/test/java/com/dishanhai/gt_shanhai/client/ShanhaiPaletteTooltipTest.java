package com.dishanhai.gt_shanhai.client;

import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShanhaiPaletteTooltipTest {
    @Test
    void putsEveryNewPaletteAfterTheTitleAndPreservesExistingLines() throws Exception {
        Class<?> handler = Class.forName("com.dishanhai.gt_shanhai.client.ShanhaiPaletteTooltip");
        var itemId = handler.getDeclaredField("TEST_ITEM");
        itemId.setAccessible(true);
        assertEquals("dishanhai:test_dynamic_text", itemId.get(null).toString());
        var append = handler.getDeclaredMethod("appendPreview", List.class);
        append.setAccessible(true);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("既有標題"));
        lines.add(Component.literal("既有尾行"));
        append.invoke(null, lines);

        assertEquals("既有標題", lines.get(0).getString());
        assertEquals("既有尾行", lines.get(6).getString());
        assertEquals(7, lines.size());
        String[] names = {"shanhai_dawn", "shanhai_mist", "shanhai_galaxy", "shanhai_prism"};
        for (int i = 0; i < names.length; i++) {
            assertEquals("fcs:" + names[i], lines.get(i + 2).getStyle().getInsertion());
        }
        var event = handler.getDeclaredMethod("onTooltip", net.minecraftforge.event.entity.player.ItemTooltipEvent.class);
        SubscribeEvent annotation = event.getAnnotation(SubscribeEvent.class);
        assertNotNull(annotation);
        assertEquals(EventPriority.LOWEST, annotation.priority());
    }
}
