# Auth Study HR 관리자 환경

Spring Boot 3.5.15, PostgreSQL, Next.js 16으로 구성한 로컬 학습용 HR 관리자 환경입니다.

## 첫 실행

> 경고: 아래 관리자 자격 증명과 개발용 JWT 키는 로컬 학습 전용입니다. 다른 환경이나 운영 환경에서 절대 재사용하지 마세요.

Docker Desktop이 실행 중이어야 합니다. 저장소 루트의 Windows PowerShell에서 PostgreSQL을 시작하고 health 상태를 확인합니다.

```powershell
docker compose -f infrastructure/docker-compose.yml up -d
docker compose -f infrastructure/docker-compose.yml ps
```

`auth-study-postgres`가 `healthy`가 된 뒤, 저장소 루트를 현재 디렉터리로 연 새 PowerShell 창에서 백엔드를 실행합니다.

```powershell
cd .\backend
.\gradlew.bat bootRun
```

저장소 루트를 현재 디렉터리로 연 새 PowerShell 창에서 프런트엔드를 실행합니다.

```powershell
cd .\frontend
npm install
npm run dev
```

- 관리자 웹: `http://localhost:3000`
- 백엔드 API: `http://localhost:8080/api/v1`
- 개발용 시스템 관리자: `admin@auth-study.local`
- 개발용 비밀번호: `AuthStudy1234!`

Next.js는 `/api/*` 요청을 로컬 백엔드로 rewrite합니다. Refresh Token은 브라우저의 HttpOnly 쿠키로만 전달됩니다.

## 로컬 샘플 데이터

회사와 직원을 직접 등록하지 않고 화면을 개발하려면, 백엔드를 실행할 PowerShell에서 샘플 데이터 옵션을 켭니다. 저장소 루트 기준 명령입니다.

```powershell
cd .\backend
$env:DEV_SAMPLE_DATA_ENABLED = "true"
.\gradlew.bat bootRun
```

`dev` 프로필에서만 실행되며 기본값은 꺼짐입니다. 기본 로컬 실행 프로필은 `dev`입니다. 이미 실행 중인 백엔드는 종료한 뒤 위 명령으로 다시 시작하세요.

- 회사: `DEMO` / 데모 주식회사 / `demo.example`
- 부서: 본사 아래 개발부·인사부·영업부·재무부
- 직위: 회사 생성 시 준비되는 기본 직위 5개
- 직원: `U001`~`U020`, 각 부서에 5명씩 배치하고 부서장 1명 지정
- 로그인: `user001@demo.example`~`user020@demo.example`
- 공통 비밀번호: `Demo1234!` (로컬 샘플 계정 전용)

직원은 활성 상태이며 주 소속 부서가 연결됩니다. 최초 비밀번호 변경 없이 로그인할 수 있고, 일반 사용자 권한만 갖습니다. 관리자 화면은 기존 시스템 관리자 계정으로 이용하세요.

`DEMO` 회사가 이미 있으면 샘플 생성을 통째로 건너뜁니다. 재실행해도 수정한 이름·상태·비밀번호를 덮어쓰지 않으며 삭제한 샘플 직원도 복원하지 않습니다. 기존에 직접 만든 `DEMO` 회사가 있어도 동일하게 건너뜁니다. 최초 생성은 한 트랜잭션으로 처리되어 실패 시 샘플 전체가 롤백됩니다.

옵션을 끄려면 같은 PowerShell에서 `$env:DEV_SAMPLE_DATA_ENABLED = "false"`로 설정한 뒤 백엔드를 다시 실행하세요. 이미 생성된 데이터는 유지됩니다.

## 테스트

저장소 루트를 현재 디렉터리로 연 PowerShell 창에서 백엔드 전체 테스트를 실행합니다.

```powershell
cd .\backend
.\gradlew.bat clean test
```

별도의 PowerShell 창을 저장소 루트에서 열어 프런트엔드 단위 테스트, 린트와 프로덕션 빌드를 실행합니다.

```powershell
cd .\frontend
npm test -- --run
npm run lint
npm run build
```

실제 Chromium E2E는 PostgreSQL만 미리 실행한 상태에서 수행합니다. 설정이 `dev` 프로필의 백엔드와 현재 체크아웃의 프런트엔드를 직접 시작하므로 8080 또는 3000 포트에 기존 서버가 있으면 먼저 종료해야 합니다. 저장소 루트를 현재 디렉터리로 연 새 PowerShell 창에서 실행합니다.

```powershell
cd .\frontend
npx playwright install chromium
npm run test:e2e
```

실패 시 `frontend/test-results`에 trace, screenshot과 video가 보존되며 HTML 보고서는 `frontend/playwright-report`에 생성됩니다.

## 종료

각 개발 서버에서 `Ctrl+C`를 누른 뒤, 저장소 루트를 현재 디렉터리로 연 PowerShell 창에서 데이터베이스를 중지합니다.

```powershell
docker compose -f infrastructure/docker-compose.yml down
```

데이터까지 초기화하려면 학습 데이터를 삭제해도 되는지 확인한 뒤 별도로 `down -v`를 실행하세요.
