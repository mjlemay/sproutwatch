package dev.hytalemodding.sproutwatch.prefab;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * The ground a pen paste replaced, saved so Remove pen (and moving the pen) can put it back, even
 * after a server restart. Pure: the selection is kept as the document SelectionPrefabSerializer
 * wrote, so this class never touches the engine.
 *
 * The file (pen-restore.json in the plugin's data directory) is one JSON object:
 * - Version: 1
 * - World: the pen world's UUID
 * - InteriorX, FloorY, InteriorZ: the pen interior the config recorded at placement; a snapshot is
 *   only used for the pen it was taken with (see {@link #matches})
 * - OriginX, OriginY, OriginZ: the world position of the selection's local block (0, 0, 0)
 * - Selection: the replaced blocks as SelectionPrefabSerializer.serialize wrote them (local block
 *   coordinates plus the anchor; the selection's position is not part of that format)
 *
 * Written as extended JSON so every number keeps its exact BSON type (a long inside a block
 * component stays a long after a round trip).
 *
 * @param selection the serialized selection of the replaced blocks
 */
public record PenSnapshot(String worldUuid, int interiorX, int floorY, int interiorZ,
                          int originX, int originY, int originZ, BsonDocument selection) {

    private static final int VERSION = 1;
    private static final JsonWriterSettings WRITER_SETTINGS = JsonWriterSettings.builder()
        .outputMode(JsonMode.EXTENDED).indent(false).build();

    /** True when this ground was saved for the pen at that site (same world, same interior corner). */
    public boolean matches(PenSite site) {
        return worldUuid.equals(site.worldUuid())
            && interiorX == site.interiorX() && floorY == site.floorY() && interiorZ == site.interiorZ();
    }

    public String toJson() {
        BsonDocument document = new BsonDocument()
            .append("Version", new BsonInt32(VERSION))
            .append("World", new BsonString(worldUuid))
            .append("InteriorX", new BsonInt32(interiorX))
            .append("FloorY", new BsonInt32(floorY))
            .append("InteriorZ", new BsonInt32(interiorZ))
            .append("OriginX", new BsonInt32(originX))
            .append("OriginY", new BsonInt32(originY))
            .append("OriginZ", new BsonInt32(originZ))
            .append("Selection", selection);
        return document.toJson(WRITER_SETTINGS);
    }

    /** @throws IllegalArgumentException when the text is not a version 1 snapshot */
    public static PenSnapshot fromJson(String json) {
        try {
            BsonDocument document = BsonDocument.parse(json);
            int version = document.getInt32("Version").getValue();
            if (version != VERSION) throw new IllegalArgumentException("Unsupported pen snapshot version " + version);
            return new PenSnapshot(
                document.getString("World").getValue(),
                document.getInt32("InteriorX").getValue(),
                document.getInt32("FloorY").getValue(),
                document.getInt32("InteriorZ").getValue(),
                document.getInt32("OriginX").getValue(),
                document.getInt32("OriginY").getValue(),
                document.getInt32("OriginZ").getValue(),
                document.getDocument("Selection"));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Not a pen snapshot: " + exception.getMessage(), exception);
        }
    }
}
