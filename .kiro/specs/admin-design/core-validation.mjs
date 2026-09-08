import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';
const require = createRequire(resolve('apps/web/package.json'));
const ts = require('typescript');
let request = async () => { throw new Error('offline'); };
function load(file) {
  const exports = {};
  const source = ts.transpileModule(readFileSync(file,'utf8'), {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
  runInNewContext(source,{exports, require:(name)=>name==='../runtime'?{apiRequest:(...args)=>request(...args)}:load('packages/core/src/design/schema.ts'),AbortSignal});
  return exports;
}
const design=load('packages/core/src/design/index.ts');
assert.equal(design.validDesignTokens(design.DEFAULT_DESIGN_TOKENS),true);
for (const value of ['red','#fff','url(evil)','#ffffff;']) assert.equal(design.validDesignTokens({...design.DEFAULT_DESIGN_TOKENS,'--color-brand-600':value}),false);
for (const value of ['-1px','21px','NaNpx','1e2px']) assert.equal(design.validDesignTokens({...design.DEFAULT_DESIGN_TOKENS,'--design-font-size':value}),false);
assert.equal(design.validDesignTokens({...design.DEFAULT_DESIGN_TOKENS,'--arbitrary-css':'red'}),false);
assert.equal(design.designContrast('#ffffff','#ffffff'),1);
assert.equal(design.designContrast('#ffffff','#000000'),21);
assert.deepEqual(await design.fetchPublishedDesign(),design.DEFAULT_DESIGN_TOKENS);
request=async()=>({tokens:{'--evil':'url(evil)'}});
assert.deepEqual(await design.fetchPublishedDesign(),design.DEFAULT_DESIGN_TOKENS);
const changed={...design.DEFAULT_DESIGN_TOKENS,'--color-brand-600':'#663399'};
request=async()=>({tokens:changed});
assert.deepEqual(await design.fetchPublishedDesign(),changed);
const css=readFileSync('apps/web/src/app/globals.css','utf8');
for (const token of design.DESIGN_SCHEMA) {
 const match=css.match(new RegExp(`${token.key}:\\s*([^;]+);`));
 assert.equal(match?.[1],token.defaultValue,`CSS schema drift: ${token.key}`);
}
console.log('PASS: allowlist, bounds, CSS injection, contrast, offline/invalid fallback, published tokens, Tailwind schema parity');
