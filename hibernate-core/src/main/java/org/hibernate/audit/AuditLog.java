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
 * {@link org.hibernate.SessionBuilder#atTransaction(Object)
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
	 * A special transaction identifier that selects all
	 * revisions from the audit table without filtering.
	 * Pass this to
	 * {@link org.hibernate.SessionBuilder#atTransaction(Object)
	 * atTransaction()} to open a session that reads all audit
	 * rows, including deletions.
	 * <p>
	 * Usage:
	 * <pre>
	 * try (var s = sf.withOptions()
	 *         .atTransaction(AuditLog.ALL_REVISIONS).open()) {
	 *     var history = s.createSelectionQuery(
	 *             "from MyEntity where id = :id",
	 *             MyEntity.class)
	 *         .setParameter("id", entityId)
	 *         .getResultList();
	 * }
	 * </pre>
	 */
	Object ALL_REVISIONS = org.hibernate.engine.spi.LoadQueryInfluencers.ALL_REVISIONS;

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

	/**
	 * Find an entity snapshot at a specific transaction.
	 *
	 * @param entityClass the audited entity class
	 * @param id the entity identifier
	 * @param transactionId the transaction identifier
	 * @return the entity state at that transaction, or
	 *         {@code null} if the entity did not exist
	 *         (e.g. before creation or after deletion)
	 *
	 * @param <T> the entity type
	 */
	<T> T find(Class<T> entityClass, Object id, Object transactionId);

	/**
	 * Find all entity snapshots of the given type that
	 * were modified at a specific transaction.
	 * <p>
	 * Deleted entities are excluded from the result.
	 *
	 * @param entityClass the audited entity class
	 * @param transactionId the transaction identifier
	 * @return the entity snapshots at that transaction
	 *
	 * @param <T> the entity type
	 */
	<T> List<T> findEntitiesModifiedAt(Class<T> entityClass, Object transactionId);

	/**
	 * Get the full audit history for an entity, ordered
	 * chronologically by transaction identifier.
	 * <p>
	 * Each entry contains the entity snapshot, the transaction
	 * identifier (or revision entity), and the
	 * {@linkplain ModificationType modification type}
	 * (ADD/MOD/DEL).
	 * <p>
	 * For DEL entries, the entity snapshot reflects the state
	 * at the moment of deletion.
	 *
	 * @param entityClass the audited entity class
	 * @param id the entity identifier
	 * @return the audit history as a list of {@link AuditEntry}
	 *
	 * @param <T> the entity type
	 */
	<T> List<AuditEntry<T>> getHistory(Class<T> entityClass, Object id);
}
