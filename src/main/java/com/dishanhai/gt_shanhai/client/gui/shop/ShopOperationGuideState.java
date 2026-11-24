package com.dishanhai.gt_shanhai.client.gui.shop;

/** 山海：单次打开操作引导时的示例商品和翻页状态。 */
final class ShopOperationGuideState {
    enum Step {
        BROWSE("browse"), QUANTITY("quantity"), CONFIRM("confirm"), SHORTCUTS("shortcuts");

        final String key;

        Step(String key) {
            this.key = key;
        }
    }

    private static final Step[] STEPS = Step.values();
    static final int STEP_COUNT = STEPS.length;
    static final int MAX_QUANTITY = 64;
    private static final int UNIT_COST = 3;

    private int stepIndex;
    private int quantity = 1;
    private boolean selected;
    private boolean confirmed;

    Step step() { return STEPS[stepIndex]; }
    boolean canPrevious() { return stepIndex > 0; }
    boolean canNext() { return stepIndex < STEP_COUNT - 1; }

    boolean previous() {
        if (!canPrevious()) return false;
        stepIndex--;
        return true;
    }

    boolean next() {
        if (!canNext()) return false;
        stepIndex++;
        return true;
    }

    boolean selected() { return selected; }
    boolean confirmed() { return confirmed; }
    void selectExample() { selected = true; }
    void confirmExample() { confirmed = true; }

    int quantity() { return quantity; }
    int total() { return costFor(quantity); }
    static int costFor(int amount) { return amount * UNIT_COST; }
    boolean canDecrease() { return quantity > 1; }
    boolean canIncrease() { return quantity < MAX_QUANTITY; }

    void decrease() {
        if (canDecrease()) {
            quantity--;
            confirmed = false;
        }
    }

    void increase() {
        if (canIncrease()) {
            quantity++;
            confirmed = false;
        }
    }
}
