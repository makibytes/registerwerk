package de.makibytes.registerwerk.stepup.web.dto;

import de.makibytes.registerwerk.stepup.api.DualControlTarget;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Reads a {@code JsonNode} with exact decimals and without tolerating repeated object keys - the same
 * parsing {@link DualControlTarget#canonicalJson(String)} applies to the live request body. The default
 * tree reader turns {@code 0.1000000000000000055511151231257827} into a {@code double}, which would let the
 * approver's side bind a different number than the one the initiator then sends.
 */
public final class ExactJsonNodeDeserializer extends StdDeserializer<JsonNode> {

    public ExactJsonNodeDeserializer() {
        super(JsonNode.class);
    }

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) {
        return DualControlTarget.readExact(parser);
    }
}
