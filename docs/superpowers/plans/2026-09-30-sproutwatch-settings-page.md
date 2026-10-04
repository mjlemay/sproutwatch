# Sproutwatch Settings Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/sproutwatch settings` opens an in-game page with Status, Listener and Pen tabs that shows what the mod is doing (live, every tick) and lets the streamer change the Twitch channel, allow and ignore lists, start/stop, persist, tick interval and pen prefab, and place or clear the pen, without typing commands.

**Architecture:** One `InteractiveCustomUIPage<SettingsEvent>` (`ui/SproutwatchSettingsPage`) built from a bundled `.ui` document; tabs are groups toggled by `Visible`. Every mutation goes through a new `ui/SproutwatchActions` service that the chat commands also delegate to, so page and commands share one code path and one set of reply strings. `SproutwatchActions` talks to the plugin only through the narrow `ui/ActionsHost` interface, so it is unit-tested against a fake host. `ui/OpenPages` keeps the open pages keyed by player UUID; `PenTicker` gains an after-tick hook that asks each open page to push its status labels via `sendUpdate` (fields are never touched, so in-progress edits survive). `prefab/PenPrefabCatalog` maps prefab names to bundled resources and feeds the Pen tab dropdown; config gains `PenPrefab`.

**Tech Stack:** Java 25, Hytale server 0.6.3 (`com.azuredoom.hytale-tools` Gradle plugin), JUnit 5, engine custom UI (`UICommandBuilder`, `UIEventBuilder`, `BuilderCodec`), `.ui` documents under `Common/UI/Custom/Pages/Sproutwatch/`.

**Spec:** `docs/superpowers/specs/2026-09-29-sproutwatch-settings-page-design.md`

---

## Commit policy for this plan

Mertie reviews and commits; the executor does NOT commit. Every task ends with a **Checkpoint** step: run the tests, confirm green, `git add -A` to stage. The repo currently has ZERO commits on branch `dev` and everything (v0.1.0 through the persist toggle) is staged. Before starting Task 1, ask Mertie once whether they want to commit the current staged tree first (so the settings page lands as its own commit); do not wait for the answer to begin, just do not commit either way. The final task asks Mertie for the go-ahead before any commit. Never push.

## Research findings (verified against Server-0.6.3 decompiled sources and Assets.zip on 2026-09-30)

Every engine call and every `.ui` identifier used below was read from `hytale-server-decompiled-0.0.1-sources.jar` (`~/Developer/hytale/mysterion-mazurka-8/build/generated-sources-jars/server/`) or from the release `Assets.zip`. Do not substitute other APIs. The one item that could not be verified is marked and the affected step is defensive.

