package de.makibytes.registerwerk.endpoint;

import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public API for network address endpoint queries. */
public interface EndpointApi {

    Optional<AddressEndpoint> findEndpoint(UUID id);

    List<AddressEndpoint> findByEntity(UUID entityId);
}
