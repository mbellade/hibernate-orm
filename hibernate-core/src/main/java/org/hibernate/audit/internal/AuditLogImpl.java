/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.internal;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.hibernate.Session;
import org.hibernate.audit.AuditEntry;
import org.hibernate.audit.AuditException;
import org.hibernate.audit.AuditLog;
import org.hibernate.audit.ModificationType;
import org.hibernate.audit.legacy.AuditReader;
import org.hibernate.audit.spi.RevisionEntitySupplier;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.temporal.spi.TransactionIdentifierService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;

import static java.util.Objects.requireNonNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session-scoped implementation of {@link AuditLog} that queries
 * audit tables using HQL with {@code transactionId()} and
 * {@code modificationType()} functions.
 * <p>
 * Obtained via {@link org.hibernate.SharedSessionContract#getAuditLog()}.
 * Queries run on a child session (via
 * {@link org.hibernate.SharedSessionContract#sessionWithOptions()})
 * that shares the parent session's JDBC connection but has an
 * isolated persistence context, so audit snapshots don't pollute
 * the parent session's cache. The child session is created lazily
 * and closed automatically when the parent session closes.
 *
 * @author Marco Belladelli
 * @since envers-rewrite
 */
public class AuditLogImpl implements AuditReader {
	private final SessionFactoryImplementor sessionFactory;
	private final SharedSessionContractImplementor session;
	private final @Nullable RevisionEntitySupplier<?> revisionEntitySupplier;
	private final @Nullable String revisionEntityName;
	private final @Nullable String revisionNumberProperty;
	private final @Nullable String revisionTimestampProperty;
	private final @Nullable Class<?> timestampFieldType;

	/**
	 * Lazily initialized child session used for all-revisions queries.
	 * Shares the parent session's connection and is closed automatically
	 * when the parent session closes (via {@code ParentSessionObserver}).
	 */
	private @Nullable Session allRevisionsSession;

	public AuditLogImpl(SharedSessionContractImplementor session) {
		this.session = session; // todo (envers-rewrite) : we could move this back to be SF-scoped
		this.sessionFactory = session.getSessionFactory();
		final var service = sessionFactory.getServiceRegistry().getService( TransactionIdentifierService.class );
		if ( service != null && service.getIdentifierSupplier() instanceof RevisionEntitySupplier<?> supplier ) {
			this.revisionEntitySupplier = supplier;
			this.revisionEntityName = sessionFactory.getMappingMetamodel()
					.getEntityDescriptor( supplier.getRevisionEntityClass() )
					.getEntityName();
			this.revisionNumberProperty = supplier.getRevisionNumberProperty();
			this.revisionTimestampProperty = supplier.getRevisionTimestampProperty();
			this.timestampFieldType = sessionFactory.getMappingMetamodel()
					.getEntityDescriptor( supplier.getRevisionEntityClass() )
					.findAttributeMapping( supplier.getRevisionTimestampProperty() )
					.getJavaType().getJavaTypeClass();
		}
		else {
			this.revisionEntitySupplier = null;
			this.revisionEntityName = null;
			this.revisionNumberProperty = null;
			this.revisionTimestampProperty = null;
			this.timestampFieldType = null;
		}
	}

	// todo (envers-rewrite) : we should really try to cache the query plans to avoid the overhead of interpreting the HQL at runtime
	//  (where we can, ideally for all the equivalent operations as the old AuditReader API)

	@Override
	public List<Object> getRevisions(Class<?> entityClass, Object id) {
		requireNonNull( entityClass, "Entity class" );
		requireNonNull( id, "Primary key" );
		final var entityName = requireAuditedEntityName( entityClass );
		return allRevisionsSession().createSelectionQuery(
				"select transactionId(e) from " + entityName + " e"
				+ " where e.id = :id"
				+ " order by transactionId(e)",
				Object.class
		).setParameter( "id", id ).getResultList();
	}

	@Override
	public ModificationType getModificationType(Class<?> entityClass, Object id, Object transactionId) {
		requireNonNull( entityClass, "Entity class" );
		requireNonNull( id, "Primary key" );
		requireNonNull( transactionId, "Transaction identifier" );
		final var entityName = requireAuditedEntityName( entityClass );
		return allRevisionsSession().createSelectionQuery(
						"select modificationType(e) from " + entityName + " e"
						+ " where e.id = :id"
						+ " and transactionId(e) = :txId",
						ModificationType.class
				).setParameter( "id", id )
				.setParameter( "txId", transactionId )
				.getSingleResultOrNull();
	}

	@Override
	public List<Object> getEntitiesModifiedAt(Class<?> entityClass, Object transactionId) {
		requireNonNull( entityClass, "Entity class" );
		requireNonNull( transactionId, "Transaction identifier" );
		final var entityName = requireAuditedEntityName( entityClass );
		return allRevisionsSession().createSelectionQuery(
				"select e.id from " + entityName + " e"
				+ " where transactionId(e) = :txId",
				Object.class
		).setParameter( "txId", transactionId ).getResultList();
	}

	@Override
	public boolean isAudited(Class<?> entityClass) {
		requireNonNull( entityClass, "Entity class" );
		return getEntityPersister( entityClass ).getAuditMapping() != null;
	}

	@Override
	public boolean isAudited(String entityName) {
		requireNonNull( entityName, "Entity name" );
		final var persister = sessionFactory.getMappingMetamodel().findEntityDescriptor( entityName );
		return persister != null && persister.getAuditMapping() != null;
	}

	@Override
	public <T> T find(Class<T> entityClass, Object id, Object transactionId) {
		return find( entityClass, id, transactionId, false );
	}

	@Override
	public Object find(String entityName, Object id, Object transactionId) {
		return find( entityName, id, transactionId, false );
	}

	@Override
	public <T> T find(Class<T> entityClass, Object id, Object transactionId, boolean includeDeletions) {
		requireNonNull( entityClass, "Entity class" );
		return doFind( entityClass, requireAuditedEntityName( entityClass ), id, transactionId, includeDeletions );
	}

	@Override
	public Object find(String entityName, Object id, Object transactionId, boolean includeDeletions) {
		requireNonNull( entityName, "Entity name" );
		requireAuditedEntityName( entityName );
		return doFind( Object.class, entityName, id, transactionId, includeDeletions );
	}

	private <T> T doFind(Class<T> resultType, String entityName, Object id,
			Object transactionId, boolean includeDeletions) {
		requireNonNull( id, "Primary key" );
		requireNonNull( transactionId, "Transaction identifier" );
		var hql = "from " + entityName + " e"
				+ " where e.id = :id"
				+ " and transactionId(e) = :txId";
		if ( !includeDeletions ) {
			hql += " and modificationType(e) != :del";
		}
		final var query = allRevisionsSession().createSelectionQuery( hql, resultType )
				.setParameter( "id", id )
				.setParameter( "txId", transactionId );
		if ( !includeDeletions ) {
			query.setParameter( "del", ModificationType.DEL );
		}
		return query.getSingleResultOrNull();
	}

	@Override
	public <T> T find(Class<T> entityClass, Object id, Instant instant) {
		return find( entityClass, id, getTransactionId( instant ) );
	}

	@Override
	public <T> List<T> findEntitiesModifiedAt(Class<T> entityClass, Object transactionId) {
		requireNonNull( entityClass, "Entity class" );
		requireNonNull( transactionId, "Transaction identifier" );
		final var entityName = requireAuditedEntityName( entityClass );
		return allRevisionsSession().createSelectionQuery(
				"from " + entityName + " e"
				+ " where transactionId(e) = :txId",
				entityClass
		).setParameter( "txId", transactionId ).getResultList();
	}

	@Override
	public <T> List<AuditEntry<T>> getHistory(Class<T> entityClass, Object id) {
		requireNonNull( entityClass, "Entity class" );
		requireNonNull( id, "Primary key" );
		final var entityName = requireAuditedEntityName( entityClass );

		final String hql;
		if ( revisionEntityName != null ) {
			hql = "select e, r, modificationType(e)"
				+ " from " + entityName + " e"
				+ " join " + revisionEntityName + " r"
				+ " on r.id = transactionId(e)"
				+ " where e.id = :id"
				+ " order by transactionId(e)";
		}
		else {
			hql = "select e, transactionId(e), modificationType(e)"
				+ " from " + entityName + " e"
				+ " where e.id = :id"
				+ " order by transactionId(e)";
		}

		final List<Object[]> rows = allRevisionsSession()
				.createSelectionQuery( hql, Object[].class )
				.setParameter( "id", id ).getResultList();

		final List<AuditEntry<T>> result = new ArrayList<>( rows.size() );
		for ( var row : rows ) {
			@SuppressWarnings("unchecked") final var entity = (T) row[0];
			final var revision = row[1];
			final var modType = (ModificationType) row[2];
			result.add( new AuditEntry<>( entity, revision, modType ) );
		}
		return result;
	}

	// --- Revision entity queries ---

	@Override
	public Instant getTransactionTimestamp(Object transactionId) {
		requireNonNull( transactionId, "Transaction identifier" );
		requireRevisionEntity();
		final String hql = "select e." + revisionTimestampProperty
				+ " from " + revisionEntityName + " e"
				+ " where e." + revisionNumberProperty + " = :rev";
		final var result = allRevisionsSession()
				.createSelectionQuery( hql, Object.class )
				.setParameter( "rev", transactionId )
				.getSingleResultOrNull();
		if ( result == null ) {
			throw new AuditException( "Revision does not exist: " + transactionId );
		}
		return toInstant( result );
	}

	@Override
	public Object getTransactionId(Instant instant) {
		requireNonNull( instant, "Instant" );
		return resolveRevisionNumberForTimestamp( resolveTimestampValue( instant ) );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T findRevision(Object transactionId) {
		requireRevisionEntity();
		final var result = allRevisionsSession().createSelectionQuery(
				"from " + revisionEntityName + " where id = :rev",
				Object.class
		).setParameter( "rev", transactionId ).getSingleResultOrNull();
		if ( result == null ) {
			throw new AuditException( "Revision does not exist: " + transactionId );
		}
		return (T) result;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<Object, T> findRevisions(Set<?> transactionIds) {
		requireRevisionEntity();
		final var results = allRevisionsSession().createSelectionQuery(
				"from " + revisionEntityName + " where id in :revs order by id",
				Object.class
		).setParameter( "revs", transactionIds ).getResultList();
		final Map<Object, T> map = new LinkedHashMap<>();
		for ( var rev : results ) {
			final var id = allRevisionsSession().getIdentifier( rev );
			map.put( id, (T) rev );
		}
		return map;
	}

	// --- Helpers ---

	private Object resolveRevisionNumberForTimestamp(Object timestampValue) {
		requireRevisionEntity();
		final String hql = "select max(e." + revisionNumberProperty + ")"
				+ " from " + revisionEntityName + " e"
				+ " where e." + revisionTimestampProperty + " <= :ts";
		final var result = allRevisionsSession()
				.createSelectionQuery( hql, Object.class )
				.setParameter( "ts", timestampValue )
				.getSingleResultOrNull();
		if ( result == null ) {
			throw new AuditException( "No revision exists at or before the given date" );
		}
		return result;
	}

	/**
	 * Convert an {@link Instant} to match the revision entity's
	 * timestamp field type, following the same conversion logic
	 * as envers' {@code RevisionTimestampValueResolver}.
	 */
	private Object resolveTimestampValue(Instant instant) {
		if ( timestampFieldType == Date.class ) {
			return Date.from( instant );
		}
		else if ( timestampFieldType == LocalDateTime.class ) {
			return LocalDateTime.ofInstant( instant, ZoneId.systemDefault() );
		}
		else if ( timestampFieldType == Instant.class ) {
			return instant;
		}
		else {
			return instant.toEpochMilli();
		}
	}

	private void requireRevisionEntity() {
		if ( revisionEntitySupplier == null ) {
			throw new AuditException(
					"No @RevisionEntity configured. "
							+ "This operation requires a revision entity with "
							+ "@RevisionNumber and @RevisionTimestamp fields."
			);
		}
	}

	/**
	 * Returns the lazily-initialized child session for
	 * {@link AuditLog#ALL_REVISIONS} queries. The child session
	 * shares the parent's JDBC connection and is closed
	 * automatically when the parent session closes.
	 */
	private Session allRevisionsSession() {
		if ( allRevisionsSession == null ) {
			allRevisionsSession = session.sessionWithOptions()
					.connection()
					.atTransaction( ALL_REVISIONS )
					.open();
		}
		return allRevisionsSession;
	}

	private EntityPersister getEntityPersister(Class<?> entityClass) {
		return sessionFactory.getMappingMetamodel().getEntityDescriptor( entityClass );
	}

	private String requireAuditedEntityName(Class<?> entityClass) {
		return requireAuditedEntityName( getEntityPersister( entityClass ) );
	}

	private void requireAuditedEntityName(String entityName) {
		requireAuditedEntityName( sessionFactory.getMappingMetamodel().getEntityDescriptor( entityName ) );
	}

	private String requireAuditedEntityName(EntityPersister persister) {
		if ( persister.getAuditMapping() == null ) {
			throw new IllegalArgumentException(
					"Entity '" + persister.getEntityName() + "' is not audited"
			);
		}
		return persister.getEntityName();
	}

	private static Instant toInstant(Object value) {
		if ( value instanceof Instant instant ) {
			return instant;
		}
		else if ( value instanceof LocalDateTime localDateTime ) {
			return localDateTime.atZone( ZoneId.systemDefault() ).toInstant();
		}
		else if ( value instanceof Date date ) {
			return date.toInstant();
		}
		else if ( value instanceof Long millis ) {
			return Instant.ofEpochMilli( millis );
		}
		throw new AuditException( "Cannot convert revision timestamp to Instant: " + value );
	}
}
