/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.legacy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Incubating;
import org.hibernate.audit.AuditLog;

/**
 * Legacy compatibility interface mirroring
 * {@code org.hibernate.envers.AuditReader}.
 * <p>
 * Extends {@link AuditLog} with methods that are not
 * supported in core auditing. Unsupported methods throw
 * {@link UnsupportedOperationException} with migration hints.
 * <p>
 * Obtain an instance via {@link AuditReaderFactory#get} or
 * directly via {@code session.getAuditLog()}.
 *
 * @deprecated Use {@link AuditLog}
 */
@Incubating
@Deprecated(forRemoval = true)
public interface AuditReader extends AuditLog {

	/**
	 * @deprecated Use {@link AuditLog#find(Class, Object, Instant)}
	 */
	@Deprecated(forRemoval = true)
	default <T> T find(Class<T> cls, Object primaryKey, Date date) {
		return find( cls, primaryKey, date.toInstant() );
	}

	/**
	 * @deprecated Use {@link AuditLog#find(Class, Object, Instant)}
	 */
	@Deprecated(forRemoval = true)
	default <T> T find(Class<T> cls, Object primaryKey, LocalDateTime dateTime) {
		return find( cls, primaryKey, dateTime.atZone( ZoneId.systemDefault() ).toInstant() );
	}


	/**
	 * Envers overload with entityName and class for typed return.
	 * The entityName parameter is ignored — core auditing resolves
	 * the entity from the class.
	 */
	@Deprecated(forRemoval = true)
	default <T> T find(Class<T> cls, String entityName, Object primaryKey, Object revision) {
		return find( cls, primaryKey, revision );
	}

	/**
	 * Envers overload with entityName, class, and includeDeletions.
	 * The entityName parameter is ignored — core auditing resolves
	 * the entity from the class.
	 */
	@Deprecated(forRemoval = true)
	default <T> T find(Class<T> cls, String entityName, Object primaryKey, Object revision, boolean includeDeletions) {
		return find( cls, primaryKey, revision, includeDeletions );
	}

	/**
	 * Envers overload with entityName parameter.
	 */
	@Deprecated(forRemoval = true)
	default List<Object> getRevisions(Class<?> cls, String entityName, Object primaryKey) {
		return getRevisions( cls, primaryKey );
	}

	/**
	 * Envers overload with explicit revision entity class.
	 * Core derives the class from the configured {@code @RevisionEntity}.
	 */
	@Deprecated(forRemoval = true)
	default <T> T findRevision(Class<T> revisionEntityClass, Object transactionId) {
		return findRevision( transactionId );
	}

	/**
	 * Envers overload with explicit revision entity class.
	 * Core derives the class from the configured {@code @RevisionEntity}.
	 */
	@Deprecated(forRemoval = true)
	default <T> Map<Object, T> findRevisions(Class<T> revisionEntityClass, Set<?> transactionIds) {
		return findRevisions( transactionIds );
	}

	/**
	 * @deprecated Use {@link AuditLog#isAudited(Class)}
	 */
	@Deprecated(forRemoval = true)
	default boolean isEntityClassAudited(Class<?> entityClass) {
		return isAudited( entityClass );
	}

	/**
	 * Legacy wrapper returning {@link Date}.
	 *
	 * @deprecated Use {@link AuditLog#getTransactionTimestamp(Object)}
	 */
	@Deprecated(forRemoval = true)
	default Date getRevisionDate(Number revision) {
		return Date.from( getTransactionTimestamp( revision ) );
	}

	/**
	 * @deprecated Use {@link AuditLog#getTransactionId(Instant)}
	 */
	@Deprecated(forRemoval = true)
	default Number getRevisionNumberForDate(Date date) {
		return (Number) getTransactionId( date.toInstant() );
	}

	/**
	 * @deprecated Use {@link AuditLog#getTransactionId(Instant)}
	 */
	@Deprecated(forRemoval = true)
	default Number getRevisionNumberForDate(LocalDateTime dateTime) {
		return (Number) getTransactionId( dateTime.atZone( ZoneId.systemDefault() ).toInstant() );
	}

	/**
	 * @deprecated Use {@link AuditLog#getTransactionId(Instant)}
	 */
	@Deprecated(forRemoval = true)
	default Number getRevisionNumberForDate(Instant instant) {
		return (Number) getTransactionId( instant );
	}

	/**
	 * Not supported in core auditing. Use HQL with
	 * {@code transactionId()}/{@code modificationType()} functions
	 * and standard HQL criteria instead.
	 *
	 * @throws UnsupportedOperationException always
	 */
	default Object createQuery() {
		throw new UnsupportedOperationException(
				"AuditQuery is not supported in core auditing. "
						+ "Use session.withOptions().atTransaction(txId).open() for point-in-time queries, "
						+ "or AuditLog.ALL_REVISIONS for querying across revision combined with the transactionId()/modificationType() HQL functions"
		);
	}

	/**
	 * Not supported in core auditing. Use
	 * {@link AuditLog#getEntityTypesModifiedAt},
	 * {@link AuditLog#findAllEntitiesModifiedAt}, or
	 * {@link AuditLog#findAllEntitiesGroupedByModificationType}
	 * instead.
	 *
	 * @throws UnsupportedOperationException always
	 */
	default Object getCrossTypeRevisionChangesReader() {
		throw new UnsupportedOperationException(
				"CrossTypeRevisionChangesReader is not supported in core auditing. "
						+ "Use AuditLog.getEntityTypesModifiedAt(), "
						+ "findAllEntitiesModifiedAt(), or "
						+ "findAllEntitiesGroupedByModificationType() instead."
		);
	}

	/**
	 * Envers overload with entityName parameter.
	 * Core auditing does not support entity-name-only lookups;
	 * this method always returns {@code false} for safety.
	 *
	 * @deprecated Use {@link AuditLog#isAudited(Class)}
	 */
	@Deprecated(forRemoval = true)
	default boolean isEntityNameAudited(String entityName) {
		throw new UnsupportedOperationException(
				"isEntityNameAudited() is not supported in core auditing. "
						+ "Use isAudited(Class) instead."
		);
	}

	/**
	 * Legacy method to retrieve the entity name.
	 * The entity must be associated with the session.
	 *
	 * @deprecated Not needed in core auditing; use standard
	 *             Hibernate APIs to obtain the entity name.
	 */
	@Deprecated(forRemoval = true)
	default String getEntityName(Object primaryKey, Number revision, Object entity) {
		throw new UnsupportedOperationException(
				"getEntityName() is not supported in core auditing. "
						+ "Use session.getEntityName(entity) instead."
		);
	}

	/**
	 * @deprecated Was already deprecated in envers 5.2.
	 *             Use {@code RevisionListener} instead.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Deprecated(since = "5.2")
	default <T> T getCurrentRevision(Class<T> revisionEntityClass, boolean persist) {
		throw new UnsupportedOperationException(
				"getCurrentRevision() was deprecated in Envers 5.2. "
						+ "Use RevisionListener to access the revision entity."
		);
	}
}
