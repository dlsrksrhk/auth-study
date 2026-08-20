# HR 관리자 시스템 기반 설계

## 1. 목적

이 프로젝트는 OAuth 2.0, OpenID Connect, SAML 같은 인증·인가 프로토콜을 학습하기 위한 로컬 전용 시스템입니다. 첫 단계에서는 인증 프로토콜을 바로 구현하지 않고, 인증 주체와 조직 정보를 제공할 HR 시스템 및 관리자 환경을 먼저 완성합니다.

첫 단계의 결과물은 다음 기능을 제공해야 합니다.

- 여러 회사를 지원하는 HR 데이터 모델
- 회사, 직위, 부서, 사용자 관리
- 사용자의 복수 부서 소속 관리
- 시스템 관리자, 회사 관리자, 일반 사용자 권한 분리
- JWT 기반 로그인, 토큰 갱신, 로그아웃, 최초 비밀번호 변경
- Next.js와 shadcn/ui 기반 관리자 웹
- Docker Desktop에서 실행되는 PostgreSQL 개발 환경
- 향후 회사별 OAuth/OIDC 클라이언트와 SAML 설정을 추가할 수 있는 모듈 경계

## 2. 범위

### 2.1 포함 범위

- PostgreSQL Docker Compose 환경
- Flyway 기반 데이터베이스 마이그레이션
- 회사, 직위, 부서, 사용자, 부서 소속 도메인
- 인증 계정과 HR 사용자의 분리
- 고정 개발용 시스템 관리자 계정
- 회사 관리자 지정 및 회수
- JWT Access Token과 회전형 Refresh Token
- 역할 기반 API 접근 제어와 회사 데이터 격리
- 관리자 변경 감사 로그
- 관리자 웹과 핵심 E2E 흐름

### 2.2 제외 범위

- OAuth 2.0 Authorization Server
- OpenID Connect Provider
- SAML Identity Provider
- OAuth 클라이언트, 스코프, 클레임, 동의, 키 관리 화면
- 이메일 발송과 사용자 초대 링크
- 근태, 급여, 휴가 등 추가 HR 기능
- 회사별 사용자 정의 프로필 필드
- 운영 배포 프로필과 운영 수준의 비밀 관리

동작하지 않는 OAuth/OIDC/SAML 메뉴나 빈 모듈은 첫 단계에 만들지 않습니다.

## 3. 저장소와 실행 구조

```text
auth-study/
├─ backend/          Spring Boot 3.5, Java 21, Gradle
├─ frontend/         Next.js, TypeScript, shadcn/ui
├─ infrastructure/   PostgreSQL Docker Compose
└─ docs/             설계와 실행 문서
```

백엔드는 하나의 Spring Boot 애플리케이션으로 실행되는 모듈형 모놀리스입니다. 프론트엔드는 별도의 Next.js 애플리케이션으로 실행합니다. 개발 중에는 PostgreSQL만 Docker Desktop에서 실행하고 백엔드와 프론트엔드는 호스트에서 실행합니다.

브라우저는 Next.js의 `/api` 프록시 경로를 통해 Spring Boot API에 접근합니다. 브라우저 관점에서 동일 출처를 유지해 Refresh Token 쿠키와 CORS 구성을 단순화합니다.

## 4. 백엔드 모듈 경계

백엔드는 단일 Gradle 모듈 안에서 기능 중심 패키지로 나눕니다.

| 모듈 | 책임 |
|---|---|
| `identity` | Account, 비밀번호, 로그인, JWT, Refresh Token |
| `authorization` | 시스템·회사 관리자 역할과 접근 정책 |
| `hr.company` | 회사와 회사 이메일 도메인 |
| `hr.position` | 회사별 직위 |
| `hr.department` | 부서 트리와 조직 구조 |
| `hr.user` | 사용자 프로필과 복수 부서 소속 |
| `audit` | 관리자 변경 행위 기록과 조회 |
| `shared` | 시간, 공통 오류 표현 등 최소 공통 요소 |

각 기능 내부는 다음 의존 방향을 따릅니다.

```text
presentation → application → domain
                         ↑
                 infrastructure
```

