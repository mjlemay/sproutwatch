package dev.hytalemodding.sproutwatch.config;

/** Test helper: lets other test packages build a default config. */
public final class SproutwatchConfigAccess {
    public SproutwatchConfig fresh() {
        return new SproutwatchConfig();
    }
}
