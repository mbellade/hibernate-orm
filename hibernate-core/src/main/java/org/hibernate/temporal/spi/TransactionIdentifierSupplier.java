/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.spi;

import org.hibernate.Incubating;
import org.hibernate.SharedSessionContract;

/**
 * A supplier of transaction identifiers for use with
 * {@linkplain org.hibernate.annotations.Temporal temporal}
 * or {@linkplain org.hibernate.annotations.Audited audited}
 * entities.
 * <p>
 * Implementations may persist a revision entity using the
 * provided session and return its generated identifier as
 * the transaction identifier for audit rows. This provides
 * a migration path from Hibernate Envers' {@code @RevisionEntity}
 * and {@code RevisionListener} pattern.
 * <p>
 * Transaction ids produced by this supplier must be distinct
 * and monotonically increasing. The supplier is called at
 * most once per transaction.
 * <p>
 * Configure via the
 * {@link TransactionIdentifierService} or the
 * {@link org.hibernate.cfg.StateManagementSettings#TRANSACTION_ID_SUPPLIER TRANSACTION_ID_SUPPLIER}
 * setting.
 *
 * @param <T> the type of transaction identifier produced
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
public interface TransactionIdentifierSupplier<T> {
	/**
	 * Called once per transaction to obtain the transaction
	 * identifier. The implementation may persist a revision
	 * entity using the provided session.
	 *
	 * @param session the current session
	 * @return the transaction identifier, for example, the
	 *         generated id of a persisted revision entity
	 */
	T getTransactionIdentifier(SharedSessionContract session);
}
