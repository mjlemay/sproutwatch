package dev.hytalemodding.sproutwatch.assets;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the shipped sprout model assets: every clothing slot can be empty, the dress is the rarest outfit. */
class SproutCosmeticsTest {

    private static final Path MODELS = Path.of("src/main/resources/Server/Models/Sproutwatch");
    private static final Path ROLES = Path.of("src/main/resources/Server/NPC/Roles/Sproutwatch");
    private static final List<String> COLORS = List.of("Red", "Orange", "Pink", "Yellow", "Green", "Brown");

    private static BsonDocument read(Path path) throws IOException {
        return BsonDocument.parse(Files.readString(path));
    }

    private static List<BsonDocument> sproutModels() throws IOException {
        List<BsonDocument> out = new java.util.ArrayList<>();
        out.add(read(MODELS.resolve("Sprout_Sproutling.json")));
        for (String c : COLORS) out.add(read(MODELS.resolve("Sprout_Sapling_" + c + ".json")));
        return out;
    }

    private static double weight(BsonValue option) {
        return option.asDocument().getNumber("Weight").doubleValue();
    }

    @Test void everyClothingSlotHasANoneOptionAndExplicitWeights() throws IOException {
        for (BsonDocument m : sproutModels()) {
            BsonDocument sets = m.getDocument("RandomAttachmentSets");
            for (String slot : List.of("Hair", "Outfit")) {
                BsonDocument opts = sets.getDocument(slot);
                assertTrue(opts.containsKey("null"), slot + " needs a 'none' option: " + m.getString("Parent").getValue());
                for (Map.Entry<String, BsonValue> o : opts.entrySet()) {
                    assertTrue(o.getValue().asDocument().containsKey("Weight"), slot + "." + o.getKey() + " needs an explicit Weight");
                }
            }
        }
    }

    @Test void theDressIsTheRarestOutfit() throws IOException {
        for (BsonDocument m : sproutModels()) {
            BsonDocument outfit = m.getDocument("RandomAttachmentSets").getDocument("Outfit");
            double dress = weight(outfit.get("Dress"));
            for (Map.Entry<String, BsonValue> o : outfit.entrySet()) {
                if (!o.getKey().equals("Dress")) {
                    assertTrue(dress < weight(o.getValue()), "Dress must be rarer than " + o.getKey() + " in " + m.getString("Parent").getValue());
                }
            }
        }
    }

    @Test void sproutlingHatsAreTheModsScaledCopies() throws IOException {
        // Sapling hats sit ~4px above the smaller Sproutling head; scripts/gen_sproutling_hats.py writes
        // 85% copies into the mod. Every hairstyle except the Sproutling's own bud must use one.
        BsonDocument hair = read(MODELS.resolve("Sprout_Sproutling.json")).getDocument("RandomAttachmentSets").getDocument("Hair");
        for (Map.Entry<String, BsonValue> o : hair.entrySet()) {
            if (o.getKey().equals("null") || o.getKey().equals("Bud")) continue;
            String model = o.getValue().asDocument().getString("Model").getValue();
            assertTrue(model.startsWith("NPC/Sproutwatch/Hats/"), o.getKey() + " still uses " + model);
            assertTrue(Files.exists(Path.of("src/main/resources/Common").resolve(model)), "missing " + model);
        }
    }

    @Test void eachSaplingColorRoleUsesTheModsModel() throws IOException {
        for (String c : COLORS) {
            BsonDocument role = read(ROLES.resolve("Sprout_Sapling_" + c + ".json"));
            assertEquals("Sprout_Sapling_" + c, role.getDocument("Modify").getString("Appearance").getValue());
            assertEquals("Kweebec_Sapling_" + c, read(MODELS.resolve("Sprout_Sapling_" + c + ".json")).getString("Parent").getValue());
        }
    }
}
