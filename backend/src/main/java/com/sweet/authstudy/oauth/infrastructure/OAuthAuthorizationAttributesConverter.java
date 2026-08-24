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
            "principalName", "authorizationRequestUri");
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
            return JSON.treeToValue(root, OAuthAuthorization.Attributes.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot read allowlisted authorization attributes.", exception);
        }
    }
}
