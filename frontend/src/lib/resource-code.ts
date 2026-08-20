const BUSINESS_CODE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,49}$/;

export function resourceCode(value: string, label = "업무 코드"): string {
  const normalized = value.trim().toUpperCase();
  if (!BUSINESS_CODE.test(normalized)) throw new TypeError(`${label}가 올바르지 않습니다.`);
  return encodeURIComponent(normalized);
}
