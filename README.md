<p align="center">
  <h1 align="center">ariadne</h1>
  <p align="center">
    <strong>MCP Server for Affected Test Selection</strong>
  </p>
  <p align="center">
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square" alt="License"></a>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.3.0-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"></a>
  </p>
</p>

<br>

**ariadne** is an MCP (Model Context Protocol) server that provides AI agents with the ability to identify affected tests. Powered by [sazanami](https://github.com/anthropics/sazanami), it analyzes code changes and returns only the tests that need to be run.

## Features

- **MCP Integration** — Works with Claude Code, Claude Desktop, and other MCP-compatible clients
- **Automatic Git Diff** — No need to pass diff manually; ariadne runs `git diff` internally
- **Powered by sazanami** — Uses Kotlin Analysis API for accurate static analysis

## Installation

### Build from Source

```bash
git clone --recursive https://github.com/anthropics/ariadne.git
cd ariadne
./gradlew installDist
```

### Configure MCP Client

Add to your MCP client configuration (e.g., Claude Desktop):

```json
{
  "mcpServers": {
    "ariadne": {
      "command": "/path/to/ariadne/build/install/ariadne/bin/ariadne"
    }
  }
}
```

## Usage

Once configured, AI agents can use the `get_affected_tests` tool:

### Tool: `get_affected_tests`

**Parameters:**
- `project_path` (required) — Path to the Kotlin project
- `base_branch` (optional) — Branch to compare against (default: `origin/main`)

**Returns:**
- List of affected test FQNs (fully qualified names)

### Example

Agent request:
```json
{
  "name": "get_affected_tests",
  "arguments": {
    "project_path": "/path/to/your/kotlin/project"
  }
}
```

Response:
```
com.example.UserServiceTest.testCreateUser
com.example.UserRepositoryTest.testSave
```

## How It Works

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  MCP Client │ ──▶ │   ariadne   │ ──▶ │  sazanami   │
│  (Agent)    │     │ (MCP Server)│     │  (Analysis) │
└─────────────┘     └─────────────┘     └─────────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │  git diff   │
                    └─────────────┘
```

1. **Agent calls tool** — Passes project path to ariadne
2. **Run git diff** — ariadne executes `git diff --unified=0` against base branch
3. **Analyze with sazanami** — Build call graph and find affected tests
4. **Return results** — List of test FQNs returned to agent

## Requirements

- **JDK 21** or later
- **Git** — For diff detection

## Limitations (v0.1)

- Module discovery is convention-based: it parses `settings.gradle(.kts)` includes,
  standard `src/{main,test}/{kotlin,java}` layouts, and `project(":x")` /
  type-safe accessor dependencies from build files. Dynamic includes, custom
  source sets (Android variants, KMP), and dependencies injected by convention
  plugins are not detected — see [#1](https://github.com/MikhailHal/ariadne/issues/1)
- Full graph rebuild on each request (no caching yet)

## License

```
Copyright 2025 ariadne contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

See [LICENSE](LICENSE) for the full text.
