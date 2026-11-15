"""Layer 2 sandbox helper.

Capturing stdout/stderr and scoping the working directory happens here rather than in Kotlin so a
single JNI hop covers a whole execution.

The deadline is enforced with sys.settrace, which only fires on Python line events. A single
long-running C call (a huge numpy reduction, a blocking socket read) therefore cannot be
interrupted from here; Kotlin applies its own outer timeout and abandons the thread in that case.
"""
import contextlib
import io
import json
import os
import sys
import time
import traceback


class SandboxTimeout(Exception):
    pass


def _deadline_tracer(deadline):
    def tracer(frame, event, arg):
        if time.monotonic() > deadline:
            raise SandboxTimeout("script exceeded its time budget")
        return tracer

    return tracer


def run(code, workspace, timeout_ms=10000):
    os.makedirs(workspace, exist_ok=True)
    prev_cwd = os.getcwd()
    out, err = io.StringIO(), io.StringIO()
    ok = True
    timed_out = False
    started = time.monotonic()
    try:
        os.chdir(workspace)
        # Fresh namespace per run: durable state belongs in the memory vault, not in leftover globals.
        namespace = {"__name__": "__eae__", "WORKSPACE": workspace}
        compiled = compile(code, "<agent>", "exec")
        sys.settrace(_deadline_tracer(started + (timeout_ms / 1000.0)))
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                exec(compiled, namespace)
        finally:
            sys.settrace(None)
    except SandboxTimeout as exc:
        ok = False
        timed_out = True
        err.write(str(exc))
    except SyntaxError:
        ok = False
        err.write(traceback.format_exc(limit=1))
    except BaseException:
        ok = False
        err.write(traceback.format_exc())
    finally:
        with contextlib.suppress(OSError):
            os.chdir(prev_cwd)
    return json.dumps(
        {
            "ok": ok,
            "timed_out": timed_out,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
            "stdout": out.getvalue(),
            "stderr": err.getvalue(),
        }
    )


def listdir(workspace):
    if not os.path.isdir(workspace):
        return json.dumps([])
    entries = []
    for name in sorted(os.listdir(workspace)):
        path = os.path.join(workspace, name)
        entries.append(
            {"name": name, "bytes": os.path.getsize(path) if os.path.isfile(path) else None}
        )
    return json.dumps(entries)


def read_text(workspace, name, max_chars=4000):
    path = os.path.join(workspace, name)
    if not os.path.isfile(path):
        return json.dumps({"ok": False, "error": "no such file: %s" % name})
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        data = handle.read(max_chars + 1)
    return json.dumps(
        {"ok": True, "truncated": len(data) > max_chars, "text": data[:max_chars]}
    )
