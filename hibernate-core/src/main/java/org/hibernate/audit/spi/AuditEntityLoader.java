/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.spi;

import org.hibernate.Incubating;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Loads an entity snapshot from an audit table at a specific
 * transaction. Similar to
 * {@link org.hibernate.loader.ast.spi.SingleIdEntityLoader}
 * but specialized for audit table reads.
 * <p>
 * The default implementation uses a pre-built SQL AST load plan.
 *
 * @author Marco Belladelli
 * @see org.hibernate.metamodel.mapping.AuditMapping#getEntityLoader
 * @since envers-rewrite
 */
@Incubating
public interface AuditEntityLoader {

	/**
	 * Load an entity snapshot at the given transaction.
	 *
	 * @param id the entity identifier
	 * @param transactionId the transaction identifier
	 * @param includeDeletions whether to include DEL revisions
	 * @param session the session to use for loading
	 * @return the entity instance, or {@code null}
	 */
	<T> T find(Object id, Object transactionId, boolean includeDeletions, SharedSessionContractImplementor session);
}
