# Sproutwatch — design spec

**Date:** 2026-09-25
**Status:** approved in brainstorming, awaiting written-spec review
**Author:** Mertie (with Claude)

## 1. Goal

An idle "who is watching" pen for a Hytale stream, in the spirit of *Watch Your
Plastic Duck*. Every viewer connected to the streamer's Twitch chat gets a baby
Kweebec NPC wearing their name, wandering inside a fenced pen. A fixed camera
frames the pen as a 4:3 landscape so it can be cropped into an OBS layout.
Whoever sits on the chair in the pen gets that camera.

Non-goals for v1: viewer interaction (chat commands moving their Kweebec),
persistence across restarts, Helix/OAuth integration, sounds, HUD.

## 2. Decisions made

| Question | Decision |
|---|---|
| Viewer source | Anonymous Twitch IRC with `twitch.tv/membership` (NAMES + JOIN/PART), plus PRIVMSG senders. No OAuth. |
| Spawn rule | One new viewer per tick (default 60 s). |
| Leave rule | Despawn after a grace window (default 300 s) since last seen. |
| Population cap | Hard cap (default 30); when full, new viewers wait. Oldest stays. |
| Prefab source | A generated starter prefab (`scripts/gen_pen_prefab.py`) ships in the mod; Mertie can replace it with an in-game export later. The mod pastes it on command. |
| 4:3 meaning | Pen interior is 16 x 12 blocks (4:3 landscape). Camera sits above the chair on a long side looking across the pen, so the wide axis fills the frame. |
| Camera owner | Any player who sits on the chair block inside the prefab; standing up resets the camera. |
| Kweebec variants | Random mix of `Kweebec_Seedling`, `Kweebec_Sproutling`, `Kweebec_Sapling` (configurable). |
| Name | `sproutwatch`, mod_id `sproutwatch`, command `/sproutwatch`. |
| Core loop | Roster reconciliation: each tick compares the live roster to the pen and applies the diff. |

## 3. Project layout

Copied from the `mysterion-mazurka-8` scaffold (HytaleModding plugin template,
AzureDoom Hytale Gradle Plugin, Java 25, Hytale 0.6.3, `includes_pack = true`,
`deploy.sh`, JUnit 5 with the server jar mirrored onto the test classpath).

```
sproutwatch/
  gradle.properties            mod_id=sproutwatch, main_class=dev.hytalemodding.sproutwatch.SproutwatchPlugin
  deploy.sh                    build + copy build/libs/sproutwatch-<ver>.jar into UserData/Mods
  src/main/java/dev/hytalemodding/sproutwatch/
    SproutwatchPlugin.java     wiring, bridge logger (copied from Subinator), start/stop listener
    config/SproutwatchConfig.java
    commands/SproutwatchCommand.java
    twitch/TwitchMembershipClient.java
    twitch/MembershipParser.java
    twitch/RosterEvent.java
    twitch/ChatRoster.java
    pen/PenRegistry.java
    pen/PenReconciler.java
    pen/PenPlan.java
    pen/PenTicker.java
    pen/SproutSpawner.java
    pen/PenBounds.java
    pen/PenDespawnSystem.java
    prefab/PenPlacer.java
    camera/PenCamera.java
    camera/ChairCameraService.java
  src/main/resources/
    Sproutwatch_config.json    defaults, kept in sync with SproutwatchConfig
    Server/Prefabs/Sproutwatch/sproutwatch_pen.prefab.json   (generated; see scripts/)
  scripts/gen_pen_prefab.py    regenerates the starter prefab from a few constants
    Server/Languages/en-US/sproutwatch.lang
  src/test/java/...            one test class per pure unit (section 10)
  docs/superpowers/specs/      this file
  docs/superpowers/plans/      implementation plan (next step)
```

Each package has one job and talks to the others only through the plugin
class or a small interface. `twitch` never touches the entity store; `pen`
never opens a socket; `camera` never spawns anything.

## 4. Twitch roster (`twitch/`)

