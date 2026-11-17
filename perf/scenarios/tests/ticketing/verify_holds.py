import http.server, json, os, subprocess, threading, time
from pathlib import Path
from urllib.parse import urlsplit
root=Path.cwd(); out=root/'.ai-output/ticketing-k6'; out.mkdir(parents=True, exist_ok=True); csv=out/'test-users.csv'
csv.write_text('user_id,access_token\n'+''.join(f'{n},token{n}\n' for n in range(1,5)))
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  body=b'ok' if urlsplit(self.path).path=='/actuator/prometheus' else b'[{"seatId":1,"status":"AVAILABLE"},{"seatId":2,"status":"AVAILABLE"},{"seatId":3,"status":"AVAILABLE"},{"seatId":4,"status":"AVAILABLE"}]'
  self.send_response(200); self.end_headers(); self.wfile.write(body)
 def do_POST(self):
  if getattr(self.server, 'slow', False): time.sleep(0.7)
  body=json.loads(self.rfile.read(int(self.headers['Content-Length']))); auth=self.headers.get('Authorization');key=self.headers.get('Idempotency-Key')
  with self.server.lock: self.server.requests.append((auth,key,body)); seen=sum(1 for a,k,b in self.server.requests if k==key)
  response=json.dumps({'reservationId':int(auth[-1])}).encode();self.send_response(201);self.end_headers();self.wfile.write(response)
 def log_message(self,*args):pass
s=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);s.lock=threading.Lock();threading.Thread(target=s.serve_forever,daemon=True).start()
env=os.environ.copy();env.update(BASE_URL='http://perf-app:8080',MANAGEMENT_URL='http://perf-app:8080',PERF_ALLOWED_APP_HOSTS='perf-app',PERFORMANCE_ID='1',USERS_CSV=str(csv),PARTICIPANTS='4',VUS='4',RUN_ID='holds',HTTP_PROXY=f'http://127.0.0.1:{s.server_port}',HTTPS_PROXY=f'http://127.0.0.1:{s.server_port}',NO_PROXY='')
try:
 for mode in ['hot','overlap','distributed','replay']:
  s.requests=[];r=subprocess.run(['k6','run','--quiet',str(root/'perf/scenarios/ticketing'/f'{mode}.js')],env=env,capture_output=True,text=True,timeout=20);assert r.returncode==0,r.stderr
  assert len(s.requests)==(8 if mode=='replay' else 4),(mode,len(s.requests))
  seats=[body['seatIds'] for _,_,body in s.requests]
  if mode=='hot': assert all(x==[1] for x in seats)
  if mode=='overlap': assert sorted(seats)==[[1,2],[1,2],[2,3],[2,3]]
  if mode=='distributed': assert sorted(seats)==[[1],[2],[3],[4]]
  if mode=='replay':
   for _,key,body in s.requests: assert sum(1 for _,k,b in s.requests if k==key and b==body)==2
  print(f'{mode}: passed, {len(s.requests)} requests')
 s.requests=[]; s.slow=True
 env.update(VUS='1',MAX_DURATION='1s',SUMMARY_PATH=str(out/'incomplete-summary.json'))
 r=subprocess.run(['k6','run','--quiet',str(root/'perf/scenarios/ticketing/distributed.js')],env=env,capture_output=True,text=True,timeout=20)
 m=json.loads(Path(env['SUMMARY_PATH']).read_text())['metrics']
 assert r.returncode != 0 and m['ticket_hold_completed']['values']['count'] < 4
 assert 'conditions' in json.loads(Path(env['SUMMARY_PATH']).read_text())
 print('incomplete: passed; incomplete hold requests fail')
finally:s.shutdown()
