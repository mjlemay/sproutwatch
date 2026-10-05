package dev.hytalemodding.sproutwatch.ui;

import dev.hytalemodding.sproutwatch.ui.SproutwatchActionsTest.FakeHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageStateTest {

    private static List<String> labels(String fill) {
        return Collections.nCopies(PageState.SELECTORS.size(), fill);
    }

    @Test void builtFromASnapshotTheLabelsAndControlsFollowTheSnapshot() {
        StatusSnapshot snapshot = new SproutwatchActions(new FakeHost()).snapshot();
        PageState state = PageState.of(snapshot, "lookup line", "lists");

        List<String> expected = new ArrayList<>(snapshot.detailValues());
        expected.add(snapshot.runLabel());
        expected.add(snapshot.beginCaption());
        expected.add("lookup line");
        assertEquals(expected, state.labels());
        assertEquals(PageState.SELECTORS.size(), state.labels().size());
        assertEquals(snapshot.listenerTone(), state.listenerTone());
        assertEquals(snapshot.penSet(), state.penSet());
        assertEquals(snapshot.runButton(), state.runButton());
        assertEquals("lists", state.listsKey());
    }

    @Test void selectorsCoverTheDetailCellsThenRunLabelBeginCaptionAndLookupLine() {
        List<String> expected = new ArrayList<>();
        for (String id : StatusSnapshot.DETAIL_IDS) expected.add(id + ".Text");
        expected.add("#RunLabel.Text");
        expected.add("#BeginCaption.Text");
        expected.add("#LookupLabel.Text");
        assertEquals(expected, PageState.SELECTORS);
    }

    @Test void aChangeInOnlyTheRunButtonCountsAsAChange() {
        PageState starting = new PageState(labels("x"), "ValueGood", true, "start", "lists");
        PageState stopping = new PageState(labels("x"), "ValueGood", true, "stop", "lists");
        assertNotEquals(starting, stopping);
    }

    @Test void aChangeInOnlyTheListsKeyCountsAsAChange() {
        PageState before = new PageState(labels("x"), "ValueGood", true, "start", "a|b|c");
        PageState after = new PageState(labels("x"), "ValueGood", true, "start", "a|b|c,d");
        assertNotEquals(before, after);
    }

    @Test void aChangeInOnlyTheListenerToneOrPenCountsAsAChange() {
        PageState base = new PageState(labels("x"), "ValueGood", true, "start", "lists");
        assertNotEquals(base, new PageState(labels("x"), "ValueBad", true, "start", "lists"));
        assertNotEquals(base, new PageState(labels("x"), "ValueGood", false, "start", "lists"));
        assertNotEquals(base, new PageState(labels("y"), "ValueGood", true, "start", "lists"));
    }

    @Test void identicalInputsAreEqual() {
        StatusSnapshot snapshot = new SproutwatchActions(new FakeHost()).snapshot();
        PageState first = PageState.of(snapshot, "lookup", "lists");
        PageState second = PageState.of(snapshot, "lookup", "lists");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        // Equality is by value, not by list implementation.
        assertEquals(new PageState(new ArrayList<>(labels("x")), "ValueGood", true, "start", "lists"),
            new PageState(labels("x"), "ValueGood", true, "start", "lists"));
    }

    @Test void aWrongLabelCountFailsLoudly() {
        List<String> tooMany = new ArrayList<>(labels("x"));
        tooMany.add("ValueGood"); // a control value appended to the labels, as the old list did
        assertThrows(IllegalArgumentException.class, () -> new PageState(tooMany, "ValueGood", true, "start", "lists"));
        List<String> tooFew = labels("x").subList(1, PageState.SELECTORS.size());
        assertThrows(IllegalArgumentException.class, () -> new PageState(tooFew, "ValueGood", true, "start", "lists"));
    }

    @Test void labelsAreCopiedSoALaterChangeToTheSourceListDoesNotLeakIn() {
        List<String> source = new ArrayList<>(labels("x"));
        PageState state = new PageState(source, "ValueGood", true, "start", "lists");
        source.set(0, "changed");
        assertEquals(labels("x"), state.labels());
    }
}
