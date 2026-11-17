package com.mobby.automation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnaceMathTest {

    private static FurnaceMath.Rules rules(int cookTicks, int fuelTicks) {
        FurnaceMath.Rules rules = new FurnaceMath.Rules();
        rules.cookTicks = cookTicks;
        rules.fuelTicks = fuelTicks;
        rules.outputPerItem = 1;
        rules.maxResultStack = 64;
        return rules;
    }

    @Test
    void oneCoalSmeltsEightItemsInVanillaTime() {
        FurnaceMath.State state = new FurnaceMath.State(0, 0, 8, 1, 0);
        FurnaceMath.Rules rules = rules(200, 1600);

        assertTrue(FurnaceMath.step(state, rules, 1600));

        assertEquals(8, state.resultCount, "eight raw items become eight results");
        assertEquals(0, state.inputCount, "each result consumes exactly one input item");
        assertEquals(0, state.fuelCount, "one coal is consumed");
        assertEquals(0, state.burnTime, "the coal's 1600 ticks are used up");
        assertEquals(0, state.cookTime);
    }

    @Test
    void partialProgressProducesNothingYet() {
        FurnaceMath.State state = new FurnaceMath.State(0, 0, 4, 1, 0);
        FurnaceMath.step(state, rules(200, 1600), 199);

        assertEquals(0, state.resultCount);
        assertEquals(4, state.inputCount);
        assertEquals(199, state.cookTime);
    }

    @Test
    void noFuelMeansNoProgressAndNoItems() {
        FurnaceMath.State state = new FurnaceMath.State(0, 0, 5, 0, 0);
        boolean changed = FurnaceMath.step(state, rules(200, 1600), 400);

        assertFalse(changed);
        assertEquals(5, state.inputCount);
        assertEquals(0, state.resultCount);
        assertEquals(0, state.burnTime);
    }

    @Test
    void fuelIsNotBurnedWhenThereIsNothingToSmelt() {
        FurnaceMath.State state = new FurnaceMath.State(0, 0, 0, 4, 0);
        FurnaceMath.step(state, rules(200, 1600), 400);

        assertEquals(4, state.fuelCount);
        assertEquals(0, state.burnTime);
    }

    @Test
    void fullResultSlotStopsSmeltingWithoutBurningFuel() {
        FurnaceMath.Rules rules = rules(200, 1600);
        rules.maxResultStack = 1;
        FurnaceMath.State state = new FurnaceMath.State(0, 0, 5, 2, 1);

        FurnaceMath.step(state, rules, 400);

        assertEquals(1, state.resultCount);
        assertEquals(5, state.inputCount);
        assertEquals(2, state.fuelCount);
        assertEquals(0, state.burnTime);
    }

    @Test
    void wrongResultItemBlocksSmelting() {
        FurnaceMath.Rules rules = rules(200, 1600);
        rules.resultSlotCompatible = false;
        FurnaceMath.State state = new FurnaceMath.State(0, 0, 5, 2, 0);

        FurnaceMath.step(state, rules, 400);

        assertEquals(0, state.resultCount);
        assertEquals(5, state.inputCount);
        assertEquals(2, state.fuelCount);
    }

    @Test
    void cookingProgressCoolsDownWithoutFuel() {
        FurnaceMath.State state = new FurnaceMath.State(0, 50, 5, 0, 0);
        FurnaceMath.step(state, rules(200, 1600), 1);

        assertEquals(48, state.cookTime, "vanilla cools by two ticks per tick");
    }

    @Test
    void multiTickStepsNeverOverProduce() {
        FurnaceMath.Rules rules = rules(200, 1600);
        rules.outputPerItem = 3;
        FurnaceMath.State state = new FurnaceMath.State(0, 0, 2, 1, 0);

        FurnaceMath.step(state, rules, 400);

        assertEquals(6, state.resultCount, "two input items at three outputs each");
        assertEquals(0, state.inputCount);
    }

    @Test
    void producedItemCountUsesTheRecipeOutputSize() {
        assertEquals(2, FurnaceMath.producedItems(0, 6, 3));
        assertEquals(0, FurnaceMath.producedItems(4, 4, 1));
        assertEquals(1, FurnaceMath.producedItems(63, 64, 1));
    }

    @Test
    void canCookRequiresInputAndSpace() {
        FurnaceMath.Rules rules = rules(200, 1600);
        assertFalse(FurnaceMath.canCook(new FurnaceMath.State(0, 0, 0, 0, 0), rules));
        assertTrue(FurnaceMath.canCook(new FurnaceMath.State(0, 0, 1, 0, 0), rules));
        assertFalse(FurnaceMath.canCook(new FurnaceMath.State(0, 0, 1, 0, 64), rules));
    }

    @Test
    void countsNeverGoNegative() {
        FurnaceMath.State state = new FurnaceMath.State(-5, -5, -2, -1, -3);
        FurnaceMath.step(state, rules(200, 1600), 100);

        assertTrue(state.inputCount >= 0);
        assertTrue(state.fuelCount >= 0);
        assertTrue(state.resultCount >= 0);
        assertTrue(state.burnTime >= 0);
        assertTrue(state.cookTime >= 0);
    }
}
