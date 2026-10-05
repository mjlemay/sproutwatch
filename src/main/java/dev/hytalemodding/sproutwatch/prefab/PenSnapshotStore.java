package dev.hytalemodding.sproutwatch.prefab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The one saved-ground file (one pen at a time). Plain file I/O, no engine.
 *
 * Writes happen inline on the pen world thread: the bundled prefab has 1085 blocks, so a snapshot
 * holds at most that many (only blocks the paste actually changed are recorded). Measured: all 1085
 * blocks written as extended JSON come to 110,450 characters, so a snapshot is about 110 KB at the
 * very most and a few milliseconds to write. Writing inline keeps the order of save
 * and delete obvious (an off-thread write could land after a later delete and bring back a stale
 * snapshot).
 */
public final class PenSnapshotStore {

    private final Path file;

    public PenSnapshotStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** The saved ground, or empty when there is no file. @throws IOException when the file cannot be read or parsed */
    public synchronized Optional<PenSnapshot> load() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return Optional.of(PenSnapshot.fromJson(json));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unreadable pen snapshot " + file + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * Replaces the file with this snapshot: written to a uniquely named temporary file in the same
     * directory first, then moved over, so two saves can never write into the same temporary file.
     */
    public synchronized void save(PenSnapshot snapshot) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, snapshot.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    /** Deletes the file only while it still holds that snapshot (a newer pen may have saved its own since). */
    public synchronized void deleteIfStill(PenSnapshot expected) throws IOException {
        if (expected != null && load().filter(expected::equals).isPresent()) Files.deleteIfExists(file);
    }

    /**
     * Moves an unreadable file aside to the same name plus ".bad" (replacing an older one), so the
     * next attempt finds no saved ground and uses the air fallback on purpose.
     * @return where the file went
     */
    public synchronized Path setAside() throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + ".bad");
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        return aside;
    }
}
