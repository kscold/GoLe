"""Extract only reviewed tokens from the existing Tailwind theme. Run from repo root."""
from pathlib import Path
import re, json
css=Path('apps/web/src/app/globals.css').read_text()
keys=['color-brand-'+str(n) for n in [50,100,200,300,400,500,600,700,800,900,950]]+['color-accent-400','color-accent-500','color-surface-raised','color-text-secondary','radius-sm','radius-md','radius-lg','radius-xl','radius-2xl','space-section','space-card']
rows=[]
for key in keys:
 value=re.search(r'--'+key+r':\s*([^;]+);',css).group(1)
 rows.append(dict(key='--'+key, defaultValue=value, kind='color' if key.startswith('color') else 'length', min=0, max=8 if key.startswith('space') else 24, unit='rem' if key.startswith('space') else 'px'))
# Explicit base typography token, wired into html in globals.css.
rows.append(dict(key='--design-font-size',defaultValue='16px',kind='length',min=14,max=20,unit='px'))
p=Path('packages/core/src/design');p.mkdir(parents=True,exist_ok=True)
(p/'schema.ts').write_text('// Generated from globals.css by .kiro/specs/admin-design/generate-schema.py.\nexport const DESIGN_SCHEMA = '+json.dumps(rows,indent=2)+' as const;\n')
p=Path('apps/api/src/main/java/com/gole/api/design/domain/model');p.mkdir(parents=True,exist_ok=True)
entries=',\n'.join('        new Token('+', '.join([json.dumps(r['key']),json.dumps(r['defaultValue']),json.dumps(r['kind']),str(r['min']),str(r['max']),json.dumps(r['unit'])])+')' for r in rows)
(p/'DesignSchema.java').write_text('''package com.gole.api.design.domain.model;
import java.util.*;
import com.gole.api.common.exception.BadRequestException;
/** Reviewed allowlist extracted from the Tailwind theme; never accepts CSS source. */
public final class DesignSchema {
    private DesignSchema() {}
    public record Token(String key, String defaultValue, String kind, int min, int max, String unit) {}
    public static final List<Token> TOKENS = List.of(
'''+entries+''');
    public static Map<String,String> defaults() {
        Map<String,String> result = new LinkedHashMap<>();
        TOKENS.forEach(t -> result.put(t.key(), t.defaultValue()));
        return Map.copyOf(result);
    }
    public static Map<String,String> validate(Map<String,String> input) {
        if (input == null || input.size() != TOKENS.size()) throw invalid();
        for (Token t : TOKENS) {
            String value = input.get(t.key());
            if (value == null || value.length() > 16) throw invalid();
            if (t.kind().equals("color")) {
                if (!value.matches("#[0-9a-fA-F]{6}")) throw invalid();
            } else {
                if (!value.matches("[0-9]+(?:\\\\.[0-9]{1,3})?" + t.unit())) throw invalid();
                double n = Double.parseDouble(value.substring(0,value.length()-t.unit().length()));
                if (n < t.min() || n > t.max()) throw invalid();
            }
        }
        return Map.copyOf(input);
    }
    private static BadRequestException invalid() { return new BadRequestException("INVALID_DESIGN_TOKENS", "허용된 토큰과 값 범위를 확인해 주세요"); }
}
''')
