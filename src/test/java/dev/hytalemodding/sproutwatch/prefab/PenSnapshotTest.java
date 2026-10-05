package dev.hytalemodding.sproutwatch.prefab;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The saved-ground wrapper and its file. The selection document here is hand-built: a real one comes
 * from SelectionPrefabSerializer.serialize, which needs the engine's block asset map, so the engine
 * side (capture on place, paste back on remove) is only provable in game.
 */
class PenSnapshotTest {

    private static BsonDocument selection() {
        BsonDocument block = new BsonDocument()
            .append("x", new BsonInt32(-3)).append("y", new BsonInt32(0)).append("z", new BsonInt32(7))
            .append("name", new BsonString("Soil_Grass"))
            .append("components", new BsonDocument("Stamp", new BsonInt64(1234567890123L)));
        return new BsonDocument()
            .append("version", new BsonInt32(8))
            .append("anchorX", new BsonInt32(0)).append("anchorY", new BsonInt32(0)).append("anchorZ", new BsonInt32(0))
            .append("blocks", new BsonArray(List.of(block)));
    }

    private static PenSnapshot snapshot() {
        return new PenSnapshot("world-1", 10, 64, 20, 4, 63, 14, selection());
    }

    @Test void jsonRoundTripKeepsEveryFieldAndNumberType() {
        PenSnapshot original = snapshot();
        PenSnapshot restored = PenSnapshot.fromJson(original.toJson());
        assertEquals(original, restored);
        BsonDocument stamp = restored.selection().getArray("blocks").get(0).asDocument().getDocument("components");
        assertTrue(stamp.get("Stamp").isInt64(), "a long in a block component must stay a long");
    }

    @Test void fromJsonRejectsMissingFieldsAndGarbage() {
        assertThrows(IllegalArgumentException.class, () -> PenSnapshot.fromJson("{\"World\": \"world-1\"}"));
        assertThrows(IllegalArgumentException.class, () -> PenSnapshot.fromJson("not json"));
    }

    @Test void matchesOnlyTheSamePenWorldAndInterior() {
        PenSnapshot ground = snapshot();
        assertTrue(ground.matches(new PenSite("world-1", 10, 64, 20, "default")));
        assertFalse(ground.matches(new PenSite("world-2", 10, 64, 20, "default")));
        assertFalse(ground.matches(new PenSite("world-1", 11, 64, 20, "default")));
        assertFalse(ground.matches(new PenSite("world-1", 10, 65, 20, "default")));
        assertFalse(ground.matches(new PenSite("world-1", 10, 64, 21, "default")));
    }

    @Test void storeWritesReadsAndDeletesTheFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("pen-restore.json");
        PenSnapshotStore store = new PenSnapshotStore(file);
        assertEquals(Optional.empty(), store.load(), "no file yet");
        store.save(snapshot());
        assertTrue(Files.exists(file));
        assertEquals(Optional.of(snapshot()), store.load());
        PenSnapshot moved = new PenSnapshot("world-2", 1, 2, 3, 0, 1, 2, selection());
        store.save(moved);
        assertEquals(Optional.of(moved), store.load(), "one snapshot at a time: a save replaces the old one");
        store.delete();
        assertFalse(Files.exists(file));
        assertEquals(Optional.empty(), store.load());
        store.delete();   // deleting nothing is fine
    }

    @Test void storeLoadThrowsOnACorruptFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("pen-restore.json");
        Files.writeString(file, "{ broken");
        assertThrows(IOException.class, () -> new PenSnapshotStore(file).load());
    }

    @Test void storeCreatesTheDataDirectoryWhenMissing(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("not-yet").resolve("pen-restore.json");
        new PenSnapshotStore(file).save(snapshot());
        assertTrue(Files.exists(file));
    }

    @Test void setAsideMovesTheFileToBadSoTheNextLoadFindsNone(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("pen-restore.json");
        Files.writeString(file, "{ broken");
        PenSnapshotStore store = new PenSnapshotStore(file);
        assertEquals(directory.resolve("pen-restore.json.bad"), store.setAside());
        assertFalse(Files.exists(file));
        assertEquals("{ broken", Files.readString(directory.resolve("pen-restore.json.bad")));
        assertEquals(Optional.empty(), store.load());
    }

    @Test void deleteIfStillKeepsANewerSnapshot(@TempDir Path directory) throws IOException {
        PenSnapshotStore store = new PenSnapshotStore(directory.resolve("pen-restore.json"));
        PenSnapshot old = snapshot();
        PenSnapshot newer = new PenSnapshot("world-2", 1, 2, 3, 0, 1, 2, selection());
        store.save(newer);
        store.deleteIfStill(old);
        assertEquals(Optional.of(newer), store.load(), "the newer pen's ground stays");
        store.deleteIfStill(newer);
        assertEquals(Optional.empty(), store.load());
    }

    @Test void saveLeavesNoTemporaryFilesBehind(@TempDir Path directory) throws IOException {
        PenSnapshotStore store = new PenSnapshotStore(directory.resolve("pen-restore.json"));
        store.save(snapshot());
        store.save(snapshot());
        try (var files = Files.list(directory)) {
            assertEquals(List.of(directory.resolve("pen-restore.json")), files.toList());
        }
    }
}
