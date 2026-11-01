import type { User } from 'oidc-client-ts';

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1').replace(/\/$/, '');

export type Money = { amount: string; currency: string };
export type Account = {
  id: string;
  displayName: string;
  maskedNumber: string;
  type: 'CHECKING' | 'SAVINGS';
  status: 'ACTIVE' | 'FROZEN' | 'CLOSED';
  balance: { ledger: Money; available: Money; pendingDebit: Money; asOf: string };
  version: number;
};
export type Transaction = {
  id: string;
  type: string;
  status: 'PENDING' | 'POSTED' | 'REVERSED' | 'DECLINED';
  amount: Money;
  description: string | null;
  relatedTransactionId: string | null;
  createdAt: string;
  postedAt: string | null;
};
export type AccountPage = { items: Account[]; page: { hasMore: boolean; nextCursor: string | null } };
export type TransactionPage = { items: Transaction[]; page: { hasMore: boolean; nextCursor: string | null } };
export type Transfer = { id: string; status: string; amount: Money; createdAt: string };
export type Authorization = {
  id: string;
  status: string;
  amount: Money;
  capturedAmount: Money;
  declineCode: string | null;
  createdAt: string;
  expiresAt: string;
};
export type ApiProblem = { code?: string; detail?: string; traceId?: string };

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly traceId?: string;

  constructor(status: number, problem: ApiProblem) {
    super(problem.detail ?? 'The request could not be completed.');
    this.name = 'ApiError';
    this.status = status;
    this.code = problem.code;
    this.traceId = problem.traceId;
  }
}

export async function apiRequest<T>(user: User, path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${user.access_token}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${apiBaseUrl}${path}`, { ...init, headers });
  if (!response.ok) {
    let problem: ApiProblem = {};
    try { problem = await response.json() as ApiProblem; } catch { /* Keep a safe generic message. */ }
    throw new ApiError(response.status, problem);
  }
  return response.json() as Promise<T>;
}

export async function loadAccountActivity(user: User, accountId: string) {
  const [account, transactions] = await Promise.all([
    apiRequest<Account>(user, `/accounts/${accountId}`),
    apiRequest<TransactionPage>(user, `/accounts/${accountId}/transactions?limit=6`),
  ]);
  return { account, transactions: transactions.items };
}
