import json, os, subprocess
from pathlib import Path
root=Path.cwd(); out=root/'.ai-output/ticketing-k6'; out.mkdir(parents=True,exist_ok=True)
fake=out/'fake-k6'; fake.write_text('#!/usr/bin/env python3\nimport json,os,sys\nprint(json.dumps({"SCRIPT":sys.argv[-1], **{k:os.environ.get(k) for k in ["MODE","POLL_ENTRY","PARTICIPANTS","VUS","HOLD_PARTICIPANTS","DURATION","MIXED_HOLD","RUN_ID","SUMMARY_PATH"]}}))\n'); fake.chmod(0o700)
csv=out/'runner-users.csv'; csv.write_text('user_id,access_token\n1,token1\n')
env=os.environ.copy(); env.update(BASE_URL='http://perf-app:8080',MANAGEMENT_URL='http://perf-app:8081',PERF_ALLOWED_APP_HOSTS='perf-app',PERFORMANCE_ID='1',USERS_CSV=str(csv),K6_BIN=str(fake))
script=str(root/'perf/scenarios/run-ticketing.sh')
for scenario in ['spread','simultaneous','hot','overlap','distributed','replay','mixed','mixed-distributed']:
 for n in (100,300,500):
  p=subprocess.run([script,scenario,str(n)],env=env,capture_output=True,text=True,check=True)
  d=json.loads(p.stdout.splitlines()[-1]); assert d['SCRIPT'].endswith('/ticketing/'+scenario+'.js'); assert d['MODE']==('poll' if scenario in ('spread','simultaneous') else 'mixed' if scenario.startswith('mixed') else scenario)
  assert int(d['PARTICIPANTS'])==(n*4//5 if scenario.startswith('mixed') else n)
  assert (int(d['HOLD_PARTICIPANTS']) if scenario.startswith('mixed') else 0)==(n-n*4//5 if scenario.startswith('mixed') else 0)
  assert d['VUS']==str(n) and str(root/'.ai-output/perf') in d['SUMMARY_PATH']
  if scenario=='mixed-distributed': assert d['MIXED_HOLD']=='distributed'
for args in (['bad','100'],['hot','0'],['mixed','1'],[]):
 assert subprocess.run([script,*args],env=env,capture_output=True).returncode != 0
print('24 scenario/size combinations and 4 invalid inputs: passed')
