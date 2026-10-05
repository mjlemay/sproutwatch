package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReconcileRequestTest {

    private static final long NOW = 1_000_000L;
    private static final long GRACE = 300_000L;

    @Test void builderDefaultsMatchThePlainFirstSeenCase() {
        ReconcileRequest request = ReconcileRequest.builder(Map.of("a", 1L), Map.of(), 30, NOW)
            .graceMillis(GRACE)
            .build();
        assertEquals(new ReconcileRequest(Map.of("a", 1L), Map.of(), List.of(), 30, GRACE, NOW,
            false, Set.of(), Map.of(), 0L), request);
    }

    @Test void namedBuilderMethodsKeepTheLongsApart() {
        ReconcileRequest request = ReconcileRequest.builder(Map.of(), Map.of(), 2, NOW)
            .graceMillis(GRACE)
            .queue(List.of("q"))
            .persist(true)
            .guests(Set.of("g"))
            .quiet(Map.of("a", 5L), 600_000L)
            .build();
        assertEquals(NOW, request.now());
        assertEquals(GRACE, request.graceMillis());
        assertEquals(600_000L, request.quietMillis());
        assertEquals(2, request.cap());
        assertEquals(List.of("q"), request.queue());
        assertTrue(request.persist());
        assertEquals(Set.of("g"), request.guests());
        assertEquals(Map.of("a", 5L), request.lastActive());
    }

    @Test void graceMillisIsRequired() {
        ReconcileRequest.Builder builder = ReconcileRequest.builder(Map.of(), Map.of(), 30, NOW);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test void constructorRejectsNullCollections() {
        assertThrows(NullPointerException.class, () ->
            new ReconcileRequest(null, Map.of(), List.of(), 1, GRACE, NOW, false, Set.of(), Map.of(), 0L));
        assertThrows(NullPointerException.class, () ->
            new ReconcileRequest(Map.of(), null, List.of(), 1, GRACE, NOW, false, Set.of(), Map.of(), 0L));
        assertThrows(NullPointerException.class, () ->
            new ReconcileRequest(Map.of(), Map.of(), null, 1, GRACE, NOW, false, Set.of(), Map.of(), 0L));
        assertThrows(NullPointerException.class, () ->
            new ReconcileRequest(Map.of(), Map.of(), List.of(), 1, GRACE, NOW, false, null, Map.of(), 0L));
        assertThrows(NullPointerException.class, () ->
            new ReconcileRequest(Map.of(), Map.of(), List.of(), 1, GRACE, NOW, false, Set.of(), null, 0L));
    }

    @Test void requestCopiesItsCollections() {
        Map<String, Long> roster = new HashMap<>(Map.of("a", 1L));
        ReconcileRequest request = ReconcileRequest.builder(roster, Map.of(), 30, NOW).graceMillis(GRACE).build();
        roster.put("b", 2L);
        assertEquals(Map.of("a", 1L), request.roster());
    }
}
