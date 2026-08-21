# OAuth/OIDC Administration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 HR 관리자 API와 Next.js SPA에 회사 소유 OAuth client, redirect URI, scope, trust, secret 회전, 동의 폐기와 protocol trace 관리 기능을 추가합니다.

**Architecture:** backend의 oauth.presentation은 코어 계획에서 만든 OAuthClientService, OAuthConsentService, OAuthGrantRevocationService, OAuthProtocolEventService만 호출합니다. frontend는 기존 memory JWT ApiClient와 회사 선택·역할 guard를 재사용하고 oauth-clients feature 안에 API, form, table, detail, consent, trace를 둡니다. 일회성 client secret은 별도 typed provider가 메모리에서 한 번만 표시하며 URL, storage, query cache에 넣지 않습니다.

**Tech Stack:** Java 21, Spring Boot MVC/Security/Validation, MockMvc, Next.js 16.3 App Router, React 19, TypeScript, shadcn/ui, Vitest, Testing Library, Playwright

**Spec:** docs/superpowers/specs/2026-08-21-oauth-oidc-idp-design.md

**Prerequisite:** docs/superpowers/plans/2026-08-21-oauth-oidc-idp-core.md를 먼저 완료합니다.

## Global Constraints

- COMPANY_ADMIN은 자기 회사 client만 조회·생성·수정·비활성화·secret 회전·동의 폐기할 수 있습니다.
- SYSTEM_ADMIN은 모든 회사 client를 관리하고 TRUSTED_FIRST_PARTY를 설정할 수 있지만 OAuth 로그인 주체는 아닙니다.
- public client에는 secret 동작을 제공하지 않습니다. confidential secret 원문은 create/rotate response 한 번에만 존재합니다.
- UI는 secret을 localStorage, sessionStorage, cookie, URL, React Query cache와 console에 기록하지 않습니다.
- redirect URI는 exact 값 그대로 편집하고 서버가 *.localhost http 또는 https 규칙을 최종 검증합니다.
- scope 설명은 openid/profile/email/hr.company/hr.organization/hr.roles의 고정 목록을 사용합니다.
- frontend 구현 전 frontend/AGENTS.md와 해당 Next.js 16 문서를 읽습니다: fetching-data.md, server-and-client-components.md, linking-and-navigating.md, dynamic-routes.md, forms.md.
- frontend/.idea는 스테이징하지 않습니다.
- 각 작업은 실패 테스트 → 실패 확인 → 최소 구현 → 통과 확인 → 명시적 파일만 커밋 순서입니다.

---

## File and Responsibility Map

    backend/src/main/java/com/sweet/authstudy/oauth/presentation/
      OAuthClientAdminController.java
      OAuthConsentAdminController.java
      OAuthProtocolEventAdminController.java
      OAuthAdminRequests.java
      OAuthAdminResponses.java
    frontend/src/features/oauth-clients/
      oauth-client-api.ts       HTTP DTO와 ApiClient 호출
      oauth-client-form.tsx     create/edit 입력
      oauth-client-table.tsx    목록
      oauth-client-detail.tsx   상태, redirect, scope, secret, consent
      oauth-secret-operation-provider.tsx 일회성 secret 메모리 경계
      oauth-consent-list.tsx
      oauth-protocol-trace.tsx
      oauth-scope-catalog.ts
    frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/
      page.tsx
      new/page.tsx
      [clientId]/page.tsx
      [clientId]/protocol-events/page.tsx

---

### Task 1: OAuth 관리자 REST 계약과 tenant authorization

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthAdminRequests.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthAdminResponses.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthClientAdminController.java
- Modify: backend/src/main/java/com/sweet/authstudy/authorization/SecurityConfig.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/presentation/OAuthClientAdminControllerIntegrationTest.java

**Interfaces:**
- Consumes: OAuthClientService와 HR JWT AuthenticatedAccount
- Produces: /api/v1/admin/companies/{companyCode}/oauth-clients 관리 API와 system-admin 전체 조회 API

