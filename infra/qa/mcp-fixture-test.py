#!/usr/bin/env python3
"""Exercise the model wire contract before paying for a native scheduled run."""
import http.client
import json
from mcp_fixture import server


with server() as (port, methods):
    tools = [{'type': 'function', 'function': {
        'name': name, 'parameters': {'type': 'object', 'properties': fields}}}
        for name, fields in [('terminal_read', {'terminal_id': {'type': 'string'}}),
                             ('read', {'file_path': {'type': 'string'}}),
                             ('read_file', {'path': {'type': 'string'}})]]

    def send(messages):
        peer = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        try:
            peer.request('POST', '/v1/chat/completions', json.dumps({
                'model': 'qa-model', 'stream': True, 'messages': messages, 'tools': tools}),
                {'Content-Type': 'application/json', 'Authorization': 'Bearer qa-runtime-synthetic-secret'})
            response = peer.getresponse()
            body = response.read().decode()
            assert response.status == 200, (response.status, body)
            return [json.loads(line[6:]) for line in body.splitlines()
                    if line.startswith('data: ') and line[6:] != '[DONE]']
        finally:
            peer.close()

    messages = [{'role': 'user', 'content': 'Read qa-automation-input.txt and report the marker'}]
    call = send(messages)[0]['choices'][0]['delta']['tool_calls'][0]['function']
    assert call['name'] == 'read'
    assert json.loads(call['arguments']) == {'file_path': 'qa-automation-input.txt'}
    result = send(messages + [{'role': 'tool', 'tool_call_id': 'qa_project_read', 'content': 'DSH_QA_WORKSPACE_MARKER'}])
    assert 'DSH_QA_WORKSPACE_MARKER' in result[0]['choices'][0]['delta']['content']
    assert 'llm/project-read-confirmed' in methods
    print('Mixed tool catalog -> file read -> confirmed project marker: PASS')
