# Auth Study HR 관리자 환경

Spring Boot 4, PostgreSQL, Next.js 16으로 구성한 로컬 학습용 HR 관리자 환경입니다.

## 첫 실행

> 경고: 아래 관리자 자격 증명과 개발용 JWT 키는 로컬 학습 전용입니다. 다른 환경이나 운영 환경에서 절대 재사용하지 마세요.

Docker Desktop이 실행 중이어야 합니다. 저장소 루트의 Windows PowerShell에서 PostgreSQL을 시작하고 health 상태를 확인합니다.

```powershell
docker compose -f infrastructure/docker-compose.yml up -d
docker compose -f infrastructure/docker-compose.yml ps
```

`auth-study-postgres`가 `healthy`가 된 뒤 백엔드를 실행합니다.

```powershell
cd backend
.\gradlew.bat bootRun
```

새 PowerShell 창에서 프런트엔드를 실행합니다.

```powershell
cd frontend
npm install
npm run dev
```

- 관리자 웹: `http://localhost:3000`
- 백엔드 API: `http://localhost:8080/api/v1`
- 개발용 시스템 관리자: `admin@auth-study.local`
- 개발용 비밀번호: `AuthStudy1234!`

Next.js는 `/api/*` 요청을 로컬 백엔드로 rewrite합니다. Refresh Token은 브라우저의 HttpOnly 쿠키로만 전달됩니다.

## 테스트

저장소 루트에서 백엔드 전체 테스트를 실행합니다.

```powershell
cd backend
.\gradlew.bat clean test
```

프런트엔드 단위 테스트, 린트와 프로덕션 빌드를 실행합니다.

```powershell
cd frontend
npm test -- --run
npm run lint
npm run build
```

실제 Chromium E2E는 PostgreSQL만 미리 실행한 상태에서 수행합니다. 설정이 `dev` 프로필의 백엔드와 현재 체크아웃의 프런트엔드를 직접 시작하므로 8080 또는 3000 포트에 기존 서버가 있으면 먼저 종료해야 합니다.

```powershell
cd frontend
npx playwright install chromium
npm run test:e2e
```

실패 시 `frontend/test-results`에 trace, screenshot과 video가 보존되며 HTML 보고서는 `frontend/playwright-report`에 생성됩니다.

## 종료

각 개발 서버에서 `Ctrl+C`를 누른 뒤 저장소 루트에서 데이터베이스를 중지합니다.

```powershell
docker compose -f infrastructure/docker-compose.yml down
```

데이터까지 초기화하려면 학습 데이터를 삭제해도 되는지 확인한 뒤 별도로 `down -v`를 실행하세요.