### 4.1 `TwitchMembershipClient`

A fork of Subinator's `TwitchChatClient`, keeping: TLS socket to
`irc.chat.twitch.tv:6697`, random `justinfan` NICK, one daemon thread,
one-shot start/stop, PING/PONG, reconnect with exponential backoff (initial
5 s, doubling to 5 min, reset after a 60 s healthy connection), 10 min read
timeout, JUL bridge logger.

Changes from Subinator:

- `CAP REQ :twitch.tv/membership twitch.tv/commands` (membership instead of
  tags). If Twitch has not ACKed membership by the 376 welcome line, log a
  warning: JOIN/PART will be missing and only chatters who speak will appear.
- Every line goes through `MembershipParser.parse(line)`; each non-null
  `RosterEvent` is applied to the `ChatRoster` on the client thread.
- Exposes `getState()`, `getChannel()`, `isMembershipAcked()`,
  `getRosterSize()` for `/sproutwatch status`.

### 4.2 `MembershipParser` (pure)

`static RosterEvent parse(String line)` returns:

| IRC line | Event |
|---|---|
| `:x.tmi.twitch.tv 353 justinfan #chan :a b c` | `Names(["a","b","c"])` |
| `:a!a@a.tmi.twitch.tv JOIN #chan` | `Join("a")` |
| `:a!a@a.tmi.twitch.tv PART #chan` | `Part("a")` |
| `:a!a@a.tmi.twitch.tv PRIVMSG #chan :hi` | `Seen("a")` |
| anything else | `null` |

Logins are lowercased. `RosterEvent` is a sealed interface with those four
records.

### 4.3 `ChatRoster` (thread-safe)

`ConcurrentHashMap<String login, long lastSeenMillis>` plus a `Set<String>
ignored` supplied from config (lowercased). Methods:

- `apply(RosterEvent, long now)`: `Names`/`Join`/`Seen` put `now` for each
  login; `Part` removes the login. Ignored logins and any `justinfan*` login
  are dropped before insertion.
- `snapshot()`: an immutable `Map<String, Long>` copy for the reconciler.
- `clear()` on client stop.

`Part` removes immediately from the roster, but the pen keeps the Kweebec
until the grace window elapses (section 5.2), so chat reconnects do not
flicker. A reconnect's NAMES burst simply refreshes timestamps.

## 5. Pen lifecycle (`pen/`)

### 5.1 `PenRegistry`

Maps `login -> PenEntry(login, displayName, entityUuid, networkId, worldUuid,
spawnedAtMillis, lastSeenMillis)`. Same listener seam as Subinator's
`BossRegistry` is *not* needed in v1; keep it a plain synchronized map with
`put/remove/get/snapshot/size`.

### 5.2 `PenReconciler` (pure, unit-tested)

> 2026-09-29: with `PersistSprouts` (default on) the grace pass is skipped; at `MaxSprouts` the pen login not in the eligible roster with the smallest `lastSeen` is replaced by the newcomer, one per tick. The reconciler also takes a priority list (the `!sprout` queue). See the plan's Verification notes.

```
static PenPlan reconcile(Map<String,Long> roster,   // login -> lastSeen (from ChatRoster)
                         Map<String,Long> pen,      // login -> lastSeen (from PenRegistry)
                         int cap, long graceMillis, long now)
```

Both maps are `login -> lastSeenMillis`. `PenTicker` refreshes each
`PenEntry.lastSeenMillis` from the roster snapshot before calling reconcile,
so a viewer still in chat always has a fresh pen timestamp, while a parted
viewer's pen timestamp freezes at the moment they left.

Returns `PenPlan(Optional<String> spawn, List<String> despawn)`:

1. **despawn** = every login in `pen` whose `lastSeen < now - graceMillis`.
   A viewer who PARTs and rejoins within the grace window keeps their Kweebec.
