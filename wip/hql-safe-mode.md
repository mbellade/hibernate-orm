# HQL Safe Mode — Draft Design

## Motivation

By default, HQL provides several "escape hatches" that allow queries to reach beyond
the domain model and execute arbitrary SQL constructs against the database:

- **`function('name', ...)`** — calls *any* database function by name, even if not
  registered in the `SqmFunctionRegistry`. The function name is passed through to SQL
  verbatim, with no validation until execution time.

- **`column(entity.col_name)`** — accesses *any* database column by name, completely
  bypassing entity attribute mapping. This includes system/hidden columns like
  PostgreSQL's `ctid`, `xmin`, etc.

- **Unregistered generic function calls** — when a `genericFunction` name is not found
  in the function registry, Hibernate silently creates a pass-through
  `NamedSqmFunctionDescriptor` that renders the function name directly into SQL
  (`SemanticQueryBuilder.java:4602-4615`).

In multi-tenant applications, applications that accept user-provided HQL/JPQL, or
environments where queries are constructed from partially untrusted input, these
escape hatches may be undesirable. A "safe mode" would restrict HQL to operate
strictly within the boundaries of the mapped domain model and registered functions.


## Goals

When safe mode is enabled, HQL should guarantee that:

1. **Only mapped entity attributes can be accessed** — no arbitrary column references.
2. **Only registered functions can be called** — no pass-through of unknown identifiers
   as SQL function names.
3. **No raw SQL escape hatches** — `function()`, `column()`, and any similar constructs
   that allow injecting arbitrary SQL fragments are rejected at parse time.


## Comprehensive audit of escape hatches

### Escape hatches to block

#### 1. `function('name', args)` — JPA non-standard function call

**Grammar rule:** `jpaNonstandardFunction` in `HqlParser.g4:1121-1122`

**Parser visitor:** `SemanticQueryBuilder.visitJpaNonstandardFunction()` (line 4491)

**Problem:** Accepts any function name (even as a string literal). If not found in
the registry, creates an unvalidated `NamedSqmFunctionDescriptor` that passes through
to SQL. Even when the function *is* registered, the `FUNCTION()` syntax itself is
designed as a raw escape hatch — the function name can be an arbitrary string literal.

**Action in safe mode:** Throw `SemanticException` in `visitJpaNonstandardFunction()`.

#### 2. `column(entity.col_name)` — Direct column access

**Grammar rule:** `columnFunction` in `HqlParser.g4:1133-1134`

**Parser visitor:** `SemanticQueryBuilder.visitColumnFunction()` (line 4519)