| Item | Verified API |
|---|---|
| Page base class | `com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage<T>`; ctor `(PlayerRef playerRef, CustomPageLifetime lifetime, BuilderCodec<T> eventDataCodec)`; abstract `build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store)`; overridable `handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull T data)` and `onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store)`. Protected fields `playerRef`, `lifetime`. |
| Rebuild / update / close | `CustomUIPage.rebuild()` (protected; re-runs `build` and sends a `CustomPage` with `clear=true`, on the calling thread); `sendUpdate(UICommandBuilder)` (protected; `InteractiveCustomUIPage` overrides the 2-arg form so it marshals onto the player's world via `store.getExternalData().getWorld().execute(...)`, so it is safe to call from any thread); `close()`. |
| Event decoding | `InteractiveCustomUIPage.handleDataEvent(ref, store, String rawData)` decodes with `eventDataCodec.decodeJson(new RawJsonReader(rawData.toCharArray()), extraInfo)` then calls the typed overload. Vanilla event classes are mutable classes with public fields, not records (`TeleporterSettingsPage.PageEventData`, `PointInspectorPage.PageData`). |
| Event codec shape | `BuilderCodec.builder(SettingsEvent.class, SettingsEvent::new).append(new KeyedCodec<>("Action", Codec.STRING, false), (e, v) -> e.action = v, e -> e.action).add() ... .build()`; `append(KeyedCodec, BiConsumer setter, Function getter)` is a public overload (BuilderCodec lines 970-975); `KeyedCodec(String key, Codec codec, boolean required)` 3-arg ctor with `false` = optional (used by `TriggerVolumeInspectorPage.PageData`). Codecs used: `Codec.STRING`, `Codec.BOOLEAN` (`@ParamBool` from a CheckBox `.Value`, TriggerVolume inspector), `Codec.INTEGER` (`@MaxSize` from a NumberField `.Value` with `MaxDecimalPlaces: 0`, `ImageImportPage`). Absent keys leave the field untouched (each vanilla binding sends only a subset of keys), so boxed `Integer`/`Boolean` fields are used and null-checked. |
| Event key convention | `EventData.append(String key, String value)`; a key starting with `@` is read from the client (`"@Channel", "#ChannelField.Value"`); any other key is a literal sent back verbatim (`"Action", "saveChannel"`, `"Login", login`) exactly as `PointInspectorPage.bindStaticEvents` and its per-row `RemoveTag` binding do. |
| Event binding | `UIEventBuilder.addEventBinding(CustomUIEventBindingType type, String selector, EventData data)` (locks the interface until the server replies) and `addEventBinding(type, selector, data, boolean locksInterface)`. Vanilla uses `Activating` (default lock) for buttons and `ValueChanged` with `locksInterface=false` for checkboxes, dropdowns and text fields. |
| Command builder | `UICommandBuilder.append(String documentPath)`, `append(String selector, String documentPath)` (appends a document as a child of the selector; children are addressed `"#List[" + i + "] #Child"`), `clear(String selector)`, `set(String selector, String)`, `set(selector, boolean)`, `set(selector, int)`, `set(selector, double)`, `set(selector, List<T>)` where `T` must be in `CODEC_MAP` (`DropdownEntryInfo` is), `set(selector, Message)`. Selector syntax `"#Group #Child.Property"` (descendant) and `"#Id.Property"`. |
| Dropdown entries | `new DropdownEntryInfo(LocalizableString label, String value)` (2-arg ctor); `LocalizableString.fromString(String)`; set with `cmd.set("#PrefabDropdown.Entries", List<DropdownEntryInfo>)` and `cmd.set("#PrefabDropdown.Value", String)`; read back with `"@Prefab", "#PrefabDropdown.Value"` (same shape as `PointInspectorPage` `#WorldDropdown`). |
| Enums | `CustomPageLifetime.CanDismissOrCloseThroughInteraction` (also `CantClose`, `CanDismiss`); `CustomUIEventBindingType.Activating`, `.ValueChanged`. |
| Opening a page | On the player's world thread: `Ref<EntityStore> ref = playerRef.getReference(); Store<EntityStore> store = ref.getStore(); Player player = store.getComponent(ref, Player.getComponentType()); player.getPageManager().openCustomPage(ref, store, page)`. `PageManager.openCustomPage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull CustomUIPage page)` calls `onDismiss` on any previous page, then `page.build(...)`, then sends. `Player` is `com.hypixel.hytale.server.core.entity.entities.Player`; `Player.getComponentType()` and `getPageManager()` exist (Player.java lines 172, 456). Qrhyr opens its page this way. |
| PlayerRef | `com.hypixel.hytale.server.core.universe.PlayerRef`: `getReference()` (null when not in a world), `isValid()`, `getUuid()`, `getWorldUuid()`, `sendMessage(Message)`, `getPacketHandler()`. |
| World access | `store.getExternalData().getWorld()`; `Universe.get().getWorld(UUID)`; `world.getWorldConfig().getUuid()`; `world.getName()`; `world.execute(Runnable)` (already wrapped by `SproutwatchPlugin.runOnWorld`). |
| Disconnect | `PlayerDisconnectEvent.getPlayerRef().getUuid()` (already used by the plugin). |
| Document path | Bundled `.ui` files live under `src/main/resources/Common/UI/Custom/`; `cmd.append("Pages/Sproutwatch/SettingsPage.ui")` is relative to `Common/UI/Custom/` (Qrhyr: `Pages/QrPage.ui`; vanilla: `Pages/Point/PointInspectorPage.ui`). A document two folders deep imports `$C = "../../Common.ui";` (vanilla `Pages/Point/*.ui`, `Pages/Fields/*.ui`). |
| `.ui` templates (Common.ui) | `$C.@PageOverlay` (dim background), `$C.@DecoratedContainer` (slots `#Title` and `#Content`), `$C.@Title { @Text = "..."; }`, `$C.@Subtitle { @Text = "..."; }`, `$C.@TextField #Id { @Anchor = (...); PlaceholderText: "..."; }` (`.Value` string), `$C.@NumberField #Id { @Anchor = (...); Format: (MaxDecimalPlaces: 0, Step: 1); }` (`.Value` number), `$C.@CheckBox #Id { Anchor: (Width: 26, Height: 26); }` (`.Value` boolean), `$C.@DropdownBox #Id { @Anchor = (...); }` (`.Entries`, `.Value`), `$C.@TextButton #Id { @Text = "..."; @Anchor = (...); }`, `$C.@SecondaryTextButton`, `$C.@SmallSecondaryTextButton #Id { @Text = "..."; Anchor: (Width: 96, Height: 30, Right: 4); }` (the TriggerVolume tab button), `$C.@CancelTextButton`, `$C.@DefaultLabelStyle`, `$C.@DefaultScrollbarStyle`, `$C.@BackButton {}`. Every element accepts `Visible: true|false;` and a `.Visible` / `.Disabled` / `.Text` set from the server (`#FullSettings.Visible`, `#DeletePointButton.Disabled`, `#ErrorLabel.Text`). |
| Tabs | `TriggerVolumeInspectorPage.buildTabs`: one `@SmallSecondaryTextButton` per tab, `cmd.set(sel + ".Disabled", selectedTab == tab)` marks the active one, each bound with `Activating` and `EventData().append("Action", "ChangeTab").append("Tab", tab.name())`; content groups toggled with `cmd.set("#VolumeTab.Visible", ...)`. |
| List rows | `PointInspectorPage.buildContent`: `cmd.append("#TagsList", "Pages/Point/PointTagRow.ui")`, then `cmd.set("#TagsList[" + i + "] #TagLabel.Text", ...)` and `evt.addEventBinding(Activating, "#TagsList[" + i + "] #RemoveButton", new EventData().put("Action", "RemoveTag").put("RemoveKey", key))`. `PointTagRow.ui` = `Group { LayoutMode: Left; Label #TagLabel { FlexWeight: 1; } $C.@SecondaryTextButton #RemoveButton { @Text = "x"; Anchor: (Width: 24, Height: 24); } }`. A scrolling list container is `Group #X { LayoutMode: TopScrolling; ScrollbarStyle: $C.@DefaultScrollbarStyle; }`. |
| Message text | `Message.raw(String)` for chat replies (existing). Labels take plain strings via `set(selector, String)`. |
| NOT VERIFIED | Whether the client sends a NumberField's `.Value` as an integer or a double JSON number when `MaxDecimalPlaces: 0`. `ImageImportPage` decodes such a field with `Codec.INTEGER`, so this plan does too. Task 7 step 4 item 9 checks it; if saving the interval logs a codec error, change `@Interval` in `SettingsEvent` to `Codec.DOUBLE` with a `Double` field and round in the page (`(int) Math.round(e.interval)`). |

## Deviations from the spec (decided during research)

1. **Event data is a mutable class, not a record.** `BuilderCodec` fills an instance through setter lambdas after `SettingsEvent::new`; records cannot be built that way. `ui/SettingsEvent` copies `TeleporterSettingsPage.PageEventData` (public fields + static `CODEC`). `ui/StatusSnapshot` IS a record as specified.
2. **Narrow host interface.** `SproutwatchActions` depends on `ui/ActionsHost` (config, save, logger, roster, registry, roleSet, listener state/running/feed-acked, start/stop, ticker running/restart, pen world, `runOnPenWorld`, `runOnPlayerWorld`, `runOnWorld`, `statusChanged`) instead of `PenTicker`/`TwitchMembershipClient` objects, so the unit tests need no threads, sockets or engine objects. `SproutwatchPlugin implements ActionsHost`.
3. **Field rows are written inline** in `SettingsPage.ui` using the same shape as vanilla `Pages/Fields/{TextRow,IntRow,CheckboxRow,DropdownRow}.ui` (label + `$C.@TextField` / `@NumberField` / `@CheckBox` / `@DropdownBox`), each with its own `#Id`, instead of appending the vanilla row documents. Reason: fixed ids (`#ChannelField`) instead of index selectors (`#Row[0] #Input`) for every static field; the vanilla Common.ui templates are still what is used.
4. **Tab bar** = three `$C.@SmallSecondaryTextButton`s with the active one `Disabled` (the TriggerVolume inspector pattern), not a `TabNavigation` element (which needs icon textures).
5. **Listener start/stop and persist checkboxes** send `ValueChanged` with the new checkbox value; the page calls `startListener` when the value is true and `stopListener` when false, then rebuilds, so the checkbox always ends up showing the real state even when a start is refused.
6. **Status refresh** pushes the ten status labels plus the two derived captions (`#RunLabel`, `#PenInfoLabel`); never a field `.Value`.
7. `/sproutwatch status` text is now produced by `StatusSnapshot.report()`; the wording is asserted unchanged by a unit test. `camera`, `test` and `queue` subcommands are not routed through the actions service (out of the page's scope) and keep their code verbatim.

## File map

```
sproutwatch/
  src/main/resources/Sproutwatch_config.json                         + "PenPrefab": "default"  (27 keys)
  src/main/resources/Common/UI/Custom/Pages/Sproutwatch/SettingsPage.ui   the page: tab bar, three groups, status labels, fields, buttons, message line
  src/main/resources/Common/UI/Custom/Pages/Sproutwatch/ListEntryRow.ui   one allow/ignore row: Label #Login + $C.@SmallSecondaryTextButton #RemoveButton
  src/main/java/dev/hytalemodding/sproutwatch/
    SproutwatchPlugin.java            (modified) implements ActionsHost; owns SproutwatchActions + OpenPages; ticker.setAfterTick(openPages::refreshAll); disconnect -> openPages.forget(uuid)
    config/SproutwatchConfig.java     (modified) key PenPrefab: getPenPrefab() (trimmed lowercase, "default" when blank), setPenPrefab(String)
    prefab/PenPrefab.java             (modified) readBundledJson(String resource) overload
    prefab/PenPrefabCatalog.java      name -> bundled resource; names(), contains(name), resourceFor(name) (fallback default), normalize(raw)
    prefab/PenPlacer.java             (modified) reads the prefab via PenPrefabCatalog.resourceFor(cfg.getPenPrefab())
    pen/PenTicker.java                (modified) setAfterTick(Runnable): run on the world thread at the end of tickOnWorldThread
    commands/SproutwatchCommand.java  (modified) channel/start/stop/status/place/clear/interval/allow/ignore/persist delegate to SproutwatchActions; new `settings` opens the page
    ui/ActionsHost.java               interface the plugin implements (see signatures below)
    ui/StatusSnapshot.java            record of everything the page/status show + label wording + report()
    ui/SproutwatchActions.java        every mutation, each returning the reply string; snapshot(); statusReport()
    ui/OpenPages.java                 thread-safe UUID -> OpenPages.Page; register/forget/refreshAll
    ui/SettingsEvent.java             event data class + BuilderCodec (keys Action, Tab, Login, @Channel, @AllowInput, @IgnoreInput, @Interval, @Prefab, @Run, @Persist)
    ui/SproutwatchSettingsPage.java   InteractiveCustomUIPage<SettingsEvent> implements OpenPages.Page
  src/test/java/dev/hytalemodding/sproutwatch/
    prefab/PenPrefabCatalogTest.java  (new)
    config/SproutwatchConfigTest.java (modified: key count 27, PenPrefab assertions)
    ui/SproutwatchActionsTest.java    (new; FakeHost)
    ui/OpenPagesTest.java             (new; FakePage)
```

Exact signatures used verbatim by every task:

```java
// prefab/PenPrefabCatalog
public static final String DEFAULT = "default";
public static List<String> names();
public static boolean contains(String name);
public static String resourceFor(String name);
public static String normalize(String raw);

// prefab/PenPrefab
public static String readBundledJson() throws IOException;            // existing, delegates
public static String readBundledJson(String resource) throws IOException;

// config/SproutwatchConfig
public String getPenPrefab();
public void setPenPrefab(String v);

// pen/PenTicker
public void setAfterTick(Runnable hook);

// ui/ActionsHost
enum WorldQueue { QUEUED, NOT_LOADED, REJECTED }
SproutwatchConfig config();  void saveConfig();  Logger logger();
ChatRoster roster();  PenRegistry registry();  Set<String> roleSet();
String listenerState();  boolean listenerRunning();  boolean feedAcked();
String startListener();  boolean stopListener();
boolean tickerRunning();  void restartTicker();
World penWorld();
WorldQueue runOnPenWorld(Consumer<World> task);
WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task);
boolean runOnWorld(World world, Runnable task);
void statusChanged();   // place/clear finished on a world thread: plugin -> openPages.refreshAll()

// ui/SproutwatchActions
public SproutwatchActions(ActionsHost host);
public SproutwatchConfig config();
public String setChannel(String raw);
public String startListener();  public String stopListener();
public String place(PlayerRef sender);  public String clear();
public String setPersist(boolean on);  public String setTickSeconds(int seconds);
public String addAllow(String raw);  public String removeAllow(String raw);
public String addIgnore(String raw);  public String removeIgnore(String raw);
public String selectPrefab(String raw);
public StatusSnapshot snapshot();  public String statusReport();

// ui/OpenPages
public interface Page { boolean refreshStatus(); }
public OpenPages(Logger logger);
public void register(UUID playerUuid, Page page);
public void forget(UUID playerUuid);  public void forget(UUID playerUuid, Page page);
public boolean isOpen(UUID playerUuid);  public int size();  public void refreshAll();

// ui/SproutwatchSettingsPage
public SproutwatchSettingsPage(PlayerRef playerRef, SproutwatchActions actions, OpenPages openPages, Logger logger);

// SproutwatchPlugin additions
public SproutwatchActions getActions();  public OpenPages getOpenPages();
```

`.ui` element ids (SettingsPage.ui): tab buttons `#TabStatus #TabListener #TabPen`; groups `#StatusTab #ListenerTab #PenTab`; status labels `#ListenerLabel #ChannelLabel #FeedLabel #RosterLabel #QueueLabel #PenCountLabel #RetiredLabel #PersistLabel #PenPlacedLabel #ChairLabel`; channel `#ChannelField #SaveChannelButton`; allow `#AllowList #AllowEmptyLabel #AllowField #AddAllowButton`; ignore `#IgnoreList #IgnoreField #AddIgnoreButton`; listener `#RunCheck #RunLabel #PersistCheck #PersistCheckLabel #IntervalField #SaveIntervalButton`; pen `#PrefabDropdown #PlaceButton #ClearButton #PenInfoLabel`; `#MessageLabel`. ListEntryRow.ui: `#Login #RemoveButton`.

All test commands assume the project root `~/Developer/hytale/sproutwatch`. Run one class with `./gradlew test --tests 'dev.hytalemodding.sproutwatch.<pkg>.<Class>'`; Gradle prints `BUILD SUCCESSFUL` on pass and lists failing tests on failure. The suite currently has 94 tests.

---

### Task 1: PenPrefabCatalog, config key `PenPrefab`, PenPlacer reads through the catalog

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/prefab/PenPrefabCatalog.java`
- Create: `src/test/java/dev/hytalemodding/sproutwatch/prefab/PenPrefabCatalogTest.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/prefab/PenPrefab.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/config/SproutwatchConfig.java`
- Modify: `src/main/resources/Sproutwatch_config.json`
- Modify: `src/test/java/dev/hytalemodding/sproutwatch/config/SproutwatchConfigTest.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/prefab/PenPlacer.java`

- [ ] **Step 1: Write the failing catalog test**

```java
package dev.hytalemodding.sproutwatch.prefab;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PenPrefabCatalogTest {

    @Test void namesListsDefaultFirst() {
        List<String> names = PenPrefabCatalog.names();
        assertEquals("default", names.get(0));
        assertEquals(1, names.size(), "only the bundled pen exists today; extend RESOURCES when adding one");
        assertThrows(UnsupportedOperationException.class, () -> names.add("x"));
    }

    @Test void normalizeTrimsAndLowercases() {
        assertEquals("default", PenPrefabCatalog.normalize("  Default "));
        assertEquals("", PenPrefabCatalog.normalize(null));
        assertEquals("", PenPrefabCatalog.normalize("   "));
    }

    @Test void containsAndResourceForFallBackToDefault() {
        assertTrue(PenPrefabCatalog.contains("default"));
        assertFalse(PenPrefabCatalog.contains("castle"));
        assertFalse(PenPrefabCatalog.contains(null));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor("default"));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor("castle"));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor(null));
        assertEquals(PenPrefab.RESOURCE, PenPrefabCatalog.resourceFor(" DEFAULT "));
    }

    @Test void everyCatalogEntryIsBundledAndParses() throws Exception {
        for (String name : PenPrefabCatalog.names()) {
            String json = PenPrefab.readBundledJson(PenPrefabCatalog.resourceFor(name));
            assertTrue(PenPrefab.parseBlocks(json).size() > 1000, name + " should be a full pen");
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalogTest'`
Expected: compilation failure, `PenPrefabCatalog` not found and `readBundledJson(String)` not found.

- [ ] **Step 3: Add the resource overload to PenPrefab**

Replace the existing `readBundledJson()` method in `PenPrefab.java` with these two methods:

```java
    public static String readBundledJson() throws IOException {
        return readBundledJson(RESOURCE);
    }

    /** @param resource absolute classpath path of a bundled prefab JSON (see PenPrefabCatalog) */
    public static String readBundledJson(String resource) throws IOException {
        try (InputStream in = PenPrefab.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Bundled prefab missing from jar: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
```

- [ ] **Step 4: Write PenPrefabCatalog**

```java
package dev.hytalemodding.sproutwatch.prefab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names the bundled pen prefabs (config key PenPrefab, settings page dropdown). Only "default"
 * exists today; add a resource under Server/Prefabs/Sproutwatch/ and one RESOURCES entry to ship
 * another. Unknown names fall back to the default so a hand-edited config can never break placement.
 */
public final class PenPrefabCatalog {

    public static final String DEFAULT = "default";

    private static final Map<String, String> RESOURCES = new LinkedHashMap<>();
    static {
        RESOURCES.put(DEFAULT, PenPrefab.RESOURCE);
    }

    private PenPrefabCatalog() {}

    /** Immutable, default first, in declaration order. */
    public static List<String> names() {
        return List.copyOf(RESOURCES.keySet());
    }

    /** Trimmed, lowercase; "" for null or blank. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean contains(String name) {
        return RESOURCES.containsKey(normalize(name));
    }

    /** Classpath resource for the named prefab, or the default's when the name is unknown. */
    public static String resourceFor(String name) {
        String res = RESOURCES.get(normalize(name));
        return res != null ? res : RESOURCES.get(DEFAULT);
    }
}
```

- [ ] **Step 5: Run the catalog test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalogTest'`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 6: Extend the config test (fails first)**

In `SproutwatchConfigTest.defaultsMatchTheShippedJson` change the key-count line to:

```java
        assertEquals(27, json.size(), "JSON key count changed; update this test and the getter list together");
```

and add, directly after the `PersistSprouts` assertion:

```java
        assertEquals(json.getString("PenPrefab").getValue(), c.getPenPrefab());
```

Add this test method at the end of the class:

```java
    @Test void penPrefabDefaultsAndNormalises() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertEquals("default", c.getPenPrefab());
        c.setPenPrefab("  Castle ");
        assertEquals("castle", c.getPenPrefab(), "the config stores the name; the catalog decides whether it exists");
        c.setPenPrefab("   ");
        assertEquals("default", c.getPenPrefab());
        c.setPenPrefab(null);
        assertEquals("default", c.getPenPrefab());
    }
```

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.config.SproutwatchConfigTest'`
Expected: compilation failure (`getPenPrefab` not found).

- [ ] **Step 7: Add the key to the config and the JSON**

In `SproutwatchConfig.java` add the field after `persistSprouts`:

```java
    private volatile String penPrefab = "default";
```

Add the codec entry directly after the `PersistSprouts` `.add()` and before `.build()`:

```java
            .append(new KeyedCodec<>("PenPrefab", Codec.STRING),
                (c, v, x) -> c.penPrefab = v, (c, x) -> c.penPrefab).add()
```

Add the accessors at the end of the class (after `setPersistSprouts`):

```java
    /** Bundled pen prefab name (see PenPrefabCatalog): trimmed lowercase, "default" when blank. */
    public String getPenPrefab() {
        String p = penPrefab == null ? "" : penPrefab.trim().toLowerCase(Locale.ROOT);
        return p.isEmpty() ? "default" : p;
    }
    public void setPenPrefab(String v) { penPrefab = v; }
```

In `Sproutwatch_config.json` change the last line `"PersistSprouts": true` to:

```json
  "PersistSprouts": true,
  "PenPrefab": "default"
```

- [ ] **Step 8: Run the config test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.config.SproutwatchConfigTest'`
Expected: `BUILD SUCCESSFUL`, 14 tests pass.

- [ ] **Step 9: Make PenPlacer read through the catalog**

In `PenPlacer.place` replace the first line of the body

```java
        String json = PenPrefab.readBundledJson();
```

with

```java
        String json = PenPrefab.readBundledJson(PenPrefabCatalog.resourceFor(cfg.getPenPrefab()));
```

(`PenPrefabCatalog` is in the same package; no import needed.)

- [ ] **Step 10: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 99 tests.

---

### Task 2: StatusSnapshot, ActionsHost and SproutwatchActions (pure, TDD)

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/ui/StatusSnapshot.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/ui/ActionsHost.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/ui/SproutwatchActions.java`
- Create: `src/test/java/dev/hytalemodding/sproutwatch/ui/SproutwatchActionsTest.java`

Nothing in this task touches the plugin or the command; the fake host stands in for the plugin. Every reply string below is copied verbatim from the current `SproutwatchCommand` so Task 3 is behaviour-preserving.

- [ ] **Step 1: Write the failing actions test**

```java
package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;
import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import dev.hytalemodding.sproutwatch.twitch.SproutQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SproutwatchActionsTest {

    /** Records every side effect and never touches the engine: World is only a type here. */
    static final class FakeHost implements ActionsHost {
        final SproutwatchConfig cfg = new SproutwatchConfigAccess().fresh();
        final ChatRoster roster = new ChatRoster(cfg::ignoredLogins, cfg::getQueueCommand, new SproutQueue());
        final PenRegistry registry = new PenRegistry();
        final Logger logger = Logger.getLogger("SproutwatchActionsTest");
        boolean running;
        boolean acked;
        String startError;
        int startCalls, stopCalls, saveCalls, restartCalls;
        boolean tickerRunning;
        WorldQueue penQueue = WorldQueue.NOT_LOADED;
        WorldQueue playerQueue = WorldQueue.NOT_LOADED;
        final List<Consumer<World>> penTasks = new ArrayList<>();
        final List<Consumer<World>> playerTasks = new ArrayList<>();

        @Override public SproutwatchConfig config() { return cfg; }
        @Override public void saveConfig() { saveCalls++; }
        @Override public Logger logger() { return logger; }
        @Override public ChatRoster roster() { return roster; }
        @Override public PenRegistry registry() { return registry; }
        @Override public Set<String> roleSet() { return Set.of(cfg.getRoles()); }
        @Override public String listenerState() { return running ? "connected to #" + cfg.getTwitchChannel() : "stopped"; }
        @Override public boolean listenerRunning() { return running; }
        @Override public boolean feedAcked() { return acked; }
        @Override public String startListener() {
            startCalls++;
            if (startError != null) return startError;
            running = true;
            return null;
        }
        @Override public boolean stopListener() {
            stopCalls++;
            boolean was = running;
            running = false;
            return was;
        }
        @Override public boolean tickerRunning() { return tickerRunning; }
        @Override public void restartTicker() { restartCalls++; }
        @Override public World penWorld() { return null; }
        @Override public WorldQueue runOnPenWorld(Consumer<World> task) {
            if (penQueue == WorldQueue.QUEUED) penTasks.add(task);
            return penQueue;
        }
        @Override public WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task) {
            if (playerQueue == WorldQueue.QUEUED) playerTasks.add(task);
            return playerQueue;
        }
        @Override public boolean runOnWorld(World world, Runnable task) { return false; }
        int changedCalls;
        @Override public void statusChanged() { changedCalls++; }
    }

    @Test void setChannelWhenStoppedSavesAndTellsHowToStart() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Channel set to #streamer. Run /sproutwatch start to begin.", a.setChannel("#Streamer!"));
        assertEquals("streamer", h.cfg.getTwitchChannel());
        assertEquals(1, h.saveCalls);
        assertEquals(0, h.startCalls);
    }

    @Test void setChannelWhileRunningRestartsListener() {
        FakeHost h = new FakeHost();
        h.running = true;
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Channel set to #streamer; listener restarted.", a.setChannel("streamer"));
        assertEquals(1, h.startCalls);
        h.startError = "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        assertEquals(h.startError, a.setChannel("other"));
        assertEquals("other", h.cfg.getTwitchChannel(), "the channel is saved even when the restart is refused");
        assertEquals(2, h.saveCalls);
    }

    @Test void setChannelRejectsInvalidInput() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Invalid channel name.", a.setChannel("!!!"));
        assertEquals("Invalid channel name.", a.setChannel(null));
        assertEquals(0, h.saveCalls);
    }

    @Test void startListenerPassesRefusalThrough() {
        FakeHost h = new FakeHost();
        h.startError = "No Twitch channel set. Use /sproutwatch channel <name> first.";
        assertEquals(h.startError, new SproutwatchActions(h).startListener());
    }

    @Test void startAndStopWording() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Sproutwatch watching #streamer; one sprout every 60s.", a.startListener());
        assertTrue(h.running);
        assertEquals("Sproutwatch stopped.", a.stopListener());
        assertEquals("Sproutwatch was not running.", a.stopListener());
        assertEquals(2, h.stopCalls);
    }

    @Test void allowAddRemoveNormalisesAndSaves() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Added alice to the allow list.", a.addAllow("@Alice"));
        assertEquals("alice is already on the allow list.", a.addAllow("alice"));
        assertEquals(Set.of("alice"), h.cfg.allowedLogins());
        assertEquals("Removed alice from the allow list.", a.removeAllow("ALICE"));
        assertEquals("alice is not on the allow list.", a.removeAllow("alice"));
        assertEquals(2, h.saveCalls, "saved once per successful change");
    }

    @Test void ignoreAddPartsTheViewerAndRemoveRestores() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.roster.apply(new RosterEvent.Chat("spammer", "!sprout"), 1L);
        assertEquals(1, h.roster.size());
        assertEquals(1, h.roster.queue().size());
        assertEquals("Added spammer to the ignore list.", a.addIgnore("Spammer"));
        assertEquals(0, h.roster.size(), "an ignored viewer leaves the roster at once");
        assertEquals(0, h.roster.queue().size());
        assertTrue(h.cfg.ignoredLogins().contains("spammer"));
        assertEquals("spammer is already on the ignore list.", a.addIgnore("spammer"));
        assertEquals("Removed spammer from the ignore list.", a.removeIgnore("spammer"));
        assertEquals("spammer is not on the ignore list.", a.removeIgnore("spammer"));
        assertEquals(2, h.saveCalls);
    }

    @Test void invalidLoginsAreRejectedEverywhere() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        for (String bad : new String[]{null, "", "   ", "!!!"}) {
            assertEquals("Invalid login.", a.addAllow(bad));
            assertEquals("Invalid login.", a.removeAllow(bad));
            assertEquals("Invalid login.", a.addIgnore(bad));
            assertEquals("Invalid login.", a.removeIgnore(bad));
        }
        assertEquals(0, h.saveCalls);
    }

    @Test void persistSetSavesAndExplains() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Persist is now off: sprouts despawn 300s after their viewer leaves (any already past that window go on the next tick).", a.setPersist(false));
        assertFalse(h.cfg.isPersistSprouts());
        assertEquals("Persist is now on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced.", a.setPersist(true));
        assertTrue(h.cfg.isPersistSprouts());
        assertEquals(2, h.saveCalls);
    }

    @Test void tickSecondsClampsAndRestartsRunningTicker() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Tick interval is now 5s.", a.setTickSeconds(1));
        assertEquals(0, h.restartCalls, "a stopped ticker is not re-armed");
        h.tickerRunning = true;
        assertEquals("Tick interval is now 30s.", a.setTickSeconds(30));
        assertEquals(1, h.restartCalls);
        assertEquals(2, h.saveCalls);
    }

    @Test void selectPrefabRejectsUnknownAndSavesKnown() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals("Unknown pen prefab 'castle'. Available: default.", a.selectPrefab("castle"));
        assertEquals("Unknown pen prefab ''. Available: default.", a.selectPrefab(null));
        assertEquals(0, h.saveCalls);
        assertEquals("Pen prefab set to default. Place the pen to paste it.", a.selectPrefab(" Default "));
        assertEquals("default", h.cfg.getPenPrefab());
        assertEquals(1, h.saveCalls);
    }

    @Test void placeReportsQueueOutcome() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.playerQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Placing the pen...", a.place(null));
        assertEquals(1, h.playerTasks.size(), "the paste runs on the player's world thread");
        h.playerQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Your world is not loaded.", a.place(null));
        h.playerQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Your world is unloading; try again.", a.place(null));
        assertEquals(1, h.playerTasks.size());
    }

    @Test void clearReportsQueueOutcomeAndForgetsWhenUnloaded() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        h.registry.put(new PenRegistry.Entry("alice", null, -1, null, 0L, 0L));
        h.penQueue = ActionsHost.WorldQueue.NOT_LOADED;
        assertEquals("Pen world not loaded; forgot 1 tracked sprout(s).", a.clear());
        assertEquals(0, h.registry.size());
        h.penQueue = ActionsHost.WorldQueue.QUEUED;
        assertEquals("Clearing the pen...", a.clear());
        assertEquals(1, h.penTasks.size());
        h.penQueue = ActionsHost.WorldQueue.REJECTED;
        assertEquals("Pen world is unloading; try again.", a.clear());
        assertEquals(1, h.penTasks.size());
    }

    @Test void snapshotReflectsHostAndConfig() {
        FakeHost h = new FakeHost();
        h.cfg.setTwitchChannel("streamer");
        h.cfg.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        h.cfg.setChair(true, 17, 65, 13);
        h.cfg.setPenFacing("south");
        h.cfg.addAllow("alice");
        h.roster.apply(new RosterEvent.Chat("bob", "!sprout"), 1L);
        h.registry.put(new PenRegistry.Entry("bob", null, -1, null, 0L, 0L));
        h.registry.retire("carol");
        h.running = true;
        h.acked = true;
        h.tickerRunning = true;
        StatusSnapshot s = new SproutwatchActions(h).snapshot();
        assertEquals("connected to #streamer", s.listenerState());
        assertTrue(s.listenerRunning());
        assertTrue(s.feedAcked());
        assertEquals("streamer", s.channel());
        assertEquals(1, s.rosterSize());
        assertEquals(1, s.queueSize());
        assertEquals("!sprout", s.queueCommand());
        assertEquals(1, s.allowCount());
        assertEquals(1, s.penCount());
        assertEquals(30, s.cap());
        assertEquals(1, s.retiredCount());
        assertEquals(60, s.tickSeconds());
        assertEquals(300, s.graceSeconds());
        assertTrue(s.tickerRunning());
        assertTrue(s.persist());
        assertTrue(s.penSet());
        assertEquals(10, s.penX());
        assertEquals(64, s.penY());
        assertEquals(20, s.penZ());
        assertEquals(16, s.penSizeX());
        assertEquals(12, s.penSizeZ());
        assertEquals("south", s.penFacing());
        assertNull(s.penWorldName(), "the fake host never loads a world");
        assertEquals("11111111-2222-3333-4444-555555555555", s.penWorldId());
        assertTrue(s.chairSet());
        assertEquals(17, s.chairX());
        assertEquals(65, s.chairY());
        assertEquals(13, s.chairZ());
        assertEquals("default", s.prefabName());
    }

    @Test void statusReportMatchesCommandWording() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        assertEquals(String.join("\n",
            "Listener: stopped",
            "Channel: (unset)",
            "Roster: 0 in chat",
            "Queue: 0 waiting (type !sprout in chat)",
            "Allow list: everyone",
            "Pen: 0/30 sprouts, tick 60s, grace 300s, ticker stopped",
            "Persist: on (longest-gone replaced at the cap)",
            "Pen: not placed (run /sproutwatch place)"), a.statusReport());

        h.cfg.setTwitchChannel("streamer");
        h.cfg.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        h.running = true;
        h.registry.retire("carol");
        String running = a.statusReport();
        assertTrue(running.contains("Listener: connected to #streamer\n"), running);
        assertTrue(running.contains("Channel: #streamer\n"), running);
        assertTrue(running.contains("Twitch JOIN/PART feed: OFF (Twitch did not grant membership; only viewers who chat will appear)\n"), running);
        assertTrue(running.contains("Retired (killed by a player): 1\n"), running);
        assertTrue(running.contains("Pen placed: 16x12 at 10,64,20 in an unloaded world (11111111-2222-3333-4444-555555555555), camera faces north\n"), running);
        assertTrue(running.endsWith("Chair: (none; use /sproutwatch camera)"), running);
    }

    @Test void snapshotLabelsForThePage() {
        FakeHost h = new FakeHost();
        SproutwatchActions a = new SproutwatchActions(h);
        StatusSnapshot stopped = a.snapshot();
        assertEquals("Twitch JOIN/PART feed: n/a (listener stopped)", stopped.feedLine());
        assertEquals("Listener stopped; tick to start", stopped.runLabel());
        assertEquals("Persist on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced", stopped.persistLabel());
        assertEquals("Chair: (no pen)", stopped.chairLine());
        assertEquals("No pen placed yet. Stand where you want it and press Place.", stopped.penInfoLine());
        assertEquals("Retired (killed by a player): 0", stopped.retiredLine());

        h.cfg.setTwitchChannel("streamer");
        h.cfg.setPersistSprouts(false);
        h.cfg.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        h.cfg.setChair(true, 17, 65, 13);
        h.running = true;
        h.acked = true;
        StatusSnapshot running = a.snapshot();
        assertEquals("Twitch JOIN/PART feed: on", running.feedLine());
        assertEquals("Listener running (connected to #streamer); untick to stop", running.runLabel());
        assertEquals("Persist off: sprouts despawn 300s after their viewer leaves", running.persistLabel());
        assertEquals("Interior min 10,64,20, size 16x12, facing north, chair 17,65,13, prefab default", running.penInfoLine());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.ui.SproutwatchActionsTest'`
Expected: compilation failure, `ActionsHost`, `SproutwatchActions`, `StatusSnapshot` not found.

- [ ] **Step 3: Write StatusSnapshot**

```java
package dev.hytalemodding.sproutwatch.ui;

/**
 * Point-in-time view of everything the settings page and /sproutwatch status show, plus the
 * wording of every line, so the text is unit-tested once and shared by page and command.
 * penWorldName is null when the pen world is not loaded; penWorldId is the configured UUID string.
 */
public record StatusSnapshot(
    String listenerState, boolean listenerRunning, boolean feedAcked,
    String channel, int rosterSize, int queueSize, String queueCommand, int allowCount,
    int penCount, int cap, int retiredCount, int tickSeconds, int graceSeconds, boolean tickerRunning,
    boolean persist,
    boolean penSet, int penX, int penY, int penZ, int penSizeX, int penSizeZ, String penFacing,
    String penWorldName, String penWorldId,
    boolean chairSet, int chairX, int chairY, int chairZ,
    String prefabName) {

    public String listenerLine() {
        return "Listener: " + listenerState;
    }

    public String channelLine() {
        return "Channel: " + (channel.isEmpty() ? "(unset)" : "#" + channel);
    }

    /** The status command prints this only while the listener runs; the page always shows it. */
    public String feedLine() {
        if (!listenerRunning) return "Twitch JOIN/PART feed: n/a (listener stopped)";
        return "Twitch JOIN/PART feed: " + (feedAcked
            ? "on"
            : "OFF (Twitch did not grant membership; only viewers who chat will appear)");
    }

    public String rosterLine() {
        return "Roster: " + rosterSize + " in chat";
    }

    public String queueLine() {
        return "Queue: " + queueSize + " waiting (type " + queueCommand + " in chat)";
    }

    public String allowLine() {
        return "Allow list: " + (allowCount == 0 ? "everyone" : allowCount + " logins");
    }

    public String penCountLine() {
        return "Pen: " + penCount + "/" + cap + " sprouts, tick " + tickSeconds + "s, grace " + graceSeconds
            + "s, ticker " + (tickerRunning ? "running" : "stopped");
    }

    public String persistLine() {
        return "Persist: " + (persist ? "on (longest-gone replaced at the cap)" : "off (grace " + graceSeconds + "s)");
    }

    public String retiredLine() {
        return "Retired (killed by a player): " + retiredCount;
    }

    public String penPlacedLine() {
        if (!penSet) return "Pen: not placed (run /sproutwatch place)";
        return "Pen placed: " + penSizeX + "x" + penSizeZ + " at " + penX + "," + penY + "," + penZ
            + " in " + (penWorldName == null ? "an unloaded world (" + penWorldId + ")" : penWorldName)
            + ", camera faces " + penFacing;
    }

    public String chairLine() {
        if (!penSet) return "Chair: (no pen)";
        return "Chair: " + (chairSet ? chairX + "," + chairY + "," + chairZ : "(none; use /sproutwatch camera)");
    }

    /** Caption of the Listener tab start/stop checkbox. */
    public String runLabel() {
        return listenerRunning
            ? "Listener running (" + listenerState + "); untick to stop"
            : "Listener stopped; tick to start";
    }

    /** Caption of the Listener tab persist checkbox. */
    public String persistLabel() {
        return persist
            ? "Persist on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced"
            : "Persist off: sprouts despawn " + graceSeconds + "s after their viewer leaves";
    }

    /** Read-only line on the Pen tab. */
    public String penInfoLine() {
        if (!penSet) return "No pen placed yet. Stand where you want it and press Place.";
        return "Interior min " + penX + "," + penY + "," + penZ + ", size " + penSizeX + "x" + penSizeZ
            + ", facing " + penFacing + ", chair " + (chairSet ? chairX + "," + chairY + "," + chairZ : "none")
            + ", prefab " + prefabName;
    }

    /** Exactly the text /sproutwatch status printed before the page existed. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(listenerLine()).append('\n');
        sb.append(channelLine()).append('\n');
        if (listenerRunning) sb.append(feedLine()).append('\n');
        sb.append(rosterLine()).append('\n');
        sb.append(queueLine()).append('\n');
        sb.append(allowLine()).append('\n');
        sb.append(penCountLine()).append('\n');
        sb.append(persistLine()).append('\n');
        if (retiredCount > 0) sb.append(retiredLine()).append('\n');
        sb.append(penPlacedLine());
        if (penSet) sb.append('\n').append(chairLine());
        return sb.toString();
    }
}
```

- [ ] **Step 4: Write ActionsHost**

```java
package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;

import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Everything SproutwatchActions needs from the plugin, narrowed so a unit test can fake it
 * without threads, sockets or engine objects. SproutwatchPlugin implements it.
 */
public interface ActionsHost {

    /** Outcome of asking for work on a world thread. */
    enum WorldQueue { QUEUED, NOT_LOADED, REJECTED }

    SproutwatchConfig config();

    /** Persists the config asynchronously; failures are logged by the plugin. */
    void saveConfig();

    Logger logger();

    ChatRoster roster();

    PenRegistry registry();

    /** Configured NPC roles, for the pen sweep. */
    Set<String> roleSet();

    /** "stopped" when no listener exists, else the client's state text. */
    String listenerState();

    boolean listenerRunning();

    /** True when Twitch acknowledged the membership capability on the current connection. */
    boolean feedAcked();

    /** @return error text, or null on success (starts the client and the ticker). */
    String startListener();

    /** @return true if a listener was running and has been stopped. */
    boolean stopListener();

    boolean tickerRunning();

    /** Re-arms the ticker with the current TickSeconds. */
    void restartTicker();

    /** The configured pen world, or null when unset or not loaded. */
    World penWorld();

    /** Queues task on the pen world's thread; NOT_LOADED when there is no loaded pen world. */
    WorldQueue runOnPenWorld(Consumer<World> task);

    /** Queues task on the sender's world thread; NOT_LOADED when that world is not loaded. */
    WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task);

    /** world.execute that cannot escape. @return false if the world rejected the task. */
    boolean runOnWorld(World world, Runnable task);

    /** Something the page shows changed outside a tick (place/clear finished); the plugin refreshes open settings pages. */
    void statusChanged();
}
```

- [ ] **Step 5: Write SproutwatchActions**

```java
package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenClearer;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.prefab.PenPlacer;
import dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalog;
import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every mutation the settings page or a chat command can perform, each returning the reply text.
 * Shared by SproutwatchCommand and SproutwatchSettingsPage so both behave identically. Work that
 * must run on a world thread (place, clear) is queued through the host and answered with an
 * interim message; the final result reaches the player as a chat message from the world thread.
 */
public final class SproutwatchActions {

    private final ActionsHost host;

    public SproutwatchActions(ActionsHost host) {
        this.host = host;
    }

    public SproutwatchConfig config() {
        return host.config();
    }

    public String setChannel(String raw) {
        String channel = SproutwatchConfig.normalizeChannel(raw);
        if (channel.isEmpty()) return "Invalid channel name.";
        host.config().setTwitchChannel(channel);
        host.saveConfig();
        if (host.listenerRunning()) {
            String err = host.startListener();
            return err != null ? err : "Channel set to #" + channel + "; listener restarted.";
        }
        return "Channel set to #" + channel + ". Run /sproutwatch start to begin.";
    }

    public String startListener() {
        String err = host.startListener();
        if (err != null) return err;
        SproutwatchConfig c = host.config();
        return "Sproutwatch watching #" + c.getTwitchChannel() + "; one sprout every " + c.getTickSeconds() + "s.";
    }

    public String stopListener() {
        return host.stopListener() ? "Sproutwatch stopped." : "Sproutwatch was not running.";
    }

    /** Sweeps the old pen, pastes the prefab centred on the sender and saves the config (world thread). */
    public String place(PlayerRef sender) {
        ActionsHost.WorldQueue q = host.runOnPlayerWorld(sender, world -> placeOnWorldThread(sender, world));
        return switch (q) {
            case QUEUED -> "Placing the pen...";
            case NOT_LOADED -> "Your world is not loaded.";
            case REJECTED -> "Your world is unloading; try again.";
        };
    }

    private void placeOnWorldThread(PlayerRef sender, World world) {
        try {
            Ref<EntityStore> ref = sender.getReference();
            if (ref == null || !ref.isValid()) return;
            Store<EntityStore> store = ref.getStore();
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t == null) return;
            Vector3d p = t.getPosition();
            Vector3i feet = new Vector3i((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            String swept = sweepOldPen(world);
            String msg = PenPlacer.place(world, store, feet, world.getWorldConfig().getUuid(), host.config(), host.logger());
            host.saveConfig();
            sender.sendMessage(Message.raw(msg + swept));
            host.statusChanged(); // no tick may be running; push the new pen to open settings pages now
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Sproutwatch place failed", e);
            sender.sendMessage(Message.raw("Pen placement failed: " + e.getMessage() + " (see server log)"));
        }
    }

    /**
     * Re-placing the pen: remove the previous pen's sprouts first (registry + any youngling of a
     * configured role inside the old bounds) so they do not linger untracked. Runs on the new
     * pen's world thread; an old pen in another loaded world is swept on that world's thread.
     * @return a suffix for the placement reply
     */
    private String sweepOldPen(World newWorld) {
        SproutwatchConfig c = host.config();
        if (!c.isPenSet()) return "";
        PenBounds old = PenBounds.fromConfig(c);
        World oldWorld = host.penWorld();
        if (oldWorld == newWorld) {
            int n = PenClearer.clear(newWorld, host.registry(), host.roleSet(), old, host.logger());
            return " Cleared " + n + " sprout(s) from the old pen.";
        }
        if (oldWorld != null) {
            PenRegistry registry = host.registry();
            Set<String> roles = host.roleSet();
            Logger log = host.logger();
            host.runOnWorld(oldWorld, () -> PenClearer.clear(oldWorld, registry, roles, old, log));
            return " Clearing the old pen in its own world.";
        }
        int n = host.registry().clear().size();
        return " Old pen world not loaded; forgot " + n + " tracked sprout(s).";
    }

    public String clear() {
        ActionsHost.WorldQueue q = host.runOnPenWorld(w -> {
            PenClearer.clear(w, host.registry(), host.roleSet(), PenBounds.fromConfig(host.config()), host.logger());
            host.statusChanged(); // no tick may be running; push the emptied pen to open settings pages now
        });
        return switch (q) {
            case QUEUED -> "Clearing the pen...";
            case NOT_LOADED -> "Pen world not loaded; forgot " + host.registry().clear().size() + " tracked sprout(s).";
            case REJECTED -> "Pen world is unloading; try again.";
        };
    }

    public String setPersist(boolean on) {
        host.config().setPersistSprouts(on);
        host.saveConfig();
        return on
            ? "Persist is now on: sprouts stay after their viewer leaves; at the cap the longest-gone is replaced."
            : "Persist is now off: sprouts despawn " + host.config().getGraceSeconds()
                + "s after their viewer leaves (any already past that window go on the next tick).";
    }

    public String setTickSeconds(int seconds) {
        host.config().setTickSeconds(seconds);
        host.saveConfig();
        if (host.tickerRunning()) host.restartTicker();
        return "Tick interval is now " + host.config().getTickSeconds() + "s.";
    }

    public String addAllow(String raw) {
        String login = SproutwatchConfig.normalizeChannel(raw);
        if (login.isEmpty()) return "Invalid login.";
        boolean added = host.config().addAllow(login);
        if (added) host.saveConfig();
        return added ? "Added " + login + " to the allow list." : login + " is already on the allow list.";
    }

    public String removeAllow(String raw) {
        String login = SproutwatchConfig.normalizeChannel(raw);
        if (login.isEmpty()) return "Invalid login.";
        boolean removed = host.config().removeAllow(login);
        if (removed) host.saveConfig();
        return removed ? "Removed " + login + " from the allow list." : login + " is not on the allow list.";
    }

    public String addIgnore(String raw) {
        String login = SproutwatchConfig.normalizeChannel(raw);
        if (login.isEmpty()) return "Invalid login.";
        boolean added = host.config().addIgnore(login);
        if (added) {
            host.saveConfig();
            // Drop them from the roster (and queue) now; the roster only filters on entry, and
            // add() refuses them from here on. Their sprout ages out after GraceSeconds.
            host.roster().apply(new RosterEvent.Part(login), System.currentTimeMillis());
        }
        return added ? "Added " + login + " to the ignore list." : login + " is already on the ignore list.";
    }

    public String removeIgnore(String raw) {
        String login = SproutwatchConfig.normalizeChannel(raw);
        if (login.isEmpty()) return "Invalid login.";
        boolean removed = host.config().removeIgnore(login);
        if (removed) host.saveConfig();
        return removed ? "Removed " + login + " from the ignore list." : login + " is not on the ignore list.";
    }

    public String selectPrefab(String raw) {
        String name = PenPrefabCatalog.normalize(raw);
        if (!PenPrefabCatalog.contains(name)) {
            return "Unknown pen prefab '" + (raw == null ? "" : raw) + "'. Available: "
                + String.join(", ", PenPrefabCatalog.names()) + ".";
        }
        host.config().setPenPrefab(name);
        host.saveConfig();
        return "Pen prefab set to " + name + ". Place the pen to paste it.";
    }

    public StatusSnapshot snapshot() {
        SproutwatchConfig c = host.config();
        World w = c.isPenSet() ? host.penWorld() : null;
        return new StatusSnapshot(
            host.listenerState(), host.listenerRunning(), host.feedAcked(),
            c.getTwitchChannel(), host.roster().size(), host.roster().queue().size(), c.getQueueCommand(), c.allowedLogins().size(),
            host.registry().size(), c.getMaxSprouts(), host.registry().retiredLogins().size(),
            c.getTickSeconds(), c.getGraceSeconds(), host.tickerRunning(),
            c.isPersistSprouts(),
            c.isPenSet(), c.getPenX(), c.getPenY(), c.getPenZ(), c.getPenSizeX(), c.getPenSizeZ(), c.getPenFacing(),
            w == null ? null : w.getName(), c.getPenWorld(),
            c.isChairSet(), c.getChairX(), c.getChairY(), c.getChairZ(),
            c.getPenPrefab());
    }

    /** The /sproutwatch status text. */
    public String statusReport() {
        return snapshot().report();
    }
}
```

- [ ] **Step 6: Run the actions test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.ui.SproutwatchActionsTest'`
Expected: `BUILD SUCCESSFUL`, 16 tests pass.

- [ ] **Step 7: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 115 tests.

---

### Task 3: Plugin implements ActionsHost; SproutwatchCommand delegates to the actions

**Files:**
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/SproutwatchPlugin.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/commands/SproutwatchCommand.java`

Behaviour-preserving: every reply string stays identical (Task 2 tests pin them). One negligible difference: `/sproutwatch allow xyz !!!` (bad action AND bad login) now replies the usage line instead of "Invalid login.".

- [ ] **Step 1: Make the plugin implement ActionsHost**

Replace the whole of `SproutwatchPlugin.java` with:

```java
package dev.hytalemodding.sproutwatch;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.Config;
import dev.hytalemodding.sproutwatch.camera.ChairCameraService;
import dev.hytalemodding.sproutwatch.commands.SproutwatchCommand;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenClearer;
import dev.hytalemodding.sproutwatch.pen.PenDespawnSystem;
import dev.hytalemodding.sproutwatch.pen.PenRegistry;
import dev.hytalemodding.sproutwatch.pen.PenTicker;
import dev.hytalemodding.sproutwatch.pen.SproutDeathSystem;
import dev.hytalemodding.sproutwatch.pen.SproutSpawner;
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;
import dev.hytalemodding.sproutwatch.twitch.SproutQueue;
import dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClient;
import dev.hytalemodding.sproutwatch.ui.ActionsHost;
import dev.hytalemodding.sproutwatch.ui.SproutwatchActions;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class SproutwatchPlugin extends JavaPlugin implements ActionsHost {

    private final Config<SproutwatchConfig> config;
    private final Logger bridgeLogger;
    private java.util.logging.Handler bridgeHandler;
    private final ChatRoster roster;
    private final PenRegistry registry;
    private final SproutSpawner spawner;
    private final PenTicker ticker;
    private final ChairCameraService cameraService;
    private final SproutwatchActions actions;
    private volatile TwitchMembershipClient client;
    private final AtomicBoolean bootSweepDone = new AtomicBoolean();

    public SproutwatchPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.config = withConfig("Sproutwatch_config", SproutwatchConfig.CODEC);
        this.bridgeLogger = createBridgeLogger();
        // Ignore list and queue command are read live from the config on every roster event, so
        // /sproutwatch ignore add|remove and a QueueCommand edit take effect without a restart.
        this.roster = new ChatRoster(() -> config.get().ignoredLogins(), () -> config.get().getQueueCommand(), new SproutQueue());
        this.registry = new PenRegistry();
        this.spawner = new SproutSpawner(config::get, registry, bridgeLogger);
        this.ticker = new PenTicker(config::get, roster, registry, spawner, bridgeLogger);
        ticker.setOnWorldReady(this::bootSweep);
        this.cameraService = new ChairCameraService(config::get, bridgeLogger);
        this.actions = new SproutwatchActions(this);
    }

    @Override
    protected void setup() {
        config.save().whenComplete((v, t) -> {
            if (t != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", t);
        });
        getCommandRegistry().registerCommand(new SproutwatchCommand(this));
        // Disconnect never fires the mount-removed callback; drop the player's camera state here.
        try {
            getEventRegistry().register(PlayerDisconnectEvent.class, e -> cameraService.forget(e.getPlayerRef().getUuid()));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch disconnect listener failed to register");
        }
        // ECS systems via getEntityStoreRegistry(), the route Subinator proved on 0.6.3. Each
        // registration is guarded on its own so one failure never drops the other.
        try {
            getEntityStoreRegistry().registerSystem(new PenDespawnSystem(registry, bridgeLogger));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch despawn system failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(new SproutDeathSystem(registry, bridgeLogger));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch death system failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(cameraService);
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch chair camera failed to register");
        }
        // Restart safety: sweep leftover younglings out of the pen if its world is already loaded.
        World w = PenTicker.resolveWorld(config.get());
        if (w != null) runOnWorld(w, () -> bootSweep(w));
        if (config.get().isAutoStartOnBoot()) {
            String err = startListener();
            if (err != null) getLogger().atWarning().log("%s", err);
        }
    }

    @Override
    protected void shutdown() {
        stopListener();
        ticker.shutdown();
        if (bridgeHandler != null) bridgeLogger.removeHandler(bridgeHandler);
    }

    public Config<SproutwatchConfig> getConfigHolder() { return config; }
    public Logger getBridgeLogger() { return bridgeLogger; }
    public ChatRoster getRoster() { return roster; }
    /** The "!sprout" priority queue the roster feeds (FIFO of logins who asked for a sprout). */
    public SproutQueue getQueue() { return roster.queue(); }
    public PenRegistry getRegistry() { return registry; }
    public PenTicker getTicker() { return ticker; }
    public ChairCameraService getCameraService() { return cameraService; }
    public TwitchMembershipClient getClient() { return client; }
    /** Shared mutation service used by the commands and the settings page. */
    public SproutwatchActions getActions() { return actions; }

    // ---- ActionsHost -------------------------------------------------------------------------

    @Override public SproutwatchConfig config() { return config.get(); }
    @Override public Logger logger() { return bridgeLogger; }
    @Override public ChatRoster roster() { return roster; }
    @Override public PenRegistry registry() { return registry; }

    @Override
    public Set<String> roleSet() {
        return Set.of(config.get().getRoles());
    }

    @Override
    public void saveConfig() {
        config.save().whenComplete((v, t) -> {
            if (t != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", t);
        });
    }

    @Override
    public String listenerState() {
        TwitchMembershipClient c = client;
        return c == null ? "stopped" : c.getState();
    }

    @Override
    public boolean listenerRunning() {
        TwitchMembershipClient c = client;
        return c != null && c.isRunning();
    }

    @Override
    public boolean feedAcked() {
        TwitchMembershipClient c = client;
        return c != null && c.isMembershipAcked();
    }

    /** @return error text, or null on success. Synchronized: commands may race. */
    @Override
    public synchronized String startListener() {
        SproutwatchConfig cfg = config.get();
        if (cfg.getTwitchChannel().isEmpty()) {
            return "No Twitch channel set. Use /sproutwatch channel <name> first.";
        }
        if (!cfg.isPenSet()) {
            return "No pen placed yet. Stand where you want it and run /sproutwatch place.";
        }
        stopListener();
        client = new TwitchMembershipClient(cfg.getTwitchChannel(), roster, bridgeLogger); // one-shot per start
        client.start();
        World w = PenTicker.resolveWorld(cfg);
        if (w != null) runOnWorld(w, () -> bootSweep(w));
        ticker.start();
        return null;
    }

    /** @return true if a listener was running and has been stopped. Sprouts stay until /sproutwatch clear. */
    @Override
    public synchronized boolean stopListener() {
        TwitchMembershipClient c = client;
        client = null;
        if (c != null) c.stop();   // stop the producer before clearing what it produces
        ticker.stop();
        roster.clear();
        return c != null;
    }

    @Override public boolean tickerRunning() { return ticker.isRunning(); }
    @Override public void restartTicker() { ticker.start(); }
    @Override public void statusChanged() { } // filled in Task 4 once OpenPages exists

    @Override
    public World penWorld() {
        return PenTicker.resolveWorld(config.get());
    }

    @Override
    public WorldQueue runOnPenWorld(Consumer<World> task) {
        World w = PenTicker.resolveWorld(config.get());
        if (w == null) return WorldQueue.NOT_LOADED;
        return runOnWorld(w, () -> task.accept(w)) ? WorldQueue.QUEUED : WorldQueue.REJECTED;
    }

    @Override
    public WorldQueue runOnPlayerWorld(PlayerRef sender, Consumer<World> task) {
        Universe u = Universe.get();
        World w = u == null ? null : u.getWorld(sender.getWorldUuid());
        if (w == null) return WorldQueue.NOT_LOADED;
        return runOnWorld(w, () -> task.accept(w)) ? WorldQueue.QUEUED : WorldQueue.REJECTED;
    }

    /** world.execute that cannot escape: a world mid-unload rejects tasks. @return false if rejected. */
    @Override
    public boolean runOnWorld(World w, Runnable task) {
        try { w.execute(task); return true; }
        catch (RuntimeException e) { bridgeLogger.log(Level.WARNING, "Sproutwatch: world rejected task (unloading?)", e); return false; }
    }

    // ---- internals ---------------------------------------------------------------------------

    /** Restart safety: once per boot, sweep leftover younglings out of the pen. World thread. */
    private void bootSweep(World w) {
        if (!bootSweepDone.compareAndSet(false, true)) return;
        PenClearer.clear(w, registry, roleSet(), PenBounds.fromConfig(config.get()), bridgeLogger);
    }

    /**
     * Bridges java.util.logging (used by every component) onto this plugin's HytaleLogger, a
     * Flogger with only the fluent at(Level) API. Copied from Subinator (verified on 0.6.3).
     */
    private Logger createBridgeLogger() {
        Logger jul = Logger.getLogger("Sproutwatch");
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.ALL);
        // On 0.6.3 the server's HytaleLogManager returns a HytaleJdkLogger that forwards records straight
        // to the engine backend (prefix [Sproutwatch]); this handler only matters on a plain JVM (unit tests).
        if (jul.getHandlers().length == 0) {
            SimpleFormatter formatter = new SimpleFormatter();
            bridgeHandler = new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord r) {
                    String msg = formatter.formatMessage(r);
                    var api = getLogger().at(r.getLevel());
                    if (r.getThrown() != null) api = api.withCause(r.getThrown());
                    api.log("%s", msg);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            jul.addHandler(bridgeHandler);
        }
        return jul;
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL` (the command still compiles against the unchanged plugin getters).

- [ ] **Step 3: Rewrite SproutwatchCommand to delegate**

Replace the whole of `SproutwatchCommand.java` with (camera, test and queue classes are verbatim copies of the current file; `sweepOldPen` and `applyPersist` are gone):

```java
package dev.hytalemodding.sproutwatch.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.hytalemodding.sproutwatch.SproutwatchPlugin;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenTicker;
import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import dev.hytalemodding.sproutwatch.ui.SproutwatchActions;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class SproutwatchCommand extends AbstractCommandCollection {

    public SproutwatchCommand(SproutwatchPlugin plugin) {
        super("sproutwatch", "Twitch viewers as baby Kweebecs in a pen (allow/ignore lists, !sprout queue)");
        String permission = plugin.getBasePermission() + ".admin";
        requirePermission(permission);
        addSubCommand(new ChannelCommand(plugin, permission));
        addSubCommand(new StartCommand(plugin, permission));
        addSubCommand(new StopCommand(plugin, permission));
        addSubCommand(new StatusCommand(plugin, permission));
        addSubCommand(new PlaceCommand(plugin, permission));
        addSubCommand(new ClearCommand(plugin, permission));
        addSubCommand(new CameraCommand(plugin, permission));
        addSubCommand(new IntervalCommand(plugin, permission));
        addSubCommand(new TestCommand(plugin, permission));
        addSubCommand(new AllowCommand(plugin, permission));
        addSubCommand(new IgnoreCommand(plugin, permission));
        addSubCommand(new QueueCommand(plugin, permission));
        addSubCommand(new PersistCommand(plugin, permission));
    }

    /** AbstractCommand.execute returns CompletableFuture<Void>; funnel subclasses through run(). */
    abstract static class Sub extends AbstractCommand {
        final SproutwatchPlugin plugin;

        Sub(SproutwatchPlugin plugin, String name, String desc, String permission) {
            super(name, desc);
            this.plugin = plugin;
            requirePermission(permission);
        }

        /** Usage variant (no name): dispatched when the positional token count matches its required args. */
        Sub(SproutwatchPlugin plugin, String desc, String permission) {
            super(desc);
            this.plugin = plugin;
            requirePermission(permission);
        }

        void reply(CommandContext ctx, String text) {
            ctx.sendMessage(Message.raw(text));
        }

        SproutwatchConfig cfg() {
            return plugin.getConfigHolder().get();
        }

        SproutwatchActions actions() {
            return plugin.getActions();
        }

        /** The sending player, or null (with a reply) when run from console. */
        PlayerRef player(CommandContext ctx) {
            if (!ctx.isPlayer()) {
                reply(ctx, "Run this in game as a player.");
                return null;
            }
            PlayerRef p = ctx.senderAs(PlayerRef.class);
            if (p == null) reply(ctx, "Could not resolve the sending player.");
            return p;
        }

        abstract void run(CommandContext ctx);

        @Override
        protected final CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            run(ctx);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ChannelCommand extends Sub {
        private final RequiredArg<String> nameArg;

        ChannelCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "channel", "Set the Twitch channel to watch", permission);
            nameArg = withRequiredArg("name", "Twitch channel name", ArgTypes.STRING);
        }

        @Override void run(CommandContext ctx) {
            reply(ctx, actions().setChannel(ctx.get(nameArg)));
        }
    }

    private static final class StartCommand extends Sub {
        StartCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "start", "Start watching chat and filling the pen", permission);
        }

        @Override void run(CommandContext ctx) {
            reply(ctx, actions().startListener());
        }
    }

    private static final class StopCommand extends Sub {
        StopCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "stop", "Stop watching chat (sprouts stay until clear)", permission);
        }

        @Override void run(CommandContext ctx) {
            reply(ctx, actions().stopListener());
        }
    }

    private static final class StatusCommand extends Sub {
        StatusCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "status", "Show listener, roster and pen status", permission);
        }

        @Override void run(CommandContext ctx) {
            reply(ctx, actions().statusReport());
        }
    }

    private static final class PlaceCommand extends Sub {
        PlaceCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "place", "Paste the pen prefab centred on you and save it", permission);
        }

        @Override void run(CommandContext ctx) {
            PlayerRef sender = player(ctx);
            if (sender == null) return;
            reply(ctx, actions().place(sender));
        }
    }

    private static final class ClearCommand extends Sub {
        ClearCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "clear", "Remove every sprout in the pen", permission);
        }

        @Override void run(CommandContext ctx) {
            reply(ctx, actions().clear());
        }
    }

    private static final class CameraCommand extends Sub {
        CameraCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "camera", "Toggle the pen camera for you; /sproutwatch camera <height> <back> <fov> to tune", permission);
            addUsageVariant(new CameraTuneVariant(plugin, permission));
        }

        @Override void run(CommandContext ctx) {
            PlayerRef sender = player(ctx);
            if (sender == null) return;
            if (!cfg().isPenSet()) {
                reply(ctx, "No pen placed yet. Run /sproutwatch place first.");
                return;
            }
            boolean on = plugin.getCameraService().toggleManual(sender);
            reply(ctx, on ? "Pen camera on. Run /sproutwatch camera again to reset." : "Pen camera off.");
        }
    }

    /** {@code /sproutwatch camera <height> <back> <fov>}: console may tune, so no player requirement. */
    private static final class CameraTuneVariant extends Sub {
        private final RequiredArg<Double> heightArg;
        private final RequiredArg<Double> backArg;
        private final RequiredArg<Double> fovArg;

        CameraTuneVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Set camera height, back and fov (blocks, blocks, degrees) and re-send it to anyone using it", permission);
            heightArg = withRequiredArg("height", "Blocks above the floor", ArgTypes.DOUBLE);
            backArg = withRequiredArg("back", "Blocks behind the short side", ArgTypes.DOUBLE);
            fovArg = withRequiredArg("fov", "Field of view in degrees", ArgTypes.DOUBLE);
        }

        @Override void run(CommandContext ctx) {
            SproutwatchConfig c = cfg();
            if (!c.isPenSet()) {
                reply(ctx, "No pen placed yet. Run /sproutwatch place first.");
                return;
            }
            c.setCamera(ctx.get(heightArg), ctx.get(backArg), ctx.get(fovArg));
            plugin.saveConfig();
            plugin.getCameraService().refresh(Universe.get().getPlayers());
            reply(ctx, "Camera height " + c.getCameraHeight() + ", back " + c.getCameraBack() + ", fov " + c.getCameraFov() + ".");
        }
    }

    private static final class IntervalCommand extends Sub {
        private final RequiredArg<Integer> secondsArg;

        IntervalCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "interval", "Seconds between sprout spawns (min 5)", permission);
            secondsArg = withRequiredArg("seconds", "Tick interval in seconds", ArgTypes.INTEGER);
        }

        @Override void run(CommandContext ctx) {
            reply(ctx, actions().setTickSeconds(ctx.get(secondsArg)));
        }
    }

    private static final class TestCommand extends Sub {
        private final RequiredArg<String> loginArg;

        TestCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "test", "Pretend <login> is in chat: /sproutwatch test <login> [now]", permission);
            loginArg = withRequiredArg("login", "Fake viewer login", ArgTypes.STRING);
            addUsageVariant(new TestNowVariant(plugin, permission));
        }

        @Override void run(CommandContext ctx) {
            applyTest(plugin, this, ctx, ctx.get(loginArg), false);
        }
    }

    /** {@code /sproutwatch test <login> now}: same as test, then a forced tick. */
    private static final class TestNowVariant extends Sub {
        private final RequiredArg<String> loginArg;
        private final RequiredArg<String> whenArg;

        TestNowVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Pretend <login> is in chat and tick immediately", permission);
            loginArg = withRequiredArg("login", "Fake viewer login", ArgTypes.STRING);
            whenArg = withRequiredArg("when", "must be 'now'", ArgTypes.STRING);
        }

        @Override void run(CommandContext ctx) {
            if (!"now".equalsIgnoreCase(ctx.get(whenArg))) {
                reply(ctx, "Usage: /sproutwatch test <login> [now]");
                return;
            }
            applyTest(plugin, this, ctx, ctx.get(loginArg), true);
        }
    }

    /** Shared body of {@code test} and its {@code now} variant. */
    static void applyTest(SproutwatchPlugin plugin, Sub cmd, CommandContext ctx, String rawLogin, boolean now) {
        if (!cmd.cfg().isPenSet()) {
            cmd.reply(ctx, "No pen placed yet. Run /sproutwatch place first.");
            return;
        }
        String login = rawLogin == null ? "" : SproutwatchConfig.normalizeChannel(rawLogin);
        if (login.isEmpty()) {
            cmd.reply(ctx, "Give a login, e.g. /sproutwatch test alice now");
            return;
        }
        if (cmd.cfg().ignoredLogins().contains(login)) {
            cmd.reply(ctx, login + " is on the ignore list (or is the channel itself); remove them first.");
            return;
        }
        Set<String> allow = cmd.cfg().allowedLogins();
        if (!allow.isEmpty() && !allow.contains(login)) {
            cmd.reply(ctx, login + " is not on the allow list; /sproutwatch allow add " + login + " or empty the list.");
            return;
        }
        if (plugin.getRegistry().isRetired(login)) {
            cmd.reply(ctx, login + " was retired (a player killed their sprout); they get one again after leaving and rejoining chat, or after /sproutwatch clear.");
            return;
        }
        // firstSeen 0L: PenReconciler spawns the smallest firstSeen first, so the test login jumps ahead of
        // every real viewer in first-seen order (an existing entry keeps its time; putIfAbsent). The
        // "!sprout" queue is deliberately left alone: SproutQueue has no move-to-front, and offering the
        // test login would only put it behind real queued viewers anyway, so queued real viewers spawn
        // before a test viewer. Empty text: never mistaken for the queue command.
        plugin.getRoster().apply(new RosterEvent.Chat(login, ""), 0L);
        if (now) {
            if (plugin.getRegistry().contains(login)) {
                cmd.reply(ctx, login + " is already in the pen.");
                return;
            }
            int size = plugin.getRegistry().size();
            int max = cmd.cfg().getMaxSprouts();
            if (size >= max) {
                cmd.reply(ctx, "Pen is full (" + size + "/" + max + "); run /sproutwatch clear or raise MaxSprouts.");
                return;
            }
            if (PenTicker.resolveWorld(cmd.cfg()) == null) {
                cmd.reply(ctx, "Pen world is not loaded; nothing to tick.");
                return;
            }
            plugin.getTicker().tickNow();
            cmd.reply(ctx, login + " added as a test viewer; ticking now.");
        } else if (plugin.getTicker().isRunning()) {
            cmd.reply(ctx, login + " added as a test viewer; spawns on the next tick.");
        } else {
            cmd.reply(ctx, login + " added as a test viewer; run /sproutwatch start (or add 'now').");
        }
    }

    /** {@code /sproutwatch allow list | add <login> | remove <login>}: AllowUsers (empty = everyone eligible). */
    private static final class AllowCommand extends Sub {
        private static final String USAGE = "Usage: /sproutwatch allow list | add <login> | remove <login>";
        private final RequiredArg<String> actionArg;

        AllowCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "allow", "Allow list: /sproutwatch allow list | add <login> | remove <login> (empty = everyone)", permission);
            actionArg = withRequiredArg("action", "list", ArgTypes.STRING);
            addUsageVariant(new AllowEditVariant(plugin, permission));
        }

        @Override void run(CommandContext ctx) {
            if (!"list".equalsIgnoreCase(ctx.get(actionArg))) {
                reply(ctx, USAGE);
                return;
            }
            Set<String> allow = cfg().allowedLogins();
            reply(ctx, allow.isEmpty()
                ? "Allow list is empty: everyone in chat is eligible."
                : "Allow list (" + allow.size() + "): " + String.join(", ", allow));
        }
    }

    /** {@code /sproutwatch allow add|remove <login>}. */
    private static final class AllowEditVariant extends Sub {
        private final RequiredArg<String> actionArg;
        private final RequiredArg<String> loginArg;

        AllowEditVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Add or remove a login on the allow list", permission);
            actionArg = withRequiredArg("action", "add or remove", ArgTypes.STRING);
            loginArg = withRequiredArg("login", "Twitch login", ArgTypes.STRING);
        }

        @Override void run(CommandContext ctx) {
            String action = ctx.get(actionArg);
            String login = ctx.get(loginArg);
            if ("add".equalsIgnoreCase(action)) {
                reply(ctx, actions().addAllow(login));
            } else if ("remove".equalsIgnoreCase(action)) {
                reply(ctx, actions().removeAllow(login));
            } else {
                reply(ctx, AllowCommand.USAGE);
            }
        }
    }

    /** {@code /sproutwatch ignore list | add <login> | remove <login>}: IgnoreUsers (read live by the roster). */
    private static final class IgnoreCommand extends Sub {
        private static final String USAGE = "Usage: /sproutwatch ignore list | add <login> | remove <login>";
        private final RequiredArg<String> actionArg;

        IgnoreCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "ignore", "Ignore list: /sproutwatch ignore list | add <login> | remove <login>", permission);
            actionArg = withRequiredArg("action", "list", ArgTypes.STRING);
            addUsageVariant(new IgnoreEditVariant(plugin, permission));
        }

        @Override void run(CommandContext ctx) {
            if (!"list".equalsIgnoreCase(ctx.get(actionArg))) {
                reply(ctx, USAGE);
                return;
            }
            // ignoredLogins() always includes the channel login itself (the streamer never gets a sprout).
            Set<String> ignored = cfg().ignoredLogins();
            reply(ctx, ignored.isEmpty()
                ? "Ignore list is empty."
                : "Ignore list (" + ignored.size() + ", includes the channel): " + String.join(", ", ignored));
        }
    }

    /** {@code /sproutwatch ignore add|remove <login>}. */
    private static final class IgnoreEditVariant extends Sub {
        private final RequiredArg<String> actionArg;
        private final RequiredArg<String> loginArg;

        IgnoreEditVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Add or remove a login on the ignore list", permission);
            actionArg = withRequiredArg("action", "add or remove", ArgTypes.STRING);
            loginArg = withRequiredArg("login", "Twitch login", ArgTypes.STRING);
        }

        @Override void run(CommandContext ctx) {
            String action = ctx.get(actionArg);
            String login = ctx.get(loginArg);
            if ("add".equalsIgnoreCase(action)) {
                reply(ctx, actions().addIgnore(login));
            } else if ("remove".equalsIgnoreCase(action)) {
                reply(ctx, actions().removeIgnore(login));
            } else {
                reply(ctx, IgnoreCommand.USAGE);
            }
        }
    }

    /** {@code /sproutwatch queue list | clear | remove <login>}: the "!sprout" priority queue (in memory). */
    private static final class QueueCommand extends Sub {
        private static final String USAGE = "Usage: /sproutwatch queue list | clear | remove <login>";
        private final RequiredArg<String> actionArg;

        QueueCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "queue", "!sprout queue: /sproutwatch queue list | clear | remove <login>", permission);
            actionArg = withRequiredArg("action", "list or clear", ArgTypes.STRING);
            addUsageVariant(new QueueRemoveVariant(plugin, permission));
        }

        @Override void run(CommandContext ctx) {
            String action = ctx.get(actionArg);
            if ("list".equalsIgnoreCase(action)) {
                List<String> q = plugin.getQueue().snapshot();
                reply(ctx, q.isEmpty() ? "Queue is empty." : "Queue (" + q.size() + "): " + String.join(", ", q));
            } else if ("clear".equalsIgnoreCase(action)) {
                int n = plugin.getQueue().size();
                plugin.getQueue().clear();
                reply(ctx, "Queue cleared (" + n + " removed).");
            } else {
                reply(ctx, USAGE);
            }
        }
    }

    /** {@code /sproutwatch queue remove <login>}. */
    private static final class QueueRemoveVariant extends Sub {
        private final RequiredArg<String> actionArg;
        private final RequiredArg<String> loginArg;

        QueueRemoveVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Drop a login from the !sprout queue", permission);
            actionArg = withRequiredArg("action", "remove", ArgTypes.STRING);
            loginArg = withRequiredArg("login", "Twitch login", ArgTypes.STRING);
        }

        @Override void run(CommandContext ctx) {
            if (!"remove".equalsIgnoreCase(ctx.get(actionArg))) {
                reply(ctx, QueueCommand.USAGE);
                return;
            }
            String login = SproutwatchConfig.normalizeChannel(ctx.get(loginArg));
            if (login.isEmpty()) {
                reply(ctx, "Invalid login.");
                return;
            }
            boolean removed = plugin.getQueue().remove(login);
            reply(ctx, removed ? "Removed " + login + " from the queue." : login + " is not queued.");
        }
    }

    /**
     * {@code /sproutwatch persist [on|off]}: PersistSprouts. On, sprouts stay after their viewer
     * leaves chat and the longest-gone one is replaced when the pen is at MaxSprouts; off, sprouts
     * despawn GraceSeconds after their viewer leaves. No argument toggles.
     */
    private static final class PersistCommand extends Sub {
        static final String USAGE = "Usage: /sproutwatch persist [on|off]";

        PersistCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "persist", "Keep sprouts after their viewer leaves: /sproutwatch persist [on|off]", permission);
            addUsageVariant(new PersistSetVariant(plugin, permission));
        }

        @Override void run(CommandContext ctx) {
            reply(ctx, actions().setPersist(!cfg().isPersistSprouts()));
        }
    }

    /** {@code /sproutwatch persist on|off}: set explicitly. */
    private static final class PersistSetVariant extends Sub {
        private final RequiredArg<String> stateArg;

        PersistSetVariant(SproutwatchPlugin plugin, String permission) {
            super(plugin, "Turn persist on or off", permission);
            stateArg = withRequiredArg("state", "on or off", ArgTypes.STRING);
        }

        @Override void run(CommandContext ctx) {
            String state = ctx.get(stateArg);
            if ("on".equalsIgnoreCase(state)) {
                reply(ctx, actions().setPersist(true));
            } else if ("off".equalsIgnoreCase(state)) {
                reply(ctx, actions().setPersist(false));
            } else {
                reply(ctx, PersistCommand.USAGE);
            }
        }
    }
}
```

- [ ] **Step 4: Compile and confirm the file shrank**

Run: `./gradlew compileJava && wc -l src/main/java/dev/hytalemodding/sproutwatch/commands/SproutwatchCommand.java`
Expected: `BUILD SUCCESSFUL`; line count under 500 (was 645).

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 115 tests.

---

### Task 4: OpenPages (TDD), PenTicker.setAfterTick, plugin wiring

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/ui/OpenPages.java`
- Create: `src/test/java/dev/hytalemodding/sproutwatch/ui/OpenPagesTest.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenTicker.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/SproutwatchPlugin.java`

