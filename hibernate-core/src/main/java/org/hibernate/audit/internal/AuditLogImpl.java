/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.internal;

import org.hibernate.audit.AuditEntry;
import org.hibernate.audit.AuditLog;
import org.hibernate.audit.ModificationType;
import org.hibernate.audit.RevisionEntitySupplier;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.temporal.spi.TransactionIdentifierService;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link AuditLog} that queries
 * audit tables using HQL with {@code transactionId()} and
 * {@code modificationType()} functions in all-revisions mode.
 *
 * @author Marco Belladelli
 * @since envers-rewrite
 */
public class AuditLogImpl implements AuditLog {
	// todo (envers-rewrite) : all operations (or most) seem to involve having to act on a session
	//  - the old envers' `AuditReader` used to be session-scoped, perhaps it's best if we do that here as well?
	//  - we risk not being able to load lazy associations if we just always create on demand sessions for some of this queries
	//    (at least ones that don't only return scalar values)
	private final SessionFactoryImplementor sessionFactory;
	private final @Nullable String revisionEntityName;

	public AuditLogImpl(SessionFactoryImplementor sessionFactory) {
		this.sessionFactory = sessionFactory;
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

	@Override
	public List<Object> getRevisions(Class<?> entityClass, Object id) {
		final var entityName = requireAuditedEntityName( entityClass );
		try ( var session = sessionFactory.withStatelessOptions()
				.atTransaction( ALL_REVISIONS ).openStatelessSession() ) {
			return session.createSelectionQuery(
					"select transactionId(e) from " + entityName + " e"
							+ " where e.id = :id"
							+ " order by transactionId(e)",
					Object.class
			).setParameter( "id", id ).getResultList();
		}
	}

	@Override
	public ModificationType getModificationType(Class<?> entityClass, Object id, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		try ( var session = sessionFactory.withStatelessOptions()
				.atTransaction( ALL_REVISIONS ).openStatelessSession() ) {
			final Object result = session.createSelectionQuery(
					"select modificationType(e) from " + entityName + " e"
							+ " where e.id = :id"
							+ " and transactionId(e) = :txId",
					Object.class
			).setParameter( "id", id ).setParameter( "txId", transactionId )
					.getSingleResultOrNull();
			if ( result == null ) {
				return null;
			}
			return ModificationType.values()[( (Number) result ).intValue()];
		}
	}

	@Override
	public List<Object> getEntitiesModifiedAt(Class<?> entityClass, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		try ( var session = sessionFactory.withStatelessOptions()
				.atTransaction( ALL_REVISIONS ).openStatelessSession() ) {
			return session.createSelectionQuery(
					"select e.id from " + entityName + " e"
							+ " where transactionId(e) = :txId",
					Object.class
			).setParameter( "txId", transactionId ).getResultList();
		}
	}

	@Override
	public boolean isAudited(Class<?> entityClass) {
		return getEntityPersister( entityClass ).getAuditMapping() != null;
	}

	@Override
	public <T> T find(Class<T> entityClass, Object id, Object transactionId) {
		try ( var session = sessionFactory.withStatelessOptions()
				.atTransaction( transactionId ).openStatelessSession() ) {
			return session.get( entityClass, id );
		}
	}

	@Override
	public <T> List<T> findEntitiesModifiedAt(Class<T> entityClass, Object transactionId) {
		final var ids = getEntitiesModifiedAt( entityClass, transactionId );
		if ( ids.isEmpty() ) {
			return List.of();
		}
		try ( var session = sessionFactory.withStatelessOptions()
				.atTransaction( transactionId ).openStatelessSession() ) {
			// getMultiple returns null for deleted entities — filter them out
			return session.getMultiple( entityClass, ids ).stream()
					.filter( java.util.Objects::nonNull )
					.toList();
		}
	}

	@Override
	public <T> List<AuditEntry<T>> getHistory(Class<T> entityClass, Object id) {
		final var entityName = requireAuditedEntityName( entityClass );

		try ( var session = sessionFactory.withOptions()
				.atTransaction( ALL_REVISIONS ).openSession() ) {
			final String hql;
			if ( revisionEntityName != null ) {
				// Join the revision entity to return it as the revision member of AuditEntry
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

			final List<Object[]> rows = session.createSelectionQuery( hql, Object[].class )
					.setParameter( "id", id ).getResultList();

			final List<AuditEntry<T>> result = new ArrayList<>( rows.size() );
			for ( var row : rows ) {
				@SuppressWarnings("unchecked")
				final var entity = (T) row[0];
				final var revision = row[1];
				final var modType = ModificationType.values()[( (Number) row[2] ).intValue()];
				result.add( new AuditEntry<>( entity, revision, modType ) );
			}
			return result;
		}
	}

	// --- helpers ---

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