**Endpoint contract:**

    GET    /api/v1/admin/companies/{companyCode}/oauth-clients?page=0&size=20
    POST   /api/v1/admin/companies/{companyCode}/oauth-clients
    GET    /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}
    PUT    /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}
    POST   /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/rotate-secret
    POST   /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/disable
    POST   /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/enable
    GET    /api/v1/admin/oauth-clients?page=0&size=20&companyCode=ACME

- [ ] **Step 1: 권한·validation·secret response 실패 테스트 작성**

    mockMvc.perform(post("/api/v1/admin/companies/ACME/oauth-clients")
            .with(companyAdminJwt(otherCompanyId))
            .contentType(APPLICATION_JSON)
            .content(validConfidentialClientJson()))
            .andExpect(status().isForbidden());

create confidential response는 oneTimeSecret을 포함하고 GET/PUT/list response는 해당 JSON property 자체가 없어야 합니다. public create와 public rotate는 각각 secret 없음/409 INVALID_STATE를 검증합니다.

- [ ] **Step 2: controller 부재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthClientAdminControllerIntegrationTest"

Expected: FAIL with 404.

- [ ] **Step 3: request/response DTO 구현**

    public record CreateClientRequest(
            @NotBlank @Size(max = 100) String displayName,
            boolean publicClient,
            @NotEmpty Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            @NotEmpty Set<String> scopes,
            OAuthClientTrust trust) {}

    public record UpdateClientRequest(
            @NotBlank @Size(max = 100) String displayName,
            @NotEmpty Set<URI> redirectUris,
            Set<URI> postLogoutRedirectUris,
            @NotEmpty Set<String> scopes,
            OAuthClientTrust trust,
            @PositiveOrZero long version) {}

    public record OneTimeClientSecretResponse(ClientResponse client, String oneTimeSecret) {}

PageResponse를 기존 목록 API와 같은 shape로 사용합니다. clientId는 path의 opaque string이며 internal numeric id와 secret hash는 response에 넣지 않습니다.

- [ ] **Step 4: controller와 security matcher 연결**

COMPANY_ADMIN 요청은 path companyCode와 principal companyId를 서비스에서 재검증합니다. SYSTEM_ADMIN용 /api/v1/admin/oauth-clients는 role guard를 적용합니다. request trust=TRUSTED_FIRST_PARTY를 COMPANY_ADMIN이 보내면 무시하지 말고 403으로 거부합니다.

- [ ] **Step 5: 통합 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*OAuthClientAdminControllerIntegrationTest"

Expected: PASS for 200/201/204, field violations, cross-tenant 403, missing 404, optimistic conflict 409 and one-time secret contract.

- [ ] **Step 6: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthAdminRequests.java backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthAdminResponses.java backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthClientAdminController.java backend/src/main/java/com/sweet/authstudy/authorization/SecurityConfig.java backend/src/test/java/com/sweet/authstudy/oauth/presentation/OAuthClientAdminControllerIntegrationTest.java
    git commit -m "feat: expose oauth client administration api"

---

### Task 2: 동의·authorization 폐기와 protocol event 조회 API

**Files:**
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthConsentAdminController.java
- Create: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolEventAdminController.java
- Modify: backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthAdminResponses.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/presentation/OAuthConsentAdminControllerIntegrationTest.java
- Create: backend/src/test/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolEventAdminControllerIntegrationTest.java

**Interfaces:**
- Consumes: OAuthConsentService, OAuthGrantRevocationService, OAuthProtocolEventService
- Produces: client별 consent/authorization 폐기와 sanitised trace pagination

**Endpoint contract:**

    GET    /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/consents
    DELETE /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/consents/{subject}
    POST   /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/revoke-authorizations
    GET    /api/v1/admin/companies/{companyCode}/oauth-clients/{clientId}/protocol-events?page=0&size=50&type=TOKEN_ISSUED&outcome=SUCCESS

