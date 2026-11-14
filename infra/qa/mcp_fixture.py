"""Disposable MCP and HTTP model peers; only a synthetic test credential is accepted."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import threading


@contextmanager
def server():
    methods = []
    recovered = threading.Event()
    recovery_tail = threading.Event()

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
            if self.path == '/qa/recovery-tail':
                recovery_tail.set()
                self.reply(200, {'ready': True})
                return
            if self.path == '/qa/recovery-resume':
                recovered.set()
                self.reply(200, {'ready': True})
                return
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
                if model == 'qa-recovery' and not recovered.is_set():
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.end_headers()
                    partial = dict(base, choices=[{'index': 0, 'delta': {'role': 'assistant', 'content': 'Native '}, 'finish_reason': None}])
                    self.wfile.write(('data: ' + json.dumps(partial) + '\n\n').encode())
                    self.wfile.flush()
                    methods.append('llm/recovery-partial')
                    # Release the tail only after Android confirms a durable prefix checkpoint.
                    if not recovery_tail.wait(90):
                        return
                    trailing = dict(base, choices=[{'index': 0, 'delta': {'content': 'partial response'}, 'finish_reason': None}])
                    methods.append('llm/recovery-tail')
                    self.wfile.write(('data: ' + json.dumps(trailing) + '\n\n').encode())
                    self.wfile.flush()
                    recovered.wait(180)
                    try:
                        self.wfile.write(b'data: [DONE]\n\n')
                    except (BrokenPipeError, ConnectionResetError):
                        pass
                    return
                events = [dict(base, choices=[{'index': 0, 'delta': {'role': 'assistant', 'content': 'ping'}, 'finish_reason': None}]),
                          dict(base, choices=[{'index': 0, 'delta': {}, 'finish_reason': 'stop'}], usage=usage)]
                if model == 'qa-readonly':
                    results = [m.get('content', '') for m in request.get('messages', []) if m.get('role') == 'tool']
                    if not results:
                        tools = [t['function'] for t in request.get('tools', []) if re.search(r'(?:^|[._])(?:bash|shell)$', t['function']['name'])]
                        if len(tools) != 1:
                            self.reply(400, {'error': {'message': 'QA expected one shell tool, observed: ' + ', '.join(t['function']['name'] for t in request.get('tools', []))}})
                            return
                        tool = tools[0]
                        fields = tool.get('parameters', {}).get('properties', {})
                        args = {'command' if 'command' in fields else 'cmd': 'uname -a'}
                        if 'description' in fields:
                            args['description'] = 'Read kernel identity without modifying files'
                        call = {'index': 0, 'id': 'qa_readonly_call', 'type': 'function',
                                'function': {'name': tool['name'], 'arguments': json.dumps(args)}}
                        events = [dict(base, choices=[{'index': 0, 'delta': {'role': 'assistant', 'tool_calls': [call]}, 'finish_reason': None}]),
                                  dict(base, choices=[{'index': 0, 'delta': {}, 'finish_reason': 'tool_calls'}], usage=usage)]
                        methods.append('llm/readonly-shell-requested')
                    else:
                        detail = ' '.join(v if isinstance(v, str) else json.dumps(v) for v in results)
                        denied = bool(re.search(r'sandbox|denied|not permitted', detail, re.I))
                        methods.append('llm/readonly-denied' if denied else 'llm/readonly-unexpected-result')
                        events[0]['choices'][0]['delta']['content'] = 'Read-only shell report: ' + detail[:800]
                if model == 'qa-model' and 'qa-automation-input.txt' in json.dumps(request.get('messages', [])) and request.get('tools'):
                    results = [m.get('content', '') for m in request.get('messages', []) if m.get('role') == 'tool']
                    if not results:
                        tools = [t['function'] for t in request['tools']
                                 if re.search(r'(?:^|[._])(?:read|read_file)$', t['function']['name'])
                                 and {'file_path', 'path'} & t['function'].get('parameters', {}).get('properties', {}).keys()]
                        if not tools:
                            self.reply(400, {'error': {'message': 'QA file-read tool missing; observed: ' + ', '.join(t['function']['name'] for t in request['tools'])}})
                            return
                        # terminal_read also ends in "read", but cannot read a project file.
                        tool = min(tools, key=lambda t: (t['name'] not in ('read', 'read_file'), t['name']))
                        fields = tool.get('parameters', {}).get('properties', {})
                        args = {'file_path' if 'file_path' in fields else 'path': 'qa-automation-input.txt'}
                        call = {'index': 0, 'id': 'qa_project_read', 'type': 'function',
                                'function': {'name': tool['name'], 'arguments': json.dumps(args)}}
                        events = [dict(base, choices=[{'index': 0, 'delta': {'role': 'assistant', 'tool_calls': [call]}, 'finish_reason': None}]),
                                  dict(base, choices=[{'index': 0, 'delta': {}, 'finish_reason': 'tool_calls'}], usage=usage)]
                        methods.append('llm/project-read-requested')
                    else:
                        detail = ' '.join(v if isinstance(v, str) else json.dumps(v) for v in results)
                        found = 'DSH_QA_WORKSPACE_MARKER' in detail
                        methods.append('llm/project-read-confirmed' if found else 'llm/project-read-missing')
                        events[0]['choices'][0]['delta']['content'] = 'Workspace marker: DSH_QA_WORKSPACE_MARKER' if found else 'Workspace marker missing: ' + detail[:200]
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
        recovery_tail.set()
        recovered.set()
        http.shutdown()
        http.server_close()
        worker.join(timeout=5)
