import http.server, os, subprocess, threading, time
from pathlib import Path
from urllib.parse import urlsplit
root=Path.cwd(); out=root/'.ai-output/ticketing-k6'
csv=out/'timing-users.csv'; csv.write_text('user_id,access_token\n'+''.join(f'{i},token{i}\n' for i in range(1,6)))
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if urlsplit(self.path).path=='/actuator/prometheus': body=b'ok'
  elif urlsplit(self.path).path.endswith('/booking-availability'): body=b'{"maximum":3,"confirmed":0,"reserved":0,"remaining":3,"activeReservation":null}'
  else: self.server.events.append((self.headers.get('Authorization'),time.monotonic())); body=b'[{"seatId":1,"status":"AVAILABLE"}]'
  self.send_response(200); self.end_headers(); self.wfile.write(body)
 def do_POST(self):
  self.rfile.read(int(self.headers['Content-Length'])); self.send_response(201); self.end_headers(); self.wfile.write(b'{"reservationId":1}')
 def log_message(self,*args): pass
class Server(http.server.ThreadingHTTPServer):
 request_queue_size=64  # 조회자마다 요청 2개를 동시에 보낸다.
s=Server(('127.0.0.1',0),Handler); s.events=[]
threading.Thread(target=s.serve_forever,daemon=True).start()
env=os.environ.copy();env.update(BASE_URL='http://perf-app:8080',MANAGEMENT_URL='http://perf-app:8080',PERF_ALLOWED_APP_HOSTS='perf-app',PERFORMANCE_ID='1',USERS_CSV=str(csv),MODE='mixed',PARTICIPANTS='4',HOLD_PARTICIPANTS='1',VUS='1000',HOLD_DELAY_SECONDS='1',DURATION='5s',RUN_ID='timing',POLL_ENTRY='spread',HTTP_PROXY=f'http://127.0.0.1:{s.server_port}',HTTPS_PROXY=f'http://127.0.0.1:{s.server_port}',NO_PROXY='')
try:
 r=subprocess.run(['k6','run','--quiet',str(root/'perf/scenarios/ticketing/mixed.js')],env=env,capture_output=True,text=True,timeout=20);assert r.returncode==0,r.stderr
 first={}
 for token,t in s.events[1:]:first.setdefault(token,t)
 assert len(first)==4
 span=max(first.values())-min(first.values());assert 1.8<span<3.0,span
 print(f'mixed spread with inherited VUS=1000: 4 readers span {span:.2f}s: passed')
finally:s.shutdown()