- Controller는 애플리케이션 유스케이스만 호출합니다.
- JPA, JWT, 비밀번호 해시 구현은 infrastructure에 둡니다.
- 다른 기능의 JPA Entity나 Repository를 직접 참조하지 않습니다.
- 기능 간 연결은 애플리케이션 서비스, 조회 인터페이스 또는 식별자로 수행합니다.
- `Account`는 `User` 객체가 아니라 `userId`만 보유합니다.
- 회사 범위 검사는 Controller뿐 아니라 애플리케이션 유스케이스에서도 수행합니다.

향후 프로토콜 모듈은 `identity`의 인증 유스케이스와 HR의 사용자 클레임 조회 인터페이스를 사용합니다. 첫 단계에는 사용처가 없는 프로토콜 추상화나 빈 패키지를 만들지 않습니다.

## 5. 도메인 모델

### 5.1 공통 규칙

주요 업무 도메인은 다음 공통 속성을 가집니다.

- `id: Long`: 내부 데이터베이스 식별자
- `code: String`: 외부 API와 연동에서 사용하는 업무 식별자
- `createdAt`, `updatedAt`: 생성·수정 시각
- `version`: 낙관적 잠금 버전

코드는 앞뒤 공백을 제거하고 대문자로 정규화합니다. 생성 이후 변경할 수 없습니다. 외부 API는 불변 code를 사용하고 내부 관계는 Long id를 사용합니다. 연결 엔티티와 토큰처럼 독립적인 업무 식별자가 필요하지 않은 데이터에는 code를 만들지 않습니다.

### 5.2 Company

| 필드 | 설명 |
|---|---|
| `id`, `code` | 내부 ID와 전역 업무 코드 |
| `name` | 회사명 |
| `emailDomain` | 로그인 이메일에 사용하는 기본 도메인 하나 |
| `status` | `ACTIVE`, `INACTIVE` |

- 회사 code는 시스템 전체에서 대소문자를 무시하고 유일합니다.
- emailDomain도 시스템 전체에서 대소문자를 무시하고 유일합니다.
- 개발 시스템 관리자에 사용하는 `auth-study.local`은 예약 도메인으로 두고 Company에 등록할 수 없습니다.
- 회사가 비활성화되면 소속 계정의 신규 로그인과 토큰 갱신을 차단합니다.
- 회사 비활성화는 사용자와 조직 데이터를 연쇄 삭제하지 않습니다.

### 5.3 Position

| 필드 | 설명 |
|---|---|
| `id`, `code` | 내부 ID와 회사 내 업무 코드 |
| `companyId` | 소속 회사 |
| `name` | 사원, 대리, 과장, 차장, 부장 등의 표시명 |
| `level` | 직위 비교용 숫자 단계 |
| `displayOrder` | 화면 정렬 순서 |
| `active` | 사용 여부 |

- code는 회사 안에서 유일합니다.
- 초기 회사 생성 시 `EMPLOYEE`(사원), `ASSISTANT_MANAGER`(대리), `MANAGER`(과장), `DEPUTY_GENERAL_MANAGER`(차장), `GENERAL_MANAGER`(부장)를 기본 직위로 생성합니다.
- 직위 level은 관리자 권한이나 부서 역할을 자동으로 부여하지 않습니다.
- 사용 중인 직위는 삭제하지 않고 비활성화합니다.

### 5.4 Department

| 필드 | 설명 |
|---|---|
| `id`, `code` | 내부 ID와 회사 내 업무 코드 |
| `companyId` | 소속 회사 |
| `parentDepartmentId` | 상위 부서, 최상위 부서는 null |
| `name` | 부서명 |
| `status` | `ACTIVE`, `INACTIVE` |

- code는 회사 안에서 유일합니다.
- 인접 목록 방식으로 본부, 부문, 팀 등의 계층을 표현합니다.
- 부모 부서는 반드시 같은 회사에 속해야 합니다.
- 자기 자신 또는 자신의 하위 부서를 부모로 지정할 수 없습니다.
- 활성 사용자 소속이나 활성 하위 부서가 있는 부서는 비활성화 전에 영향을 명시적으로 해소해야 합니다.

