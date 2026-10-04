package dev.hytalemodding.sproutwatch.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Roster key -> name shown on the sprout's nameplate (latest seen wins). Only platforms whose key
 * is not the visible name (YouTube) put entries; anything else falls back to the key, so Twitch
 * logins map to themselves. Written from chat source threads, read on the world thread.
 */
public final class DisplayNames {

    static final int MAX_CODE_POINTS = 32;
    /** U+200D ZERO WIDTH JOINER: the one format character kept (it glues emoji sequences). */
    private static final int ZWJ = 0x200D;

    private final Map<String, String> names = new ConcurrentHashMap<>();

    /** Stores the sanitized name; a null name, or one that sanitizes to empty, drops the entry. */
    public void put(String key, String name) {
        if (key == null || key.isBlank()) return;
        String clean = sanitize(name);
        if (clean.isEmpty()) names.remove(key);
        else names.put(key, clean);
    }

    /** @return the stored display name, else the key itself. */
    public String nameFor(String key) {
        if (key == null) return null;
        return names.getOrDefault(key, key);
    }

    public void clear() {
        names.clear();
    }

    /**
     * Pure. Kept: letters in any script, combining accents, emoji, skin-tone modifiers and variation
     * selectors (none of these are format characters), and U+200D ZERO WIDTH JOINER, which glues
     * emoji sequences such as families and professions. Collapsed to one space: any whitespace (tab,
     * newline, NBSP, ideographic space) plus the blank-looking Hangul fillers (U+115F, U+1160,
     * U+3164, U+FFA0, which Java classes as letters) and the braille blank U+2800. Stripped: ISO
     * control characters, unpaired surrogates, and every other format character (zero-width space,
     * ZWNJ, bidi embeddings/overrides/isolates, BOM, and the tag characters of subdivision flags),
     * which only hide or reorder text. A ZWJ survives only between two visible code points: one at an
     * edge, beside a space or beside another ZWJ is dropped, so joiners cannot make a name invisible.
     * Trimmed and capped at 32 code points; the cap may cut inside a grapheme cluster (a flag pair, a
     * skin tone) but never yields malformed UTF-16 or a dangling ZWJ. A result with nothing visible
     * (no letter, digit, symbol or punctuation) is "", so the nameplate falls back to the key.
     * Null gives "".
     */
    static String sanitize(String raw) {
        if (raw == null) return "";
        // Pass 1: whitespace and blank fillers -> ' '; drop control, surrogate and format (except ZWJ).
        int[] cps = raw.codePoints()
            .map(cp -> isBlank(cp) ? ' ' : cp)
            .filter(cp -> cp == ZWJ || !(Character.isISOControl(cp)
                || Character.getType(cp) == Character.FORMAT
                || Character.getType(cp) == Character.SURROGATE))
            .toArray();
        // Pass 2: keep a ZWJ only between two visible neighbors; collapse spaces.
        StringBuilder out = new StringBuilder(raw.length());
        int count = 0;
        for (int i = 0; i < cps.length && count < MAX_CODE_POINTS; i++) {
            int cp = cps[i];
            if (cp == ZWJ && !(i > 0 && isVisible(cps[i - 1]) && i + 1 < cps.length && isVisible(cps[i + 1]))) continue;
            if (cp == ' ' && (out.length() == 0 || out.charAt(out.length() - 1) == ' ')) continue;
            out.appendCodePoint(cp);
            count++;
        }
        // The cap may cut a sequence right after its joiner, or leave a trailing space.
        int len = out.length();
        while (len > 0 && (out.charAt(len - 1) == ' ' || out.charAt(len - 1) == ZWJ)) len--;
        String clean = out.substring(0, len);
        return clean.codePoints().anyMatch(DisplayNames::isShown) ? clean : "";
    }

    private static boolean isBlank(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp)
            || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800;
    }

    /** A code point that draws something on its own: letter, digit, symbol or punctuation. */
    private static boolean isShown(int cp) {
        if (Character.isLetterOrDigit(cp)) return true;
        return switch (Character.getType(cp)) {
            case Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
                 Character.OTHER_SYMBOL, Character.DASH_PUNCTUATION, Character.CONNECTOR_PUNCTUATION,
                 Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean isVisible(int cp) {
        return cp != ' ' && cp != ZWJ;
    }
}
