# Sproutwatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every viewer in a Twitch chat gets a named baby Kweebec wandering in a fenced 16x12 landscape pen; sitting on the pen's chair gives that player a fixed 4:3 landscape camera over the pen.

**Architecture:** Three isolated packages joined only by the plugin class: `twitch` (anonymous IRC with `twitch.tv/membership` feeding a `ChatRoster`), `pen` (a ticker reconciles roster vs. registry and spawns/despawns NPCs on the world thread), `camera` (an ECS `RefChangeSystem` on `MountedComponent` sends `SetServerCamera` when a player sits on the saved chair). `prefab` pastes the bundled pen with the engine's own JSON prefab deserializer. Pure logic (parser, roster, reconciler, bounds, layout, camera math, config) is unit-tested; engine-touching classes are thin and verified in-game.

**Tech Stack:** Java 25, Hytale server 0.6.3 (`com.azuredoom.hytale-tools` Gradle plugin), JUnit 5, `org.bson` (bundled in the server jar) for prefab JSON.

**Spec:** `docs/superpowers/specs/2026-09-25-sproutwatch-design.md`

---

## Commit policy for this plan

Mertie reviews and commits; the executor does NOT commit. Every task ends with a **Checkpoint** step: run the tests, confirm green, `git add -A` to stage. The final task asks Mertie for the go-ahead before the first commit. Work happens on branch `dev`; `main` is fast-forwarded when a version ships.

## Research findings (verified with javap against Server-0.6.3.jar on 2026-09-26)

These resolve the spec's R1-R3 and pin every engine call used below. Do not substitute other APIs.