- [ ] **Step 1: subject privacy와 tenant filter 실패 테스트 작성**

ConsentResponse는 subject, approvedScopes, grantedAt, updatedAt만 제공하고 login email/accountId/userId를 노출하지 않습니다. protocol event는 원문 code/token/secret/verifier/password key가 없고 다른 company event가 섞이지 않는지 검증합니다.

- [ ] **Step 2: endpoint 부재 실패 확인**

Run: cd backend; .\gradlew.bat test --tests "*OAuthConsentAdminControllerIntegrationTest" --tests "*OAuthProtocolEventAdminControllerIntegrationTest"

Expected: FAIL with 404.

- [ ] **Step 3: controller 구현**

consent DELETE는 저장된 consent와 해당 subject/client의 active authorization/refresh를 함께 폐기합니다. revoke-authorizations는 consent를 남기고 active authorization/refresh만 폐기합니다. trace filter는 allowlisted enum과 occurredAt desc/id desc cursor를 사용하며 size 최대 100입니다.

- [ ] **Step 4: audit action 연결**

기존 AuditService에 OAUTH_CLIENT_CREATED, OAUTH_CLIENT_UPDATED, OAUTH_CLIENT_SECRET_ROTATED, OAUTH_CLIENT_DISABLED, OAUTH_CLIENT_ENABLED, OAUTH_CONSENT_REVOKED, OAUTH_AUTHORIZATIONS_REVOKED action을 추가합니다. 감사 metadata에도 secret과 token 원문을 넣지 않습니다.

- [ ] **Step 5: API 테스트 통과**

Run: cd backend; .\gradlew.bat test --tests "*OAuthConsentAdminControllerIntegrationTest" --tests "*OAuthProtocolEventAdminControllerIntegrationTest" --tests "*Audit*"

Expected: PASS with correct 403/404 distinction and sanitized payload.

- [ ] **Step 6: 커밋**

    git add backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthConsentAdminController.java backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolEventAdminController.java backend/src/main/java/com/sweet/authstudy/oauth/presentation/OAuthAdminResponses.java backend/src/main/java/com/sweet/authstudy/audit/application/AuditActions.java backend/src/test/java/com/sweet/authstudy/oauth/presentation/OAuthConsentAdminControllerIntegrationTest.java backend/src/test/java/com/sweet/authstudy/oauth/presentation/OAuthProtocolEventAdminControllerIntegrationTest.java
    git commit -m "feat: administer oauth consents and protocol traces"

---

### Task 3: frontend API types와 scope catalog

**Files:**
- Create: frontend/src/features/oauth-clients/oauth-client-api.ts
- Create: frontend/src/features/oauth-clients/oauth-scope-catalog.ts
- Create: frontend/src/features/oauth-clients/oauth-client-api.test.ts
- Modify: frontend/src/lib/api/problem.ts

**Interfaces:**
- Consumes: 기존 ApiClient와 Task 1/2 REST JSON
- Produces: OAuthClientSummary/Detail/Input, OneTimeSecretResult, ConsentSummary, ProtocolEvent, scope label/description

- [ ] **Step 1: URL encoding과 response mapping 실패 테스트 작성**

    await oauthClientApi.get("ACME", "client/id+value");
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/companies/ACME/oauth-clients/client%2Fid%2Bvalue"),
      expect.anything(),
    );

secret property가 일반 OAuthClientDetail type에 존재하지 않고 create/rotate result에만 존재하도록 TypeScript expectTypeOf를 사용합니다.

- [ ] **Step 2: module 부재 실패 확인**

Run: cd frontend; npm test -- oauth-client-api.test.ts

Expected: FAIL because module does not exist.

