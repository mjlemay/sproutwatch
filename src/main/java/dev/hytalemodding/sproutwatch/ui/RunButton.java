package dev.hytalemodding.sproutwatch.ui;

/**
 * Which of the Listener tab's run buttons shows (see {@link StatusSnapshot#runButton()}). The page
 * maps each value to its button with a switch that has no default, so a new constant without a
 * button fails to compile.
 */
public enum RunButton {
    /** Nothing runs: the Start button shows. */
    START,
    /** A source is still connecting or retrying: the Connecting button shows. */
    CONNECTING,
    /** Every source has settled: the Stop button shows. */
    STOP
}
