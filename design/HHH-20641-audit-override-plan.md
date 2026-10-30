# HHH-20641: `@Audited.Override` for core auditing

Design plan for porting the `@AuditOverride` mechanic from Hibernate Envers into
the new `@Audited` core logic. Status: agreed design, pre-implementation.

## Background

- Jira: https://hibernate.atlassian.net/browse/HHH-20641
- Prior community attempt (not merged, superseded by this plan): PR #13086 (niklas-enns)

### Existing Envers functionality (source of requirements, not of design)

- `@AuditOverride(name, isAudited, auditJoinTable, forClass)`, repeatable via `@AuditOverrides`.
- Processing in `AuditedPropertiesReader.readAuditOverrides`: leaf-first recursive scan of the
  superclass chain with mutual-exclusion guards; deepest declaration wins.
- `forClass` required for mapped-superclass targets; empty `name` = whole-class override;
  unknown property names throw `EnversMappingException` at boot.
- Class-level: un-audit an `@Audited` MSC, or re-audit a non-audited superclass
  (incl. deprecated `@Audited.auditParents`).
- Embeddables: overrides both on the `@Embedded` site (with `forClass` targeting a class inside
  the embeddable's hierarchy) and on the `@Embeddable` class itself.
- Join-table (collection middle table) renames via `auditJoinTable`, taking precedence over a
  site-level `@AuditJoinTable` (HHH-12913).
- Effects are baked into boot-time `PropertyMapper` trees; runtime work units are override-unaware.

### Why PR #13086 was not merged (review findings, incorporated here)

- Resolution scattered across three extraction methods with inheritance-strategy-specific
  branching (TPC scans up, others also scan down via `isRevoked`), each re-scanning annotations
  multiple times.
- Downward scan let a subclass override mutate superclass audit mapping state.
- Persister re-derived overrides via a separate `getOverridesMap` scan.
- Secondary-table cases left `@FailureExpected`; a `null` param NPE risk and a TODO remained.
- No embeddable support.

## Core model facts (verified on main)

- Audit enablement is bound once on the `RootClass` (`EntityBinder.bindAudited`); subclasses
  inherit; only `Audited.Table` overrides are per-subclass (`bindSubclassAuditTables`).
- `Property.auditedExcluded` is the single declaration-time lever, set only from a *direct*
  `@Audited.Excluded` in `PropertyBinder.handleAuditedExcluded` (also sets
  `optimisticLocked=false`).
- Inherited entity properties are *shared* `Property` instances down the hierarchy; MSC-declared
  properties are *re-bound* per inheriting entity. Per-subclass state therefore cannot live on
  `Property` alone.
- Consumers of `Property.isAuditedExcluded()`:
  1. `AuditHelper.resolveExcludedColumns` (+ `collectPropertyColumns`) second passes - column
     copying into `_AUD` tables; SINGLE_TABLE already iterates subclasses.
  2. `AbstractEntityPersister.propertyAuditedExcluded[]` - audit insert masks
     (`EntityAuditSupport`) and load-plan exclusion (`AuditHelper.isFetchableAuditExcluded`).
  3. `CollectionBinder.processAudited` / `bindOneToManyAuditTable` - middle audit table
     existence and naming.
- Each `@Embedded` site gets a fresh `Component` with freshly bound sub-properties
  (`EmbeddableBinder`), so per-site embeddable resolution is naturally available.
- `resolveExcludedColumns` does not recurse into components: `@Audited.Excluded` on an embeddable
  sub-property is currently silently ignored.
- Property names are unique within a hierarchy (`PersistentClass:714` duplicate check), so
  name-based override targeting is unambiguous.

## Design

**Core principle: overrides are local, artifacts are unions.**

### Annotation

Nested under `@Audited` (consistent with `Audited.Table`, `Audited.Excluded`, etc.):

```java
@Target(TYPE)
@Retention(RUNTIME)
@Repeatable(Audited.Overrides.class)
@interface Override {
    String name();                                  // required, property (or dotted path) name
    boolean isAudited() default true;
    Audited.CollectionTable collectionTable() default @Audited.CollectionTable; // middle-table rename, ignored when isAudited=false
}
```

Dropped from Envers, deliberately:

- `forClass`: redundant under nearest-wins name resolution (names are unique per hierarchy).
  Convenience sugar for "exclude all properties of MSC X" may be added later without touching
  the resolution model.
- Class-level (empty-name) re-auditing of non-audited superclasses: architecturally impossible
  in core today (audit enablement is root-determined); would be a separate feature/Jira if ever
  requested.

### Resolution

- `effectiveAudited(P, T)` = nearest `@Audited.Override(name = P)` walking from `T` up the
  `ClassDetails.getSuperClass()` chain (via `getNamedAnnotationUsage(..., "name", ...)`),
  falling back to the declaration flag `Property.isAuditedExcluded()`.
- One resolution rule, no inheritance-strategy branching. This reproduces every passing
  expectation of PR #13086's inheritance tests:
  - SINGLE_TABLE/JOINED: shared `_AUD` table contains the column iff *any* type in the hierarchy
    resolves the property as audited (union; a subclass override only changes that subclass's
    state, the shared column survives via the union).
  - TPC: each subclass has its own `_AUD` table; per-class resolution applies directly.
- Runtime (masks, load exclusion): per-persister, each subclass resolving its own chain.
  `isFetchableAuditExcluded` gains a `FetchParent` parameter to consult the actually-loaded
  type's persister mask for entity-inherited properties.

### Consumers

- `PropertyBinder.handleAuditedExcluded`: unchanged; `auditedExcluded` stays pure declaration
  state.
- `AuditHelper`: new `findAuditOverride(String, ClassDetails, ModelsContext)` +
  `isEffectivelyExcluded(PersistentClass, Property)`; `resolveExcludedColumns` overloads
  restructured to per-class resolution with union semantics (fixes PR #13086's secondary-table
  `@FailureExpected` cases with the same rule).
- `AbstractEntityPersister`: `propertyAuditedExcluded[i]` computed from
  `isEffectivelyExcluded(persistentClass, property)`; no separate overrides map scan.
- `CollectionBinder`:
  - Middle-table *existence*: union over the hierarchy (table must exist if any subclass audits
    the collection).
  - Middle-table *naming*: scoped to the declaring class and its MSC chain (nearest override at
    or above the owner). A subclass override MUST NOT rename a collection table owned by its
    superclass (one shared `Collection` mapping/middle table per declaration site; Envers never
    had this problem because it generated per-entity audit mappings).
- Validation at binding time (`MappingException`): unknown `name()` against the hierarchy
  property closure; blank `name()`.

## Status (post package 1)

Package 1 (entity / MSC / inheritance / collection support) is implemented on branch
`HHH-20641` (not yet compiled / tested / committed). Changed production files:
`Audited.java`, `AuditHelper`, `EntityBinder`, `CollectionBinder`,
`AbstractEntityPersister`, `EntityPersister`, `AuditStateManagement`,
`LoaderSelectBuilder`, `BaseSqmToSqlAstConverter`, `EntityResultGraphNode`,
`AbstractEntityResultGraphNode`, `EntityFetchJoinedImpl`, `EntityInitializerImpl`.
Tests under `hibernate-core/src/test/java/org/hibernate/temporal/audit/auditoverrides/`
(7 files: assertions helper, MSC, inheritance, secondary tables, collections, embedded collections, validation).
Documentation (.adoc) deliberately not written yet (user decision): revisit at the very end;
possibly nothing, if there is no core `@Audited` docs section worth extending. Javadocs /
inline comments are in scope.

Additional design decisions made during implementation (all verified with the main session):

- `AuditStateManagement.buildTableAuditInfoMap` widened from `rootClass.getJoins()` to
  `getSubclassJoinClosure()` so subclass-owned `@SecondaryTable` audit tables are registered.
- Collection middle-table existence re-checked inside the existing collection second passes
  (subclass registration is first-pass, so it is populated by then); no nested optional passes
  (they would risk `ConcurrentModificationException`).
- Fetch-plan exclusion uses a polymorphic-closure mask (property excluded only when excluded
  for every concrete type in the loaded closure), populated in `postInstantiate()` from the
  already-resolved per-class masks of the linked subclass persisters. Attributes are matched
  by name, not by the loaded persister's state-array position. This avoids re-resolving boot
  annotations and keeps the result independent of persister construction order. Polymorphic reads surface
  subclass-audited values; per-row hiding enforced via concrete-type masks during assembly
  (`EntityInitializerImpl` post-pass on mixed-exclusion attributes, guarded by `isAuditRead()`
  on `EntityResultGraphNode`, default false, set from influencers, delegated in
  `EntityFetchJoinedImpl`).
- Implicit to-one fetches with mixed concrete-type exclusion use select fetching during audit
  point loads. This preserves rows whose audit FK is null despite a mandatory current-state
  association, without changing regular loads or uniformly audited associations.
- Subclass-declared fetchables absent from the loaded root descriptor use the declaring
  type's polymorphic mask. Concrete-row hiding visits descendants of the loaded type;
  `isTypeOrSuperType` takes the candidate supertype, not the candidate descendant.
- `Audited.CollectionTable.name()` gained `default ""` (blank = unspecified) so a default
  `@Audited.CollectionTable` value on `Override` is inert.

### Pre-existing bug fixed alongside (separate commit candidate)

DEFAULT-strategy `AuditLog.find()` on a TABLE_PER_CLASS subclass entity returned null
(confirmed pre-existing via stash check on clean HEAD; reproducer evidence in
`local://HHH-20641-tpc-stash-check.md`). Root cause: `AuditEntityLoaderImpl` passed the
physical mapped table name into `createRestriction()`, selecting the plain root
`TableAuditInfo` instead of the logical UNION entry registered by `addAuditSubquery()`, so
the correlated `MAX(REV)` subquery scanned only the root audit table. Fix: loader selects
`entityMappingType.getEntityPersister().getTableName()` (logical union expression) - one
statement plus comment in `AuditEntityLoaderImpl.java`; regression retained as
`AuditTablePerClassFindTest` (no override annotations, deliberately no @Jira tag). Files:
`AuditEntityLoaderImpl.java` + `AuditTablePerClassFindTest.java`, independent of the override
feature, to be committed separately (user decision: separate commit, no separate Jira).
`migration-guide.adoc` edit by the agent was reverted by the main session (doc files are
left to the very end; whether this bugfix warrants a migration-guide entry is a decision
for the user at commit time).
- Component-owned collection table existence follows the top-level component property's
  effective exclusion, unioned over concrete owner types. Nested component and collection
  declaration exclusions still apply at every depth; bare entity overrides do not match nested
  members. Collection table naming remains declaration-based until dotted-path resolution
  (package 3). Regressions: `AuditExcludedComponentCollectionTest` and
  `AuditEmbeddedCollectionOverrideTest`.
- Unknown override names are validated only for audit state management, not temporal history.

### Package 2: concrete collection owners and row-time masking

Both collection write queues and bulk-removal audit handling use the shared
`ResultsHelper.getCollectionOwner` pattern: look up the collection key in the
persistence context first; on null, extract the wrapper owner's identifier without
initializing proxies and re-resolve through the persistence context. The resolved
entry's persister supplies the concrete audit mask. Only ownerless work falls back
to the declaring persister. Component collections consult their top-level owning
property's mask.

The legacy queue filters excluded collections before callback registration and change
recording. The graph contributor applies the same filter before recording either
collection rows or the owner's MOD change. Both queues build owner audit changes
using the concrete persister, so re-audited subclass collections create revisions
without a scalar co-update. Collection change keys include the role and owner id;
skipping one collection does not discard another collection's snapshot.

Owner entity changes use the resolved owner's actual entity key, not a potentially
non-PK property-ref collection key. Entity coordinators and contributors construct
their own final audit support. Only `CollectionAuditSupport` caches arbitrary
concrete-owner support in a private concurrent map; this support is session-independent.
Property-ref regressions cover null-primary fallback and opposite concrete-owner
masks with both audit strategies and both queues. Wrong-non-null-owner collisions
remain outside this package, pending a separate Jira for `getCollectionOwner` lookup ordering.

The former limitations were ghost middle rows for subclass-excluded collections and
missing collection-only revisions for subclass-restored collections. The collection
override regressions now pin both directions, including legacy queue coverage.
Legacy entity update handling still treats a dirty collection as sufficient for an
owner revision, even when that collection is audit-excluded. This existing revision
policy is unchanged; excluded middle rows are suppressed in both queues.

After the write-path regressions passed, the result-graph audit-read flag and the
initializer constructor post-pass were removed. Row-processing data carries the
audit context; per-row concrete masks skip excluded subinitializers and supply null
or primitive defaults during state assembly. No per-concrete-type static masking
fallback remains. Generic absent-fetch assemblers and hierarchy-union fetch planning
are unchanged.

### Remaining work packages

- **Package 2 (implemented)**: collection writes resolve concrete owners in both queues;
  collection-only revision fixtures no longer co-update scalars. The result graph no
  longer stores audit-read session state; initializer masking runs at row time.
- **Package 3 (embeddable sub-property overrides, dotted paths)**: as specified in the
  "Embeddables" design section above. Pre-PR.
- **Final step (after all packages)**: consolidation pass on the auditoverrides test suite -
  merge overlapping fixtures/classes where clean, keeping all pinned behaviors; rerun full
  suite after. Deliberately last, so consolidation does not churn while packages 2/3 add cases.
- Docs decision (possibly nothing), spotless, final review, commit (user approval gates each).

### Semantics decisions (agreed)

- Overrides affect audit behavior only; they do not restore `optimisticLocked` on the base
  table (that flag derives from the declaration-time `@Audited.Excluded`).
- A property audited only by some entities of a hierarchy yields a nullable `_AUD` column for
  entities that exclude it (no validation possible or sensible; documented + tested).

### Embeddables (package 3)

- Dotted-path names: `@Audited.Override(name = "contactPerson.firstName", isAudited = false)`
  at the embedding entity (or MSC above it); embeddable-local overrides
  (`name = "firstName"`) on the `@Embeddable` class resolved at every embedding site.
- Precedence: nearest site-level path override > member declaration flag > embeddable-local
  override > default (audited).
- Requires: recursion in `resolveExcludedColumns` (all overloads), an excluded-attribute-path
  set on the persister consulted for nested fetchables (`isFetchableAuditExcluded` currently
  bails for `EmbeddableMappingType`), and column-level masking within component value bindings
  in `EntityAuditSupport`.
- Collections nested in embeddables: `name = "address.tags", collectionTable = ...`.

## Implementation plan (original, superseded by the Status section)

Historical; kept for reference. The current package split is: package 1 (done, see Status),
package 2 (collection write-path owner resolution), package 3 (embeddables). The original
numbering below ("commit 1 / commit 2") predates the discovery of the collection write-path
limitations and the docs decision (docs possibly nothing, .adoc left to the very end).


### Commit 1

1. `Audited.Override` / `Audited.Overrides` annotations (`org.hibernate.annotations`).
2. `AuditHelper.findAuditOverride` + `isEffectivelyExcluded`; restructure
   `resolveExcludedColumns` overloads to per-class resolution with union semantics.
3. `AbstractEntityPersister`: per-persister masks via `isEffectivelyExcluded`.
4. `isFetchableAuditExcluded(Fetchable, FetchParent, LoadQueryInfluencers)` +
   `LoaderSelectBuilder`/`BaseSqmToSqlAstConverter` call sites.
5. `CollectionBinder`: second-pass middle-table existence (union) + owner-scoped rename.
6. Binding-time validation (unknown/blank `name`).
7. Tests: inheritance (SINGLE_TABLE/JOINED/TPC, entity + MSC hierarchies), collection tables,
   secondary tables, nullable-column edge case, `optimisticLocked` pin. Conventions:
   `org.hibernate.temporal.audit` package, `@AuditedTest` class template, static inner-class
   entities with explicit names, `@Jira` annotation.
8. Docs: `whats-new.adoc`, `documentation` module auditing section.

### Commit 2

1. Dotted-path resolution (site-level + embeddable-local).
2. `resolveExcludedColumns` recursion into components.
3. Persister excluded-path set + nested fetchable exclusion in load plans.
4. Component column-level audit insert masking in `EntityAuditSupport`.
5. Tests mirroring Envers' `integration/embeddables/EmbeddableTest` and
   `integration/superclass/auditoverride/EmbeddableTest` matrices.

## Process rules (session-specific)

- After each work package: stop, present, wait for explicit approval.
- Never review/commit/push without explicit approval.
- Agents implementing code MUST compile what they write (`./gradlew <module>:compileJava`
  and `compileTestJava` for test changes) before delivering, and include the compile result in
  their report. (Added after package 1 was delivered non-compiling: three trivial errors that
  surfaced one at a time. Builds are read-only for the repo; the no-build constraint was
  over-broad. Tests/formatters/git operations remain gated as below.)
- No builds/tests are run without user approval; compiling by delegated agents is the exception
  above. Running tests requires explicit user approval.
- Main session does not edit files directly; delegate to code agents. Javadocs/inline comments
  are in scope. `.adoc` documentation files are left to the very end (possibly nothing).
