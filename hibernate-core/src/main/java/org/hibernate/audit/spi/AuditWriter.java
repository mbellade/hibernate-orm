/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.spi;

import org.hibernate.Incubating;
import org.hibernate.audit.ModificationType;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Callback interface implemented by audit coordinators to
 * perform the actual audit row write at transaction completion.
 *
 * @see AuditWorkQueue
 *
 * @since envers-rewrite
 */
@Incubating
@FunctionalInterface
public interface AuditWriter {
	/**
	 * Write an audit row for the given entity state and modification type.
	 * Called by the {@link AuditWorkQueue} at transaction completion.
	 *
	 * @param entity the entity instance (may be null)
	 * @param id the entity identifier
	 * @param values the entity state
	 * @param modificationType the modification type (ADD/MOD/DEL)
	 * @param session the current session
	 */
	void writeAuditRow(
			Object entity,
			Object id,
			Object[] values,
			ModificationType modificationType,
			SharedSessionContractImplementor session);
}
