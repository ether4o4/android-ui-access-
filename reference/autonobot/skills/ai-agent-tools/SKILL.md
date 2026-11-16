# AI Agent Tools

Agent utilities, tool management, execution optimization, self-improvement.

## Capabilities
- Tool chain optimization (minimize steps)
- Context window management for small models
- Prompt construction and refinement
- JSON output validation and repair
- Execution plan generation
- Error recovery strategies
- Memory management (conversation history)
- Multi-step task decomposition
- Tool output parsing and formatting
- Self-diagnostic and health checks

## Approach
1. Analyze available tools for the task
2. Build minimal execution plan
3. Validate outputs at each step
4. Recover from errors automatically
5. Report results clearly

## Meta-Tools
- **plan_task** — Break complex request into tool calls
- **validate_json** — Fix malformed JSON from LLM output
- **summarize_context** — Compress conversation for token efficiency
- **health_check** — Verify all tools are operational

## Tools
- execute_shell — run diagnostics, process management
- read_file / write_file — manage agent state
- http_get — check service health
