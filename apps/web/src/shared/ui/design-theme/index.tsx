"use client";
import "@shared/config/bootstrap";
import { useEffect } from "react";
import { fetchPublishedDesign } from "@gole/core/design";
/** CSS defaults render immediately; validated publications load after hydration. */
export function DesignTheme() {
  useEffect(() => {
    let active = true;
    let generation = 0;
    const refresh = () => {
      const requested = ++generation;
      void fetchPublishedDesign().then((tokens) => {
        if (active && requested === generation)
          Object.entries(tokens).forEach(([key, value]) =>
            document.documentElement.style.setProperty(key, value),
          );
      });
    };
    refresh();
    window.addEventListener("gole:design-published", refresh);
    window.addEventListener("focus", refresh);
    return () => {
      active = false;
      window.removeEventListener("gole:design-published", refresh);
      window.removeEventListener("focus", refresh);
    };
  }, []);
  return null;
}
