/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.spi;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.hibernate.Incubating;
import org.hibernate.SharedSessionContract;
import org.hibernate.Session;
import org.hibernate.audit.AuditException;
import org.hibernate.audit.ModifiedEntityNames;
import org.hibernate.audit.RevisionListener;
import org.hibernate.audit.RevisionNumber;
import org.hibernate.audit.RevisionTimestamp;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.temporal.spi.TransactionIdentifierService;
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
	private final String revisionNumberProperty;
	private final String revisionTimestampProperty;
	private final @Nullable String modifiedEntityNamesProperty;
	private final @Nullable RevisionListener listener;

	/**
	 * @param revisionEntityClass the revision entity class
	 * @param revisionNumberProperty the name of the {@link RevisionNumber @RevisionNumber} property
	 * @param revisionTimestampProperty the name of the {@link RevisionTimestamp @RevisionTimestamp} property
	 * @param listener optional callback for populating custom fields
	 * @param modifiedEntityNamesProperty the name of the {@link ModifiedEntityNames @ModifiedEntityNames}
	 *        property, or {@code null} if entity change tracking is not configured
	 */
	public RevisionEntitySupplier(
			Class<?> revisionEntityClass,
			String revisionNumberProperty,
			String revisionTimestampProperty,
			@Nullable RevisionListener listener,
			@Nullable String modifiedEntityNamesProperty) {
		this.revisionEntityClass = revisionEntityClass;
		this.revisionNumberProperty = revisionNumberProperty;
		this.revisionTimestampProperty = revisionTimestampProperty;
		this.listener = listener;
		this.modifiedEntityNamesProperty = modifiedEntityNamesProperty;
	}

	/**
	 * The revision entity class.
	 */
	public Class<?> getRevisionEntityClass() {
		return revisionEntityClass;
	}

	/**
	 * The name of the {@link RevisionNumber @RevisionNumber} property.
	 */
	public String getRevisionNumberProperty() {
		return revisionNumberProperty;
	}

	/**
	 * The name of the {@link RevisionTimestamp @RevisionTimestamp} property.
	 */
	public String getRevisionTimestampProperty() {
		return revisionTimestampProperty;
	}

	/**
	 * The configured revision listener, or {@code null}.
	 */
	public @Nullable RevisionListener getListener() {
		return listener;
	}

	/**
	 * The name of the {@link ModifiedEntityNames @ModifiedEntityNames}
	 * property, or {@code null} if entity change tracking is not configured.
	 */
	public @Nullable String getModifiedEntityNamesProperty() {
		return modifiedEntityNamesProperty;
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
		final var childSession = persistRevisionEntity( session, revisionEntity );
		sessionImpl.getAuditWorkQueue().setRevisionContext( revisionEntity, childSession );
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
	 * Persist the revision entity using a child {@link Session}
	 * that shares the parent session's JDBC connection.
	 * The child session is returned so it can be kept open
	 * for deferred flush of {@code @ElementCollection} changes.
	 */
	private static Session persistRevisionEntity(
			SharedSessionContract session,
			Object revisionEntity) {
		final var childSession = session.sessionWithOptions()
				.connection()
				.openSession();
		childSession.persist( revisionEntity );
		childSession.flush();
		return childSession;
	}

	/**
	 * Resolve the {@link RevisionEntitySupplier} from the given
	 * service registry, or return {@code null} if no
	 * {@code @RevisionEntity} is configured.
	 */
	public static @Nullable RevisionEntitySupplier<?> resolve(ServiceRegistry registry) {
		final var service = registry.getService( TransactionIdentifierService.class );
		return service != null && service.getIdentifierSupplier() instanceof RevisionEntitySupplier<?> supplier
				? supplier
				: null;
	}
}
