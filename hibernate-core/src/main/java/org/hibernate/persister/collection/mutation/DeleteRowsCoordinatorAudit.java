/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * DeleteRowsCoordinator for audited collections.
 * <p>
 * Delegates to the standard coordinator without writing audit rows — audit DEL
 * rows are computed via snapshot diff in {@link InsertRowsCoordinatorAudit},
 * which handles both ADD and DEL audit rows for collection changes.
 */
public class DeleteRowsCoordinatorAudit implements DeleteRowsCoordinator {
	private final CollectionMutationTarget mutationTarget;
	private final DeleteRowsCoordinator currentDeleteCoordinator;

	public DeleteRowsCoordinatorAudit(
			CollectionMutationTarget mutationTarget,
			DeleteRowsCoordinator currentDeleteCoordinator) {
		this.mutationTarget = mutationTarget;
		this.currentDeleteCoordinator = currentDeleteCoordinator;
	}

	@Override
	public CollectionMutationTarget getMutationTarget() {
		return mutationTarget;
	}

	@Override
	public void deleteRows(
			PersistentCollection<?> collection,
			Object key,
			SharedSessionContractImplementor session) {
		currentDeleteCoordinator.deleteRows( collection, key, session );
	}
}