| Item | Verified API |
|---|---|
| R1 prefab load | `com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer.deserialize(org.bson.BsonDocument)` returns a `BlockSelection` from exactly the JSON format the bundled prefab uses (`version`, `anchorX/Y/Z`, `blockIdVersion`, `blocks[{x,y,z,name,...}]`). `org.bson.BsonDocument.parse(String)` builds the document. |
| R1 prefab paste | `BlockSelection.placeNoReturn(World, org.joml.Vector3i pos, ComponentAccessor<EntityStore>)`. World coordinate of a local block = `local + selection.getX() + pos.x - selection.getAnchorX()` (same for y, z). `Store<EntityStore>` is a `ComponentAccessor`. No `IPrefabBuffer` / `PrefabUtil` needed. |
| R1 chair detection | `BlockType.getAssetMap().getIndexOrDefault(name, -1)` then `getAsset(int)`; a chair has `getSeats().size() > 0`. Vanilla `Furniture_Village_Chair` declares `Seats`. |
| R2 mount hook | `com.hypixel.hytale.component.system.RefChangeSystem<EntityStore, com.hypixel.hytale.builtin.mounts.MountedComponent>` with abstract `componentType()`, `getQuery()`, `onComponentAdded(ref, comp, store, cb)`, `onComponentSet(ref, old, new, store, cb)`, `onComponentRemoved(ref, comp, store, cb)`. Vanilla `WakeUpOnDismountSystem` returns the `ComponentType` itself from `getQuery()` (`ComponentType` implements `Query`). |
| R2 seat block | `MountedComponent.getBlockMountType()` is `com.hypixel.hytale.protocol.BlockMountType.Seat` for chairs; `MountedComponent.getMountedToBlock()` is a `Ref<ChunkStore>`; `ref.getStore().getComponent(ref, BlockMountComponent.getComponentType()).getBlockPos()` is the seat's `Vector3i`. |
| R3 nameplate | `store.ensureAndGetComponent(ref, com.hypixel.hytale.server.core.entity.nameplate.Nameplate.getComponentType()).setText(String)`, exactly what vanilla `/entity nameplate` does. |
| Camera set | `new SetServerCamera(ClientCameraView.Custom, true, ServerCameraSettings)` via `playerRef.getPacketHandler().writeNoCache(packet)`. `ServerCameraSettings` has public fields (`positionType`, `position`, `rotationType`, `rotation`, `attachedToType`, `baseFov`, ...). Vanilla `/camera topdown` sets `rotation = new Direction(yaw, pitch, roll)` with pitch `-1.5707964f` for straight down, so pitch is radians and negative looks down. |
| Camera reset | `new SetServerCamera(ClientCameraView.Custom, false, null)` (what `CameraManager.resetCamera` and `/camera reset` both send). |
| Look-at | `com.hypixel.hytale.math.vector.Rotation3f.lookAt(Vector3dc, Vector3dc)` computes yaw/pitch from a delta; `com.hypixel.hytale.server.core.util.PositionUtil.toDirectionPacket(Rotation3f)` converts to `Direction`. Argument order (from,to vs to,from) could not be read from bytecode, so config `CameraFlip` swaps it. |
| NPC spawn | `NPCPlugin.get().spawnNPCWithSpaceValidation(store, role, null, Vector3dc, Rotation3fc, TriConsumer<NPCEntity,Ref,Store>, true, false)` and `spawnNPCWithColumnProbe(store, role, null, world, x, z, y, rot, callback)` return `SpawnTestResult` (Subinator's proven ladder). |
| Store access | `world.getEntityStore().getStore()` is the `Store<EntityStore>`; `world.getWorldConfig().getUuid()`; `Universe.get().getWorld(UUID)`; `world.execute(Runnable)` runs on the world thread. |
| NPC scan | `store.forEachChunk(Archetype.of(NPCEntity.getComponentType()), (chunk, cb) -> ...)`; `chunk.size()`, `chunk.getReferenceTo(i)`, `chunk.getComponent(i, type)`; `NPCEntity.getRoleName()`; `TransformComponent.getPosition()`. |
| Despawn | `store.removeEntity(ref, RemoveReason.REMOVE)` on the world thread. |
| Roles/blocks | Vanilla roles `Kweebec_Seedling`, `Kweebec_Sproutling`, `Kweebec_Sapling` exist under `Server/NPC/Roles/Intelligent/Neutral/Kweebec/`. Blocks `Soil_Grass`, `Wood_Hardwood_Fence`, `Furniture_Village_Chair` exist. |

## Deviations from the spec (decided during research)

1. **Placement** uses `SelectionPrefabSerializer` + `BlockSelection.placeNoReturn`, not `IPrefabBuffer`/`PrefabUtil`. The pen is centred on the caller rather than cornered at their feet.
2. **Ticker** owns a daemon `ScheduledExecutorService` and marshals each tick onto the pen world via `world.execute`, instead of `world.scheduleAfter` re-arming. Reason: a scheduleAfter chain dies silently if the world unloads; the executor keeps polling and recovers when the world reloads.
3. **Presence semantics.** Roster timestamps mean "first seen since they last left" (used only to order who spawns next). The ticker sets a pen entry's `lastSeen = now` whenever the login is present in the roster, so a lurker who joined an hour ago is never despawned while still in chat.
4. **PenEntry** is keyed by login and holds the entity `Ref` (identity, as Subinator's registry does) plus `NetworkId`; no entity UUID.
5. **Config** `PenX/PenY/PenZ` = world coordinates of the interior's min corner with `PenY` the floor block; `PenSizeX/PenSizeZ` = interior size; `PenSizeY` = clear height above the floor. `PenFacing` = the direction the camera looks (from the chair side into the pen). New key `CameraFlip` (default false).
6. No `.lang` file: names come from the `Nameplate` component.
7. When membership is not ACKed, chatters only ever `Seen` and never `Part`, so they stay until restart. Logged, accepted for v1.

## File map

```
sproutwatch/
  build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew*, gradle/wrapper/*, deploy.sh, .github/workflows/gradle.yml
  src/main/resources/Sproutwatch_config.json
  src/main/resources/Server/Prefabs/Sproutwatch/sproutwatch_pen.prefab.json   (exists)
  src/main/java/dev/hytalemodding/sproutwatch/
    SproutwatchPlugin.java              wiring, bridge logger, startListener/stopListener, pen world resolver
    config/SproutwatchConfig.java       codec config + clamps
    config/PenFacing.java               NORTH/SOUTH/EAST/WEST, parse, toward
    commands/SproutwatchCommand.java    /sproutwatch channel|start|stop|status|place|clear|camera|interval|test
    twitch/RosterEvent.java             sealed Names/Join/Part/Seen
    twitch/MembershipParser.java        IRC line -> RosterEvent (pure)
    twitch/ChatRoster.java              login -> firstSeen, ignore list (thread-safe)
    twitch/TwitchMembershipClient.java  TLS IRC client, membership CAP, backoff
    pen/PenRegistry.java                login -> Entry(ref, networkId, ...) (thread-safe)
    pen/PenPlan.java                    record(spawn, despawn)
    pen/PenReconciler.java              pure diff
    pen/PenBounds.java                  pure geometry from config
    pen/PenTicker.java                  scheduler + world-thread tick
    pen/SproutSpawner.java              NPC spawn ladder + nameplate + registry
    pen/PenDespawnSystem.java           RefSystem: registry eviction on entity removal
    pen/PenClearer.java                 world-thread sweep of pen younglings
    prefab/PrefabBlock.java             record(x,y,z,name)
    prefab/PenPrefab.java               read bundled JSON, parse blocks (pure)
    prefab/PenLayout.java               interior/chair/facing analysis (pure)
    prefab/PenPlacer.java               deserialize, paste, save config
    camera/PenCamera.java               position + lookAt (pure)
    camera/CameraPackets.java           ServerCameraSettings/SetServerCamera builders
    camera/ChairCameraService.java      RefChangeSystem<EntityStore, MountedComponent> + manual toggle
  src/test/java/dev/hytalemodding/sproutwatch/
    config/SproutwatchConfigTest, config/PenFacingTest
    twitch/MembershipParserTest, twitch/ChatRosterTest, twitch/TwitchMembershipClientTest
    pen/PenRegistryTest, pen/PenReconcilerTest, pen/PenBoundsTest
    prefab/PenPrefabTest, prefab/PenLayoutTest
    camera/PenCameraTest
```

All test commands below assume the project root `~/Developer/hytale/sproutwatch`. Run a single class with `./gradlew test --tests 'dev.hytalemodding.sproutwatch.<pkg>.<Class>'`; Gradle prints `BUILD SUCCESSFUL` on pass and lists failed tests on failure.

---

### Task 1: Scaffold the project

**Files:**
- Copy from `~/Developer/hytale/mysterion-mazurka-8`: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/gradle.yml`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `deploy.sh`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/SproutwatchPlugin.java` (stub)
- Create: `src/main/resources/Sproutwatch_config.json`

- [ ] **Step 1: Switch the unborn branch to dev and copy the wrapper**

```bash
cd ~/Developer/hytale/sproutwatch
git checkout -b dev
cp ~/Developer/hytale/mysterion-mazurka-8/gradlew ~/Developer/hytale/mysterion-mazurka-8/gradlew.bat .
mkdir -p gradle/wrapper .github/workflows
cp ~/Developer/hytale/mysterion-mazurka-8/gradle/wrapper/gradle-wrapper.jar ~/Developer/hytale/mysterion-mazurka-8/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
cp ~/Developer/hytale/mysterion-mazurka-8/.github/workflows/gradle.yml .github/workflows/
chmod +x gradlew
```

- [ ] **Step 2: Write settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "AzureDoom Maven"
            url = uri("https://maven.azuredoom.com/mods")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sproutwatch"
```

- [ ] **Step 3: Write build.gradle.kts**

```kotlin
plugins {
    java
    id("com.azuredoom.hytale-tools") version "1.+"
}

tasks.withType<Javadoc>().configureEach {
    (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).addStringOption("Xdoclint:-missing", "-quiet")
}

group = project.property("group").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
}

hytaleTools {
    javaVersion = property("java_version").toString().toInt()
    hytaleVersion = property("hytale_version").toString()
    manifestServerVersion = property("manifestServerVersion").toString()
    manifestGroup = property("manifest_group").toString()
    modId = property("mod_id").toString()
    modDescription = property("mod_description").toString()
    modUrl = property("mod_url").toString()
    mainClass = property("main_class").toString()
    modCredits = property("mod_author").toString()
    manifestDependencies = property("manifest_dependencies").toString()
    manifestOptionalDependencies = property("manifest_opt_dependencies").toString()
    curseforgeId = property("curseforgeID").toString()
    disabledByDefault = property("disabled_by_default").toString().toBoolean()
    includesPack = property("includes_pack").toString().toBoolean()
    patchline = property("patchline").toString()
    injectServerJavadocsIntoSources = property("injectServerJavadocsIntoSources").toString().toBoolean()
    generateAssetsBinary = property("generateAssetsBinary").toString().toBoolean()
}

repositories {
    mavenCentral()
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(rootProject.name)
    archiveVersion.set(project.property("version").toString())
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // hytaleTools adds the Server jar to compileClasspath only; tests that touch engine types
    // (org.bson, org.joml, Ref, codecs) need it mirrored onto the test classpath.
    testImplementation(files(configurations.compileClasspath))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Write gradle.properties**

```properties
org.gradle.daemon = true
org.gradle.jvmargs = -Xmx3G
org.gradle.parallel = true
org.gradle.caching = true

java_version = 25
hytale_version = 0.6.3

group = dev.hytalemodding
manifest_group = Mertie
mod_name = Sproutwatch
main_class = dev.hytalemodding.sproutwatch.SproutwatchPlugin
mod_author = Mertie|MJSTREAMERKEEP@gmail.com|https://thinklikemike.com
mod_id = sproutwatch
mod_license = MIT
mod_description = Twitch viewers become baby Kweebecs in a pen with a fixed 3:4 camera
mod_url =
version = 0.1.0

includes_pack = true
disabled_by_default = false
patchline = release
server_version = 0.5.3
manifestServerVersion = >=0.5.3 <0.7.0
manifest_dependencies = Hytale:AssetModule=*
manifest_opt_dependencies = 
curseforgeID = 
injectServerJavadocsIntoSources = true
generateAssetsBinary = false
hytaleHomeOverride = /path/to/Hytale/install/release/package/game/latest/Assets.zip
```

- [ ] **Step 5: Write deploy.sh and make it executable**

```bash
#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "Building Sproutwatch..."
./gradlew build

VERSION=$(grep '^version' gradle.properties | sed 's/version *= *//' | tr -d ' ')
JAR="build/libs/sproutwatch-${VERSION}.jar"
MODS="$HOME/Library/Application Support/Hytale/UserData/Mods"

[ -f "$JAR" ] || { echo "Jar not found: $JAR"; ls build/libs/; exit 1; }

echo "Deploying ${JAR}..."
mkdir -p "$MODS"
rm -f "$MODS"/sproutwatch-*.jar
cp "$JAR" "$MODS/"
echo "✓ Deployed $(basename "$JAR") to $MODS"
```

Run: `chmod +x deploy.sh`

- [ ] **Step 6: Write the plugin stub**

`src/main/java/dev/hytalemodding/sproutwatch/SproutwatchPlugin.java`:

```java
package dev.hytalemodding.sproutwatch;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;

public class SproutwatchPlugin extends JavaPlugin {

    public SproutwatchPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getLogger().atInfo().log("Sproutwatch loaded");
    }
}
```

- [ ] **Step 7: Write the default config JSON**

`src/main/resources/Sproutwatch_config.json`:

```json
{
  "TwitchChannel": "",
  "AutoStartOnBoot": false,
  "TickSeconds": 60,
  "GraceSeconds": 300,
  "MaxSprouts": 30,
  "Roles": ["Kweebec_Seedling", "Kweebec_Sproutling", "Kweebec_Sapling"],
  "IgnoreUsers": ["nightbot", "streamelements", "streamlabs"],
  "PenWorld": "",
  "PenX": 0,
  "PenY": 0,
  "PenZ": 0,
  "PenSizeX": 0,
  "PenSizeY": 0,
  "PenSizeZ": 0,
  "ChairSet": false,
  "ChairX": 0,
  "ChairY": 0,
  "ChairZ": 0,
  "PenFacing": "north",
  "CameraHeight": 14.0,
  "CameraBack": 4.0,
  "CameraFov": 60.0,
  "CameraFlip": false
}
```

- [ ] **Step 8: Build and check the jar**

Run: `./gradlew build && unzip -l build/libs/sproutwatch-0.1.0.jar | grep -E 'manifest.json|SproutwatchPlugin.class|sproutwatch_pen.prefab.json'`
Expected: `BUILD SUCCESSFUL` and all three entries listed. (First build downloads the server jar; needs network.)

- [ ] **Step 9: Checkpoint**

Run: `git add -A && git status --short | head`
Expected: build files, stub, config, spec, prefab and script all staged; no commit.

---

### Task 2: Config and PenFacing

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/config/PenFacing.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/config/SproutwatchConfig.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/config/PenFacingTest.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/config/SproutwatchConfigTest.java`

- [ ] **Step 1: Write the failing PenFacing test**

```java
package dev.hytalemodding.sproutwatch.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PenFacingTest {

    @Test void parsesCaseInsensitivelyAndDefaultsToNorth() {
        assertEquals(PenFacing.SOUTH, PenFacing.parse("south"));
        assertEquals(PenFacing.EAST, PenFacing.parse(" East "));
        assertEquals(PenFacing.NORTH, PenFacing.parse("sideways"));
        assertEquals(PenFacing.NORTH, PenFacing.parse(null));
    }

    @Test void keyIsLowercaseName() {
        assertEquals("west", PenFacing.WEST.key());
    }

    @Test void towardPicksDominantAxis() {
        assertEquals(PenFacing.SOUTH, PenFacing.toward(0.5, 9.5));
        assertEquals(PenFacing.NORTH, PenFacing.toward(0.5, -9.5));
        assertEquals(PenFacing.EAST, PenFacing.toward(7, 1));
        assertEquals(PenFacing.WEST, PenFacing.toward(-7, 1));
        assertEquals(PenFacing.SOUTH, PenFacing.toward(3, 3)); // tie prefers z
    }

    @Test void deltasMatchDirections() {
        assertEquals(1, PenFacing.SOUTH.dz);
        assertEquals(-1, PenFacing.NORTH.dz);
        assertEquals(1, PenFacing.EAST.dx);
        assertEquals(-1, PenFacing.WEST.dx);
        assertEquals(0, PenFacing.SOUTH.dx);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.config.PenFacingTest'`
Expected: compilation failure, `PenFacing` not found.

- [ ] **Step 3: Write PenFacing**

```java
package dev.hytalemodding.sproutwatch.config;

import java.util.Locale;

/** Horizontal direction the pen camera looks: from the chair side into the pen. */
public enum PenFacing {
    NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

    public final int dx;
    public final int dz;

    PenFacing(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** Config form: lowercase name. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive parse; anything unrecognised (including null) is NORTH. */
    public static PenFacing parse(String s) {
        if (s == null) return NORTH;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NORTH;
        }
    }

    /** The facing whose axis dominates the (dx, dz) delta; a tie prefers the z axis. */
    public static PenFacing toward(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? EAST : WEST;
        return dz >= 0 ? SOUTH : NORTH;
    }
}
```

- [ ] **Step 4: Run the PenFacing test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.config.PenFacingTest'`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Write the failing config test**

```java
package dev.hytalemodding.sproutwatch.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SproutwatchConfigTest {

    @Test void defaultsMatchTheShippedJson() {
        SproutwatchConfig c = new SproutwatchConfig();
        assertEquals("", c.getTwitchChannel());
        assertFalse(c.isAutoStartOnBoot());
        assertEquals(60, c.getTickSeconds());
        assertEquals(300, c.getGraceSeconds());
        assertEquals(30, c.getMaxSprouts());
        assertArrayEquals(new String[]{"Kweebec_Seedling", "Kweebec_Sproutling", "Kweebec_Sapling"}, c.getRoles());
        assertEquals("", c.getPenWorld());
        assertFalse(c.isPenSet());
        assertFalse(c.isChairSet());
        assertEquals("north", c.getPenFacing());
        assertEquals(14.0, c.getCameraHeight());
        assertEquals(4.0, c.getCameraBack());
        assertEquals(60.0, c.getCameraFov());
        assertFalse(c.isCameraFlip());
    }

    @Test void clampsBadValues() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setTickSeconds(1);
        assertEquals(5, c.getTickSeconds());
        c.setGraceSeconds(-9);
        assertEquals(0, c.getGraceSeconds());
        c.setMaxSprouts(0);
        assertEquals(1, c.getMaxSprouts());
        c.setCamera(14, 4, 500);
        assertEquals(170.0, c.getCameraFov());
        c.setRoles(new String[0]);
        assertEquals(3, c.getRoles().length);
    }

    @Test void normalizesChannel() {
        assertEquals("mertie_tv", SproutwatchConfig.normalizeChannel("#Mertie_TV!"));
        assertEquals("", SproutwatchConfig.normalizeChannel(null));
        SproutwatchConfig c = new SproutwatchConfig();
        c.setTwitchChannel("@Streamer");
        assertEquals("streamer", c.getTwitchChannel());
    }

    @Test void ignoredLoginsIncludeDefaultsAndChannel() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setTwitchChannel("Streamer");
        Set<String> ignored = c.ignoredLogins();
        assertTrue(ignored.containsAll(Set.of("nightbot", "streamelements", "streamlabs", "streamer")));
        assertFalse(ignored.contains(""));
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
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.config.SproutwatchConfigTest'`
Expected: compilation failure, `SproutwatchConfig` not found.

- [ ] **Step 7: Write SproutwatchConfig**

```java
package dev.hytalemodding.sproutwatch.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Codec-backed config for Sproutwatch_config.json. Defaults here must match
 * src/main/resources/Sproutwatch_config.json. Getters clamp; the on-disk value is left as written.
 * Same BuilderCodec idiom as Subinator's SubinatorConfig (verified working on 0.6.3).
 */
public class SproutwatchConfig {

    public static final String[] DEFAULT_ROLES = {"Kweebec_Seedling", "Kweebec_Sproutling", "Kweebec_Sapling"};
    public static final String[] DEFAULT_IGNORE = {"nightbot", "streamelements", "streamlabs"};

    private volatile String twitchChannel = "";
    private volatile boolean autoStartOnBoot = false;
    private volatile int tickSeconds = 60;
    private volatile int graceSeconds = 300;
    private volatile int maxSprouts = 30;
    private volatile String[] roles = DEFAULT_ROLES.clone();
    private volatile String[] ignoreUsers = DEFAULT_IGNORE.clone();
    private volatile String penWorld = "";
    private volatile int penX = 0;
    private volatile int penY = 0;
    private volatile int penZ = 0;
    private volatile int penSizeX = 0;
    private volatile int penSizeY = 0;
    private volatile int penSizeZ = 0;
    private volatile boolean chairSet = false;
    private volatile int chairX = 0;
    private volatile int chairY = 0;
    private volatile int chairZ = 0;
    private volatile String penFacing = "north";
    private volatile double cameraHeight = 14.0;
    private volatile double cameraBack = 4.0;
    private volatile double cameraFov = 60.0;
    private volatile boolean cameraFlip = false;

    /** Package-private so tests can build defaults; the engine uses CODEC. */
    SproutwatchConfig() {}

    public static final BuilderCodec<SproutwatchConfig> CODEC =
        BuilderCodec.builder(SproutwatchConfig.class, SproutwatchConfig::new)
            .append(new KeyedCodec<>("TwitchChannel", Codec.STRING),
                (c, v, x) -> c.twitchChannel = v, (c, x) -> c.twitchChannel).add()
            .append(new KeyedCodec<>("AutoStartOnBoot", Codec.BOOLEAN),
                (c, v, x) -> c.autoStartOnBoot = v, (c, x) -> c.autoStartOnBoot).add()
            .append(new KeyedCodec<>("TickSeconds", Codec.INTEGER),
                (c, v, x) -> c.tickSeconds = v, (c, x) -> c.tickSeconds).add()
            .append(new KeyedCodec<>("GraceSeconds", Codec.INTEGER),
                (c, v, x) -> c.graceSeconds = v, (c, x) -> c.graceSeconds).add()
            .append(new KeyedCodec<>("MaxSprouts", Codec.INTEGER),
                (c, v, x) -> c.maxSprouts = v, (c, x) -> c.maxSprouts).add()
            .append(new KeyedCodec<>("Roles", Codec.STRING_ARRAY),
                (c, v, x) -> c.roles = v, (c, x) -> c.roles).add()
            .append(new KeyedCodec<>("IgnoreUsers", Codec.STRING_ARRAY),
                (c, v, x) -> c.ignoreUsers = v, (c, x) -> c.ignoreUsers).add()
            .append(new KeyedCodec<>("PenWorld", Codec.STRING),
                (c, v, x) -> c.penWorld = v, (c, x) -> c.penWorld).add()
            .append(new KeyedCodec<>("PenX", Codec.INTEGER), (c, v, x) -> c.penX = v, (c, x) -> c.penX).add()
            .append(new KeyedCodec<>("PenY", Codec.INTEGER), (c, v, x) -> c.penY = v, (c, x) -> c.penY).add()
            .append(new KeyedCodec<>("PenZ", Codec.INTEGER), (c, v, x) -> c.penZ = v, (c, x) -> c.penZ).add()
            .append(new KeyedCodec<>("PenSizeX", Codec.INTEGER), (c, v, x) -> c.penSizeX = v, (c, x) -> c.penSizeX).add()
            .append(new KeyedCodec<>("PenSizeY", Codec.INTEGER), (c, v, x) -> c.penSizeY = v, (c, x) -> c.penSizeY).add()
            .append(new KeyedCodec<>("PenSizeZ", Codec.INTEGER), (c, v, x) -> c.penSizeZ = v, (c, x) -> c.penSizeZ).add()
            .append(new KeyedCodec<>("ChairSet", Codec.BOOLEAN), (c, v, x) -> c.chairSet = v, (c, x) -> c.chairSet).add()
            .append(new KeyedCodec<>("ChairX", Codec.INTEGER), (c, v, x) -> c.chairX = v, (c, x) -> c.chairX).add()
            .append(new KeyedCodec<>("ChairY", Codec.INTEGER), (c, v, x) -> c.chairY = v, (c, x) -> c.chairY).add()
            .append(new KeyedCodec<>("ChairZ", Codec.INTEGER), (c, v, x) -> c.chairZ = v, (c, x) -> c.chairZ).add()
            .append(new KeyedCodec<>("PenFacing", Codec.STRING),
                (c, v, x) -> c.penFacing = v, (c, x) -> c.penFacing).add()
            .append(new KeyedCodec<>("CameraHeight", Codec.DOUBLE),
                (c, v, x) -> c.cameraHeight = v, (c, x) -> c.cameraHeight).add()
            .append(new KeyedCodec<>("CameraBack", Codec.DOUBLE),
                (c, v, x) -> c.cameraBack = v, (c, x) -> c.cameraBack).add()
            .append(new KeyedCodec<>("CameraFov", Codec.DOUBLE),
                (c, v, x) -> c.cameraFov = v, (c, x) -> c.cameraFov).add()
            .append(new KeyedCodec<>("CameraFlip", Codec.BOOLEAN),
                (c, v, x) -> c.cameraFlip = v, (c, x) -> c.cameraFlip).add()
            .build();

    /** IRC-safe channel form: lowercase, [a-z0-9_] only. */
    public static String normalizeChannel(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    public String getTwitchChannel() { return normalizeChannel(twitchChannel); }
    public void setTwitchChannel(String v) { twitchChannel = v; }
    public boolean isAutoStartOnBoot() { return autoStartOnBoot; }
    public int getTickSeconds() { return Math.max(5, tickSeconds); }
    public void setTickSeconds(int v) { tickSeconds = v; }
    public int getGraceSeconds() { return Math.max(0, graceSeconds); }
    public void setGraceSeconds(int v) { graceSeconds = v; }
    public int getMaxSprouts() { return Math.max(1, maxSprouts); }
    public void setMaxSprouts(int v) { maxSprouts = v; }

    /** Never empty: falls back to the defaults. Returned array must not be mutated. */
    public String[] getRoles() {
        String[] r = roles;
        return (r == null || r.length == 0) ? DEFAULT_ROLES.clone() : r;
    }
    public void setRoles(String[] v) { roles = v; }

    /** Lowercased ignore list plus the channel login itself (the streamer never gets a sprout). */
    public Set<String> ignoredLogins() {
        Set<String> out = new HashSet<>();
        String[] ig = ignoreUsers;
        if (ig != null) {
            for (String s : ig) {
                if (s == null) continue;
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) out.add(t);
            }
        }
        String ch = getTwitchChannel();
        if (!ch.isEmpty()) out.add(ch);
        return out;
    }

    public String getPenWorld() { return penWorld == null ? "" : penWorld; }
    public boolean isPenSet() { return !getPenWorld().isEmpty() && penSizeX > 0 && penSizeZ > 0; }
    public int getPenX() { return penX; }
    public int getPenY() { return penY; }
    public int getPenZ() { return penZ; }
    public int getPenSizeX() { return penSizeX; }
    public int getPenSizeY() { return penSizeY; }
    public int getPenSizeZ() { return penSizeZ; }

    /** x/y/z = world min corner of the interior (y = floor block); sizes = interior x/z and clear height. */
    public void setPen(String worldUuid, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        penWorld = worldUuid;
        penX = x; penY = y; penZ = z;
        penSizeX = sizeX; penSizeY = sizeY; penSizeZ = sizeZ;
    }

    public boolean isChairSet() { return chairSet; }
    public int getChairX() { return chairX; }
    public int getChairY() { return chairY; }
    public int getChairZ() { return chairZ; }
    public void setChair(boolean set, int x, int y, int z) {
        chairSet = set;
        chairX = x; chairY = y; chairZ = z;
    }

    public String getPenFacing() { return penFacing == null ? "north" : penFacing; }
    public void setPenFacing(String v) { penFacing = v; }

    public double getCameraHeight() { return cameraHeight; }
    public double getCameraBack() { return cameraBack; }
    public double getCameraFov() { return Math.max(10.0, Math.min(170.0, cameraFov)); }
    public void setCamera(double height, double back, double fov) {
        cameraHeight = height; cameraBack = back; cameraFov = fov;
    }
    public boolean isCameraFlip() { return cameraFlip; }
    public void setCameraFlip(boolean v) { cameraFlip = v; }
}
```

- [ ] **Step 8: Run both config tests**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.config.*'`
Expected: `BUILD SUCCESSFUL`, 9 tests pass.

- [ ] **Step 9: Checkpoint**

Run: `./gradlew test && git add -A`
Expected: all tests green, files staged.

---

### Task 3: RosterEvent and MembershipParser

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/twitch/RosterEvent.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/twitch/MembershipParser.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/twitch/MembershipParserTest.java`

- [ ] **Step 1: Write the failing parser test**

```java
package dev.hytalemodding.sproutwatch.twitch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MembershipParserTest {

    @Test void parsesNamesReplyWithChannelMarker() {
        RosterEvent e = MembershipParser.parse(":justinfan1.tmi.twitch.tv 353 justinfan1 = #streamer :Alice bob  CAROL");
        assertEquals(new RosterEvent.Names(List.of("alice", "bob", "carol")), e);
    }

    @Test void parsesNamesReplyWithoutMarker() {
        RosterEvent e = MembershipParser.parse(":x.tmi.twitch.tv 353 justinfan #chan :a b c");
        assertEquals(new RosterEvent.Names(List.of("a", "b", "c")), e);
    }

    @Test void parsesJoinPartAndPrivmsgLowercased() {
        assertEquals(new RosterEvent.Join("alice"),
            MembershipParser.parse(":Alice!alice@alice.tmi.twitch.tv JOIN #streamer"));
        assertEquals(new RosterEvent.Part("bob"),
            MembershipParser.parse(":bob!bob@bob.tmi.twitch.tv PART #streamer"));
        assertEquals(new RosterEvent.Seen("carol"),
            MembershipParser.parse(":carol!carol@carol.tmi.twitch.tv PRIVMSG #streamer :hello there"));
    }

    @Test void skipsALeadingTagBlock() {
        assertEquals(new RosterEvent.Seen("dave"),
            MembershipParser.parse("@badge-info=;color=#FF0000;display-name=Dave :dave!dave@dave.tmi.twitch.tv PRIVMSG #streamer :hi"));
    }

    @Test void returnsNullForEverythingElse() {
        assertNull(MembershipParser.parse(null));
        assertNull(MembershipParser.parse(""));
        assertNull(MembershipParser.parse("PING :tmi.twitch.tv"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv 001 justinfan1 :Welcome, GLHF!"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv 366 justinfan1 #streamer :End of /NAMES list"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv CAP * ACK :twitch.tv/membership"));
        assertNull(MembershipParser.parse(":tmi.twitch.tv JOIN #streamer"));      // no user prefix
        assertNull(MembershipParser.parse(":x.tmi.twitch.tv 353 justinfan #chan"));  // no name list
        assertNull(MembershipParser.parse("@msg-id=sub :tmi.twitch.tv USERNOTICE #streamer"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.twitch.MembershipParserTest'`
Expected: compilation failure, `RosterEvent`/`MembershipParser` not found.

- [ ] **Step 3: Write RosterEvent**

```java
package dev.hytalemodding.sproutwatch.twitch;

import java.util.List;

/** One roster change parsed from a Twitch IRC line. Logins are always lowercase. */
public sealed interface RosterEvent
    permits RosterEvent.Names, RosterEvent.Join, RosterEvent.Part, RosterEvent.Seen {

    /** A 353 NAMES reply: everyone currently in the channel (one of possibly many bursts). */
    record Names(List<String> logins) implements RosterEvent {}

    /** A viewer joined the channel. */
    record Join(String login) implements RosterEvent {}

    /** A viewer left the channel. */
    record Part(String login) implements RosterEvent {}

    /** A viewer spoke (PRIVMSG); proves presence even without membership. */
    record Seen(String login) implements RosterEvent {}
}
```

- [ ] **Step 4: Write MembershipParser**

```java
package dev.hytalemodding.sproutwatch.twitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure parser for Twitch IRC lines under the twitch.tv/membership capability.
 * Never throws: anything it does not understand is null.
 *
 * Shapes handled (a leading "@tags " block is skipped if present):
 *   :x.tmi.twitch.tv 353 nick = #chan :a b c      -> Names
 *   :a!a@a.tmi.twitch.tv JOIN #chan               -> Join
 *   :a!a@a.tmi.twitch.tv PART #chan               -> Part
 *   :a!a@a.tmi.twitch.tv PRIVMSG #chan :text      -> Seen
 */
public final class MembershipParser {

    private MembershipParser() {}

    public static RosterEvent parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String s = line;
        if (s.startsWith("@")) {
            int sp = s.indexOf(' ');
            if (sp < 0) return null;
            s = s.substring(sp + 1);
        }
        if (!s.startsWith(":")) return null;
        int sp = s.indexOf(' ');
        if (sp < 0) return null;
        String prefix = s.substring(1, sp);
        String rest = s.substring(sp + 1);
        int cmdEnd = rest.indexOf(' ');
        String command = cmdEnd < 0 ? rest : rest.substring(0, cmdEnd);

        switch (command) {
            case "353": {
                int colon = rest.indexOf(" :");
                if (colon < 0) return null;
                List<String> logins = new ArrayList<>();
                for (String n : rest.substring(colon + 2).trim().split("\\s+")) {
                    if (!n.isEmpty()) logins.add(n.toLowerCase(Locale.ROOT));
                }
                return logins.isEmpty() ? null : new RosterEvent.Names(List.copyOf(logins));
            }
            case "JOIN": {
                String login = loginOf(prefix);
                return login == null ? null : new RosterEvent.Join(login);
            }
            case "PART": {
                String login = loginOf(prefix);
                return login == null ? null : new RosterEvent.Part(login);
            }
            case "PRIVMSG": {
                String login = loginOf(prefix);
                return login == null ? null : new RosterEvent.Seen(login);
            }
            default:
                return null;
        }
    }

    /** "nick!user@host" -> "nick" lowercased; a server prefix (no '!') yields null. */
    static String loginOf(String prefix) {
        int bang = prefix.indexOf('!');
        if (bang <= 0) return null;
        String login = prefix.substring(0, bang).trim().toLowerCase(Locale.ROOT);
        return login.isEmpty() ? null : login;
    }
}
```

- [ ] **Step 5: Run the parser test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.twitch.MembershipParserTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 6: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 4: ChatRoster

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/twitch/ChatRoster.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/twitch/ChatRosterTest.java`

- [ ] **Step 1: Write the failing roster test**

```java
package dev.hytalemodding.sproutwatch.twitch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChatRosterTest {

    private static ChatRoster roster(String... ignored) {
        return new ChatRoster(() -> Set.of(ignored));
    }

    @Test void namesJoinAndSeenAddPartRemoves() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Names(List.of("a", "b")), 100);
        r.apply(new RosterEvent.Join("c"), 200);
        r.apply(new RosterEvent.Seen("d"), 300);
        r.apply(new RosterEvent.Part("b"), 400);
        assertEquals(Map.of("a", 100L, "c", 200L, "d", 300L), r.snapshot());
        assertEquals(3, r.size());
    }

    @Test void firstSightingWinsUntilTheyLeave() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Join("a"), 100);
        r.apply(new RosterEvent.Seen("a"), 200);
        r.apply(new RosterEvent.Names(List.of("a")), 300);
        assertEquals(100L, r.snapshot().get("a"));
        r.apply(new RosterEvent.Part("a"), 400);
        r.apply(new RosterEvent.Join("a"), 500);
        assertEquals(500L, r.snapshot().get("a"));
    }

    @Test void dropsIgnoredAndJustinfanLogins() {
        ChatRoster r = roster("nightbot");
        r.apply(new RosterEvent.Names(List.of("nightbot", "justinfan12345", "real")), 1);
        r.apply(new RosterEvent.Join("NightBot".toLowerCase()), 2);
        r.apply(new RosterEvent.Seen("justinfan9"), 3);
        assertEquals(Set.of("real"), r.snapshot().keySet());
    }

    @Test void snapshotIsImmutableAndClearEmpties() {
        ChatRoster r = roster();
        r.apply(new RosterEvent.Join("a"), 1);
        Map<String, Long> snap = r.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.put("b", 2L));
        r.clear();
        assertEquals(0, r.size());
        assertEquals(1, snap.size());
    }

    @Test void nullEventIsIgnored() {
        ChatRoster r = roster();
        r.apply(null, 1);
        assertEquals(0, r.size());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.twitch.ChatRosterTest'`
Expected: compilation failure, `ChatRoster` not found.

- [ ] **Step 3: Write ChatRoster**

```java
package dev.hytalemodding.sproutwatch.twitch;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Who is in chat right now: login -> millis when we first saw them since they last left.
 * Written from the Twitch client thread, read (snapshot) from the pen ticker. The timestamp is
 * only used to order who gets a sprout next; presence is membership in the map.
 */
public final class ChatRoster {

    private final ConcurrentHashMap<String, Long> firstSeen = new ConcurrentHashMap<>();
    private final Supplier<Set<String>> ignored;

    /** @param ignored lowercase logins that never enter the roster (read on every apply, so config edits take effect live). */
    public ChatRoster(Supplier<Set<String>> ignored) {
        this.ignored = ignored;
    }

    public void apply(RosterEvent event, long now) {
        if (event == null) return;
        Set<String> skip = ignored.get();
        switch (event) {
            case RosterEvent.Names n -> { for (String l : n.logins()) add(l, now, skip); }
            case RosterEvent.Join j -> add(j.login(), now, skip);
            case RosterEvent.Seen s -> add(s.login(), now, skip);
            case RosterEvent.Part p -> firstSeen.remove(p.login());
        }
    }

    private void add(String login, long now, Set<String> skip) {
        if (login == null || login.isEmpty()) return;
        if (login.startsWith("justinfan")) return;
        if (skip != null && skip.contains(login)) return;
        firstSeen.putIfAbsent(login, now);
    }

    /** Immutable point-in-time copy. */
    public Map<String, Long> snapshot() {
        return Map.copyOf(firstSeen);
    }

    public int size() {
        return firstSeen.size();
    }

    public void clear() {
        firstSeen.clear();
    }
}
```

- [ ] **Step 4: Run the roster test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.twitch.ChatRosterTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 5: TwitchMembershipClient

Fork of Subinator's `TwitchChatClient` (`~/Developer/hytale/subinator/src/main/java/dev/hytalemodding/subinator/twitch/TwitchChatClient.java`): same socket, thread, backoff and PING handling; different CAP, parser and sink.

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/twitch/TwitchMembershipClient.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/twitch/TwitchMembershipClientTest.java`

- [ ] **Step 1: Write the failing client test (mock IRC server on localhost)**

```java
package dev.hytalemodding.sproutwatch.twitch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.SocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TwitchMembershipClientTest {

    private static final String CAP_ACK = ":tmi.twitch.tv CAP * ACK :twitch.tv/membership twitch.tv/commands";

    private static Logger warningsInto(String name, List<String> sink) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) sink.add(r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    @Test
    @Timeout(15)
    void handshakesAppliesRosterEventsAndPongs() throws Exception {
        ChatRoster roster = new ChatRoster(() -> Set.of());
        CompletableFuture<List<String>> received = new CompletableFuture<>();
        CompletableFuture<String> pong = new CompletableFuture<>();
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = warningsInto("tmc-1", warnings);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread mock = new Thread(() -> {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    received.complete(List.of(in.readLine(), in.readLine(), in.readLine()));
                    out.println(CAP_ACK);
                    out.println(":justinfan1.tmi.twitch.tv 353 justinfan1 = #streamer :alice bob");
                    out.println(":carol!carol@carol.tmi.twitch.tv JOIN #streamer");
                    out.println(":dave!dave@dave.tmi.twitch.tv PRIVMSG #streamer :hi");
                    out.println(":bob!bob@bob.tmi.twitch.tv PART #streamer");
                    out.println("PING :tmi.twitch.tv");
                    pong.complete(in.readLine());
                } catch (IOException e) {
                    received.completeExceptionally(e);
                    pong.completeExceptionally(e);
                }
            });
            mock.start();

            TwitchMembershipClient client = new TwitchMembershipClient(
                "streamer", roster, logger, SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort(), 5_000);
            client.start();
            try {
                List<String> hs = received.get(10, TimeUnit.SECONDS);
                assertEquals("CAP REQ :twitch.tv/membership twitch.tv/commands", hs.get(0));
                assertTrue(hs.get(1).startsWith("NICK justinfan"));
                assertEquals("JOIN #streamer", hs.get(2));
                // The PONG is written only after every earlier line was read and applied.
                assertEquals("PONG :tmi.twitch.tv", pong.get(10, TimeUnit.SECONDS));
                assertEquals(Set.of("alice", "carol", "dave"), roster.snapshot().keySet());
                assertTrue(client.isMembershipAcked());
                assertEquals(0, warnings.stream().filter(w -> w != null && w.contains("membership")).count(),
                    "no membership warning when ACK arrives, got: " + warnings);
            } finally {
                client.stop();
                mock.join(5_000);
            }
        }
    }

    @Test
    @Timeout(15)
    void warnsOnceWhenMembershipNeverAcked() throws Exception {
        ChatRoster roster = new ChatRoster(() -> Set.of());
        CompletableFuture<String> pong = new CompletableFuture<>();
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = warningsInto("tmc-2", warnings);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread mock = new Thread(() -> {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    in.readLine(); in.readLine(); in.readLine();
                    out.println(":tmi.twitch.tv 001 justinfan1 :Welcome");
                    out.println(":tmi.twitch.tv 376 justinfan1 :End of /MOTD"); // warning trigger
                    out.println(":erin!erin@erin.tmi.twitch.tv PRIVMSG #streamer :still here");
                    out.println("PING :tmi.twitch.tv");
                    pong.complete(in.readLine());
                } catch (IOException e) {
                    pong.completeExceptionally(e);
                }
            });
            mock.start();

            TwitchMembershipClient client = new TwitchMembershipClient(
                "streamer", roster, logger, SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort(), 5_000);
            client.start();
            try {
                assertEquals("PONG :tmi.twitch.tv", pong.get(10, TimeUnit.SECONDS));
                assertFalse(client.isMembershipAcked());
                assertEquals(1, warnings.stream().filter(w -> w != null && w.contains("membership")).count(),
                    "exactly one membership warning, got: " + warnings);
                assertEquals(Set.of("erin"), roster.snapshot().keySet(), "speakers still count without membership");
            } finally {
                client.stop();
                mock.join(5_000);
            }
        }
    }

    @Test
    void isOneShot() {
        ChatRoster roster = new ChatRoster(() -> Set.of());
        TwitchMembershipClient client = new TwitchMembershipClient(
            "streamer", roster, Logger.getLogger("tmc-3"), SocketFactory.getDefault(), "127.0.0.1", 1, 5_000);
        client.start();
        client.stop();
        assertThrows(IllegalStateException.class, client::start);
        assertEquals("stopped", client.getState());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClientTest'`
Expected: compilation failure, `TwitchMembershipClient` not found.

- [ ] **Step 3: Write TwitchMembershipClient**

```java
package dev.hytalemodding.sproutwatch.twitch;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Anonymous read-only Twitch chat client that keeps a ChatRoster current. One daemon thread;
 * reconnects with exponential backoff (initial delay doubling to 5 min, reset once a connection
 * survives 60 s). Requests twitch.tv/membership so JOIN/PART/NAMES arrive; if Twitch never ACKs
 * it, only chatters who speak will ever appear, which is warned once per connection.
 * One-shot: construct a new client per start; stop() is terminal.
 * Forked from Subinator's TwitchChatClient (same transport, proven on 0.6.3).
 */
public final class TwitchMembershipClient {

    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long READ_TIMEOUT_MS = 600_000; // Twitch pings ~every 5 min
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long HEALTHY_CONNECTION_MS = 60_000;

    private final String channel;
    private final ChatRoster roster;
    private final Logger logger;
    private final SocketFactory socketFactory;
    private final String host;
    private final int port;
    private final long initialBackoffMs;
    private final AtomicLong eventCount = new AtomicLong();

    private volatile boolean running;
    private volatile Thread thread;
    private volatile Socket socket;
    private volatile String state = "stopped";
    private volatile boolean membershipAcked;
    private boolean started;

    /** Production client: TLS to irc.chat.twitch.tv:6697. */
    public TwitchMembershipClient(String channel, ChatRoster roster, Logger logger) {
        this(channel, roster, logger, SSLSocketFactory.getDefault(), "irc.chat.twitch.tv", 6697, 5_000);
    }

    TwitchMembershipClient(String channel, ChatRoster roster, Logger logger,
                           SocketFactory socketFactory, String host, int port, long initialBackoffMs) {
        this.channel = SproutwatchConfig.normalizeChannel(channel);
        this.roster = roster;
        this.logger = logger;
        this.socketFactory = socketFactory;
        this.host = host;
        this.port = port;
        this.initialBackoffMs = initialBackoffMs;
    }

    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("TwitchMembershipClient is one-shot; construct a new instance");
        }
        started = true;
        running = true;
        state = "connecting";
        thread = new Thread(this::runLoop, "sproutwatch-twitch");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        state = "stopped";
        closeSocket();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    public boolean isRunning() { return running; }
    public String getState() { return state; }
    public String getChannel() { return channel; }
    public long getEventCount() { return eventCount.get(); }
    public boolean isMembershipAcked() { return membershipAcked; }
    public int getRosterSize() { return roster.size(); }

    private void runLoop() {
        long backoffMs = initialBackoffMs;
        try {
            while (running) {
                long startedAt = System.currentTimeMillis();
                try {
                    connectAndRead();
                } catch (IOException | RuntimeException e) {
                    if (!running) break;
                    state = "reconnecting";
                    logger.log(Level.WARNING, "Twitch connection lost ({0}); retrying in {1}s",
                            new Object[]{e.getMessage(), backoffMs / 1000});
                }
                if (!running) break;
                long connectedMs = System.currentTimeMillis() - startedAt;
                backoffMs = connectedMs >= HEALTHY_CONNECTION_MS
                        ? initialBackoffMs
                        : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            if (running) {
                logger.severe("Twitch listener thread died unexpectedly - run /sproutwatch stop then start");
                running = false;
            }
            state = "stopped";
        }
    }

    private void connectAndRead() throws IOException {
        Socket s = socketFactory.createSocket();
        try {
            s.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT_MS);
            socket = s;
            s.setSoTimeout((int) READ_TIMEOUT_MS);
            if (!running) return;

            membershipAcked = false; // per connection
            boolean warnedNoMembership = false;
            int preAckLines = 0;
            try (BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

                out.println("CAP REQ :twitch.tv/membership twitch.tv/commands");
                out.println("NICK justinfan" + ThreadLocalRandom.current().nextInt(10_000, 100_000));
                out.println("JOIN #" + channel);
                state = "connected to #" + channel;
                logger.info("Sproutwatch watching Twitch channel #" + channel);

                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith("PING")) {
                        out.println("PONG" + line.substring(4));
                        continue;
                    }
                    if (!membershipAcked) {
                        if (line.contains(" CAP ") && line.contains(" ACK ") && line.contains("twitch.tv/membership")) {
                            membershipAcked = true;
                            continue;
                        }
                        preAckLines++;
                        if (!warnedNoMembership && (line.contains(" 376 ") || preAckLines >= 50)) {
                            warnedNoMembership = true;
                            logger.warning("Twitch has not acknowledged the membership capability - JOIN/PART are missing, so only viewers who chat will get a sprout");
                        }
                    }
                    RosterEvent event = MembershipParser.parse(line);
                    if (event == null) continue;
                    eventCount.incrementAndGet();
                    try {
                        roster.apply(event, System.currentTimeMillis());
                    } catch (RuntimeException e) {
                        logger.log(Level.WARNING, "Roster update failed", e);
                    }
                }
            }
        } finally {
            if (socket == s) socket = null;
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
```

- [ ] **Step 4: Run the client test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClientTest'`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 6: PenRegistry

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenRegistry.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/pen/PenRegistryTest.java`

`Ref<EntityStore>` has no equals/hashCode, so it is an identity key. In tests a `Ref` with a null store is a safe opaque token (the registry only calls `isValid()`, never dereferences the store), the same double Subinator's `BossRegistryTest` uses.

- [ ] **Step 1: Write the failing registry test**

```java
package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PenRegistryTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final AtomicInteger nextIndex = new AtomicInteger();

    /** Null-store Ref: identity token only. toString overridden because Ref's own dereferences the store. */
    private static final class TestRef extends Ref<EntityStore> {
        TestRef(int index) { super(null, index); }
        @Override public String toString() { return "TestRef@" + System.identityHashCode(this); }
    }

    private static Ref<EntityStore> ref() { return new TestRef(nextIndex.getAndIncrement()); }

    private static PenRegistry.Entry entry(String login, Ref<EntityStore> ref, long t) {
        return new PenRegistry.Entry(login, ref, 7, WORLD, t, t);
    }

    @Test void putGetRemoveByLogin() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> a = ref();
        r.put(entry("alice", a, 100));
        assertTrue(r.contains("alice"));
        assertSame(a, r.get("alice").ref());
        assertEquals(1, r.size());
        PenRegistry.Entry removed = r.remove("alice");
        assertEquals("alice", removed.login());
        assertNull(r.remove("alice"));
        assertTrue(r.isEmpty());
    }

    @Test void removeByRefFindsTheLogin() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> a = ref();
        r.put(entry("alice", a, 100));
        r.put(entry("bob", ref(), 100));
        assertEquals("alice", r.removeByRef(a).login());
        assertNull(r.removeByRef(a));
        assertNull(r.removeByRef(ref()));
        assertEquals(1, r.size());
        assertTrue(r.contains("bob"));
    }

    @Test void putReplacesAndReindexesRef() {
        PenRegistry r = new PenRegistry();
        Ref<EntityStore> old = ref();
        Ref<EntityStore> fresh = ref();
        r.put(entry("alice", old, 100));
        r.put(entry("alice", fresh, 200));
        assertEquals(1, r.size());
        assertNull(r.removeByRef(old));
        assertEquals("alice", r.removeByRef(fresh).login());
    }

    @Test void touchUpdatesLastSeenOnlyForKnownLogins() {
        PenRegistry r = new PenRegistry();
        r.put(entry("alice", ref(), 100));
        r.touch("alice", 500);
        r.touch("ghost", 500);
        assertEquals(Map.of("alice", 500L), r.lastSeenMap());
        assertEquals(100L, r.get("alice").spawnedAt());
    }

    @Test void snapshotAndClear() {
        PenRegistry r = new PenRegistry();
        r.put(entry("b", ref(), 1));
        r.put(entry("a", ref(), 2));
        List<PenRegistry.Entry> snap = r.snapshot();
        assertEquals(2, snap.size());
        assertThrows(UnsupportedOperationException.class, () -> snap.add(entry("c", ref(), 3)));
        List<PenRegistry.Entry> cleared = r.clear();
        assertEquals(2, cleared.size());
        assertTrue(r.isEmpty());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.pen.PenRegistryTest'`
Expected: compilation failure, `PenRegistry` not found.

- [ ] **Step 3: Write PenRegistry**

```java
package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bookkeeping of live sprouts: login -> Entry. Plain synchronized map (no listeners in v1).
 * Ref&lt;EntityStore&gt; is an identity key (no equals/hashCode override), the same assumption
 * Subinator's BossRegistry relies on: the engine hands back the same Ref instance in the spawn
 * callback and in system callbacks. No store access happens here.
 */
public final class PenRegistry {

    /** One live sprout. networkId is -1 when the entity had no NetworkId at spawn time. */
    public record Entry(String login, Ref<EntityStore> ref, int networkId, UUID worldUuid,
                        long spawnedAt, long lastSeen) {
        public Entry withLastSeen(long t) {
            return new Entry(login, ref, networkId, worldUuid, spawnedAt, t);
        }
    }

    private final Map<String, Entry> byLogin = new HashMap<>();
    private final Map<Ref<EntityStore>, String> byRef = new HashMap<>();

    public synchronized void put(Entry entry) {
        Entry previous = byLogin.put(entry.login(), entry);
        if (previous != null) byRef.remove(previous.ref());
        byRef.put(entry.ref(), entry.login());
    }

    public synchronized Entry get(String login) {
        return byLogin.get(login);
    }

    public synchronized boolean contains(String login) {
        return byLogin.containsKey(login);
    }

    public synchronized Entry remove(String login) {
        Entry e = byLogin.remove(login);
        if (e != null) byRef.remove(e.ref());
        return e;
    }

    /** For PenDespawnSystem: the entity vanished for any reason. Null if ref is not ours. */
    public synchronized Entry removeByRef(Ref<EntityStore> ref) {
        if (ref == null) return null;
        String login = byRef.remove(ref);
        return login == null ? null : byLogin.remove(login);
    }

    /** Marks a login as present at time now; unknown logins are ignored. */
    public synchronized void touch(String login, long now) {
        Entry e = byLogin.get(login);
        if (e != null) byLogin.put(login, e.withLastSeen(now));
    }

    /** login -> lastSeen, the shape PenReconciler wants. */
    public synchronized Map<String, Long> lastSeenMap() {
        Map<String, Long> out = new HashMap<>();
        for (Entry e : byLogin.values()) out.put(e.login(), e.lastSeen());
        return out;
    }

    public synchronized int size() {
        return byLogin.size();
    }

    public synchronized boolean isEmpty() {
        return byLogin.isEmpty();
    }

    /** Immutable point-in-time copy. */
    public synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(byLogin.values()));
    }

    /** Empties the registry and returns what was in it (for /sproutwatch clear). */
    public synchronized List<Entry> clear() {
        List<Entry> out = List.copyOf(new ArrayList<>(byLogin.values()));
        byLogin.clear();
        byRef.clear();
        return out;
    }
}
```

- [ ] **Step 4: Run the registry test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.pen.PenRegistryTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 7: PenPlan and PenReconciler

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenPlan.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenReconciler.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/pen/PenReconcilerTest.java`

- [ ] **Step 1: Write the failing reconciler test**

```java
package dev.hytalemodding.sproutwatch.pen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PenReconcilerTest {

    private static final long NOW = 1_000_000L;
    private static final long GRACE = 300_000L;

    @Test void emptyInputsPlanNothing() {
        PenPlan p = PenReconciler.reconcile(Map.of(), Map.of(), 30, GRACE, NOW);
        assertEquals(Optional.empty(), p.spawn());
        assertEquals(List.of(), p.despawn());
    }

    @Test void spawnPicksTheEarliestArrival() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L, "b", 50L), Map.of(), 30, GRACE, NOW);
        assertEquals(Optional.of("b"), p.spawn());
    }

    @Test void spawnTieBreaksAlphabetically() {
        PenPlan p = PenReconciler.reconcile(Map.of("b", 100L, "a", 100L), Map.of(), 30, GRACE, NOW);
        assertEquals(Optional.of("a"), p.spawn());
    }

    @Test void spawnSkipsLoginsAlreadyInThePen() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L), Map.of("a", NOW), 30, GRACE, NOW);
        assertEquals(Optional.empty(), p.spawn());
        assertEquals(List.of(), p.despawn());
    }

    @Test void capBlocksSpawn() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L), Map.of("x", NOW), 1, GRACE, NOW);
        assertEquals(Optional.empty(), p.spawn());
    }

    @Test void despawnsOnlyPastTheGraceWindow() {
        Map<String, Long> pen = Map.of("stale", NOW - GRACE - 1, "fresh", NOW - GRACE + 1, "edge", NOW - GRACE);
        PenPlan p = PenReconciler.reconcile(Map.of(), pen, 30, GRACE, NOW);
        assertEquals(List.of("stale"), p.despawn());
    }

    @Test void despawnFreesACapSlotInTheSameTick() {
        PenPlan p = PenReconciler.reconcile(Map.of("a", 100L), Map.of("x", NOW - GRACE - 1), 1, GRACE, NOW);
        assertEquals(List.of("x"), p.despawn());
        assertEquals(Optional.of("a"), p.spawn());
    }

    @Test void despawnListIsSortedAndOnlyOneSpawnPerTick() {
        Map<String, Long> pen = Map.of("z", 0L, "m", 0L, "c", 0L);
        PenPlan p = PenReconciler.reconcile(Map.of("q", 1L, "r", 2L), pen, 30, GRACE, NOW);
        assertEquals(List.of("c", "m", "z"), p.despawn());
        assertEquals(Optional.of("q"), p.spawn());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.pen.PenReconcilerTest'`
Expected: compilation failure, `PenPlan`/`PenReconciler` not found.

- [ ] **Step 3: Write PenPlan and PenReconciler**

`PenPlan.java`:

```java
package dev.hytalemodding.sproutwatch.pen;

import java.util.List;
import java.util.Optional;

/** What one tick should do: at most one spawn, any number of despawns (sorted by login). */
public record PenPlan(Optional<String> spawn, List<String> despawn) {}
```

`PenReconciler.java`:

```java
package dev.hytalemodding.sproutwatch.pen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure diff between chat and pen.
 *   roster: login -> firstSeen (ordering only; presence = key present)
 *   pen:    login -> lastSeen  (PenTicker sets this to now for every login still in the roster)
 * despawn = pen logins whose lastSeen is older than graceMillis.
 * spawn   = if the pen has room after despawns, the roster login not in the pen with the smallest
 *           firstSeen (ties by login), so nobody waits forever.
 */
public final class PenReconciler {

    private PenReconciler() {}

    public static PenPlan reconcile(Map<String, Long> roster, Map<String, Long> pen,
                                    int cap, long graceMillis, long now) {
        List<String> despawn = new ArrayList<>();
        long cutoff = now - graceMillis;
        for (Map.Entry<String, Long> e : pen.entrySet()) {
            if (e.getValue() < cutoff) despawn.add(e.getKey());
        }
        despawn.sort(Comparator.naturalOrder());

        Optional<String> spawn = Optional.empty();
        if (pen.size() - despawn.size() < cap) {
            spawn = roster.entrySet().stream()
                .filter(e -> !pen.containsKey(e.getKey()))
                .min(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
        }
        return new PenPlan(spawn, List.copyOf(despawn));
    }
}
```

- [ ] **Step 4: Run the reconciler test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.pen.PenReconcilerTest'`
Expected: `BUILD SUCCESSFUL`, 8 tests pass.

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 8: PenBounds

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenBounds.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/pen/PenBoundsTest.java`

- [ ] **Step 1: Write the failing bounds test**

```java
package dev.hytalemodding.sproutwatch.pen;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfigAccess;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PenBoundsTest {

    // interior min corner (10, floor 64, 20), 12 x 16, 4 blocks of clear air above the floor
    private static final PenBounds B = new PenBounds(10, 64, 20, 12, 16, 4);

    @Test void geometry() {
        assertTrue(B.isSet());
        assertEquals(21, B.maxX());
        assertEquals(35, B.maxZ());
        assertEquals(16.0, B.centerX());
        assertEquals(28.0, B.centerZ());
        assertEquals(65.0, B.feetY());
        assertEquals(new Vector3d(16.0, 65.0, 28.0), B.center());
    }

    @Test void randomPointsLandOnBlockCentresInsideTheInterior() {
        Random r = new Random(42);
        for (int i = 0; i < 500; i++) {
            Vector3d p = B.randomPoint(r);
            assertTrue(p.x >= 10.5 && p.x <= 21.5, "x " + p.x);
            assertTrue(p.z >= 20.5 && p.z <= 35.5, "z " + p.z);
            assertEquals(65.0, p.y);
            assertEquals(0.5, p.x - Math.floor(p.x));
            assertEquals(0.5, p.z - Math.floor(p.z));
        }
    }

    @Test void containsUsesMarginHorizontallyAndFloorToClearHeightVertically() {
        assertTrue(B.contains(10.5, 65, 20.5, 0));
        assertTrue(B.contains(21.9, 65, 35.9, 0));
        assertFalse(B.contains(9.5, 65, 20.5, 0));
        assertTrue(B.contains(9.5, 65, 20.5, 1));     // fence tile with margin
        assertFalse(B.contains(16, 63.9, 28, 0));     // below floor
        assertTrue(B.contains(16, 64, 28, 0));        // floor block
        assertTrue(B.contains(16, 70, 28, 0));        // floor + clear + 2
        assertFalse(B.contains(16, 70.1, 28, 0));
    }

    @Test void fromConfigAndUnsetPen() {
        SproutwatchConfig c = new SproutwatchConfigAccess().fresh();
        assertFalse(PenBounds.fromConfig(c).isSet());
        c.setPen("w", 10, 64, 20, 12, 4, 16);
        assertEquals(B, PenBounds.fromConfig(c));
    }
}
```

Also create the tiny cross-package accessor `src/test/java/dev/hytalemodding/sproutwatch/config/SproutwatchConfigAccess.java` (the config constructor is package-private):

```java
package dev.hytalemodding.sproutwatch.config;

/** Test helper: lets other test packages build a default config. */
public final class SproutwatchConfigAccess {
    public SproutwatchConfig fresh() {
        return new SproutwatchConfig();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.pen.PenBoundsTest'`
Expected: compilation failure, `PenBounds` not found.

- [ ] **Step 3: Write PenBounds**

```java
package dev.hytalemodding.sproutwatch.pen;

import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3d;

import java.util.Random;

/**
 * The pen interior in world coordinates. minX/minZ = interior min corner, floorY = the floor
 * block's y (NPC feet stand at floorY + 1), sizeX/sizeZ = interior size, clearHeight = air
 * blocks above the floor. Pure: derived from config, no world access.
 */
public record PenBounds(int minX, int floorY, int minZ, int sizeX, int sizeZ, int clearHeight) {

    public static PenBounds fromConfig(SproutwatchConfig c) {
        return new PenBounds(c.getPenX(), c.getPenY(), c.getPenZ(), c.getPenSizeX(), c.getPenSizeZ(), c.getPenSizeY());
    }

    public boolean isSet() {
        return sizeX > 0 && sizeZ > 0;
    }

    public int maxX() { return minX + sizeX - 1; }
    public int maxZ() { return minZ + sizeZ - 1; }
    public double centerX() { return minX + sizeX / 2.0; }
    public double centerZ() { return minZ + sizeZ / 2.0; }
    public double feetY() { return floorY + 1.0; }

    public Vector3d center() {
        return new Vector3d(centerX(), feetY(), centerZ());
    }

    /** Centre of a uniformly random interior block, at floor level. */
    public Vector3d randomPoint(Random random) {
        double x = minX + random.nextInt(sizeX) + 0.5;
        double z = minZ + random.nextInt(sizeZ) + 0.5;
        return new Vector3d(x, feetY(), z);
    }

    /** Inside the interior widened by margin on x/z, from the floor block up to clearHeight + 2. */
    public boolean contains(double x, double y, double z, double margin) {
        return x >= minX - margin && x < minX + sizeX + margin
            && z >= minZ - margin && z < minZ + sizeZ + margin
            && y >= floorY && y <= floorY + clearHeight + 2;
    }
}
```

- [ ] **Step 4: Run the bounds test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.pen.PenBoundsTest'`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 9: PrefabBlock, PenPrefab and PenLayout

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/prefab/PrefabBlock.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/prefab/PenPrefab.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/prefab/PenLayout.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/prefab/PenPrefabTest.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/prefab/PenLayoutTest.java`

The bundled prefab (`src/main/resources/Server/Prefabs/Sproutwatch/sproutwatch_pen.prefab.json`, generated by `scripts/gen_pen_prefab.py`) has: floor `Soil_Grass` at y=0 over x 0..17, z 0..13; fence ring at y=1 only (Hytale fences are one block tall; straight runs rotation 0 along x / 1 along z, four `*Wood_Hardwood_Fence_State_Definitions_Corner` corners); interior x 1..16, z 1..12 filled with `Empty` at y=1..4; chair `Furniture_Village_Chair` at (8,1,-1) with `Empty` above it. The layout tests read that real file, so a regenerated prefab is checked automatically.

- [ ] **Step 1: Write the failing PenPrefab test**

```java
package dev.hytalemodding.sproutwatch.prefab;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PenPrefabTest {

    @Test void parsesBlocksFromInlineJson() {
        String json = """
            {"version": 8, "blockIdVersion": 11, "anchorX": 0, "anchorY": 0, "anchorZ": 0,
             "blocks": [
               {"x": 1, "y": 2, "z": 3, "name": "Soil_Grass"},
               {"x": -1, "y": 0, "z": 4, "name": "Furniture_Village_Chair", "rotation": 2}
             ],
             "entities": []}
            """;
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        assertEquals(List.of(
            new PrefabBlock(1, 2, 3, "Soil_Grass"),
            new PrefabBlock(-1, 0, 4, "Furniture_Village_Chair")), blocks);
    }

    @Test void bundledPrefabIsOnTheClasspathAndParses() throws Exception {
        String json = PenPrefab.readBundledJson();
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        assertTrue(blocks.size() > 500, "expected a full pen, got " + blocks.size());
        assertTrue(blocks.stream().anyMatch(b -> b.name().equals("Furniture_Village_Chair")));
        assertTrue(blocks.stream().anyMatch(b -> b.name().equals("Wood_Hardwood_Fence")));
        assertTrue(blocks.stream().anyMatch(b -> b.name().equals("Empty")));
    }

    @Test void rejectsJsonWithoutBlocks() {
        assertThrows(IllegalArgumentException.class, () -> PenPrefab.parseBlocks("{\"version\": 8}"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.prefab.PenPrefabTest'`
Expected: compilation failure, `PrefabBlock`/`PenPrefab` not found.

- [ ] **Step 3: Write PrefabBlock and PenPrefab**

`PrefabBlock.java`:

```java
package dev.hytalemodding.sproutwatch.prefab;

/** One block of the bundled prefab in prefab-local coordinates. */
public record PrefabBlock(int x, int y, int z, String name) {}
```

`PenPrefab.java`:

```java
package dev.hytalemodding.sproutwatch.prefab;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the bundled pen prefab. The file is plain Hytale prefab JSON (version 8), the same
 * format the engine's SelectionPrefabSerializer.deserialize(BsonDocument) consumes, so the
 * placer hands the parsed document straight to the engine while PenLayout analyses this
 * lightweight block list. org.bson ships inside HytaleServer.jar.
 */
public final class PenPrefab {

    public static final String RESOURCE = "/Server/Prefabs/Sproutwatch/sproutwatch_pen.prefab.json";

    private PenPrefab() {}

    public static String readBundledJson() throws IOException {
        try (InputStream in = PenPrefab.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("Bundled prefab missing from jar: " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static BsonDocument parseDocument(String json) {
        return BsonDocument.parse(json);
    }

    /** @throws IllegalArgumentException when the document has no "blocks" array. */
    public static List<PrefabBlock> parseBlocks(String json) {
        BsonDocument doc = parseDocument(json);
        BsonValue blocks = doc.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            throw new IllegalArgumentException("Prefab JSON has no \"blocks\" array");
        }
        BsonArray arr = blocks.asArray();
        List<PrefabBlock> out = new ArrayList<>(arr.size());
        for (BsonValue v : arr) {
            BsonDocument b = v.asDocument();
            out.add(new PrefabBlock(
                b.getInt32("x").getValue(),
                b.getInt32("y").getValue(),
                b.getInt32("z").getValue(),
                b.getString("name").getValue()));
        }
        return List.copyOf(out);
    }
}
```

- [ ] **Step 4: Run the PenPrefab test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.prefab.PenPrefabTest'`
Expected: `BUILD SUCCESSFUL`, 3 tests pass. If `org.bson` is not found, the compileClasspath mirror in `build.gradle.kts` is missing; fix that rather than adding a dependency.

- [ ] **Step 5: Write the failing PenLayout test**

```java
package dev.hytalemodding.sproutwatch.prefab;

import dev.hytalemodding.sproutwatch.config.PenFacing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PenLayoutTest {

    private static boolean isChair(String name) {
        return name.contains("Chair");
    }

    @Test void analysesTheBundledPen() throws Exception {
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(PenPrefab.readBundledJson());
        PenLayout l = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(0, l.floorY());
        assertEquals(4, l.clearHeight());
        assertEquals(1, l.interiorMinX());
        assertEquals(1, l.interiorMinZ());
        assertEquals(12, l.sizeX());
        assertEquals(16, l.sizeZ());
        assertTrue(l.chairFound());
        assertEquals(6, l.chairX());
        assertEquals(1, l.chairY());
        assertEquals(-1, l.chairZ());
        assertEquals(PenFacing.SOUTH, l.facing());
    }

    @Test void fallsBackToShrunkBoundingBoxWithoutEmptyBlocks() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 5, 0, "Stone"), new PrefabBlock(9, 5, 0, "Stone"),
            new PrefabBlock(0, 5, 7, "Stone"), new PrefabBlock(9, 5, 7, "Stone"),
            new PrefabBlock(4, 7, 3, "Stone"));
        PenLayout l = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(5, l.floorY());
        assertEquals(2, l.clearHeight());
        assertEquals(1, l.interiorMinX());
        assertEquals(1, l.interiorMinZ());
        assertEquals(8, l.sizeX());
        assertEquals(6, l.sizeZ());
        assertFalse(l.chairFound());
        assertEquals(PenFacing.NORTH, l.facing());
    }

    @Test void facingPointsFromTheChairIntoThePen() {
        List<PrefabBlock> blocks = List.of(
            new PrefabBlock(0, 0, 0, "Soil"),
            new PrefabBlock(1, 1, 1, "Empty"), new PrefabBlock(4, 1, 1, "Empty"),
            new PrefabBlock(1, 1, 4, "Empty"), new PrefabBlock(4, 1, 4, "Empty"),
            new PrefabBlock(6, 1, 2, "Furniture_Village_Chair"));
        PenLayout l = PenLayout.analyze(blocks, PenLayoutTest::isChair);
        assertEquals(PenFacing.WEST, l.facing());   // chair east of the pen, camera looks west
    }

    @Test void rejectsEmptyPrefab() {
        assertThrows(IllegalArgumentException.class, () -> PenLayout.analyze(List.of(), PenLayoutTest::isChair));
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.prefab.PenLayoutTest'`
Expected: compilation failure, `PenLayout` not found.

- [ ] **Step 7: Write PenLayout**

```java
package dev.hytalemodding.sproutwatch.prefab;

import dev.hytalemodding.sproutwatch.config.PenFacing;

import java.util.List;
import java.util.function.Predicate;

/**
 * Pure analysis of a prefab block list (prefab-local coordinates):
 *   floorY       = lowest block y
 *   clearHeight  = highest block y - floorY
 *   interior     = bounding box of "Empty" blocks at floorY + 1 (the fenced air), or, if the
 *                  prefab has no Empty blocks there, the whole bounding box shrunk by one on x/z
 *   chair        = first block the isSeat predicate accepts (runtime: BlockType has Seats)
 *   facing       = direction from the chair toward the interior centre (NORTH when no chair)
 */
public record PenLayout(int interiorMinX, int interiorMinZ, int sizeX, int sizeZ,
                        int floorY, int clearHeight,
                        boolean chairFound, int chairX, int chairY, int chairZ,
                        PenFacing facing) {

    public static PenLayout analyze(List<PrefabBlock> blocks, Predicate<String> isSeat) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("Prefab has no blocks");
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PrefabBlock b : blocks) {
            minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
            minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
            minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
        }
        int floorY = minY;
        int clearHeight = maxY - minY;

        int airMinX = Integer.MAX_VALUE, airMinZ = Integer.MAX_VALUE;
        int airMaxX = Integer.MIN_VALUE, airMaxZ = Integer.MIN_VALUE;
        boolean anyAir = false;
        PrefabBlock chair = null;
        for (PrefabBlock b : blocks) {
            if (b.y() == floorY + 1 && "Empty".equals(b.name())) {
                anyAir = true;
                airMinX = Math.min(airMinX, b.x()); airMaxX = Math.max(airMaxX, b.x());
                airMinZ = Math.min(airMinZ, b.z()); airMaxZ = Math.max(airMaxZ, b.z());
            }
            if (chair == null && isSeat.test(b.name())) chair = b;
        }

        int iMinX, iMinZ, sizeX, sizeZ;
        if (anyAir) {
            iMinX = airMinX; iMinZ = airMinZ;
            sizeX = airMaxX - airMinX + 1; sizeZ = airMaxZ - airMinZ + 1;
        } else {
            iMinX = minX + 1; iMinZ = minZ + 1;
            sizeX = Math.max(1, maxX - minX - 1); sizeZ = Math.max(1, maxZ - minZ - 1);
        }

        PenFacing facing = PenFacing.NORTH;
        if (chair != null) {
            double cx = iMinX + sizeX / 2.0;
            double cz = iMinZ + sizeZ / 2.0;
            facing = PenFacing.toward(cx - (chair.x() + 0.5), cz - (chair.z() + 0.5));
        }
        return new PenLayout(iMinX, iMinZ, sizeX, sizeZ, floorY, clearHeight,
            chair != null, chair == null ? 0 : chair.x(), chair == null ? 0 : chair.y(), chair == null ? 0 : chair.z(),
            facing);
    }
}
```

- [ ] **Step 8: Run both prefab tests**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.prefab.*'`
Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 9: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 10: PenCamera (pure)

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/camera/PenCamera.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/camera/PenCameraTest.java`

- [ ] **Step 1: Write the failing camera test**

```java
package dev.hytalemodding.sproutwatch.camera;

import dev.hytalemodding.sproutwatch.config.PenFacing;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PenCameraTest {

    // interior min (10, floor 64, 20), 12 x 16 -> centre (16, 65, 28)
    private static final PenBounds B = new PenBounds(10, 64, 20, 12, 16, 4);
    private static final Vector3d LOOK = new Vector3d(16.0, 65.0, 28.0);

    @Test void southCameraSitsBehindTheMinZSide() {
        PenCamera c = PenCamera.of(B, PenFacing.SOUTH, 14, 4);
        assertEquals(new Vector3d(16.0, 79.0, 16.0), c.position());
        assertEquals(LOOK, c.lookAt());
    }

    @Test void northCameraSitsBehindTheMaxZSide() {
        assertEquals(new Vector3d(16.0, 79.0, 40.0), PenCamera.of(B, PenFacing.NORTH, 14, 4).position());
    }

    @Test void eastCameraSitsBehindTheMinXSide() {
        assertEquals(new Vector3d(6.0, 79.0, 28.0), PenCamera.of(B, PenFacing.EAST, 14, 4).position());
    }

    @Test void westCameraSitsBehindTheMaxXSide() {
        assertEquals(new Vector3d(26.0, 79.0, 28.0), PenCamera.of(B, PenFacing.WEST, 14, 4).position());
    }

    @Test void heightAndBackAreApplied() {
        PenCamera c = PenCamera.of(B, PenFacing.SOUTH, 2.5, 0);
        assertEquals(new Vector3d(16.0, 67.5, 20.0), c.position());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.camera.PenCameraTest'`
Expected: compilation failure, `PenCamera` not found.

- [ ] **Step 3: Write PenCamera**

```java
package dev.hytalemodding.sproutwatch.camera;

import dev.hytalemodding.sproutwatch.config.PenFacing;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import org.joml.Vector3d;

/**
 * Where the fixed pen camera sits and what it looks at. Pure geometry.
 * The camera is centred on the short side opposite to the facing direction (the chair side),
 * pushed `back` blocks away from the pen and raised `height` blocks above the floor, looking at
 * the pen centre at foot level. Tune with config CameraHeight / CameraBack / CameraFov until a
 * 4:3 landscape crop of the game window is filled by the 16x12 pen.
 */
public record PenCamera(Vector3d position, Vector3d lookAt) {

    public static PenCamera of(PenBounds b, PenFacing facing, double height, double back) {
        double cx = b.centerX();
        double cz = b.centerZ();
        double y = b.feetY();
        double px = cx - facing.dx * (b.sizeX() / 2.0 + back);
        double pz = cz - facing.dz * (b.sizeZ() / 2.0 + back);
        return new PenCamera(new Vector3d(px, y + height, pz), new Vector3d(cx, y, cz));
    }
}
```

- [ ] **Step 4: Run the camera test**

Run: `./gradlew test --tests 'dev.hytalemodding.sproutwatch.camera.PenCameraTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 11: PenPlacer (engine)

Engine-only, no unit test: compile, then verified in-game in Task 17.

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/prefab/PenPlacer.java`

- [ ] **Step 1: Write PenPlacer**

```java
package dev.hytalemodding.sproutwatch.prefab;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.bson.BsonDocument;
import org.joml.Vector3i;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pastes the bundled pen prefab centred on a player and records the pen in config.
 * Verified against Server-0.6.3.jar: SelectionPrefabSerializer.deserialize(BsonDocument) reads
 * exactly the bundled JSON format; BlockSelection.placeNoReturn(World, Vector3i, ComponentAccessor)
 * puts prefab-local block (lx,ly,lz) at world (lx + sel.getX() + pos.x - sel.getAnchorX(), ...).
 * Must run on the world thread.
 */
public final class PenPlacer {

    private PenPlacer() {}

    /**
     * @param feet the block position of the caller's feet (floor of their position)
     * @return a message for the caller
     * @throws IOException if the bundled prefab is missing
     */
    public static String place(World world, Store<EntityStore> store, Vector3i feet, UUID worldUuid,
                               SproutwatchConfig cfg, Logger logger) throws IOException {
        String json = PenPrefab.readBundledJson();
        List<PrefabBlock> blocks = PenPrefab.parseBlocks(json);
        PenLayout layout = PenLayout.analyze(blocks, PenPlacer::isSeatBlock);
        BlockSelection sel = SelectionPrefabSerializer.deserialize(BsonDocument.parse(json));

        // Centre the interior on the caller; the floor block goes one below their feet.
        int centreLocalX = (int) Math.floor(layout.interiorMinX() + layout.sizeX() / 2.0);
        int centreLocalZ = (int) Math.floor(layout.interiorMinZ() + layout.sizeZ() / 2.0);
        Vector3i pos = new Vector3i(
            feet.x - centreLocalX,
            feet.y - 1 - layout.floorY(),
            feet.z - centreLocalZ);

        sel.placeNoReturn(world, pos, store);

        int ox = sel.getX() + pos.x - sel.getAnchorX();
        int oy = sel.getY() + pos.y - sel.getAnchorY();
        int oz = sel.getZ() + pos.z - sel.getAnchorZ();

        cfg.setPen(worldUuid.toString(),
            layout.interiorMinX() + ox, layout.floorY() + oy, layout.interiorMinZ() + oz,
            layout.sizeX(), layout.clearHeight(), layout.sizeZ());
        cfg.setChair(layout.chairFound(),
            layout.chairX() + ox, layout.chairY() + oy, layout.chairZ() + oz);
        cfg.setPenFacing(layout.facing().key());

        String chair = layout.chairFound()
            ? "chair at " + (layout.chairX() + ox) + "," + (layout.chairY() + oy) + "," + (layout.chairZ() + oz)
            : "NO chair block found in the prefab (camera only via /sproutwatch camera)";
        logger.log(Level.INFO, "Sproutwatch pen placed: interior min {0},{1},{2} size {3}x{4}, facing {5}, {6}",
            new Object[]{layout.interiorMinX() + ox, layout.floorY() + oy, layout.interiorMinZ() + oz,
                layout.sizeX(), layout.sizeZ(), layout.facing().key(), chair});
        return "Pen placed (" + layout.sizeX() + "x" + layout.sizeZ() + ", camera faces " + layout.facing().key() + "); " + chair + ".";
    }

    /** True when the named block type declares seat mount points (a chair, bench, stool). */
    static boolean isSeatBlock(String name) {
        try {
            int idx = BlockType.getAssetMap().getIndexOrDefault(name, -1);
            if (idx < 0) return false;
            BlockType bt = BlockType.getAssetMap().getAsset(idx);
            return bt != null && bt.getSeats() != null && bt.getSeats().size() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`. If `getIndexOrDefault` does not accept a `String`, the asset map key type differs from the javap reading; switch to `BlockType.getAssetMap().getAsset(name)` only if such an overload exists (check with javap), never guess.

- [ ] **Step 3: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 12: SproutSpawner, PenDespawnSystem and PenClearer (engine)

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/SproutSpawner.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenDespawnSystem.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenClearer.java`

- [ ] **Step 1: Write SproutSpawner**

```java
package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.spawning.SpawnTestResult;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3d;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spawns one named youngling inside the pen. Same validated ladder as Subinator's BossSpawner
 * (up to 8 random validated spots, then a column probe over the pen centre); no unvalidated
 * fallback, a miss is retried next tick. Must run on the world thread.
 */
public final class SproutSpawner {

    private static final int PLACEMENT_ATTEMPTS = 8;

    private final Supplier<SproutwatchConfig> config;
    private final PenRegistry registry;
    private final Logger logger;
    private final Random random = new Random();
    private final Set<String> warnedRoles = ConcurrentHashMap.newKeySet();

    public SproutSpawner(Supplier<SproutwatchConfig> config, PenRegistry registry, Logger logger) {
        this.config = config;
        this.registry = registry;
        this.logger = logger;
    }

    /** Pure: a random pool entry. */
    static String pickRole(String[] pool, Random random) {
        return pool[random.nextInt(pool.length)];
    }

    /** @return true if a sprout was spawned (and registered) for login. World thread only. */
    public boolean spawn(World world, String login) {
        try {
            SproutwatchConfig cfg = config.get();
            PenBounds bounds = PenBounds.fromConfig(cfg);
            if (!bounds.isSet()) {
                logger.warning("Sproutwatch: cannot spawn, pen not placed");
                return false;
            }
            String role = pickRole(cfg.getRoles(), random);
            Store<EntityStore> store = world.getEntityStore().getStore();
            UUID worldUuid = world.getWorldConfig().getUuid();
            NPCPlugin npcs = NPCPlugin.get();
            SpawnTestResult result = SpawnTestResult.FAIL_NO_POSITION;

            for (int i = 0; i < PLACEMENT_ATTEMPTS; i++) {
                Vector3d pos = bounds.randomPoint(random);
                Rotation3f rot = new Rotation3f(0f, (float) (random.nextDouble() * Math.PI * 2), 0f);
                result = npcs.spawnNPCWithSpaceValidation(
                    store, role, null, pos, rot,
                    (npc, npcRef, npcStore) -> onSpawned(npcRef, npcStore, login, role, worldUuid),
                    true, false);
                if (result == SpawnTestResult.TEST_OK) return true;
            }
            Vector3d c = bounds.center();
            result = npcs.spawnNPCWithColumnProbe(
                store, role, null, world, (int) Math.floor(c.x), (int) Math.floor(c.z), c.y + 1.0,
                new Rotation3f(0f, 0f, 0f),
                (npc, npcRef, npcStore) -> onSpawned(npcRef, npcStore, login, role, worldUuid));
            if (result == SpawnTestResult.TEST_OK) return true;

            if (isRoleProblem(result)) {
                if (warnedRoles.add(role)) {
                    logger.warning("Sproutwatch role '" + role + "' cannot spawn: " + result + " (check the Roles config)");
                }
            } else {
                logger.log(Level.FINE, "Sproutwatch: no validated spot for {0} ({1}); retrying next tick",
                    new Object[]{login, result});
            }
            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch spawn failed for " + login, e);
            return false;
        }
    }

    static boolean isRoleProblem(SpawnTestResult r) {
        return r == SpawnTestResult.FAIL_NOT_SPAWNABLE
            || r == SpawnTestResult.FAIL_NO_MOTION_CONTROLLERS
            || r == SpawnTestResult.FAIL_NO_MOTION_CONTROLLER_MATCH
            || r == SpawnTestResult.FAIL_BREATHING_INCOMPATIBLE;
    }

    /**
     * Spawn init callback: world thread, outside the store's write-processing lock (Subinator's
     * TwitchAura mutates a plain Store from the same callback), so ensureAndGetComponent is safe.
     * Nameplate is what vanilla /entity nameplate uses (R3).
     */
    private void onSpawned(Ref<EntityStore> npcRef, Store<EntityStore> npcStore, String login, String role, UUID worldUuid) {
        try {
            npcStore.ensureAndGetComponent(npcRef, Nameplate.getComponentType()).setText(login);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch: could not set nameplate for " + login, e);
        }
        int networkId = -1;
        try {
            NetworkId nid = npcStore.getComponent(npcRef, NetworkId.getComponentType());
            if (nid != null) networkId = nid.getId();
        } catch (Exception e) {
            logger.log(Level.FINE, "Sproutwatch: no NetworkId for " + login, e);
        }
        long now = System.currentTimeMillis();
        registry.put(new PenRegistry.Entry(login, npcRef, networkId, worldUuid, now, now));
        logger.info("Sproutwatch: " + login + " joined the pen as " + role);
    }
}
```

- [ ] **Step 2: Write PenDespawnSystem**

```java
package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Evicts a registry entry when its entity is removed for any reason (killed, chunk unload,
 * /kill, shutdown, or our own despawn). The next tick then respawns that viewer if they are
 * still in chat. Copy of Subinator's BossDespawnSystem: match-everything query, membership
 * decided by the registry. Fires inside the store's write lock: no store mutation here.
 */
public final class PenDespawnSystem extends RefSystem<EntityStore> {

    private final PenRegistry registry;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    public PenDespawnSystem(PenRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store,
                              CommandBuffer<EntityStore> commandBuffer) {
        // SproutSpawner registers from its spawn callback.
    }

    @Override
    public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store,
                               CommandBuffer<EntityStore> commandBuffer) {
        if (registry.isEmpty()) return; // chunk-unload storms with no sprouts live
        try {
            PenRegistry.Entry e = registry.removeByRef(ref);
            if (e != null) logger.info("Sproutwatch: sprout for " + e.login() + " removed (" + reason + ")");
        } catch (Exception e) {
            if (warned.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Sproutwatch registry eviction failed on entity removal", e);
            }
        }
    }
}
```

- [ ] **Step 3: Write PenClearer**

```java
package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Removes every tracked sprout plus any NPC whose role is in `roles` and whose position is
 * inside the pen (one block of margin). The only restart safety: nothing about individual
 * sprouts is persisted. Must run on the world thread. Refs are collected first and removed
 * after the scan, because Store.removeEntity must not be called while iterating chunks.
 */
public final class PenClearer {

    private PenClearer() {}

    /** @return how many entities were removed */
    public static int clear(World world, PenRegistry registry, Set<String> roles, PenBounds bounds, Logger logger) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Set<Ref<EntityStore>> victims = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PenRegistry.Entry e : registry.clear()) {
            if (e.ref() != null && e.ref().isValid()) victims.add(e.ref());
        }
        if (bounds.isSet()) {
            try {
                store.forEachChunk(Archetype.of(NPCEntity.getComponentType()), (chunk, cb) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                        if (npc == null || npc.getRoleName() == null || !roles.contains(npc.getRoleName())) continue;
                        TransformComponent t = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (t == null) continue;
                        Vector3d p = t.getPosition();
                        if (bounds.contains(p.x, p.y, p.z, 1.0)) victims.add(chunk.getReferenceTo(i));
                    }
                });
            } catch (Exception e) {
                logger.log(Level.WARNING, "Sproutwatch pen scan failed; removing tracked sprouts only", e);
            }
        }
        int removed = 0;
        for (Ref<EntityStore> ref : new ArrayList<>(victims)) {
            try {
                if (ref.isValid()) {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Sproutwatch: failed to remove a sprout during clear", e);
            }
        }
        logger.info("Sproutwatch: cleared " + removed + " sprout(s)");
        return removed;
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`. Known risk: `store.forEachChunk(Query, BiConsumer)` lambda parameter types are `ArchetypeChunk<EntityStore>` and `CommandBuffer<EntityStore>`; if inference fails, declare them explicitly.

- [ ] **Step 5: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 13: PenTicker (engine)

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/pen/PenTicker.java`

- [ ] **Step 1: Write PenTicker**

```java
package dev.hytalemodding.sproutwatch.pen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every TickSeconds: resolve the pen world, hop onto its thread, mark every pen entry whose
 * login is still in the roster as seen now, reconcile, despawn the stale, spawn one newcomer.
 * Owns a daemon scheduler and marshals onto the world via world.execute (a world.scheduleAfter
 * chain would die silently on world unload; this keeps polling and recovers). Every tick body is
 * caught and logged; the schedule never stops on an exception.
 */
public final class PenTicker {

    private final Supplier<SproutwatchConfig> config;
    private final ChatRoster roster;
    private final PenRegistry registry;
    private final SproutSpawner spawner;
    private final Logger logger;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sproutwatch-tick");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> future;

    public PenTicker(Supplier<SproutwatchConfig> config, ChatRoster roster, PenRegistry registry,
                     SproutSpawner spawner, Logger logger) {
        this.config = config;
        this.roster = roster;
        this.registry = registry;
        this.spawner = spawner;
        this.logger = logger;
    }

    /** (Re)arms the schedule with the current TickSeconds. Idempotent. */
    public synchronized void start() {
        stop();
        int s = config.get().getTickSeconds();
        future = exec.scheduleAtFixedRate(this::dispatch, s, s, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    public synchronized boolean isRunning() {
        return future != null && !future.isCancelled();
    }

    /** Plugin shutdown: releases the scheduler thread. */
    public void shutdown() {
        stop();
        exec.shutdownNow();
    }

    /** Runs one tick as soon as the pen world thread gets to it (/sproutwatch test <login> now). */
    public void tickNow() {
        dispatch();
    }

    private void dispatch() {
        try {
            World world = resolveWorld(config.get());
            if (world == null) {
                logger.fine("Sproutwatch tick skipped: pen world not loaded");
                return;
            }
            world.execute(() -> tickOnWorldThread(world));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Sproutwatch tick dispatch failed", e);
        }
    }

    /** Null when no pen is placed, the UUID is malformed, or the world is not loaded. */
    public static World resolveWorld(SproutwatchConfig cfg) {
        String id = cfg.getPenWorld();
        if (id.isEmpty()) return null;
        try {
            return Universe.get().getWorld(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    void tickOnWorldThread(World world) {
        try {
            SproutwatchConfig cfg = config.get();
            long now = System.currentTimeMillis();
            Map<String, Long> live = roster.snapshot();
            for (String login : live.keySet()) registry.touch(login, now);

            PenPlan plan = PenReconciler.reconcile(
                live, registry.lastSeenMap(), cfg.getMaxSprouts(), cfg.getGraceSeconds() * 1000L, now);

            for (String login : plan.despawn()) {
                PenRegistry.Entry e = registry.remove(login);
                if (e == null) continue;
                Ref<EntityStore> ref = e.ref();
                if (ref != null && ref.isValid()) {
                    try {
                        ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
                    } catch (Exception ex) {
                        logger.log(Level.WARNING, "Sproutwatch: failed to despawn sprout for " + login, ex);
                    }
                }
                logger.info("Sproutwatch: " + login + " left chat, sprout despawned");
            }
            plan.spawn().ifPresent(login -> spawner.spawn(world, login));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch tick failed", e);
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 14: CameraPackets and ChairCameraService (engine)

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/camera/CameraPackets.java`
- Create: `src/main/java/dev/hytalemodding/sproutwatch/camera/ChairCameraService.java`

- [ ] **Step 1: Write CameraPackets**

```java
package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.CanMoveType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.util.PositionUtil;
import dev.hytalemodding.sproutwatch.config.PenFacing;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;

/**
 * Builds the two camera packets. Field choices mirror vanilla /camera topdown
 * (PlayerCameraTopdownCommand, read via javap): public fields on ServerCameraSettings, unset
 * enums stay null, packet written with PacketHandler.writeNoCache. The fixed camera uses
 * PositionType.Custom + RotationType.Custom so it never follows the player.
 * Rotation3f.lookAt(a, b) gives yaw/pitch from a delta; whether it wants (from, to) or (to, from)
 * could not be read from bytecode, hence CameraFlip.
 */
public final class CameraPackets {

    private CameraPackets() {}

    public static SetServerCamera penCamera(SproutwatchConfig cfg) {
        PenBounds bounds = PenBounds.fromConfig(cfg);
        PenCamera cam = PenCamera.of(bounds, PenFacing.parse(cfg.getPenFacing()), cfg.getCameraHeight(), cfg.getCameraBack());
        return penCamera(cam, cfg.getCameraFov(), cfg.isCameraFlip());
    }

    public static SetServerCamera penCamera(PenCamera cam, double fov, boolean flip) {
        Rotation3f look = flip
            ? Rotation3f.lookAt(cam.lookAt(), cam.position())
            : Rotation3f.lookAt(cam.position(), cam.lookAt());
        Direction dir = PositionUtil.toDirectionPacket(look);

        ServerCameraSettings s = new ServerCameraSettings();
        s.positionLerpSpeed = 0.2f;
        s.rotationLerpSpeed = 0.2f;
        s.isFirstPerson = false;
        s.displayCursor = false;
        s.eyeOffset = false;
        s.attachedToType = AttachedToType.None;
        s.positionType = PositionType.Custom;
        s.position = new Position(cam.position().x, cam.position().y, cam.position().z);
        s.positionDistanceOffsetType = PositionDistanceOffsetType.None;
        s.rotationType = RotationType.Custom;
        s.rotation = dir;
        s.movementForceRotationType = MovementForceRotationType.Custom;
        s.movementForceRotation = dir;
        s.canMoveType = CanMoveType.Always;
        s.baseFov = (float) fov;
        return new SetServerCamera(ClientCameraView.Custom, true, s);
    }

    /** Identical bytes to /camera reset and CameraManager.resetCamera. */
    public static SetServerCamera reset() {
        return new SetServerCamera(ClientCameraView.Custom, false, null);
    }
}
```

- [ ] **Step 2: Write ChairCameraService**

```java
package dev.hytalemodding.sproutwatch.camera;

import com.hypixel.hytale.builtin.mounts.BlockMountComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.protocol.BlockMountType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.joml.Vector3i;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends the fixed pen camera to a player who sits on the saved chair block and resets it when
 * they stand up. ECS RefChangeSystem on the engine's MountedComponent (builtin/mounts): the
 * exact shape vanilla's MountSystems$PlayerMount and beds' WakeUpOnDismountSystem use (R2).
 * Callbacks run inside the store's write lock: reads and packet sends only, no store mutation.
 * Also backs /sproutwatch camera (manual toggle for tuning).
 */
public final class ChairCameraService extends RefChangeSystem<EntityStore, MountedComponent> {

    private final Supplier<SproutwatchConfig> config;
    private final Logger logger;
    private final Set<UUID> seated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> manual = ConcurrentHashMap.newKeySet();

    public ChairCameraService(Supplier<SproutwatchConfig> config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public ComponentType<EntityStore, MountedComponent> componentType() {
        return MountedComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return MountedComponent.getComponentType();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, MountedComponent mounted, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (mounted.getBlockMountType() != BlockMountType.Seat) return;
            SproutwatchConfig cfg = config.get();
            if (!cfg.isChairSet() || !cfg.isPenSet()) return;
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) return;
            if (!cfg.getPenWorld().equals(String.valueOf(player.getWorldUuid()))) return;
            Vector3i seat = seatBlock(mounted);
            if (seat == null || seat.x != cfg.getChairX() || seat.y != cfg.getChairY() || seat.z != cfg.getChairZ()) return;
            seated.add(player.getUuid());
            player.getPacketHandler().writeNoCache(CameraPackets.penCamera(cfg));
            player.sendMessage(Message.raw("Sproutwatch camera on. Stand up to reset."));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch chair camera failed on mount", e);
        }
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref, MountedComponent oldValue, MountedComponent newValue,
                               Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        // A seat swap is a remove + add from our point of view; nothing to do here.
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref, MountedComponent mounted, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
        try {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) return;
            if (seated.remove(player.getUuid())) {
                manual.remove(player.getUuid());
                player.getPacketHandler().writeNoCache(CameraPackets.reset());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Sproutwatch chair camera failed on dismount", e);
        }
    }

    /** The seat's block position, read from the BlockMountComponent the rider is attached to. */
    private static Vector3i seatBlock(MountedComponent mounted) {
        Ref<ChunkStore> blockRef = mounted.getMountedToBlock();
        if (blockRef == null || !blockRef.isValid()) return null;
        BlockMountComponent bm = blockRef.getStore().getComponent(blockRef, BlockMountComponent.getComponentType());
        return bm == null ? null : bm.getBlockPos();
    }

    /** /sproutwatch camera: toggles the fixed camera for a player without sitting. @return true if now on. */
    public boolean toggleManual(PlayerRef player) {
        UUID id = player.getUuid();
        if (manual.remove(id)) {
            player.getPacketHandler().writeNoCache(CameraPackets.reset());
            return false;
        }
        manual.add(id);
        player.getPacketHandler().writeNoCache(CameraPackets.penCamera(config.get()));
        return true;
    }

    /** Re-sends the camera to everyone currently using it (after /sproutwatch interval-style tuning edits). */
    public void refresh(java.util.Collection<PlayerRef> players) {
        SproutwatchConfig cfg = config.get();
        for (PlayerRef p : players) {
            if (seated.contains(p.getUuid()) || manual.contains(p.getUuid())) {
                try {
                    p.getPacketHandler().writeNoCache(CameraPackets.penCamera(cfg));
                } catch (Exception e) {
                    logger.log(Level.FINE, "Sproutwatch camera refresh failed", e);
                }
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`. If `RefChangeSystem` demands a bridge override for the raw `onComponentRemoved(Ref, Component, Store, CommandBuffer)`, the generic overrides above already satisfy it (vanilla subclasses compile the same way).

- [ ] **Step 4: Checkpoint**

Run: `./gradlew test && git add -A`

---

### Task 15: SproutwatchPlugin wiring

**Files:**
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/SproutwatchPlugin.java` (replace the stub)

- [ ] **Step 1: Replace the plugin class**

```java
package dev.hytalemodding.sproutwatch;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
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
import dev.hytalemodding.sproutwatch.pen.SproutSpawner;
import dev.hytalemodding.sproutwatch.twitch.ChatRoster;
import dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClient;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class SproutwatchPlugin extends JavaPlugin {

    private final Config<SproutwatchConfig> config;
    private final Logger bridgeLogger;
    private final ChatRoster roster;
    private final PenRegistry registry;
    private final SproutSpawner spawner;
    private final PenTicker ticker;
    private final ChairCameraService cameraService;
    private volatile TwitchMembershipClient client;

    public SproutwatchPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.config = withConfig("Sproutwatch_config", SproutwatchConfig.CODEC);
        this.bridgeLogger = createBridgeLogger();
        this.roster = new ChatRoster(() -> config.get().ignoredLogins());
        this.registry = new PenRegistry();
        this.spawner = new SproutSpawner(config::get, registry, bridgeLogger);
        this.ticker = new PenTicker(config::get, roster, registry, spawner, bridgeLogger);
        this.cameraService = new ChairCameraService(config::get, bridgeLogger);
    }

    @Override
    protected void setup() {
        config.save().whenComplete((v, t) -> {
            if (t != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", t);
        });
        getCommandRegistry().registerCommand(new SproutwatchCommand(this));
        // ECS systems via getEntityStoreRegistry(), the route Subinator proved on 0.6.3. Each
        // registration is guarded on its own so one failure never drops the other.
        try {
            getEntityStoreRegistry().registerSystem(new PenDespawnSystem(registry, bridgeLogger));
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch despawn system failed to register");
        }
        try {
            getEntityStoreRegistry().registerSystem(cameraService);
        } catch (RuntimeException e) {
            getLogger().atSevere().withCause(e).log("Sproutwatch chair camera failed to register");
        }
        // Restart safety: sweep leftover younglings out of the pen if its world is already loaded.
        World w = PenTicker.resolveWorld(config.get());
        if (w != null) {
            w.execute(() -> PenClearer.clear(w, registry, roleSet(), PenBounds.fromConfig(config.get()), bridgeLogger));
        }
        if (config.get().isAutoStartOnBoot()) {
            String err = startListener();
            if (err != null) getLogger().atWarning().log("%s", err);
        }
    }

    @Override
    protected void shutdown() {
        stopListener();
        ticker.shutdown();
    }

    public Config<SproutwatchConfig> getConfigHolder() { return config; }
    public Logger getBridgeLogger() { return bridgeLogger; }
    public ChatRoster getRoster() { return roster; }
    public PenRegistry getRegistry() { return registry; }
    public PenTicker getTicker() { return ticker; }
    public ChairCameraService getCameraService() { return cameraService; }
    public TwitchMembershipClient getClient() { return client; }

    public Set<String> roleSet() {
        return Set.of(config.get().getRoles());
    }

    public void saveConfig() {
        config.save().whenComplete((v, t) -> {
            if (t != null) bridgeLogger.log(Level.WARNING, "Failed to save Sproutwatch config", t);
        });
    }

    /** @return error text, or null on success. Synchronized: commands may race. */
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
        ticker.start();
        return null;
    }

    /** @return true if a listener was running and has been stopped. Sprouts stay until /sproutwatch clear. */
    public synchronized boolean stopListener() {
        TwitchMembershipClient c = client;
        client = null;
        ticker.stop();
        roster.clear();
        if (c != null) {
            c.stop();
            return true;
        }
        return false;
    }

    /**
     * Bridges java.util.logging (used by every component) onto this plugin's HytaleLogger, a
     * Flogger with only the fluent at(Level) API. Copied from Subinator (verified on 0.6.3).
     */
    private Logger createBridgeLogger() {
        Logger jul = Logger.getLogger("Sproutwatch");
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.ALL);
        if (jul.getHandlers().length == 0) {
            SimpleFormatter formatter = new SimpleFormatter();
            jul.addHandler(new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord r) {
                    String msg = formatter.formatMessage(r);
                    var api = getLogger().at(r.getLevel());
                    if (r.getThrown() != null) api = api.withCause(r.getThrown());
                    api.log("%s", msg);
                }
                @Override public void flush() {}
                @Override public void close() {}
            });
        }
        return jul;
    }
}
```

- [ ] **Step 2: Compile (expected to fail only on the missing command class)**

Run: `./gradlew compileJava`
Expected: one error, `SproutwatchCommand` not found. Task 16 adds it.

---

### Task 16: SproutwatchCommand

**Files:**
- Create: `src/main/java/dev/hytalemodding/sproutwatch/commands/SproutwatchCommand.java`

Same structure as Subinator's `SubinatorCommand` (`AbstractCommandCollection` + a `Sub` base whose `execute` returns `CompletableFuture<Void>`; `withRequiredArg`/`withDefaultArg` with `ArgTypes.STRING`/`INTEGER`/`DOUBLE`; `ctx.isPlayer()`, `ctx.senderAs(PlayerRef.class)`, `ctx.sendMessage(Message.raw(..))`). Permission node `<base>.admin` (e.g. `mertie.sproutwatch.admin`).

- [ ] **Step 1: Write the command collection**

```java
package dev.hytalemodding.sproutwatch.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.sproutwatch.SproutwatchPlugin;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import dev.hytalemodding.sproutwatch.pen.PenBounds;
import dev.hytalemodding.sproutwatch.pen.PenClearer;
import dev.hytalemodding.sproutwatch.pen.PenTicker;
import dev.hytalemodding.sproutwatch.prefab.PenPlacer;
import dev.hytalemodding.sproutwatch.twitch.RosterEvent;
import dev.hytalemodding.sproutwatch.twitch.TwitchMembershipClient;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class SproutwatchCommand extends AbstractCommandCollection {

    public SproutwatchCommand(SproutwatchPlugin plugin) {
        super("sproutwatch", "Twitch viewers as baby Kweebecs in a pen");
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
    }

    /** AbstractCommand.execute returns CompletableFuture<Void>; funnel subclasses through run(). */
    private abstract static class Sub extends AbstractCommand {
        final SproutwatchPlugin plugin;

        Sub(SproutwatchPlugin plugin, String name, String desc, String permission) {
            super(name, desc);
            this.plugin = plugin;
            requirePermission(permission);
        }

        void reply(CommandContext ctx, String text) {
            ctx.sendMessage(Message.raw(text));
        }

        SproutwatchConfig cfg() {
            return plugin.getConfigHolder().get();
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
            String channel = SproutwatchConfig.normalizeChannel(ctx.get(nameArg));
            if (channel.isEmpty()) {
                reply(ctx, "Invalid channel name.");
                return;
            }
            cfg().setTwitchChannel(channel);
            plugin.saveConfig();
            TwitchMembershipClient c = plugin.getClient();
            if (c != null && c.isRunning()) {
                String err = plugin.startListener();
                reply(ctx, err != null ? err : "Channel set to #" + channel + "; listener restarted.");
            } else {
                reply(ctx, "Channel set to #" + channel + ". Run /sproutwatch start to begin.");
            }
        }
    }

    private static final class StartCommand extends Sub {
        StartCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "start", "Start watching chat and filling the pen", permission);
        }

        @Override void run(CommandContext ctx) {
            String err = plugin.startListener();
            if (err != null) {
                reply(ctx, err);
                return;
            }
            reply(ctx, "Sproutwatch watching #" + cfg().getTwitchChannel() + "; one sprout every " + cfg().getTickSeconds() + "s.");
        }
    }

    private static final class StopCommand extends Sub {
        StopCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "stop", "Stop watching chat (sprouts stay until clear)", permission);
        }

        @Override void run(CommandContext ctx) {
            boolean was = plugin.stopListener();
            reply(ctx, was ? "Sproutwatch stopped." : "Sproutwatch was not running.");
        }
    }

    private static final class StatusCommand extends Sub {
        StatusCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "status", "Show listener, roster and pen status", permission);
        }

        @Override void run(CommandContext ctx) {
            SproutwatchConfig c = cfg();
            TwitchMembershipClient client = plugin.getClient();
            StringBuilder sb = new StringBuilder();
            sb.append("Listener: ").append(client == null ? "stopped" : client.getState()).append('\n');
            sb.append("Channel: ").append(c.getTwitchChannel().isEmpty() ? "(unset)" : "#" + c.getTwitchChannel()).append('\n');
            if (client != null && client.isRunning()) {
                sb.append("Membership ACK: ").append(client.isMembershipAcked() ? "yes" : "NO (only chatters will appear)").append('\n');
            }
            sb.append("Roster: ").append(plugin.getRoster().size()).append(" in chat\n");
            sb.append("Pen: ").append(plugin.getRegistry().size()).append('/').append(c.getMaxSprouts()).append(" sprouts, tick ")
              .append(c.getTickSeconds()).append("s, grace ").append(c.getGraceSeconds()).append("s, ticker ")
              .append(plugin.getTicker().isRunning() ? "running" : "stopped").append('\n');
            if (c.isPenSet()) {
                World w = PenTicker.resolveWorld(c);
                sb.append("Pen placed: ").append(c.getPenSizeX()).append('x').append(c.getPenSizeZ())
                  .append(" at ").append(c.getPenX()).append(',').append(c.getPenY()).append(',').append(c.getPenZ())
                  .append(" in ").append(w == null ? "an unloaded world (" + c.getPenWorld() + ")" : w.getName())
                  .append(", camera faces ").append(c.getPenFacing()).append('\n');
                sb.append("Chair: ").append(c.isChairSet()
                    ? c.getChairX() + "," + c.getChairY() + "," + c.getChairZ()
                    : "(none; use /sproutwatch camera)");
            } else {
                sb.append("Pen: not placed (run /sproutwatch place)");
            }
            reply(ctx, sb.toString());
        }
    }

    private static final class PlaceCommand extends Sub {
        PlaceCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "place", "Paste the pen prefab centred on you and save it", permission);
        }

        @Override void run(CommandContext ctx) {
            PlayerRef sender = player(ctx);
            if (sender == null) return;
            UUID worldUuid = sender.getWorldUuid();
            World world = Universe.get().getWorld(worldUuid);
            if (world == null) {
                reply(ctx, "Your world is not loaded.");
                return;
            }
            world.execute(() -> {
                try {
                    Ref<EntityStore> ref = sender.getReference();
                    if (ref == null || !ref.isValid()) return;
                    Store<EntityStore> store = ref.getStore();
                    TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                    if (t == null) return;
                    Vector3d p = t.getPosition();
                    Vector3i feet = new Vector3i((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
                    String msg = PenPlacer.place(world, store, feet, worldUuid, cfg(), plugin.getBridgeLogger());
                    plugin.saveConfig();
                    sender.sendMessage(Message.raw(msg));
                } catch (Exception e) {
                    plugin.getBridgeLogger().log(Level.WARNING, "Sproutwatch place failed", e);
                    sender.sendMessage(Message.raw("Pen placement failed: " + e.getMessage() + " (see server log)"));
                }
            });
            reply(ctx, "Placing the pen...");
        }
    }

    private static final class ClearCommand extends Sub {
        ClearCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "clear", "Remove every sprout in the pen", permission);
        }

        @Override void run(CommandContext ctx) {
            World w = PenTicker.resolveWorld(cfg());
            if (w == null) {
                int n = plugin.getRegistry().clear().size();
                reply(ctx, "Pen world not loaded; forgot " + n + " tracked sprout(s).");
                return;
            }
            w.execute(() -> PenClearer.clear(w, plugin.getRegistry(), plugin.roleSet(), PenBounds.fromConfig(cfg()), plugin.getBridgeLogger()));
            reply(ctx, "Clearing the pen...");
        }
    }

    private static final class CameraCommand extends Sub {
        private final DefaultArg<Double> heightArg;
        private final DefaultArg<Double> backArg;
        private final DefaultArg<Double> fovArg;

        CameraCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "camera", "Toggle the pen camera for you; optional height back fov to tune", permission);
            heightArg = withDefaultArg("height", "Blocks above the floor (default keeps current)", ArgTypes.DOUBLE, -1.0, "current");
            backArg = withDefaultArg("back", "Blocks behind the short side", ArgTypes.DOUBLE, -1.0, "current");
            fovArg = withDefaultArg("fov", "Field of view in degrees", ArgTypes.DOUBLE, -1.0, "current");
        }

        @Override void run(CommandContext ctx) {
            PlayerRef sender = player(ctx);
            if (sender == null) return;
            SproutwatchConfig c = cfg();
            if (!c.isPenSet()) {
                reply(ctx, "No pen placed yet. Run /sproutwatch place first.");
                return;
            }
            double h = ctx.get(heightArg), b = ctx.get(backArg), f = ctx.get(fovArg);
            boolean tuned = h >= 0 || b >= 0 || f >= 0;
            if (tuned) {
                c.setCamera(h >= 0 ? h : c.getCameraHeight(), b >= 0 ? b : c.getCameraBack(), f >= 0 ? f : c.getCameraFov());
                plugin.saveConfig();
                plugin.getCameraService().refresh(Universe.get().getPlayers());
                reply(ctx, "Camera height " + c.getCameraHeight() + ", back " + c.getCameraBack() + ", fov " + c.getCameraFov() + ".");
                return;
            }
            boolean on = plugin.getCameraService().toggleManual(sender);
            reply(ctx, on ? "Pen camera on. Run /sproutwatch camera again to reset." : "Pen camera off.");
        }
    }

    private static final class IntervalCommand extends Sub {
        private final RequiredArg<Integer> secondsArg;

        IntervalCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "interval", "Seconds between sprout spawns (min 5)", permission);
            secondsArg = withRequiredArg("seconds", "Tick interval in seconds", ArgTypes.INTEGER);
        }

        @Override void run(CommandContext ctx) {
            cfg().setTickSeconds(ctx.get(secondsArg));
            plugin.saveConfig();
            if (plugin.getTicker().isRunning()) plugin.getTicker().start();
            reply(ctx, "Tick interval is now " + cfg().getTickSeconds() + "s.");
        }
    }

    private static final class TestCommand extends Sub {
        private final RequiredArg<String> loginArg;
        private final DefaultArg<String> whenArg;

        TestCommand(SproutwatchPlugin plugin, String permission) {
            super(plugin, "test", "Pretend <login> is in chat: /sproutwatch test <login> [now]", permission);
            loginArg = withRequiredArg("login", "Fake viewer login", ArgTypes.STRING);
            whenArg = withDefaultArg("when", "'now' to tick immediately", ArgTypes.STRING, "later", "later");
        }

        @Override void run(CommandContext ctx) {
            if (!cfg().isPenSet()) {
                reply(ctx, "No pen placed yet. Run /sproutwatch place first.");
                return;
            }
            String login = ctx.get(loginArg).trim().toLowerCase(Locale.ROOT);
            if (login.isEmpty()) {
                reply(ctx, "Give a login, e.g. /sproutwatch test alice now");
                return;
            }
            plugin.getRoster().apply(new RosterEvent.Seen(login), System.currentTimeMillis());
            if ("now".equalsIgnoreCase(ctx.get(whenArg))) {
                plugin.getTicker().tickNow();
                reply(ctx, login + " added to the roster; ticking now.");
            } else if (plugin.getTicker().isRunning()) {
                reply(ctx, login + " added to the roster; spawns on the next tick.");
            } else {
                reply(ctx, login + " added to the roster; run /sproutwatch start (or add 'now').");
            }
        }
    }
}
```

- [ ] **Step 2: Compile and run everything**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, all unit tests green (config 15, twitch 15, pen 17, prefab 7, camera 5 = 59).

- [ ] **Step 3: Checkpoint**

Run: `git add -A && git status --short | wc -l`
Expected: every new file staged.

---

### Task 17: Deploy, headless boot check, in-game verification

**Files:** none new.

- [ ] **Step 1: Deploy**

Run: `./deploy.sh`
Expected: `✓ Deployed sproutwatch-0.1.0.jar to .../UserData/Mods`.

- [ ] **Step 2: Headless boot (validates plugin load and the bundled asset pack)**

From an EMPTY working directory (the server also loads `./mods` from cwd):

```bash
# cwd must NOT contain a mods/ dir: the server also loads ./mods relative to cwd ("Tried to load duplicate plugin").
S=/private/tmp/sproutwatch-boot && rm -rf "$S" && mkdir -p "$S/mods" "$S/early" "$S/cache" "$S/universe" "$S/cwd" && cd "$S/cwd"
cp ~/Developer/hytale/sproutwatch/build/libs/sproutwatch-0.1.0.jar "$S/mods/"
JAVA="$HOME/Library/Application Support/Hytale/install/release/package/jre/latest/Contents/Home/bin/java"
G="$HOME/Library/Application Support/Hytale/install/release/package/game/latest"
"$JAVA" -Xms512M -jar "$G/Server/HytaleServer.jar" --assets="$G/Assets.zip" --mods="$S/mods" \
  --early-plugins="$S/early" --prefab-cache="$S/cache" --bind localhost:57999 --auth-mode=offline \
  --universe="$S/universe" --transport QUICHE > boot.log 2>&1 &