- [ ] **Step 1: Write the failing OpenPages test**

```java
package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OpenPagesTest {

    static final class FakePage implements OpenPages.Page {
        int refreshes;
        boolean alive = true;
        boolean explode;

        @Override public boolean refreshStatus() {
            refreshes++;
            if (explode) throw new IllegalStateException("boom");
            return alive;
        }
    }

    private static OpenPages pages() {
        return new OpenPages(Logger.getLogger("OpenPagesTest"));
    }

    @Test void registerAndRefreshDispatchToEveryPage() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage pa = new FakePage(), pb = new FakePage();
        p.register(a, pa);
        p.register(b, pb);
        assertEquals(2, p.size());
        assertTrue(p.isOpen(a));
        assertFalse(p.isOpen(UUID.randomUUID()));
        p.refreshAll();
        p.refreshAll();
        assertEquals(2, pa.refreshes);
        assertEquals(2, pb.refreshes);
    }

    @Test void registeringAgainReplacesThePage() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID();
        FakePage old = new FakePage(), fresh = new FakePage();
        p.register(a, old);
        p.register(a, fresh);
        assertEquals(1, p.size());
        p.refreshAll();
        assertEquals(0, old.refreshes);
        assertEquals(1, fresh.refreshes);
    }

    @Test void forgetDropsByUuidAndByInstance() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage pa = new FakePage(), pb = new FakePage(), other = new FakePage();
        p.register(a, pa);
        p.register(b, pb);
        p.forget(a);
        assertFalse(p.isOpen(a));
        p.forget(b, other);
        assertTrue(p.isOpen(b), "a different instance must not evict the registered page");
        p.forget(b, pb);
        assertFalse(p.isOpen(b));
        assertEquals(0, p.size());
        p.forget(UUID.randomUUID());
    }

    @Test void refreshDropsPagesThatReportDead() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID();
        FakePage pa = new FakePage();
        pa.alive = false;
        p.register(a, pa);
        p.refreshAll();
        assertEquals(0, p.size());
        p.refreshAll();
        assertEquals(1, pa.refreshes, "a dropped page is never refreshed again");
    }

    @Test void refreshSurvivesAThrowingPageAndDropsIt() {
        OpenPages p = pages();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        FakePage bad = new FakePage(), good = new FakePage();
        bad.explode = true;
        p.register(a, bad);
        p.register(b, good);
        assertDoesNotThrow(p::refreshAll);
        assertEquals(1, good.refreshes);
        assertFalse(p.isOpen(a));
        assertTrue(p.isOpen(b));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.ui.OpenPagesTest'`
