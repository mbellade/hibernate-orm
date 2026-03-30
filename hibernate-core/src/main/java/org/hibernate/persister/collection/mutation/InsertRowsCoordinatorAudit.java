/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import java.util.function.UnaryOperator;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.jdbc.batch.internal.BasicBatchKey;
import org.hibernate.engine.jdbc.mutation.spi.MutationExecutorService;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.audit.ModificationType;
import org.hibernate.sql.model.MutationOperationGroup;

import static java.util.Collections.emptyIterator;

/**
 * InsertRowsCoordinator for audited collections.
 */
public class InsertRowsCoordinatorAudit implements InsertRowsCoordinator {
	private final CollectionMutationTarget mutationTarget;
	private final InsertRowsCoordinator currentInsertCoordinator;
	private final SessionFactoryImplementor sessionFactory;
	private final MutationExecutorService mutationExecutorService;
	private final BasicBatchKey auditBatchKey;
	private final boolean deleteByIndex;
	private final boolean[] indexColumnIsSettable;
	private final boolean[] elementColumnIsSettable;
	private final UnaryOperator<Object> indexIncrementer;

	private MutationOperationGroup auditOperationGroup;
	private AuditCollectionHelper auditHelper;

	public InsertRowsCoordinatorAudit(
			CollectionMutationTarget mutationTarget,
			InsertRowsCoordinator currentInsertCoordinator,
			boolean deleteByIndex,
			boolean[] indexColumnIsSettable,
			boolean[] elementColumnIsSettable,
			UnaryOperator<Object> indexIncrementer,
			SessionFactoryImplementor sessionFactory) {
		this.mutationTarget = mutationTarget;
		this.currentInsertCoordinator = currentInsertCoordinator;
		this.sessionFactory = sessionFactory;
		this.deleteByIndex = deleteByIndex;
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

			// Use snapshot diff to avoid excessive audit rows during collection recreate.
			// For new collections (no loaded persister), just include everything.
			final var collectionEntry = session.getPersistenceContextInternal()
					.getCollectionEntry( collection );
			final boolean hasPriorDbState = collectionEntry != null
					&& collectionEntry.getLoadedPersister() != null;

			final var entries = collection.entries( collectionDescriptor );
			int entryCount = 0;
			while ( entries.hasNext() ) {
				final Object entry = entries.next();
				if ( hasPriorDbState
						? collection.needsInserting( entry, entryCount, collectionDescriptor.getElementType() )
						: entryChecker.include( entry, entryCount, collection, pluralAttribute ) ) {
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

			// Write DEL audit rows for actually removed elements (in snapshot but not in current)
			final var deletes = hasPriorDbState
					? collection.getDeletes( collectionDescriptor, !deleteByIndex )
					: emptyIterator();
			int deleteCount = 0;
			while ( deletes.hasNext() ) {
				final Object deleted = deletes.next();
				bindings.bindInsertValues(
						collection,
						id,
						deleted,
						deleteCount++,
						ModificationType.DEL,
						session,
						mutationExecutor.getJdbcValueBindings()
				);
				mutationExecutor.execute( deleted, null, null, null, session );
			}
		}
		finally {
			mutationExecutor.release();
		}
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
