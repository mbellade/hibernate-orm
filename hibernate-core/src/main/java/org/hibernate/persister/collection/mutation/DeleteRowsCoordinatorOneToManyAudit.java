/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.audit.ModificationType;

/**
 * DeleteRowsCoordinator for audited one-to-many collections with @JoinColumn.
 * <p>
 * Delegates the FK null-out to the standard coordinator, then writes a MOD
 * audit row to the child entity's audit table for each removed entry,
 * with the FK column set to null.
 */
public class DeleteRowsCoordinatorOneToManyAudit
		extends AbstractOneToManyAuditCoordinator
		implements DeleteRowsCoordinator {
	private final DeleteRowsCoordinator standardCoordinator;

	public DeleteRowsCoordinatorOneToManyAudit(
			CollectionMutationTarget mutationTarget,
			DeleteRowsCoordinator standardCoordinator,
			SessionFactoryImplementor sessionFactory) {
		super( mutationTarget, sessionFactory, "#OTM_AUDIT_DELETE" );
		this.standardCoordinator = standardCoordinator;
	}

	@Override
	public void deleteRows(
			PersistentCollection<?> collection,
			Object key,
			SharedSessionContractImplementor session) {
		final var pluralAttribute = getMutationTarget().getTargetPart();
		final var collectionDescriptor = pluralAttribute.getCollectionDescriptor();
		final var elementPersister = collectionDescriptor.getElementPersister();
		final var auditMapping = elementPersister.getAuditMapping();

		// Collect removed entries BEFORE the standard coordinator nulls the FK
		final List<Object> removedEntries = new ArrayList<>();
		if ( auditMapping != null ) {
			final var deletes = collection.getDeletes( collectionDescriptor, false );
			while ( deletes.hasNext() ) {
				removedEntries.add( deletes.next() );
			}
		}

		// Delegate to standard coordinator: UPDATE child SET fk = NULL WHERE ...
		standardCoordinator.deleteRows( collection, key, session );

		// Write MOD audit rows for each removed child entity (FK = null)
		if ( auditMapping != null && !removedEntries.isEmpty() ) {
			final var operationGroup = getOrBuildOperationGroup( elementPersister, auditMapping );
			final var mutationExecutor = mutationExecutorService.createExecutor(
					() -> auditBatchKey, operationGroup, session );
			try {
				for ( final Object entry : removedEntries ) {
					bindAuditValues( entry, null, elementPersister, auditMapping,
							ModificationType.MOD, session, mutationExecutor.getJdbcValueBindings() );
					mutationExecutor.execute( entry, null, null, null, session );
				}
			}
			finally {
				mutationExecutor.release();
			}
		}
	}
}
