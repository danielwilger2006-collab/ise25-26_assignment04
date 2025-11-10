package de.seuhd.campuscoffee.data.impl;

import de.seuhd.campuscoffee.domain.exceptions.OsmNodeNotFoundException;
import de.seuhd.campuscoffee.domain.model.OsmNode;
import de.seuhd.campuscoffee.domain.ports.OsmDataService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * OSM import service that fetches and parses data from the OpenStreetMap API.
 */
@Service
@Slf4j
class OsmDataServiceImpl implements OsmDataService {
    private static final String OSM_API_URL = "https://www.openstreetmap.org/api/0.6/node/";
    private final RestTemplate restTemplate;

    public OsmDataServiceImpl() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public @NonNull OsmNode fetchNode(@NonNull Long nodeId) throws OsmNodeNotFoundException {
        log.info("Fetching OSM node {} from OpenStreetMap API", nodeId);

        try {
            // Fetch XML from OpenStreetMap API
            String url = OSM_API_URL + nodeId;
            String xmlResponse = restTemplate.getForObject(url, String.class);

            if (xmlResponse == null || xmlResponse.isEmpty()) {
                log.error("Empty response from OSM API for node {}", nodeId);
                throw new OsmNodeNotFoundException(nodeId);
            }

            // Parse XML response
            OsmNode osmNode = parseOsmXml(xmlResponse, nodeId);
            log.info("Successfully fetched OSM node {}: {}", nodeId, osmNode.tags().get("name"));
            return osmNode;

        } catch (HttpClientErrorException.NotFound e) {
            log.error("OSM node {} not found", nodeId);
            throw new OsmNodeNotFoundException(nodeId);
        } catch (RestClientException e) {
            log.error("Error fetching OSM node {}: {}", nodeId, e.getMessage());
            throw new OsmNodeNotFoundException(nodeId);
        } catch (Exception e) {
            log.error("Error parsing OSM XML for node {}: {}", nodeId, e.getMessage(), e);
            throw new OsmNodeNotFoundException(nodeId);
        }
    }

    /**
     * Parses OSM XML response and extracts node data.
     *
     * @param xml    the XML response from OSM API
     * @param nodeId the node ID being parsed
     * @return parsed OsmNode with coordinates and tags
     * @throws Exception if XML parsing fails
     */
    private OsmNode parseOsmXml(String xml, Long nodeId) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // Get the node element
        NodeList nodeList = document.getElementsByTagName("node");
        if (nodeList.getLength() == 0) {
            throw new IllegalArgumentException("No node element found in XML");
        }

        Element nodeElement = (Element) nodeList.item(0);

        // Extract latitude and longitude
        Double latitude = null;
        Double longitude = null;
        if (nodeElement.hasAttribute("lat")) {
            latitude = Double.parseDouble(nodeElement.getAttribute("lat"));
        }
        if (nodeElement.hasAttribute("lon")) {
            longitude = Double.parseDouble(nodeElement.getAttribute("lon"));
        }

        // Extract tags
        Map<String, String> tags = new HashMap<>();
        NodeList tagList = nodeElement.getElementsByTagName("tag");
        for (int i = 0; i < tagList.getLength(); i++) {
            Element tagElement = (Element) tagList.item(i);
            String key = tagElement.getAttribute("k");
            String value = tagElement.getAttribute("v");
            tags.put(key, value);
        }

        return OsmNode.builder()
                .nodeId(nodeId)
                .latitude(latitude)
                .longitude(longitude)
                .tags(tags)
                .build();
    }
}
