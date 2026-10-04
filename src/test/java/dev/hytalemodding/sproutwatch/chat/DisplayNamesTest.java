package dev.hytalemodding.sproutwatch.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DisplayNamesTest {

    @Test void nameForFallsBackToTheKey() {
        DisplayNames names = new DisplayNames();
        assertEquals("some_login", names.nameFor("some_login"));
        assertEquals("yt:UCabc", names.nameFor("yt:UCabc"));
    }

    @Test void putStoresTheSanitizedNameAndLatestWins() {
        DisplayNames names = new DisplayNames();
        names.put("yt:UCabc", "  Lofi   Fan ");
        assertEquals("Lofi Fan", names.nameFor("yt:UCabc"));
        names.put("yt:UCabc", "Renamed");
        assertEquals("Renamed", names.nameFor("yt:UCabc"));
    }

    @Test void blankOrNullNameRemovesTheEntry() {
        DisplayNames names = new DisplayNames();
        names.put("yt:UCabc", "Fan");
        names.put("yt:UCabc", "   ");
        assertEquals("yt:UCabc", names.nameFor("yt:UCabc"));
        names.put("yt:UCabc", "Fan");
        names.put("yt:UCabc", null);
        assertEquals("yt:UCabc", names.nameFor("yt:UCabc"));
        names.put("yt:UCabc", "Fan");
        names.put("yt:UCabc", "\u200B\u0007");   // sanitizes to empty
        assertEquals("yt:UCabc", names.nameFor("yt:UCabc"));
    }

    @Test void blankOrNullKeyIsIgnored() {
        DisplayNames names = new DisplayNames();
        names.put(null, "Fan");
        names.put("  ", "Fan");
        assertEquals("  ", names.nameFor("  "));
    }

    @Test void clearDropsEveryName() {
        DisplayNames names = new DisplayNames();
        names.put("yt:a", "A");
        names.put("yt:b", "B");
        names.clear();
        assertEquals("yt:a", names.nameFor("yt:a"));
        assertEquals("yt:b", names.nameFor("yt:b"));
    }

    @Test void sanitizeStripsControlCharacters() {
        assertEquals("ab c", DisplayNames.sanitize("a\u0000b\u0007 c\u007F"));
        assertEquals("a b", DisplayNames.sanitize("a\tb\n"));
    }

    @Test void sanitizeStripsZeroWidthAndDirectionOverrides() {
        assertEquals("evil", DisplayNames.sanitize("\u202Eevil\u200B"));
        assertEquals("ab", DisplayNames.sanitize("a\u200Cb\u2066\uFEFF"));
        assertEquals("abcd", DisplayNames.sanitize("\u202Aa\u202Bb\u202Dc\u2069d\u2067\u2068"));
    }

    @Test void sanitizeKeepsZwjEmojiSequences() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";   // man ZWJ woman ZWJ girl
        assertEquals(family, DisplayNames.sanitize(family));
        assertEquals("Fan " + family, DisplayNames.sanitize("Fan  " + family + "\u200B"));
    }

    @Test void sanitizeKeepsSkinToneAndVariationSelectors() {
        assertEquals("\uD83D\uDC4D\uD83C\uDFFD", DisplayNames.sanitize("\uD83D\uDC4D\uD83C\uDFFD"));   // thumbs up, medium skin
        assertEquals("\u2764\uFE0F", DisplayNames.sanitize("\u2764\uFE0F"));
    }

    @Test void sanitizeDropsPointlessZwj() {
        assertEquals("", DisplayNames.sanitize("\u200D\u200D\u200D"));
        assertEquals("a b", DisplayNames.sanitize("a\u200D b"));
        assertEquals("a b", DisplayNames.sanitize("a \u200D b"));
        assertEquals("ab", DisplayNames.sanitize("\u200Dab\u200D"));
        assertEquals("ab", DisplayNames.sanitize("a\u200D\u200Db"));
    }

    @Test void onlyZwjNameRemovesTheEntry() {
        DisplayNames names = new DisplayNames();
        names.put("yt:UCabc", "Fan");
        names.put("yt:UCabc", "\u200D\u200D");
        assertEquals("yt:UCabc", names.nameFor("yt:UCabc"));
    }

    @Test void capNeverLeavesATrailingZwj() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        // 30 x's + man (31) + ZWJ (32): the cut lands on a ZWJ, which must be dropped.
        String capped = DisplayNames.sanitize("x".repeat(30) + family);
        assertEquals("x".repeat(30) + "\uD83D\uDC68", capped);
        assertFalse(capped.endsWith("\u200D"));
    }

    @Test void sanitizeDropsLoneSurrogates() {
        assertEquals("ab", DisplayNames.sanitize("a\uD83Db"));
        assertEquals("ab", DisplayNames.sanitize("a\uDC4Bb"));
        assertEquals("", DisplayNames.sanitize("\uD83D"));
        assertEquals("a\uD83D\uDC4B", DisplayNames.sanitize("a\uD83D\uDC4B\uD83D"));   // valid pair kept
    }

    @Test void sanitizeBlankLookingNamesAreEmpty() {
        assertEquals("", DisplayNames.sanitize("\u3164"));                 // Hangul filler
        assertEquals("", DisplayNames.sanitize("\u2800\u2800"));           // braille blank
        assertEquals("", DisplayNames.sanitize("\u115F\u1160"));           // Hangul choseong/jungseong fillers
        assertEquals("", DisplayNames.sanitize("\uFFA0"));                 // halfwidth Hangul filler
        assertEquals("", DisplayNames.sanitize("\u0301\u0301"));           // combining marks alone
        assertEquals("a b", DisplayNames.sanitize("a\u3164b"));
    }

    @Test void blankLookingNameRemovesTheEntry() {
        DisplayNames names = new DisplayNames();
        names.put("yt:UCabc", "Fan");
        names.put("yt:UCabc", "\u3164\u3164");
        assertEquals("yt:UCabc", names.nameFor("yt:UCabc"));
    }

    @Test void sanitizeKeepsPunctuationAndSymbolOnlyNames() {
        assertEquals("!!!", DisplayNames.sanitize("!!!"));
        assertEquals("\u2605", DisplayNames.sanitize("\u2605"));   // black star
    }

    @Test void sanitizeKeepsAccentsIntact() {
        assertEquals("\u00E9", DisplayNames.sanitize("\u00E9"));          // precomposed
        assertEquals("e\u0301", DisplayNames.sanitize("e\u0301"));        // e + combining acute
    }

    @Test void sanitizeCollapsesIdeographicSpaces() {
        assertEquals("a b", DisplayNames.sanitize("a\u3000\u3000b"));
    }

    @Test void capCutJustAfterAJoinerDropsIt() {
        assertEquals("x".repeat(30) + "a", DisplayNames.sanitize("x".repeat(30) + "a\u200Db"));
    }

    @Test void sanitizeKeepsEmojiAndNonLatinLetters() {
        assertEquals("Viewer 👋", DisplayNames.sanitize("Viewer 👋"));
        assertEquals("ロウファイ 女の子", DisplayNames.sanitize("ロウファイ 女の子"));
        assertEquals("Ünïcødé Ελληνικά", DisplayNames.sanitize("Ünïcødé Ελληνικά"));
    }

    @Test void sanitizeCollapsesWhitespaceAndTrims() {
        assertEquals("a b c", DisplayNames.sanitize("  a \t\n b\u00A0\u00A0c  "));
    }

    @Test void sanitizeCapsAt32CodePointsWithoutSplittingSurrogates() {
        String base = "x".repeat(31);
        String capped = DisplayNames.sanitize(base + "👋👋");
        assertEquals(base + "👋", capped);
        assertEquals(32, capped.codePointCount(0, capped.length()));
        assertFalse(Character.isHighSurrogate(capped.charAt(capped.length() - 1)));

        assertEquals("y".repeat(32), DisplayNames.sanitize("y".repeat(40)));
        assertEquals("z".repeat(32), DisplayNames.sanitize("z".repeat(32)));
    }

    @Test void sanitizeNullIsEmpty() {
        assertEquals("", DisplayNames.sanitize(null));
        assertEquals("", DisplayNames.sanitize("   "));
    }
}