sleep 40; kill %1
sed 's/\x1b\[[0-9;]*m//g' boot.log | grep -iE 'sproutwatch|FAIL:|validation failed|Unknown JSON attribute|duplicate plugin' | head -30
```

Expected: a line showing the Sproutwatch plugin loaded and its command registered; no `FAIL:` / `Unknown JSON attribute` lines mentioning sproutwatch. Anything else: fix before the in-game pass.

- [ ] **Step 3: In-game checklist (Mertie, adventure mode, Testr World)**

Report each line as pass/fail with the server log lines for failures.

1. `/sproutwatch place` on flat ground: a 16x12 fenced grass pen (fence one block high) appears centred on you with a chair outside the middle of one long side; reply names the chair position and facing. `/sproutwatch status` shows "Pen placed".
2. `/sproutwatch test alice now`: within a second a baby Kweebec appears inside the fence with the nameplate `alice` (R3 check: if no name shows, note whether `/entity nameplate` on it works either).
3. `/sproutwatch test bob now`, `/sproutwatch test carol now`: three named sprouts wander without leaving the pen.
4. Sit on the chair: the view snaps to a high camera over the chair's long side looking across the pen. If it looks away from the pen, set `"CameraFlip": true` in `mods/Mertie_sproutwatch/Sproutwatch_config.json` (or edit and re-`place`), restart, retry. Tune with `/sproutwatch camera 16 20 30` style arguments until a 4:3 landscape crop of the window is filled by the pen; the camera re-sends live.
5. Stand up: the normal camera returns. `/sproutwatch camera` toggles the same view without sitting.
6. `/sproutwatch clear`: all sprouts vanish; `status` shows 0 sprouts.
7. `/sproutwatch channel <live channel>` then `/sproutwatch start`: `status` shows "Membership ACK: yes" and a roster count; one sprout per tick appears until chat is exhausted or the cap is hit; `/sproutwatch interval 10` speeds this up.
8. Kill a sprout with a sword: it respawns for that viewer on the next tick (PenDespawnSystem path).
9. Restart the server with sprouts in the pen: they are swept on boot (PenClearer in setup) and the pen refills once you `start`.

**Reviewer-added checks (from the per-task code reviews, 2026-09-27):**

10. After `place`, config should read `PenX = feet.x - 8`, `PenY = feet.y - 1`, `PenZ = feet.z - 6`, `PenSizeX 16`, `PenSizeZ 12`, `PenSizeY 4`, `PenFacing south`, `ChairSet true`; the chair at world (feet.x - 1, feet.y, feet.z - 8). The chair sits at local (8,1,-1) on its own floor tile: check it sits level on sloped ground and faces the pen.
11. `/sproutwatch test foo now` twice within a second: exactly one sprout and one "joined the pen" line. Two means the spawn callback is asynchronous (orphan risk).
12. Kill a sprout: expect `sprout for <login> removed (...)` then a respawn next tick. Walk far enough to unload the pen chunks with sprouts live, return, and count younglings after the ticker catches up (evicted registry vs persisted NPCs could duplicate).
13. Unload/reload the pen world (or restart with it unloaded): ticks resume without a command; the boot log shows exactly one `cleared N sprout(s)` per boot, and `start`/`stop`/`start` never sweeps again.
14. Camera fields untested by vanilla: confirm `AttachedToType.None` + `PositionType.Custom` puts the camera at the world point (else try `AttachedToType.LocalPlayer`); confirm `baseFov` is degrees and whether vertical or horizontal (compare 60 vs 90); confirm WASD is camera-relative with the manual toggle on and that the fixed rotation does not fight mouse look.
15. `/sproutwatch camera 12 0 70` sets `back` to 0 (sentinel fix); `/sproutwatch camera` twice toggles on then off.
16. Disconnect while seated, reconnect, run a camera tuning edit: you must NOT receive the pen camera while standing (disconnect hook).
17. `/sproutwatch status` renders as multiple lines; permission `<base>.admin` gates every subcommand for a non-op; console can run `status`/`start`/`stop`.
18. Obstructed pen (a few blocks inside, or `PenSizeY` 1): spawns fail quietly at FINE. FINE lines are invisible unless the plugin logger is raised (`--log <pluginLoggerName>=FINE`; the name is `<pluginName>|P`). Decide whether a WARNING after N misses is wanted.
19. Re-running `place` elsewhere sweeps the old pen's sprouts first (reply ends with "Cleared N sprout(s) from the old pen"); the old pen's blocks remain in the world.

20. Place the pen in world A, `test alice now`, then `/sproutwatch place` in a different world B and wait past grace (or `clear`): no "failed to despawn"/"failed to remove" WARNING and no wrong-thread assertion in the log; the registry entry is dropped and A's younglings are left in place (by design).
21. `/sproutwatch stop`, wait longer than GraceSeconds, then `/sproutwatch test bob now`: every other sprout despawns (the roster was cleared on stop, so a forced tick treats them as gone). Confirm this is wanted or document it next to item 6.

- [ ] **Step 4: Record outcomes**

Add a `## Verification 2026-xx-xx` section at the bottom of this plan with the checklist results and any config values that were tuned (CameraHeight/Back/Fov/Flip).

