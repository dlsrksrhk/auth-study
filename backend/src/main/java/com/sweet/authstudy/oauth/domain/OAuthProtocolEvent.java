package com.sweet.authstudy.oauth.domain;

import java.time.Instant;
import java.util.*;

public final class OAuthProtocolEvent {

    public enum EventType {
        AUTHORIZATION_REQUEST_VALIDATED,
        LOGIN_REQUIRED,
        LOGIN_SUCCEEDED,
        LOGIN_FAILED,
        PASSWORD_CHANGE_REQUIRED,
        PASSWORD_CHANGED,
        CONSENT_GRANTED,
        CONSENT_DENIED,
        AUTHORIZATION_CODE_ISSUED,
        AUTHORIZATION_CODE_EXCHANGED,
        AUTHORIZATION_CODE_REPLAY_REJECTED,
        REFRESH_ROTATED,
        REFRESH_REUSE_DETECTED,
        AUTHORIZATION_REVOKED,
        USERINFO_SUCCEEDED,
        USERINFO_DENIED,
        LOGOUT_COMPLETED
    }

    public enum Outcome {SUCCESS, FAILURE, DENIED}

    public enum Endpoint {AUTHORIZE, LOGIN, PASSWORD, CONSENT, TOKEN, REVOCATION, USERINFO, LOGOUT}

    public enum GrantType {AUTHORIZATION_CODE, REFRESH_TOKEN}

    public enum ResponseType {CODE}

    public enum AuthenticationMethod {NONE, CLIENT_SECRET_BASIC, CLIENT_SECRET_POST}

    public enum FailureReason {
        INVALID_REQUEST,
        INVALID_CLIENT,
        INVALID_GRANT,
        INVALID_SCOPE,
        INVALID_TOKEN,
        ACCESS_DENIED,
        LOGIN_FAILED,
        SESSION_INVALID,
        TOKEN_REUSE,
        CODE_REPLAY,
        STATE_INVALID,
        SERVER_ERROR
    }