### 5.5 User

`User`는 HR 사용자이며 로그인 비밀번호를 직접 소유하지 않습니다.

| 필드 | 설명 |
|---|---|
| `id`, `code` | 내부 ID와 회사 내 업무 코드 |
| `companyId` | 소속 회사 |
| `employeeNumber` | 회사 내 사번 |
| `name` | 사용자 이름 |
| `phone` | 업무 연락처 |
| `hiredAt` | 입사일 |
| `workplace` | 근무지 |
| `profileImageUrl` | 프로필 이미지 URL |
| `positionId` | 회사별 직위 |
| `status` | `PENDING`, `ACTIVE`, `LOCKED`, `RESIGNED` |

- code와 employeeNumber는 회사 안에서 각각 유일합니다.
- position은 반드시 같은 회사에 속해야 합니다.
- 가변 JSON 프로필이나 회사별 사용자 정의 필드는 첫 단계에 사용하지 않습니다.
- 퇴사자와 잠금 사용자는 로그인과 Refresh Token 갱신이 불가능합니다.
- User는 물리 삭제하지 않습니다.

### 5.6 DepartmentMembership

| 필드 | 설명 |
|---|---|
| `id` | 내부 ID |
| `userId` | 사용자 |
| `departmentId` | 부서 |
| `role` | `HEAD`, `DEPUTY_HEAD`, `MEMBER` |
| `isPrimary` | 주 소속 여부 |
| `startedAt`, `endedAt` | 소속 시작·종료 시각 |

- 사용자와 부서는 같은 회사에 속해야 합니다.
- 한 사용자는 여러 부서에 동시에 소속될 수 있습니다.
- 활성 소속은 `endedAt IS NULL`인 행입니다.
- 사용자와 부서 조합의 활성 소속은 하나만 허용합니다.
- 사용자당 활성 주 소속은 하나만 허용합니다.
- 부서당 활성 `HEAD`는 한 명만 허용합니다.
- `DEPUTY_HEAD`와 `MEMBER`의 활성 인원 수는 제한하지 않습니다.
- 종료한 소속은 이력으로 보존합니다.
- 활성 사용자는 하나의 활성 주 소속을 가져야 합니다.

활성 소속, 활성 주 소속과 활성 부서장 제약은 PostgreSQL 부분 유일 인덱스로 보강합니다.

### 5.7 Account와 AccountRole

`Account`는 로그인과 인증을 담당하고 `User`와 분리합니다.

| 필드 | 설명 |
|---|---|
| `id` | 내부 ID |
| `companyId` | 회사 계정의 회사, 시스템 관리자는 null |
| `userId` | HR 사용자 연결, 시스템 관리자는 null |
| `loginEmail` | 로그인 ID |
| `passwordHash` | BCrypt 비밀번호 해시 |
| `status` | `ACTIVE`, `DISABLED` |
| `mustChangePassword` | 최초 비밀번호 변경 필요 여부 |
| `failedLoginAttempts`, `lockedUntil` | 로그인 실패 잠금 정보 |

- 회사 계정은 User와 1:1로 연결합니다.
- 회사 계정 loginEmail은 해당 회사 안에서 대소문자를 무시하고 유일합니다.
- loginEmail은 앞뒤 공백을 제거하고 소문자로 정규화해 저장합니다.
- 회사 이메일 도메인은 Company.emailDomain과 일치해야 합니다.
- 회사 도메인 자체가 전역 유일하므로 정상적인 회사 계정 이메일은 시스템 전체에서도 충돌하지 않습니다.
- 시스템 관리자 Account는 companyId와 userId 없이 존재할 수 있습니다.
- 역할은 `SYSTEM_ADMIN`, `COMPANY_ADMIN`, `USER`입니다.
- `SYSTEM_ADMIN`은 시스템 관리자 계정에만 부여합니다.
- 회사 관리자 역할의 지정과 회수는 시스템 관리자만 수행합니다.
- 회사 관리자는 다른 회사 관리자나 시스템 관리자를 만들 수 없습니다.

### 5.8 RefreshToken

