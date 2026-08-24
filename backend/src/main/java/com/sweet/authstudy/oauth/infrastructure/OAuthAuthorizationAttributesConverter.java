package com.sweet.authstudy.oauth.infrastructure;

import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweet.authstudy.oauth.domain.OAuthAuthorization;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
class OAuthAuthorizationAttributesConverter
        implements AttributeConverter<OAuthAuthorization.Attributes, String> {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "principalName", "authorizationRequestUri", "authorizationRequest", "sessionBinding");
    private static final Set<String> ALLOWED_REQUEST_FIELDS = Set.of(
            "redirectUri", "requestedScopes", "rpState", "codeChallenge",
            "codeChallengeMethod", "nonce");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(OAuthAuthorization.Attributes attributes) {
        try {
            return JSON.writeValueAsString(attributes);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize allowlisted authorization attributes.", exception);
        }
    }

    @Override
    public OAuthAuthorization.Attributes convertToEntityAttribute(String value) {
        try {
            JsonNode root = JSON.readTree(value);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Authorization attributes must be a JSON object.");
            }
            root.fieldNames().forEachRemaining(field -> {
                if (!ALLOWED_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Authorization attribute is not allowlisted: " + field);
                }
            });
            requireText(root, "principalName");
            requireText(root, "authorizationRequestUri");
            requireNullableText(root, "sessionBinding");
            JsonNode sessionBinding = root.get("sessionBinding");
            if (sessionBinding != null && !sessionBinding.isNull()) {
                java.util.UUID.fromString(sessionBinding.textValue());
            }
            JsonNode request = root.get("authorizationRequest");
            if (request != null && !request.isNull()) {
                if (!request.isObject()) {
                    throw new IllegalArgumentException("Authorization request attributes must be a JSON object.");
                }
                request.fieldNames().forEachRemaining(field -> {
                    if (!ALLOWED_REQUEST_FIELDS.contains(field)) {
                        throw new IllegalArgumentException(
                                "Authorization request attribute is not allowlisted: " + field);
                    }
                });
                requireText(request, "redirectUri");
                JsonNode scopes = request.get("requestedScopes");
                if (scopes == null || !scopes.isArray()) {
                    throw wrongType("requestedScopes");
                }
                scopes.forEach(scope -> {
                    if (!scope.isTextual()) throw wrongType("requestedScopes element");
                });
                requireNullableText(request, "rpState");
                requireText(request, "codeChallenge");
                requireText(request, "codeChallengeMethod");
                requireNullableText(request, "nonce");
            }
            return JSON.treeToValue(root, OAuthAuthorization.Attributes.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot read allowlisted authorization attributes.", exception);
        }
    }

    private static void requireText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw wrongType(field);
    }

    private static void requireNullableText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) throw wrongType(field);
    }

    private static IllegalArgumentException wrongType(String field) {
        return new IllegalArgumentException(
                "Authorization attribute has an invalid JSON type: " + field);
    }
}
