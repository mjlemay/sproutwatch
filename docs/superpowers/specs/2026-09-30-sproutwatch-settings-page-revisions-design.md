# Sproutwatch settings page revisions: design

Date: 2026-09-30. Status: approved in conversation, awaiting written-spec review.
Revises: `2026-09-29-sproutwatch-settings-page-design.md` (the page as built by plan `2026-09-30-sproutwatch-settings-page.md`, Tasks 1–6, deployed 2026-09-30).

## 1. Goal

Three changes Mertie asked for after seeing the first deployed page:

1. Tabs that look like Hytale tabs and switch without rebuilding the page.
2. The allow and ignore lists on their own **Viewers** tab.
3. A single Start / Stop button for the listener instead of a checkbox.

## 2. Non-goals

No new settings, no change to any chat command, no change to `SproutwatchActions`, `OpenPages`, `PenTicker` or the live-status push. The Persist checkbox stays a checkbox (it is a setting, not an action). No use of the client's native `TabNavigation` widget: it is icon-only in vanilla and no server code in the engine jar listens to its `SelectedTabChanged` event, so it is unverified end to end.

## 3. Research summary (vanilla evidence)

There is no client-side switching primitive in the `.ui` language: every tab click is a server round trip. Vanilla pages with panes use one of:

- **Sibling groups toggled by `.Visible`, active button shown by a `.Style` swap, sent as a partial `sendUpdate` with no rebuild** — `EntitySpawnPage` (`#TabNPC/#TabItems/#TabModel`, `Value.ref("Common.ui", "DefaultTextButtonStyle" | "SecondaryTextButtonStyle")`), `UIGalleryPage` (`Value.ref("Pages/UIGallery/CategoryButton.ui", "SelectedLabelStyle")`). Event bindings registered in `build()` survive partial updates.
- **`.Disabled` on the active button + full rebuild** — `TriggerVolumeInspectorPage`. What Sproutwatch does today; weakest visual, and the rebuild wipes any text being typed.
- **One page per tab** — `WorldEventPanelPage`. Rejected: `PageManager.openCustomPage` calls `onDismiss` on the old page at every switch, which fights `OpenPages` registration, and it is a full clear+rebuild each time.
- **Twin pre-styled elements, server shows one via `.Visible`** — `MemoriesPage` (`#ButtonSelected` / `#ButtonNotSelected`). Used here for Start / Stop.

`Common.ui` ships header-tab 9-patch textures used by its `@HeaderTabsStyle`: `Common/HeaderTabBackground.png` (dark bordered box) and `Common/HeaderTabSelectedBackground.png` (lighter gradient, gold border), both `Border: 7`. They are rectangular, so they work as `TextButtonStyle` backgrounds for 120×30 text buttons (unlike the icon-shaped `Tab.png`).

## 4. Design

### 4.1 Tabs

**Tab bar.** Four `TextButton`s in `#TabBar`: `#TabStatus`, `#TabViewers`, `#TabListener`, `#TabPen`, each `Anchor: (Width: 120, Height: 30, Right: 4)` (last one without `Right`). 4 × 124 = 496 px fits the 696 px content width. Two styles are declared at the top of `SettingsPage.ui`, after `$C = "../../Common.ui";`:

- `@TabStyle` — `TextButtonStyle` whose four states all use `PatchStyle(TexturePath: "Common/HeaderTabBackground.png", Border: 7)`; label `$C.@SmallSecondaryButtonLabelStyle`, hovered/pressed label with the brighter text colour.
- `@TabSelectedStyle` — same shape with `"Common/HeaderTabSelectedBackground.png"` and the gold label colour.

Every tab button is written as a plain `TextButton #TabX { Text: "..."; Style: @TabStyle; Anchor: ...; }` (not a `$C.@SmallSecondaryTextButton` instance), so the server can swap `.Style` freely. Exact `Common.ui` names for the label style and colours are verified by grep in the plan, not assumed.

**Content groups.** Four sibling groups under `#Content`: `#StatusTab` (`Visible: true`), `#ViewersTab`, `#ListenerTab`, `#PenTab` (`Visible: false`). Same `LayoutMode: TopScrolling`, `Anchor: (Height: 520)` as today.

**Server.** The tab bar moves out of the page into a small package-private enum `SettingsTab { STATUS, VIEWERS, LISTENER, PEN }` that owns each tab's button and group selector, `apply(cmd, active)` (sets the four groups' `.Visible` and each button's `.Style` to `Value.ref(DOCUMENT, "TabSelectedStyle")` or `Value.ref(DOCUMENT, "TabStyle")` — `DOCUMENT` is the page's own `.ui` path, the same way `UIGalleryPage` refs its row document), `bind(evt)` and `parse(name, current)`. `build()` calls `bind` + `apply`. The `"tab"` event handler does `tab = SettingsTab.parse(e.tab, tab)`, then `apply` on a fresh `UICommandBuilder` and `sendUpdate(cmd)` — **no `rebuild()`**, no change to `message`. All other actions keep the rebuild they have today. The `.Disabled` sets on tab buttons are removed. `parse` and the selectors are unit-tested; the enum keeps the page under its 260-line budget.

