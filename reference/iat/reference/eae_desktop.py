"""Desktop EAE reference implementation.

This is the non-Android counterpart to the Kotlin app in `app/`. It is kept in the repo because it
documents the intended behaviour of Layers 2, 3 and 5 in a form you can run and read in one sitting:

    python3 reference/eae_desktop.py

Mapping to the Android build:

    DynamicToolRegistry   -> core/tools/ToolRegistry.kt      (SQLite -> Room)
    LocalSandbox          -> core/sandbox/PythonSandbox.kt   (subprocess -> Chaquopy)
    ContextPruner         -> core/orchestrator/ContextPruner.kt
    LocalAgentOrchestrator-> core/orchestrator/AgentEngine.kt
    mock_llm_inference    -> core/inference/{RemoteEngine,MediaPipeEngine}.kt

The inference call here is a deterministic stub so the loop can be exercised without a model. The
Android app does not stub it: it calls a real OpenAI-compatible endpoint or an on-device MediaPipe
model.
"""
import json
import sqlite3
import subprocess
import sys
from typing import Any, Dict, List


class DynamicToolRegistry:
    """Layer 3. The catalogue lives in SQLite; only hydrated schemas reach the prompt."""

    def __init__(self, db_path: str = ":memory:") -> None:
        self.conn = sqlite3.connect(db_path)
        self._init_db()
        self.active_schemas: Dict[str, Dict[str, Any]] = {}
        self._bootstrap_default_tools()

    def _init_db(self) -> None:
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS tool_registry (
                name TEXT PRIMARY KEY,
                description TEXT,
                schema_json TEXT
            )
            """
        )
        self.conn.commit()

    def register_tool(self, name: str, description: str, schema: Dict[str, Any]) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO tool_registry VALUES (?, ?, ?)",
            (name, description, json.dumps(schema)),
        )
        self.conn.commit()

    def _bootstrap_default_tools(self) -> None:
        self.register_tool(
            name="search_tools",
            description="Searches for available dynamic tools by keyword.",
            schema={
                "name": "search_tools",
                "description": "Find tools needed for specific sub-tasks.",
                "parameters": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            },
        )
        self.register_tool(
            name="execute_python",
            description="Executes python code safely within the sandbox.",
            schema={
                "name": "execute_python",
                "description": "Run Python 3 scripts to process data, files, or calculations.",
                "parameters": {
                    "type": "object",
                    "properties": {"code": {"type": "string"}},
                    "required": ["code"],
                },
            },
        )
        # The whole point of Layer 3: start with search_tools only.
        self.hydrate_tool("search_tools")

    def search_tools(self, query: str) -> List[str]:
        rows = self.conn.execute(
            "SELECT name, description FROM tool_registry "
            "WHERE name LIKE ? OR description LIKE ?",
            (f"%{query}%", f"%{query}%"),
        ).fetchall()
        for name, _ in rows:
            self.hydrate_tool(name)
        return [f"{name}: {desc}" for name, desc in rows]

    def hydrate_tool(self, name: str) -> None:
        row = self.conn.execute(
            "SELECT schema_json FROM tool_registry WHERE name = ?", (name,)
        ).fetchone()
        if row:
            self.active_schemas[name] = json.loads(row[0])

    def get_active_system_prompt_schemas(self) -> str:
        return json.dumps(list(self.active_schemas.values()), indent=2)


class LocalSandbox:
    """Layer 2. A subprocess stands in for the container on desktop."""

    def execute_python(self, code: str, timeout: int = 10) -> Dict[str, Any]:
        try:
            result = subprocess.run(
                [sys.executable, "-c", code],
                capture_output=True,
                text=True,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            return {"status": "error", "output": f"Execution timed out after {timeout} seconds."}
        except OSError as exc:
            return {"status": "error", "output": f"Could not start interpreter: {exc}"}

        if result.returncode == 0:
            return {
                "status": "success",
                "output": result.stdout.strip() or "Executed successfully with no stdout.",
            }
        return {"status": "error", "output": result.stderr.strip() or "Non-zero exit, no stderr."}


class ContextPruner:
    """Layer 5. Removes what is already durable elsewhere."""

    @staticmethod
    def truncate_tool_output(output: str, max_chars: int = 500) -> str:
        if len(output) <= max_chars:
            return output
        dropped = len(output) - max_chars
        return output[:max_chars] + f"\n...[TRUNCATED {dropped} CHARS - full output saved to file]..."

    @staticmethod
    def prune_history(messages: List[Dict[str, str]]) -> List[Dict[str, str]]:
        """Replaces a failed tool response with a note once a later call succeeded.

        Only the failed observation is collapsed. The assistant turn that produced the fix is kept,
        because dropping it loses the reasoning that made the next step work.
        """
        succeeded_later = [
            any(
                later["role"] == "tool_response" and "error" not in later["content"].lower()
                for later in messages[i + 1 :]
            )
            for i in range(len(messages))
        ]
        pruned: List[Dict[str, str]] = []
        for i, msg in enumerate(messages):
            is_failure = msg["role"] == "tool_response" and "error" in msg["content"].lower()
            if is_failure and succeeded_later[i]:
                pruned.append(
                    {
                        "role": "system_note",
                        "content": "[System Note: an earlier tool error was resolved; trace pruned.]",
                    }
                )
                continue
            pruned.append(msg)
        return pruned


class LocalAgentOrchestrator:
    def __init__(self) -> None:
        self.registry = DynamicToolRegistry()
        self.sandbox = LocalSandbox()
        self.pruner = ContextPruner()
        self.messages: List[Dict[str, str]] = []

    def build_system_prompt(self) -> str:
        return (
            "You are a minimal reasoning engine operating within an External Agentic Environment.\n"
            "Respond ONLY with valid JSON using this format:\n"
            "{\n"
            '  "thought": "short explanation",\n'
            '  "action": "tool_name OR final_response",\n'
            '  "args": { ... }\n'
            "}\n\n"
            "AVAILABLE ACTIVE TOOLS:\n"
            f"{self.registry.get_active_system_prompt_schemas()}\n"
        )

    def mock_llm_inference(self, prompt: str) -> str:
        """Deterministic stand-in for a local model. Replace with an HTTP call to Ollama/vLLM."""
        if "execute_python" not in prompt:
            return json.dumps(
                {
                    "thought": "I need a tool that can run Python to analyse CSV data.",
                    "action": "search_tools",
                    "args": {"query": "python"},
                }
            )
        if "Total Revenue" not in prompt:
            return json.dumps(
                {
                    "thought": "execute_python is available; compute revenue by region.",
                    "action": "execute_python",
                    "args": {
                        "code": (
                            "data = [('East', 100), ('West', 200), ('East', 150)]\n"
                            "revenue = {}\n"
                            "for region, val in data:\n"
                            "    revenue[region] = revenue.get(region, 0) + val\n"
                            "print(f'Total Revenue: {revenue}')"
                        )
                    },
                }
            )
        return json.dumps(
            {
                "thought": "The script produced the totals, so the task is done.",
                "action": "final_response",
                "args": {"response": "Total revenue by region: East 250, West 200."},
            }
        )

    def run_task(self, user_query: str, max_steps: int = 5) -> None:
        print(f"\n[USER QUERY]: {user_query}")
        self.messages.append({"role": "user", "content": user_query})

        for step in range(1, max_steps + 1):
            print(f"\n--- STEP {step} ---")
            full_prompt = self.build_system_prompt() + "\n" + json.dumps(self.messages)

            raw = self.mock_llm_inference(full_prompt)
            parsed = json.loads(raw)
            action = parsed["action"]
            args = parsed.get("args", {})

            print(f"[MODEL THOUGHT]: {parsed['thought']}")
            print(f"[ACTION]: {action}({args})")

            if action == "final_response":
                print(f"\n[FINAL OUTPUT]: {args.get('response')}")
                return

            if action == "search_tools":
                tool_output = f"Discovered Tools: {self.registry.search_tools(args.get('query', ''))}"
            elif action == "execute_python":
                tool_output = self.sandbox.execute_python(args.get("code", ""))["output"]
            else:
                tool_output = f"Error: Tool '{action}' is not recognized."

            truncated = self.pruner.truncate_tool_output(tool_output)
            print(f"[TOOL RESP]: {truncated}")

            self.messages.append({"role": "assistant", "content": raw})
            self.messages.append({"role": "tool_response", "content": truncated})
            self.messages = self.pruner.prune_history(self.messages)

        print(f"\n[HALTED]: step budget of {max_steps} exhausted without a final answer.")


if __name__ == "__main__":
    LocalAgentOrchestrator().run_task(
        "Analyze sales_data.csv on my desktop and calculate total revenue by region."
    )
