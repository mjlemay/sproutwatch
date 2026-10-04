# Any Chair at the Pen Toggles the Camera — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sitting on *any* seat block within the pen's guard zone (interior + 4 blocks, the same area the pen guard protects) turns the pen camera on, hides the HUD and makes the player invulnerable; standing up turns it all off. Today only the one chair recorded at `/sproutwatch place` (`ChairX/Y/Z`) does.

**Architecture:** `ChairCameraService.onComponentAdded` currently requires `cfg.isChairSet()` and an exact match between the seat block and `ChairX/Y/Z`. Replace both with one pure, tested predicate `ChairCameraService.isPenSeat(cfg, seatBlock)` that asks `PenBounds.inGuardZone` about the seat block's centre. The camera itself is already independent of the chair (`PenCamera.of(bounds, facing, height, back)` is pure pen geometry), dismount handling is already keyed by the `seated` set, and `SeatedInvulnerabilitySystem` reads that same set, so nothing else changes behaviour.

**Tech Stack:** Java 25, Gradle, JUnit 5, Hytale server API. Working state: branch `dev`, zero commits, everything staged; checkpoints are `git add -A` only.

**Unchanged on purpose:**
- Only `BlockMountType.Seat` mounts count (beds and creature mounts never do).
- Pen-world check stays (a seat at the same coordinates in another world does nothing).
- `ChairX/Y/Z` and `chairFound` stay in config and in `/sproutwatch status` (they describe the prefab's built-in chair); they just stop gating the camera.
- The manual `/sproutwatch camera` toggle still grants no invulnerability.
- Several players seated at once each get the camera (already supported by the `seated` set).

**Edge cases decided:**
- Y band is `PenBounds.contains`'s: from the pen floor block up to `clearHeight + 2`. A chair on the ground right outside the fence is at floor level or one above, so it qualifies; a chair on a roof far above does not.
- A seat inside the pen interior also counts (sprouts never sit, so no conflict).
- Pen moved (`/sproutwatch place`) while someone is seated: unchanged from today — they keep the camera until they stand.

---

### Task 1: Pure predicate `isPenSeat` (TDD)

**Files:**
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/camera/ChairCameraService.java`
- Test: `src/test/java/dev/hytalemodding/sproutwatch/camera/ChairSeatTest.java` (new)

- [ ] **Step 1: Write the failing test**

```java
package dev.hytalemodding.sproutwatch.camera;

import org.joml.Vector3i;
import dev.hytalemodding.sproutwatch.config.SproutwatchConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChairSeatTest {

    /** Interior min (10, floor 64, 20), 16 x 12, clear height 4 — the same shape PenPlacer writes. */
    private static SproutwatchConfig pen() {
        SproutwatchConfig c = new SproutwatchConfig();
        c.setPen("11111111-2222-3333-4444-555555555555", 10, 64, 20, 16, 4, 12);
        return c;
    }

    @Test void anySeatAtThePenCounts() {
        SproutwatchConfig c = pen();
        assertTrue(ChairCameraService.isPenSeat(c, new Vector3i(17, 65, 18)), "the prefab chair, 2 outside");
        assertTrue(ChairCameraService.isPenSeat(c, new Vector3i(6, 65, 25)), "a player chair 4 outside the west side");
        assertTrue(ChairCameraService.isPenSeat(c, new Vector3i(15, 65, 25)), "inside the pen");
    }

    @Test void seatsAwayFromThePenDoNot() {
        SproutwatchConfig c = pen();
        assertFalse(ChairCameraService.isPenSeat(c, new Vector3i(4, 65, 25)), "6 outside");
        assertFalse(ChairCameraService.isPenSeat(c, new Vector3i(15, 80, 25)), "high above");
        assertFalse(ChairCameraService.isPenSeat(c, null), "no seat block");
        assertFalse(ChairCameraService.isPenSeat(new SproutwatchConfig(), new Vector3i(15, 65, 25)), "no pen placed");
    }
}
```

Verified: the service imports `org.joml.Vector3i`; `setPen(worldUuid, x, y, z, sizeX, sizeY, sizeZ)` where `sizeY` is the clear height, so `(…, 16, 4, 12)` is a 16 x 12 interior spanning x 10..25, z 20..31 with 4 blocks of clear air. The guard zone is x 6..29, z 16..35 (margin 4), which is what the test coordinates assume.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*ChairSeatTest' 2>&1 | tail -5`
Expected: compile error, `cannot find symbol: method isPenSeat`.

- [ ] **Step 3: Implement**

Add to `ChairCameraService`:

```java
    /**
     * A seat counts as the pen chair when its block centre is in the pen's guard zone (interior +
     * PenBounds.GUARD_MARGIN, floor up to clearHeight + 2): any chair a player sets next to the pen
     * works, not just the one recorded at /sproutwatch place. Pure; package-visible for tests.
     */
    static boolean isPenSeat(SproutwatchConfig cfg, Vector3i seat) {
        if (seat == null || !cfg.isPenSet()) return false;
        return PenBounds.fromConfig(cfg).inGuardZone(seat.x + 0.5, seat.y, seat.z + 0.5);
    }
```

(Import `dev.hytalemodding.sproutwatch.pen.PenBounds` if not already imported.)

- [ ] **Step 4: Run the test, then the suite**

Run: `./gradlew test 2>&1 | tail -3 && ls build/test-results/test/*.xml | xargs grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print "tests:", s}'`
Expected: `BUILD SUCCESSFUL`, `tests: 153`.

- [ ] **Step 5: Checkpoint** — `git add -A`

### Task 2: Use the predicate on mount

**Files:**
- Modify: `src/main/java/dev/hytalemodding/sproutwatch/camera/ChairCameraService.java` (`onComponentAdded`, class Javadoc)

- [ ] **Step 1: Replace the gate**

In `onComponentAdded`, replace

```java
            if (!cfg.isChairSet() || !cfg.isPenSet()) return;
```

with

```java
            if (!cfg.isPenSet()) return;
```

and replace

```java
            Vector3i seat = seatBlock(mounted);
            if (seat == null || seat.x != cfg.getChairX() || seat.y != cfg.getChairY() || seat.z != cfg.getChairZ()) return;
```

with

```java
            if (!isPenSeat(cfg, seatBlock(mounted))) return;
```

(the pen-world check between them stays as is).

- [ ] **Step 2: Update the class Javadoc's first sentence** to: "Sends the fixed pen camera to a player who sits on any seat block at the pen (PenBounds.inGuardZone: interior plus 4 blocks) and resets it when they stand up."

- [ ] **Step 3: Compile, test, grep**

Run: `./gradlew test -q 2>&1 | tail -3; grep -n 'getChairX\|isChairSet' src/main/java/dev/hytalemodding/sproutwatch/camera/ChairCameraService.java`
Expected: build green, 153 tests; grep prints nothing.

- [ ] **Step 4: Checkpoint** — `git add -A`

### Task 3: Deploy and verify in-game

- [ ] **Step 1:** `./deploy.sh` (runs the `.ui` check, builds, copies the jar).
- [ ] **Step 2:** Headless boot as in the revisions plan, Task 5 step 2; expect `Enabled plugin Mertie:sproutwatch`, no exceptions.
- [ ] **Step 3 (Mertie, in game):**
  1. Sit on the prefab's chair → camera on, HUD hidden, a skeleton hit does no damage; stand → all reset. (Regression.)
  2. Place a vanilla chair 2–3 blocks from the fence on another side, sit → same behaviour.
  3. Place a chair ~6+ blocks away, sit → nothing happens (normal sitting).
  4. Sit on a chair at the pen in a *different* world/location with the same coordinates → nothing.
  5. Two players seated on two pen chairs at once → both get the camera; each resets on their own stand-up.
- [ ] **Step 4:** Record results under `## Verification` at the bottom of this plan; checkpoint `git add -A`.

## Execution 2026-10-01 (inline, Mertie: "make sure it works for all beds and sofas")

Vanilla asset survey (release Assets.zip, Server/Item/Items): 31 items declare `"Seats"` (chairs, stools, benches, couches/sofas, e.g. Furniture_Royal_Magic_Couch) and mount as `BlockMountType.Seat`; 15 declare `"Beds"` and mount as `BlockMountType.Bed`. The engine enum has only those two values. Added `ChairCameraService.isPenMountType(type)` (Seat or Bed; null = creature mount = no) next to `isPenSeat`, so lying in any bed at the pen also gives the camera, HUD hide and invulnerability. Tests: `ChairSeatTest` (3 tests, using `SproutwatchConfigAccess().fresh()` since the config constructor is package-private) → **154 tests**.