| 필드 | 설명 |
|---|---|
| `id` | 내부 ID |
| `accountId` | 토큰 소유 계정 |
| `tokenHash` | SHA-256 토큰 해시 |
| `familyId` | 회전 토큰 묶음 ID |
| `issuedAt`, `expiresAt` | 발급·만료 시각 |
| `usedAt`, `revokedAt` | 사용·폐기 시각 |

Refresh Token 원문은 데이터베이스나 로그에 저장하지 않습니다.

### 5.9 AuditLog

관리자의 데이터 변경에 대해 actorAccountId, action, targetType, targetId, companyId, 성공 여부, occurredAt, 요청 traceId와 비민감 세부 정보를 기록합니다. 비밀번호, Access Token, Refresh Token과 전체 요청 본문은 기록하지 않습니다. 감사 로그는 관리자 API를 통한 수정 대상이 아닙니다.

## 6. 인증과 토큰 흐름

### 6.1 로그인

1. 이메일 형식과 비밀번호 입력을 검증합니다.
2. 시스템 관리자 이메일과 정확히 일치하는 계정이 있는지 먼저 조회합니다.
3. 시스템 관리자 계정이 아니면 이메일 도메인으로 활성 Company를 찾습니다.
4. 회사 안에서 loginEmail에 해당하는 Account를 찾습니다.
5. Account, User, Company 상태와 비밀번호를 검증합니다.
6. 로그인 실패 횟수가 설정 임계값에 도달하면 Account를 설정된 시간 동안 잠급니다.
7. mustChangePassword가 true이면 비밀번호 변경 API만 호출할 수 있는 제한 토큰을 발급합니다.
8. 정상 계정에는 일반 Access Token과 Refresh Token을 발급합니다.

관리자가 사용자를 생성할 때 서버는 영문 대문자·소문자·숫자·특수문자를 각각 포함한 16자 임시 비밀번호를 무작위로 생성하고 mustChangePassword를 true로 설정합니다. 응답은 임시 비밀번호 원문을 성공 시 한 번만 반환하며 서버는 BCrypt 해시만 저장합니다. 관리 화면은 복사 가능한 일회성 결과 대화상자를 표시하고 닫은 뒤에는 원문을 다시 보여주지 않습니다. 임시 비밀번호를 잃어버리면 권한 있는 관리자가 재설정 API로 새 임시 비밀번호를 발급합니다. 비밀번호 재설정은 기존 Refresh Token을 모두 폐기합니다.

사용자는 최초 로그인에서 비밀번호를 변경해야 다른 API를 사용할 수 있습니다.

사용자가 지정하는 비밀번호는 12자 이상 64자 이하이며 영문 대문자·소문자·숫자·특수문자를 각각 하나 이상 포함해야 합니다.

### 6.2 Access Token

- 수명은 15분입니다.
- 서명 알고리즘은 개발용 고정 키를 사용하는 HS256입니다.
- 브라우저 메모리에만 보관합니다.
- Authorization Bearer 헤더로 API에 전달합니다.
- `sub`, `company_id`, `user_id`, `roles`, `jti`만 기본 클레임으로 포함합니다.
- 개인 프로필과 부서 목록은 토큰에 포함하지 않습니다.
- 비밀번호 변경 전용 토큰에는 별도 목적 claim을 넣고 비밀번호 변경 API 외 접근을 차단합니다.

### 6.3 Refresh Token

- 수명은 7일입니다.
- `AUTH_STUDY_REFRESH` 이름의 HttpOnly 쿠키로 전달합니다.
- 쿠키 Path는 `/api/v1/auth`, SameSite는 `Lax`, 로컬 HTTP 환경의 Secure는 `false`입니다.
- 갱신할 때마다 기존 토큰을 사용 처리하고 새 토큰으로 회전합니다.
- 이미 사용된 토큰이 다시 제출되면 같은 familyId의 모든 토큰을 폐기합니다.
- 로그아웃, 비밀번호 변경, 퇴사, 계정 잠금, 관리자 역할 변경 시 해당 계정의 활성 Refresh Token을 전부 폐기합니다.
- Refresh와 로그아웃 요청은 동일 출처 프록시를 사용하고 Origin을 검증합니다.

