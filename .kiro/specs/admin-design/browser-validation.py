"""Run after installing browser-mock.js in the dedicated design tab. Never sends real design writes."""
import json,subprocess
PAGE='9038a99b-8f82-4720-9ed0-c37178aa56f2'
def call(*args):
 r=subprocess.run(['orca',*args,'--page',PAGE,'--json'],capture_output=True,text=True)
 data=json.loads(r.stdout)
 if not data.get('ok'):raise RuntimeError(data)
 return data['result']
def ev(js):
 result=call('eval','--expression',js)['result']
 try:return json.loads(result)
 except:return result
def act(command,name,role=None,value=None):
 refs=call('snapshot')['refs']
 key=next(k for k,v in refs.items() if v['name']==name and (role is None or v['role']==role))
 args=[command,'--element','@'+key]
 if value is not None:args+=['--value',value]
 if command == 'click':
  return ev('([...document.querySelectorAll("button")].find(b=>b.textContent==='+json.dumps(name)+')).click()')
 if command == 'check':
  return ev('(()=>{const c=document.querySelector("input[type=checkbox]");if(!c.checked)c.click();return c.checked;})()')
 return call(*args)
assert ev('!!window.__designMock') is True
results={}
ev('window.__designMock.conflict=false')
act('click','최신 값 다시 불러오기 · 편집 취소')
call('wait','--text','최신 게시 값을 불러왔습니다.')
results['mobile']=ev('({width:innerWidth,height:innerHeight,overflow:document.documentElement.scrollWidth>innerWidth})')
ev('window.__designMock.conflict=true')
act('fill','--color-brand-600',value='#005544')
act('fill','변경 사유 (감사 기록, 필수)',value='로컬 충돌 검증')
act('check','미리보기와 대비를 확인했으며 전체 사이트에 게시합니다.')
act('click','검토한 테마 게시')
results['conflict']=ev('({message:document.body.innerText.includes("다른 관리자가 먼저 변경했습니다"),draft:document.querySelector("input[aria-label=\\"--color-brand-600\\"]").value,disabled:[...document.querySelectorAll("button")].find(b=>b.textContent==="검토한 테마 게시").disabled})')
assert results['conflict']['message'] and results['conflict']['disabled'] and results['conflict']['draft']=='#005544'
ev('window.__designMock.conflict=false')
act('click','최신 값 다시 불러오기 · 편집 취소')
call('wait','--text','최신 게시 값을 불러왔습니다.')
act('click','기본값 미리보기')
act('fill','변경 사유 (감사 기록, 필수)',value='로컬 기본값 복원 검증')
act('check','미리보기와 대비를 확인했으며 전체 사이트에 게시합니다.')
act('click','검토한 테마 게시')
call('wait','--text','게시 완료 · revision 3')
results['reset']=ev('({published:document.body.innerText.includes("게시 완료 · revision 3"),root:getComputedStyle(document.documentElement).getPropertyValue("--color-brand-600"),posts:window.__designMock.requests.filter(r=>r.method==="POST")})')
assert results['reset']['published'] and results['reset']['root']=='#1d4ed8'
open('.kiro/specs/admin-design/browser-results.json','w').write(json.dumps(results,indent=2,ensure_ascii=False))
print(json.dumps(results,ensure_ascii=False))
