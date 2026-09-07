const MAX_BACKEND_OFFSET = 2_147_483_647;
const PAGE_SIZES = [20, 50, 100] as const;

type QueryConfig<TSort extends string, TEnum extends string> = {
  defaultSort: TSort;
  sorts: readonly TSort[];
  enumKey?: "status" | "active";
  enumValues?: readonly TEnum[];
};

export type ParsedListQuery<TSort extends string, TEnum extends string> = {
  page: number;
  size: number;
  sort: TSort;
  search: string;
  enumValue: TEnum | undefined;
  canonical: URLSearchParams;
  needsReplace: boolean;
};

function strictInteger(value: string | null): number | null {
  if (value === null || !/^(0|[1-9]\d*)$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

export function parseListQuery<TSort extends string, TEnum extends string = never>(
    input: URLSearchParams,
    config: QueryConfig<TSort, TEnum>,
): ParsedListQuery<TSort, TEnum> {
  const rawPage = input.get("page");
  const rawSize = input.get("size");
  const rawSort = input.get("sort");
  const rawSearch = input.get("search");
  const rawEnum = config.enumKey ? input.get(config.enumKey) : null;

  const candidateSize = strictInteger(rawSize);
  const size = candidateSize !== null && PAGE_SIZES.includes(candidateSize as (typeof PAGE_SIZES)[number])
      ? candidateSize
      : 20;
  const candidatePage = strictInteger(rawPage);
  const page = candidatePage !== null && candidatePage * size + size <= MAX_BACKEND_OFFSET
      ? candidatePage
      : 0;
  const sort = config.sorts.includes(rawSort as TSort) ? rawSort as TSort : config.defaultSort;
  const search = rawSearch?.trim() ?? "";
  const enumValue = config.enumValues?.includes(rawEnum as TEnum) ? rawEnum as TEnum : undefined;

  const canonical = new URLSearchParams();
  if (search) canonical.set("search", search);
  if (config.enumKey && enumValue !== undefined) canonical.set(config.enumKey, enumValue);
  if (page !== 0) canonical.set("page", String(page));
  if (size !== 20) canonical.set("size", String(size));
  if (sort !== config.defaultSort) canonical.set("sort", sort);
  const inputEntries = [...input.entries()];
  const canonicalEntries = [...canonical.entries()];
  const needsReplace = inputEntries.length !== canonicalEntries.length
      || canonicalEntries.some(([key, value]) => input.getAll(key).length !== 1 || input.get(key) !== value);

  return {
    page,
    size,
    sort,
    search,
    enumValue,
    canonical,
    needsReplace,
  };
}
