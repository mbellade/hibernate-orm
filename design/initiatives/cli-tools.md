# Hibernate CLI Tools — Draft Proposal

## Motivation

With AI agents becoming mainstream, CLI tools with structured (JSON) output are the natural
interface for agent-to-tool communication. Hibernate already has deep knowledge of mappings,
schemas, and queries internally — the goal is to expose this knowledge via a CLI that both
humans and AI agents can use.

## Distribution: JBang Catalog

JBang allows running single-file Java programs with automatic dependency resolution.
A JBang catalog (`jbang-catalog.json`) lets users install and run tools with:

```bash
jbang catalog add hibernate https://github.com/hibernate/hibernate-tools-cli/blob/main/jbang-catalog.json
jbang hibernate@hibernate schema inspect --project-dir ./my-app
```

### Why JBang?

- Zero install beyond JBang itself
- No build step — scripts declare their own dependencies via `//DEPS`
- Catalog mechanism provides a clean namespace (`hibernate@hibernate`)
- Scripts are easy to maintain and version
- JBang caching mitigates JVM startup cost

## Core Architecture

### The Key Insight

Hibernate's `Metadata` object (from `MetadataSources.buildMetadata()`) contains entity mappings,
type info, table structures, and relationship graphs — **without needing a database connection**.

It can be built from:
- Annotated `.class` files on the classpath (e.g. `target/classes`)
- A `persistence.xml`
- Programmatic configuration

This means most useful operations work **offline** — no running app or database required.

### Two Modes

1. **CLI mode** — run a command, get JSON output, exit
2. **MCP server mode** (`--mcp`) — start an MCP (Model Context Protocol) server over stdio,
   exposing the same commands as tools that any AI agent can call directly

## Proposed Commands / Tools

| Command | Description | Needs DB? |
|---|---|---|
| `schema inspect` | List entities, fields, types, relationships as structured JSON | No |
| `schema ddl` | Generate DDL (CREATE TABLE statements) for current mappings | No |
| `schema diff` | Compare entity mappings vs live database schema | Yes |
| `hql validate <query>` | Parse and validate HQL/JPQL, report errors | No |
| `hql sql <query>` | Show the SQL that Hibernate would generate for an HQL query | No |
| `mapping check` | Validate annotations, warn about common issues | No |

## Example Usage

### CLI (human or agent)
```bash
# Inspect entities in a project
jbang hibernate@hibernate schema inspect --project-dir ./my-app --output json

# Validate an HQL query
jbang hibernate@hibernate hql validate "from Book b where b.author.name = :name"

# Generate DDL
jbang hibernate@hibernate schema ddl --project-dir ./my-app --dialect postgresql
```

### MCP Server (AI agent integration)
```bash
# Start as MCP server — agents connect via stdio
jbang hibernate@hibernate --mcp --project-dir ./my-app
```

Agent configuration (e.g. in Claude Code's `mcp_servers`):
```json
{
  "hibernate": {
    "command": "jbang",
    "args": ["hibernate@hibernate", "--mcp", "--project-dir", "./my-app"]
  }
}
```

## Technology Choices

- **JBang** — distribution and execution
- **Picocli** — CLI argument parsing (`//DEPS info.picocli:picocli`)
- **Hibernate ORM** — `MetadataSources` / `Metadata` for offline analysis
- **MCP SDK** — for MCP server mode (TBD: Java MCP SDK or custom stdio implementation)
- **Jackson** — JSON output formatting

## Relationship to Quarkus Integration

The Quarkus Hibernate extension already exposes some tools as MCP servers in dev mode.
This standalone CLI complements that by:
- Working outside Quarkus (plain Hibernate, Spring, etc.)
- Working without a running application
- Providing a CLI interface in addition to MCP

Phase 2 could align the tool interfaces so Quarkus's live `SessionFactory`-backed tools
and this offline CLI share the same tool definitions, adding runtime-only tools like:
- `query explain` (actual DB query plan)
- `stats` (SessionFactory statistics)
- `cache inspect` (second-level cache contents)

## Open Questions

- Should scripts live in this repo or a separate `hibernate-tools-cli` repo?
- What's the minimum useful subset for an initial release?
- Should we support Gradle project scanning in addition to Maven?
- How to handle multi-module projects (entity classes spread across modules)?
- JBang catalog hosting — GitHub raw vs dedicated URL?