---

the command `/sproutwatch test bob now` now says it worked but I do not see the npc

can we makew the pen 3:4 diemnsions, we overocrrected

can we set the default camera to 16 20 30


### Task 18: Review and commit handoff

- [ ] **Step 1: Full test run**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`, 60 tests.

- [ ] **Step 2: Stage and summarise**

Run: `git add -A && git status --short && git diff --cached --stat | tail -3`

- [ ] **Step 3: Ask Mertie**

Post the test count, the in-game checklist results, and this proposed first commit message, then STOP and wait for an explicit go-ahead before running `git commit` (never push; no remote exists yet):

```
feat: sproutwatch v0.1.0 — Twitch viewers as baby Kweebecs in a pen

Anonymous IRC membership roster, per-tick reconciliation into a fenced 16x12
pen pasted from a bundled prefab, Nameplate per viewer, chair-triggered fixed
4:3 landscape camera. 61 unit tests; in-game checklist in docs/superpowers/plans.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016zNY2952ZXsGfbC8JfYWsz
```

---

## Self-review notes (written with the plan)

- Spec coverage: §4 Tasks 3-5; §5.1-5.6 Tasks 6-8, 12-13; §5.7 Task 12 (PenClearer) + Task 15 setup; §6 Tasks 9, 11; §7 Tasks 10, 14; §8 Task 2; §9 Task 16; §10 error handling is inline in Tasks 5, 12-14; §11 Tasks 2-10 tests + Task 17 manual list; §12 answered in the research table.
- Deliberate omissions: no `.lang` file (nameplates), no `PenCamera` pitch/yaw fields (the engine's `Rotation3f.lookAt` does that at packet-build time), `/sproutwatch camera` gained optional tuning arguments so the three camera numbers can be adjusted without editing JSON.
- Names used consistently across tasks: `PenRegistry.Entry(login, ref, networkId, worldUuid, spawnedAt, lastSeen)`, `registry.touch/lastSeenMap/removeByRef/clear`, `PenBounds(minX, floorY, minZ, sizeX, sizeZ, clearHeight)`, `PenLayout.analyze(blocks, isSeat)`, `PenCamera.of(bounds, facing, height, back)`, `CameraPackets.penCamera(cfg)/reset()`, `PenTicker.resolveWorld(cfg)`, `plugin.startListener()/stopListener()/saveConfig()/roleSet()`.

---

## Verification 2026-09-27

Automated (Claude, evening session):

- Tasks 1-16 implemented via subagent-driven development; every task passed a spec-compliance review and a code-quality review, plus a final whole-implementation review (spec coverage, cross-package contracts, threading, lifecycle, packaging). Review-driven changes are listed under "Deviations applied during execution" below.
- `./gradlew clean build`: BUILD SUCCESSFUL, **59 tests**, 0 failures (config 15, twitch 15, pen 17, prefab 7, camera 5).
- `./deploy.sh`: `sproutwatch-0.1.0.jar` (96208 bytes) deployed to `UserData/Mods` at 23:41.
- Headless boot (Task 17 step 2), run three times on successive jars and once on the deployed jar: `Mertie:sproutwatch` loads, its asset pack loads, `Enabled plugin Mertie:sproutwatch`, `Universe ready!`, `Sproutwatch_config.json` written identical to the shipped defaults, orderly `Shut down plugin`. No `FAIL:`, `validation failed`, `Unknown JSON attribute` or plugin SEVERE lines. (The `[SERR] Reallocate` lines are engine stderr noise present regardless of this plugin.)
- Task 17 step 3 (in-game checklist items 1-21): **pending Mertie**. Record pass/fail per item here, plus any tuned CameraHeight/Back/Fov/Flip values.
- Task 18 commit: **pending Mertie's go-ahead**. Zero commits on `dev`; 51 files staged.

Deviations applied during execution (beyond the plan's numbered list):

- Task 3: two extra parser tests (degenerate input never throws; IRCv3 `\s` tag escapes).
- Task 6/8: stronger test assertions (byRef mirror after remove/clear via rejoin; random-point extremes reached); comments only in `PenBounds`.
- Task 13 `PenTicker`: prune registry entries whose entity ref is invalid or belongs to another world before touching; `dispatch` catches `Throwable` (SEVERE); `isRunning` uses `!isDone`; `shutdown` synchronized and `start` no-ops after shutdown; `Universe.get()` null-guarded; `setOnWorldReady(Consumer<World>)` one-shot hook; despawn INFO only on success.
- Task 14: `CameraPackets` javadoc corrected (`Rotation3f.lookAt` is `(eye, target)`, verified in decompiled source; `CameraFlip` is a safety toggle); `ChairCameraService.forget(UUID)`; manual toggle and refresh failures logged WARNING.
- Task 15 `SproutwatchPlugin`: `stopListener` stops the Twitch client before clearing the roster; boot sweep runs once per boot when the pen world first resolves (plugin `setup()` runs before worlds load, so the plan's setup-time sweep never fired); `PlayerDisconnectEvent` -> `cameraService.forget`; JUL bridge handler removed on shutdown; `runOnWorld` helper guards `world.execute` during world unload.
- Task 16 `SproutwatchCommand`: camera tuning uses `ctx.provided(arg)` so `back 0` is settable; `test <login> now` reports an unloaded pen world instead of silently skipping.
- Task 17 recipe: headless boot must run from a cwd that does not contain `mods/` (duplicate-plugin trap); reviewer checks 10-21 added to the in-game list.

### In-game round 1 (2026-09-28 morning, Mertie)

What worked (from the server log):

- `/sproutwatch place`: pen pasted and saved (interior min, size, facing and chair logged).
- `/sproutwatch channel` + `/sproutwatch start`: listener connected and the Twitch membership feed was granted; real viewers spawned one per tick until the 30-sprout cap was reached.
- `/sproutwatch clear`: removed 19 sprouts, later 30.
- `/sproutwatch interval`: values below 5 clamped to 5s.
- `/sproutwatch stop` then `start`: both survive and the listener resumes.

Mertie's notes (verbatim):

- ` he command `/sproutwatch test bob now` does not work`
- `Unclear what 'membership ACk` values prepresents in `status` command`
- `the camera view is weird on second sitting. unclear if thatw as from the `camera` command `

