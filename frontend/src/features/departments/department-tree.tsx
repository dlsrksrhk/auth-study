"use client";

import {useCallback, useEffect, useMemo, useRef, useState} from "react";

import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Skeleton} from "@/components/ui/skeleton";
import {isApiProblemError} from "@/lib/api/problem";
import {resourceCode} from "@/lib/resource-code";
import {DepartmentForm} from "./department-form";
import {departmentApi, type Department} from "./department-api";

type TreeNode = Department & { children: TreeNode[] };

function makeTree(items: Department[]): TreeNode[] {
  const nodes = new Map(items.map((item) => [item.id, {...item, children: []} as TreeNode]));
  const roots: TreeNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.parentDepartmentId === null ? undefined : nodes.get(node.parentDepartmentId);
    if (parent) parent.children.push(node); else roots.push(node);
  }
  const sort = (list: TreeNode[]) => {
    list.sort((a, b) => a.code.localeCompare(b.code));
    list.forEach((node) => sort(node.children));
  };
  sort(roots);
  return roots;
}

function validateDepartmentGraph(items: Department[]): void {
  const ids = new Set<number>();
  for (const item of items) {
    if (ids.has(item.id)) throw new Error("부서 데이터 무결성 오류: 중복된 부서 ID가 있습니다.");
    ids.add(item.id);
  }
  for (const item of items) {
    if (item.parentDepartmentId !== null && !ids.has(item.parentDepartmentId)) throw new Error("부서 데이터 무결성 오류: 존재하지 않는 상위 부서가 있습니다.");
  }
  const byId = new Map(items.map((item) => [item.id, item]));
  const colors = new Map<number, 0 | 1 | 2>();

  function visit(id: number) {
    if (colors.get(id) === 1) throw new Error("부서 데이터 무결성 오류: 부서 계층에 순환이 있습니다.");
    if (colors.get(id) === 2) return;
    colors.set(id, 1);
    const parent = byId.get(id)?.parentDepartmentId;
    if (parent !== null && parent !== undefined) visit(parent);
    colors.set(id, 2);
  }

  items.forEach((item) => visit(item.id));
}