- [ ] **Step 3: exact types와 API functions 구현**

    export type OAuthScope =
      | "openid"
      | "profile"
      | "email"
      | "hr.company"
      | "hr.organization"
      | "hr.roles";

    export type OAuthClientInput = {
      displayName: string;
      publicClient: boolean;
      redirectUris: string[];
      postLogoutRedirectUris: string[];
      scopes: OAuthScope[];
      trust: "CONSENT_REQUIRED" | "TRUSTED_FIRST_PARTY";
    };

    export type OAuthClientUpdate = Omit<OAuthClientInput, "publicClient"> & {
      version: number;
    };

list/create/get/update/rotateSecret/enable/disable/listConsents/revokeConsent/revokeAuthorizations/listProtocolEvents를 구현합니다. 모든 path segment는 encodeURIComponent를 거칩니다.

- [ ] **Step 4: scope catalog 구현**

openid=기본 식별자, profile=이름과 사용자 코드, email=로그인 이메일, hr.company=회사 정보, hr.organization=부서·직위 정보, hr.roles=HR 역할로 고정합니다. UI에서 raw scope와 설명을 함께 보여줄 수 있는 readonly record를 export합니다.

- [ ] **Step 5: 테스트 통과**

Run: cd frontend; npm test -- oauth-client-api.test.ts

Expected: PASS including Problem Details propagation and one-time secret type boundary.

- [ ] **Step 6: 커밋**

    git add frontend/src/features/oauth-clients/oauth-client-api.ts frontend/src/features/oauth-clients/oauth-scope-catalog.ts frontend/src/features/oauth-clients/oauth-client-api.test.ts frontend/src/lib/api/problem.ts
    git commit -m "feat: add oauth administration api client"

---

### Task 4: OAuth 전용 일회성 client secret provider

**Files:**
- Create: frontend/src/features/oauth-clients/oauth-secret-operation-provider.tsx
- Create: frontend/src/features/oauth-clients/oauth-secret-operation-provider.test.tsx
- Modify: frontend/src/app/(admin)/layout.tsx

**Interfaces:**
- Consumes: OneTimeSecretResult from create/rotate mutation
- Produces: 메모리 전용 dialog, 한 번 copy, 닫을 때 즉시 폐기

- [ ] **Step 1: lifecycle과 storage 금지 실패 테스트 작성**

provider.open({clientId, displayName, oneTimeSecret}) 뒤 dialog가 표시되고 닫으면 재호출 없이 다시 볼 수 없어야 합니다. unmount 후 값이 남지 않고 localStorage/sessionStorage 호출이 전혀 없는지 spy로 확인합니다. copy button은 navigator.clipboard.writeText만 호출하고 toast에 secret을 넣지 않습니다.

- [ ] **Step 2: provider 부재 실패 확인**

Run: cd frontend; npm test -- oauth-secret-operation-provider.test.tsx

Expected: FAIL because module does not exist.

- [ ] **Step 3: discriminated state와 dialog 구현**

    type SecretState =
      | { kind: "closed" }
      | { kind: "visible"; clientId: string; displayName: string; secret: string };

dialog에는 다시 확인할 수 없다는 경고, monospace secret, 복사와 닫기만 둡니다. close는 setState({kind:"closed"})로 원문 reference를 제거합니다. 기존 AdminSecretOperationProvider의 temporary password type과 상태를 재사용하지 않습니다.

- [ ] **Step 4: admin layout에 provider 추가**

기존 user secret provider와 sibling으로 감싸고 route/navigation 중 unmount되지 않는 동안에도 dialog close 후 값이 제거되는지 검증합니다.

- [ ] **Step 5: 테스트 통과**

Run: cd frontend; npm test -- oauth-secret-operation-provider.test.tsx

Expected: PASS.

- [ ] **Step 6: 커밋**

    git add frontend/src/features/oauth-clients/oauth-secret-operation-provider.tsx frontend/src/features/oauth-clients/oauth-secret-operation-provider.test.tsx frontend/src/app/(admin)/layout.tsx
    git commit -m "feat: show one-time oauth client secrets"

