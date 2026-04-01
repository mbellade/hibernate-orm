/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import org.hibernate.audit.ModificationType;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.jdbc.batch.internal.BasicBatchKey;
import org.hibernate.engine.jdbc.mutation.spi.MutationExecutorService;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.sql.model.MutationOperationGroup;
import org.hibernate.type.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * InsertRowsCoordinator for audited collections.
 */
public class InsertRowsCoordinatorAudit implements InsertRowsCoordinator {
	private final CollectionMutationTarget mutationTarget;
	private final InsertRowsCoordinator currentInsertCoordinator;
	private final SessionFactoryImplementor sessionFactory;
	private final MutationExecutorService mutationExecutorService;
	private final BasicBatchKey auditBatchKey;
	private final boolean[] indexColumnIsSettable;
	private final boolean[] elementColumnIsSettable;
	private final UnaryOperator<Object> indexIncrementer;

	private MutationOperationGroup auditOperationGroup;
	private AuditCollectionHelper auditHelper;

	public InsertRowsCoordinatorAudit(
			CollectionMutationTarget mutationTarget,
			InsertRowsCoordinator currentInsertCoordinator,
			boolean[] indexColumnIsSettable,
			boolean[] elementColumnIsSettable,
			UnaryOperator<Object> indexIncrementer,
			SessionFactoryImplementor sessionFactory) {
		this.mutationTarget = mutationTarget;
		this.currentInsertCoordinator = currentInsertCoordinator;
		this.sessionFactory = sessionFactory;
		this.indexColumnIsSettable = indexColumnIsSettable;
		this.elementColumnIsSettable = elementColumnIsSettable;
		this.indexIncrementer = indexIncrementer;
		this.auditBatchKey = new BasicBatchKey( mutationTarget.getRolePath() + "#AUDIT_INSERT" );
		this.mutationExecutorService = sessionFactory.getServiceRegistry().getService( MutationExecutorService.class );
	}

	@Override
	public CollectionMutationTarget getMutationTarget() {
		return mutationTarget;
	}

	@Override
	public void insertRows(
			PersistentCollection<?> collection,
			Object id,
			EntryFilter entryChecker,
			SharedSessionContractImplementor session) {
		currentInsertCoordinator.insertRows( collection, id, entryChecker, session );

		if ( auditOperationGroup == null ) {
			auditOperationGroup = getAuditHelper().getAuditInsertOperationGroup();
		}
		if ( auditOperationGroup == null ) {
			return;
		}

		final var pluralAttribute = mutationTarget.getTargetPart();
		final var collectionDescriptor = pluralAttribute.getCollectionDescriptor();

		final var mutationExecutor = mutationExecutorService.createExecutor(
				() -> auditBatchKey,
				auditOperationGroup,
				session
		);

		try {
			final var bindings = getAuditHelper().getRowMutationHelper();
			final var collectionEntry = session.getPersistenceContextInternal()
					.getCollectionEntry( collection );
			final boolean hasPriorDbState = collectionEntry != null
					&& collectionEntry.getLoadedPersister() != null;

			if ( !hasPriorDbState ) {
				// New collection, write ADD for all entries
				final var entries = collection.entries( collectionDescriptor );
				int entryCount = 0;
				while ( entries.hasNext() ) {
					final Object entry = entries.next();
					if ( entryChecker.include( entry, entryCount, collection, pluralAttribute ) ) {
						bindings.bindInsertValues(
								collection,
								id,
								entry,
								entryCount,
								ModificationType.ADD,
								session,
								mutationExecutor.getJdbcValueBindings()
						);
						mutationExecutor.execute( entry, null, null, null, session );
					}
					entryCount++;
				}
			}
			else {
				// Existing collection, compute semantic diff
				for ( var change : computeCollectionChanges( collection, collectionDescriptor ) ) {
					bindings.bindInsertValues(
							collection,
							id,
							change.rawEntry,
							change.position,
							change.modificationType,
							session,
							mutationExecutor.getJdbcValueBindings()
					);
					mutationExecutor.execute( change.rawEntry, null, null, null, session );
				}
			}
		}
		finally {
			mutationExecutor.release();
		}
	}

	/** An audit change to write: the raw entry, its position, and the modification type. */
	private record AuditChange(Object rawEntry, int position, ModificationType modificationType) {}

	/**
	 * Compute the set of ADD/DEL changes between the collection's snapshot and current
	 * state, following the same approach as envers' {@code mapCollectionChanges()}.
	 * <p>
	 * For indexed collections (maps, lists with {@code @OrderColumn}), uses direct
	 * snapshot lookups by index/key. For non-indexed collections (sets, bags), uses
	 * linear scan.
	 */
	private List<AuditChange> computeCollectionChanges(
			PersistentCollection<?> collection,
			CollectionPersister collectionDescriptor) {
		final Object snapshot = collection.getStoredSnapshot();
		final Type elementType = collectionDescriptor.getElementType();
		if ( collectionDescriptor.hasIndex() ) {
			return snapshot instanceof Map<?,?> snapshotMap
					? computeMapChanges( collection, collectionDescriptor, snapshotMap, elementType )
					: computeListChanges( collection, collectionDescriptor, (List<?>) snapshot, elementType );
		}
		else {
			// Non-indexed (sets, bags): extract snapshot elements into a mutable list
			final Collection<?> snapshotElements = snapshot instanceof Map<?, ?> snapshotMap
					? snapshotMap.values()
					: (Collection<?>) snapshot;
			return computeUnindexedChanges( collection, collectionDescriptor, snapshotElements, elementType );
		}
	}