Expected: compilation failure, `OpenPages` not found.

- [ ] **Step 3: Write OpenPages**

```java
package dev.hytalemodding.sproutwatch.ui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The settings pages currently open, keyed by player UUID. Registered by the page itself when it
 * builds, removed on dismiss (page) and disconnect (plugin). refreshAll() runs after every pen tick
 * on the pen world thread; a page that reports itself gone, or throws, is dropped.
 */
public final class OpenPages {

    /** What the registry needs from a page. */
    public interface Page {
        /** Push the current status to the client. @return false when the page is gone and should be dropped. */
        boolean refreshStatus();
    }

    private final ConcurrentHashMap<UUID, Page> pages = new ConcurrentHashMap<>();
    private final Logger logger;

    public OpenPages(Logger logger) {
        this.logger = logger;
    }

    public void register(UUID playerUuid, Page page) {
        pages.put(playerUuid, page);
    }

    /** Disconnect: whatever page the player had is gone. */
    public void forget(UUID playerUuid) {
        pages.remove(playerUuid);
    }

    /** Dismiss: drop only if this exact page is the registered one (a newer page may have replaced it). */
    public void forget(UUID playerUuid, Page page) {
        pages.remove(playerUuid, page);
    }

    public boolean isOpen(UUID playerUuid) {
        return pages.containsKey(playerUuid);
    }

    public int size() {
        return pages.size();
    }

    public void refreshAll() {
        for (Map.Entry<UUID, Page> e : pages.entrySet()) {
            boolean keep;
            try {
                keep = e.getValue().refreshStatus();
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "Sproutwatch settings page refresh failed for " + e.getKey() + "; dropping it", ex);
                keep = false;
            }
            if (!keep) pages.remove(e.getKey(), e.getValue());
        }
    }
}
```

