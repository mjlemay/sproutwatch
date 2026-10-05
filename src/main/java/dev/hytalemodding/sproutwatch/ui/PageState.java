package dev.hytalemodding.sproutwatch.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * What the settings page last showed of the live status, compared with equals() to gate pushes
 * (see SproutwatchSettingsPage.refreshStatus). Pure, so it is unit-testable without the engine.
 *
 * @param labels       label texts in {@link #SELECTORS} order: the Details cells, the run label, the
 *                     begin caption, the Viewers tab's YouTube lookup line. Never a field value.
 * @param listenerTone style name of the Listener cell (see {@link StatusSnapshot#listenerTone()})
 * @param penSet       whether a pen is placed, which enables Wrangle
 * @param runButton    which run button shows: "start", "connecting" or "stop"
 * @param listsKey     fingerprint of what the Viewers lists show; a change rebuilds the page so new
 *                     rows appear. Never pushed as a label.
 */
record PageState(List<String> labels, String listenerTone, boolean penSet, String runButton, String listsKey) {

    /** Selectors of the labels, in {@link #labels()} order. */
    static final List<String> SELECTORS = selectors();

    private static List<String> selectors() {
        List<String> selectors = new ArrayList<>();
        for (String id : StatusSnapshot.DETAIL_IDS) selectors.add(id + ".Text");
        selectors.add("#RunLabel.Text");
        selectors.add("#BeginCaption.Text");
        selectors.add("#LookupLabel.Text");
        return List.copyOf(selectors);
    }

    PageState {
        labels = List.copyOf(labels);
        if (labels.size() != SELECTORS.size()) {
            throw new IllegalArgumentException("PageState needs " + SELECTORS.size() + " labels (one per selector), got " + labels.size());
        }
    }

    /** The state the page shows for this snapshot, YouTube lookup line ("" when none) and lists fingerprint. */
    static PageState of(StatusSnapshot snapshot, String lookupLine, String listsKey) {
        List<String> labels = new ArrayList<>(snapshot.detailValues());
        labels.add(snapshot.runLabel());
        labels.add(snapshot.beginCaption());
        labels.add(lookupLine);
        return new PageState(labels, snapshot.listenerTone(), snapshot.penSet(), snapshot.runButton(), listsKey);
    }
}