## 7. 권한과 회사 격리

| 역할 | 권한 |
|---|---|
| `SYSTEM_ADMIN` | 회사 생성·수정·비활성화, 전체 조직 조회, 회사 관리자 지정·회수, 전체 감사 로그 조회 |
| `COMPANY_ADMIN` | 자기 회사의 직위·부서·일반 사용자와 소속 관리, 일반 사용자 임시 비밀번호 재발급, 자기 회사 감사 로그 조회 |
| `USER` | 자기 계정과 프로필 조회, 비밀번호 변경 |

- 회사 관리 API에는 companyCode가 포함되지만 실제 권한 범위는 인증 컨텍스트의 companyId로 검증합니다.
- 시스템 관리자는 화면에서 관리할 회사를 선택할 수 있습니다.
- 회사 관리자는 자기 회사로 고정되며 다른 companyCode 요청은 403으로 거부합니다.
- 회사 관리자는 회사 자체 생성·비활성화와 관리자 역할 변경을 수행할 수 없습니다.
- 메뉴 숨김은 사용자 경험을 위한 보조 수단이며 백엔드 권한 검사를 대체하지 않습니다.

## 8. API 설계

API 접두사는 `/api/v1`입니다.

```text
POST   /auth/login
POST   /auth/refresh
POST   /auth/logout
PUT    /auth/password
GET    /auth/me

GET    /admin/companies
POST   /admin/companies
GET    /admin/companies/{companyCode}
PUT    /admin/companies/{companyCode}

GET    /admin/companies/{companyCode}/positions
POST   /admin/companies/{companyCode}/positions
PUT    /admin/companies/{companyCode}/positions/{positionCode}

GET    /admin/companies/{companyCode}/departments
POST   /admin/companies/{companyCode}/departments
PUT    /admin/companies/{companyCode}/departments/{departmentCode}

GET    /admin/companies/{companyCode}/users
POST   /admin/companies/{companyCode}/users
GET    /admin/companies/{companyCode}/users/{userCode}
PUT    /admin/companies/{companyCode}/users/{userCode}
PUT    /admin/companies/{companyCode}/users/{userCode}/status
POST   /admin/companies/{companyCode}/users/{userCode}/temporary-password

GET    /admin/companies/{companyCode}/users/{userCode}/memberships
POST   /admin/companies/{companyCode}/users/{userCode}/memberships
PUT    /admin/companies/{companyCode}/users/{userCode}/memberships/{membershipId}
DELETE /admin/companies/{companyCode}/users/{userCode}/memberships/{membershipId}

PUT    /admin/companies/{companyCode}/users/{userCode}/admin-role
DELETE /admin/companies/{companyCode}/users/{userCode}/admin-role
GET    /admin/companies/{companyCode}/audit-logs
```

소속 DELETE는 행을 물리 삭제하지 않고 endedAt을 기록해 활성 소속을 종료하는 의미입니다. 목록 API는 서버 페이지네이션, 검색어와 상태 필터를 지원합니다. 페이지 크기는 상한을 두어 대량 조회를 방지합니다.

## 9. 오류 처리와 동시성

오류는 일관된 Problem Details 형식으로 반환합니다.

```json
{
  "type": "https://auth-study.local/problems/duplicate-code",
  "title": "중복된 코드입니다.",
  "status": 409,
  "code": "DUPLICATE_CODE",
  "traceId": "요청 추적 ID",
  "fieldErrors": []
}
```

- 입력 검증 실패: 400
- 인증 실패 또는 만료: 401
- 권한 부족 또는 다른 회사 접근: 403
- 자원 없음: 404
- code, 이메일, 사번 충돌: 409
- 낙관적 잠금 충돌: 409

애플리케이션에서 사전 검증하더라도 데이터 무결성의 최종 보장은 DB 제약에 둡니다. 관리자 화면은 version을 수정 요청에 포함하고 충돌 시 최신 데이터를 다시 불러오도록 안내합니다.

## 10. PostgreSQL과 개발 설정

