/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.spi;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.hibernate.Incubating;
import org.hibernate.SharedSessionContract;
import org.hibernate.StatelessSession;
import org.hibernate.audit.AuditException;
import org.hibernate.audit.RevisionListener;
import org.hibernate.audit.RevisionNumber;
import org.hibernate.audit.RevisionTimestamp;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * A built-in {@link TransactionIdentifierSupplier} that persists
 * a user-defined revision entity and returns the
 * {@link RevisionNumber @RevisionNumber} property value as the
 * transaction id for audit rows.
 * <p>
 * The revision entity is persisted using a temporary child
 * {@link StatelessSession} that shares the parent session's
 * JDBC connection and transaction, so it does not interfere
 * with the user's persistence context.
 * <p>
 * The {@link RevisionTimestamp @RevisionTimestamp} property is
 * automatically set to the current time before persistence.
 * <p>
 * An optional {@link RevisionListener} callback can be
 * configured for populating custom fields.
 *
 * @param <T> the type of the transaction identifier
 * (the {@link RevisionNumber @RevisionNumber} property type)
 * @author Marco Belladelli
 * @since envers-rewrite
 */
@Incubating
public class RevisionEntitySupplier<T> implements TransactionIdentifierSupplier<T> {
	private final Class<?> revisionEntityClass;
	private final @Nullable RevisionListener listener;
	private final String revisionNumberProperty;
	private final String revisionTimestampProperty;

	/**
	 * @param revisionEntityClass the revision entity class
	 * @param revisionNumberProperty the name of the {@link RevisionNumber @RevisionNumber} property
	 * @param revisionTimestampProperty the name of the {@link RevisionTimestamp @RevisionTimestamp} property
	 * @param listener optional callback for populating custom fields
	 */
	public RevisionEntitySupplier(
			Class<?> revisionEntityClass,
			String revisionNumberProperty,
			String revisionTimestampProperty,
			@Nullable RevisionListener listener) {
		this.revisionEntityClass = revisionEntityClass;
		this.revisionNumberProperty = revisionNumberProperty;
		this.revisionTimestampProperty = revisionTimestampProperty;
		this.listener = listener;
	}

	/**
	 * The revision entity class.
	 */
	public Class<?> getRevisionEntityClass() {
		return revisionEntityClass;
	}

	/**
	 * The configured revision listener, or {@code null}.
	 */
	public @Nullable RevisionListener getListener() {
		return listener;
	}

	@Override
	@SuppressWarnings("unchecked")
	public T generateTransactionIdentifier(SharedSessionContract session) {
		final var sessionImpl = (SharedSessionContractImplementor) session;
		final EntityPersister persister = sessionImpl.getEntityPersister( revisionEntityClass.getName(), null );
		final Object revisionEntity = persister.instantiate( null, sessionImpl );
		initializeRevisionEntity( revisionEntity, persister );
		if ( listener != null ) {
			listener.newRevision( revisionEntity );
		}
		persistRevisionEntity( session, revisionEntity );
		// Store the revision entity on the work queue for
		// EntityTrackingRevisionListener callbacks
		sessionImpl.getAuditWorkQueue().setRevisionEntity( revisionEntity );
		return (T) readRevisionNumber( revisionEntity, persister, sessionImpl );
	}

	/**
	 * Set the {@link RevisionTimestamp @RevisionTimestamp} property
	 * to the current time via the {@link EntityPersister}.
	 * Override for custom initialization.
	 */
	protected void initializeRevisionEntity(Object revisionEntity, EntityPersister persister) {
		final var timestampAttr = persister.findAttributeMapping( revisionTimestampProperty );
		if ( timestampAttr == null ) {
			throw new AuditException(
					"@RevisionTimestamp property '" + revisionTimestampProperty
					+ "' not found on " + revisionEntityClass.getName()
			);
		}
		final Object timestamp = resolveTimestamp( timestampAttr.getJavaType().getJavaTypeClass() );
		persister.setValue( revisionEntity, timestampAttr.getStateArrayPosition(), timestamp );
	}

	/**
	 * Read the {@link RevisionNumber @RevisionNumber} property value
	 * from the revision entity after persistence.
	 * <p>
	 * Handles both regular properties and {@code @Id} properties.
	 */
	private Object readRevisionNumber(
			Object revisionEntity,
			EntityPersister persister,
			SharedSessionContractImplementor session) {
		final Object revisionNumber;
		final var revNumberAttr = persister.findAttributeMapping( revisionNumberProperty );
		if ( revNumberAttr != null ) {
			revisionNumber = persister.getValue( revisionEntity, revNumberAttr.getStateArrayPosition() );
		}
		else {
			// @RevisionNumber is the @Id
			revisionNumber = persister.getIdentifier( revisionEntity, session );
		}
		if ( revisionNumber == null ) {
			throw new AuditException(
					"@RevisionNumber property '" + revisionNumberProperty
					+ "' is null after persisting revision entity '"
					+ revisionEntityClass.getName() + "'"
			);
		}
		return revisionNumber;
	}

	private static Object resolveTimestamp(Class<?> type) {
		if ( type == long.class || type == Long.class ) {
			return System.currentTimeMillis();
		}
		else if ( type == Instant.class ) {
			return Instant.now().truncatedTo( ChronoUnit.MILLIS );
		}
		else if ( type == Date.class ) {
			return new Date();
		}
		else if ( type == LocalDateTime.class ) {
			return LocalDateTime.now();
		}
		else {
			throw new AuditException(
					"Unsupported @RevisionTimestamp type: " + type.getName()
					+ ". Supported: long, Long, Instant, Date, LocalDateTime"
			);
		}
	}

	/**
	 * Persist the revision entity using a temporary child
	 * {@link StatelessSession} that shares the parent session's
	 * JDBC connection.
	 */
	private static void persistRevisionEntity(
			SharedSessionContract session,
			Object revisionEntity) {
		try (StatelessSession childSession = session.statelessWithOptions().connection().open()) {
			childSession.insert( revisionEntity );
		}
	}
}
