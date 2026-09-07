import { OAuthClientDetail } from "@/features/oauth-clients/oauth-client-detail";

export default async function OAuthClientDetailPage({
  params,
}: {
  params: Promise<{ companyCode: string; clientId: string }>;
}) {
  const { companyCode, clientId } = await params;
  return <OAuthClientDetail companyCode={companyCode} clientId={clientId} />;
}