2. **spawn** = if `pen.size() - despawn.size() < cap`, the login in `roster`
   but not in `pen` with the **smallest** roster `lastSeen` (earliest
   arrival, so nobody waits forever); otherwise empty.

### 5.3 `PenTicker`

Started by `startListener()`, stopped by `stopListener()`. Schedules itself on
the pen world with `world.scheduleAfter(tick, TickSeconds, SECONDS)` and
re-arms at the end of each tick while running. Each tick, on the world thread:

1. Resolve the pen world from the saved anchor; if not loaded, skip.
2. Refresh registry last-seen from `roster.snapshot()`.
3. `plan = PenReconciler.reconcile(...)`.
4. For each despawn: remove the entity (via the store's remove call on the
   world thread) and evict from the registry.
5. For the spawn, call `SproutSpawner.spawn(login)`.

Any exception is caught and logged; the ticker always re-arms.

### 5.4 `PenBounds` (pure)

Derived from the saved anchor, rotation, and prefab dimensions:

- `interior`: the prefab's block-space box shrunk by one block on each
  horizontal side and floored at anchor y + 1.
- `randomPoint(Random)`: uniform x/z inside `interior`, y = floor level + 1.
- `center()`: used by the camera.
- `contains(Vector3d)`: used by `clear`.

### 5.5 `SproutSpawner`

On the world thread. Picks a random role from `Roles`, then tries
`NPCPlugin.spawnNPCWithSpaceValidation` at up to 8 random `PenBounds`
points, then the column probe over the pen center, exactly the ladder
Subinator's `BossSpawner.spawnOne` uses. No unvalidated fallback: a missed
spawn is simply retried next tick.

In the spawn init callback:

- `accessor.ensureAndGetComponent(ref, Nameplate.getComponentType()).setText(displayName)`
  where `displayName` is the login as Twitch gave it (v1 has no display-name
  lookup; logins are lowercase).
- Register the `PenEntry` with the entity's UUID and `NetworkId`.

### 5.6 `PenDespawnSystem`

Copy of Subinator's `BossDespawnSystem`: an entity-removed system that evicts
the matching registry entry when a tracked Kweebec is removed for any reason
(killed, chunk unload, `/kill`). The next tick then respawns that viewer.

### 5.7 Cleanup

`/sproutwatch clear` and plugin `setup()` (when an anchor is saved and the
world is loaded) iterate the pen world's NPC entities, and remove any whose
role name is in `Roles` and whose position is inside `PenBounds.interior`.
Then clear the registry. This is the only restart safety; nothing is
persisted about individual Kweebecs.

## 6. Prefab and placement (`prefab/`)

### 6.1 The prefab

Hytale prefabs are plain JSON (`version: 8`, `blockIdVersion: 11`, anchor,
a `blocks` list of `{x, y, z, name, rotation?, support?}`, and `entities`),
the same format wayside-shrines ships. `scripts/gen_pen_prefab.py` writes the
starter pen to `src/main/resources/Server/Prefabs/Sproutwatch/sproutwatch_pen.prefab.json`:

- Anchor at the floor's min corner. Floor `Soil_Grass` at y=0 over the whole
  box (x 0..17, z 0..13) plus the chair tile.
- `Wood_Hardwood_Fence` ring at y=1 (Hytale fences are one block tall) on x=0|17 and z=0|13, enclosing a
  16 x 12 interior (x 1..16, z 1..12), 4:3 landscape. Straight runs along x use rotation 0, runs along z rotation 1, and the four corners are `*Wood_Hardwood_Fence_State_Definitions_Corner` (vanilla Bunny_Area rule).
- Interior y=1..4 set to `Empty` so pasting flattens and clears terrain.
- One `Furniture_Village_Chair` at (8, 1, -1), outside the -z long side,
  `rotation: 0`. Rotation convention verified against vanilla Village
  prefabs (chairs face their tables): 0 faces +z, 1 faces +x, 2 faces -z,
  3 faces -x. The chair's block type declares `Seats`, so it is a block
  mount the engine's `Block_Seat` interaction handles.

