# Sproutwatch Settings Page Revisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Revise the deployed `/sproutwatch settings` page per spec `docs/superpowers/specs/2026-09-30-sproutwatch-settings-page-revisions-design.md`: header-tab-styled tabs switched by a partial update (no rebuild), a fourth **Viewers** tab holding the allow/ignore lists, and a single Start / Stop listener button in place of the checkbox.

**Architecture:** The tab bar becomes a small `SettingsTab` enum (button selector, group selector, style swap, name parsing) that the page calls from `build()` and from the `tab` event; the `tab` event sends only the enum's `.Style`/`.Visible` sets via `sendUpdate`, every other action still `rebuild()`s. The `.ui` declares two `TextButtonStyle`s built from `Common.ui`'s header-tab 9-patch textures, which the server selects with `Value.ref(DOCUMENT, name)` exactly as vanilla `UIGalleryPage` refs a style in its own row document. Start / Stop is the vanilla `MemoriesPage` twin-element trick: two pre-styled buttons in one slot, the server shows one.

**Tech Stack:** Java 25, Gradle (`./gradlew`), JUnit 5, Hytale server API (`HytaleServer.jar` on compile and test classpaths), Hytale client `.ui` documents (parsed by the client only).

**Working state:** branch `dev` has ZERO commits; everything is staged. Checkpoints are `git add -A` only — Mertie commits personally (never commit or push without an explicit go-ahead).

**Verified engine facts (javap against the 2026-09-19 server jar, vanilla `.ui` from release `Assets.zip`):**

| Fact | Evidence |
|---|---|
| `com.hypixel.hytale.server.core.ui.Value` has `static <T> Value<T> ref(String document, String name)`; `UICommandBuilder` has `<T> set(String, Value<T>)` | javap |
| `Value.ref(doc, name)` resolves `@name` declared in `doc` (path relative to `Common/UI/Custom/`) | `UIGalleryPage` → `Value.ref("Pages/UIGallery/CategoryButton.ui", "SelectedLabelStyle")`; `EntitySpawnPage` → `Value.ref("Common.ui", "DefaultTextButtonStyle")` |
| A partial `sendUpdate(cmd)` (clear=false) after `build()` keeps every binding registered in `build()` | `EntitySpawnPage.TabSwitch`, `UIGalleryPage` category switch |
| Plain `TextButton #Id { Text; Style; Anchor }` outside a `$C.@` template is valid | `Pages/Memories/Memory.ui:15-55` |
| Two sibling buttons, server shows one via `.Visible` | `Pages/Memories/Memory.ui` (`#ButtonSelected` / `#ButtonNotSelected`) |
| `Common.ui`: `@SmallSecondaryButtonLabelStyle` (:105), `@SmallSecondaryButtonDisabledLabelStyle` (:110), `@ColorDefault` (:4), `@ColorGoldHighlight` (:9), `@HeaderTabsStyle` uses `Background: (TexturePath: "../../Common/HeaderTabSelectedBackground.png", Border: 7)` (:704) | grep |
| Textures `Common/UI/Custom/Common/HeaderTabBackground@2x.png` (dark bordered box) and `HeaderTabSelectedBackground@2x.png` (light gradient, gold border) ship in `Assets.zip`, 98×68 @2x, 9-patch | unzip + sips |
| `LabelStyle(...@Other, TextColor: X)`, `PatchStyle(TexturePath:, Border:)`, `TextButtonStyle(Default:, Hovered:, Pressed:, Disabled:)` constructor forms | `Common.ui:105-113, 63, 178-183` |

**NOT VERIFIED (the in-game check in Task 5 decides):** that a `Value.ref` into the page's *own* document (rather than a row document) resolves; that the header-tab textures look right stretched to 120×30. The fallback for both is in Task 5 step 5 and needs no Java change beyond two string constants.

---

### Task 1: `StatusSnapshot.runLabel` wording (TDD)

**Files:**
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/ui/StatusSnapshot.java:71-76`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/ui/SproutwatchActionsTest.java:307,321`

- [ ] **Step 1: Change the two assertions to the new wording**

In `SproutwatchActionsTest.snapshotLabelsForThePage()`, line 307:

```java
        assertEquals("Listener stopped; press Start listener to start", stopped.runLabel());
```

and line 321:

```java
        assertEquals("Listener running (connected to #streamer); press Stop listener to stop", running.runLabel());
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SproutwatchActionsTest.snapshotLabelsForThePage' 2>&1 | tail -15`
Expected: FAILED, `expected: <Listener stopped; press Start listener to start> but was: <Listener stopped; tick to start>`.

- [ ] **Step 3: Update `runLabel` and its Javadoc**

Replace `StatusSnapshot.java` lines 71–76 with:

```java
    /** Caption beside the Listener tab's Start / Stop button. */
    public String runLabel() {
        return listenerRunning
            ? "Listener running (" + listenerState + "); press Stop listener to stop"
            : "Listener stopped; press Start listener to start";
    }
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test 2>&1 | tail -5 && ls build/test-results/test/*.xml | xargs grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print "tests:", s}'`
Expected: `BUILD SUCCESSFUL`, `tests: 120`.

- [ ] **Step 5: Checkpoint**

Run: `git add -A`

---

### Task 2: `SettingsTab` enum (TDD)

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/ui/SettingsTab.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/ui/SettingsTabTest.java`

The enum owns everything about the tab bar: the four tabs in display order, each tab's button and content-group selector, the style swap that marks the active tab, the event bindings, and parsing the tab name the client sends back. `apply` produces exactly the commands a tab switch needs, so the page can send them as a partial update.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/dev/hytalemodding/sproutwatch/ui/SettingsTabTest.java`:

```java
package dev.hytalemodding.sproutwatch.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsTabTest {

    @Test void tabsInDisplayOrderWithTheirSelectors() {
        assertArrayEquals(new SettingsTab[] {SettingsTab.STATUS, SettingsTab.VIEWERS, SettingsTab.LISTENER, SettingsTab.PEN},
            SettingsTab.values());
        assertEquals("#TabViewers", SettingsTab.VIEWERS.button);
        assertEquals("#ViewersTab", SettingsTab.VIEWERS.group);
        assertEquals("#TabStatus", SettingsTab.STATUS.button);
        assertEquals("#PenTab", SettingsTab.PEN.group);
    }

    @Test void parseAcceptsEnumNames() {
        assertEquals(SettingsTab.LISTENER, SettingsTab.parse("LISTENER", SettingsTab.STATUS));
        assertEquals(SettingsTab.PEN, SettingsTab.parse("PEN", SettingsTab.STATUS));
    }

    @Test void parseKeepsTheCurrentTabForNullOrUnknownNames() {
        assertEquals(SettingsTab.VIEWERS, SettingsTab.parse(null, SettingsTab.VIEWERS));
        assertEquals(SettingsTab.VIEWERS, SettingsTab.parse("Bogus", SettingsTab.VIEWERS));
        assertEquals(SettingsTab.VIEWERS, SettingsTab.parse("listener", SettingsTab.VIEWERS));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*SettingsTabTest' 2>&1 | tail -15`
Expected: compilation error, `cannot find symbol: class SettingsTab`.

- [ ] **Step 3: Write `SettingsTab`**

Create `src/main/java/dev/hytalemodding/sproutwatch/ui/SettingsTab.java`:

```java
package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * The settings page's tab bar, in display order: one TextButton and one content Group per tab.
 * The active tab is shown by swapping each button's Style between the two TextButtonStyles the
 * page document declares (@TabStyle / @TabSelectedStyle), the vanilla EntitySpawnPage /
 * UIGalleryPage pattern, so a switch is a partial update and never a rebuild.
 */
enum SettingsTab {
    STATUS("#TabStatus", "#StatusTab"),
    VIEWERS("#TabViewers", "#ViewersTab"),
    LISTENER("#TabListener", "#ListenerTab"),
    PEN("#TabPen", "#PenTab");

    /** Style refs into the page's own document; Value.ref(doc, name) resolves "@name" declared in doc. */
    private static final Value<?> STYLE = Value.ref(SproutwatchSettingsPage.DOCUMENT, "TabStyle");
    private static final Value<?> SELECTED_STYLE = Value.ref(SproutwatchSettingsPage.DOCUMENT, "TabSelectedStyle");

    final String button;
    final String group;

    SettingsTab(String button, String group) {
        this.button = button;
        this.group = group;
    }

    /** Every button's style and every group's visibility for {@code active}. Complete on its own: a partial update suffices. */
    static void apply(UICommandBuilder cmd, SettingsTab active) {
        for (SettingsTab t : values()) {
            cmd.set(t.button + ".Style", t == active ? SELECTED_STYLE : STYLE);
            cmd.set(t.group + ".Visible", t == active);
        }
    }

    /** Binds each button once per build; bindings survive partial updates. */
    static void bind(UIEventBuilder evt) {
        for (SettingsTab t : values()) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, t.button,
                new EventData().append(SettingsEvent.KEY_ACTION, "tab").append(SettingsEvent.KEY_TAB, t.name()));
        }
    }

    /** The tab the client named, or {@code current} when the name is missing or unknown. */
    static SettingsTab parse(String name, SettingsTab current) {
        if (name == null) return current;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return current;
        }
    }
}
```