export function DepartmentTree({companyCode}: { companyCode: string }) {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [focusedId, setFocusedId] = useState<number | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [form, setForm] = useState<{ mode: "create" | "edit" | "move"; department?: Department } | null>(null);
  const requestId = useRef(0);
  const restoreFocus = useRef(false);
  const itemRefs = useRef(new Map<number, HTMLElement>());
  const departmentsRef = useRef<Department[]>([]);
  let canonicalCode: string;
  try {
    canonicalCode = decodeURIComponent(resourceCode(companyCode, "회사 코드"));
  } catch {
    canonicalCode = "";
  }

  useEffect(() => {
    departmentsRef.current = departments;
  }, [departments]);

  useEffect(() => {
    if (!canonicalCode) return;
    const id = ++requestId.current;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (id === requestId.current) {
        setLoading(true);
        setError(null);
      }
    });
    departmentApi.listAll(canonicalCode, controller.signal).then((items) => {
      if (id !== requestId.current) return;
      validateDepartmentGraph(items);
      setDepartments(items);
      setExpanded(new Set(items.map((item) => item.id)));
      const survivingId = (current: number | null) => {
        let candidate = current;
        while (candidate !== null && !items.some((item) => item.id === candidate)) candidate = departmentsRef.current.find((item) => item.id === candidate)?.parentDepartmentId ?? null;
        return candidate ?? items[0]?.id ?? null;
      };
      setSelectedId((current) => survivingId(current));
      setFocusedId((current) => {
        const next = survivingId(current);
        if (restoreFocus.current && next !== null) requestAnimationFrame(() => itemRefs.current.get(next)?.focus());
        restoreFocus.current = false;
        return next;
      });
    }).catch((cause) => {
      if (id !== requestId.current || (cause instanceof DOMException && cause.name === "AbortError")) return;
      setDepartments([]);
      setSelectedId(null);
      setFocusedId(null);
      setError(isApiProblemError(cause) ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})` : cause instanceof Error ? cause.message : "부서 목록을 불러오지 못했습니다.");
    }).finally(() => {
      if (id === requestId.current) setLoading(false);
    });
    return () => {
      controller.abort();
      if (requestId.current === id) requestId.current += 1;
    };
  }, [canonicalCode, reload]);

  const tree = useMemo(() => makeTree(departments), [departments]);
  const visible = useMemo(() => {
    const result: TreeNode[] = [];
    const walk = (nodes: TreeNode[]) => nodes.forEach((node) => {
      result.push(node);
      if (expanded.has(node.id)) walk(node.children);
    });
    walk(tree);
    return result;
  }, [expanded, tree]);
  const selected = departments.find((item) => item.id === selectedId);

  const focusAt = useCallback((index: number) => {
    const id = visible[Math.max(0, Math.min(index, visible.length - 1))]?.id;
    if (id === undefined) return;
    setFocusedId(id);
    itemRefs.current.get(id)?.focus();
  }, [visible]);

  function isDescendant(id: number | null, ancestorId: number): boolean {
    let current = id;
    while (current !== null) {
      const node = departments.find((item) => item.id === current);
      if (!node?.parentDepartmentId) return false;
      if (node.parentDepartmentId === ancestorId) return true;
      current = node.parentDepartmentId;
    }
    return false;
  }

  function toggle(node: TreeNode) {
    const collapsing = expanded.has(node.id);
    setExpanded((old) => {
      const next = new Set(old);
      if (collapsing) next.delete(node.id); else next.add(node.id);
      return next;
    });
    if (collapsing && (isDescendant(focusedId, node.id) || isDescendant(selectedId, node.id))) {
      setFocusedId(node.id);
      setSelectedId(node.id);
      itemRefs.current.get(node.id)?.focus();
    }
  }

  function keyDown(event: React.KeyboardEvent, node: TreeNode) {
    event.stopPropagation();
    const index = visible.findIndex((item) => item.id === node.id);
    if (event.key === "ArrowDown") {
      event.preventDefault();
      focusAt(index + 1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      focusAt(index - 1);
    } else if (event.key === "Home") {
      event.preventDefault();
      focusAt(0);
    } else if (event.key === "End") {
      event.preventDefault();
      focusAt(visible.length - 1);
    } else if (event.key === "ArrowRight" && node.children.length) {
      event.preventDefault();
      if (!expanded.has(node.id)) setExpanded((old) => new Set(old).add(node.id)); else {
        setFocusedId(node.children[0].id);
        itemRefs.current.get(node.children[0].id)?.focus();
      }
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      if (expanded.has(node.id) && node.children.length) toggle(node); else if (node.parentDepartmentId) {
        setFocusedId(node.parentDepartmentId);
        itemRefs.current.get(node.parentDepartmentId)?.focus();
      }
    } else if (event.key === "Enter") {
      event.preventDefault();
      setSelectedId(node.id);
    } else if (event.key === " ") {
      event.preventDefault();
      setSelectedId(node.id);
      if (node.children.length) toggle(node);
    }
  }

  function renderNodes(nodes: TreeNode[], level: number): React.ReactNode {
    return nodes.map((node) => <li aria-expanded={node.children.length ? expanded.has(node.id) : undefined}
                                   aria-label={`${node.code} ${node.name}`} aria-level={level}
                                   aria-selected={selectedId === node.id} className="outline-none" key={node.id}
                                   onClick={(event) => {
                                     event.stopPropagation();
                                     setSelectedId(node.id);
                                     setFocusedId(node.id);
                                     itemRefs.current.get(node.id)?.focus();
                                   }} onKeyDown={(event) => keyDown(event, node)} ref={(element) => {
      if (element) itemRefs.current.set(node.id, element); else itemRefs.current.delete(node.id);
    }} role="treeitem" tabIndex={focusedId === node.id ? 0 : -1}>
      <div
          className={`flex items-center gap-2 rounded-md px-2 py-1.5 ${selectedId === node.id ? "bg-teal-50 text-teal-900" : "hover:bg-slate-50"}`}
          style={{paddingLeft: `${(level - 1) * 20 + 8}px`}}>
        {node.children.length ?
            <button aria-label={`${node.code} ${expanded.has(node.id) ? "접기" : "펼치기"}`} className="size-6"
                    onClick={(event) => {
                      event.stopPropagation();
                      toggle(node);
                    }} tabIndex={-1} type="button">{expanded.has(node.id) ? "▾" : "▸"}</button> :
            <span className="inline-block size-6"/>}
        <span className="font-mono text-sm font-semibold">{node.code}</span><span
          className="truncate text-sm">{node.name}</span>
      </div>
      {node.children.length && expanded.has(node.id) ?
          <ul role="group">{renderNodes(node.children, level + 1)}</ul> : null}
    </li>);
  }

  if (!canonicalCode) return <section aria-labelledby="department-title" className="mx-auto max-w-7xl"><h1
      id="department-title" className="text-3xl font-semibold">부서 관리</h1><p aria-live="assertive"
                                                                            className="mt-6 rounded-xl border bg-white p-8 text-red-700">회사
    코드가 올바르지 않습니다.</p></section>;
  return <section aria-labelledby="department-title" className="mx-auto max-w-7xl">
    <div className="flex items-end justify-between gap-4">
      <div><p
          className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">{canonicalCode || "INVALID"}</p>
        <h1 className="mt-2 text-3xl font-semibold" id="department-title">부서 관리</h1><p
            className="mt-2 text-sm text-slate-600">조직 계층과 부서 상태를 관리합니다.</p></div>
      <Button onClick={() => setForm({mode: "create"})}>부서 생성</Button></div>
    {notice ? <p aria-live="polite" className="mt-4 text-sm text-teal-700">{notice}</p> : null}
    {loading ? <div aria-busy="true" className="mt-6"><span className="sr-only">부서 목록을 불러오는 중입니다.</span><Skeleton
        className="h-80 w-full"/></div> : error ?
        <div className="mt-6 rounded-xl border bg-white p-8 text-center"><p aria-live="assertive"
                                                                            className="text-sm text-red-700">{error}</p>
          <Button className="mt-3" variant="outline" onClick={() => setReload((value) => value + 1)}>다시 시도</Button>
        </div> : <div className="mt-6 grid gap-5 lg:grid-cols-[minmax(18rem,2fr)_minmax(16rem,1fr)]">
          <div className="rounded-xl border bg-white p-3 shadow-sm">{tree.length ?
              <ul aria-label="부서 트리" role="tree">{renderNodes(tree, 1)}</ul> :
              <p className="p-8 text-center text-sm text-slate-500">등록된 부서가 없습니다.</p>}</div>
          <aside className="rounded-xl border bg-white p-5 shadow-sm" aria-label="선택한 부서 상세">{selected ? <>
            <div className="flex items-center justify-between"><h2
                className="font-mono text-lg font-semibold">{selected.code}</h2><Badge
                variant={selected.status === "ACTIVE" ? "default" : "secondary"}>{selected.status === "ACTIVE" ? "활성" : "비활성"}</Badge>
            </div>
            <p className="mt-2 text-sm">{selected.name}</p><p
              className="mt-1 text-xs text-slate-500">버전 {selected.version}</p>
            <div className="mt-5 flex flex-wrap gap-2"><Button aria-label={`${selected.code} 수정`} size="sm"
                                                               variant="outline" onClick={() => setForm({
              mode: "edit",
              department: selected
            })}>수정</Button><Button aria-label={`${selected.code} 이동`} size="sm" variant="outline"
                                   onClick={() => setForm({mode: "move", department: selected})}>이동</Button></div>
          </> : <p className="text-sm text-slate-500">부서를 선택해 주세요.</p>}</aside>
        </div>}
    {form ? <DepartmentForm companyCode={canonicalCode} department={form.department} departments={departments}
                            mode={form.mode} onConflict={() => {
      setNotice("다른 관리자가 수정해 최신 부서 정보를 다시 불러왔습니다.");
      restoreFocus.current = true;
      setReload((value) => value + 1);
    }} onOpenChange={(open) => {
      if (!open) {
        setForm(null);
        requestAnimationFrame(() => focusedId && itemRefs.current.get(focusedId)?.focus());
      }
    }} onSaved={() => {
      restoreFocus.current = true;
      setReload((value) => value + 1);
    }} open/> : null}
  </section>;
}
