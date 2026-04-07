/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.spi;

import org.hibernate.Incubating;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Callback interface for writing collection audit rows at
 * transaction completion. The implementation computes the diff
 * between the original snapshot and the current collection state,
 * then writes the resulting ADD/DEL audit rows.
 *
 * @see AuditWorkQueue
 *
 * @since envers-rewrite
 */
@Incubating
@FunctionalInterface
public interface CollectionAuditWriter {
	/**
	 * Compute the diff between the original snapshot and the current
	 * collection state, then write the resulting ADD/DEL audit rows.
	 *
	 * @param collection the persistent collection
	 * @param ownerId the owning entity's identifier
	 * @param originalSnapshot the snapshot captured before the first flush,
	 *                         or {@code null} for new collections
	 * @param session the current session
	 */
	void writeCollectionAuditRows(
			PersistentCollection<?> collection,
			Object ownerId,
			Object originalSnapshot,
			SharedSessionContractImplementor session);
}
