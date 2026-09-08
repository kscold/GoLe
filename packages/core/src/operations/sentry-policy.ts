/** SDK-independent preparation. No transport, DSN, token, request body or user data. */
export interface SafeSentryEvent {
  level: "error" | "fatal";
  environment: "production";
  message: string;
  fingerprint: string[];
  tags: { component: string; diagnostic: string };
}
export interface SentryPolicyInput {
  level?: string;
  environment?: string;
  tags?: Record<string, unknown>;
  [key: string]: unknown;
}
const components = new Set(["web", "api", "operations"]);
const diagnostics = new Set(["UNEXPECTED_ERROR", "DEPENDENCY_UNAVAILABLE", "JOB_FAILED"]);

/** Opt-in beforeSend adapter: allowlist projection, bounded local dedupe and rate budget.
 * Global / cross-instance alert throttling must also be configured in Sentry.
 * Dropped events are deliberately not counted as delivery or persisted as successful sends.
 */
export function createSentryPolicy({
  enabled = false,
  now = Date.now,
}: { enabled?: boolean; now?: () => number } = {}) {
  const accepted = new Map<string, number>();
  let windowStart = 0;
  let windowCount = 0;
  return (event: SentryPolicyInput): SafeSentryEvent | null => {
    if (
      !enabled ||
      event.environment !== "production" ||
      (event.level !== "error" && event.level !== "fatal")
    )
      return null;
    const component =
      typeof event.tags?.component === "string" && components.has(event.tags.component)
        ? event.tags.component
        : "web";
    const diagnostic =
      typeof event.tags?.diagnostic === "string" && diagnostics.has(event.tags.diagnostic)
        ? event.tags.diagnostic
        : "UNEXPECTED_ERROR";
    const timestamp = now();
    const key = `${component}:${diagnostic}`;
    for (const [fingerprint, time] of accepted)
      if (timestamp - time >= 300_000 || timestamp < time) accepted.delete(fingerprint);
    if (timestamp - windowStart >= 60_000 || timestamp < windowStart) {
      windowStart = timestamp;
      windowCount = 0;
    }
    if (accepted.has(key) || windowCount >= 3) return null;
    accepted.set(key, timestamp);
    windowCount += 1;
    // Never spread the original event: exception text, stack locals, breadcrumbs, extras,
    // request URL/headers, user, session replay and arbitrary tags can contain credentials.
    return {
      level: event.level,
      environment: "production",
      message: diagnostic,
      fingerprint: ["gole", component, diagnostic],
      tags: { component, diagnostic },
    };
  };
}
