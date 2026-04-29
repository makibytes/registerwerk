package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.endpoint.AddressEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AddressEndpointRepository extends JpaRepository<AddressEndpoint, UUID> {

    List<AddressEndpoint> findByOwnerTypeAndOwnerId(AddressEndpoint.OwnerType ownerType, UUID ownerId);

    List<AddressEndpoint> findByOwnerTypeAndOwnerIdIsNull(AddressEndpoint.OwnerType ownerType);

    Optional<AddressEndpoint> findByOwnerTypeAndOwnerIdAndAddress(
            AddressEndpoint.OwnerType ownerType, UUID ownerId, String address);

    @Query("SELECT e FROM AddressEndpoint e WHERE e.ownerType = :ownerType AND e.ownerId IS NULL AND e.address = :address")
    Optional<AddressEndpoint> findByOperatorAndAddress(
            @Param("ownerType") AddressEndpoint.OwnerType ownerType,
            @Param("address") String address);

    @Query("SELECT e FROM AddressEndpoint e WHERE e.ownerType = :ownerType AND e.ownerId = :ownerId AND e.address IN :addresses")
    List<AddressEndpoint> findByEntityOwnerAndAddressIn(
            @Param("ownerType") AddressEndpoint.OwnerType ownerType,
            @Param("ownerId") UUID ownerId,
            @Param("addresses") Collection<String> addresses);

    @Query("SELECT e FROM AddressEndpoint e WHERE e.ownerType = :ownerType AND e.ownerId IS NULL AND e.address IN :addresses")
    List<AddressEndpoint> findByOperatorOwnerAndAddressIn(
            @Param("ownerType") AddressEndpoint.OwnerType ownerType,
            @Param("addresses") Collection<String> addresses);
}