Mertie can later replace the file with an in-game export; the placer reads
dimensions and the chair position from whatever file is bundled, so nothing
else changes.

### 6.2 `PenPlacer`

`/sproutwatch place` (caller must be in a world):

1. Load the bundled prefab into an `IPrefabBuffer` **(research item R1:
   which loader turns a classpath `.prefab.json` into a buffer; candidates are
   `PrefabFormat` and `SelectionPrefabSerializer` in the server jar)**.
2. `PrefabUtil.paste(buffer, world, floorPos, Rotation.NONE, random, store)`
   at the block under the caller's feet.
3. Scan the pasted box for the first block whose `BlockType` has mount
   points (chair). If none, warn and leave `Chair*` unset (camera command
   still works manually).
4. Save to config: `PenWorld` (UUID string), `PenX/Y/Z` (anchor), `PenSizeX/Y/Z`,
   `ChairX/Y/Z`, and `PenFacing` (the horizontal direction from the chair
   into the pen; drives camera placement).

## 7. Camera (`camera/`)

### 7.1 `PenCamera` (pure)

From `PenBounds`, `PenFacing`, and config `CameraHeight` (default 16),
`CameraBack` (default 20, blocks behind the short side toward the chair),
`CameraFov` (default 30):

- `position()` = center of the long side nearest the chair, moved
  `CameraBack` blocks away from the pen and `CameraHeight` blocks up.
- `lookAt()` = `PenBounds.center()` at floor level.
- `pitch/yaw` derived from those two points.

Defaults are a starting guess; `/sproutwatch camera` exists so Mertie can
tune the three numbers live until a landscape crop of the game window is
filled by the pen.

### 7.2 `ChairCameraService`

Registered with `getEntityStoreRegistry()` like Subinator's systems. It
reacts to the engine's mount components (`builtin/mounts`: `MountedComponent`
on the rider, `BlockMountComponent` for block seats). **Research item R2:**
confirm which component-added/removed hook fires for a block mount, and how
the seat block position is read from it.

- On mount by a player whose seat block equals the saved `Chair*` position:
  send `SetServerCamera(ClientCameraView.Custom, ..., ServerCameraSettings)`
  built from `PenCamera` (same packet the built-in `/camera topdown` command
  sends).
- On dismount (or disconnect) of that player: `CameraManager.resetCamera(playerRef)`.
- `/sproutwatch camera` toggles the same packet for the caller without
  sitting, for tuning.

## 8. Config (`config/SproutwatchConfig`)

Codec-backed like `SubinatorConfig`, file `Sproutwatch_config.json`:

| Key | Default | Notes |
|---|---|---|
| `TwitchChannel` | `""` | normalised to `[a-z0-9_]` |
| `AutoStartOnBoot` | `false` | |
| `TickSeconds` | `60` | min 5 |
| `GraceSeconds` | `300` | min 0 |
| `MaxSprouts` | `30` | min 1 |
| `Roles` | `["Kweebec_Seedling","Kweebec_Sproutling","Kweebec_Sapling"]` | |
| `IgnoreUsers` | `["nightbot","streamelements","streamlabs"]` | plus the channel login itself, always; edited live by `ignore add/remove` |
| `AllowUsers` | `[]` | empty = everyone in chat is eligible; otherwise only these logins get a sprout (`IgnoreUsers` still applies on top); edited by `allow add/remove` |
| `QueueCommand` | `"!sprout"` | chat text (trimmed, case-insensitive) that puts the sender at the front of the spawn order; consumed when they spawn, dropped when they leave chat |
| `PenWorld` | `""` | set by `place` |
| `PenX/PenY/PenZ` | `0` | set by `place` |
| `PenSizeX/PenSizeY/PenSizeZ` | `0` | set by `place` |
| `ChairX/ChairY/ChairZ` | `0` | set by `place`; `ChairSet` boolean guards |
| `PenFacing` | `"north"` | set by `place` |
| `CameraHeight` | `16.0` | |
| `CameraBack` | `20.0` | |
| `CameraFov` | `30.0` | |
| `PersistSprouts` | `true` | on: sprouts stay after their viewer leaves (grace ignored); at `MaxSprouts` the sprout whose viewer has been gone the longest is replaced by a waiting newcomer, one per tick; toggled by `persist` |