    private static final Set<String> SUPPORTED_SCOPES = Set.of(
            "openid", "profile", "email", "hr.company", "hr.organization", "hr.roles");
    private static final Set<String> ERROR_CODES = Set.of(
            "invalid_request", "invalid_client", "invalid_grant", "invalid_scope", "invalid_token",
            "access_denied", "login_failed", "session_invalid", "server_error");
    private static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "code", "token", "secret", "verifier", "password", "cookie", "credential", "assertion");
    private static final Set<String> METADATA_KEYS = Set.of(
            "endpoint", "grant_type", "response_type", "scopes", "public_client",
            "redirect_validated", "session_invalidated", "authentication_method", "reason", "http_status");

    public record Metadata(
            Endpoint endpoint,
            GrantType grantType,
            ResponseType responseType,
            Set<String> scopes,
            Boolean publicClient,
            Boolean redirectValidated,
            Boolean sessionInvalidated,
            AuthenticationMethod authenticationMethod,
            FailureReason reason,
            Integer httpStatus) {

        public Metadata {
            scopes = scopes == null ? null : validateScopes(scopes);
            if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
                throw invalidMetadata();
            }
        }

        public static Metadata empty() {
            return new Metadata(null, null, null, null, null, null, null, null, null, null);
        }

        public static Metadata from(Map<String, ?> values) {
            Objects.requireNonNull(values, "metadata");
            values.keySet().forEach(OAuthProtocolEvent::rejectSensitiveText);
            if (!METADATA_KEYS.containsAll(values.keySet())) throw invalidMetadata();
            return new Metadata(
                    enumValue(values, "endpoint", Endpoint.class),
                    enumValue(values, "grant_type", GrantType.class),
                    enumValue(values, "response_type", ResponseType.class),
                    scopes(values.get("scopes")),
                    booleanValue(values, "public_client"),
                    booleanValue(values, "redirect_validated"),
                    booleanValue(values, "session_invalidated"),
                    enumValue(values, "authentication_method", AuthenticationMethod.class),
                    enumValue(values, "reason", FailureReason.class),
                    integerValue(values, "http_status"));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            put(values, "endpoint", endpoint);
            put(values, "grant_type", grantType);
            put(values, "response_type", responseType);
            if (scopes != null) values.put("scopes", scopes.stream().sorted().toList());
            put(values, "public_client", publicClient);
            put(values, "redirect_validated", redirectValidated);
            put(values, "session_invalidated", sessionInvalidated);
            put(values, "authentication_method", authenticationMethod);
            put(values, "reason", reason);
            put(values, "http_status", httpStatus);
            return Map.copyOf(values);
        }

        private static void put(Map<String, Object> target, String key, Object value) {
            if (value instanceof Enum<?> enumValue) target.put(key, enumValue.name());
            else if (value != null) target.put(key, value);
        }

        private static <E extends Enum<E>> E enumValue(Map<String, ?> values, String key, Class<E> type) {
            Object value = values.get(key);
            if (value == null) return null;
            if (value instanceof String text) {
                try {
                    return Enum.valueOf(type, text);
                } catch (IllegalArgumentException exception) {
                    throw invalidMetadata();
                }
            }
            if (type.isInstance(value)) return type.cast(value);
            throw invalidMetadata();
        }

        private static Boolean booleanValue(Map<String, ?> values, String key) {
            Object value = values.get(key);
            if (value == null || value instanceof Boolean) return (Boolean) value;
            throw invalidMetadata();
        }

        private static Integer integerValue(Map<String, ?> values, String key) {
            Object value = values.get(key);
            if (value == null || value instanceof Integer) return (Integer) value;
            throw invalidMetadata();
        }

        private static Set<String> scopes(Object value) {
            if (value == null) return null;
            if (!(value instanceof Collection<?> collection)) throw invalidMetadata();
            Set<String> scopes = new LinkedHashSet<>();
            for (Object item : collection) {
                if (!(item instanceof String scope)) throw invalidMetadata();
                rejectSensitiveText(scope);
                scopes.add(scope);
            }
            return scopes;
        }

        private static Set<String> validateScopes(Set<String> scopes) {
            Set<String> copy = Set.copyOf(scopes);
            if (copy.isEmpty() || !SUPPORTED_SCOPES.containsAll(copy)) throw invalidMetadata();
            return copy;
        }
    }

    private final Long id;
    private final Instant occurredAt;
    private final String correlationId;
    private final EventType eventType;
    private final Outcome outcome;
    private final String clientId;
    private final UUID subject;
    private final Long accountId;
    private final Long companyId;
    private final String authorizationId;
    private final String errorCode;
    private final Metadata metadata;

    private OAuthProtocolEvent(Long id, Instant occurredAt, String correlationId,
                               EventType eventType, Outcome outcome, String clientId, UUID subject,
                               Long accountId, Long companyId, String authorizationId, String errorCode, Metadata metadata) {
        if (id != null && id <= 0) throw new IllegalArgumentException("id must be positive.");
        this.id = id;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.correlationId = safeText(correlationId, "correlationId", 128);
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.clientId = nullableSafeText(clientId, "clientId", 128);
        this.subject = subject;
        this.accountId = positive(accountId, "accountId");
        this.companyId = positive(companyId, "companyId");
        this.authorizationId = nullableSafeText(authorizationId, "authorizationId", 128);
        this.errorCode = validateErrorCode(outcome, errorCode);
        this.metadata = metadata == null ? Metadata.empty() : metadata;
    }

    public static OAuthProtocolEvent create(Instant occurredAt, String correlationId,
                                            EventType eventType, Outcome outcome, String clientId, UUID subject,
                                            Long accountId, Long companyId, String authorizationId, String errorCode, Metadata metadata) {
        return new OAuthProtocolEvent(null, occurredAt, correlationId, eventType, outcome,
                clientId, subject, accountId, companyId, authorizationId, errorCode, metadata);
    }

    public static OAuthProtocolEvent restore(Long id, Instant occurredAt, String correlationId,
                                             EventType eventType, Outcome outcome, String clientId, UUID subject,
                                             Long accountId, Long companyId, String authorizationId, String errorCode, Metadata metadata) {
        return new OAuthProtocolEvent(id, occurredAt, correlationId, eventType, outcome,
                clientId, subject, accountId, companyId, authorizationId, errorCode, metadata);
    }

    private static String validateErrorCode(Outcome outcome, String errorCode) {
        if (errorCode == null) {
            if (outcome != Outcome.SUCCESS) throw new IllegalArgumentException("A failed event needs an errorCode.");
            return null;
        }
        if (outcome == Outcome.SUCCESS || !ERROR_CODES.contains(errorCode)) {
            throw new IllegalArgumentException("errorCode is not allowed for this outcome.");
        }
        return errorCode;
    }

    private static Long positive(Long value, String label) {
        if (value != null && value <= 0) throw new IllegalArgumentException(label + " must be positive.");
        return value;
    }

    private static String nullableSafeText(String value, String label, int maximum) {
        return value == null ? null : safeText(value, label, maximum);
    }

    private static String safeText(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " is invalid.");
        }
        return value;
    }

    private static void rejectSensitiveText(String text) {
        if (text == null) throw invalidMetadata();
        String normalized = text.toLowerCase(java.util.Locale.ROOT).replace("-", "_");
        if (FORBIDDEN_FRAGMENTS.stream().anyMatch(normalized::contains)) throw invalidMetadata();
    }

    private static IllegalArgumentException invalidMetadata() {
        return new IllegalArgumentException("protocol event metadata contains a forbidden key or value");
    }

    public Long id() {
        return id;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String correlationId() {
        return correlationId;
    }

    public EventType eventType() {
        return eventType;
    }

    public Outcome outcome() {
        return outcome;
    }

    public String clientId() {
        return clientId;
    }

    public UUID subject() {
        return subject;
    }

    public Long accountId() {
        return accountId;
    }

    public Long companyId() {
        return companyId;
    }

    public String authorizationId() {
        return authorizationId;
    }

    public String errorCode() {
        return errorCode;
    }

    public Metadata metadata() {
        return metadata;
    }
}
