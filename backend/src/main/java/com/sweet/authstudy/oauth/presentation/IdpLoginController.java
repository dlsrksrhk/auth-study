package com.sweet.authstudy.oauth.presentation;

import static com.sweet.authstudy.identity.application.AuthCommands.ChangePasswordCommand;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import com.sweet.authstudy.authorization.AuthenticatedAccount;
import com.sweet.authstudy.identity.application.AuthenticationService;
import com.sweet.authstudy.identity.application.CredentialAuthenticationResult;
import com.sweet.authstudy.identity.application.CredentialAuthenticationService;
import com.sweet.authstudy.oauth.application.OAuthSecurityProperties;
import com.sweet.authstudy.oauth.application.OAuthProtocolEventService;
import com.sweet.authstudy.oauth.application.OAuthSubjectService;
import com.sweet.authstudy.oauth.domain.OAuthProtocolEvent;
import com.sweet.authstudy.oauth.domain.OAuthClient;
import com.sweet.authstudy.oauth.domain.OAuthClientRepository;
import com.sweet.authstudy.oauth.domain.OAuthClientStatus;
import com.sweet.authstudy.shared.error.ApiException;
import com.sweet.authstudy.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class IdpLoginController {
    public static final String PENDING_AUTHORIZATION_ATTRIBUTE =
            IdpLoginController.class.getName() + ".PENDING_AUTHORIZATION";
    public static final String LAST_ACCESS_ATTRIBUTE =
            IdpLoginController.class.getName() + ".LAST_ACCESS";
    public static final String PASSWORD_CHANGE_REQUIRED_ATTRIBUTE =
            IdpLoginController.class.getName() + ".PASSWORD_CHANGE_REQUIRED";
    private static final String LOGIN_FLOW_ATTRIBUTE =
            IdpLoginController.class.getName() + ".LOGIN_FLOW";
    private static final String PASSWORD_FLOW_ATTRIBUTE =
            IdpLoginController.class.getName() + ".PASSWORD_FLOW";
    private static final String GENERIC_LOGIN_ERROR = "이메일 또는 비밀번호를 확인해 주세요.";

    private final OAuthSecurityProperties properties;
    private final CredentialAuthenticationService credentials;
    private final AuthenticationService authenticationService;
    private final OAuthSubjectService subjects;
    private final OAuthClientRepository clients;
    private final Clock clock;
    private final OAuthProtocolEventService protocolEvents;
    private final SecureRandom random = new SecureRandom();

    public IdpLoginController(OAuthSecurityProperties properties,
            CredentialAuthenticationService credentials,
            AuthenticationService authenticationService,
            OAuthSubjectService subjects,
            OAuthClientRepository clients,
            Clock clock,
            OAuthProtocolEventService protocolEvents) {
        this.properties = properties;
        this.credentials = credentials;
        this.authenticationService = authenticationService;
        this.subjects = subjects;
        this.clients = clients;
        this.clock = clock;
        this.protocolEvents = protocolEvents;
    }

    @GetMapping("/idp/login")
    String login(HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpSession session = request.getSession(true);
        if (Boolean.TRUE.equals(session.getAttribute(PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))
                && currentAuthentication() instanceof IdpSessionAuthentication) {
            return "redirect:/idp/password";
        }
        prepareLoginForm(session, model);
        addSessionCookie(response, properties, session.getId());
        return "idp/login";
    }

    @PostMapping("/idp/login")
    String login(@RequestParam String flowId,
            @RequestParam String email,
            @RequestParam String password,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        HttpSession session = request.getSession(false);
        if (session == null || !consumeLoginFlow(session, flowId)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            model.addAttribute("message", "로그인 요청이 만료되었습니다. 다시 시작해 주세요.");
            return "idp/error";
        }

        CredentialAuthenticationResult result;
        try {
            result = credentials.authenticate(new CredentialAuthenticationService.Command(email, password));
            if (!pendingOwnershipMatches(session, result)) {
                return failedLogin(session, response, model);
            }
        } catch (ApiException exception) {
            if (exception.errorCode() != ErrorCode.UNAUTHENTICATED) throw exception;
            return failedLogin(session, response, model);
        }

        var subject = subjects.getOrCreate(result.accountId());
        request.changeSessionId();
        Instant now = result.authenticatedAt();
        IdpSessionAuthentication authentication = new IdpSessionAuthentication(
                result.accountId(), result.companyId(), result.userId(), result.roles(),
                subject.subject(), now);
        establishAuthentication(session, authentication, result.mustChangePassword(), now);
        addSessionCookie(response, properties, session.getId());
        OAuthProtocolEventService.Context eventContext = new OAuthProtocolEventService.Context(
                pendingClientId(session), subject.subject(), result.accountId(), result.companyId(), null);
        protocolEvents.success(OAuthProtocolEvent.EventType.LOGIN_SUCCEEDED, eventContext,
                OAuthProtocolEvent.Metadata.from(java.util.Map.of("endpoint", "LOGIN")));
        if (result.mustChangePassword()) {
            protocolEvents.success(OAuthProtocolEvent.EventType.PASSWORD_CHANGE_REQUIRED, eventContext,
                    OAuthProtocolEvent.Metadata.from(java.util.Map.of("endpoint", "PASSWORD")));
            return "redirect:/idp/password";
        }
        return "redirect:" + consumePendingUri(session);
    }

    @GetMapping("/idp/password")
    String password(HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpSession session = request.getSession(false);
        if (!(currentAuthentication() instanceof IdpSessionAuthentication)
                || session == null
                || !Boolean.TRUE.equals(session.getAttribute(PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))) {
            return "redirect:/idp/error";
        }
        preparePasswordForm(session, model);
        addSessionCookie(response, properties, session.getId());
        return "idp/password";
    }

    @PostMapping("/idp/password")
    String password(@RequestParam String flowId,
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        HttpSession session = request.getSession(false);
        Authentication current = currentAuthentication();
        if (!(current instanceof IdpSessionAuthentication idp)
                || session == null
                || !Boolean.TRUE.equals(session.getAttribute(PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            model.addAttribute("message", "비밀번호 변경 세션이 유효하지 않습니다.");
            return "idp/error";
        }
        if (!consumeFlow(session, PASSWORD_FLOW_ATTRIBUTE, flowId)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            model.addAttribute("message", "비밀번호 변경 요청이 만료되었습니다. 다시 시작해 주세요.");
            return "idp/error";
        }
        try {
            authenticationService.changePassword(
                    new AuthenticatedAccount(idp.accountId(), idp.companyId(), idp.userId(), idp.roles(), true),
                    new ChangePasswordCommand(currentPassword, newPassword));
        } catch (ApiException exception) {
            preparePasswordForm(session, model);
            model.addAttribute("error", exception.errorCode() == ErrorCode.VALIDATION_FAILED
                    ? "새 비밀번호가 정책을 충족하지 않습니다."
                    : "현재 비밀번호를 확인해 주세요.");
            return "idp/password";
        }

        request.changeSessionId();
        Instant now = clock.instant();
        IdpSessionAuthentication refreshed = new IdpSessionAuthentication(
                idp.accountId(), idp.companyId(), idp.userId(), idp.roles(), idp.sub(), idp.authenticatedAt(),
                idp.sessionBinding());
        establishAuthentication(session, refreshed, false, now);
        addSessionCookie(response, properties, session.getId());
        protocolEvents.success(OAuthProtocolEvent.EventType.PASSWORD_CHANGED,
                new OAuthProtocolEventService.Context(pendingClientId(session), idp.sub(),
                        idp.accountId(), idp.companyId(), null),
                OAuthProtocolEvent.Metadata.from(java.util.Map.of("endpoint", "PASSWORD")));
        return "redirect:" + consumePendingUri(session);
    }

    @GetMapping("/idp/error")
    String error(Model model) {
        if (!model.containsAttribute("message")) {
            model.addAttribute("message", "요청을 처리할 수 없습니다. 로그인을 다시 시작해 주세요.");
        }
        return "idp/error";
    }

    private String failedLogin(HttpSession session, HttpServletResponse response, Model model) {
        protocolEvents.failure(OAuthProtocolEvent.EventType.LOGIN_FAILED, pendingContext(session),
                "login_failed", OAuthProtocolEvent.Metadata.from(java.util.Map.of(
                        "endpoint", "LOGIN", "reason", "LOGIN_FAILED")));
        prepareLoginForm(session, model);
        model.addAttribute("error", GENERIC_LOGIN_ERROR);
        addSessionCookie(response, properties, session.getId());
        return "idp/login";
    }

    private OAuthProtocolEventService.Context pendingContext(HttpSession session) {
        Object value = session.getAttribute(PENDING_AUTHORIZATION_ATTRIBUTE);
        if (!(value instanceof PendingAuthorizationRequest pending)) {
            return OAuthProtocolEventService.Context.empty();
        }
        return clients.findById(pending.registeredClientId())
                .map(client -> new OAuthProtocolEventService.Context(
                        client.clientId(), null, null, client.companyId(), null))
                .orElseGet(OAuthProtocolEventService.Context::empty);
    }

    private String pendingClientId(HttpSession session) {
        Object value = session.getAttribute(PENDING_AUTHORIZATION_ATTRIBUTE);
        return value instanceof PendingAuthorizationRequest pending ? pending.clientId() : null;
    }

    private boolean pendingOwnershipMatches(HttpSession session, CredentialAuthenticationResult result) {
        if (result.companyId() == null) return false;
        Object value = session.getAttribute(PENDING_AUTHORIZATION_ATTRIBUTE);
        if (value == null) return true;
        if (!(value instanceof PendingAuthorizationRequest pending)) return false;
        OAuthClient client = clients.findById(pending.registeredClientId())
                .filter(candidate -> candidate.status() == OAuthClientStatus.ACTIVE)
                .orElse(null);
        return client != null && client.clientId().equals(pending.clientId())
                && client.companyId() == result.companyId()
                && client.allowsRedirect(java.net.URI.create(pending.redirectUri()))
                && client.scopes().containsAll(pending.requestedScopes());
    }

    private void establishAuthentication(HttpSession session, IdpSessionAuthentication authentication,
            boolean passwordChangeRequired, Instant now) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, passwordChangeRequired);
        session.setAttribute(LAST_ACCESS_ATTRIBUTE, now);
    }

    private void prepareLoginForm(HttpSession session, Model model) {
        String flowId = newFlowId();
        PendingAuthorizationRequest pending = session.getAttribute(PENDING_AUTHORIZATION_ATTRIBUTE)
                instanceof PendingAuthorizationRequest value ? value : null;
        session.setAttribute(LOGIN_FLOW_ATTRIBUTE, new LoginFlow(flowId, pending));
        model.addAttribute("flowId", flowId);
    }

    private void preparePasswordForm(HttpSession session, Model model) {
        String flowId = newFlowId();
        session.setAttribute(PASSWORD_FLOW_ATTRIBUTE, flowId);
        model.addAttribute("flowId", flowId);
    }

    private boolean consumeFlow(HttpSession session, String attribute, String supplied) {
        synchronized (session) {
            Object expected = session.getAttribute(attribute);
            if (!(expected instanceof String value) || !constantTimeEquals(value, supplied)) return false;
            session.removeAttribute(attribute);
            return true;
        }
    }

    private boolean consumeLoginFlow(HttpSession session, String supplied) {
        synchronized (session) {
            Object value = session.getAttribute(LOGIN_FLOW_ATTRIBUTE);
            PendingAuthorizationRequest current = session.getAttribute(PENDING_AUTHORIZATION_ATTRIBUTE)
                    instanceof PendingAuthorizationRequest pending ? pending : null;
            if (!(value instanceof LoginFlow expected)
                    || !constantTimeEquals(expected.flowId(), supplied)
                    || !java.util.Objects.equals(expected.pending(), current)) return false;
            session.removeAttribute(LOGIN_FLOW_ATTRIBUTE);
            return true;
        }
    }

    private String consumePendingUri(HttpSession session) {
        Object value;
        synchronized (session) {
            value = session.getAttribute(PENDING_AUTHORIZATION_ATTRIBUTE);
            session.removeAttribute(PENDING_AUTHORIZATION_ATTRIBUTE);
        }
        return value instanceof PendingAuthorizationRequest pending
                ? pending.originalUri() : "/idp/error";
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private String newFlowId() {
        byte[] value = new byte[24];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (supplied == null) return false;
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    public static void addSessionCookie(HttpServletResponse response,
            OAuthSecurityProperties properties, String sessionId) {
        ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), sessionId)
                .httpOnly(true).secure(properties.sessionCookieSecure()).path("/").sameSite("Lax").build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static void expireSessionCookie(HttpServletResponse response, OAuthSecurityProperties properties) {
        ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), "")
                .httpOnly(true).secure(properties.sessionCookieSecure()).path("/").sameSite("Lax")
                .maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static void clearPendingBrowserState(HttpSession session) {
        if (session == null) return;
        session.removeAttribute(PENDING_AUTHORIZATION_ATTRIBUTE);
        session.removeAttribute(LOGIN_FLOW_ATTRIBUTE);
    }

    public record PendingAuthorizationRequest(
            long registeredClientId,
            String clientId,
            String redirectUri,
            String state,
            String nonce,
            Set<String> requestedScopes,
            String codeChallenge,
            String codeChallengeMethod,
            String originalUri) implements java.io.Serializable {
        public PendingAuthorizationRequest {
            if (registeredClientId <= 0) throw new IllegalArgumentException("registeredClientId must be positive.");
            clientId = requireText(clientId, "clientId");
            redirectUri = requireText(redirectUri, "redirectUri");
            requestedScopes = Set.copyOf(requestedScopes);
            codeChallenge = requireText(codeChallenge, "codeChallenge");
            if (!"S256".equals(codeChallengeMethod)) {
                throw new IllegalArgumentException("codeChallengeMethod must be S256.");
            }
            if (originalUri == null || !originalUri.startsWith("/oauth2/authorize?")) {
                throw new IllegalArgumentException("originalUri must be a local authorization URI.");
            }
        }

        private static String requireText(String value, String label) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank.");
            return value;
        }
    }

    private record LoginFlow(String flowId, PendingAuthorizationRequest pending)
            implements java.io.Serializable { }
}
