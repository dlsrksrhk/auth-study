const encoder = new TextEncoder();

export function validateNewPassword(password: string): string[] {
  const errors: string[] = [];
  const codePoints = Array.from(password).length;
  if (codePoints < 12) errors.push("비밀번호는 12자 이상이어야 합니다.");
  if (codePoints > 64) errors.push("비밀번호는 64자 이하이어야 합니다.");
  if (encoder.encode(password).byteLength > 72) errors.push("비밀번호는 UTF-8 72바이트 이하이어야 합니다.");
  if (!/[A-Z]/.test(password)) errors.push("영문 대문자를 하나 이상 포함해야 합니다.");
  if (!/[a-z]/.test(password)) errors.push("영문 소문자를 하나 이상 포함해야 합니다.");
  if (!/[0-9]/.test(password)) errors.push("숫자를 하나 이상 포함해야 합니다.");
  if (!/[^A-Za-z0-9]/.test(password)) errors.push("특수문자를 하나 이상 포함해야 합니다.");
  return errors;
}