If `cmd.set(t.button + ".Style", ...)` fails to compile with an ambiguity or capture error, declare the two constants as `Value<Object>` instead of `Value<?>` (`Value.ref` is `<T> Value<T>`, so `Value<Object> STYLE = Value.ref(...)` infers `T = Object`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*SettingsTabTest' 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Full suite**

Run: `./gradlew test 2>&1 | tail -5 && ls build/test-results/test/*.xml | xargs grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print "tests:", s}'`
Expected: `BUILD SUCCESSFUL`, `tests: 123`.

- [ ] **Step 6: Checkpoint**

Run: `git add -A`

---

### Task 3: The revised `SettingsPage.ui`

**Files:**
- Modify: `src/main/resources/Common/UI/Custom/Pages/Sproutwatch/SettingsPage.ui` (full replacement)

`ListEntryRow.ui` is unchanged. Every id the page sets or binds keeps its name; the only new ids are `#TabViewers`, `#ViewersTab`, `#StartButton`, `#StopButton`, and the two style declarations. The allow/ignore blocks move verbatim from `#StatusTab` into `#ViewersTab`. The four tab buttons are plain `TextButton`s (not `$C.@SmallSecondaryTextButton`) so the server can swap `.Style` between the two declared `TextButtonStyle`s.

- [ ] **Step 1: Replace the whole file with this content**

```
$C = "../../Common.ui";

// Tab bar styles: Hytale's own header-tab 9-patch textures (Common.ui @HeaderTabsStyle) on plain
// text buttons. The server sets each tab button's Style to one of these two on every tab switch
// (Value.ref into this document), so the active tab is the gold-bordered one.
@TabBackground = PatchStyle(TexturePath: "../../Common/HeaderTabBackground.png", Border: 7);
@TabSelectedBackground = PatchStyle(TexturePath: "../../Common/HeaderTabSelectedBackground.png", Border: 7);
@TabLabel = $C.@SmallSecondaryButtonLabelStyle;
@TabLabelHovered = (...$C.@SmallSecondaryButtonLabelStyle, TextColor: $C.@ColorDefault);
@TabLabelSelected = (...$C.@SmallSecondaryButtonLabelStyle, TextColor: $C.@ColorGoldHighlight);

@TabStyle = TextButtonStyle(
  Default: (Background: @TabBackground, LabelStyle: @TabLabel),
  Hovered: (Background: @TabBackground, LabelStyle: @TabLabelHovered),
  Pressed: (Background: @TabSelectedBackground, LabelStyle: @TabLabelHovered),
  Disabled: (Background: @TabBackground, LabelStyle: $C.@SmallSecondaryButtonDisabledLabelStyle)
);

@TabSelectedStyle = TextButtonStyle(
  Default: (Background: @TabSelectedBackground, LabelStyle: @TabLabelSelected),
  Hovered: (Background: @TabSelectedBackground, LabelStyle: @TabLabelSelected),
  Pressed: (Background: @TabSelectedBackground, LabelStyle: @TabLabelSelected),
  Disabled: (Background: @TabSelectedBackground, LabelStyle: @TabLabelSelected)
);

// Details value cells: the server sets #ListenerValue.Style to one of these (Value.ref) per listener state.
@ValuePlain = (...$C.@DefaultLabelStyle, FontSize: 14, VerticalAlignment: Center, Wrap: true);
@ValueGood = (...$C.@DefaultLabelStyle, FontSize: 14, VerticalAlignment: Center, Wrap: true, TextColor: #7ed957);
@ValueWarn = (...$C.@DefaultLabelStyle, FontSize: 14, VerticalAlignment: Center, Wrap: true, TextColor: $C.@ColorGoldHighlight);
@ValueBad = (...$C.@DefaultLabelStyle, FontSize: 14, VerticalAlignment: Center, Wrap: true, TextColor: #e05a4f);

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

      // Tab bar: the server sets every button's Style (selected / not) and every tab group's Visible.
      Group #TabBar {
        LayoutMode: Left;
        Anchor: (Height: 32, Bottom: 8);

        TextButton #TabConnect {
          Text: "Connect";
          Style: @TabSelectedStyle;
          Anchor: (Width: 120, Height: 30, Right: 4);
        }

        TextButton #TabPen {
          Text: "Pen";
          Style: @TabStyle;
          Anchor: (Width: 120, Height: 30, Right: 4);
        }

        TextButton #TabDetails {
          Text: "Details";
          Style: @TabStyle;
          Anchor: (Width: 120, Height: 30, Right: 4);
        }

        TextButton #TabViewers {
          Text: "Viewers";
          Style: @TabStyle;
          Anchor: (Width: 120, Height: 30, Right: 4);
        }

        TextButton #TabListener {
          Text: "Listener";
          Style: @TabStyle;
          Anchor: (Width: 120, Height: 30);
        }
      }

      $C.@PanelSeparatorFancy {
        @Anchor = (Bottom: 10);
      }

      // ---------------- Connect tab ----------------
      Group #ConnectTab {
        LayoutMode: TopScrolling;
        ScrollbarStyle: $C.@DefaultScrollbarStyle;
        FlexWeight: 1;
        Padding: (Full: 8);
        Visible: true;

        $C.@Subtitle {
          @Text = "Chat sources";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 6);

          $C.@CheckBox #TwitchCheck {
            Anchor: (Width: 26, Height: 26);
          }

          Label {
            Text: "Twitch chat";
            Anchor: (Left: 11);
            FlexWeight: 1;
            Style: (...$C.@DefaultLabelStyle, FontSize: 15, VerticalAlignment: Center, Wrap: true);
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@CheckBox #YouTubeCheck {
            Anchor: (Width: 26, Height: 26);
          }

          Label {
            Text: "YouTube chat";
            Anchor: (Left: 11);
            FlexWeight: 1;
            Style: (...$C.@DefaultLabelStyle, FontSize: 15, VerticalAlignment: Center, Wrap: true);
          }
        }

        $C.@Subtitle {
          @Text = "Twitch Channel";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@TextField #ChannelField {
            @Anchor = (Width: 300, Right: 8);
            PlaceholderText: "twitch channel name";
          }

          $C.@TextButton #SaveChannelButton {
            @Text = "Save";
            @Anchor = (Width: 120);
          }

          $C.@CancelTextButton #RemoveChannelButton {
            @Text = "Remove";
            @Anchor = (Width: 120);
            Visible: false;
          }
        }

        // YouTube: the key field is always sent empty; its placeholder shows only the masked saved key.
        $C.@Subtitle {
          @Text = "YouTube Channel Details";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 6);

          $C.@TextField #YouTubeHandleField {
            @Anchor = (Width: 300, Right: 8);
            PlaceholderText: "@yourhandle";
          }

          $C.@TextButton #SaveYouTubeHandleButton {
            @Text = "Save";
            @Anchor = (Width: 120);
          }

          $C.@CancelTextButton #RemoveYouTubeHandleButton {
            @Text = "Remove";
            @Anchor = (Width: 120);
            Visible: false;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 6);

          // Plain TextField (styled like $C.@TextField) so PasswordChar sits on the element exactly as in
          // the vanilla client's JoinViaCodePopup.ui: typed characters show as "*".
          TextField #YouTubeKeyField {
            Style: $C.@DefaultInputFieldStyle;
            PlaceholderStyle: $C.@DefaultInputFieldPlaceholderStyle;
            Background: $C.@InputBoxBackground;
            Anchor: (Width: 300, Right: 8, Height: 38);
            Padding: (Horizontal: 10);
            PlaceholderText: "API key";
            PasswordChar: "*";
          }

          $C.@TextButton #SaveYouTubeKeyButton {
            @Text = "Save";
            @Anchor = (Width: 120);
          }

          $C.@CancelTextButton #RemoveYouTubeKeyButton {
            @Text = "Remove";
            @Anchor = (Width: 120);
            Visible: false;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 6);

          $C.@TextField #YouTubeVideoField {
            @Anchor = (Width: 300, Right: 8);
            PlaceholderText: "stream link (optional; save empty to clear)";
          }

          $C.@TextButton #SaveYouTubeVideoButton {
            @Text = "Save";
            @Anchor = (Width: 120);
          }

          $C.@CancelTextButton #RemoveYouTubeVideoButton {
            @Text = "Remove";
            @Anchor = (Width: 120);
            Visible: false;
          }
        }

        Label {
          Text: "Free API key: Google Cloud Console > APIs and Services > enable YouTube Data API v3 > Credentials > API key.";
          Anchor: (Bottom: 12);
          Style: (...$C.@DefaultLabelStyle, FontSize: 13, Wrap: true);
        }

        $C.@Subtitle {
          @Text = "Max viewer sprouts in the pen (min 1)";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 12);

          $C.@NumberField #MaxField {
            @Anchor = (Width: 140, Right: 8);
            Format: (MaxDecimalPlaces: 0, Step: 1);
          }

          $C.@TextButton #SaveMaxButton {
            @Text = "Update";
            @Anchor = (Width: 120);
          }
        }
      }

      // ---------------- Details tab (live) ----------------
      Group #DetailsTab {
        LayoutMode: TopScrolling;
        ScrollbarStyle: $C.@DefaultScrollbarStyle;
        FlexWeight: 1;
        Padding: (Full: 8);
        Visible: false;

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);
          Background: (Color: #000000(0.15));

          Label {
            Text: "Channel";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #ChannelValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);

          Label {
            Text: "Listener";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #ListenerValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);
          Background: (Color: #000000(0.15));

          Label {
            Text: "Twitch feed";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #FeedValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);

          Label {
            Text: "YouTube quota";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #YouTubeQuotaValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);
          Background: (Color: #000000(0.15));

          Label {
            Text: "Persist";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #PersistValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);

          Label {
            Text: "Seen in chat";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #RosterValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);
          Background: (Color: #000000(0.15));

          Label {
            Text: "Queue";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #QueueValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);

          Label {
            Text: "Filter";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #FilterValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);
          Background: (Color: #000000(0.15));

          Label {
            Text: "Pen";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #PenCountValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }

        Group {
          LayoutMode: Left;
          Anchor: (Height: 28);
          Padding: (Horizontal: 6);

          Label {
            Text: "Retired";
            Anchor: (Width: 170);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14, TextColor: $C.@ColorDefaultLabel, VerticalAlignment: Center);
          }

          Label #RetiredValue {
            Text: "";
            FlexWeight: 1;
            Style: @ValuePlain;
          }
        }
      }

      // ---------------- Viewers tab ----------------
      Group #ViewersTab {
        LayoutMode: TopScrolling;
        ScrollbarStyle: $C.@DefaultScrollbarStyle;
        FlexWeight: 1;
        Padding: (Full: 8);
        Visible: false;

        $C.@Subtitle {
          @Text = "Who gets a sprout";
        }

        // Filter toggle: tab-styled twin buttons, the server marks the active one and shows only its list.
        Group {
          LayoutMode: Left;
          Anchor: (Height: 32, Bottom: 6);

          TextButton #FilterIgnoreButton {
            Text: "Ignore list";
            Style: @TabSelectedStyle;
            Anchor: (Width: 140, Height: 30, Right: 4);
          }

          TextButton #FilterAllowButton {
            Text: "Allow list";
            Style: @TabStyle;
            Anchor: (Width: 140, Height: 30);
          }
        }

        Label #FilterLabel {
          Text: "";
          Anchor: (Bottom: 12);
          Style: (...$C.@DefaultLabelStyle, FontSize: 14, Wrap: true);
        }

        Group #AllowSection {
          LayoutMode: Top;
          Visible: false;

          $C.@Subtitle {
            @Text = "Allow list";
          }

          Label #AllowEmptyLabel {
            Text: "Allow list is empty: nobody gets a sprout until you add someone.";
            Anchor: (Bottom: 4);
            Style: (...$C.@DefaultLabelStyle, FontSize: 14);
            Visible: true;
          }

          Group #AllowList {
            LayoutMode: TopScrolling;
            ScrollbarStyle: $C.@DefaultScrollbarStyle;
            Anchor: (Height: 220, Bottom: 6);
            Background: (Color: #000000(0.15));
            Padding: (Full: 6);
          }

          Group {
            LayoutMode: Left;
            Anchor: (Bottom: 4);

            $C.@TextField #AllowField {
              @Anchor = (Right: 8);
              FlexWeight: 1;
              PlaceholderText: "Twitch name, or yt:@handle / YouTube link";
            }

            $C.@SecondaryTextButton #AddAllowButton {
              @Text = "Add";
              @Anchor = (Width: 120);
            }
          }
        }

        Group #IgnoreSection {
          LayoutMode: Top;
          Visible: true;

          $C.@Subtitle {
            @Text = "Ignore list (your own channel is always ignored)";
          }

          Group #IgnoreList {
            LayoutMode: TopScrolling;
            ScrollbarStyle: $C.@DefaultScrollbarStyle;
            Anchor: (Height: 220, Bottom: 6);
            Background: (Color: #000000(0.15));
            Padding: (Full: 6);
          }

          Group {
            LayoutMode: Left;
            Anchor: (Bottom: 4);

            $C.@TextField #IgnoreField {
              @Anchor = (Right: 8);
              FlexWeight: 1;
              PlaceholderText: "Twitch name, or yt:@handle / YouTube link";
            }

            $C.@SecondaryTextButton #AddIgnoreButton {
              @Text = "Add";
              @Anchor = (Width: 120);
            }
          }
        }

        // Shared by both lists (only one section shows): input rules, then the last YouTube lookup's outcome.
        Label #ListInputHint {
          Text: "Plain names and @names are Twitch. For YouTube use yt:@handle, a youtube.com link or a UC... channel ID.";
          Anchor: (Bottom: 4);
          Style: (...$C.@DefaultLabelStyle, FontSize: 13, Wrap: true);
        }

        Label #LookupLabel {
          Text: "";
          Anchor: (Bottom: 12);
          Style: (...$C.@DefaultLabelStyle, FontSize: 14, Wrap: true);
        }
      }

      // ---------------- Listener tab ----------------
      Group #ListenerTab {
        LayoutMode: TopScrolling;
        ScrollbarStyle: $C.@DefaultScrollbarStyle;
        FlexWeight: 1;
        Padding: (Full: 8);
        Visible: false;

        $C.@Subtitle {
          @Text = "Start / stop";
        }

        // Twin buttons in one slot; the server shows exactly one (Start when stopped, Stop when running).
        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 16);

          $C.@TextButton #StartButton {
            @Text = "Start listener";
            @Anchor = (Width: 200);
          }

          $C.@SecondaryTextButton #ConnectingButton {
            @Text = "Connecting...";
            @Anchor = (Width: 200);
            Visible: false;
          }

          $C.@CancelTextButton #StopButton {
            @Text = "Stop listener";
            @Anchor = (Width: 200);
            Visible: false;
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
          @Text = "Auto-start on boot";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 16);

          $C.@CheckBox #AutoStartCheck {
            Anchor: (Width: 26, Height: 26);
          }

          Label #AutoStartCheckLabel {
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
        FlexWeight: 1;
        Padding: (Full: 8);
        Visible: false;

        $C.@Subtitle {
          @Text = "Creatures (changing clears the pen)";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 16);

          $C.@DropdownBox #CreatureDropdown {}
        }

        $C.@Subtitle {
          @Text = "Pen prefab (saved on change)";
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 16);

          $C.@DropdownBox #PrefabDropdown {}
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

        $C.@Subtitle {
          @Text = "Go live";
        }

        Label #BeginCaption {
          Text: "Close and bring viewers to the pen.";
          Anchor: (Bottom: 6);
          Style: (...$C.@DefaultLabelStyle, FontSize: 14, Wrap: true);
        }

        Group {
          LayoutMode: Left;
          Anchor: (Bottom: 6);

          $C.@TextButton #BeginButton {
            @Text = "Wrangle Viewers";
            @Anchor = (Width: 240);
          }
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

Notes on what changed versus the previous document, for the reviewer: (a) the five `@Tab*` declarations at the top; (b) four plain `TextButton`s in `#TabBar` instead of three `$C.@SmallSecondaryTextButton`s; (c) `#ViewersTab` is new and holds the allow/ignore blocks that used to sit at the end of `#StatusTab` — the two list groups grew from `Height: 96` to `Height: 160` because the tab now has the room; (d) in `#ListenerTab` the `$C.@CheckBox #RunCheck` became `#StartButton` + `#StopButton`; (e) everything else is byte-identical.

- [ ] **Step 2: Confirm the document is packed**

Run: `./gradlew jar && unzip -l build/libs/sproutwatch-0.1.0.jar | grep 'Pages/Sproutwatch/'`
Expected: `Common/UI/Custom/Pages/Sproutwatch/ListEntryRow.ui` and `Common/UI/Custom/Pages/Sproutwatch/SettingsPage.ui` (plus the directory entry).

- [ ] **Step 3: Checkpoint**

Run: `git add -A`

---

### Task 4: Page and event changes (engine)

**Files:**
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/ui/SettingsEvent.java`
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/ui/SproutwatchSettingsPage.java`

Engine-bound: compile here, behaviour checked in Task 5.

- [ ] **Step 1: Drop the `@Run` key from `SettingsEvent`**

Remove these three lines from `SettingsEvent.java`:

```java
    public static final String KEY_RUN = "@Run";
```

```java
        .append(new KeyedCodec<>(KEY_RUN, Codec.BOOLEAN, false), (e, v) -> e.run = v, e -> e.run).add()
```

```java
    public Boolean run;
```

The remaining nine keys, their codec lines and fields are unchanged.

- [ ] **Step 2: Rewrite `SproutwatchSettingsPage`**

Replace the whole file with:

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
 * The /sproutwatch settings page: Status, Viewers, Listener and Pen tabs (see SettingsTab), every
 * button routed through SproutwatchActions, result shown on the message line, page rebuilt after
 * each action except a tab switch, which is a partial update so typed text survives it. Registers
 * itself in OpenPages on build so PenTicker's after-tick hook can push the status labels live via
 * refreshStatus(); removed on dismiss (here) and disconnect (plugin). The push is change-gated:
 * every sendUpdate bumps the engine's per-player unacknowledged-update counter and client data
 * events (button clicks) are dropped while it is non-zero, so an unconditional push every tick
 * would silently eat clicks that cross it.
 */
public final class SproutwatchSettingsPage extends InteractiveCustomUIPage<SettingsEvent> implements OpenPages.Page {

    static final String DOCUMENT = "Pages/Sproutwatch/SettingsPage.ui";
    static final String LIST_ROW = "Pages/Sproutwatch/ListEntryRow.ui";

    /** Selectors of the labels refreshStatus() pushes, in the order statusLabels() emits them. */
    private static final List<String> STATUS_SELECTORS = List.of(
        "#ListenerLabel.Text", "#ChannelLabel.Text", "#FeedLabel.Text", "#RosterLabel.Text",
        "#QueueLabel.Text", "#PenCountLabel.Text", "#RetiredLabel.Text", "#PersistLabel.Text",
        "#PenPlacedLabel.Text", "#ChairLabel.Text", "#RunLabel.Text", "#PenInfoLabel.Text");

    private final SproutwatchActions actions;
    private final OpenPages openPages;
    private final Logger logger;
    // Only ever touched on the player's world thread (refreshStatus never reads them), but a player
    // can change worlds while the page is open, so successive events may arrive on different threads.
    private volatile SettingsTab tab = SettingsTab.STATUS;
    private volatile String message = "";
    // Written by build() (player thread) and refreshStatus() (pen thread); the benign race costs one extra push.
    private volatile List<String> lastSentLabels = List.of();
    // A refresh queued on the pen thread can land after the player dismissed the page.
    private volatile boolean dismissed;

    public SproutwatchSettingsPage(PlayerRef playerRef, SproutwatchActions actions, OpenPages openPages, Logger logger) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, SettingsEvent.CODEC);
        this.actions = actions;
        this.openPages = openPages;
        this.logger = logger;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder evt, @Nonnull Store<EntityStore> store) {
        StatusSnapshot s = actions.snapshot();
        SproutwatchConfig cfg = actions.config();
        cmd.append(DOCUMENT);
        SettingsTab.bind(evt);
        SettingsTab.apply(cmd, tab);
        List<String> labels = statusLabels(s);
        setStatusLabels(cmd, labels);
        lastSentLabels = labels;
        buildStatusTab(cmd, evt, cfg);
        buildViewersTab(cmd, evt, cfg);
        buildListenerTab(cmd, evt, s);
        buildPenTab(cmd, evt, s);
        cmd.set("#MessageLabel.Text", message);
        // Last, so a build that throws (e.g. a bad .ui) never leaves a phantom entry refreshed every tick.
        openPages.register(playerRef.getUuid(), this);
    }

    /** The labels refreshStatus() pushes, in STATUS_SELECTORS order. Never a field value. */
    private static List<String> statusLabels(StatusSnapshot s) {
        return List.of(
            s.listenerLine(), s.channelLine(), s.feedLine(), s.rosterLine(),
            s.queueLine(), s.penCountLine(), s.retiredLine(), s.persistLine(),
            s.penPlacedLine(), s.chairLine(), s.runLabel(), s.penInfoLine());
    }

    private static void setStatusLabels(UICommandBuilder cmd, List<String> labels) {
        for (int i = 0; i < STATUS_SELECTORS.size(); i++) cmd.set(STATUS_SELECTORS.get(i), labels.get(i));
    }

    private void buildStatusTab(UICommandBuilder cmd, UIEventBuilder evt, SproutwatchConfig cfg) {
        cmd.set("#ChannelField.Value", cfg.getTwitchChannel());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#SaveChannelButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "saveChannel").append(SettingsEvent.KEY_CHANNEL, "#ChannelField.Value"));
    }

    private void buildViewersTab(UICommandBuilder cmd, UIEventBuilder evt, SproutwatchConfig cfg) {
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
        // Twin buttons in one slot: show Start while stopped, Stop while running.
        cmd.set("#StartButton.Visible", !s.listenerRunning());
        cmd.set("#StopButton.Visible", s.listenerRunning());
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#StartButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "startListener"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#StopButton",
            new EventData().append(SettingsEvent.KEY_ACTION, "stopListener"));
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
        // Effective name: a stale config value that is no longer in the catalog would otherwise leave
        // the dropdown unselected, so fall back to the catalog default (what PenPlacer will use too).
        String selected = PenPrefabCatalog.contains(s.prefabName()) ? s.prefabName() : PenPrefabCatalog.DEFAULT;
        cmd.set("#PrefabDropdown.Value", selected);
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
        if (action.equals("tab")) {
            switchTab(e.tab);
            return;
        }
        try {
            message = switch (action) {
                case "saveChannel" -> actions.setChannel(e.channel);
                case "addAllow" -> actions.addAllow(e.allowInput);
                case "removeAllow" -> actions.removeAllow(e.login);
                case "addIgnore" -> actions.addIgnore(e.ignoreInput);
                case "removeIgnore" -> actions.removeIgnore(e.login);
                case "startListener" -> actions.startListener();
                case "stopListener" -> actions.stopListener();
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
        try {
            rebuild();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page rebuild failed", ex);
        }
    }

    /**
     * A tab switch is a partial update (styles + visibility only): no rebuild, so typed text and the
     * message line survive it. Server state follows the click even if the push fails (the next
     * rebuild resends it). Always pushes, even for the already-active tab: the button's binding locks
     * the client UI until some update arrives.
     */
    private void switchTab(String name) {
        tab = SettingsTab.parse(name, tab);
        try {
            UICommandBuilder cmd = new UICommandBuilder();
            SettingsTab.apply(cmd, tab);
            sendUpdate(cmd);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page tab switch failed", ex);
        }
    }

    /**
     * Called on the pen world thread after every tick and from statusChanged() after Place/Clear; the
     * two may run concurrently for the same page, so this touches no page state beyond
     * lastSentLabels (a fresh snapshot into a fresh builder each call). Pushes only when the labels
     * differ from the last set sent: each sendUpdate opens a window in which the engine drops the
     * player's clicks (see the class comment). sendUpdate marshals onto the player's world thread.
     */
    @Override
    public boolean refreshStatus() {
        if (dismissed) return false;
        if (!playerRef.isValid() || playerRef.getReference() == null) return false;
        try {
            List<String> labels = statusLabels(actions.snapshot());
            if (labels.equals(lastSentLabels)) return true;
            UICommandBuilder cmd = new UICommandBuilder();
            setStatusLabels(cmd, labels);
            sendUpdate(cmd);
            lastSentLabels = labels;
            return true;
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Sproutwatch settings page refresh failed; dropping the page", ex);
            return false;
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        dismissed = true; // before forget: a refresh already past refreshAll's iteration must still bail out
        openPages.forget(playerRef.getUuid(), this);
    }
}
```

What changed versus the previous file, for the reviewer: the inner `Tab` enum, `buildTabs` and `tabButton` are gone (now `SettingsTab`); `build()` calls `SettingsTab.bind` + `SettingsTab.apply` and the new `buildViewersTab`; `buildStatusTab` keeps only the channel field; `buildListenerTab` shows one of `#StartButton`/`#StopButton` and binds both; `handleDataEvent` routes `tab` to `switchTab` before the switch and has `startListener`/`stopListener` cases instead of `toggleListener`; `selectTab` is replaced by `SettingsTab.parse`. `refreshStatus`, `onDismiss`, `statusLabels`, `setStatusLabels`, `buildPenTab` are byte-identical.

- [ ] **Step 3: Compile and count lines**

Run: `./gradlew compileJava 2>&1 | tail -5 && wc -l src/main/java/dev/hytalemodding/sproutwatch/ui/SproutwatchSettingsPage.java src/main/java/dev/hytalemodding/sproutwatch/ui/SettingsTab.java`
Expected: `BUILD SUCCESSFUL`; the page is under 260 lines, the enum under 70.

- [ ] **Step 4: Full suite**

Run: `./gradlew test 2>&1 | tail -5 && ls build/test-results/test/*.xml | xargs grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print "tests:", s}'`
Expected: `BUILD SUCCESSFUL`, `tests: 123`.

- [ ] **Step 5: Grep for leftovers**

Run: `grep -rn "RunCheck\|KEY_RUN\|toggleListener\|Tab.valueOf\|\.Disabled" src/main/java src/main/resources/Common/UI/Custom/Pages/Sproutwatch/`
Expected: no output.

- [ ] **Step 6: Checkpoint**

Run: `git add -A`

---

### Task 5: Deploy, headless boot, in-game verification

**Files:** none new; possibly `SettingsPage.ui` and `SettingsTab.java` if the fallback in step 5 is needed.

- [ ] **Step 1: Deploy**

Run: `./deploy.sh`
Expected: `✓ Deployed sproutwatch-0.1.0.jar to .../UserData/Mods`. Mertie copies the jar to the other PC if testing there.

- [ ] **Step 2: Headless boot**

macOS has no `timeout`; run the server as a background job capped at ~50 s, then grep. The cwd must NOT contain a `mods/` directory.

```bash
S=/private/tmp/claude-501/-Users-mertie/34742382-0118-4ce9-a2d2-fe13147ad5fb/scratchpad/boot && rm -rf "$S" && mkdir -p "$S/mods" "$S/early" "$S/cache" "$S/universe" "$S/cwd"
cp ~/Developer/hytale/sproutwatch/build/libs/sproutwatch-0.1.0.jar "$S/mods/"
JAVA="$HOME/Library/Application Support/Hytale/install/release/package/jre/latest/Contents/Home/bin/java"
G="$HOME/Library/Application Support/Hytale/install/release/package/game/latest"
cd "$S/cwd" && "$JAVA" -Xms512M -jar "$G/Server/HytaleServer.jar" --assets="$G/Assets.zip" --mods="$S/mods" \
  --early-plugins="$S/early" --prefab-cache="$S/cache" --bind localhost:57999 --auth-mode=offline \
  --universe="$S/universe" --transport QUICHE > "$S/boot.log" 2>&1
# (background job, timeout 50000 ms; afterwards:)
sed 's/\x1b\[[0-9;]*m//g' "$S/boot.log" | grep -iE 'sproutwatch|FAIL:|validation failed|Unknown JSON attribute|duplicate plugin|Exception' | head -40
grep -E 'PenPrefab' "$S/cwd/mods/Mertie_sproutwatch/Sproutwatch_config.json"
```

Expected: `Enabled plugin Mertie:sproutwatch`, `Loaded pack: Mertie:sproutwatch`, no `FAIL:` / `Unknown JSON attribute` / exception lines mentioning sproutwatch, config contains `"PenPrefab": "default"`. The `.ui` is client-parsed; step 4 is what proves it.

- [ ] **Step 3: Commands unchanged**

`/sproutwatch status`, `/sproutwatch start`, `/sproutwatch stop`, `/sproutwatch allow list`: replies read exactly as before.

- [ ] **Step 4: In-game checklist (Mertie, Testr World, admin)**

Report each line pass/fail; on a failure include the server log lines and, for a blank or broken page, the client log. A page that stays blank means the `.ui` failed to parse: the first suspects are the five `@Tab*` declarations at the top of `SettingsPage.ui` (compare against `Common.ui:63,105-113,178-183` in Assets.zip). Known engine race, not a bug to chase: a click that lands in the round trip right after a status push can be silently lost; click again.

1. `/sproutwatch settings` from the server console → `Run this in game as a player.`
2. In game: page opens on the Status tab. **Tab bar: four buttons Status | Viewers | Listener | Pen; Status has the gold-bordered light background, the other three the dark bordered background; hovering a dark one brightens its text.** The ten status lines match `/sproutwatch status`; the channel field is prefilled; the allow/ignore lists are NOT on this tab. Message line at the bottom fully visible.
3. **Type `abc` into the channel field without saving, then click Viewers, then Status: the pane switches, the clicked tab takes the gold style and the previous one returns to dark, and the channel field still shows `abc`** (partial update, not a rebuild). The message line keeps its text across switches.
4. Status tab, channel: type a channel with capitals and `#`, Save → `Channel set to #<lowercase>...`, field shows the normalised name, `Sproutwatch_config.json` has it.
5. **Viewers tab:** the "empty" allow line shows; ignore list shows nightbot/streamelements/streamlabs with Remove and the channel row without. Add `Alice` → row `alice` with Remove, the "empty" line disappears, message `Added alice to the allow list.`; Remove → row gone, `Removed alice from the allow list.`; blank Add → `Invalid login.`. Page reopens on the Viewers tab after each action (rebuild keeps the current tab).
6. Viewers tab: Add `spammer` to ignore → row appears; Remove it; the channel row never shows Remove.
7. **Listener tab with no pen placed: a green `Start listener` button (no `Stop`); caption `Listener stopped; press Start listener to start`. Press it → message `No pen placed yet. Stand where you want it and run /sproutwatch place.`, the button is still `Start listener`.**
8. Pen tab: dropdown lists `default` selected; Place → `Placing the pen...`, pen appears, chat gets `Pen placed (...)`, within one tick the Pen and info lines update on their own.
9. Listener tab: Save interval `3` → `Tick interval is now 5s.`, field shows 5 (if the server log shows a codec/decode exception, switch `@Interval` to `Codec.DOUBLE` / `Double interval` in `SettingsEvent` and `(int) Math.round(e.interval)` in the page). Save `30` → `Tick interval is now 30s.`.
10. **Press `Start listener` → message `Sproutwatch watching #<channel>; one sprout every 30s.`, the button becomes a red `Stop listener`, caption `Listener running (connected to #<channel>); press Stop listener to stop`**, the Status tab's Listener line updates within a tick.
11. Live status: page open on Status, `/sproutwatch test bob now` from console: Roster and Pen lines change on their own within one tick; a channel you were typing is NOT reset.
12. Persist checkbox: untick → `Persist is now off: ...`, caption changes, `/sproutwatch status` agrees; tick back.
13. **Press `Stop listener` → `Sproutwatch stopped.`, button becomes green `Start listener`**, status lines update.
14. Pen tab, Clear → `Clearing the pen...`, sprouts vanish, Pen count drops within a tick.
15. Dismiss and reopen → Status tab, empty message line, no server warning.
16. Disconnect with the page open, reconnect → no repeating `refresh failed` warnings.
17. `/sproutwatch settings` twice without closing → one page, no error.
18. Feed line `on` while running, `n/a (listener stopped)` after.
19. Open the page, dismiss right as a tick fires, reopen → buttons still respond.

- [ ] **Step 5: Fallback if the tab styling is wrong (only if item 2 or 3 fails on looks)**

If the page renders but the tab buttons are blank/black boxes or the active tab is not distinguishable, the header-tab 9-patch does not suit a 30 px button or the self-document `Value.ref` did not resolve. Switch to the vanilla `EntitySpawnPage` look without touching the page: in `SettingsTab.java` change the two constants to

```java
    private static final Value<?> STYLE = Value.ref("Common.ui", "SecondaryTextButtonStyle");
    private static final Value<?> SELECTED_STYLE = Value.ref("Common.ui", "DefaultTextButtonStyle");
```

and in `SettingsPage.ui` change the four tab buttons' initial `Style:` to `$C.@SecondaryTextButtonStyle` (`#TabStatus`: `$C.@DefaultTextButtonStyle`) and delete the five `@Tab*` declarations. Rebuild, redeploy, re-run items 2–3. Record which variant shipped in step 6.

- [ ] **Step 6: Record the results**

Add a `## Verification 2026-xx-xx` section at the bottom of this plan with the checklist results, whether step 5's fallback was needed, and any fixes applied.

- [ ] **Step 7: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: `BUILD SUCCESSFUL`, 123 tests.

---

### Task 6: Review and commit handoff

- [ ] **Step 1: Full build**

Run: `./gradlew clean build 2>&1 | tail -5 && ls build/test-results/test/*.xml | xargs grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print "tests:", s}'`
Expected: `BUILD SUCCESSFUL`, `tests: 150` (see Addendum). Report the exact number if it differs and say why.

- [ ] **Step 2: Diff summary for Mertie**

Run: `git status --short | grep -v '^A ' ; git diff --cached --stat | tail -3`
Report the file list (everything is staged on the unborn `dev` branch; no history yet).

- [ ] **Step 3: Hand off**

Tell Mertie: revisions done and verified in-game per Task 5 step 6; jar deployed; nothing committed. Ask for the commit go-ahead — do not commit or push without it.

---

### Addendum 2026-10-01 (during Task 5 verification, Mertie-directed)

Applied directly while the in-game checklist ran; all staged, nothing committed.

1. **Texture paths are document-relative.** The header-tab textures rendered as white boxes with a red X until the two `PatchStyle` paths became `../../Common/HeaderTab*.png` (vanilla precedent: `Pages/PrefabEditorSaveSettings.ui:303` uses `../Common/ProgressBar.png`). The Task 3 block above has the corrected paths. The self-document `Value.ref` DID resolve (active tab styled correctly after the fix).
2. **Divider + padding** (Mertie): `$C.@PanelSeparatorFancy { @Anchor = (Bottom: 10); }` under the tab bar; the four panes use `FlexWeight: 1; Padding: (Full: 8);` instead of `Anchor: (Height: 520)` (vanilla `TriggerVolumeInspectorPage.ui:182-187` form). Block above updated.
3. **Placeholders** (Mertie): "Add viewer name to allow" / "Add viewer name to ignore".
4. **Camera defaults** (Mertie): 15 / 10 / 45 in `SproutwatchConfig` (initialisers + non-finite fallbacks), `Sproutwatch_config.json`, and `SproutwatchConfigTest`. Existing worlds keep their stored values; run `/sproutwatch camera 15 10 45` once.
5. **Auto-start on boot checkbox** (Mertie, after a world relaunch left the listener stopped — `AutoStartOnBoot` defaults to false by the original spec). Listener tab gains a third row (subtitle "Auto-start on boot", `$C.@CheckBox #AutoStartCheck`, `#AutoStartCheckLabel`) between Persist and the interval row; `SproutwatchConfig.setAutoStartOnBoot`; `StatusSnapshot.autoStart` + `autoStartLabel()` ("Auto-start on: the listener reconnects when the world loads" / "Auto-start off: press Start listener after each launch"); `SproutwatchActions.setAutoStart(boolean)` (saves config, replies "Auto-start on boot is now on: …" / "…off: …"); `SettingsEvent.KEY_AUTO_START = "@AutoStart"`; page binds `ValueChanged` on `#AutoStartCheck` → `setAutoStart`. Tests: `autoStartTogglesConfigAndSaves` + two label assertions → **124 tests**. Page is 259 lines.

Checklist addition (Task 5 step 4): **12b.** Listener tab, tick Auto-start → message `Auto-start on boot is now on: …`, caption changes, config has `"AutoStartOnBoot": true`; relaunch the world → server log shows `Sproutwatch watching Twitch channel #…` without pressing Start.

Task 6 expected count is now **124**.

## Verification 2026-10-01 (Mertie, test world 2, channel #ironmouse)

- Items 2–3 PASS: four header-tab-styled buttons, active tab gold-bordered, text typed in the channel field survives a tab switch (partial update confirmed; self-document `Value.ref` resolves — fallback not needed). First attempt showed white boxes with a red X → Addendum item 1 (document-relative texture paths), fixed and redeployed.
- Start / Stop PASS: single button flips green `Start listener` ↔ red `Stop listener`; real viewers spawned one per tick (server log 00:25–00:31, 14 sprouts, 60 s then 10 s interval).
- 12b PASS: Auto-start on boot ticked → listener running after a world relaunch without pressing Start.
- Divider, pane padding, placeholder wording, camera defaults 15/10/45: applied on request, confirmed visually.
- Items 4–11, 13–19 were exercised during the same sessions (channel save, Viewers add/remove, interval change, Place, Clear 14 sprouts, dismiss/reopen) with no server warnings in the logs; not individually reported line by line.
- Codec item 9: no decode exception in the logs with `Codec.INTEGER` for `@Interval`; the `Codec.DOUBLE` switch was not needed.
6. **Honest `test <login> now` reply** (Mertie, after the Task 5 pass). The spawner fails quietly (FINE) when the pen has no validated free spot, so the forced tick now reports back: `PenTicker.tickOnWorldThread` returns a pure `pen/TickOutcome(Optional<String> candidate, boolean spawned)`, `tickNow(Consumer<TickOutcome>)` hands it to the caller on the world thread, and `applyTest` sends `outcome.testReply(login)` to the sender as a second chat line (console: INFO log) after the immediate "added as a test viewer; ticking now." Wording covers: joined / no free spot (retry hint) / someone ahead in line (spawned or not) / nothing to spawn. `TickOutcomeTest` (4 tests) → **128 tests**. Task 6 expected count is now 128.
7. **Max sprouts field on the Status tab** (Mertie). Row "Max viewer sprouts in the pen (min 1)" after the channel row: `$C.@NumberField #MaxField` (same shape as `#IntervalField`) + `$C.@TextButton #SaveMaxButton`; `SettingsEvent.KEY_MAX = "@Max"` (`Codec.INTEGER`, field `max`); page sets `#MaxField.Value` from `getMaxSprouts()` and binds `Activating` → `saveMax` (null → "Enter a number of sprouts (min 1)."); `SproutwatchActions.setMaxSprouts(int)` saves and replies `Max sprouts is now N.` (config clamps to ≥ 1; lowering below the pen count despawns nothing by itself — the reconciler swaps one per tick). Test `maxSproutsClampsToAtLeastOneAndSaves` → **129 tests**. Page is now 263 lines, 3 over the 260 guideline: the next page addition should move the per-tab builders into their own class rather than grow it further. Task 6 expected count is now 129.

Checklist addition: **4b.** Status tab, Max: save `12` → `Max sprouts is now 12.`, the Pen line reads `…/12 sprouts`, config has `"MaxSprouts": 12`; save `0` → `Max sprouts is now 1.`
8. **Lowering Max sprouts trims the pen at once** (Mertie: "if I change max I need to clear and start over"; chose "trim the surplus immediately"). `PenReconciler.trim(pen lastSeen, roster firstSeen, cap)` (pure, 4 tests) picks the surplus: absent viewers' sprouts first (longest-gone first), then present viewers newest-arrival first, ties by login. `PenClearer.despawn(world, registry, logins, logger)` removes just those (registry + entity; the ticker's loop, extracted). `SproutwatchActions.setMaxSprouts` saves, then if the pen exceeds the cap queues the despawn on the pen world thread and `statusChanged()`; replies `Max sprouts is now N; removing K extra sprout(s)...` (NOT_LOADED: forgets the tracked entries; REJECTED: "the extra sprouts go on the next tick"). Test `loweringMaxBelowThePenCountTrimsTheSurplusOnThePenWorld`. → **134 tests**. Task 6 expected count is now 134.

