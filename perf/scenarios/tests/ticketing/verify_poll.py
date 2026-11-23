import http.server, json, os, subprocess, threading, time
from pathlib import Path
from urllib.parse import urlsplit
root=Path.cwd(); out=root/'.ai-output/ticketing-k6'; out.mkdir(parents=True,exist_ok=True)
csv=out/'test-users.csv'; csv.write_text('user_id,access_token\n'+''.join(f'{n},token{n}\n' for n in range(1,5)))
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  path=urlsplit(self.path).path
  if path=='/actuator/prometheus': body=b'probe'
  elif path=='/api/performances/1/booking-availability': body=b'{"maximum":3,"confirmed":0,"reserved":0,"remaining":3,"activeReservation":null}'
  elif path in ('/api/performances/1/seats','/api/performances/1/seats/status'):
   auth=self.headers.get('Authorization',''); self.server.seen.append((time.monotonic(),auth,path));
   if getattr(self.server,'reject',False) and auth.endswith('token2'): self.send_error(401); return
   body=b'[{"seatId":1,"status":"HELD"}]'
  else: self.send_error(404); return
  self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(body)
 def log_message(self,*args): pass
class Server(http.server.ThreadingHTTPServer):
 request_queue_size=64
srv=Server(('127.0.0.1',0),Handler); srv.seen=[]
threading.Thread(target=srv.serve_forever,daemon=True).start()
try:
 for entry in ('simultaneous','spread','page'):
  srv.seen=[]
  env=os.environ.copy(); env.update(BASE_URL='http://perf-app:8080', MANAGEMENT_URL='http://perf-app:8080', PERF_ALLOWED_APP_HOSTS='perf-app', PERFORMANCE_ID='1', USERS_CSV=str(csv), MODE='poll', PARTICIPANTS='4', VUS='4', DURATION='4s', RUN_ID='verify', POLL_ENTRY='simultaneous' if entry=='page' else entry, HTTP_PROXY=f'http://127.0.0.1:{srv.server_port}', HTTPS_PROXY=f'http://127.0.0.1:{srv.server_port}', NO_PROXY='')
  summary=out/f'verify-{entry}.json'
  env['SUMMARY_PATH']=str(summary)
  result=subprocess.run(['k6','run','--quiet',str(root/('perf/scenarios/ticketing/'+entry+'.js'))],env=env,capture_output=True,text=True,timeout=25)
  if result.returncode: raise RuntimeError(f'{entry} failed: {result.stderr[-1000:]}')
  metrics=json.loads(summary.read_text())['metrics']; assert metrics['ticket_unexpected']['values']['rate']==0
  initial={}
  for t,token,path in srv.seen[1:]: initial.setdefault(token,t)
  assert len(initial)==4, (entry, len(initial))
  span=max(initial.values())-min(initial.values())
  if entry in ('simultaneous','page'): assert span < 1, span
  else: assert 1.5 < span < 3.5, span
  for token in initial:
   paths=[path for _,auth,path in srv.seen[1:] if auth==token]
   assert paths[0].endswith('/seats') and all(path.endswith('/seats/status') for path in paths[1:]),paths
  assert any(path.endswith('/seats/status') for _,_,path in srv.seen[1:])
  print(f'{entry}: pass; distinct users={len(initial)}, first-request span={span:.2f}s, errors=0')
 srv.reject=True
 env.update(POLL_ENTRY='simultaneous', DURATION='1s', SUMMARY_PATH=str(out/'verify-unauthorized.json'))
 result=subprocess.run(['k6','run','--quiet',str(root/'perf/scenarios/ticketing/simultaneous.js')],env=env,capture_output=True,text=True,timeout=25)
 assert result.returncode != 0, '401 must fail threshold'
 print('unauthorized: pass; k6 exit nonzero')
finally: srv.shutdown()
