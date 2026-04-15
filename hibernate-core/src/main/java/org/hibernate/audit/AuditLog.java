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
 * Obtain an instance via {@link AuditLogFactory#create}.
 * The instance manages an internal session for audit queries;
 * close it when done to release the session and its JDBC
 * connection.
 *
 * @see AuditLogFactory
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
public interface AuditLog extends AutoCloseable {

	/**
	 * Close this audit log and release its internal session.
	 */
	@Override
	void close();
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
	 * Find all entity snapshots of the given type that
	 * were modified at a specific transaction with the
	 * specified modification type.
	 *
	 * @param entityClass the audited entity class
	 * @param transactionId the transaction identifier
	 * @param modificationType the modification type filter
	 * @return the matching entity snapshots
	 *
	 * @param <T> the entity type
	 */
	<T> List<T> findEntitiesModifiedAt(Class<T> entityClass, Object transactionId, ModificationType modificationType);

	/**
	 * Find all entity snapshots of the given type that
	 * were modified at a specific transaction, grouped
	 * by modification type (ADD, MOD, DEL).
	 *
	 * @param entityClass the audited entity class
	 * @param transactionId the transaction identifier
	 * @return entity snapshots grouped by modification type
	 *
	 * @param <T> the entity type
	 */
	<T> Map<ModificationType, List<T>> findEntitiesGroupedByModificationType(Class<T> entityClass, Object transactionId);

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

	// --- Cross-type revision queries ---

	/**
	 * Get the set of entity types that were modified at the
	 * given transaction.
	 * <p>
	 * Requires a {@link RevisionEntity @RevisionEntity} with a
	 * {@link ModifiedEntityNames @ModifiedEntityNames} property
	 * (e.g. {@link DefaultTrackingModifiedEntitiesRevisionEntity}).
	 *
	 * @param transactionId the transaction identifier
	 * @return the set of entity classes modified at that transaction
	 * @throws AuditException if entity change tracking is not enabled
	 */
	Set<Class<?>> getEntityTypesModifiedAt(Object transactionId);

	/**
	 * Find all entity snapshots across all audited types that
	 * were modified at the given transaction.
	 * <p>
	 * Requires a {@link RevisionEntity @RevisionEntity} with a
	 * {@link ModifiedEntityNames @ModifiedEntityNames} property
	 * (e.g. {@link DefaultTrackingModifiedEntitiesRevisionEntity}).
	 *
	 * @param transactionId the transaction identifier
	 * @return all entity snapshots modified at that transaction
	 * @throws AuditException if entity change tracking is not enabled
	 */
	List<Object> findAllEntitiesModifiedAt(Object transactionId);

	/**
	 * Find all entity snapshots across all audited types that
	 * were modified at the given transaction with the specified
	 * modification type.
	 * <p>
	 * Requires a {@link RevisionEntity @RevisionEntity} with a
	 * {@link ModifiedEntityNames @ModifiedEntityNames} property
	 * (e.g. {@link DefaultTrackingModifiedEntitiesRevisionEntity}).
	 *
	 * @param transactionId the transaction identifier
	 * @param modificationType the modification type filter
	 * @return the matching entity snapshots
	 * @throws AuditException if entity change tracking is not enabled
	 */
	List<Object> findAllEntitiesModifiedAt(Object transactionId, ModificationType modificationType);

	/**
	 * Find all entity snapshots modified at the given transaction,
	 * grouped by modification type (ADD, MOD, DEL).
	 * <p>
	 * Requires a {@link RevisionEntity @RevisionEntity} with a
	 * {@link ModifiedEntityNames @ModifiedEntityNames} property
	 * (e.g. {@link DefaultTrackingModifiedEntitiesRevisionEntity}).
	 *
	 * @param transactionId the transaction identifier
	 * @return entity snapshots grouped by modification type
	 * @throws AuditException if entity change tracking is not enabled
	 */
	Map<ModificationType, List<Object>> findAllEntitiesGroupedByModificationType(Object transactionId);

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