Root causes (from the decompiled 0.6.3 `com.hypixel.hytale.server.core.command.system.*`) and fixes applied:

1. **Optional args are `--name value` only.** `withDefaultArg`/`withOptionalArg`/flags are recognised solely in `--name value` / `--name=value` form (`ParserContext.ARG_NAME_PATTERN = --([\w-]*)`). A bare extra positional token (`now`, or `2 6 50`) counts toward `numberOfPreOptionalTokens` and `AbstractCommand.acceptCall0` rejects the call with "wrongNumberRequiredParameters" unless a usage variant with that many required parameters exists. Fix: `test` and `camera` now register usage variants (`addUsageVariant`, description-only `AbstractCommand(String)` ctor, own `RequiredArg`s): `TestNowVariant` (`<login> <when>`, `when` must be `now`) and `CameraTuneVariant` (`<height> <back> <fov>`); the shared body of `test` lives in `SproutwatchCommand.applyTest`.
2. **`CommandContext.get(DefaultArg)` inserts the default into `argValues`,** so `ctx.provided(arg)` returns true after any `get`. That is why a bare `/sproutwatch camera` saved -1.0 into CameraHeight/Back/Fov (the "weird" second sitting). Fix: `camera` no longer has default args at all; bare `camera` only toggles, and tuning is the three-required-arg variant. Mertie: re-tune with `/sproutwatch camera <height> <back> <fov>` (or fix the -1.0 values in `Sproutwatch_config.json`) before the next sitting.
3. **"Membership ACK" was jargon.** `status` now prints `Twitch JOIN/PART feed: on` or `OFF (Twitch did not grant membership; only viewers who chat will appear)`.
4. **Parameterised JUL messages print raw on the server.** The server installs `HytaleLogManager`, so `Logger.getLogger("Sproutwatch")` is a `HytaleJdkLogger` whose `log(LogRecord)` goes straight to the engine backend, bypassing handlers, and the backend prints `record.getMessage()` unformatted (observed: `Sproutwatch pen placed: interior min {0},{1},{2} ...`). Our JUL bridge handler never runs on the real server. Fix: every log call in `src/main` now concatenates its message (`PenPlacer`, `TwitchMembershipClient` reconnect warning, `SproutSpawner` FINE); `grep -rn '{0}' src/main` is empty; comment added above the bridge handler in `SproutwatchPlugin.createBridgeLogger`. New test `TwitchMembershipClientTest.reconnectWarningIsFullyFormatted` (fail-then-pass) guards the reconnect warning.

