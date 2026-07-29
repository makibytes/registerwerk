package de.makibytes.registerwerk.marketplace.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DappListingRepository extends JpaRepository<DappListing, UUID> {

    Optional<DappListing> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<DappListing> findByPublisherEntityIdOrderByCreatedAtDesc(UUID publisherEntityId);

    // :query is cast explicitly because it also appears in a bare "IS NULL" comparison;
    // with no other type context, Hibernate/PGJDBC binds a null parameter as an
    // untyped OID and Postgres then fails the lower(...) calls with
    // "function lower(bytea) does not exist" whenever no query string is supplied.
    @Query("""
            SELECT l FROM DappListing l
            WHERE l.status = de.makibytes.registerwerk.marketplace.api.DappListingStatus.PUBLISHED
              AND (:category IS NULL OR l.category = :category)
              AND (:query IS NULL
                   OR lower(l.name) LIKE lower(concat('%', CAST(:query AS string), '%'))
                   OR lower(l.slug) LIKE lower(concat('%', CAST(:query AS string), '%')))
            ORDER BY l.updatedAt DESC
            """)
    Page<DappListing> searchCatalog(@Param("query") String query,
                                    @Param("category") String category,
                                    Pageable pageable);

    @Query("""
            SELECT DISTINCT l.category FROM DappListing l
            WHERE l.status = de.makibytes.registerwerk.marketplace.api.DappListingStatus.PUBLISHED
            ORDER BY l.category
            """)
    List<String> findDistinctPublishedCategories();
}
