package de.seuhd.campuscoffee.domain.impl;

import de.seuhd.campuscoffee.domain.exceptions.DuplicatePosNameException;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeMissingFieldsException;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeNotFoundException;
import de.seuhd.campuscoffee.domain.model.CampusType;
import de.seuhd.campuscoffee.domain.model.OsmNode;
import de.seuhd.campuscoffee.domain.model.Pos;
import de.seuhd.campuscoffee.domain.exceptions.PosNotFoundException;
import de.seuhd.campuscoffee.domain.model.PosType;
import de.seuhd.campuscoffee.domain.ports.OsmDataService;
import de.seuhd.campuscoffee.domain.ports.PosDataService;
import de.seuhd.campuscoffee.domain.ports.PosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of the POS service that handles business logic related to POS entities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PosServiceImpl implements PosService {
    private final PosDataService posDataService;
    private final OsmDataService osmDataService;

    @Override
    public void clear() {
        log.warn("Clearing all POS data");
        posDataService.clear();
    }

    @Override
    public @NonNull List<Pos> getAll() {
        log.debug("Retrieving all POS");
        return posDataService.getAll();
    }

    @Override
    public @NonNull Pos getById(@NonNull Long id) throws PosNotFoundException {
        log.debug("Retrieving POS with ID: {}", id);
        return posDataService.getById(id);
    }

    @Override
    public @NonNull Pos upsert(@NonNull Pos pos) throws PosNotFoundException {
        if (pos.id() == null) {
            // Create new POS
            log.info("Creating new POS: {}", pos.name());
            return performUpsert(pos);
        } else {
            // Update existing POS
            log.info("Updating POS with ID: {}", pos.id());
            // POS ID must be set
            Objects.requireNonNull(pos.id());
            // POS must exist in the database before the update
            posDataService.getById(pos.id());
            return performUpsert(pos);
        }
    }

    @Override
    public @NonNull Pos importFromOsmNode(@NonNull Long nodeId) throws OsmNodeNotFoundException {
        log.info("Importing POS from OpenStreetMap node {}...", nodeId);

        // Fetch the OSM node data using the port
        OsmNode osmNode = osmDataService.fetchNode(nodeId);

        // Convert OSM node to POS domain object and upsert it
        // TODO: Implement the actual conversion (the response is currently hard-coded).
        Pos savedPos = upsert(convertOsmNodeToPos(osmNode));
        log.info("Successfully imported POS '{}' from OSM node {}", savedPos.name(), nodeId);

        return savedPos;
    }

    /**
     * Converts an OSM node to a POS domain object.
     * Extracts all relevant information from OSM tags and maps them to POS fields.
     *
     * @param osmNode the OSM node to convert
     * @return a new POS object with data from the OSM node
     * @throws OsmNodeMissingFieldsException if required fields are missing
     */
    private @NonNull Pos convertOsmNodeToPos(@NonNull OsmNode osmNode) throws OsmNodeMissingFieldsException {
        if (osmNode.tags() == null || osmNode.tags().isEmpty()) {
            log.error("OSM node {} has no tags", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }

        Map<String, String> tags = osmNode.tags();

        // Extract required fields
        String name = tags.get("name");
        String street = tags.get("addr:street");
        String houseNumber = tags.get("addr:housenumber");
        String city = tags.get("addr:city");
        String postalCodeStr = tags.get("addr:postcode");

        // Validate required fields
        if (name == null || name.isBlank()) {
            log.error("OSM node {} is missing required field: name", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (street == null || street.isBlank()) {
            log.error("OSM node {} is missing required field: addr:street", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (houseNumber == null || houseNumber.isBlank()) {
            log.error("OSM node {} is missing required field: addr:housenumber", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (city == null || city.isBlank()) {
            log.error("OSM node {} is missing required field: addr:city", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        if (postalCodeStr == null || postalCodeStr.isBlank()) {
            log.error("OSM node {} is missing required field: addr:postcode", osmNode.nodeId());
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }

        // Parse postal code
        Integer postalCode;
        try {
            postalCode = Integer.parseInt(postalCodeStr);
        } catch (NumberFormatException e) {
            log.error("OSM node {} has invalid postal code: {}", osmNode.nodeId(), postalCodeStr);
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }

        // Determine POS type from OSM amenity tag
        PosType posType = determinePosType(tags);

        // Determine campus type (default to ALTSTADT for Heidelberg, could be enhanced with coordinate-based logic)
        CampusType campusType = determineCampusType(city, osmNode.latitude(), osmNode.longitude());

        // Generate description from OSM tags
        String description = generateDescription(tags);

        log.debug("Converted OSM node {} to POS: name={}, street={} {}, city={}", 
                osmNode.nodeId(), name, street, houseNumber, city);

        return Pos.builder()
                .name(name)
                .description(description)
                .type(posType)
                .campus(campusType)
                .street(street)
                .houseNumber(houseNumber)
                .postalCode(postalCode)
                .city(city)
                .build();
    }

    /**
     * Determines the POS type based on OSM tags.
     * Maps OSM amenity values to PosType enum values.
     *
     * @param tags the OSM tags
     * @return the determined POS type, defaults to CAFE if not specified
     */
    private PosType determinePosType(Map<String, String> tags) {
        String amenity = tags.get("amenity");
        String cuisine = tags.get("cuisine");
        String shop = tags.get("shop");

        if (amenity != null) {
            return switch (amenity.toLowerCase()) {
                case "cafe" -> PosType.CAFE;
                case "restaurant" -> PosType.RESTAURANT;
                case "bar", "pub" -> PosType.BAR;
                case "fast_food" -> PosType.FAST_FOOD;
                default -> PosType.CAFE; // Default to cafe
            };
        }

        if (shop != null && shop.equalsIgnoreCase("bakery")) {
            return PosType.BAKERY;
        }

        if (cuisine != null && cuisine.toLowerCase().contains("coffee")) {
            return PosType.CAFE;
        }

        // Default to CAFE
        return PosType.CAFE;
    }

    /**
     * Determines the campus type based on city and coordinates.
     * This is a simplified implementation that defaults to ALTSTADT for Heidelberg.
     * Could be enhanced with coordinate-based logic to determine the specific campus.
     *
     * @param city      the city name
     * @param latitude  the latitude (nullable)
     * @param longitude the longitude (nullable)
     * @return the determined campus type
     */
    private CampusType determineCampusType(String city, Double latitude, Double longitude) {
        // For Heidelberg, default to ALTSTADT
        // In a real implementation, you could use coordinates to determine the specific campus
        if (city != null && city.equalsIgnoreCase("Heidelberg")) {
            return CampusType.ALTSTADT;
        }
        // For other cities, still default to ALTSTADT
        return CampusType.ALTSTADT;
    }

    /**
     * Generates a description from OSM tags.
     * Combines relevant tags to create a meaningful description.
     *
     * @param tags the OSM tags
     * @return a generated description
     */
    private String generateDescription(Map<String, String> tags) {
        StringBuilder description = new StringBuilder();

        String amenity = tags.get("amenity");
        String cuisine = tags.get("cuisine");
        String shop = tags.get("shop");
        String osmDescription = tags.get("description");

        // Use explicit OSM description if available
        if (osmDescription != null && !osmDescription.isBlank()) {
            return osmDescription;
        }

        // Build description from available tags
        if (shop != null) {
            description.append(capitalize(shop));
        } else if (amenity != null) {
            description.append(capitalize(amenity));
        }

        if (cuisine != null && !cuisine.isBlank()) {
            if (description.length() > 0) {
                description.append(" - ");
            }
            description.append(cuisine);
        }

        // Return built description or a default
        return description.length() > 0 ? description.toString() : "Point of Sale";
    }

    /**
     * Capitalizes the first letter of a string.
     *
     * @param str the string to capitalize
     * @return the capitalized string
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Performs the actual upsert operation with consistent error handling and logging.
     * Database constraint enforces name uniqueness - data layer will throw DuplicatePosNameException if violated.
     * JPA lifecycle callbacks (@PrePersist/@PreUpdate) set timestamps automatically.
     *
     * @param pos the POS to upsert
     * @return the persisted POS with updated ID and timestamps
     * @throws DuplicatePosNameException if a POS with the same name already exists
     */
    private @NonNull Pos performUpsert(@NonNull Pos pos) throws DuplicatePosNameException {
        try {
            Pos upsertedPos = posDataService.upsert(pos);
            log.info("Successfully upserted POS with ID: {}", upsertedPos.id());
            return upsertedPos;
        } catch (DuplicatePosNameException e) {
            log.error("Error upserting POS '{}': {}", pos.name(), e.getMessage());
            throw e;
        }
    }
}
