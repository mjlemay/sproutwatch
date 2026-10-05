package dev.hytalemodding.sproutwatch.youtube;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointTest {

    @Test
    void everyEndpointHasAPositiveCost() {
        for (Endpoint endpoint : Endpoint.values()) {
            assertTrue(endpoint.cost() > 0, endpoint.name());
        }
    }

    @Test
    void pathsAreTheYouTubeDataApiPaths() {
        assertEquals("channels", Endpoint.CHANNELS.path());
        assertEquals("search", Endpoint.SEARCH.path());
        assertEquals("videos", Endpoint.VIDEOS.path());
        assertEquals("liveChat/messages", Endpoint.CHAT_MESSAGES.path());
    }

    @Test
    void pathsAreUnique() {
        Set<String> paths = new HashSet<>();
        for (Endpoint endpoint : Endpoint.values()) {
            assertTrue(paths.add(endpoint.path()), endpoint.path());
        }
    }

    @Test
    void costsMatchTheMeasuredNumbers() {
        assertEquals(1, Endpoint.CHANNELS.cost());
        assertEquals(1, Endpoint.SEARCH.cost());
        assertEquals(1, Endpoint.VIDEOS.cost());
        assertEquals(2, Endpoint.CHAT_MESSAGES.cost());
    }

    @Test
    void chatReadCostIsThePacerCostPerPoll() {
        assertEquals(QuotaPacer.COST_PER_POLL, Endpoint.CHAT_MESSAGES.cost());
    }
}
