package dev.hytalemodding.sproutwatch.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudLayoutTest {

    @Test void theDefaultMessageFitsOnOneLine() {
        assertEquals(1, HudLayout.lines("Type !sprout in chat to be added"));
    }

    @Test void shortMessagesTakeOneLine() {
        assertEquals(1, HudLayout.lines("Say hi!"));
        assertEquals(HudLayout.CHROME + HudLayout.LINE_HEIGHT, HudLayout.height("Say hi!"));
    }

    @Test void longMessagesWrapByWord() {
        String eighty = "Chat with !sprout and a little creature joins the pen; goblins sometimes go boom";
        assertEquals(3, HudLayout.lines(eighty));
    }

    @Test void aWordLongerThanALineTakesExtraLines() {
        assertEquals(2, HudLayout.lines("x".repeat(HudLayout.CHARS_PER_LINE + 1)));
        assertEquals(3, HudLayout.lines("hi " + "x".repeat(HudLayout.CHARS_PER_LINE + 1)));
    }

    @Test void theBarFillsAcrossTheTick() {
        assertEquals(0f, HudLayout.progress(10_000, 10), 1e-6);
        assertEquals(0.5f, HudLayout.progress(5_000, 10), 1e-6);
        assertEquals(1f, HudLayout.progress(0, 10), 1e-6);
    }

    @Test void theBarIsEmptyWhenTheTickerIsStopped() {
        assertEquals(0f, HudLayout.progress(-1, 10), 1e-6);
    }

    @Test void aDelayLongerThanTheTickClampsToEmpty() {
        // Right after the interval is lengthened the old schedule can report more than one period.
        assertEquals(0f, HudLayout.progress(60_000, 10), 1e-6);
    }
}