Not yet re-verified in game: `test <login> now`, `camera <h> <b> <f>`, the new `status` wording. Round 2 pending Mertie.

### Design change 2026-09-28: pen 36 wide, fence 1 high (pen size superseded by in-game round 2 below)

- Why: Hytale fences are one block tall (the two-high ring was a Minecraft assumption), and the camera should frame a landscape from the chair rather than a 3:4 portrait down the long axis. The pen interior became 36 wide by 12 deep (three to one; overcorrected, now 16 wide, see round 2) with the chair centred outside the -z long side at local (18,1,-1); the fence ring is a single row at y=1.
- Fence orientation (from vanilla `Server/Prefabs/Npc/Kweebec/Autumn/Bunny_Area/Kweebec_Autumn_Bunny_Area_001.prefab.json`; a ring sharing one rotation leaves gaps the Kweebecs walk through): straight runs along x are `Wood_Hardwood_Fence` rotation 0, runs along z rotation 1; the four corners are `*Wood_Hardwood_Fence_State_Definitions_Corner` (leading asterisk as vanilla writes it) with rotation by the sides joined: east+south 1 at (0,0), west+south 0 at (37,0), east+north 2 at (0,13), west+north 3 at (37,13). `PenPrefabTest` asserts exactly four corner blocks.
- Camera defaults raised to `CameraHeight 16.0`, `CameraBack 8.0` (fov 60; now 16/20/30, see round 2) in `SproutwatchConfig` and `Sproutwatch_config.json` so the wider pen is roughly framed out of the box.
- Reviewer item 10 arithmetic (for the 36-wide pen; round 2 has the current numbers): `PenPlacer` centres on floor(1 + 36/2) = 19 and floor(1 + 12/2) = 7, so paste pos = feet - (19, 1, 7); interior min world = (feet.x - 18, feet.y - 1, feet.z - 6); chair (18,1,-1) -> (feet.x - 1, feet.y, feet.z - 8).
- In game: `/sproutwatch place` must be re-run to paste the new pen and refresh config. The OLD pen's blocks (fence, grass, chair) are NOT removed by the mod and remain in the world; clear them by hand or place the new pen elsewhere.

