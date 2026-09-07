import { Suspense } from "react";
import { OAuthProtocolTrace } from "@/features/oauth-clients/oauth-protocol-trace";

export default async function OAuthProtocolEventsPage({
  params,
}: {
  params: Promise<{ companyCode: string; clientId: string }>;
}) {
  const { companyCode, clientId } = await params;
  return (
    <Suspense
      fallback={<p aria-busy="true">이벤트 화면을 준비하는 중입니다.</p>}
    >
      <OAuthProtocolTrace companyCode={companyCode} clientId={clientId} />
    </Suspense>
  );
}
