package com.mobby.automation;

/**
 * Pure re-implementation of the vanilla furnace tick rules, used only when a furnace inside an
 * active region is not being ticked by the server.
 *
 * <p>The rules mirror vanilla: fuel is consumed only when there is something to smelt, burn time
 * counts down every tick, cooking progresses only while lit, a finished item consumes exactly one
 * input item and adds exactly one recipe result, and cooking progress cools down by two ticks per
 * tick when the furnace cannot cook. Because the caller passes absolute item counts, a step can
 * never create items out of nothing.
 */
public final class FurnaceMath {

    private FurnaceMath() {
    }

    /** Mutable furnace state expressed in plain counts and ticks. */
    public static final class State {
        public int burnTime;
        public int burnTotal;
        public int cookTime;
        public int inputCount;
        public int fuelCount;
        public int resultCount;

        public State() {
        }

        public State(int burnTime, int cookTime, int inputCount, int fuelCount, int resultCount) {
            this.burnTime = Math.max(0, burnTime);
            this.burnTotal = this.burnTime;
            this.cookTime = Math.max(0, cookTime);
            this.inputCount = Math.max(0, inputCount);
            this.fuelCount = Math.max(0, fuelCount);
            this.resultCount = Math.max(0, resultCount);
        }

        @Override
        public String toString() {
            return "State{burn=" + burnTime + ", cook=" + cookTime + ", input=" + inputCount
                    + ", fuel=" + fuelCount + ", result=" + resultCount + "}";
        }
    }

    /** Static rules for the furnace that is being simulated. */
    public static final class Rules {
        public int cookTicks = 200;
        public int fuelTicks = 1600;
        public int outputPerItem = 1;
        public int maxResultStack = 64;
        public boolean smeltable = true;
        public boolean resultSlotCompatible = true;

        @Override
        public String toString() {
            return "Rules{cookTicks=" + cookTicks + ", fuelTicks=" + fuelTicks + ", outputPerItem="
                    + outputPerItem + ", maxResultStack=" + maxResultStack + ", smeltable=" + smeltable
                    + ", resultSlotCompatible=" + resultSlotCompatible + "}";
        }
    }

    /**
     * Advances the furnace by {@code ticks} ticks.
     *
     * @return {@code true} when the state changed and should be written back
     */
    public static boolean step(State state, Rules rules, int ticks) {
        if (ticks <= 0) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < ticks; i++) {
            if (state.burnTime <= 0) {
                if (canCook(state, rules) && state.fuelCount > 0) {
                    state.fuelCount--;
                    state.burnTime = Math.max(1, rules.fuelTicks);
                    state.burnTotal = state.burnTime;
                    changed = true;
                }
            }
            if (state.burnTime > 0) {
                state.burnTime--;
                changed = true;
                if (canCook(state, rules)) {
                    state.cookTime++;
                    if (state.cookTime >= Math.max(1, rules.cookTicks)) {
                        state.cookTime = 0;
                        state.inputCount--;
                        state.resultCount += Math.max(1, rules.outputPerItem);
                    }
                } else if (state.cookTime > 0) {
                    state.cookTime = Math.max(0, state.cookTime - 2);
                }
            } else if (state.cookTime > 0) {
                state.cookTime = Math.max(0, state.cookTime - 2);
                changed = true;
            }
        }
        return changed;
    }

    public static boolean canCook(State state, Rules rules) {
        if (!rules.smeltable || !rules.resultSlotCompatible) {
            return false;
        }
        if (state.inputCount <= 0) {
            return false;
        }
        return state.resultCount + Math.max(1, rules.outputPerItem) <= rules.maxResultStack;
    }

    /** Number of finished items produced by one step, used for experience bookkeeping. */
    public static int producedItems(int resultBefore, int resultAfter, int outputPerItem) {
        int delta = resultAfter - resultBefore;
        if (delta <= 0) {
            return 0;
        }
        return delta / Math.max(1, outputPerItem);
    }
}
