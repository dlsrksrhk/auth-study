import {OAuthClientTable} from "@/features/oauth-clients/oauth-client-table";

export default async function Page({
                                     params,
                                   }: {
  params: Promise<{ companyCode: string }>;
}) {
  const {companyCode} = await params;
  return <OAuthClientTable companyCode={companyCode}/>;
}
