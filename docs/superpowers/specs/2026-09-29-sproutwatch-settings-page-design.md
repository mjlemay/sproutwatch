# Sproutwatch settings page: design

Date: 2026-09-29. Status: approved in conversation, awaiting written-spec review.

## 1. Goal

An in-game page, opened with `/sproutwatch settings`, that shows what the mod is doing and lets the streamer change the common settings without typing commands: the Twitch channel, the allow and ignore lists, start/stop, persist, tick interval, pen prefab, place and clear. Status numbers stay live while the page is open.

## 2. Non-goals

Camera tuning, queue management, extra prefabs (only `default` exists), non-admin access, persistence of page state across reopen. Existing chat commands keep working unchanged.

## 3. Engine mechanism (verified against 0.6.3 decompiled source)

- A page is a server-side object extending `InteractiveCustomUIPage<EventData>`; it is opened per player with `player.getPageManager().openCustomPage(ref, store, page)` on that player's world thread (the same call the sibling Qrhyr mod uses).
- `build(ref, commandBuilder, eventBuilder, store)` appends a `.ui` document from the mod's asset pack (`Common/UI/Custom/Pages/...`), sets values with `commandBuilder.set("#Selector.Property", value)` (strings, booleans, numbers, dropdown entry lists via `DropdownEntryInfo`), and binds button events with `eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#Button", EventData)`, where the `EventData` maps keys to `#Selector.Value` expressions so the client sends the current field values back with the event.
- `handleDataEvent(ref, store, data)` receives the decoded event record (a `BuilderCodec` over the keys). `rebuild()` re-runs `build`; `sendUpdate(commandBuilder)` pushes partial changes without a rebuild; `close()` closes the page; `onDismiss` fires when the client closes it.
- Tabs are groups toggled with `#Group.Visible`, as vanilla's teleporter and trigger-volume pages do. Field rows reuse the vanilla `Common.ui` templates (`TextRow`, `DropdownRow`, `CheckboxRow`, text buttons).

## 4. Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `ui/SproutwatchActions` | Every mutation the page or a command can perform, each returning a result message: `setChannel`, `startListener`, `stopListener`, `place(PlayerRef)`, `clear()`, `setPersist(boolean)`, `setTickSeconds(int)`, `addAllow/removeAllow/addIgnore/removeIgnore`, `selectPrefab(name)`; plus `snapshot()` building a `StatusSnapshot` record (listener state, channel, feed acked, roster size, queue size, pen count, cap, retired count, persist, pen position/size/facing, chair set, prefab name). | plugin (config, roster, registry, ticker, client), `PenClearer`, `PenPlacer`, a world-executor abstraction so tests can record queued work |
| `commands/SproutwatchCommand` (modified) | Delegates every existing subcommand body to `SproutwatchActions`; adds `settings` (player-only) that opens the page. Replies and behaviour unchanged. | `SproutwatchActions` |
| `ui/SproutwatchSettingsPage` | Builds the document from a `StatusSnapshot` and config; remembers the selected tab; binds button events; on event, calls the matching action, stores the message, rebuilds; `refreshStatus()` pushes only the status labels via `sendUpdate`. Registers in `OpenPages` on open, removes itself on dismiss. | engine page API, `SproutwatchActions`, `OpenPages` |
| `ui/OpenPages` | Thread-safe set of open settings pages keyed by player UUID; `forget(uuid)` on disconnect; `refreshAll()` called after each tick. | none |
| `pen/PenTicker` (modified) | Gains `setAfterTick(Runnable)`; invoked on the world thread at the end of `tickOnWorldThread` inside its try/catch. | existing |
| `prefab/PenPrefabCatalog` | Maps prefab names to bundled resource paths; `names()` for the dropdown; `resourceFor(name)` with fallback to `default`. | `PenPrefab` |
| `config/SproutwatchConfig` (modified) | New key `PenPrefab` (string, default `default`). | existing codec |
| `prefab/PenPlacer` (modified) | Reads the prefab through the catalog using the configured name. | `PenPrefabCatalog` |
| `SproutwatchPlugin` (modified) | Constructs `SproutwatchActions` and `OpenPages`; wires `ticker.setAfterTick(openPages::refreshAll)`; the existing disconnect hook also calls `openPages.forget(uuid)`. | existing |
| `Common/UI/Custom/Pages/Sproutwatch/SettingsPage.ui` (+ `ListEntryRow.ui`) | The document: tab bar, three groups, status labels, fields, buttons, message line. | vanilla `Common.ui` |

