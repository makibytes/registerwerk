package de.makibytes.registerwerk.shared;

import java.util.UUID;

public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String entityType, UUID id) {
        super(entityType + " not found with id: " + id);
    }

    public EntityNotFoundException(String entityType, String field, String value) {
        super(entityType + " not found with " + field + ": " + value);
    }
}
