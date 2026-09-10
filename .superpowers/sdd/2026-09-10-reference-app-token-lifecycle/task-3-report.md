# Task 3 implementation report

Status: completed; self-reviewed. No merge or push.

## Implemented decisions

- Per-session binding-listener state holds closing, one view-only future, and a monotonic operation deadline. The mutex is the Spring session mutex; a registered HttpSessionMutexListener makes it stable across servlet session wrappers.
- Refresh ownership rereads the authorized-client repository under that mutex. Access token remaining lifetime >30 seconds returns empty; <=30 starts exactly one exchange. Waiters share the operation future and its original deadline. Failures permanently close that state.
- Controller ruling applied: only protocol and snapshot DB work run on a bounded worker executor (maximum 32 workers, no pending-work queue). All servlet request/response access and authorized-client publication stay on the original requesting thread. The original synchronous-network-owner prose is superseded by this choice.
- Worker and owner share a transient Work handoff, never stored in session. Its readiness future carries only AppUserView. The worker owns the candidate until the servlet acknowledges successful repository publication. Owner finally acknowledges rejection on timeout, interrupt, close, or exception; the worker clears its candidate reference and revokes rejected/late successors. Session future never retains Work or tokens.
- The servlet waits on either worker readiness or session close, with the shared operation deadline. Publication checks closing, deadline, and original session identity under the same mutex used by cleaner close+invalidate. A late DB result cannot resurrect or populate another login session.
- Filter order is Origin/CSRF, CurrentAppUserFilter, OAuthSessionLifecycleFilter, AuthorizationFilter. Lifecycle applies only to REQUEST BFF traffic and excludes login, csrf, logout, and continuation routes. CurrentAppUserFilter excludes both logout POSTs and continuation so local DB outage does not block logout.
- Successful refresh rechecks current user, status, identity, and authorities and installs a request-local SecurityContext/view. Shared session security context and original OIDC delegate remain unchanged. Failure clears both context and request view, invalidates session, expires cookie; only /bff/session continues anonymously, others stop at 401.
- DBless security contexts now supply snapshot mocks; cookie configuration imports the real protocol beans.

## Verification

Red evidence:
- Initial coordinator focused command failed compilation because OAuthSessionRefreshCoordinator did not exist.
- Initial lifecycle focused command failed compilation because OAuthSessionLifecycleFilter did not exist (fixture accessor typo corrected before this clean red run).
- CurrentAppUserFilter logout exclusion test failed NoInteractionsWanted before adding exclusions.
- subsequentFreshRequestDoesNotReportAnotherRefresh failed before correcting the no-refresh Optional.empty contract.

Green commands:
- ./gradlew.bat test --tests '*OAuthSessionRefreshCoordinatorTest' --tests '*OAuthSessionLifecycleFilterTest' --tests '*CurrentAppUserFilterTest' --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*ReferenceSecurityPropertiesTest' --console=plain
  Final run: BUILD SUCCESSFUL in 18s; 87 tests, no failures/errors/skips.
- ./gradlew.bat test --console=plain
  Full backend run: BUILD SUCCESSFUL in 53s; 242 tests across 31 suites, no failures/errors/skips. This includes existing real HTTP and PostgreSQL tests.
  Afterwards only tests were improved: concurrent-wait synchronization stopped depending on a private method name, and two explicit post-refresh disabled/DB-failure tests were added; the final focused run above validated them. No production behavior changed after the full suite.
- git diff --check passed.

Race evidence:
- Eight servlet callers with one session and a held network latch wait for one exchange and receive the published view; a separate eight-caller failure test verifies all fail and no follow-up reuses the old token.
- A second session completes while the first session's protocol operation is blocked.
- 31/30 second boundary, missing client, protocol failure, and snapshot failure all exercise repository outcomes and no retry.
- Network and DB latch tests outlive a 100ms configured operation deadline; request returns failure, late successor is revoked, repository retains no successor.
- Cleaner invalidation during a blocked snapshot wakes the owner before releasing DB work; late result is revoked and request has no session.
- Explicit close during blocked network wakes owner without waiting for network completion, rejects publication and revokes late result.
- Replacing the original request's session during snapshot work never saves the old-login candidate in the new session.
- Repository spy asserts publication occurs on the original servlet thread.
- Filter tests verify request-local roles, shared-context preservation, excluded routes/dispatches, anonymous /bff/session on failure, and post-refresh missing/disabled/database-failed user cleanup.