## 5. Page layout

Tab bar: **Status**, **Listener**, **Pen**. Selecting a tab flips group visibility; the selection survives rebuilds while the page is open.

**Status tab**
1. Status block (labels): listener state; channel; Twitch JOIN/PART feed on/off; roster count; queue length; pen count of cap; retired count; persist on/off; pen placed at position, size, facing, or "not placed"; chair set or not.
2. Channel: text field prefilled with the current channel, Save button (normalised; restarts the listener if it is running, exactly as the command does).
3. Allow list panel: one row per login with a Remove button; "everyone is eligible" line when empty; text field and Add button.
4. Ignore list panel: same shape; the channel login row is shown fixed with no Remove button.

**Listener tab**
1. Start/Stop: a checkbox labelled with the current state; toggling calls start or stop and shows the result (including the "no channel" and "no pen" refusals).
2. Persist: checkbox with the one-line explanation of each state.
3. Tick interval: number field with Save (min 5, as the command).

**Pen tab**
1. Prefab dropdown (entries from the catalog; `default` only today), saved on change.
2. Place button: sweeps the old pen and pastes centred on the player, via the same path as the command; the message line shows "Placing the pen..." until the next status push.
3. Clear button.
4. Read-only line: current pen interior min corner, size, facing, chair position.

**Message line** (all tabs): result of the last action; cleared on the next action.

## 6. Data flow

- Open: command handler resolves the player's world, queues `openCustomPage` on that world thread, page registers in `OpenPages`.
- Build: page fills fields from `actions.snapshot()` and config; every button binding carries an `action` key and the values it needs (`channel`, `allowInput`, `ignoreInput`, `interval`, `prefab`, per-row `login` for Remove buttons via a per-row binding with the login baked into the event data).
- Event: `handleDataEvent` switches on `action`, runs the action inside try/catch, stores the message, calls `rebuild()`. Actions that must run on the world thread (place, clear) are queued by the actions service and return immediately with an interim message.
- Live status: `PenTicker` calls the after-tick hook; `OpenPages.refreshAll()` asks each page to `refreshStatus()`, which sends only the status labels (never the fields), so in-progress edits are preserved.
- Removal: `onDismiss` and the plugin's disconnect hook remove the page from `OpenPages`.
- Persistence: all mutations go through the actions service, which edits config and calls `saveConfig`, identical to the commands.

## 7. Error handling

- Every action call in the page is wrapped; failures log a WARNING with the `[Sproutwatch]` prefix and show a short message in the message line.
- A page whose world is unloading or whose player left is dropped from `OpenPages`; a failed `sendUpdate` (player gone) removes the page.
- Inputs are normalised with `SproutwatchConfig.normalizeChannel`; blank or invalid input shows "Invalid login." / "Invalid channel name." in the message line.
- Opening from console replies "Run this in game as a player." like the other player commands.

## 8. Testing

- Unit (JUnit): `SproutwatchActions` against a fake plugin surface (real `SproutwatchConfig`, `ChatRoster`, `PenRegistry`, a recording world executor, a stub listener controller): channel set with and without a running listener; start refusals; allow/ignore add/remove results and normalisation; persist and interval set; prefab select with unknown name; snapshot fields. `PenPrefabCatalog` names and fallback. `OpenPages` add/forget/refresh dispatch with a fake page.
- Engine-bound (compile + in-game checklist): page opens; each tab; each button; live status update while a viewer joins; place/clear from the page; dismiss and reopen; disconnect while open.

## 9. Open questions resolved

- Editable lists on the page: yes.
- Persist toggle location: Listener tab.
- Status refresh: live every tick while open.