- [ ] **Step 4: Run the OpenPages test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.ui.OpenPagesTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Add the after-tick hook to PenTicker**

In `PenTicker.java`, directly below the field `private volatile Consumer<World> onWorldReady;` add:

```java
    private volatile Runnable afterTick;
```

Directly below the `setOnWorldReady` method add:

```java
    /** Runs on the pen world thread at the end of every tick (settings pages push their status). */
    public void setAfterTick(Runnable hook) {
        this.afterTick = hook;
    }
```

In `tickOnWorldThread`, replace

```java
            plan.spawn().ifPresent(login -> {
                if (spawner.spawn(world, login)) roster.queue().remove(login);
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch tick failed", e);
        }
```

with

```java
            plan.spawn().ifPresent(login -> {
                if (spawner.spawn(world, login)) roster.queue().remove(login);
            });

            Runnable after = afterTick;
            if (after != null) {
                try {
                    after.run();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Sproutwatch after-tick hook failed", e);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch tick failed", e);
        }
```

- [ ] **Step 6: Wire OpenPages into the plugin**

In `SproutwatchPlugin.java` (the Task 3 version) make these four edits.

Add the import (alphabetically among the `dev.hytalemodding.sproutwatch.ui` imports):

```java
import dev.hytalemodding.sproutwatch.ui.OpenPages;
```

