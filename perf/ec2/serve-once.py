"""Serve one file once over HTTP, then exit. Standard library only.

perf-ec2.sh serve-tokens uses it to hand the seed token bundle to the laptop that runs k6.
Only GET /<file name> is answered; every other path gets 404. The process exits after the first
complete download, so the tokens are not left exposed.
"""
import http.server
import os
import socketserver
import sys

path, port = sys.argv[1], int(sys.argv[2])
name = "/" + os.path.basename(path)


class Handler(http.server.BaseHTTPRequestHandler):
    done = False

    def do_GET(self):
        if self.path != name:
            self.send_error(404)
            return
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "application/gzip")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while chunk := f.read(1 << 20):
                self.wfile.write(chunk)
        Handler.done = True
        print(f"sent {name} ({size} bytes) to {self.client_address[0]}", flush=True)


class Server(http.server.HTTPServer):
    # HTTPServer.server_bind looks up the host name (getfqdn), which can hang without reverse DNS.
    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


server = Server(("0.0.0.0", port), Handler)
while not Handler.done:
    server.handle_request()
