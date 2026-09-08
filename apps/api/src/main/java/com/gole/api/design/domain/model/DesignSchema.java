package com.gole.api.design.domain.model;

import com.gole.api.common.exception.BadRequestException;
import java.util.*;

/** Reviewed allowlist extracted from the Tailwind theme; never accepts CSS source. */
public final class DesignSchema {
    private DesignSchema() {}

    public record Token(String key, String defaultValue, String kind, int min, int max, String unit) {}

    public static final List<Token> TOKENS = List.of(
            new Token("--color-brand-50", "#eff4ff", "color", 0, 24, "px"),
            new Token("--color-brand-100", "#dbe6fe", "color", 0, 24, "px"),
            new Token("--color-brand-200", "#bfcffe", "color", 0, 24, "px"),
            new Token("--color-brand-300", "#93aefb", "color", 0, 24, "px"),
            new Token("--color-brand-400", "#6082f7", "color", 0, 24, "px"),
            new Token("--color-brand-500", "#3b5cf2", "color", 0, 24, "px"),
            new Token("--color-brand-600", "#1d4ed8", "color", 0, 24, "px"),
            new Token("--color-brand-700", "#1a3fc0", "color", 0, 24, "px"),
            new Token("--color-brand-800", "#1b359c", "color", 0, 24, "px"),
            new Token("--color-brand-900", "#1c2f7c", "color", 0, 24, "px"),
            new Token("--color-brand-950", "#131e4f", "color", 0, 24, "px"),
            new Token("--color-accent-400", "#facc15", "color", 0, 24, "px"),
            new Token("--color-accent-500", "#eab308", "color", 0, 24, "px"),
            new Token("--color-surface-raised", "#fcfbf8", "color", 0, 24, "px"),
            new Token("--color-text-secondary", "#5b524b", "color", 0, 24, "px"),
            new Token("--radius-sm", "4px", "length", 0, 24, "px"),
            new Token("--radius-md", "8px", "length", 0, 24, "px"),
            new Token("--radius-lg", "10px", "length", 0, 24, "px"),
            new Token("--radius-xl", "14px", "length", 0, 24, "px"),
            new Token("--radius-2xl", "18px", "length", 0, 24, "px"),
            new Token("--space-section", "5rem", "length", 0, 8, "rem"),
            new Token("--space-card", "1.25rem", "length", 0, 8, "rem"),
            new Token("--design-font-size", "16px", "length", 14, 20, "px"));

    public static Map<String, String> defaults() {
        Map<String, String> result = new LinkedHashMap<>();
        TOKENS.forEach(t -> result.put(t.key(), t.defaultValue()));
        return Map.copyOf(result);
    }

    public static Map<String, String> validate(Map<String, String> input) {
        if (input == null || input.size() != TOKENS.size()) throw invalid();
        for (Token t : TOKENS) {
            String value = input.get(t.key());
            if (value == null || value.length() > 16) throw invalid();
            if (t.kind().equals("color")) {
                if (!value.matches("#[0-9a-fA-F]{6}")) throw invalid();
            } else {
                if (!value.matches("[0-9]+(?:\\.[0-9]{1,3})?" + t.unit())) throw invalid();
                double n = Double.parseDouble(
                        value.substring(0, value.length() - t.unit().length()));
                if (n < t.min() || n > t.max()) throw invalid();
            }
        }
        return Map.copyOf(input);
    }

    private static BadRequestException invalid() {
        return new BadRequestException("INVALID_DESIGN_TOKENS", "허용된 토큰과 값 범위를 확인해 주세요");
    }
}