### In-game round 2 (2026-09-28, Mertie)

Mertie's notes (verbatim):

- `the command `/sproutwatch test bob now` now says it worked but I do not see the npc`
- `can we makew the pen 3:4 diemnsions, we overocrrected`
- `can we set the default camera to 16 20 30`

Log evidence for the first note: each forced tick after `/sproutwatch test <login> now` spawned a real viewer instead of the test login (`fhellipelllipe`, then `its_destroyah`); an earlier attempt with the pen at 30/30 spawned nothing at all, while the command still replied "ticking now".

Root cause: `PenReconciler` spawns the roster login with the smallest `firstSeen`. `applyTest` stamped the test login with `System.currentTimeMillis()`, making it the newest entry, so a forced tick served the longest-waiting real viewer first, and with the pen at cap it served nobody.

Changes applied:

- **A. `/sproutwatch test <login> now` spawns that login.** `SproutwatchCommand.applyTest` now applies the roster event with timestamp `0L` so the test login jumps to the front of the spawn queue (`ChatRoster.apply` uses `putIfAbsent`, so an existing entry keeps its time). Before ticking, the `now` branch checks honestly: `<login> is already in the pen.` when `PenRegistry.contains(login)`, and `Pen is full (<size>/<max>); run /sproutwatch clear or raise MaxSprouts.` when the registry is at `MaxSprouts`; the unloaded-world check stays. Replies now say "added to the front of the queue". New unit test `PenReconcilerTest.zeroTimestampJumpsTheQueue` documents the ordering contract the command relies on.
- **B. Pen is 16 wide x 12 deep (4:3 landscape from the chair).** `scripts/gen_pen_prefab.py` `WIDTH, DEPTH = 16, 12`, chair at local (8,1,-1); fence still one row at y=1 with the vanilla rotation/corner rule (32 straight fences rotation 0 on z=0|13, 24 rotation 1 on x=0|17; corners (0,0) r1, (17,0) r0, (0,13) r2, (17,13) r3); interior `Empty` y=1..4 over x 1..16, z 1..12; 1085 blocks, bbox x 0..17, z -1..13, y 0..4. `PenLayoutTest.analysesTheBundledPen` now expects 16x12 / chair x 8 (failed `expected: <16> but was: <36>` before regeneration, passes after); `PenPrefabTest` size bound lowered to > 1000, four-corner assertion kept. Item 10 arithmetic: `PenPlacer` centres on floor(1 + 16/2.0) = 9 and floor(1 + 12/2.0) = 7, so pos = feet - (9, 1, 7): `PenX = feet.x - 8`, `PenY = feet.y - 1`, `PenZ = feet.z - 6`, sizes 16/12/4, chair world (feet.x - 1, feet.y, feet.z - 8). In game: re-run `/sproutwatch place`; the old pen's blocks are not removed by the mod.
- **C. Camera defaults 16 / 20 / 30.** `SproutwatchConfig` defaults and not-finite fallbacks: `cameraHeight 16.0` (unchanged), `cameraBack 20.0` (was 8.0), `cameraFov 30.0` (was 60.0; inside the 10..170 clamp). `Sproutwatch_config.json` matches; `SproutwatchConfigTest` fallback expectations updated and `clampsBadValues` uses `setCamera(16, 20, 500)` -> fov 170. An existing `mods/Mertie_sproutwatch/Sproutwatch_config.json` keeps its own values; edit it or run `/sproutwatch camera 16 20 30`.