`infrastructure/docker-compose.yml`은 `postgres:17-alpine` 이미지와 영속 볼륨, health check를 사용합니다. 로컬 고정 설정은 다음과 같습니다.

```text
database: auth_study
username: auth_study
password: auth_study
```

백엔드 설정은 다음 파일로 관리합니다.

```text
backend/src/main/resources/
├─ application.yaml
└─ application-dev.yaml
```

- application.yaml은 공통 설정과 기본 활성 프로필 `dev`를 가집니다.
- application-dev.yaml은 DB 접속, JWT 고정 개발 키, 토큰 만료 시간, 쿠키, 로그인 잠금, 개발 계정을 포함합니다.
- JWT 서명 키는 최소 32바이트 난수의 Base64 문자열을 한 번 생성해 application-dev.yaml에 커밋합니다.
- 개발 JWT Access Token은 이 키로 HS256 서명합니다.
- 고정 시스템 관리자는 `admin@auth-study.local`과 `AuthStudy1234!`를 사용합니다.
- 고정 시스템 관리자의 mustChangePassword는 false입니다.
- 개발 설정의 로그인 잠금 기준은 연속 5회 실패, 잠금 시간은 15분입니다.
- 개발 시스템 관리자는 dev 프로필에서 계정이 없을 때 한 번만 생성합니다.
- Docker Compose와 YAML의 개발용 비밀번호 및 키는 Git에 커밋합니다.
- README에는 모든 자격 증명이 로컬 학습 전용이며 다른 환경에서 재사용하면 안 된다고 명시합니다.
- 운영 프로필과 환경변수 기반 비밀 관리는 첫 단계 범위에 포함하지 않습니다.

Flyway가 테이블, FK, 유일 제약과 부분 인덱스를 생성합니다. Hibernate는 모든 환경에서 `ddl-auto=validate`를 사용합니다.

## 11. 기본 보안 기준

- 모든 요청 DTO에 Bean Validation과 문자열 길이 제한을 적용합니다.
- JPA 파라미터 바인딩을 사용하고 사용자 입력을 결합한 SQL을 작성하지 않습니다.
- React의 기본 문자열 이스케이프를 사용하고 임의 HTML 렌더링을 허용하지 않습니다.
- 비밀번호는 cost 12의 BCrypt로 해시하며 원문을 저장하거나 로그에 남기지 않습니다.
- Access Token, Refresh Token, 비밀번호와 민감한 요청 본문을 로그에서 제외합니다.
- 로그인 실패 횟수 제한과 일시 잠금을 적용합니다.
- 관리자 변경은 AuditLog에 기록합니다.
- Refresh Token 원문은 쿠키 외 서버 저장소에 남기지 않습니다.
- 개발용 키와 비밀번호는 로컬 학습 전용이라는 전제 아래서만 Git 관리를 허용합니다.

## 12. 관리자 웹

Next.js App Router와 TypeScript, shadcn/ui를 사용합니다. 하나의 앱에서 역할에 따라 메뉴와 라우트를 분기합니다.

### 12.1 화면

- 로그인
- 최초 비밀번호 변경
- 대시보드
- 회사 목록·상세·상태 관리
- 직위 목록·생성·수정·비활성화
- 부서 트리·상세·이동·비활성화
- 사용자 목록·상세·생성·수정·상태 변경
- 사용자별 복수 부서 소속과 주 소속 관리
- 감사 로그
- 내 계정과 비밀번호 변경

시스템 관리자에게는 회사 선택기를 제공하고 회사 관리자는 자기 회사로 고정합니다. 목록은 서버 페이지네이션, 검색과 상태 필터를 사용합니다. 영향이 큰 작업에는 확인 대화상자를 표시하고 성공·실패 결과는 토스트로 알립니다. API 오류가 traceId를 포함하면 사용자에게 함께 표시합니다.

향후 인증 프로토콜 기능은 `인증/인가` 내비게이션 그룹에 추가하지만 첫 단계에는 비어 있는 메뉴를 노출하지 않습니다.

### 12.2 인증 상태

