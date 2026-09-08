import { apiRequest } from "../runtime";

export interface OperationJob {
  id: string;
  title: string;
  description: string;
}
export interface OperationRun {
  id: string;
  jobId: string;
  actorId: string;
  reasonCode: string;
  retryOf: string | null;
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
  startedAt: string;
  finishedAt: string | null;
  resultCode: string | null;
}
export interface OperationsSnapshot {
  jobs: OperationJob[];
  history: OperationRun[];
}
export function fetchOperations(token: string) {
  return apiRequest<OperationsSnapshot>("/api/admin/operations", {
    cache: "no-store",
    headers: token.length > 0 ? { Authorization: `Bearer ${token}` } : {},
  });
}
export function executeOperation(
  token: string,
  jobId: string,
  reasonCode: "MANUAL_CHECK" | "INCIDENT_REVIEW" | "RETRY_FAILED",
  retryOf?: string,
) {
  return apiRequest<OperationRun>(`/api/admin/operations/${encodeURIComponent(jobId)}/runs`, {
    method: "POST",
    headers: token.length > 0 ? { Authorization: `Bearer ${token}` } : {},
    body: { reasonCode, retryOf },
  });
}

export { createSentryPolicy } from "./sentry-policy";
export type { SafeSentryEvent, SentryPolicyInput } from "./sentry-policy";
