#!/usr/bin/env python3
"""Regenerate docs/TOOLS.md from a running Lensbridge (GET /api/ lists every tool with its schema).
Usage: python3 tools/gen_tool_reference.py <token> [port]"""
import json, sys, urllib.request
token = sys.argv[1]; port = sys.argv[2] if len(sys.argv) > 2 else '25599'
req = urllib.request.Request(f'http://127.0.0.1:{port}/api/', headers={'Authorization': 'Bearer ' + token})
tools = json.load(urllib.request.urlopen(req))
GROUPS = [('Observe', ['status', 'screenshot', 'inspect', 'chat_log', 'wait', 'find_entities', 'list_captures']),
          ('Act (player input)', ['command', 'chat', 'look', 'hold_keys', 'press', 'hotbar', 'close_screen', 'release_inputs']),
          ('Stage & look', ['scene', 'hud', 'window_size']),
          ('Director camera', ['camera_set', 'camera_look_at', 'camera_preview', 'camera_release', 'exit_camera']),
          ('Cinematic moves', ['camera_lock', 'camera_orbit', 'camera_pan', 'camera_rail', 'camera_follow', 'camera_dolly_zoom']),
          ('Drone', ['drone_fly', 'drone_control', 'drone_status']),
          ('Tripods & Camera Manager', ['tripod_place', 'tripod_ring', 'tripod_list', 'tripod_all', 'tripod_update', 'tripod_nudge',
                                        'tripod_set_from_view', 'tripod_thumbs', 'tripod_preview', 'tripod_edit', 'tripod_edit_finish',
                                        'tripod_view', 'tripod_remove']),
          ('Capture', ['screenshot', 'record', 'capture_orbit', 'capture_path', 'capture_program', 'tripod_shots', 'multicam']),
          ('Paths', ['path_mark', 'path_save', 'path_get', 'path_list', 'path_delete', 'path_show'])]
by = {t['name']: t for t in tools}
out = ['# Lensbridge tool reference', '',
       f'*Generated from a running Lensbridge by `tools/gen_tool_reference.py` ({len(tools)} tools). Every tool is available over MCP '
       '(`tools/call`) and REST (`POST /api/<tool>` with the arguments as JSON).*', '']
seen = set()
for title, names in GROUPS:
    out += [f'## {title}', '']
    for n in names:
        if n not in by or (n in seen and title != 'Capture'):
            continue
        seen.add(n)
        t = by[n]
        out += [f'### `{n}`', '', t['description'], '']
        props = t['inputSchema'].get('properties', {})
        req = set(t['inputSchema'].get('required', []))
        if props:
            out += ['| Argument | Type | Required | Notes |', '|---|---|---|---|']
            for k, v in props.items():
                out.append(f"| `{k}` | {v.get('type', '')} | {'yes' if k in req else ''} | {v.get('description', '').replace('|', '/')} |")
            out.append('')
rest = [n for n in by if n not in seen]
if rest:
    out += ['## Other', '']
    for n in rest:
        out += [f'### `{n}`', '', by[n]['description'], '']
open('docs/TOOLS.md', 'w').write('\n'.join(out) + '\n')
print('docs/TOOLS.md:', len(tools), 'tools')