---

### Task 5: client 목록과 생성 화면

**Files:**
- Create: frontend/src/features/oauth-clients/oauth-client-table.tsx
- Create: frontend/src/features/oauth-clients/oauth-client-form.tsx
- Create: frontend/src/features/oauth-clients/oauth-client-table.test.tsx
- Create: frontend/src/features/oauth-clients/oauth-client-form.test.tsx
- Create: frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/page.tsx
- Create: frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/new/page.tsx

**Interfaces:**
- Consumes: oauth-client-api, selected company, AuthSession roles, OAuthSecretOperationProvider
- Produces: paginated list, public/confidential create, redirect/scope/trust form

- [ ] **Step 1: role별 UI와 form validation 실패 테스트 작성**

COMPANY_ADMIN은 자기 company route의 목록·create를 볼 수 있고 trust selector는 CONSENT_REQUIRED로 고정되어야 합니다. SYSTEM_ADMIN은 trust selector를 볼 수 있습니다. redirect input은 줄 단위로 parse하고 공백/중복/fragment/userinfo를 client-side에서 표시하되 서버 validation error를 field에 다시 연결합니다.

- [ ] **Step 2: component 부재 실패 확인**

Run: cd frontend; npm test -- oauth-client-table.test.tsx oauth-client-form.test.tsx

Expected: FAIL.

- [ ] **Step 3: 목록과 form 구현**

table column은 표시 이름, client_id, public/confidential, status, trust, scopes, updatedAt, 상세 링크입니다. form은 표시 이름, client type, redirect URI, post-logout redirect URI, scope checkbox, trust를 제공합니다. openid는 항상 선택되고 해제할 수 없습니다.

- [ ] **Step 4: Next.js route page 연결**

page.tsx는 params를 await하는 Next.js 16 dynamic route 규칙을 따릅니다.

    export default async function Page(
      { params }: { params: Promise<{ companyCode: string }> },
    ) {
      const { companyCode } = await params;
      return <OAuthClientTable companyCode={companyCode} />;
    }

create 성공 시 confidential은 secret dialog를 연 뒤 detail로 이동하고 public은 바로 detail로 이동합니다.

- [ ] **Step 5: 테스트·lint 통과**

Run: cd frontend; npm test -- oauth-client-table.test.tsx oauth-client-form.test.tsx; npm run lint

Expected: PASS.

- [ ] **Step 6: 커밋**

    git add frontend/src/features/oauth-clients/oauth-client-table.tsx frontend/src/features/oauth-clients/oauth-client-form.tsx frontend/src/features/oauth-clients/oauth-client-table.test.tsx frontend/src/features/oauth-clients/oauth-client-form.test.tsx frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/page.tsx frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/new/page.tsx
    git commit -m "feat: add oauth client list and create screens"

---

### Task 6: client 상세·수정·secret·상태 동작

**Files:**
- Create: frontend/src/features/oauth-clients/oauth-client-detail.tsx
- Create: frontend/src/features/oauth-clients/oauth-client-detail.test.tsx
- Create: frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/[clientId]/page.tsx

**Interfaces:**
- Consumes: client detail/update/rotate/enable/disable API
- Produces: exact redirect와 scope 편집, confidential secret rotate, destructive action confirmation

- [ ] **Step 1: public/confidential 및 상태별 action 실패 테스트 작성**

public client에서는 rotate secret button이 없어야 합니다. confidential active client는 rotate/disable, disabled client는 enable을 표시합니다. disable confirm에는 active authorization과 refresh가 폐기된다는 설명이 있어야 합니다. client_id는 copy 가능하지만 수정 불가입니다.

- [ ] **Step 2: component 부재 실패 확인**

Run: cd frontend; npm test -- oauth-client-detail.test.tsx

Expected: FAIL.

- [ ] **Step 3: 상세 화면과 mutation 구현**

