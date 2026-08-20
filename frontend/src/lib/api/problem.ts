export type ApiFieldError = {
  field: string;
  message: string;
};

export type ApiProblem = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  code: string;
  traceId: string;
  fieldErrors: ApiFieldError[];
};

export class ApiProblemError extends Error implements ApiProblem {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  readonly code: string;
  readonly traceId: string;
  readonly fieldErrors: ApiFieldError[];

  constructor(problem: ApiProblem) {
    super(problem.detail ?? problem.title);
    this.name = "ApiProblemError";
    this.type = problem.type;
    this.title = problem.title;
    this.status = problem.status;
    this.detail = problem.detail;
    this.code = problem.code;
    this.traceId = problem.traceId;
    this.fieldErrors = problem.fieldErrors;
  }
}

export function isApiProblemError(error: unknown): error is ApiProblemError {
  return error instanceof ApiProblemError;
}
