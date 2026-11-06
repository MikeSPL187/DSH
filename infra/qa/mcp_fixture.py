"""Disposable, deterministic MCP HTTP peer. No model API or credentials required."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading


@contextmanager
def server():
    methods = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, value=None):
            body = b'' if value is None else json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Mcp-Session-Id', 'qa-lifecycle')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.reply(405)

        def do_DELETE(self):
            self.reply(200)

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
            method = request.get('method')
            methods.append(method)
            if 'id' not in request:
                self.reply(202)
                return
            if method == 'initialize':
                result = {'protocolVersion': request['params']['protocolVersion'], 'capabilities': {'tools': {}},
                          'serverInfo': {'name': 'dsh-qa-lifecycle', 'version': '1.0.0'}}
            elif method == 'tools/list':
                result = {'tools': [{'name': 'qa_ping', 'description': 'Return pong',
                                     'inputSchema': {'type': 'object', 'properties': {}}}]}
            elif method == 'tools/call':
                result = {'content': [{'type': 'text', 'text': 'pong'}]}
            elif method == 'ping':
                result = {}
            else:
                self.reply(200, {'jsonrpc': '2.0', 'id': request['id'], 'error': {'code': -32601, 'message': 'Method not found'}})
                return
            self.reply(200, {'jsonrpc': '2.0', 'id': request['id'], 'result': result})

    http = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    worker = threading.Thread(target=http.serve_forever, daemon=True)
    worker.start()
    try:
        yield http.server_port, methods
    finally:
        http.shutdown()
        http.server_close()
        worker.join(timeout=5)