update는 optimistic version을 request에 포함하고 409이면 최신 detail 재조회 안내를 표시합니다. rotate response는 OAuthSecretOperationProvider에 즉시 전달하고 component state에는 보관하지 않습니다. enable/disable 뒤 detail과 list cache를 명시적으로 다시 fetch합니다.

- [ ] **Step 4: dynamic route 연결**

params의 companyCode와 clientId를 await하고 decode하지 않은 Next route param을 API layer의 encodeURIComponent에 넘깁니다. 서버 오류의 traceId를 사용자 오류 panel에 표시합니다.

- [ ] **Step 5: 테스트 통과**

Run: cd frontend; npm test -- oauth-client-detail.test.tsx

Expected: PASS including no secret action for public clients and actor role gating.

- [ ] **Step 6: 커밋**

    git add frontend/src/features/oauth-clients/oauth-client-detail.tsx frontend/src/features/oauth-clients/oauth-client-detail.test.tsx frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/[clientId]/page.tsx
    git commit -m "feat: manage oauth client details"

---

### Task 7: consent와 protocol trace 화면

**Files:**
- Create: frontend/src/features/oauth-clients/oauth-consent-list.tsx
- Create: frontend/src/features/oauth-clients/oauth-consent-list.test.tsx
- Create: frontend/src/features/oauth-clients/oauth-protocol-trace.tsx
- Create: frontend/src/features/oauth-clients/oauth-protocol-trace.test.tsx
- Modify: frontend/src/features/oauth-clients/oauth-client-detail.tsx
- Create: frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/[clientId]/protocol-events/page.tsx

**Interfaces:**
- Consumes: consent and protocol event APIs
- Produces: opaque subject별 scope 확인/폐기, filterable redacted protocol timeline

- [ ] **Step 1: 민감정보 redaction과 revoke UX 실패 테스트 작성**

trace renderer가 metadata에 예상 밖 password/token/secret/verifier key를 받아도 값을 렌더링하지 않고 redacted badge를 표시하는 defense-in-depth 테스트를 작성합니다. consent revoke confirm은 해당 사용자의 해당 client login 유지 권한과 refresh가 폐기됨을 설명합니다.

- [ ] **Step 2: component 부재 실패 확인**

Run: cd frontend; npm test -- oauth-consent-list.test.tsx oauth-protocol-trace.test.tsx

Expected: FAIL.

- [ ] **Step 3: consent list 구현**

subject는 앞 8자와 끝 4자를 표시하고 전체 값은 copy action에서만 접근합니다. 승인 scope는 catalog label/description으로 표시합니다. revoke 성공 후 목록을 재조회하고 같은 client의 active authorization revoke 전체 동작을 별도 버튼으로 둡니다.

- [ ] **Step 4: protocol timeline 구현**

occurredAt, type, outcome, correlationId, subject 축약, errorCode, allowlisted metadata를 표시합니다. type/outcome filter와 page navigation을 URL search params에 저장하되 secret 데이터는 URL에 넣지 않습니다.

- [ ] **Step 5: 테스트 통과**

Run: cd frontend; npm test -- oauth-consent-list.test.tsx oauth-protocol-trace.test.tsx

Expected: PASS.

- [ ] **Step 6: 커밋**

    git add frontend/src/features/oauth-clients/oauth-consent-list.tsx frontend/src/features/oauth-clients/oauth-consent-list.test.tsx frontend/src/features/oauth-clients/oauth-protocol-trace.tsx frontend/src/features/oauth-clients/oauth-protocol-trace.test.tsx frontend/src/features/oauth-clients/oauth-client-detail.tsx frontend/src/app/(admin)/companies/[companyCode]/oauth-clients/[clientId]/protocol-events/page.tsx
    git commit -m "feat: inspect oauth consent and protocol history"

---

### Task 8: navigation, 접근성, 전체 관리자 회귀 검증