**Problem:** Accepts any identifier as a column name, rendering it directly via
`SqlColumn`. Completely bypasses entity attribute resolution. Can access system
columns (e.g., PostgreSQL's `ctid`, `xmin`), unmapped columns, or columns from other
tables if the SQL allows it.

**Action in safe mode:** Throw `SemanticException` in `visitColumnFunction()`.

#### 3. Unregistered generic functions (pass-through)

**Parser visitor:** `SemanticQueryBuilder.getFunctionTemplate()` (line 4599)

**Problem:** When `findFunctionDescriptor()` returns null, a pass-through descriptor
is silently created (lines 4602-4615). This means *any* identifier followed by `()`
becomes a raw SQL function call — `my_secret_func(x)` in HQL becomes
`my_secret_func(x)` in SQL without any validation.

**Action in safe mode:** When `functionTemplate == null`, throw `SemanticException`
instead of creating a pass-through descriptor.


### Constructs already validated (no changes needed)

| Construct | Validation | Location |
|-----------|-----------|----------|
| `FROM Entity` | Entity name resolved against JPA metamodel; throws `UnknownEntityException` if unmapped | `visitRootEntity()` line 1996, 2073 |
| `CROSS JOIN Entity` | Same metamodel validation | `consumeCrossJoin()` line 2141 |
| `JOIN entity.assoc` | Path resolved against mapped attributes | `consumeJoin()` via path resolution |
| `JOIN Entity ON ...` (entity join) | Entity name resolved against metamodel | via `SqmEntityJoin` |
| Path navigation (`e.attr.nested`) | Each segment resolved against entity metamodel | `BasicDotIdentifierConsumer` |
| `treat(e as SubType)` | Target validated as importable mapped entity type | `visitTreatedNavigablePath()` line 5823 |
| `fk(association)` | Validates path is a mapped single-valued association | `visitToOneFkReference()` line 3530 |
| `naturalid(entity)` | Validates against mapped natural ID attributes | line 3500+ |
| `id()`, `version()` | Validates against identifiable domain type | lines 3441-3498 |
| `cast(x as Type)` | Registered standard function, type-checked | registered in dialect |
| `key()`, `value()`, `entry()` | Resolved against mapped plural attribute | lines 5861-5922 |
| `type()` | Requires valid path or parameter | lines 3411-3433 |
| Set-returning functions | Already throw `SemanticException` when unregistered | `visitSimpleSetReturningFunction()` line 4480-4485 |
| Subqueries in FROM / JOIN | Subqueries are still HQL, subject to all the same restrictions | `visitRootSubquery()`, `JoinSubqueryContext` |
| CTEs | CTE body is a normal HQL query, subject to all restrictions | lines 734-835 |


### Gray areas worth discussing

#### JSON/XML path expressions

Functions like `json_value()`, `json_query()`, `json_table()`, `xmlquery()`,
`xmltable()` are *registered* functions, so they pass the "registered functions only"
check. However, they accept arbitrary path expressions (JSON paths, XPath) as string
arguments:

```hql
select json_value(e.data, '$.secret.nested.field') from Entity e
select * from json_table(e.data, '$[*]' columns (x varchar path '$.anything')) as jt
select xmlquery('//sensitive/element', e.xmlData) from Entity e
```

These paths operate *within* a mapped column's data, so they don't access unmapped
tables or columns. But they do allow navigating into arbitrary structures within
JSON/XML columns.

**Recommendation:** Out of scope for the initial safe mode. The data source (`e.data`)
is a mapped attribute; the path is a concern at the application-validation level, not
the HQL-safety level. If needed later, a separate `hibernate.query.restrict_json_paths`
could whitelist allowed path patterns, but this is a different feature.


## Implementation approach

### A. New flag in `SqmCreationOptions`

Add a method to the existing options interface, following the established pattern:

```java
// SqmCreationOptions.java
/**
 * When enabled, restricts HQL to only use mapped entity attributes and
 * registered functions. Disallows function(), column(), and pass-through
 * of unregistered function names.
 *
 * @see org.hibernate.cfg.AvailableSettings#HQL_SAFE_MODE
 */
default boolean isHqlSafeMode() {
    return false;
}
```

Wire through `SqmCreationOptionsStandard` → `QueryEngineOptions` → configuration
property, following the same pattern as `isJsonFunctionsEnabled()`.

### B. Configuration property

```java
// AvailableSettings.java (or a suitable settings interface)

/**
 * When set to true, restricts HQL to the mapped domain model:
 * only mapped attributes and registered functions are allowed.
 *
 * Default: false
 */
String HQL_SAFE_MODE = "hibernate.query.hql_safe_mode";
```

### C. Enforcement points in `SemanticQueryBuilder`

All checks go into the existing visitor methods, guarded by
`creationOptions.isHqlSafeMode()`:

```java
// 1. Block function()
@Override
public SqmExpression<?> visitJpaNonstandardFunction(HqlParser.JpaNonstandardFunctionContext ctx) {
    if ( creationOptions.isHqlSafeMode() ) {
        throw new SemanticException(
            "function() calls are not allowed when HQL safe mode is enabled"
            + " — use a registered function name directly instead",
            query
        );
    }
    // ... existing code ...
}

// 2. Block column()
@Override
public SqmExpression<?> visitColumnFunction(HqlParser.ColumnFunctionContext ctx) {
    if ( creationOptions.isHqlSafeMode() ) {
        throw new SemanticException(
            "column() is not allowed when HQL safe mode is enabled"
            + " — only mapped entity attributes can be referenced",
            query
        );
    }
    // ... existing code ...
}

// 3. Block unregistered generic functions
private SqmFunctionDescriptor getFunctionTemplate(HqlParser.GenericFunctionContext ctx) {
    final String functionName = getFunctionName( ctx );
    final var functionTemplate = getFunctionDescriptor( functionName );
    if ( functionTemplate == null ) {
        if ( creationOptions.isHqlSafeMode() ) {
            throw new SemanticException(
                "Function '" + functionName + "' is not registered"
                + " — only registered functions are allowed"
                + " when HQL safe mode is enabled",
                query
            );
        }
        return new NamedSqmFunctionDescriptor( ... ); // existing pass-through
    }
    // ... existing validation ...
}
```

### D. Interaction with strict JPA compliance

These two modes are independent and composable:

| Mode | `function()` syntax | Unregistered generic | `column()` | Non-JPA-standard registered |
|------|---------------------|----------------------|------------|----------------------------|
| Default | allowed | pass-through | allowed | allowed |
| JPA strict only | allowed (it's JPA!) | pass-through | allowed | error (use `FUNCTION()`) |
| Safe mode only | **error** | **error** | **error** | allowed |
| Both | **error** | **error** | **error** | error (use... nothing, blocked either way) |

Note: enabling both modes simultaneously would be very restrictive — only the 25 JPA
standard functions would be usable, and `FUNCTION()` (the JPA escape hatch) would
also be blocked. This combination should probably log a warning or be documented.


## Open questions

1. **Naming:** `hql_safe_mode` vs `hql_strict_functions` vs `query.restrict_to_model`
   vs something else? The name should convey that this is about preventing escape from
   the domain model abstraction, not about SQL injection prevention (which is handled
   by parameterization).

2. **Granularity:** Should this be a single boolean, or should `function()`, `column()`,
   and unregistered-function-passthrough be independently toggleable? A single flag is
   simpler and covers the main use case; individual flags add complexity but allow
   intermediate configurations.

3. **Per-query override?** Should there be a way to opt out of safe mode for specific
   queries (e.g., via a query hint), or is it strictly a SessionFactory-level setting?
   A per-query override would reduce the friction of adoption but would weaken the
   guarantee.

4. **Error type:** Should blocked constructs throw `SemanticException` (like most
   HQL errors), or a dedicated exception subclass (like `StrictJpaComplianceViolation`)
   to allow callers to distinguish "safe mode blocked this" from genuine parse errors?