## 9. Commands (`/sproutwatch ...`)

| Sub-command | Effect |
|---|---|
| `channel <name>` | save channel; restart listener if running |
| `start` / `stop` | start/stop the Twitch client and the ticker |
| `status` | connection state, membership ACK, roster size, pen count/cap, anchor set?, chair set? |
| `place` | paste the prefab at the caller's feet and save anchor/chair |
| `clear` | despawn every tracked or in-bounds youngling, empty the registry |
| `camera` | toggle the fixed pen camera for the caller |
| `interval <seconds>` | set `TickSeconds` and re-arm the ticker |
| `test <login>` | inject `Chat(login, "")` into the roster with firstSeen 0 so a spawn happens next tick (or immediately if `test <login> now`); does not touch the `!sprout` queue |
| `allow list` / `allow add <login>` / `allow remove <login>` | show or edit `AllowUsers` (empty = everyone eligible); saved to config |
| `ignore list` / `ignore add <login>` / `ignore remove <login>` | show or edit `IgnoreUsers` (list output includes the channel login); saved to config, effective on the next roster event |
| `queue list` / `queue clear` / `queue remove <login>` | show (FIFO), empty, or edit the in-memory `!sprout` priority queue |
| `persist` / `persist on|off` | toggle or set `PersistSprouts` (sprouts outlive their viewer; longest-gone replaced at the cap); saved to config |

`start`, `camera`, and `test` refuse with a hint to run `place` when no
anchor is saved.

## 10. Error handling

- Twitch: connection loss reconnects with backoff; membership not ACKed logs
  once per connection; parser never throws (returns null).
- Spawn: validation failure logs at FINE and retries next tick; role
  problems (`FAIL_NOT_SPAWNABLE` etc.) log a WARNING once per role.
- Ticker: every tick body is wrapped in try/catch; the ticker always re-arms
  while running.
- Camera: packet or reset failure logs and never affects the pen.
- Prefab: load or paste failure returns an error message to the caller and
  leaves config untouched.

## 11. Testing

Unit tests (JUnit 5, pure code only, no server boot):

- `MembershipParserTest`: the four line shapes, lowercase, garbage lines.
- `ChatRosterTest`: apply order, ignore list, justinfan filter, snapshot immutability.
- `PenReconcilerTest`: spawn picks earliest arrival, cap blocks spawn, grace
  window, despawn of absent logins, empty inputs.
- `PenBoundsTest`: interior shrink, random point inside, contains.
- `PenCameraTest`: position/lookAt for each `PenFacing`.
- `SproutwatchConfigTest`: defaults, clamps, channel normalisation.

Manual verification on the dev server (`./deploy.sh`, then in game):
`/sproutwatch place`, `/sproutwatch test alice now`, sit on the chair, stand
up, `/sproutwatch clear`, then a live channel with `start`.

## 12. Research items for the plan

- **R1** Loading a bundled `.prefab.json` into an `IPrefabBuffer` for `PrefabUtil.paste`. Fallback if no loader is exposed: the format is simple JSON, so the placer can parse it and set blocks directly, which also makes chair detection trivial.
- **R2** Which ECS hook fires on block-mount/dismount and how to read the seat block position.
- **R3** Confirm `Nameplate` on an NPC is rendered client-side for NPCs
  spawned via `NPCPlugin` (Subinator used the lang-file `NameTranslationKey`
  route, so this is the first per-entity use).

## 13. Out of scope (v1)

Viewer chat commands, Kweebec cosmetics per viewer, Helix display names,
persistence of who was present, spawn sounds/particles, HUD counters, and
running more than one pen.
