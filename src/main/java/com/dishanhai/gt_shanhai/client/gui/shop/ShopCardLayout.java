package com.dishanhai.gt_shanhai.client.gui.shop;

/** 山海：卡片的图示、文字及价格共享同一份不相交的几何预算。 */
record ShopCardLayout(int slotSize, int textX, int textWidth, int priceY, int numberWidth,
                      int costX, int extraX, boolean showCategory, boolean showCost, boolean showExtra) {
    static ShopCardLayout of(int width, int height, int amountWidth, int extraWidth, boolean hasCost) {
        int priceY = height - 13;
        int slot = 20;
        int textX = 3 + slot + 3;
        int available = width - textX - 3;
        boolean cost = hasCost && available >= 24;
        int costSpace = cost ? 12 : 0;
        boolean extra = extraWidth > 0 && available >= 12 + costSpace + extraWidth + 2;
        int number = Math.min(Math.min(48, Math.max(12, amountWidth + 8)),
                available - costSpace - (extra ? extraWidth + 2 : 0));
        int costX = textX + number + 2;
        return new ShopCardLayout(slot, textX, Math.max(0, width - textX - 3), priceY, number,
                costX, costX + costSpace, priceY >= 25, cost, extra);
    }

    int priceX() { return textX; }
    int nameWidth(boolean favorite) { return Math.max(0, textWidth - (favorite && !showCategory ? 10 : 0)); }
    int badgeWidth(boolean favorite) { return showCategory ? Math.max(0, textWidth - (favorite ? 10 : 0)) : 0; }
    int favoriteY() { return showCategory ? 14 : 4; }
}
