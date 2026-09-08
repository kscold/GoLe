import { apiRequest } from "../runtime";

export interface TrackerDiagnostics {
  readonly enabled: boolean;
  readonly configured: boolean;
  readonly connected: boolean;
  readonly lastSuccessAt: string | null;
  readonly lastFailureAt: string | null;
  readonly lastFailure: string | null;
}
export interface TrackerSample {
  readonly carrier: string;
  readonly maskedWaybill: string;
  readonly status: "PENDING" | "IN_TRANSIT" | "DELIVERED" | "UNKNOWN";
  readonly cached: boolean;
  readonly live: boolean;
  readonly checkedAt: string;
  readonly failure: string | null;
}
const base = "/api/admin/integrations/tracker";
export function fetchTrackerStatus(signal?: AbortSignal): Promise<TrackerDiagnostics> {
  return apiRequest(base, { cache: "no-store", ...(signal ? { signal } : {}) });
}
export function verifyTrackerConnection(): Promise<TrackerDiagnostics> {
  return apiRequest(`${base}/verify`, { method: "POST" });
}
export function queryTrackerSample(carrier: string, waybillNumber: string): Promise<TrackerSample> {
  return apiRequest(`${base}/sample`, { method: "POST", body: { carrier, waybillNumber } });
}
export function requeryTrackerShipment(orderId: string): Promise<TrackerSample> {
  return apiRequest(`${base}/requery`, { method: "POST", body: { orderId } });
}
