"use client";

import {createContext, useCallback, useContext, useRef, useState} from "react";

import {Button} from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type {OneTimeSecretResult} from "./oauth-client-api";

type SecretState =
    | { kind: "closed" }
    | { kind: "visible"; clientId: string; displayName: string; secret: string };

export type OpenOAuthSecret = {
  clientId: string;
  displayName: string;
  oneTimeSecret: NonNullable<OneTimeSecretResult["oneTimeSecret"]>;
};

type OAuthSecretOperations = {
  open(result: OpenOAuthSecret): void;
};

const OAuthSecretOperationContext = createContext<OAuthSecretOperations | null>(null);

export function OAuthSecretOperationProvider({children}: { children: React.ReactNode }) {
  const [state, setState] = useState<SecretState>({kind: "closed"});
  const [copyMessage, setCopyMessage] = useState("");
  const copyEpoch = useRef(0);

  const close = useCallback(() => {
    copyEpoch.current += 1;
    setCopyMessage("");
    setState({kind: "closed"});
  }, []);

  const open = useCallback((result: OpenOAuthSecret) => {
    copyEpoch.current += 1;
    setCopyMessage("");
    setState({
      kind: "visible",
      clientId: result.clientId,
      displayName: result.displayName,
      secret: result.oneTimeSecret,
    });
  }, []);

  async function copySecret() {
    if (state.kind !== "visible") return;
    const epoch = copyEpoch.current;
    try {
      await navigator.clipboard.writeText(state.secret);
      if (copyEpoch.current === epoch) setCopyMessage("secret을 복사했습니다.");
    } catch {
      if (copyEpoch.current === epoch) setCopyMessage("직접 선택해 복사해 주세요.");
    }
  }

  return (
      <OAuthSecretOperationContext.Provider value={{open}}>
        {children}
        <Dialog open={state.kind === "visible"} onOpenChange={(nextOpen) => {
          if (!nextOpen) close();
        }}>
          <DialogContent showCloseButton={false}>
            <DialogHeader>
              <DialogTitle>OAuth client secret</DialogTitle>
              <DialogDescription>
                이 secret은 지금 한 번만 확인할 수 있습니다. 닫으면 즉시 폐기되며 다시 확인할 수 없습니다.
              </DialogDescription>
            </DialogHeader>
            {state.kind === "visible" ? (
                <>
                  <dl className="grid grid-cols-[6rem_1fr] gap-2 text-sm">
                    <dt className="text-slate-500">표시 이름</dt>
                    <dd>{state.displayName}</dd>
                    <dt className="text-slate-500">client ID</dt>
                    <dd className="break-all font-mono">{state.clientId}</dd>
                  </dl>
                  <code className="select-all break-all rounded bg-slate-950 p-3 text-center font-mono text-white">
                    {state.secret}
                  </code>
                </>
            ) : null}
            <p aria-live="polite" role="status">{copyMessage}</p>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => void copySecret()}>
                복사
              </Button>
              <Button type="button" onClick={close}>
                닫기
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </OAuthSecretOperationContext.Provider>
  );
}

export function useOAuthSecretOperations(): OAuthSecretOperations {
  const operations = useContext(OAuthSecretOperationContext);
  if (!operations) {
    throw new Error("useOAuthSecretOperations must be used inside OAuthSecretOperationProvider.");
  }
  return operations;
}
