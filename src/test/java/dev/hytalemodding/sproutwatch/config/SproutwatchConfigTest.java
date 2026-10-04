package dev.hytalemodding.sproutwatch.config;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SproutwatchConfigTest {

    private static BsonDocument loadShippedJson() throws IOException {
        try (InputStream in = SproutwatchConfigTest.class.getResourceAsStream("/Sproutwatch_config.json")) {
            assertNotNull(in, "Sproutwatch_config.json must be on the test classpath");
            return BsonDocument.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String[] toStringArray(BsonArray arr) {
        return arr.stream().map(BsonValue::asString).map(v -> v.getValue()).toArray(String[]::new);
    }

    @Test void defaultsMatchTheShippedJson() throws IOException {
        BsonDocument json = loadShippedJson();
        assertEquals(39, json.size(), "JSON key count changed; update this test and the getter list together");

        SproutwatchConfig c = new SproutwatchConfig();
        assertEquals(json.getString("TwitchChannel").getValue(), c.getTwitchChannel());
        assertEquals(json.getBoolean("AutoStartOnBoot").getValue(), c.isAutoStartOnBoot());
        assertEquals(json.getString("ViewerFilter").getValue(), c.getViewerFilter());
        assertEquals(json.getInt32("QuietSeconds").getValue(), c.getQuietSeconds());
        assertEquals(json.getString("Creatures").getValue(), c.getCreatures());
        assertEquals(json.getInt32("TickSeconds").getValue(), c.getTickSeconds());
        assertEquals(json.getInt32("GraceSeconds").getValue(), c.getGraceSeconds());
        assertEquals(json.getInt32("MaxSprouts").getValue(), c.getMaxSprouts());
        assertArrayEquals(toStringArray(json.getArray("Roles")), c.getRoles());
        assertArrayEquals(toStringArray(json.getArray("IgnoreUsers")), c.ignoredViewers().toArray(new String[0]));
        assertArrayEquals(toStringArray(json.getArray("AllowUsers")), c.allowedViewers().toArray(new String[0]));
        assertEquals(json.getString("QueueCommand").getValue(), c.getQueueCommand());
        assertEquals(json.getString("PenWorld").getValue(), c.getPenWorld());
        assertEquals(json.getInt32("PenX").getValue(), c.getPenX());
        assertEquals(json.getInt32("PenY").getValue(), c.getPenY());
        assertEquals(json.getInt32("PenZ").getValue(), c.getPenZ());
        assertEquals(json.getInt32("PenSizeX").getValue(), c.getPenSizeX());
        assertEquals(json.getInt32("PenSizeY").getValue(), c.getPenSizeY());
        assertEquals(json.getInt32("PenSizeZ").getValue(), c.getPenSizeZ());
        assertEquals(json.getBoolean("ChairSet").getValue(), c.isChairSet());
        assertEquals(json.getInt32("ChairX").getValue(), c.getChairX());
        assertEquals(json.getInt32("ChairY").getValue(), c.getChairY());
        assertEquals(json.getInt32("ChairZ").getValue(), c.getChairZ());
        assertEquals(json.getString("PenFacing").getValue(), c.getPenFacing());
        assertEquals(json.getDouble("CameraHeight").getValue(), c.getCameraHeight());
        assertEquals(json.getDouble("CameraBack").getValue(), c.getCameraBack());
        assertEquals(json.getDouble("CameraFov").getValue(), c.getCameraFov());
        assertEquals(json.getBoolean("CameraFlip").getValue(), c.isCameraFlip());
        assertEquals(json.getBoolean("PersistSprouts").getValue(), c.isPersistSprouts());
        assertEquals(json.getString("PenPrefab").getValue(), c.getPenPrefab());
        assertEquals(json.getBoolean("TwitchEnabled").getValue(), c.isTwitchEnabled());
        assertEquals(json.getBoolean("YouTubeEnabled").getValue(), c.isYouTubeEnabled());
        assertEquals(json.getString("YouTubeHandle").getValue(), c.getYouTubeHandle());
        assertEquals(json.getString("YouTubeVideo").getValue(), c.getYouTubeVideo());
        assertEquals(json.getString("YouTubeApiKey").getValue(), c.getYouTubeApiKey());
        assertEquals(json.getDouble("YouTubeStreamHours").getValue(), c.getYouTubeStreamHours());
        assertEquals(json.getString("YouTubeQuotaDay").getValue(), c.getYouTubeQuotaDay());
        assertEquals(json.getInt32("YouTubeQuotaUsed").getValue(), c.getYouTubeQuotaUsed());
        assertEquals(0, json.getArray("YouTubeLabels").size());
        assertTrue(c.youTubeLabels().isEmpty());
        assertTrue(c.isTwitchEnabled());
        assertFalse(c.isYouTubeEnabled());
        assertEquals("", c.getYouTubeApiKey());
        assertEquals(8.0, c.getYouTubeStreamHours());
        assertEquals(0, c.getYouTubeQuotaUsed());
        assertFalse(c.isPenSet());
    }

    @Test void youTubeGettersTrimAndClamp() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setYouTubeHandle("  @Streamer ");
        c.setYouTubeVideo(" dQw4w9WgXcQ ");
        c.setYouTubeApiKey(" AIzaTESTKEY0123456789abcd\n");
        assertEquals("@Streamer", c.getYouTubeHandle());
        assertEquals("dQw4w9WgXcQ", c.getYouTubeVideo());
        assertEquals("AIzaTESTKEY0123456789abcd", c.getYouTubeApiKey());
        c.setYouTubeHandle(null);
        assertEquals("", c.getYouTubeHandle());
        c.setYouTubeStreamHours(0.2);
        assertEquals(1.0, c.getYouTubeStreamHours());
        c.setYouTubeStreamHours(99);
        assertEquals(24.0, c.getYouTubeStreamHours());
        c.setYouTubeStreamHours(Double.NaN);
        assertEquals(8.0, c.getYouTubeStreamHours());
        c.setYouTubeStreamHours(2.5);
        assertEquals(2.5, c.getYouTubeStreamHours());
        c.setYouTubeQuota(" 2026-10-03 ", -5);
        assertEquals("2026-10-03", c.getYouTubeQuotaDay());
        assertEquals(0, c.getYouTubeQuotaUsed());
        c.setYouTubeQuota("2026-10-03", 42);
        assertEquals(42, c.getYouTubeQuotaUsed());
    }

    @Test void youTubeConfiguredNeedsEnabledKeyAndAHandleOrVideo() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertFalse(c.youTubeConfigured());
        c.setYouTubeApiKey("AIzaTESTKEY0123456789abcd");
        c.setYouTubeHandle("@streamer");
        assertFalse(c.youTubeConfigured(), "off by default");
        c.setYouTubeEnabled(true);
        assertTrue(c.youTubeConfigured());
        c.setYouTubeApiKey("   ");
        assertFalse(c.youTubeConfigured(), "no key");
        c.setYouTubeApiKey("AIzaTESTKEY0123456789abcd");
        c.setYouTubeHandle("not a handle!!");
        assertFalse(c.youTubeConfigured(), "handle does not parse and no video");
        c.setYouTubeVideo("https://youtu.be/dQw4w9WgXcQ");
        assertTrue(c.youTubeConfigured(), "a pasted stream link is enough");
        c.setTwitchEnabled(false);
        assertFalse(c.isTwitchEnabled());
    }

    @Test void twitchReadyNeedsEnabledAndAChannel() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertFalse(c.twitchReady());
        c.setTwitchChannel("streamer");
        assertTrue(c.twitchReady());
        c.setTwitchEnabled(false);
        assertFalse(c.twitchReady());
        assertEquals("Twitch is off and YouTube is off. Turn Twitch on, or turn YouTube on and give it an API key and your @handle or a stream link.",
            c.nothingToStartReason());
        c.setYouTubeHandle("@streamer");
        c.setYouTubeApiKey("AIzaTESTKEY0123456789abcd");
        assertEquals("Twitch is off and YouTube is off. Turn Twitch on, or turn YouTube on.", c.nothingToStartReason());
        assertEquals(null, c.youTubeMissing());
    }

    @Test void clampsBadValues() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setTickSeconds(1);
        assertEquals(5, c.getTickSeconds());
        c.setGraceSeconds(-9);
        assertEquals(0, c.getGraceSeconds());
        c.setMaxSprouts(0);
        assertEquals(1, c.getMaxSprouts());
        c.setCamera(16, 20, 500);
        assertEquals(170.0, c.getCameraFov());
        c.setRoles(new String[0]);
        assertEquals(3, c.getRoles().length);
    }

    @Test void allRolesFlattensGroupsForTheSweep() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setRoles(new String[]{"A", "B| C |", " D"});
        assertEquals(java.util.Set.of("A", "B", "C", "D"), c.allRoles());
    }

    @Test void theSweepStillFindsSproutsSpawnedUnderTheOldRoleNames() {
        // Regression: after the upgrade the boot sweep only knew Sprout_* names, so Kweebec_Sapling /
        // Kweebec_Sproutling sprouts left from an earlier run stayed in the pen across a relog.
        SproutwatchConfig c = new SproutwatchConfig();
        c.setRoles(new String[]{"Kweebec_Seedling", "Kweebec_Sproutling", "Kweebec_Sapling"});
        c.upgradeLegacyRoles();
        java.util.Set<String> sweep = c.sweepRoles();
        assertTrue(sweep.containsAll(java.util.Set.of("Kweebec_Sapling", "Kweebec_Sproutling", "Kweebec_Seedling")), sweep.toString());
        assertTrue(sweep.containsAll(c.allRoles()), "and every current role");
    }

    @Test void theOldDefaultRolesAreUpgradedButCustomOnesAreKept() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setRoles(new String[]{"Kweebec_Seedling", "Kweebec_Sproutling", "Kweebec_Sapling"});
        assertTrue(c.upgradeLegacyRoles());
        assertEquals("Sprout_Sproutling", c.getRoles()[1]);
        assertTrue(c.getRoles()[2].startsWith("Sprout_Sapling_Red|"), c.getRoles()[2]);
        assertFalse(c.upgradeLegacyRoles(), "already upgraded");
        c.setRoles(new String[]{"Kweebec_Sapling"});
        assertFalse(c.upgradeLegacyRoles(), "a customized list is left alone");
        assertArrayEquals(new String[]{"Kweebec_Sapling"}, c.getRoles());
    }

    @Test void getRolesCleansEntries() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setRoles(new String[]{"A", "A", null, " "});
        assertArrayEquals(new String[]{"A"}, c.getRoles());
    }

    @Test void normalizesChannel() {
        assertEquals("mertie_tv", SproutwatchConfig.normalizeChannel("#Mertie_TV!"));
        assertEquals("", SproutwatchConfig.normalizeChannel(null));
        SproutwatchConfig c = new SproutwatchConfig();
        c.setTwitchChannel("@Streamer");
        assertEquals("streamer", c.getTwitchChannel());
    }

    @Test void ignoredViewersIncludeDefaultsAndChannel() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setTwitchChannel("Streamer");
        Set<String> ignored = c.ignoredViewers();
        assertTrue(ignored.containsAll(Set.of("nightbot", "streamelements", "streamlabs", "streamer")));
        assertFalse(ignored.contains(""));
    }

    @Test void ignoredViewersNormalizesIrcSymbols() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setIgnoreUsersForTest(new String[]{"@NightBot"});
        assertTrue(c.ignoredViewers().contains("nightbot"));
    }

    @Test void penAndChairSettersRoundTrip() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setPen("uuid-1", 10, 64, 20, 12, 4, 16);
        assertTrue(c.isPenSet());
        assertEquals("uuid-1", c.getPenWorld());
        assertEquals(10, c.getPenX());
        assertEquals(64, c.getPenY());
        assertEquals(20, c.getPenZ());
        assertEquals(12, c.getPenSizeX());
        assertEquals(4, c.getPenSizeY());
        assertEquals(16, c.getPenSizeZ());
        c.setChair(true, 16, 65, 19);
        assertTrue(c.isChairSet());
        assertEquals(16, c.getChairX());
        assertEquals(65, c.getChairY());
        assertEquals(19, c.getChairZ());
        c.setPenFacing("south");
        assertEquals("south", c.getPenFacing());
    }

    @Test void viewerFilterModeDecidesWhichListApplies() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setAllowUsersForTest(new String[] {"alice"});
        c.setIgnoreUsersForTest(new String[] {"bob"});
        assertEquals("ignore", c.getViewerFilter(), "default: everyone except the ignore list");
        assertFalse(c.isAllowMode());
        assertTrue(c.passesFilter("carol"));
        assertTrue(c.passesFilter("alice"));
        assertFalse(c.passesFilter("bob"), "ignore list always wins");
        c.setAllowMode(true);
        assertEquals("allow", c.getViewerFilter());
        assertTrue(c.passesFilter("alice"));
        assertFalse(c.passesFilter("carol"), "allow mode: only the allow list");
        c.setAllowUsersForTest(new String[0]);
        assertFalse(c.passesFilter("carol"), "allow mode with an empty list: nobody");
        c.setViewerFilterForTest("Bogus");
        assertEquals("ignore", c.getViewerFilter(), "unknown values fall back to ignore");
    }

    @Test void cameraGettersFallBackWhenNotFinite() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setCamera(Double.NaN, Double.NaN, Double.NaN);
        assertEquals(15.0, c.getCameraHeight());
        assertEquals(10.0, c.getCameraBack());
        assertEquals(45.0, c.getCameraFov());
    }

    @Test void getPenFacingNormalizesUnknownValues() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setPenFacing("sideways");
        assertEquals("north", c.getPenFacing());
    }

    @Test void allowListNormalizesAndDedupes() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertTrue(c.allowedViewers().isEmpty(), "empty means everyone");
        c.setAllowUsersForTest(new String[]{"@Alice", "alice", " ", null, "Bob!"});
        assertEquals(List.of("alice", "bob"), List.copyOf(c.allowedViewers()));
    }

    @Test void allowAndIgnoreMutatorsReportChange() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertTrue(c.addAllow("@Carol"));
        assertFalse(c.addAllow("carol"));
        assertFalse(c.addAllow("  "));
        assertFalse(c.addAllow(null));
        assertEquals(Set.of("carol"), c.allowedViewers());
        assertTrue(c.removeAllow("CAROL"));
        assertFalse(c.removeAllow("carol"));
        assertTrue(c.allowedViewers().isEmpty());

        assertTrue(c.ignoredViewers().contains("nightbot"));
        assertFalse(c.addIgnore("NightBot"));
        assertTrue(c.addIgnore("@Spammer"));
        assertTrue(c.ignoredViewers().contains("spammer"));
        assertTrue(c.removeIgnore("spammer"));
        assertFalse(c.removeIgnore("spammer"));
        assertFalse(c.ignoredViewers().contains("spammer"));
        assertTrue(c.removeIgnore("nightbot"));
        assertFalse(c.ignoredViewers().contains("nightbot"));
        assertFalse(c.addIgnore(""));
    }

    @Test void persistDefaultsOn() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertTrue(c.isPersistSprouts());
        c.setPersistSprouts(true);
        assertTrue(c.isPersistSprouts());
        c.setPersistSprouts(false);
        assertFalse(c.isPersistSprouts());
    }

    @Test void queueCommandFallsBackWhenBlank() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertEquals("!sprout", c.getQueueCommand());
        c.setQueueCommandForTest("  !seed  ");
        assertEquals("!seed", c.getQueueCommand());
        c.setQueueCommandForTest("   ");
        assertEquals("!sprout", c.getQueueCommand());
        c.setQueueCommandForTest(null);
        assertEquals("!sprout", c.getQueueCommand());
    }

    @Test void penPrefabDefaultsAndNormalizes() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertEquals("default", c.getPenPrefab());
        c.setPenPrefab("  Castle ");
        assertEquals("castle", c.getPenPrefab(), "the config stores the name; the catalog decides whether it exists");
        c.setPenPrefab("   ");
        assertEquals("default", c.getPenPrefab());
        c.setPenPrefab(null);
        assertEquals("default", c.getPenPrefab());
    }

    // ---- YouTube allow/ignore entries (Task 10) -----------------------------------------------

    static final String CH = "UCabcdefghijklmnopqrstuV";
    static final String CH2 = "UC0123456789_-ABCDEFGHIJ";

    @Test void youTubeKeysKeepTheirCaseInMixedLists() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setIgnoreUsersForTest(new String[]{"NightBot", " yt:" + CH + " ", "yt:" + CH, "@Bob"});
        assertEquals(List.of("nightbot", "yt:" + CH, "bob"), List.copyOf(c.ignoredViewers()));
        c.setAllowUsersForTest(new String[]{"Alice", "yt:" + CH2});
        assertEquals(List.of("alice", "yt:" + CH2), List.copyOf(c.allowedViewers()));
    }

    @Test void invalidYouTubeEntriesAreDropped() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setIgnoreUsersForTest(new String[]{"yt:", "yt:UCshort", "yt:" + CH.toLowerCase(java.util.Locale.ROOT),
            "yt:@handle", "yt:" + CH + "x", "carol"});
        assertEquals(Set.of("carol"), c.ignoredViewers());
        assertFalse(c.addIgnore("yt:nope"));
        assertFalse(c.addAllow("yt:"));
    }

    @Test void filterAndIgnoreWorkForYouTubeKeys() {
        SproutwatchConfig c = new SproutwatchConfig();
        String yt = "yt:" + CH;
        assertTrue(c.passesFilter(yt));
        assertTrue(c.addIgnore(yt));
        assertFalse(c.addIgnore(yt), "already there");
        assertTrue(c.ignoredViewers().contains(yt));
        assertFalse(c.passesFilter(yt));
        assertTrue(c.removeIgnore(yt));
        c.setAllowMode(true);
        assertFalse(c.passesFilter(yt));
        assertTrue(c.addAllow(yt));
        assertTrue(c.passesFilter(yt));
        assertFalse(c.passesFilter("yt:" + CH2));
        assertFalse(c.passesFilter("yt:" + CH.toLowerCase(java.util.Locale.ROOT)), "channel IDs are case-sensitive");
    }

    @Test void twitchChannelIsStillAlwaysIgnored() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setTwitchChannel("Streamer");
        c.addIgnore("yt:" + CH);
        assertTrue(c.ignoredViewers().containsAll(Set.of("streamer", "yt:" + CH)));
    }

    @Test void youTubeLabelsRoundTripAndAreCleanedUpWithTheEntry() {
        SproutwatchConfig c = new SproutwatchConfig();
        String yt = "yt:" + CH;
        assertTrue(c.youTubeLabel(yt).isEmpty());
        c.addAllow(yt);
        c.addIgnore(yt);
        c.setYouTubeLabel(yt, " @Streamer ");
        assertEquals(java.util.Optional.of("@Streamer"), c.youTubeLabel(yt));
        c.setYouTubeLabel(yt, "@Streamer2");
        assertEquals(java.util.Optional.of("@Streamer2"), c.youTubeLabel(yt));
        assertEquals(1, c.youTubeLabels().size(), "replaced, not duplicated");
        assertEquals(java.util.Optional.of(yt), c.youTubeKeyForLabel("@streamer2"), "label match is case-insensitive");
        assertTrue(c.youTubeKeyForLabel("@other").isEmpty());
        assertEquals("@Streamer2 (YouTube)", c.entryDisplay(yt));
        assertEquals(CH2 + " (YouTube)", c.entryDisplay("yt:" + CH2));
        assertEquals("alice", c.entryDisplay("alice"));

        c.setYouTubeLabel("alice", "@nope");   // Twitch keys never get a label
        c.setYouTubeLabel("yt:bad", "@nope");
        assertEquals(1, c.youTubeLabels().size());

        assertTrue(c.removeAllow(yt));
        assertTrue(c.youTubeLabel(yt).isPresent(), "still on the ignore list");
        assertTrue(c.removeIgnore(yt));
        assertTrue(c.youTubeLabel(yt).isEmpty(), "gone from both lists, label dropped");
        assertTrue(c.youTubeLabels().isEmpty());
    }

    @Test void malformedLabelEntriesAreIgnored() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setYouTubeLabelsForTest(new String[]{"garbage", "yt:" + CH + "=", "=@x", null, "yt:" + CH2 + "=@Two"});
        assertEquals(java.util.Optional.of("@Two"), c.youTubeLabel("yt:" + CH2));
        assertTrue(c.youTubeLabel("yt:" + CH).isEmpty());
    }

    @Test void addYouTubeEntryAddsKeyAndLabelTogether() {
        SproutwatchConfig c = new SproutwatchConfig();
        String yt = "yt:" + CH;
        assertTrue(c.addYouTubeEntry(false, yt, "@Streamer"));
        assertTrue(c.ignoredViewers().contains(yt));
        assertEquals(java.util.Optional.of("@Streamer"), c.youTubeLabel(yt));
        assertFalse(c.addYouTubeEntry(false, yt, "@Renamed"), "already listed");
        assertEquals(java.util.Optional.of("@Renamed"), c.youTubeLabel(yt), "label refreshed anyway");
        assertFalse(c.addYouTubeEntry(true, "alice", "@x"), "Twitch logins are not YouTube entries");
        assertFalse(c.allowedViewers().contains("alice"));
        assertTrue(c.addYouTubeEntry(true, yt, null));
        assertTrue(c.allowedViewers().contains(yt));
    }
}