Not yet re-verified in game: `test <login> now` with the queue-jump, the 16x12 pen framing at 16/20/30. Round 3 pending Mertie.

### In-game round 3 (2026-09-28, Mertie): one fence side faced inward

Straight fence segments come in two rotations per axis. The vanilla Bunny_Area pen mirrors them by which side the interior is on: north row (interior toward +z) rotation 2, south row 0, west column (interior toward +x) rotation 3, east column 1. Round 2 used 0 and 1 on both rows and both columns, so the north row and west column had their rails facing inward. Generator fixed; corners unchanged. Everything else in round 3 passed from the log: place (16x12, formatted log line), start, one viewer per 5 s to the cap, no plugin warnings. Known harmless engine warning: "Skipping pack at Mertie_sproutwatch: missing or invalid manifest.json" (the engine probes the plugin's config folder inside the save as an asset pack; Subinator gets the same).

### Feature 2026-09-28: bottom HUD hidden while seated

Requested after round 3. `ChairCameraService` now hides the bottom UI (hotbar, utility slot, health/stamina/mana/oxygen, abilities, ammo, status icons, reticle, input bindings, compass; chat and notifications stay) via the player's `HudManager.hideHudComponents` on sit and restores the exact previous set with `setVisibleHudComponents` on stand (fallback `resetVisibleHudComponents`); the same calls vanilla game modes use from an ECS system. Disconnect drops the saved set. Manual `/sproutwatch camera` toggle is unaffected. Checklist: sit -> bottom UI gone, stand -> back exactly as before.

### Feature 2026-09-28: player kills retire a sprout

Rule: a sprout killed by a monster (or anything that is not a player) respawns on the next tick as before; a sprout killed by a **player** retires its viewer, who gets no sprout again while they remain in chat. Retirement ends when the viewer drops out of the roster or on `/sproutwatch clear`.

Mechanism: new `pen/SproutDeathSystem`, a `DeathSystems.OnDeathSystem` with a match-everything query ordered after `ClearEntityEffects`, the same shape as Subinator's `BossDeathSystem`. The attacker check is vanilla `DeathSystems.PlayerKilledPlayer`'s: `DeathComponent.getDeathInfo().getSource() instanceof Damage.EntitySource` whose ref carries a `PlayerRef`. The registry entry is removed on death (`removeByRef`), so the cap slot is freed immediately instead of when the corpse entity is removed (`PenDespawnSystem` then finds nothing, harmless). `PenRegistry` gained `findByRef`, `retire`, `isRetired`, `retiredLogins`, `retainRetired`; `clear()` also drops retired logins. `PenTicker` calls `retainRetired(roster)` each tick (leaving chat un-retires) and reconciles with the roster minus retired logins. `status` prints `Retired (killed by a player): n` when non-zero; `/sproutwatch test <login>` refuses a retired login with an explanation. Three registry tests added (fail-then-pass): `findByRefReturnsEntryWithoutRemoving`, `retireIsSetLikeAndPrunedByRetain`, `clearAlsoDropsRetired`.

Checklist:

- [ ] Let a monster kill a sprout: log shows `<login>'s sprout died (<cause>); respawning next tick` and it returns on the next tick.
- [ ] Kill one yourself: log shows `<login>'s sprout was killed by <you>; retired until they leave chat`, it does not return, `status` shows `Retired (killed by a player): 1`, `/sproutwatch test <that login> now` explains the retirement, and after `/sproutwatch clear` it can return.

Killer attribution (verified in the 0.6.3 source): the death's damage source is the entity that ran the attack. A player's melee, arrow (projectile source resolves to the shooter) or player-owned explosion counts as a player kill; a player's pet or summon uses its own ref and does not; `/kill` and `/damage` use a command source and do not. Retirement lives in memory only and normally survives `/sproutwatch stop` + `start`, but if Twitch has not delivered the NAMES list by the first tick after `start`, the retained-retirement pass sees an empty roster and lifts it. Extra checklist: bow kill retires; `/kill` does not; retired viewer leaving and rejoining chat gets a sprout again.

### Feature 2026-09-28: allow list and !sprout priority queue

Decisions (Mertie): (1) the queue is a **priority** over the normal first-seen order, not a gate: everyone in chat still spawns, queued viewers just go first; (2) a queue entry is consumed when that viewer spawns, and leaving chat drops them from the queue; (3) `AllowUsers`, when non-empty, restricts who is eligible at all, and `IgnoreUsers` still applies on top (deny wins).

Config keys: `AllowUsers` (String[], default empty = everyone eligible) and `QueueCommand` (default `!sprout`; a PRIVMSG whose trimmed text equals it case-insensitively queues the sender). Both are read live (`SproutwatchConfig.allowedLogins()`, `getQueueCommand()`), as is `ignoredLogins()`, so the commands below take effect without a restart.

Commands: `/sproutwatch allow list | add <login> | remove <login>`, `/sproutwatch ignore list | add <login> | remove <login>` (the list output notes that it includes the channel login), `/sproutwatch queue list | clear | remove <login>`. `status` now prints `Queue: N waiting (type !sprout in chat)` and `Allow list: everyone | N logins`. `test` replies now say "added as a test viewer" (the test login still jumps first-seen order via firstSeen 0L but does not touch the queue, so queued real viewers spawn before a test viewer).

Mechanism: `twitch/SproutQueue` (synchronized FIFO, no duplicates) is fed by `ChatRoster` (Chat with the queue command offers; Part and clear() remove). `PenTicker` each tick: `queue.retain(roster)` (a viewer who left chat leaves the queue), eligible = roster minus retired, then `retainAll(allowedLogins())` when the allow list is non-empty, then `PenReconciler.reconcile(eligible, pen, queue.snapshot(), cap, grace, now)`: the first queued login that is eligible and not in the pen wins, else min firstSeen. The queue entry is removed only when `SproutSpawner.spawn` returns true, so a failed spawn keeps the viewer at the front.

Checklist:

- [ ] (a) As a viewer type `!sprout`: `/sproutwatch queue list` shows them, they spawn on the next tick before earlier-seen viewers, and afterwards they are gone from `queue list`.
- [ ] (b) `/sproutwatch allow add <x>`: only x spawns (others wait, `status` shows `Allow list: 1 logins`); `/sproutwatch allow remove <x>` restores everyone.
- [ ] (c) `/sproutwatch ignore add <y>` while y is in chat: y's sprout despawns after the grace window and they never respawn; `ignore remove <y>` lets them back in after their next JOIN/message.
- [ ] (d) A queued viewer who leaves chat (PART, or missing from NAMES) disappears from `queue list` and does not spawn.

Review follow-up (same day): `ignore add <login>` now also removes that viewer from the roster and queue immediately; the ticker refreshes presence only for eligible logins (not retired, on the allow list when one is set), so a viewer who becomes ignored or not-allowed while in chat has their sprout age out after GraceSeconds. `/sproutwatch test` refuses ignored and not-allowed logins with an explanation. A queued viewer who is not eligible stays queued (bounded by chat presence) and spawns once eligible; a queue head whose spawn keeps failing is retried each tick rather than rotated (failures are pen-wide, not viewer-specific).

### Feature 2026-09-29: persist toggle

Rule (Mertie): with persist ON a sprout stays after its viewer leaves chat (GraceSeconds is ignored). When the pen is at MaxSprouts and an eligible viewer is waiting, the sprout whose viewer has been gone the longest (smallest untouched `lastSeen` among pen logins not in the roster) is despawned to make room, one swap per tick; if every sprout's viewer is still present, nothing is despawned and the newcomer waits. Below the cap spawns proceed as before. Player-kill retirement, allow/ignore lists and the `!sprout` priority queue are unchanged. Persist OFF is the previous behaviour (despawn once `lastSeen` is older than GraceSeconds).

Config key: `PersistSprouts` (boolean, default `true` since 2026-09-29; `isPersistSprouts()` / `setPersistSprouts(boolean)`; the shipped JSON now has 26 keys).

Command: `/sproutwatch persist` toggles, `/sproutwatch persist on|off` sets explicitly (saved to config, effective on the next tick). `status` prints `Persist: on (longest-gone replaced at the cap)` or `Persist: off (grace <GraceSeconds>s)` under the Pen line.

Mechanism: `PenReconciler.reconcile(roster, pen, priority, cap, graceMillis, now, persist)` (7-arg; the 6-arg overload delegates with `persist = false`). With persist the grace pass is skipped, the spawn candidate is picked exactly as before (queue head, else min firstSeen), `room = pen.size() < cap`; at the cap with a candidate, the pen login not in the roster with the smallest `lastSeen` (ties by login) becomes the single despawn, otherwise the spawn is dropped. `PenTicker` passes `cfg.isPersistSprouts()`; because it only touches ELIGIBLE logins, an absent viewer's `lastSeen` stays at their departure, which is what "gone the longest" orders on. The despawn log line reads `Sproutwatch: <login> replaced to make room (viewer gone from chat)` when persist is on. Unit tests: `PenReconcilerTest.persist*` and `nonPersistUnchanged`, `SproutwatchConfigTest.persistDefaultsOn` (93 tests).

Checklist:

- [ ] (a) `/sproutwatch persist on`: reply confirms; `status` shows `Persist: on ...`; `Sproutwatch_config.json` has `"PersistSprouts": true`.
- [ ] (b) A viewer leaves chat (PART, or `/sproutwatch stop` clears the roster): their sprout stays past GraceSeconds.
- [ ] (c) Fill the pen to MaxSprouts (lower it with the config if needed) with at least one absent viewer's sprout among them.
- [ ] (d) A newcomer (`/sproutwatch test <login> now` or a real JOIN) replaces the longest-gone sprout: the log shows `Sproutwatch: <old> replaced to make room (viewer gone from chat)` and the newcomer spawns in the same tick.
- [ ] (e) With every sprout's viewer still in chat and the pen full, a newcomer waits; no sprout is replaced.
- [ ] (f) `/sproutwatch persist off`: sprouts of absent viewers despawn once GraceSeconds has passed (old log line), `status` shows `Persist: off (grace Ns)`.

Persist is ON by default (owner request 2026-09-29). Turning it off with many absent viewers despawns every sprout already past GraceSeconds on the very next tick.
