/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * {@link org.hibernate.SharedSessionContract#getAuditLog()}.
 * The returned instance is session-scoped: all queries reuse
 * the underlying session, so loaded entities remain managed
 * and support lazy association loading (when using a stateful
 * {@link org.hibernate.Session}).
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
	 *
	 * @see #getHistory(Class, Object)
	 */
	Object ALL_REVISIONS = new Object();

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
	 * Check if an entity is audited by entity name.
	 *
	 * @param entityName the entity name
	 * @return {@code true} if the entity is audited
	 */
	boolean isAudited(String entityName);

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
	 * Find an entity snapshot by entity name at a specific
	 * transaction.
	 *
	 * @param entityName the entity name
	 * @param id the entity identifier
	 * @param transactionId the transaction identifier
	 * @return the entity state at that transaction, or
	 *         {@code null} if the entity did not exist
	 *
	 * @see #find(Class, Object, Object)
	 */
	Object find(String entityName, Object id, Object transactionId);

	/**
	 * Find an entity snapshot at a specific transaction,
	 * optionally including deleted entities.
	 * <p>
	 * When {@code includeDeletions} is {@code false}, this
	 * behaves identically to {@link #find(Class, Object, Object)},
	 * returning {@code null} for DEL revisions. When {@code true},
	 * the entity state at deletion is returned instead of
	 * {@code null}.
	 *
	 * @param entityClass the audited entity class
	 * @param id the entity identifier
	 * @param transactionId the transaction identifier
	 * @param includeDeletions whether to include deleted entities
	 * @return the entity state at that transaction
	 *
	 * @param <T> the entity type
	 */
	<T> T find(Class<T> entityClass, Object id, Object transactionId, boolean includeDeletions);

	/**
	 * Find an entity snapshot by entity name at a specific
	 * transaction, optionally including deleted entities.
	 *
	 * @param entityName the entity name
	 * @param id the entity identifier
	 * @param transactionId the transaction identifier
	 * @param includeDeletions whether to include deleted entities
	 * @return the entity state at that transaction
	 *
	 * @see #find(Class, Object, Object, boolean)
	 */
	Object find(String entityName, Object id, Object transactionId, boolean includeDeletions);

	/**
	 * Find an entity snapshot as of the given instant. Returns
	 * the state at the highest revision on or before the instant.
	 *
	 * @param entityClass the audited entity class
	 * @param id the entity identifier
	 * @param instant the point in time
	 * @return the entity state, or {@code null}
	 *
	 * @param <T> the entity type
	 */
	<T> T find(Class<T> entityClass, Object id, Instant instant);

	/**
	 * Find an entity snapshot by entity name as of the given
	 * instant.
	 *
	 * @param entityName the entity name
	 * @param id the entity identifier
	 * @param instant the point in time
	 * @return the entity state, or {@code null}
	 *
	 * @see #find(Class, Object, Instant)
	 */
	Object find(String entityName, Object id, Instant instant);

	/**
	 * Find all entity snapshots of the given type that
	 * were modified at a specific transaction.
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

	/**
	 * Get the full audit history for an entity by entity name.
	 *
	 * @param entityName the entity name
	 * @param id the entity identifier
	 * @return the audit history as a list of {@link AuditEntry}
	 *
	 * @see #getHistory(Class, Object)
	 */
	List<AuditEntry<Object>> getHistory(String entityName, Object id);

	/**
	 * Get the timestamp of a specific revision. Requires
	 * a {@link RevisionEntity @RevisionEntity} with a
	 * {@link RevisionTimestamp @RevisionTimestamp} field.
	 *
	 * @param transactionId the transaction identifier
	 * @return the revision timestamp
	 * @throws AuditException if no revision entity is configured
	 *         or the transaction does not exist
	 */
	Instant getTransactionTimestamp(Object transactionId);

	/**
	 * Get the transaction identifier that was current at or
	 * before the given instant. Requires a
	 * {@link RevisionEntity @RevisionEntity} with a
	 * {@link RevisionTimestamp @RevisionTimestamp} field.
	 *
	 * @param instant the point in time
	 * @return the most recent transaction identifier at or
	 *         before the given instant
	 * @throws AuditException if no transaction exists at or
	 *         before the given instant
	 */
	Object getTransactionId(Instant instant);

	/**
	 * Load the revision entity for the given transaction identifier.
	 * Requires a {@link RevisionEntity @RevisionEntity}.
	 *
	 * @param transactionId the transaction identifier
	 * @return the revision entity
	 * @throws AuditException if no revision entity is configured
	 *         or the revision does not exist
	 *
	 * @param <T> the revision entity type
	 */
	<T> T findRevision(Object transactionId);

	/**
	 * Load revision entities for multiple transaction identifiers.
	 * Requires a {@link RevisionEntity @RevisionEntity}.
	 *
	 * @param transactionIds the transaction identifiers
	 * @return a map from transaction identifier to revision entity
	 *
	 * @param <T> the revision entity type
	 */
	<T> Map<Object, T> findRevisions(Set<?> transactionIds);

}
