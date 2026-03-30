/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import org.hibernate.engine.spi.CollectionKey;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.audit.ModificationType;

/**
 * RemoveCoordinator for audited one-to-many collections with @JoinColumn.
 * <p>
 * Delegates the bulk FK null-out to the standard coordinator, then writes
 * a MOD audit row to the child entity's audit table for each child that
 * was in the collection, with the FK column set to null.
 */
public class RemoveCoordinatorOneToManyAudit
		extends AbstractOneToManyAuditCoordinator
		implements RemoveCoordinator {
	private final RemoveCoordinator standardCoordinator;

	public RemoveCoordinatorOneToManyAudit(
			CollectionMutationTarget mutationTarget,
			RemoveCoordinator standardCoordinator,
			SessionFactoryImplementor sessionFactory) {
		super( mutationTarget, sessionFactory, "#OTM_AUDIT_REMOVE" );
		this.standardCoordinator = standardCoordinator;
	}

	@Override
	public String getSqlString() {
		return standardCoordinator.getSqlString();
	}

	@Override
	public void deleteAllRows(Object key, SharedSessionContractImplementor session) {
		final var pluralAttribute = getMutationTarget().getTargetPart();
		final var collectionDescriptor = pluralAttribute.getCollectionDescriptor();
		final var elementPersister = collectionDescriptor.getElementPersister();
		final var auditMapping = elementPersister.getAuditMapping();

		// Collect child entities from the persistence context BEFORE bulk removal.
		// Force initialization if needed — we need to know which children are affected
		// before the standard coordinator nulls their FK.
		final var collectionKey = new CollectionKey( collectionDescriptor, key );
		final var collection = session.getPersistenceContextInternal().getCollection( collectionKey );
		if ( collection != null && !collection.wasInitialized() ) {
			collection.forceInitialization();
		}

		// Delegate to standard coordinator: UPDATE child SET fk = NULL WHERE fk = ?
		standardCoordinator.deleteAllRows( key, session );

		// Write MOD audit rows for each child entity (FK = null)
		if ( auditMapping != null && collection != null ) {
			final var operationGroup = getOrBuildOperationGroup( elementPersister, auditMapping );
			final var mutationExecutor = mutationExecutorService.createExecutor(
					() -> auditBatchKey,
					operationGroup,
					session
			);
			try {
				final var entries = collection.entries( collectionDescriptor );
				while ( entries.hasNext() ) {
					final Object entry = entries.next();
					bindAuditValues(
							entry,
							null,
							elementPersister,
							auditMapping,
							ModificationType.MOD,
							session,
							mutationExecutor.getJdbcValueBindings()
					);
					mutationExecutor.execute( entry, null, null, null, session );
				}
			}
			finally {
				mutationExecutor.release();
			}
		}
	}
}
