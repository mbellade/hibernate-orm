/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.audit.ModificationType;

/**
 * InsertRowsCoordinator for audited one-to-many collections with @JoinColumn.
 * <p>
 * Delegates the FK UPDATE to the standard coordinator, then writes a MOD
 * audit row to the child entity's audit table for each affected entry.
 */
public class InsertRowsCoordinatorOneToManyAudit
		extends AbstractOneToManyAuditCoordinator
		implements InsertRowsCoordinator {
	private final InsertRowsCoordinator standardCoordinator;

	public InsertRowsCoordinatorOneToManyAudit(
			CollectionMutationTarget mutationTarget,
			InsertRowsCoordinator standardCoordinator,
			SessionFactoryImplementor sessionFactory) {
		super( mutationTarget, sessionFactory, "#OTM_AUDIT_INSERT" );
		this.standardCoordinator = standardCoordinator;
	}

	@Override
	public void insertRows(
			PersistentCollection<?> collection,
			Object id,
			EntryFilter entryChecker,
			SharedSessionContractImplementor session) {
		// 1. Delegate to standard coordinator: UPDATE child SET fk = ? WHERE id = ?
		standardCoordinator.insertRows( collection, id, entryChecker, session );

		// 2. Write MOD audit rows for each affected child entity
		final var pluralAttribute = getMutationTarget().getTargetPart();
		final var collectionDescriptor = pluralAttribute.getCollectionDescriptor();
		final var elementPersister = collectionDescriptor.getElementPersister();
		final var auditMapping = elementPersister.getAuditMapping();
		if ( auditMapping == null ) {
			return;
		}

		final var operationGroup = getOrBuildOperationGroup( elementPersister, auditMapping );
		final var entries = collection.entries( collectionDescriptor );
		int entryCount = 0;
		final var mutationExecutor = mutationExecutorService.createExecutor(
				() -> auditBatchKey, operationGroup, session );
		try {
			while ( entries.hasNext() ) {
				final Object entry = entries.next();
				if ( entryChecker == null || entryChecker.include( entry, entryCount, collection, pluralAttribute ) ) {
					bindAuditValues( entry, id, elementPersister, auditMapping,
							ModificationType.MOD, session, mutationExecutor.getJdbcValueBindings() );
					mutationExecutor.execute( entry, null, null, null, session );
				}
				entryCount++;
			}
		}
		finally {
			mutationExecutor.release();
		}
	}
}