`refreshStatus()` and its change gate are untouched: a tab switch does not change the status labels, and the extra `sendUpdate` costs the same single acknowledgement window as any other update.

**Fallback.** If the header-tab textures render badly on the 30 px buttons in-game, keep the Java and switch the two styles to `Value.ref("Common.ui", "DefaultTextButtonStyle")` (selected, gold) / `Value.ref("Common.ui", "SecondaryTextButtonStyle")` (inactive) — the `EntitySpawnPage` look. That is a `.ui`-plus-two-constants change, no redesign.

### 4.2 Viewers tab

`#ViewersTab` receives, verbatim from the Status tab: the allow subtitle, `#AllowEmptyLabel`, `#AllowList`, the `#AllowField` + `#AddAllowButton` row, the ignore subtitle, `#IgnoreList`, the `#IgnoreField` + `#AddIgnoreButton` row. Ids do not change. The Status tab keeps the ten status labels and the channel subtitle + `#ChannelField` + `#SaveChannelButton` row.

Server: `buildStatusTab` keeps only the channel field and its binding; a new `buildViewersTab(cmd, evt, cfg)` holds the allow/ignore list building moved out of it unchanged.

### 4.3 Start / Stop button

The Listener tab's "Start / stop" row replaces `$C.@CheckBox #RunCheck` with two buttons in the same slot: `$C.@TextButton #StartButton { @Text = "Start listener"; @Anchor = (Width: 200); }` and `$C.@CancelTextButton #StopButton { @Text = "Stop listener"; @Anchor = (Width: 200); }`, both followed by the existing `#RunLabel` caption. The server sets `#StartButton.Visible = !running` and `#StopButton.Visible = running` on every build, so the player sees one button that flips text and colour. Bindings: `Activating` on `#StartButton` → action `startListener`; on `#StopButton` → `stopListener`. The `toggleListener` action, the `@Run` codec key (`KEY_RUN`, field `run`) and the `#RunCheck.Value` set are removed.

`StatusSnapshot.runLabel()` wording changes from "…; untick to stop" / "…; tick to start" to "…; press Stop listener to stop" / "…; press Start listener to start". The two assertions in `SproutwatchActionsTest` change accordingly.

### 4.4 Task 7 checklist changes

In the previous plan's in-game checklist: item 2 adds "the Status tab button shows the selected (gold-bordered) style, the other three the dark style"; item 3 becomes "click Viewers, Listener, Pen: the pane switches, the clicked tab takes the selected style, and text typed into the channel field before switching is still there afterwards" (partial update, not a rebuild); items 5–6 move to the Viewers tab; items 7, 10 and 13 read "press Start listener" / "press Stop listener" and check the button flips; item 9's interval field is unchanged.

## 5. Files

- Modify `src/main/resources/Common/UI/Custom/Pages/Sproutwatch/SettingsPage.ui` (styles, 4-button tab bar, `#ViewersTab`, Start/Stop row).
- Create `src/main/java/dev/hytalemodding/sproutwatch/ui/SettingsTab.java` and `src/test/java/dev/hytalemodding/sproutwatch/ui/SettingsTabTest.java` (tab bar enum: selectors, style swap, bindings, name parsing).
- Modify `src/main/java/dev/hytalemodding/sproutwatch/ui/SproutwatchSettingsPage.java` (uses `SettingsTab`, `switchTab` partial update, `buildViewersTab`, Start/Stop bindings; stays under 260 lines).
- Modify `src/main/java/dev/hytalemodding/sproutwatch/ui/SettingsEvent.java` (drop `KEY_RUN` / `run`).
- Modify `src/main/java/dev/hytalemodding/sproutwatch/ui/StatusSnapshot.java` and `src/test/java/dev/hytalemodding/sproutwatch/ui/SproutwatchActionsTest.java` (`runLabel` wording).
- `ListEntryRow.ui`, `SproutwatchActions`, `OpenPages`, `SproutwatchCommand`, `SproutwatchPlugin`: unchanged.

## 6. Testing

Pure logic: `runLabel` wording (existing tests updated) and `SettingsTab` selectors/parsing (3 new tests; 120 → 123). Engine-bound (`.ui`, page): compile + jar packing + headless boot as before, then the revised in-game checklist in §4.4. The `.ui` is validated statically against vanilla `Common.ui` by the reviewer as in Task 5.
