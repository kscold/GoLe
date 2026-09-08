import { apiRequest } from "../runtime";
import { DESIGN_SCHEMA } from "./schema";
export { DESIGN_SCHEMA } from "./schema";
export type DesignTokens = Readonly<Record<string, string>>;
export interface DesignRevision {
  readonly revision: number;
  readonly tokens: DesignTokens;
  readonly actorId: string;
  readonly reason: string;
  readonly action: string;
  readonly publishedAt: string;
}
export const DEFAULT_DESIGN_TOKENS: DesignTokens = Object.fromEntries(
  DESIGN_SCHEMA.map((token) => [token.key, token.defaultValue]),
);
export function validDesignTokens(input: unknown): input is DesignTokens {
  if (input === null || typeof input !== "object" || Array.isArray(input)) return false;
  const values = input as Record<string, unknown>;
  return (
    Object.keys(values).length === DESIGN_SCHEMA.length &&
    DESIGN_SCHEMA.every((token) => {
      const value = values[token.key];
      if (typeof value !== "string" || value.length > 16) return false;
      if (token.kind === "color") return /^#[0-9a-fA-F]{6}$/.test(value);
      if (!new RegExp(`^[0-9]+(?:\\.[0-9]{1,3})?${token.unit}$`).test(value)) return false;
      const n = Number(value.slice(0, -token.unit.length));
      return n >= token.min && n <= token.max;
    })
  );
}
export function designContrast(a: string, b: string): number {
  const luminance = (hex: string) => {
    const channels = [1, 3, 5].map((i) => {
      const n = parseInt(hex.slice(i, i + 2), 16) / 255;
      return n <= 0.04045 ? n / 12.92 : ((n + 0.055) / 1.055) ** 2.4;
    });
    return (channels[0] ?? 0) * 0.2126 + (channels[1] ?? 0) * 0.7152 + (channels[2] ?? 0) * 0.0722;
  };
  const x = luminance(a),
    y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}
export async function fetchPublishedDesign(signal?: AbortSignal): Promise<DesignTokens> {
  try {
    const data = await apiRequest<{ tokens?: unknown }>("/api/v1/config/design", {
      cache: "no-store",
      signal: signal ?? AbortSignal.timeout(2500),
    });
    return validDesignTokens(data.tokens) ? data.tokens : DEFAULT_DESIGN_TOKENS;
  } catch {
    return DEFAULT_DESIGN_TOKENS;
  }
}
export const fetchDesignEditor = () =>
  apiRequest<{ current: DesignRevision }>("/api/admin/design", { cache: "no-store" });
export const fetchDesignHistory = (before?: number) =>
  apiRequest<DesignRevision[]>(
    `/api/admin/design/history${before === undefined ? "" : `?before=${before}`}`,
    { cache: "no-store" },
  );
export const publishDesign = (expectedRevision: number, tokens: DesignTokens, reason: string) =>
  apiRequest<DesignRevision>("/api/admin/design/publish", {
    method: "POST",
    body: { expectedRevision, tokens, reason },
  });
export const restoreDesign = (expectedRevision: number, sourceRevision: number, reason: string) =>
  apiRequest<DesignRevision>("/api/admin/design/restore", {
    method: "POST",
    body: { expectedRevision, sourceRevision, reason },
  });