Add the field directly below `private final SproutwatchActions actions;`:

```java
    private final OpenPages openPages;
```

In the constructor replace

```java
        this.actions = new SproutwatchActions(this);
```

with

```java
        this.actions = new SproutwatchActions(this);
        this.openPages = new OpenPages(bridgeLogger);
        // Live status for open settings pages: after every tick, on the pen world thread.
        ticker.setAfterTick(openPages::refreshAll);
```

In `setup()` replace

```java
            getEventRegistry().register(PlayerDisconnectEvent.class, e -> cameraService.forget(e.getPlayerRef().getUuid()));
```

with

```java
            getEventRegistry().register(PlayerDisconnectEvent.class, e -> {
                java.util.UUID uuid = e.getPlayerRef().getUuid();
                cameraService.forget(uuid);
                openPages.forget(uuid);
            });
```

Add the getter directly below `getActions()`:

```java
    /** Settings pages currently open, for live status pushes and disconnect cleanup. */
    public OpenPages getOpenPages() { return openPages; }
```

Replace the Task 3 placeholder

```java
    @Override public void statusChanged() { } // filled in Task 4 once OpenPages exists
```

with the real body (place/clear finish on a world thread with no tick necessarily running; `refreshStatus` marshals onto each player's world itself, so this is safe from any thread):

```java
    /** Place/clear finished outside a tick: push the new status to every open settings page. */
    @Override public void statusChanged() { openPages.refreshAll(); }
```

- [ ] **Step 7: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 120 tests.

---

### Task 5: The `.ui` documents

**Files:**
- Create: `src/main/resources/Common/UI/Custom/Pages/Sproutwatch/SettingsPage.ui`
- Create: `src/main/resources/Common/UI/Custom/Pages/Sproutwatch/ListEntryRow.ui`

The documents are parsed by the client, not the server, so the only automated check is that they are packed into the jar. Every template and property below exists in the release `Common.ui` (see the research table). Field rows copy the shape of vanilla `Pages/Fields/{TextRow,IntRow,CheckboxRow,DropdownRow}.ui` inline so each field has a fixed id. All groups that the server toggles start `Visible: true` except the two hidden tabs, and the server sets every `.Visible`, `.Text`, `.Value`, `.Entries` and `.Disabled` it cares about on every build, so the initial values only matter for a client that never receives a build.

- [ ] **Step 1: Write SettingsPage.ui**

```
$C = "../../Common.ui";

$C.@PageOverlay {
  LayoutMode: Middle;

  $C.@DecoratedContainer {
    Anchor: (Width: 720, Height: 680);

    #Title {
      $C.@Title {
        @Text = "Sproutwatch settings";
      }
    }

    #Content {
      LayoutMode: Top;
      Padding: (Full: 12);

      // Tab bar: the server disables the active tab's button (TriggerVolume inspector pattern).
      Group #TabBar {
        LayoutMode: Left;
        Anchor: (Height: 32, Bottom: 8);

        $C.@SmallSecondaryTextButton #TabStatus {
          @Text = "Status";
          Anchor: (Width: 120, Height: 30, Right: 4);
        }

        $C.@SmallSecondaryTextButton #TabListener {
          @Text = "Listener";
          Anchor: (Width: 120, Height: 30, Right: 4);
        }

        $C.@SmallSecondaryTextButton #TabPen {
          @Text = "Pen";
          Anchor: (Width: 120, Height: 30);
        }
      }

      // ---------------- Status tab ----------------
      Group #StatusTab {
        LayoutMode: TopScrolling;
        ScrollbarStyle: $C.@DefaultScrollbarStyle;
        Anchor: (Height: 520);
        Visible: true;

        Label #ListenerLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #ChannelLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #FeedLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #RosterLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #QueueLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #PenCountLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #RetiredLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #PersistLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #PenPlacedLabel {
          Text: "";
          Anchor: (Bottom: 3);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        Label #ChairLabel {
          Text: "";
          Anchor: (Bottom: 12);
          Style: (...$C.@DefaultLabelStyle, FontSize: 15, Wrap: true);
        }

        $C.@Subtitle {
          @Text = "Channel";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@TextField #ChannelField {
            @Anchor = (Right: 8);
            FlexWeight: 1;
            PlaceholderText: "twitch channel name";
          }

          $C.@TextButton #SaveChannelButton {
            @Text = "Save";
            @Anchor = (Width: 120);
          }
        }

        $C.@Subtitle {
          @Text = "Allow list (empty = everyone in chat is eligible)";
        }

        Label #AllowEmptyLabel {
          Text: "Allow list is empty: everyone in chat is eligible.";
          Anchor: (Bottom: 4);
          Style: (...$C.@DefaultLabelStyle, FontSize: 14);
          Visible: true;
        }

        Group #AllowList {
          LayoutMode: TopScrolling;
          ScrollbarStyle: $C.@DefaultScrollbarStyle;
          Anchor: (Height: 96, Bottom: 6);
          Background: (Color: #000000(0.15));
          Padding: (Full: 6);
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@TextField #AllowField {
            @Anchor = (Right: 8);
            FlexWeight: 1;
            PlaceholderText: "login to allow";
          }

          $C.@SecondaryTextButton #AddAllowButton {
            @Text = "Add";
            @Anchor = (Width: 120);
          }
        }

        $C.@Subtitle {
          @Text = "Ignore list (the channel login is always ignored)";
        }

        Group #IgnoreList {
          LayoutMode: TopScrolling;
          ScrollbarStyle: $C.@DefaultScrollbarStyle;
          Anchor: (Height: 96, Bottom: 6);
          Background: (Color: #000000(0.15));
          Padding: (Full: 6);
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@TextField #IgnoreField {
            @Anchor = (Right: 8);
            FlexWeight: 1;
            PlaceholderText: "login to ignore";
          }

          $C.@SecondaryTextButton #AddIgnoreButton {
            @Text = "Add";
            @Anchor = (Width: 120);
          }
        }
      }

      // ---------------- Listener tab ----------------
      Group #ListenerTab {
        LayoutMode: TopScrolling;
        ScrollbarStyle: $C.@DefaultScrollbarStyle;
        Anchor: (Height: 520);
        Visible: false;

        $C.@Subtitle {
          @Text = "Start / stop";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 16);

          $C.@CheckBox #RunCheck {
            Anchor: (Width: 26, Height: 26);
          }

          Label #RunLabel {
            Text: "";
            Anchor: (Left: 11);
            FlexWeight: 1;
            Style: (...$C.@DefaultLabelStyle, FontSize: 15, VerticalAlignment: Center, Wrap: true);
          }
        }

        $C.@Subtitle {
          @Text = "Persist";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 16);

          $C.@CheckBox #PersistCheck {
            Anchor: (Width: 26, Height: 26);
          }

          Label #PersistCheckLabel {
            Text: "";
            Anchor: (Left: 11);
            FlexWeight: 1;
            Style: (...$C.@DefaultLabelStyle, FontSize: 15, VerticalAlignment: Center, Wrap: true);
          }
        }

        $C.@Subtitle {
          @Text = "Tick interval (seconds, min 5)";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@NumberField #IntervalField {
            @Anchor = (Width: 140, Right: 8);
            Format: (MaxDecimalPlaces: 0, Step: 1);
          }

          $C.@TextButton #SaveIntervalButton {
            @Text = "Save";
            @Anchor = (Width: 120);
          }
        }
      }

      // ---------------- Pen tab ----------------
      Group #PenTab {
        LayoutMode: TopScrolling;
        ScrollbarStyle: $C.@DefaultScrollbarStyle;
        Anchor: (Height: 520);
        Visible: false;

        $C.@Subtitle {
          @Text = "Pen prefab (saved on change)";
        }

        $C.@DropdownBox #PrefabDropdown {
          @Anchor = (Bottom: 16);
        }

        $C.@Subtitle {
          @Text = "Placement";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@TextButton #PlaceButton {
            @Text = "Place pen here";
            @Anchor = (Width: 200, Right: 8);
          }

          $C.@CancelTextButton #ClearButton {
            @Text = "Clear sprouts";
            @Anchor = (Width: 200);
          }
        }

        Label #PenInfoLabel {
          Text: "";
          Anchor: (Bottom: 4);
          Style: (...$C.@DefaultLabelStyle, FontSize: 14, Wrap: true);
        }
      }

      // Result of the last action (all tabs).
      Label #MessageLabel {
        Text: "";
        Anchor: (Top: 8, Height: 40);
        Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: #E8A93B, Wrap: true);
      }
    }
  }
}

$C.@BackButton {}
```

- [ ] **Step 2: Write ListEntryRow.ui**

```
$C = "../../Common.ui";

// One allow/ignore list entry. The server fills #Login.Text, binds #RemoveButton with the login,
// and hides the button on the fixed channel row.
Group {
  LayoutMode: Left;
  Anchor: (Height: 30, Bottom: 2);

  Label #Login {
    Text: "";
    Style: (...$C.@DefaultLabelStyle, FontSize: 14, VerticalAlignment: Center);
    FlexWeight: 1;
  }

  $C.@SmallSecondaryTextButton #RemoveButton {
    @Text = "Remove";
    Anchor: (Width: 96, Height: 26);
  }
}
```

- [ ] **Step 3: Confirm both documents get packed into the jar**

Run: `./gradlew jar && unzip -l build/libs/sproutwatch-0.1.0.jar | grep 'Pages/Sproutwatch/'`
Expected: two lines, `Common/UI/Custom/Pages/Sproutwatch/SettingsPage.ui` and `Common/UI/Custom/Pages/Sproutwatch/ListEntryRow.ui`.

- [ ] **Step 4: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 120 tests.

---

### Task 6: SettingsEvent, SproutwatchSettingsPage and the `settings` subcommand (engine)

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/ui/SettingsEvent.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/ui/SproutwatchSettingsPage.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/commands/SproutwatchCommand.java`

Engine-bound: compile step here, behaviour checked in Task 7.

- [ ] **Step 1: Write SettingsEvent**

```java
package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Data the client sends back with a settings-page event. Same shape as the engine's
 * TeleporterSettingsPage.PageEventData: a mutable class filled by a BuilderCodec. Keys without
 * "@" are literals baked into the binding (which button, which row); keys with "@" are read from
 * the named field on the client when the event fires. Every key is optional because each binding
 * carries only the keys it needs, so the fields are boxed and null when absent.
 */
public final class SettingsEvent {

    public static final String KEY_ACTION = "Action";
    public static final String KEY_TAB = "Tab";
    public static final String KEY_LOGIN = "Login";
    public static final String KEY_CHANNEL = "@Channel";
    public static final String KEY_ALLOW_INPUT = "@AllowInput";
    public static final String KEY_IGNORE_INPUT = "@IgnoreInput";
    public static final String KEY_INTERVAL = "@Interval";
    public static final String KEY_PREFAB = "@Prefab";
    public static final String KEY_RUN = "@Run";
    public static final String KEY_PERSIST = "@Persist";

    public static final BuilderCodec<SettingsEvent> CODEC = BuilderCodec.builder(SettingsEvent.class, SettingsEvent::new)
        .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING, false), (e, v) -> e.action = v, e -> e.action).add()
        .append(new KeyedCodec<>(KEY_TAB, Codec.STRING, false), (e, v) -> e.tab = v, e -> e.tab).add()
        .append(new KeyedCodec<>(KEY_LOGIN, Codec.STRING, false), (e, v) -> e.login = v, e -> e.login).add()
        .append(new KeyedCodec<>(KEY_CHANNEL, Codec.STRING, false), (e, v) -> e.channel = v, e -> e.channel).add()
        .append(new KeyedCodec<>(KEY_ALLOW_INPUT, Codec.STRING, false), (e, v) -> e.allowInput = v, e -> e.allowInput).add()
        .append(new KeyedCodec<>(KEY_IGNORE_INPUT, Codec.STRING, false), (e, v) -> e.ignoreInput = v, e -> e.ignoreInput).add()
        .append(new KeyedCodec<>(KEY_INTERVAL, Codec.INTEGER, false), (e, v) -> e.interval = v, e -> e.interval).add()
        .append(new KeyedCodec<>(KEY_PREFAB, Codec.STRING, false), (e, v) -> e.prefab = v, e -> e.prefab).add()
        .append(new KeyedCodec<>(KEY_RUN, Codec.BOOLEAN, false), (e, v) -> e.run = v, e -> e.run).add()
        .append(new KeyedCodec<>(KEY_PERSIST, Codec.BOOLEAN, false), (e, v) -> e.persist = v, e -> e.persist).add()
        .build();

    public String action;
    public String tab;
    public String login;
    public String channel;
    public String allowInput;
    public String ignoreInput;
    public Integer interval;
    public String prefab;
    public Boolean run;
    public Boolean persist;
}
```

- [ ] **Step 2: Write SproutwatchSettingsPage**

```java
package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.prefab.PenPrefabCatalog;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The /sproutwatch settings page: Status, Listener and Pen tabs (groups toggled by Visible), every
 * button routed through SproutwatchActions, result shown on the message line, page rebuilt after
 * each action. Registers itself in OpenPages on build so PenTicker's after-tick hook can push the
 * status labels live via refreshStatus(); removed on dismiss (here) and disconnect (plugin).
 */
