import { OAuthClientCreate } from "@/features/oauth-clients/oauth-client-form";

export default async function Page({
  params,
}: {
  params: Promise<{ companyCode: string }>;
}) {
  const { companyCode } = await params;
  return <OAuthClientCreate companyCode={companyCode} />;
}
