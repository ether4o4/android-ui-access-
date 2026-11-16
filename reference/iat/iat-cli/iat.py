#!/usr/bin/env python3
"""IAT Local Agent CLI.

A dependency-free local agent runtime for Ollama-compatible servers.
It is intentionally small: model -> JSON action -> tool -> observation -> model.
"""
from __future__ import annotations
import argparse, json, os, subprocess, sys
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

IAT_CLI_VERSION = "0.1.1"

SYSTEM = '''You are IAT, a local coding agent. Work step-by-step and use tools when useful.
Return ONLY one JSON object: {"thought":"brief reason","action":"tool_name|final_response","args":{...}}.
Available tools:
- list_files: recursively list workspace files. args {"path":"."}
- read_file: read UTF-8 text. args {"path":"relative/path"}
- write_file: create/replace text. args {"path":"relative/path","content":"..."}
- shell: run a command in workspace. args {"command":"..."}
- git: safe git inspection: status, diff, log, branch, show. args {"command":"status"}
- final_response: finish. args {"response":"answer"}
Prefer inspection before editing. Do not invent tool results.'''

def api_call(base_url, model, messages):
    body = json.dumps({"model": model, "messages": messages, "stream": False,
                       "temperature": 0.2, "response_format": {"type": "json_object"}}).encode()
    req = Request(base_url.rstrip('/') + '/chat/completions', data=body,
                  headers={"Content-Type":"application/json","Accept":"application/json"}, method='POST')
    try:
        with urlopen(req, timeout=180) as response: data = json.loads(response.read().decode())
    except (HTTPError, URLError, TimeoutError) as exc:
        raise RuntimeError(f'model server request failed: {exc}') from exc
    if data.get('error'): raise RuntimeError(str(data['error']))
    try: return data['choices'][0]['message']['content']
    except (KeyError, IndexError, TypeError) as exc: raise RuntimeError(f'unexpected model response: {data}') from exc

def safe_path(root, value):
    target = (root / value).resolve()
    if target != root and root not in target.parents: raise RuntimeError('path escapes the workspace')
    return target

def run_tool(root, action, args, unsafe):
    if action == 'list_files':
        base = safe_path(root, args.get('path', '.'))
        files = [str(p.relative_to(root)) for p in base.rglob('*') if p.is_file()]
        return '\n'.join(files[:500]) or '(no files)'
    if action == 'read_file':
        path = safe_path(root, args['path'])
        if not path.is_file(): return f"error: file not found: {args['path']}"
        data = path.read_text(encoding='utf-8', errors='replace')
        return data[:12000] + ('\n...[truncated]' if len(data) > 12000 else '')
    if action == 'write_file':
        if not unsafe: return 'error: write_file requires --unsafe'
        path = safe_path(root, args['path']); path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(args.get('content',''), encoding='utf-8')
        return f'wrote {path.relative_to(root)} ({path.stat().st_size} bytes)'
    if action == 'shell':
        if not unsafe: return 'error: shell requires --unsafe'
        command = args.get('command','').strip()
        if not command: return 'error: empty command'
        proc = subprocess.run(command, cwd=root, shell=True, capture_output=True, text=True, timeout=120)
        out = (proc.stdout + ('\nSTDERR:\n' + proc.stderr if proc.stderr else '')).strip()
        return f'exit={proc.returncode}\n{out[:12000]}'
    if action == 'git':
        command = args.get('command','status').strip() or 'status'
        verb = command.split()[0]
        if verb not in {'status','diff','log','branch','show'}: return f"error: git subcommand '{verb}' is not allowed"
        proc = subprocess.run(['git', *command.split()], cwd=root, capture_output=True, text=True, timeout=60)
        return f'exit={proc.returncode}\n{(proc.stdout + proc.stderr)[:12000]}'
    return f'error: unknown action {action}'

def parse_action(raw):
    try: obj = json.loads(raw)
    except json.JSONDecodeError:
        start, end = raw.find('{'), raw.rfind('}')
        if start < 0 or end <= start: raise RuntimeError('model did not return JSON')
        obj = json.loads(raw[start:end+1])
    if not isinstance(obj, dict) or not obj.get('action'): raise RuntimeError('invalid action JSON')
    obj.setdefault('args', {}); return obj

def run_agent(root, model, base_url, task, max_steps, unsafe):
    messages = [{'role':'system','content':SYSTEM},{'role':'user','content':task}]
    for step in range(1, max_steps + 1):
        raw = api_call(base_url, model, messages)
        try: action = parse_action(raw)
        except RuntimeError as exc:
            messages += [{'role':'assistant','content':raw},{'role':'user','content':f'Invalid output: {exc}. Return only JSON.'}]
            continue
        name, args = action['action'], action.get('args') or {}
        if action.get('thought'): print(f"\n[{step}] {action['thought']}")
        if name == 'final_response': return str(args.get('response',''))
        print(f"  -> {name} {json.dumps(args, ensure_ascii=False)[:500]}")
        try: result = run_tool(root, name, args, unsafe)
        except Exception as exc: result = f'tool error: {type(exc).__name__}: {exc}'
        print(f"  <- {result[:700].replace(chr(10), ' ')}")
        messages += [{'role':'assistant','content':raw},{'role':'user','content':'TOOL RESULT:\n'+result}]
    return f'Step budget of {max_steps} exhausted without a final response.'

def main():
    p = argparse.ArgumentParser(description='IAT local LLM agent CLI')
    p.add_argument('task', nargs='?'); p.add_argument('--model', default=os.getenv('IAT_MODEL','qwen2.5:3b'))
    p.add_argument('--base-url', default=os.getenv('IAT_BASE_URL','http://127.0.0.1:11434/v1'))
    p.add_argument('--workspace', default='.'); p.add_argument('--max-steps', type=int, default=12)
    p.add_argument('--unsafe', action='store_true'); p.add_argument('--interactive', action='store_true')
    a = p.parse_args(); root = Path(a.workspace).resolve(); root.mkdir(parents=True, exist_ok=True)
    if a.interactive or not a.task:
        print(f'IAT local agent v{IAT_CLI_VERSION} | model={a.model} | workspace={root}')
        print('Type /exit to quit. --unsafe enables writes and shell commands.')
        while True:
            try: task = input('\niat> ').strip()
            except (EOFError, KeyboardInterrupt): print(); return 0
            if task in {'/exit','/quit'}: return 0
            if not task: continue
            try: print('\n' + run_agent(root,a.model,a.base_url,task,a.max_steps,a.unsafe))
            except Exception as exc: print(f'ERROR: {exc}', file=sys.stderr)
    try: print(run_agent(root,a.model,a.base_url,task,a.max_steps,a.unsafe)); return 0
    except Exception as exc: print(f'ERROR: {exc}', file=sys.stderr); return 1

if __name__ == '__main__': raise SystemExit(main())