- Access Token은 브라우저 메모리에만 보관합니다.
- 앱 최초 로드와 새로고침 시 Refresh API를 호출해 인증 상태를 복구합니다.
- 401 발생 시 한 번만 토큰 갱신을 시도하고 원래 요청을 재실행합니다.
- 갱신 실패 시 인증 상태를 비우고 로그인 화면으로 이동합니다.
- 다수의 동시 401 요청은 하나의 Refresh 요청만 공유하도록 단일 갱신 흐름을 사용합니다.

## 13. 테스트 전략

### 13.1 백엔드

- 도메인 단위 테스트: 코드 정규화, 상태 전이, 부서 순환, 소속 규칙
- PostgreSQL Testcontainers 통합 테스트: Flyway, 부분 인덱스, 유일 제약, JPA 쿼리
- 보안 통합 테스트: 로그인, 최초 비밀번호 변경, 역할, 회사 격리
- 토큰 테스트: 회전, 재사용 탐지, 로그아웃과 상태 변경 후 폐기
- API 테스트: 검증 오류, 충돌, Problem Details, 페이지네이션
- 감사 로그 테스트: 관리자 변경 기록과 민감정보 미기록

현재 누락된 Spring Boot 테스트 의존성을 추가해 기존 테스트 컴파일 오류를 함께 해결합니다.

### 13.2 프론트엔드와 E2E

- 역할별 메뉴와 라우트 접근
- 입력 폼 검증과 서버 fieldErrors 연결
- 새로고침 후 인증 복구
- 동시 401 상황의 단일 Refresh 처리
- 시스템 관리자 로그인 → 회사 생성 → 회사 관리자 지정
- 회사 관리자 로그인 → 직위·부서·사용자 생성 → 복수 부서와 주 소속 지정
- 다른 회사 URL 조작에 대한 403 처리

## 14. 구현 순서

1. Docker Compose, YAML 설정, Flyway, 공통 오류 처리
2. 회사와 직위 도메인 및 관리 API
3. 사용자, 계정, JWT 인증과 최초 비밀번호 변경
4. 부서 트리와 복수 부서 소속
5. 관리자 역할, 회사 격리와 감사 로그
6. Next.js와 shadcn/ui 관리자 화면
7. 통합·E2E 테스트와 로컬 실행 문서

## 15. 완료 기준

첫 단계는 다음 조건을 모두 충족할 때 완료됩니다.

- Docker Desktop 외 별도 DB 설치 없이 PostgreSQL을 실행할 수 있습니다.
- 고정 개발용 시스템 관리자로 로그인할 수 있습니다.
- 시스템 관리자가 회사와 회사 관리자를 생성·지정할 수 있습니다.
- 회사 관리자가 자기 회사의 직위, 부서, 사용자와 복수 소속을 관리할 수 있습니다.
- 최초 로그인 사용자는 비밀번호를 변경하기 전 다른 기능에 접근할 수 없습니다.
- 새로고침 후 Refresh Token으로 인증 상태가 복구됩니다.
- 역할별 권한과 회사 간 데이터 격리가 백엔드에서 강제됩니다.
- 퇴사, 계정 잠금, 비밀번호 변경과 로그아웃 시 Refresh Token이 폐기됩니다.
- 핵심 도메인, 인증, 회사 격리와 주요 관리자 흐름이 자동 테스트로 검증됩니다.
- 로컬 실행 방법과 개발용 자격 증명이 README에 문서화됩니다.

## 16. 다음 단계 확장 원칙

두 번째 단계에서는 회사별 OAuth 2.0/OIDC 클라이언트를 도입합니다. 클라이언트는 Company에 귀속되고 회사 관리자는 자기 회사 클라이언트만 관리하며 시스템 관리자는 전체를 관리합니다. 프로토콜 모듈은 Account의 인증 기능과 User의 허용된 클레임 조회 기능을 공개 인터페이스로 사용하고 HR 내부 JPA 모델에 직접 의존하지 않습니다.

OAuth/OIDC가 안정화된 뒤 같은 원칙으로 SAML 어댑터를 추가합니다. 근태 같은 HR 기능도 기존 인증 모듈을 변경하기보다 독립적인 기능 패키지로 추가합니다.