Checklist 4b becomes: with 5 sprouts in the pen save Max `3` → `Max sprouts is now 3; removing 2 extra sprout(s)...`, two sprouts vanish immediately (absent viewers' first), Pen line reads `3/3`.
9. **Test viewers are "guests"** (Mertie: "new people aren't being added" after test viewers). Root cause: `/sproutwatch test` inserted a plain roster entry with firstSeen 0 that nothing ever removed (Twitch never PARTs a fake login), so under persist the test viewers counted as present forever (never evicted at the cap) and after Clear they respawned first. Fix: `ChatRoster` tracks `guests` (`addGuest/removeGuest/guests/forgetGuests`; `clear()` and PART drop the flag); `PenReconciler.reconcile(..., persist, guests)` replaces a guest in the pen BEFORE any longest-gone absent viewer when a real viewer is waiting (never to make room for another guest); `trim(..., guests)` drops guests first; `PenTicker` passes `roster.guests()`; `SproutwatchActions.clear()` calls `forgetGuests()` first; new `SproutwatchActions.removeGuest(login)` (roster drop + immediate despawn on the pen world); commands `test list` and `test <login> remove`. Persist off unchanged (guests stay present so `test` remains useful). Tests: 2 roster, 3 reconciler, 2 actions → **141 tests**. Task 6 expected count is now 141.

Checklist: **20.** With Max 2, `test a now`, `test b now` (both spawn), then a real viewer joins chat → within one tick `a` is replaced by the real viewer (log: `a replaced to make room`). `test list` shows `b`. `test b remove` → sprout gone at once. Clear with guests present → `test list` says none, nothing respawns.

10. **Connect / Details / Begin** (Mertie). Tabs are now `Connect | Details | Viewers | Listener | Pen` (`SettingsTab.CONNECT/DETAILS` replace `STATUS`). Connect = channel row, max row, a "Go live" `$C.@TextButton #BeginButton` + caption. Begin → `SproutwatchActions.begin()` (returns the start error, or empty when started / already running) → page calls `CustomUIPage.close()` (`PageManager.setPage(None)`, verified by javap to fire `onDismiss` and reset the ack counter); on error the page stays open with the message. Details = the ten live lines as a two-column table (`StatusSnapshot.DETAIL_NAMES` static in the `.ui`, values via `detailValues()` = each line without its "Name: " prefix), 28 px rows, 170 px dim name column (`$C.@ColorDefaultLabel`), alternating `#000000(0.15)` row backgrounds; `STATUS_SELECTORS` now target `#…Value.Text`, live refresh unchanged. Per-tab builders moved out of the page into package-private `SettingsPanes` (connect/viewers/listener/pen); page is 193 lines, panes 115. Tests: `detailValues` assertions, `beginStartsTheListenerOrReportsWhyNot`, tab enum test updated → **142 tests**. Task 6 expected count is now 142.

Checklist: **21.** Page opens on Connect (gold tab), Details shows the table with live values, Begin with no pen → error on the message line and the page stays open; with a pen → page closes and the log shows `watching Twitch channel`; Begin while already running → page just closes, no reconnect.

11. **Viewer filter mode + Viewers-tab toggle** (Mertie: "a toggle for using the allow or ignore list, then only show one"). Semantics changed from "allow list restricts when non-empty" to an explicit mode: config `ViewerFilter` = `"ignore"` (default: everyone except IgnoreUsers) | `"allow"` (only AllowUsers; IgnoreUsers still wins; empty allow list = nobody). `SproutwatchConfig.getViewerFilter/isAllowMode/setAllowMode/passesFilter` (tested); `PenTicker` and `applyTest` use the mode; `/sproutwatch filter [allow|ignore]`; `/sproutwatch status` prints `Filter: …` instead of `Allow list: …`; `StatusSnapshot.filterLine/filterLabel`, Details table gains a `Filter` row (11 rows); `SproutwatchActions.setFilter(boolean)`; `SettingsEvent.KEY_FILTER = "Filter"` (literal); page action `setFilter`. Viewers tab: tab-styled twin buttons `#FilterIgnoreButton`/`#FilterAllowButton` (server swaps `.Style` via the now package-visible `SettingsTab.STYLE/SELECTED_STYLE`), caption `#FilterLabel`, and `#IgnoreSection` / `#AllowSection` (`LayoutMode: Top`, `.Visible` toggled; lists grew to 220 px). Shipped JSON has `"ViewerFilter": "ignore"` (28 keys). → **144 tests**. Task 6 expected count is now 144.

Checklist: **22.** Viewers tab opens on "Ignore list" (gold) showing only the ignore section; press "Allow list" → message `Filter is now the allow list: … (the list is empty, so nobody until you add someone).`, the allow section replaces the ignore one, Details' Filter row updates; `/sproutwatch status` agrees; with allow mode and an empty list a real viewer does NOT spawn; add them → they spawn next tick.

12. **Details row order + Listener colour** (Mertie), plus a bug fix. BUG: item 11 added a `Filter` value to `detailValues()` (11) without an 11th `.ui` cell or selector (10 + 2 captions), so on that build every Details value after Queue landed one row off. Fix: `StatusSnapshot.DETAIL_IDS` sits next to `DETAIL_NAMES` (same length, asserted in tests) and the page derives `STATUS_SELECTORS` from it. New order: Channel, Listener, Twitch feed, Persist, In chat, Queue, Filter, Pen, Retired, Pen placed, Chair. Listener cell colour: `StatusSnapshot.toneFor(state)` → `ValueGood` (green `#7ed957`, "connected…"), `ValueWarn` (gold, "connecting"), `ValueBad` (red `#e05a4f`, "reconnecting" = connection lost), `ValuePlain` (stopped); the page pushes `#ListenerValue.Style = Value.ref(DOCUMENT, tone)` with every label push (tone travels as the last entry of the compared label list, so the change gate covers it). `.ui` declares the four `@Value*` label styles; all 11 cells start `Style: @ValuePlain`. → **144 tests**.

Checklist: **23.** Details: Channel is the first row, Listener second; with the listener running the Listener cell is green; press Stop → plain; Start → briefly gold "connecting" then green; kill the network → red "reconnecting". Every other row shows its own value (Filter row says `ignore list (…)`, Pen row the counts).

13. **"In chat" → "Seen in chat"** (Mertie saw 27 vs 10,000+ viewers). The roster is every login seen via IRC (membership feed + chatters); Twitch sends JOIN/PART/NAMES only for channels under ~1,000 chatters, so for large channels it is just the people who typed since Start. The row and `/sproutwatch status` now read `Seen in chat: N (chatters since Start; Twitch sends the full member list only for channels under 1,000)`. No behaviour change; 144 tests.
14. **Quiet timeout, queue-only** (Mertie: "only have a timeout if people are in queue"). Big channels get no JOIN/PART from Twitch, so chatters never "leave" and the pen would never rotate. New config `QuietSeconds` (default 600; 0 disables): `ChatRoster` tracks `lastActive` (chat message time; join/names set it once; guests on add); `PenReconciler.reconcile(..., guests, lastActive, quietMillis)`: when the pen is full and the candidate came from the `!sprout` queue, and no guest / absent-viewer sprout is available, the present non-guest viewer quiet the longest is replaced if quiet ≥ QuietSeconds. Plain first-seen candidates never evict anyone. Works with persist on and off. The Queue row / status line now say "…; when the pen is full a queued viewer replaces anyone quiet for 10m+". Tests: 1 roster, 5 reconciler, config → **150 tests**. Task 6 expected count is now 150.
15. **Bare `/sproutwatch` opens the page** (Mertie). Root command now extends `AbstractCommand` (not `AbstractCommandCollection`, whose final `executeAsync` only prints usage; subcommand dispatch lives in `AbstractCommand`, verified by javap) with `execute` → `openSettings`; `settings` stays as an alias. Console reply unchanged ("Run this in game as a player.").

Checklist: **24.** `/sproutwatch` alone opens the page; `/sproutwatch settings` still works; `/sproutwatch status` still works (subcommand dispatch intact). **25.** Max 1, viewer A in the pen quiet > 10 min, viewer B types `!sprout` → within a tick A is replaced by B (log: `A replaced to make room`); with A chatting recently, B waits.
16. **Layout polish** (Mertie): Begin is a 200 px button left-aligned in a `LayoutMode: Left` row (was full width); channel field fixed 300 px (was `FlexWeight: 1`); prefab dropdown wrapped in a Left row so it sits left instead of centred (the template has a fixed 330 width). Listener caption split into two labels: `#RunLabel` "Listener running (connected to #…)" / "Listener stopped" and `#RunHint` "Press Stop/Start listener to stop/start" (dim, next line); `StatusSnapshot.runHint()`, pushed live with the other labels. 150 tests.
17. **Begin wording** (Mertie): caption "Close and bring viewers to the pen." now sits above the button; button text "Wrangle Viewers" (240 px wide to fit the uppercase label). Action id stays `begin`.
18. **Seen in chat wording** (Mertie): value shortened to `N (chatters since Start)` on the page and in `/sproutwatch status`; the code comment keeps the Twitch 1,000-chatter explanation.
19. **Run hint removed** (Mertie): `#RunHint` / `StatusSnapshot.runHint()` dropped; the Listener tab caption is just "Listener running (connected to #…)" / "Listener stopped" beside the button.
20. **Pen is the second tab; Wrangle gated on a pen** (Mertie). Order: Connect | Pen | Details | Viewers | Listener (enum + .ui buttons). Connect: `#BeginCaption` = `StatusSnapshot.beginCaption()` ("Close and bring viewers to the pen." / "Place a pen to wrangle viewers"), `#BeginButton.Disabled = !penSet` (the template's Disabled state); both travel with the live label push (caption as a selector, enabled flag as a trailing entry like the listener tone), so placing a pen while the page is open enables the button within the push. 150 tests.
21. **Crash fix + guard.** Item 20's tab-bar rewrite left a stray `}` after `#TabBar` (the end-of-block search matched an indented button brace), so the client refused the page at join: "Failed to parse file Pages/Sproutwatch/SettingsPage.ui (628:1) – Expected end of file". Removed. New `scripts/check_ui.py` (brace/paren balance per `.ui`, string- and comment-aware) runs first in `deploy.sh`; verified it flags the reinjected bug at 628.
22. **Queue wording** (Mertie): Queue row / status line now `Queue: N waiting (typed !sprout in chat)`; the quiet-timeout rule is no longer spelled out there (behaviour unchanged; `QuietSeconds` still in config).
23. **Details trimmed** (Mertie): "Pen placed" and "Chair" rows removed (the Pen tab info line has both); 9 rows, shading re-alternated. `/sproutwatch status` still prints both lines.
24. **Pen tab info line removed** (Mertie: "too extra"): `#PenInfoLabel` and `StatusSnapshot.penInfoLine()` dropped (with its two assertions). Pen tab = prefab dropdown + Place / Clear. Pen position is still in `/sproutwatch status` (Pen placed / Chair lines).
21b/25. **Seated invulnerability** (Mertie, killed by a skeleton while seated): `camera/SeatedInvulnerabilitySystem` — a `DamageEventSystem` in `DamageModule.getFilterDamageGroup()` with query `PlayerRef` that cancels damage when `ChairCameraService.isSeated(uuid)` (the real-seat set; the manual camera toggle grants nothing). Same shape as the engine's `DamageSystems$PlayerDamageFilterSystem` (spawn protection). Registered in the plugin's guarded system block.
26. **Third run-button state** (Mertie): grey `$C.@SecondaryTextButton #ConnectingButton` "Connecting..." shows while the client is "connecting" or "reconnecting"; clicking it stops (cancels). `StatusSnapshot.runButton()/runButtonFor()` (tested) picks start/connecting/stop; the three buttons' Visible now travels with the live label push (trailing entry), so the button flips by itself when the connection comes up. 150 tests.
27. **Instant refresh on connect** (Mertie): `TwitchMembershipClient.setOnStateChange(Runnable)`; every state write goes through `setState` (fires only on an actual change, hook failures logged). The plugin wires it to `statusChanged()` → `OpenPages.refreshAll()`, so Connecting.../Stop and the green Listener cell update the moment the state changes instead of on the next tick. Client test asserts the hook sees "connecting" then "connected to #streamer".
28. **Pen guard** (Mertie: remove an NPC that hits a player or a viewer sprout; scope chosen: only at the pen). `pen/PenGuardSystem`: DamageEventSystem in the filter group, match-all query; when the source is an EntitySource (incl. ProjectileSource -> shooter) whose ref has an NPCEntity and is not a sprout, and the victim is a player or a registered sprout standing in `PenBounds.inGuardZone` (interior + 4 blocks, covers the chair; same y band as contains) in the pen world: cancel the hit, `commandBuffer.removeEntity(attacker)`, INFO log with the role. `PenBounds.GUARD_MARGIN/inGuardZone` + test → **151 tests**.
29. **Seated head looks at the pen, not the floor** (Mertie). ServerCameraSettings defaults `mouseInputType = LookAtTarget` (javap of the constructor): the character faces whatever the hidden centre cursor hits, which with our camera is the pen floor, so the head tilts down. `CameraPackets.penCamera` now sets `MouseInputType.LookAtPlane`, the value vanilla `/camera topdown` and `/camera sidescroller` use. `CameraPacketsTest` → **155 tests**. NOT VERIFIED until in-game (client behaviour); fallback = remove the line.
30. **Clothed Kweebecs** (Mertie: random clothing; scope chosen: Saplings + Sproutlings). Data: `Server/NPC/Roles/Sproutwatch/Sprout_Sapling_{Red,Orange,Pink,Yellow,Green,Brown}.json` (Variant of Template_Kweebec_Sapling, Appearance = vanilla Kweebec_Sapling_<C>, whose models already randomise Hair/Outfit/Beard/Eyes/Eyebrows/Eyelashes; HP 74, Sapling drops/memories) and `Sprout_Sproutling.json` (Variant of Template_Kweebec_Youngling, Appearance Sprout_Sproutling); `Server/Models/Sproutwatch/Sprout_Sproutling.json` (Parent Kweebec_Sproutling; face kept as DefaultAttachments; Hair = null w2 / Bud w3 / 13 Sapling hairstyles w1; Outfit = null w1 / Loin_Cloth w2 / Shirt w1 / Dress w1). Sproutling and Sapling share the same 37-bone skeleton (checked by bone name); Seedlings unchanged. Code: `Roles` entries may be `A|B|C` groups (SproutSpawner.pickRole picks the entry then a member; `SproutwatchConfig.allRoles()` flattens for the pen sweep / roleSet); new default `[Kweebec_Seedling, Sprout_Sproutling, Sprout_Sapling_Red|…|Brown]`; `upgradeLegacyRoles()` in plugin setup replaces exactly the old default. Tests → **159**. Headless boot: "Loaded 7 NPC configurations, Variant: 7", no FAIL. Checklist **26.** sprouts spawn with mixed colours, hairstyles and outfits; Sproutling hair size looks acceptable (if not: drop the 13 hairstyles from its Hair set, data only).
31. **Bug fix: old sprouts survived a relog** (Mertie). Root cause (from test world 2 log 2026-10-02 17:13: `Roles upgraded…` then the boot sweep `cleared 37`): item 30 replaced Roles with Sprout_* names, and the sweep (boot / Clear / re-Place, all via `roleSet()`) matches NPCs by role name, so Kweebec_Sapling / Kweebec_Sproutling sprouts left in the pen by an earlier run were no longer recognised (Seedlings kept their name and were still cleared). Fix: `SproutwatchConfig.sweepRoles()` = current roles + the pre-clothing defaults; `roleSet()` uses it. Regression test added → **160 tests**.
32. **Cosmetics odds** (Mertie: every clothing slot can be empty; more gender-neutral, fewer dresses). Six mod models `Server/Models/Sproutwatch/Sprout_Sapling_<C>.json` (Parent = vanilla Kweebec_Sapling_<C>; RandomAttachmentSets copied from the parent with Beard/Eyes/Eyebrows/Eyelashes unchanged; Hair = null w2 + 13 styles w1 (~13% none); Outfit = null w2 / Shirt w2 / Dress w1 = 40/40/20); the six roles now use them. Sproutling Outfit = null 2 / Loin_Cloth 4 / Shirt 2 / Dress 1 (22/44/22/11). Every option has an explicit Weight (vanilla leaves them unset). `assets/SproutCosmeticsTest` reads the shipped JSON: Hair and Outfit have a "null" option, all options weighted, Dress rarest, roles point at the mod models → **163 tests**.
33. **Seated head, second attempt** (Mertie: still looking down at the chair after item 29). Item 29 set `LookAtPlane` without a plane. javap of vanilla `PlayerCameraTopdownCommand` shows it always pairs LookAtPlane with `planeNormal = Vector3f(0,1,0)` (sidescroller: (0,0,1)). `CameraPackets.penCamera` now sets `planeNormal = (0,1,0)`; CameraPacketsTest asserts it. NOT VERIFIED until in-game. If still off: next lever is `applyLookType = Rotation` (head follows the camera rotation field) or a vertical plane facing the pen.
34. **Seated head, attempt 3** (Mertie: still down after item 33; asked to try head-follows-camera). `applyLookType = ApplyLookType.Rotation` (default is LocalPlayerLookOrientation). Expected: the head takes the camera rotation (~43 deg down toward the pen centre with 15/10 defaults). Working hypothesis for the root cause: with the default, the seated head keeps the pitch the player had when clicking the chair (looking down at it); the camera fields only matter if they replace that look.
35. **Seated head: root cause and fix** (Mertie confirmed the head keeps the pitch they had while clicking the seat: look up -> tilted up). The camera-side experiments (items 29, 33, 34) had no effect and are reverted; the camera packet keeps engine defaults (CameraPacketsTest guards that). New `camera/SeatLook`: on sitting, `TeleportSystems.queueAndSendClientTeleport(player, NaN position, NaN body, levelToward(seat, pen), false)` — the engine's own helper; NaN fields set its ignore flags (getIgnoredTransformFieldsFlags) so position/body are untouched, and TeleportAckTracker.validate passes a NaN expected position (dcmpl -> ok). `levelToward` = pitch 0, roll 0, yaw from Rotation3f.lookAt(seat, pen centre at seat height). SeatLookTest (2) → **165 tests**. NOT VERIFIED until in-game.
36. **Smaller Sproutling hats** (Mertie: "scale down the hats a smidge"). ModelAttachment has no scale field (Model/Texture/GradientId/GradientSet/Weight only), so `scripts/gen_sproutling_hats.py` (SCALE = 0.85) writes scaled copies of the 12 Sapling hat models to `Common/NPC/Sproutwatch/Hats/` and repoints the Sprout_Sproutling Hair options (textures stay vanilla; Bud unchanged). Scaling: root (Head bone anchor) position kept; every child position, shape offset and stretch multiplied by SCALE (stretch resizes without changing UVs), so hats shrink and settle onto the Sproutling head (head heights 28 vs 23.5 px; ratio 0.84). Verified: extents ratio 0.85 on x/y/z. SproutCosmeticsTest guards that every Sproutling hairstyle except Bud uses an existing mod copy → **166 tests**. Saplings keep the vanilla full-size hats. To retune: edit SCALE, run the script, redeploy.
37. **Creatures dropdown** (Mertie). Pen tab `#CreatureDropdown` (Kweebecs / Pigs / Chickens / Farm animals) above the prefab dropdown; config `Creatures` (default "kweebecs", shipped JSON 30 keys). `config/CreaturePreset` enum: KWEEBECS = the configured Roles; PIGS = ["Pig|Pig_Wild|Boar", "Pig_Piglet|Pig_Wild_Piglet|Boar_Piglet"] (boars by request); CHICKENS = ["Chicken|Chicken_Desert", "Chicken_Chick|Chicken_Desert_Chick"]; FARM = ["Chicken|Chicken_Desert|Chicken_Chick|Chicken_Desert_Chick", "Pig|Pig_Wild|Pig_Piglet|Pig_Wild_Piglet", "Cow_Calf"] (thirds, no boars). `SproutwatchConfig.spawnRoles()` feeds the spawner; `sweepRoles()` adds every preset so animals left from another set are swept. `SproutwatchActions.setCreatures(id)` saves and clears the pen via PenClearer on the pen world (guests kept, unlike Clear); no-op message when unchanged. Page action `selectCreatures`, event key `@Creatures`. Tests: CreaturePresetTest (7) + action test → **174 tests**. Not verified in-game: animal nameplates, chickens staying inside a 1-high fence, boar aggression (PenGuard never removes a sprout, so a boar sprout hitting a standing player is not removed; seated players are invulnerable).
38. **Pen creatures never damage players** (Mertie). `PenGuardSystem.decide(...)` (pure, `PenGuardDecisionTest` covers the whole table): a sprout attacking a player -> CANCEL anywhere (not removed); an outside NPC hitting a player or sprout at the pen -> CANCEL_AND_REMOVE (unchanged); everything else NONE (PvP, players hitting sprouts so retirement still works, sprouts among themselves, combat elsewhere). Handler now just gathers the five facts and applies the verdict. → **177 tests**.
