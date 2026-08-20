# Auth Study Frontend

Next.js App Router 기반의 로컬 HR 관리자 프론트엔드입니다.

```powershell
npm install
npm run dev
```

개발 서버는 `http://localhost:3000`에서 실행되며 `/api/*`를 `http://localhost:8080/api/*`로 전달합니다.

```powershell
npm test -- --run
npm run lint
npm run build
```

Access Token은 브라우저 메모리에만 저장합니다. Refresh Token 쿠키는 JavaScript에서 읽지 않고 same-origin 요청으로만 사용합니다.
