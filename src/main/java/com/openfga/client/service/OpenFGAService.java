package com.openfga.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfga.client.model.StoreInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for interacting with OpenFGA API.
 * Uses Java HttpClient for direct API calls.
 */
public class OpenFGAService {

    private String apiUrl = "http://localhost:18080";
    private String bearerToken = "";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenFGAService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    private HttpRequest.Builder createRequestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");

        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        return builder;
    }

    private JsonNode sendRequest(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }

        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }

        return objectMapper.readTree(response.body());
    }

    public List<StoreInfo> listStores() throws Exception {
        HttpRequest request = createRequestBuilder("/stores")
                .GET()
                .build();

        JsonNode response = sendRequest(request);
        List<StoreInfo> stores = new ArrayList<>();

        JsonNode storesNode = response.get("stores");
        if (storesNode != null && storesNode.isArray()) {
            for (JsonNode store : storesNode) {
                String id = store.get("id").asText();
                String name = store.get("name").asText();
                stores.add(new StoreInfo(id, name));
            }
        }

        return stores;
    }

    public StoreInfo createStore(String name) throws Exception {
        String body = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("name", name)
        );

        HttpRequest request = createRequestBuilder("/stores")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        JsonNode response = sendRequest(request);
        String id = response.get("id").asText();
        String storeName = response.get("name").asText();

        return new StoreInfo(id, storeName);
    }

    public void deleteStore(String storeId) throws Exception {
        HttpRequest request = createRequestBuilder("/stores/" + storeId)
                .DELETE()
                .build();

        sendRequest(request);
    }

    public String writeAuthorizationModel(String storeId, String modelJson) throws Exception {
        HttpRequest request = createRequestBuilder("/stores/" + storeId + "/authorization-models")
                .POST(HttpRequest.BodyPublishers.ofString(modelJson))
                .build();

        JsonNode response = sendRequest(request);
        return response.get("authorization_model_id").asText();
    }

    public void writeTuple(String storeId, String user, String relation, String object,
                           String conditionName, String conditionContext) throws Exception {

        var tupleKey = objectMapper.createObjectNode()
                .put("user", user)
                .put("relation", relation)
                .put("object", object);

        // Add condition if provided
        if (conditionName != null && !conditionName.isBlank()) {
            var condition = objectMapper.createObjectNode()
                    .put("name", conditionName);

            if (conditionContext != null && !conditionContext.isBlank()) {
                JsonNode contextNode = objectMapper.readTree(conditionContext);
                condition.set("context", contextNode);
            }

            tupleKey.set("condition", condition);
        }

        var write = objectMapper.createObjectNode();
        var writes = objectMapper.createArrayNode();
        writes.add(tupleKey);
        write.set("tuple_keys", writes);

        var body = objectMapper.createObjectNode();
        body.set("writes", write);

        HttpRequest request = createRequestBuilder("/stores/" + storeId + "/write")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        sendRequest(request);
    }

    public void deleteTuple(String storeId, String user, String relation, String object) throws Exception {
        var tupleKey = objectMapper.createObjectNode()
                .put("user", user)
                .put("relation", relation)
                .put("object", object);

        var deletes = objectMapper.createObjectNode();
        var deleteKeys = objectMapper.createArrayNode();
        deleteKeys.add(tupleKey);
        deletes.set("tuple_keys", deleteKeys);

        var body = objectMapper.createObjectNode();
        body.set("deletes", deletes);

        HttpRequest request = createRequestBuilder("/stores/" + storeId + "/write")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        sendRequest(request);
    }

    public boolean check(String storeId, String user, String relation, String object,
                         String contextJson) throws Exception {

        var tupleKey = objectMapper.createObjectNode()
                .put("user", user)
                .put("relation", relation)
                .put("object", object);

        var body = objectMapper.createObjectNode();
        body.set("tuple_key", tupleKey);

        // Add context if provided
        if (contextJson != null && !contextJson.isBlank()) {
            JsonNode contextNode = objectMapper.readTree(contextJson);
            body.set("context", contextNode);
        }

        HttpRequest request = createRequestBuilder("/stores/" + storeId + "/check")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        JsonNode response = sendRequest(request);
        return response.get("allowed").asBoolean();
    }
}
