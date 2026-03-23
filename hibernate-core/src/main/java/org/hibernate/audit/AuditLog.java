/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;

import java.util.List;

import org.hibernate.Incubating;

/**
 * A service for querying audit metadata. Provides access
 * to revision history and modification types for
 * {@linkplain org.hibernate.annotations.Audited audited}
 * entities, complementing the transparent point-in-time
 * reads available via
 * {@link org.hibernate.SessionFactory#withOptions()
 * atTransaction()} sessions.
 * <p>
 * Obtain an instance via
 * {@link org.hibernate.SessionFactory#getAuditLog()}.
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
public interface AuditLog {
	/**
	 * List all transaction identifiers where the given entity
	 * was modified, ordered chronologically.
	 *
	 * @param entityClass the audited entity class
	 * @param id the entity identifier
	 * @return the list of transaction identifiers
	 */
	List<Object> getRevisions(Class<?> entityClass, Object id);

	/**
	 * Get the {@linkplain ModificationType modification type}
	 * (ADD/MOD/DEL) for an entity at a specific transaction.
	 *
	 * @param entityClass the audited entity class
	 * @param id the entity identifier
	 * @param transactionId the transaction identifier
	 * @return the modification type, or {@code null} if the
	 *         entity was not modified at that transaction
	 */
	ModificationType getModificationType(Class<?> entityClass, Object id, Object transactionId);

	/**
	 * Get all entity identifiers of the given type that were
	 * modified in a given transaction.
	 *
	 * @param entityClass the audited entity class
	 * @param transactionId the transaction identifier
	 * @return the list of entity identifiers
	 */
	List<Object> getEntitiesModifiedAt(Class<?> entityClass, Object transactionId);

	/**
	 * Check if an entity type is audited.
	 *
	 * @param entityClass the entity class
	 * @return {@code true} if the entity is audited
	 */
	boolean isAudited(Class<?> entityClass);
}
