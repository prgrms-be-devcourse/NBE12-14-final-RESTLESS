import http.server, json, os, subprocess, threading, time
from pathlib import Path
from urllib.parse import urlsplit
root=Path.cwd(); out=root/'.ai-output/ticketing-k6'; out.mkdir(parents=True,exist_ok=True)
csv=out/'mixed-users.csv'; csv.write_text('user_id,access_token\n'+''.join(f'{n},token{n}\n' for n in range(1,5)))
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if urlsplit(self.path).path=='/actuator/prometheus': body=b'mock_metric 1\n'
  else:
   self.server.events.append(('get',time.monotonic(),self.headers.get('Authorization')))
   body=b'[{"seatId":1,"status":"AVAILABLE"},{"seatId":2,"status":"AVAILABLE"}]'
  self.send_response(200); self.end_headers(); self.wfile.write(body)
 def do_POST(self):
  self.server.events.append(('post',time.monotonic(),self.headers.get('Authorization')))
  self.rfile.read(int(self.headers['Content-Length']))
  with self.server.lock:
   self.server.holds+=1; n=self.server.holds
  if self.server.bad: status=500; body=b'{"code":"ERROR"}'
  elif self.server.mode=='hot' and n>1: status=409; body=b'{"code":"SEAT_UNAVAILABLE"}'
  else: status=201; body=json.dumps({'reservationId':n}).encode()
  self.send_response(status); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(body)
 def log_message(self,*args): pass
srv=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler); srv.lock=threading.Lock()
threading.Thread(target=srv.serve_forever,daemon=True).start()
try:
 env=os.environ.copy(); env.update(BASE_URL='http://perf-app:8080', MANAGEMENT_URL='http://perf-app:8080', PERF_ALLOWED_APP_HOSTS='perf-app', PERFORMANCE_ID='1', USERS_CSV=str(csv), MODE='mixed', PARTICIPANTS='2', HOLD_PARTICIPANTS='2', HOLD_DELAY_SECONDS='1', DURATION='5s', RUN_ID='verify', POLL_ENTRY='simultaneous', HTTP_PROXY=f'http://127.0.0.1:{srv.server_port}', HTTPS_PROXY=f'http://127.0.0.1:{srv.server_port}', NO_PROXY='')
 for mode,bad in [('hot',False),('distributed',False),('hot',True)]:
  srv.events=[]; srv.holds=0; srv.mode=mode; srv.bad=bad
  env['MIXED_HOLD']=mode; summary=out/f'mixed-{mode}-{bad}.json'; env['SUMMARY_PATH']=str(summary)
  r=subprocess.run(['k6','run','--quiet',str(root/('perf/scenarios/ticketing/'+('mixed-distributed' if mode=='distributed' else 'mixed')+'.js'))],env=env,capture_output=True,text=True,timeout=25)
  m=json.loads(summary.read_text())['metrics']; attempts=m['ticket_hold_attempts']['values']['count']; polls=m['ticket_poll_requests']['values']['count']
  assert attempts==2 and polls>=4, (attempts,polls)
  gets=[x for x in srv.events if x[0]=='get']; posts=[x for x in srv.events if x[0]=='post']
  assert len(posts)==2 and {p[2] for p in posts}=={'Bearer token3','Bearer token4'}
  assert {p[2] for p in gets}=={'Bearer token1','Bearer token2'}
  assert min(x[1] for x in gets)<min(x[1] for x in posts)<max(x[1] for x in gets)
  assert (r.returncode!=0)==bad, (mode,bad,r.stderr[-500:])
  if not bad:
   assert m['ticket_unexpected']['values']['rate']==0
   c=m['ticket_outcomes{outcome:unavailable}']['values']['count'] if 'ticket_outcomes{outcome:unavailable}' in m else None
  print(f'{mode} error={bad}: pass; holds={attempts}, polls={polls}, exit={r.returncode}')
 srv.events=[]; srv.holds=0; srv.mode='hot'; srv.bad=False
 host=f'perf-test-{time.time_ns()}'
 env.update(DURATION='5s',HOLD_DELAY_SECONDS='1',POLL_ENTRY='simultaneous',BASE_URL=f'http://{host}:8080',MANAGEMENT_URL=f'http://{host}:8080',PERF_ALLOWED_APP_HOSTS=host,K6_WEB_DASHBOARD='false')
 p=subprocess.run([str(root/'perf/scenarios/run-ticketing.sh'),'mixed','4'],env=env,capture_output=True,text=True,timeout=25)
 assert p.returncode==0, p.stderr[-500:]
 output_path=next(line.split('요약 JSON: ',1)[1] for line in p.stdout.splitlines() if line.startswith('요약 JSON: '))
 metrics=json.loads(Path(output_path).read_text())['metrics']
 assert metrics['ticket_hold_completed']['values']['count']==1
 assert metrics['ticket_poll_requests']['values']['count']>=3
 print('short runner + mixed.js: passed, 3 readers + 1 holder')
finally: srv.shutdown()