## Self-review and limitations

No unresolved correctness blocker found. DB work itself is not forcibly cancelled on a servlet timeout; its eventual candidate remains the worker's revocation responsibility. The executor bounds this to 32 workers and rejects excess work without queuing token-bearing jobs; rejection closes the requesting session. This preserves request wait bounds even if a dependency stalls. Existing Mockito JVM-sharing and pre-existing unchecked-test warnings remain; tests are green.

## Fix round 1 (base 0906026)

Both Important findings resolved:

1. A retained access token without expiresAt now closes coordinator state and throws the fixed SessionRefreshException. The regression test invokes ensureFresh twice and verifies neither protocol nor snapshot service is called.
2. RpSessionCleaner now optionally owns retained-token termination. New package constructor: RpSessionCleaner(boolean secureCookie, OAuth2AuthorizedClientRepository clients, OAuthTokenRevoker revoker). Existing clear(request,response) and legacy boolean constructor remain compatible. Security configuration supplies the new constructor to the shared CurrentAppUserFilter/OAuthSessionLifecycleFilter cleaner. Thus both before-refresh disabled cleanup and post-refresh failure cleanup use it.

Task 4 handoff: reuse the three-argument cleaner for logout. It already captures and revokes the retained refresh token; do not add a second service-side revoke of that same retained token. Capture any full-logout ID-token/handoff data before invoking clear. Legacy callback-only cleaner construction remains unchanged in this round.

Cleanup captures authentication before clearing the SecurityContext; under the same session mutex as publication it closes refresh state, captures the retained authorized client, and invalidates the session. Only that capture/termination owner retains revocation responsibility. Local SecurityContext, request-local user, and response cookie/cache cleanup finish before one bounded revoke outside the mutex. A concurrent cleaner sees an invalidated/empty session and cannot recapture the token. The refresh worker retains separate responsibility for an unpublished successor.

Red evidence:
- ./gradlew.bat test --tests '*OAuthSessionRefreshCoordinatorTest.absentExpiry*' --tests '*OAuthSessionLifecycleFilterTest.failureRevokes*' --console=plain
- Output: four tests completed, four failed; missing expiry AssertionError and three WantedButNotInvoked revocation failures (exchange/database/disabled); BUILD FAILED in 5s.
- Added explicit capture-order assertion failed all three failure cases before moving context cleanup after retained-client capture. This assertion checks real repository access occurs under mutex while original authentication is still available.

Final green evidence:
- ./gradlew.bat test --tests '*OAuthSessionRefreshCoordinatorTest' --tests '*OAuthSessionLifecycleFilterTest' --tests '*CurrentAppUserFilterTest' --tests '*OAuth2ClientConfigurationIntegrationTest' --tests '*ReferenceSecurityPropertiesTest' --tests '*BffSessionSecurityIntegrationTest' --console=plain
- Output: BUILD SUCCESSFUL in 16s; 5 actionable tasks: 3 executed, 2 up-to-date.
- XML totals: 95 tests across 6 suites, 0 failures, 0 errors, 0 skipped.
- New real coordinator/repository/cleaner tests verify old-token revocation after exchange failure and acknowledged successor revocation after post-publication database/disabled failure. Revocation callbacks assert session invalid, no request session/context/user, expired cookie, and no held termination mutex. Repeated clear does not revoke again.
- Eight concurrent cleaners verify exactly one retained-token revocation.
- git diff --check passed. Existing JVM sharing and unchecked-test warnings are unchanged.

Self-review: no unresolved finding. No protocol transport changes, merge, or push.
