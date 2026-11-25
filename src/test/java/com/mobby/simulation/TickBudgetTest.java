package com.mobby.simulation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TickBudgetTest {
    @Test
    void overflowAndNegativeRequestsCannotIncreaseRoom() {
        TickBudget budget = new TickBudget(100);
        assertTrue(budget.tryConsume(95));
        assertFalse(budget.tryConsume(Integer.MAX_VALUE));
        assertFalse(budget.tryConsume(-20));
        assertEquals(5, budget.remaining());
    }

    @Test
    void unusedModuleAllowanceRemainsAvailableToLaterWork() {
        TickBudget parent = new TickBudget(9);
        TickBudget first = parent.share(3);
        assertFalse(first.tryConsume(4));
        assertTrue(first.tryConsume(1));
        assertTrue(parent.tryConsume(first.used()));
        assertEquals(4, parent.share(2).limit());
    }

    @Test
    void zeroAllowanceCannotAuthorizeWork() {
        assertFalse(new TickBudget(1).share(2).tryConsume(1));
        assertTrue(new TickBudget(0).isExhausted());
    }
}
