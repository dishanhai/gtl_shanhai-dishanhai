package com.dishanhai.gt_shanhai.client;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class JeiChatLinkHelperTest {

    @Test
    void makeLinksUsesNormalizedJeiSearchCommand() {
        Component linked = JeiChatLinkHelper.makeLinks(
                Component.literal("[永恒格雷工坊数据模块] gt_shanhai:eternal_workshop_data_module"));

        Component nameComponent = linked.getSiblings().get(0);
        ClickEvent clickEvent = nameComponent.getStyle().getClickEvent();
        assertNotNull(clickEvent);
        assertEquals(ClickEvent.Action.RUN_COMMAND, clickEvent.getAction());
        assertEquals(JeiChatLinkHelper.COMMAND + "*gt_shanhai:eternal_workshop_data_module", clickEvent.getValue());
    }

    @Test
    void prefixesNamespacedIdWithStarForJeiIdSearch() {
        assertEquals("*gt_shanhai:eternal_workshop_data_module",
                JeiChatLinkHelper.normalizeSearchText("gt_shanhai:eternal_workshop_data_module"));
    }

    @Test
    void keepsExplicitSearchPrefixUntouched() {
        assertEquals("*gt_shanhai:eternal_workshop_data_module",
                JeiChatLinkHelper.normalizeSearchText("*gt_shanhai:eternal_workshop_data_module"));
        assertEquals("#forge:plates",
                JeiChatLinkHelper.normalizeSearchText("#forge:plates"));
    }

    @Test
    void keepsLocalizedNameSearchUntouched() {
        assertEquals("永恒格雷工坊数据模块",
                JeiChatLinkHelper.normalizeSearchText("永恒格雷工坊数据模块"));
    }
}
