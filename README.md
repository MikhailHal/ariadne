<p align="center">
  <img src="doc/ariadne-banner.svg" alt="ariadne banner" width="100%">
</p>

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

**ariadne** is an MCP (Model Context Protocol) server that provides AI agents with the ability to identify affected tests. Powered by [sazanami](https://github.com/MikhailHal/sazanami), it analyzes code changes and returns only the tests that need to be run.

> [!IMPORTANT]
> ## ⚠️ MUST READ: Speed over Completeness
>
> ariadne is built for the agent inner loop — edit, verify, commit — where fast
> feedback matters more than exhaustive selection. Static analysis cannot trace
> every execution path: reflection, DI frameworks, and data-flow indirection
> (e.g., Flux/MVI dispatch) can hide dependencies from **any**
> affected-test-selection tool, not just ariadne.
>
> **Always keep a final line of defense in CI.** Run the full test suite (or a
> conservative selection) before merging. ariadne narrows what an agent runs
> while iterating; it is not a replacement for CI.
>
> When ariadne detects changes it cannot analyze (build scripts, resources,
> unscanned source sets), it says so explicitly in the tool response instead of
> silently reporting "no affected tests".

## Features

- **MCP Integration** — Works with Claude Code, Claude Desktop, and other MCP-compatible clients
- **Automatic Git Diff** — No need to pass diff manually; ariadne runs `git diff` internally
- **Powered by sazanami** — Uses Kotlin Analysis API for accurate static analysis

## Installation

### Homebrew (recommended)

```bash
brew install mikhailhal/tap/ariadne
```

Then register it with your MCP client — for Claude Code:

```bash
claude mcp add ariadne -- ariadne
```

Or add to your MCP client configuration manually (e.g., Claude Desktop):

```json
{
  "mcpServers": {
    "ariadne": {
      "command": "ariadne"
    }
  }
}
```

### Manual (release JAR)

Download `ariadne-<version>-all.jar` from [Releases](https://github.com/MikhailHal/ariadne/releases) (requires JDK 21+) and configure your client with `"command": "java", "args": ["-jar", "/path/to/ariadne-<version>-all.jar"]`.

### Build from Source

```bash
git clone --recursive https://github.com/MikhailHal/ariadne.git
cd ariadne
./gradlew shadowJar   # fat JAR: build/libs/ariadne-<version>-all.jar
```

## Usage

Once configured, AI agents can use the `get_affected_tests` tool:

### Tool: `get_affected_tests`

**Parameters:**
- `project_path` (required) — Path to the Kotlin project
- `base_branch` (optional) — Branch to compare against (default: `origin/main`)

**Returns:**
- List of affected test FQNs (fully qualified names), sorted
- If the diff contains changes outside the analyzed Kotlin sources (build scripts,
  resources, unscanned source sets), a note is appended recommending a full test run
  for those changes
- Analysis is bounded by a 120s timeout; on timeout an explicit error is returned

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
