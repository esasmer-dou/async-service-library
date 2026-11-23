package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueEntryFilterTest {
    @Test
    void parsesAndMatches() {
        QueueEntryFilter filter = QueueEntryFilter.parse("states=failed, pending;category=TIMEOUT;from=100;to=200", 5);
        assertEquals(Set.of("FAILED", "PENDING"), filter.states());
        assertEquals(5, filter.max());
        assertTrue(filter.matches("FAILED", "timeout", 100));
        assertTrue(filter.matches("PENDING", "TIMEOUT", 200));
        assertFalse(filter.matches("FAILED", "TIMEOUT", 201));
        assertFalse(filter.matches("FAILED", "BUSINESS", 150));
        assertFalse(filter.matches("RETRY_WAIT", "TIMEOUT", 150));
        assertFalse(filter.matches("IN_PROGRESS", "TIMEOUT", 150));
    }

    @Test
    void defaultsToFailedEntriesOfAnyCategoryAndTime() {
        QueueEntryFilter filter = QueueEntryFilter.parse(null, 1);
        assertEquals(Set.of("FAILED"), filter.states());
        assertTrue(filter.matches("FAILED", null, Long.MIN_VALUE));
        assertTrue(filter.matches("FAILED", "X", Long.MAX_VALUE));

        QueueEntryFilter uncategorised = QueueEntryFilter.parse("category=-", 1);
        assertTrue(uncategorised.matches("FAILED", null, 0));
        assertTrue(uncategorised.matches("FAILED", "", 0));
        assertFalse(uncategorised.matches("FAILED", "BUSINESS", 0));
    }

    @Test
    void rejectsWhatCouldTouchTooMuchOrTheWrongEntries() {
        assertThrows(IllegalArgumentException.class, () -> QueueEntryFilter.parse("states=IN_PROGRESS", 1));
        assertThrows(IllegalArgumentException.class, () -> QueueEntryFilter.parse("", 0));
        assertThrows(IllegalArgumentException.class, () -> QueueEntryFilter.parse("", QueueEntryFilter.MAX_PER_COMMAND + 1));
        assertThrows(IllegalArgumentException.class, () -> QueueEntryFilter.parse("from=5;to=4", 1));
        assertThrows(IllegalArgumentException.class, () -> QueueEntryFilter.parse("colour=red", 1));
        assertThrows(IllegalArgumentException.class, () -> QueueEntryFilter.parse("states", 1));
        assertThrows(NumberFormatException.class, () -> QueueEntryFilter.parse("from=yesterday", 1));
    }
}