public final class SproutwatchSettingsPage extends InteractiveCustomUIPage<SettingsEvent> implements OpenPages.Page {

    static final String DOCUMENT = "Pages/Sproutwatch/SettingsPage.ui";
    static final String LIST_ROW = "Pages/Sproutwatch/ListEntryRow.ui";

    enum Tab { STATUS, LISTENER, PEN }

    private final SproutwatchActions actions;
    private final OpenPages openPages;
    private final Logger logger;
    private volatile Tab tab = Tab.STATUS;
    private volatile String message = "";

    public SproutwatchSettingsPage(PlayerRef playerRef, SproutwatchActions actions, OpenPages openPages, Logger logger) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, SettingsEvent.CODEC);
        this.actions = actions;
        this.openPages = openPages;
        this.logger = logger;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder evt, @Nonnull Store<EntityStore> store) {
        openPages.register(playerRef.getUuid(), this);
        StatusSnapshot s = actions.snapshot();
        SproutwatchConfig cfg = actions.config();
        cmd.append(DOCUMENT);
        buildTabs(cmd, evt);
        setStatusLabels(cmd, s);
        buildStatusTab(cmd, evt, cfg);
        buildListenerTab(cmd, evt, s);
        buildPenTab(cmd, evt, s);
        cmd.set("#MessageLabel.Text", message);
    }

    private void buildTabs(UICommandBuilder cmd, UIEventBuilder evt) {
        for (Tab t : Tab.values()) {
            String button = tabButton(t);
            cmd.set(button + ".Disabled", t == tab);
            evt.addEventBinding(CustomUIEventBindingType.Activating, button,
                new EventData().append(SettingsEvent.KEY_ACTION, "tab").append(SettingsEvent.KEY_TAB, t.name()));
        }
        cmd.set("#StatusTab.Visible", tab == Tab.STATUS);
        cmd.set("#ListenerTab.Visible", tab == Tab.LISTENER);
        cmd.set("#PenTab.Visible", tab == Tab.PEN);
    }

    private static String tabButton(Tab t) {
        return switch (t) {
            case STATUS -> "#TabStatus";
            case LISTENER -> "#TabListener";
            case PEN -> "#TabPen";
        };
    }

    /** The labels refreshStatus() pushes every tick. Never a field value. */
    private static void setStatusLabels(UICommandBuilder cmd, StatusSnapshot s) {
        cmd.set("#ListenerLabel.Text", s.listenerLine());
        cmd.set("#ChannelLabel.Text", s.channelLine());
        cmd.set("#FeedLabel.Text", s.feedLine());
        cmd.set("#RosterLabel.Text", s.rosterLine());
        cmd.set("#QueueLabel.Text", s.queueLine());
        cmd.set("#PenCountLabel.Text", s.penCountLine());
        cmd.set("#RetiredLabel.Text", s.retiredLine());
        cmd.set("#PersistLabel.Text", s.persistLine());
        cmd.set("#PenPlacedLabel.Text", s.penPlacedLine());
        cmd.set("#ChairLabel.Text", s.chairLine());
        cmd.set("#RunLabel.Text", s.runLabel());
        cmd.set("#PenInfoLabel.Text", s.penInfoLine());
    }

    private void buildStatusTab(UICommandBuilder cmd, UIEventBuilder evt, SproutwatchConfig cfg) {
        cmd.set("#ChannelField.Value", cfg.getTwitchChannel());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveChannelButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveChannel").append(SettingsEvent.KEY_CHANNEL, "#ChannelField.Value"));

        List<String> allow = List.copyOf(cfg.allowedLogins());
        cmd.set("#AllowEmptyLabel.Visible", allow.isEmpty());
        int i = 0;
        for (String login : allow) {
            String sel = "#AllowList[" + i + "]";
            cmd.append("#AllowList", LIST_ROW);
            cmd.set(sel + " #Login.Text", login);
            evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RemoveButton",
                new EventData().append(SettingsEvent.KEY_ACTION, "removeAllow").append(SettingsEvent.KEY_LOGIN, login));
            i++;
        }
        cmd.set("#AllowField.Value", "");
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AddAllowButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "addAllow").append(SettingsEvent.KEY_ALLOW_INPUT, "#AllowField.Value"));

        // ignoredLogins() always ends with the channel login; that row is fixed (no Remove button).
        String channel = cfg.getTwitchChannel();
        i = 0;
        for (String login : cfg.ignoredLogins()) {
            String sel = "#IgnoreList[" + i + "]";
            boolean fixed = login.equals(channel);
            cmd.append("#IgnoreList", LIST_ROW);
            cmd.set(sel + " #Login.Text", fixed ? login + " (the channel)" : login);
            cmd.set(sel + " #RemoveButton.Visible", !fixed);
            if (!fixed) {
                evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RemoveButton",
                    new EventData().append(SettingsEvent.KEY_ACTION, "removeIgnore").append(SettingsEvent.KEY_LOGIN, login));
            }
            i++;
        }
        cmd.set("#IgnoreField.Value", "");
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AddIgnoreButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "addIgnore").append(SettingsEvent.KEY_IGNORE_INPUT, "#IgnoreField.Value"));
    }

    private void buildListenerTab(UICommandBuilder cmd, UIEventBuilder evt, StatusSnapshot s) {
        cmd.set("#RunCheck.Value", s.listenerRunning());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#RunCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, "toggleListener").append(SettingsEvent.KEY_RUN, "#RunCheck.Value"), false);
        cmd.set("#PersistCheck.Value", s.persist());
        cmd.set("#PersistCheckLabel.Text", s.persistLabel());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PersistCheck",
            new EventData().append(SettingsEvent.KEY_ACTION, "setPersist").append(SettingsEvent.KEY_PERSIST, "#PersistCheck.Value"), false);
        cmd.set("#IntervalField.Value", s.tickSeconds());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveIntervalButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveInterval").append(SettingsEvent.KEY_INTERVAL, "#IntervalField.Value"));
    }

    private void buildPenTab(UICommandBuilder cmd, UIEventBuilder evt, StatusSnapshot s) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (String name : PenPrefabCatalog.names()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(name), name));
        }
        cmd.set("#PrefabDropdown.Entries", entries);
        cmd.set("#PrefabDropdown.Value", s.prefabName());
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PrefabDropdown",
            new EventData().append(SettingsEvent.KEY_ACTION, "selectPrefab").append(SettingsEvent.KEY_PREFAB, "#PrefabDropdown.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#PlaceButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "place"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#ClearButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "clear"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull SettingsEvent e) {
        String action = e.action == null ? "" : e.action;
        try {
            message = switch (action) {
                case "tab" -> {
                    selectTab(e.tab);
                    yield message;
                }
                case "saveChannel" -> actions.setChannel(e.channel);
                case "addAllow" -> actions.addAllow(e.allowInput);
                case "removeAllow" -> actions.removeAllow(e.login);
                case "addIgnore" -> actions.addIgnore(e.ignoreInput);
                case "removeIgnore" -> actions.removeIgnore(e.login);
                case "toggleListener" -> Boolean.TRUE.equals(e.run) ? actions.startListener() : actions.stopListener();
                case "setPersist" -> actions.setPersist(Boolean.TRUE.equals(e.persist));
                case "saveInterval" -> e.interval == null ? "Enter a number of seconds (min 5)." : actions.setTickSeconds(e.interval);
                case "selectPrefab" -> actions.selectPrefab(e.prefab);
                case "place" -> actions.place(playerRef);
                case "clear" -> actions.clear();
                default -> "Unknown action: " + action;
            };
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page action '" + action + "' failed", ex);
            message = "Action failed: " + ex.getMessage() + " (see server log)";
        }
        rebuild();
    }

    private void selectTab(String name) {
        if (name == null) return;
        try {
            tab = Tab.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            // unknown tab name from the client: keep the current tab
        }
    }

    /** Called on the pen world thread after every tick; sendUpdate marshals onto the player's world. */
    @Override
    public boolean refreshStatus() {
        if (!playerRef.isValid() || playerRef.getReference() == null) return false;
        try {
            UICommandBuilder cmd = new UICommandBuilder();
            setStatusLabels(cmd, actions.snapshot());
            sendUpdate(cmd);
            return true;
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page refresh failed; dropping the page", ex);
            return false;
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        openPages.forget(playerRef.getUuid(), this);
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava && wc -l src/main/java/dev/hytalemodding/sproutwatch/ui/SproutwatchSettingsPage.java`
Expected: `BUILD SUCCESSFUL`; the page is under 260 lines. If `cmd.set("#PrefabDropdown.Entries", entries)` fails to resolve, the `List<T>` overload exists (`UICommandBuilder.set(String, List<T>)`); check the import is `java.util.List`.

- [ ] **Step 4: Add the `settings` subcommand**

In `SproutwatchCommand.java` add these imports:

```java
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.ui.SproutwatchSettingsPage;
import java.util.logging.Level;
```

In the constructor, after `addSubCommand(new PersistCommand(plugin, permission));` add:

```java
        addSubCommand(new SettingsCommand(plugin, permission));
```

Add this class at the end of `SproutwatchCommand` (before the final closing brace):

```java
    /** {@code /sproutwatch settings}: opens the settings page for the sending player. */
    private static final class SettingsCommand extends Sub {
        SettingsCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "settings", "Open the Sproutwatch settings page", permission);
        }

        @Override void run(CommandContext ctx) {
            PlayerRef sender = player(ctx);
            if (sender == null) return;
            World world = Universe.get().getWorld(sender.getWorldUuid());
            if (world == null) {
                reply(ctx, "Your world is not loaded.");
                return;
            }
            // openCustomPage must run on the player's world thread (the page builds from there).
            boolean queued = plugin.runOnWorld(world, () -> {
                try {
                    Ref<EntityStore> ref = sender.getReference();
                    if (ref == null || !ref.isValid()) return;
                    Store<EntityStore> store = ref.getStore();
                    Player player = store.getComponent(ref, Player.getComponentType());
                    if (player == null) return;
                    player.getPageManager().openCustomPage(ref, store,
                        new SproutwatchSettingsPage(sender, plugin.getActions(), plugin.getOpenPages(), plugin.getBridgeLogger()));
                } catch (RuntimeException e) {
                    plugin.getBridgeLogger().log(Level.WARNING, "Sproutwatch settings page failed to open", e);
                    sender.sendMessage(Message.raw("Could not open the settings page: " + e.getMessage() + " (see server log)"));
                }
            });
            if (!queued) reply(ctx, "Your world is unloading; try again.");
        }
    }