	/**
	 * Diff for maps: direct lookup by key in snapshot.
	 *
	 * @implNote Unlike envers which uses {@code Type.isSame()} for key comparison via linear
	 * scan, this uses {@code Map.get()} for O(1) key lookup. Should be safe because the map's
	 * own contract requires {@code equals()}/{@code hashCode()} consistency for keys, if a
	 * key is in the map, {@code get()} must find it.
	 */
	private List<AuditChange> computeMapChanges(
			PersistentCollection<?> collection,
			CollectionPersister collectionDescriptor,
			Map<?, ?> snapshot,
			Type elementType) {
		final List<AuditChange> changes = new ArrayList<>();
		final var currentMap = (Map<?, ?>) collection;

		// Current entries not matching snapshot: ADD
		final var entries = collection.entries( collectionDescriptor );
		int i = 0;
		while ( entries.hasNext() ) {
			final var entry = (Map.Entry<?, ?>) entries.next();
			if ( entry.getValue() != null ) {
				final Object snapshotValue = snapshot.get( entry.getKey() );
				if ( snapshotValue == null || !elementType.isSame( entry.getValue(), snapshotValue ) ) {
					changes.add( new AuditChange( entry, i, ModificationType.ADD ) );
				}
			}
			i++;
		}

		// Snapshot entries not in current (or value changed): DEL
		for ( var entry : snapshot.entrySet() ) {
			if ( entry.getValue() != null ) {
				final Object currentValue = currentMap.get( entry.getKey() );
				if ( currentValue == null || !elementType.isSame( entry.getValue(), currentValue ) ) {
					changes.add( new AuditChange( entry, i++, ModificationType.DEL ) );
				}
			}
		}

		return changes;
	}

	/** Diff for indexed lists: positional comparison against the snapshot. */
	private List<AuditChange> computeListChanges(
			PersistentCollection<?> collection,
			CollectionPersister collectionDescriptor,
			List<?> snapshot,
			Type elementType) {
		final List<AuditChange> changes = new ArrayList<>();

		final var entries = collection.entries( collectionDescriptor );
		int i = 0;
		while ( entries.hasNext() ) {
			final Object current = collection.getElement( entries.next() );
			final Object old = i < snapshot.size() ? snapshot.get( i ) : null;
			final boolean same = current != null && old != null && elementType.isSame( current, old );
			if ( current != null && !same ) {
				changes.add( new AuditChange( current, i, ModificationType.ADD ) );
			}
			if ( old != null && !same ) {
				changes.add( new AuditChange( old, i, ModificationType.DEL ) );
			}
			i++;
		}

		// Snapshot positions beyond current size are all DELs
		for ( ; i < snapshot.size(); i++ ) {
			final Object old = snapshot.get( i );
			if ( old != null ) {
				changes.add( new AuditChange( old, i, ModificationType.DEL ) );
			}
		}

		return changes;
	}

	/** Diff for non-indexed collections (sets, bags): linear scan with mutable snapshot copy. */
	private List<AuditChange> computeUnindexedChanges(
			PersistentCollection<?> collection,
			CollectionPersister collectionDescriptor,
			Collection<?> snapshotElements,
			Type elementType) {
		final var remaining = new ArrayList<>( snapshotElements );
		final List<AuditChange> changes = new ArrayList<>();

		final var entries = collection.entries( collectionDescriptor );
		int i = 0;
		while ( entries.hasNext() ) {
			final Object element = collection.getElement( entries.next() );
			if ( element != null ) {
				boolean matched = false;
				for ( var it = remaining.iterator(); it.hasNext(); ) {
					if ( elementType.isSame( element, it.next() ) ) {
						it.remove();
						matched = true;
						break;
					}
				}
				if ( !matched ) {
					changes.add( new AuditChange( element, i, ModificationType.ADD ) );
				}
			}
			i++;
		}

		for ( var element : remaining ) {
			changes.add( new AuditChange( element, i++, ModificationType.DEL ) );
		}

		return changes;
	}

	private AuditCollectionHelper getAuditHelper() {
		if ( auditHelper == null ) {
			auditHelper = new AuditCollectionHelper(
					mutationTarget,
					sessionFactory,
					indexColumnIsSettable,
					elementColumnIsSettable,
					indexIncrementer,
					mutationTarget.getTargetPart().getAuditMapping()
			);
		}
		return auditHelper;
	}
}
