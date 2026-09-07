import type { OAuthScope } from "./oauth-client-api";

export type OAuthScopeCatalogEntry = Readonly<{
  label: string;
  description: string;
}>;

export const oauthScopeCatalog = {
  openid: { label: "기본 식별자", description: "기본 식별자" },
  profile: { label: "이름", description: "이름" },
  email: { label: "로그인 이메일", description: "로그인 이메일" },
  "hr.company": { label: "회사 정보", description: "회사 정보" },
  "hr.organization": { label: "부서·직위 정보", description: "부서·직위 정보" },
  "hr.roles": { label: "HR 역할", description: "HR 역할" },
} as const satisfies Readonly<Record<OAuthScope, OAuthScopeCatalogEntry>>;