**Files:**
- Modify: frontend/src/components/layout/admin-sidebar.tsx
- Modify: frontend/src/components/layout/admin-sidebar.test.tsx
- Modify: frontend/src/components/layout/admin-access.ts
- Modify: frontend/src/components/layout/admin-access.test.ts
- Create: frontend/src/features/oauth-clients/oauth-admin-page.test.tsx
- Modify: frontend/playwright.config.ts
- Create: frontend/e2e/oauth-client-administration.spec.ts

**Interfaces:**
- Consumes: 기존 company-scoped sidebar와 HR auth fixture
- Produces: 인증/인가 설정 메뉴, keyboard-accessible dialogs/forms, 관리자 happy path

- [ ] **Step 1: sidebar와 접근 제어 실패 테스트 작성**

COMPANY_ADMIN과 SYSTEM_ADMIN에게 ShieldKeyhole 아이콘의 인증/인가 설정 메뉴를 표시하고 일반 사용자는 route URL을 직접 입력해도 접근할 수 없어야 합니다. company가 선택되지 않은 SYSTEM_ADMIN은 먼저 회사 선택으로 안내합니다.

- [ ] **Step 2: navigation 테스트 실패 확인**

Run: cd frontend; npm test -- admin-sidebar.test.tsx admin-access.test.ts oauth-admin-page.test.tsx

Expected: FAIL because menu and access rule are absent.

- [ ] **Step 3: menu/access와 접근성 수정**

sidebar item href는 /companies/{companyCode}/oauth-clients입니다. dialog에는 title/description, form label에는 htmlFor, destructive button에는 명시적 accessible name을 둡니다. secret과 client_id copy 결과는 aria-live toast로 성공 여부만 알리고 값을 반복하지 않습니다.

- [ ] **Step 4: 관리자 Playwright 시나리오 작성**

로그인 → 회사 선택 → confidential client 생성 → 일회성 secret 복사 가능 확인 → dialog 닫기 → 재조회에서 secret 부재 → redirect/scope 수정 → rotate → disable → enable → protocol trace 조회를 검증합니다. test trace와 screenshot에 secret이 남지 않도록 reporter attachment를 sanitize합니다.

- [ ] **Step 5: frontend 전체 검증**

Run: cd frontend; npm test; npm run lint; npm run build

Expected: PASS with no TypeScript, lint or Next.js dynamic route errors.

Run: cd frontend; npx playwright test e2e/oauth-client-administration.spec.ts

Expected: PASS while backend 8080 and frontend 3000 are started by the configured web servers.

- [ ] **Step 6: 커밋**

    git add frontend/src/components/layout/admin-sidebar.tsx frontend/src/components/layout/admin-sidebar.test.tsx frontend/src/components/layout/admin-access.ts frontend/src/components/layout/admin-access.test.ts frontend/src/features/oauth-clients/oauth-admin-page.test.tsx frontend/playwright.config.ts frontend/e2e/oauth-client-administration.spec.ts
    git commit -m "test: verify oauth client administration"

---

## Administration Completion Gate

- [ ] COMPANY_ADMIN cross-tenant request가 API와 UI 양쪽에서 차단됩니다.
- [ ] TRUSTED_FIRST_PARTY는 SYSTEM_ADMIN만 설정할 수 있습니다.
- [ ] confidential secret 원문은 create/rotate dialog 이후 어떤 API나 화면에서도 재조회되지 않습니다.
- [ ] public client에는 secret UI/API 동작이 없습니다.
- [ ] consent 폐기와 authorization 폐기의 차이가 화면 설명과 테스트에 고정됩니다.
- [ ] protocol trace와 audit payload에 secret/code/token/verifier/password 원문이 없습니다.
- [ ] cd backend; .\gradlew.bat test와 cd frontend; npm test; npm run lint; npm run build가 모두 통과합니다.
- [ ] 완료 후 docs/superpowers/plans/2026-08-21-oauth-oidc-reference-app.md를 실행합니다.
