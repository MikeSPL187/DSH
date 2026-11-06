"""Disposable MCP and HTTP model peers; only a synthetic test credential is accepted."""
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
            if self.path.endswith('/models'):
                self.reply(200, {'object': 'list', 'data': [{'id': 'qa-model'}, {'id': 'qa-fail'}]})
            else:
                self.reply(405)

        def do_DELETE(self):
            self.reply(200)

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
            if self.path.endswith('/chat/completions'):
                model = request.get('model')
                methods.append('llm/' + str(model))
                if self.headers.get('Authorization') != 'Bearer qa-runtime-synthetic-secret':
                    methods.append('llm/auth-bad')
                    self.reply(401, {'error': {'message': 'Synthetic credential was not forwarded'}})
                    return
                methods.append('llm/auth-ok')
                if model == 'qa-fail':
                    self.reply(400, {'error': {'message': 'QA controlled model refusal', 'type': 'invalid_request_error', 'code': 'qa_refused'}})
                    return
                usage = {'prompt_tokens': 10, 'completion_tokens': 1, 'total_tokens': 11}
                if not request.get('stream'):
                    self.reply(200, {'id': 'qa', 'object': 'chat.completion', 'created': 0, 'model': model,
                                     'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': 'ping'}, 'finish_reason': 'stop'}], 'usage': usage})
                    return
                base = {'id': 'qa', 'object': 'chat.completion.chunk', 'created': 0, 'model': model}
                events = [dict(base, choices=[{'index': 0, 'delta': {'role': 'assistant', 'content': 'ping'}, 'finish_reason': None}]),
                          dict(base, choices=[{'index': 0, 'delta': {}, 'finish_reason': 'stop'}], usage=usage)]
                body = (''.join('data: ' + json.dumps(e) + '\n\n' for e in events) + 'data: [DONE]\n\n').encode()
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
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