```

- [ ] **Step 5: Compile everything**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 120 tests.

---

### Task 7: Deploy, headless boot check, in-game verification

**Files:** none new.

- [ ] **Step 1: Deploy**

Run: `./deploy.sh`
Expected: `✓ Deployed sproutwatch-0.1.0.jar to .../UserData/Mods`.

- [ ] **Step 2: Headless boot (plugin load, config key, asset pack)**

The server also loads `./mods` relative to its cwd, so the cwd must NOT contain a `mods/` directory ("Tried to load duplicate plugin"):

```bash
S=/private/tmp/sproutwatch-boot && rm -rf "$S" && mkdir -p "$S/mods" "$S/early" "$S/cache" "$S/universe" "$S/cwd" && cd "$S/cwd"
cp ~/Developer/hytale/sproutwatch/build/libs/sproutwatch-0.1.0.jar "$S/mods/"
JAVA="$HOME/Library/Application Support/Hytale/install/release/package/jre/latest/Contents/Home/bin/java"
G="$HOME/Library/Application Support/Hytale/install/release/package/game/latest"
"$JAVA" -Xms512M -jar "$G/Server/HytaleServer.jar" --assets="$G/Assets.zip" --mods="$S/mods" \
  --early-plugins="$S/early" --prefab-cache="$S/cache" --bind localhost:57999 --auth-mode=offline \
  --universe="$S/universe" --transport QUICHE > boot.log 2>&1 &
sleep 40; kill %1
sed 's/\x1b\[[0-9;]*m//g' boot.log | grep -iE 'sproutwatch|FAIL:|validation failed|Unknown JSON attribute|duplicate plugin|Exception' | head -40
grep -c PenPrefab "$S/universe"/*/mods/Mertie_sproutwatch/Sproutwatch_config.json 2>/dev/null || find "$S" -name Sproutwatch_config.json -exec grep -H PenPrefab {} \;
```

Expected: `Enabled plugin Mertie:sproutwatch`, no `FAIL:` / `Unknown JSON attribute` / exception lines mentioning sproutwatch, and the written `Sproutwatch_config.json` contains `"PenPrefab": "default"`. `.ui` documents are parsed by the client only, so the boot proves nothing about them; step 4 does.

- [ ] **Step 3: Re-check the commands did not change (Mertie or Claude, any world)**

`/sproutwatch status`, `/sproutwatch channel <name>`, `/sproutwatch allow list`, `/sproutwatch persist` twice, `/sproutwatch interval 60`: replies read exactly as before (same wording as the previous plan's Verification section).

- [ ] **Step 4: In-game checklist (Mertie, Testr World, admin)**

Report each line pass/fail; on a failure include the server log lines and, for a blank or broken page, the client log. A page that stays blank means the `.ui` failed to parse: compare the document against `Pages/Point/PointInspectorPage.ui` in Assets.zip, fix, redeploy. Known engine race, not a bug to chase: `PageManager` drops client data events while an update it sent is still unacknowledged, so a click that lands in the round trip right after a tick push (or a place/clear push) can be silently lost; just click again.

1. `/sproutwatch settings` from the server console replies `Run this in game as a player.`.
2. `/sproutwatch settings` in game opens the page on the Status tab: the Status button is greyed (disabled), the ten status lines match `/sproutwatch status`, the channel field is prefilled, the allow list shows the "empty" line, the ignore list shows nightbot/streamelements/streamlabs with Remove buttons and the channel row without one. Also check the message line at the bottom is fully visible, not clipped by the container edge (if it is, lower the three tab groups' `Anchor: (Height: 520)` in `SettingsPage.ui`).
3. Click Listener, then Pen: the group switches, the clicked tab button greys out, the other two are clickable; the message line keeps its text across tab switches.
4. Status tab, channel: type a channel with capitals and `#`, Save: message line shows `Channel set to #<lowercase>...`, the field shows the normalised name, `Sproutwatch_config.json` has it.
5. Allow list: type `Alice`, Add: a row `alice` with Remove appears, the "empty" line disappears, message `Added alice to the allow list.`; Remove on the row: row gone, message `Removed alice from the allow list.`; Add with a blank field: `Invalid login.`.
6. Ignore list: Add `spammer`: row appears; Remove it; the channel row never shows Remove.
7. Listener tab with no pen placed: tick the Start checkbox: message `No pen placed yet. Stand where you want it and run /sproutwatch place.` and the checkbox is unticked again after the rebuild.
8. Pen tab: the prefab dropdown lists `default` and shows it selected; Place: message `Placing the pen...`, the pen appears centred on you, the chat gets the `Pen placed (...)` line, and within one tick the Pen line and the read-only info line show the new position (live push, no click).
9. Listener tab: Save interval `3`: message `Tick interval is now 5s.` and the field shows 5 (NOT VERIFIED item from the research table: if the server log shows a codec/decode exception when Save is pressed, switch `@Interval` to `Codec.DOUBLE` / `Double interval` in `SettingsEvent` and use `(int) Math.round(e.interval)` in the page, redeploy, retest). Save `30`: `Tick interval is now 30s.`.
10. Tick the Start checkbox: message `Sproutwatch watching #<channel>; one sprout every 30s.`, the caption reads `Listener running (connected to #<channel>); untick to stop`, the Status tab's Listener line updates within a tick.
11. Live status: with the page open on the Status tab, `/sproutwatch test bob now` from a second account or the console: the Roster and Pen lines change on their own within one tick; a channel you were typing in the field is NOT reset by the push.
12. Persist checkbox: untick: message `Persist is now off: ...`, caption changes, `/sproutwatch status` agrees; tick it back.
13. Untick Start: `Sproutwatch stopped.`; status lines update.
14. Pen tab, Clear: `Clearing the pen...`, sprouts vanish, Pen count line drops to 0 within a tick.
15. Dismiss (Escape / back button) and reopen: opens on the Status tab with an empty message line (page state is not persisted); server log shows no warning.
16. Open the page, then disconnect the client while it is open, reconnect: no `Sproutwatch settings page refresh failed` warnings keep repeating in the server log (the disconnect hook forgot the page).
17. Open the page, run `/sproutwatch settings` again without closing: a single page (no error), the old one was replaced.
18. Status tab while the listener runs: the feed line reads `Twitch JOIN/PART feed: on`; after stopping it reads `n/a (listener stopped)`.

- [ ] **Step 5: Record the results**

Add a `## Verification 2026-xx-xx` section at the bottom of this plan with the checklist results and any fixes applied (in particular whether item 9 needed the `Codec.DOUBLE` switch).

- [ ] **Step 6: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 120 tests.

---

### Task 8: Review and commit handoff

- [ ] **Step 1: Full build**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`, 120 tests (94 before this plan + 4 catalog + 1 config + 16 actions + 5 open-pages). Report the exact number if it differs and say why.

- [ ] **Step 2: Placeholder and size scan**

Run: `grep -rn '{0}' src/main; grep -rn 'TODO\|TBD' src/main; wc -l src/main/java/dev/hytalemodding/sproutwatch/ui/*.java src/main/java/dev/hytalemodding/sproutwatch/commands/SproutwatchCommand.java`
Expected: the two greps print nothing; `SproutwatchSettingsPage.java` under 300 lines; `SproutwatchCommand.java` under 540 lines.

- [ ] **Step 3: Stage and summarise**

Run: `git add -A && git status --short && git diff --cached --stat | tail -3`

- [ ] **Step 4: Ask Mertie**

Post the test count, the in-game checklist results and the proposed commit message below, then STOP and wait for an explicit go-ahead before running `git commit` (never push). The tree still has zero commits; if Mertie did not commit the pre-settings state first, this becomes the first commit and the subject should be the previous plan's `feat: sproutwatch v0.1.0 — Twitch viewers as baby Kweebecs in a pen` with the settings-page paragraph appended to its body.

```
feat: in-game settings page (/sproutwatch settings)

InteractiveCustomUIPage with Status, Listener and Pen tabs (groups toggled by
Visible), every mutation routed through SproutwatchActions and shared with the
chat commands, live status via a PenTicker after-tick hook and OpenPages,
PenPrefabCatalog + PenPrefab config key. 121 unit tests; in-game checklist in
docs/superpowers/plans/2026-09-30-sproutwatch-settings-page.md.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XK9Kn4zsgkY2pqtL8U56VF
```

---

## Self-review notes (written with the plan)

- Spec coverage: §3 mechanism -> research table; §4 `SproutwatchActions` Task 2, `SproutwatchCommand` Task 3 + Task 6 step 4, `SproutwatchSettingsPage` Task 6, `OpenPages` Task 4, `PenTicker.setAfterTick` Task 4, `PenPrefabCatalog` / config `PenPrefab` / `PenPlacer` Task 1, plugin wiring Tasks 3-4, `.ui` documents Task 5; §5 layout (tab bar, status block, channel, allow, ignore, start/stop checkbox, persist checkbox, interval, prefab dropdown, place, clear, read-only line, message line) Tasks 5-6; §6 data flow (open on world thread, `Action` key + `@` values, per-row `Login`, event -> action -> message -> rebuild, world-thread actions answered with an interim message, live status via after-tick -> `refreshAll` -> `refreshStatus` labels only, removal on dismiss/disconnect, persistence via actions + `saveConfig`) Tasks 2, 4, 6; §7 error handling (try/catch + WARNING in `handleDataEvent`, `refreshStatus` and the open command; dead page dropped by `OpenPages`; `normalizeChannel` with "Invalid login." / "Invalid channel name."; console reply) Tasks 2, 4, 6; §8 tests: actions against a fake host (channel with/without running listener, start refusals, allow/ignore add/remove + normalisation, persist, interval, prefab unknown, snapshot fields), catalog names/fallback, OpenPages add/forget/refresh with a fake page (Tasks 1, 2, 4); engine checklist Task 7 (opens, each tab, each button, live update while a viewer joins, place/clear from the page, dismiss + reopen, disconnect while open).
- Placeholder scan: no "TBD", "similar to", or "add validation" steps; every step carries its full code or exact command.
- Signature consistency: `ActionsHost` methods used by `SproutwatchActions` (Task 2), implemented by the plugin (Task 3, `statusChanged` body filled in Task 4) and faked in the test (Task 2) are the same 18 members; `SproutwatchActions` public methods used by the command (Task 3) and the page (Task 6) match the file-map list; `OpenPages.Page.refreshStatus()` returns boolean in the interface (Task 4), the fake (Task 4) and the page (Task 6); `StatusSnapshot` component order in the record (Task 2 step 3) matches the constructor call in `snapshot()` (step 5) and the accessors asserted in the test (step 1); `.ui` ids in Task 5 match every selector string in Task 6; `SettingsEvent` keys used in Task 6 bindings are the `KEY_*` constants.
- Thread model: `handleDataEvent`/`rebuild` run on the player's world thread; `refreshAll` runs on the pen world thread and `sendUpdate` marshals to the player's world (engine `InteractiveCustomUIPage.sendUpdate`); `SproutwatchActions` itself is thread-agnostic and only mutates the config (volatile fields) and thread-safe roster/registry.
