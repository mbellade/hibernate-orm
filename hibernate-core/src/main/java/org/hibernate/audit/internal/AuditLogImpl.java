/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.internal;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.hibernate.Session;
import org.hibernate.audit.AuditEntry;
import org.hibernate.audit.AuditLog;
import org.hibernate.audit.ModificationType;
import org.hibernate.audit.spi.RevisionEntitySupplier;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.temporal.spi.TransactionIdentifierService;

import java.util.ArrayList;
import java.util.List;

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
public class AuditLogImpl implements AuditLog {
	private final SessionFactoryImplementor sessionFactory;
	private final SharedSessionContractImplementor session;
	private final @Nullable String revisionEntityName;

	/**
	 * Lazily initialized child session used for all-revisions queries.
	 * Shares the parent session's connection and is closed automatically
	 * when the parent session closes (via {@code ParentSessionObserver}).
	 */
	private @Nullable Session allRevisionsSession;

	public AuditLogImpl(SharedSessionContractImplementor session) {
		this.session = session; // todo (envers-rewrite) : we could move this back to be SF-scoped
		this.sessionFactory = session.getSessionFactory();
		this.revisionEntityName = resolveRevisionEntityName( sessionFactory );
	}

	private static @Nullable String resolveRevisionEntityName(SessionFactoryImplementor sessionFactory) {
		final var service = sessionFactory.getServiceRegistry()
				.getService( TransactionIdentifierService.class );
		if ( service != null && service.getIdentifierSupplier() instanceof RevisionEntitySupplier<?> supplier ) {
			final var revisionEntityClass = supplier.getRevisionEntityClass();
			if ( revisionEntityClass != null ) {
				return sessionFactory.getMappingMetamodel()
						.getEntityDescriptor( revisionEntityClass )
						.getEntityName();
			}
		}
		return null;
	}

	// todo (envers-rewrite) : we should really try to cache the query plans to avoid the overhead of interpreting the HQL at runtime
	//  (where we can, ideally for all the equivalent operations as the old AuditReader API)

	@Override
	public List<Object> getRevisions(Class<?> entityClass, Object id) {
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
		final var entityName = requireAuditedEntityName( entityClass );
		return allRevisionsSession().createSelectionQuery(
				"select e.id from " + entityName + " e"
				+ " where transactionId(e) = :txId",
				Object.class
		).setParameter( "txId", transactionId ).getResultList();
	}

	@Override
	public boolean isAudited(Class<?> entityClass) {
		return getEntityPersister( entityClass ).getAuditMapping() != null;
	}

	@Override
	public <T> T find(Class<T> entityClass, Object id, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		return allRevisionsSession().createSelectionQuery(
						"from " + entityName + " e"
						+ " where e.id = :id"
						+ " and transactionId(e) = :txId"
						+ " and modificationType(e) != :del",
						entityClass
				).setParameter( "id", id )
				.setParameter( "txId", transactionId )
				.setParameter( "del", ModificationType.DEL )
				.getSingleResultOrNull();
	}

	@Override
	public <T> List<T> findEntitiesModifiedAt(Class<T> entityClass, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		return allRevisionsSession().createSelectionQuery(
				"from " + entityName + " e"
				+ " where transactionId(e) = :txId",
				entityClass
		).setParameter( "txId", transactionId ).getResultList();
	}

	@Override
	public <T> List<AuditEntry<T>> getHistory(Class<T> entityClass, Object id) {
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

	// --- helpers ---

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
		final var persister = getEntityPersister( entityClass );
		if ( persister.getAuditMapping() == null ) {
			throw new IllegalArgumentException(
					"Entity '" + persister.getEntityName() + "' is not audited"
			);
		}
		return persister.getEntityName();
	}
}
