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
        return readBundledJson(RESOURCE);
    }

    /** @param resource absolute classpath path of a bundled prefab JSON (see PenPrefabCatalog) */
    public static String readBundledJson(String resource) throws IOException {
        try (InputStream in = PenPrefab.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Bundled prefab missing from jar: " + resource);
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
