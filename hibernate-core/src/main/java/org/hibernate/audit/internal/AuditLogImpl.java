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
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.temporal.spi.TransactionIdentifierService;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Session-scoped implementation of {@link AuditLog} that queries
 * audit tables using HQL with {@code transactionId()} and
 * {@code modificationType()} functions in all-revisions mode.
 * <p>
 * Obtained via {@link org.hibernate.SharedSessionContract#getAuditLog()}.
 * All queries reuse the underlying session, so loaded entities
 * remain managed and support lazy association loading.
 *
 * @author Marco Belladelli
 * @since envers-rewrite
 */
public class AuditLogImpl implements AuditLog {
	private final SessionFactoryImplementor sessionFactory;
	private final SharedSessionContractImplementor session;
	private final @Nullable String revisionEntityName;

	public AuditLogImpl(SharedSessionContractImplementor session) {
		this.session = session;
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

	@Override
	public List<Object> getRevisions(Class<?> entityClass, Object id) {
		final var entityName = requireAuditedEntityName( entityClass );
		return withTemporalIdentifier( ALL_REVISIONS,
				() -> session.createSelectionQuery(
						"select transactionId(e) from " + entityName + " e"
								+ " where e.id = :id"
								+ " order by transactionId(e)",
						Object.class
				).setParameter( "id", id ).getResultList()
		);
	}

	@Override
	public ModificationType getModificationType(Class<?> entityClass, Object id, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		final Object result = withTemporalIdentifier( ALL_REVISIONS,
				() -> session.createSelectionQuery(
						"select modificationType(e) from " + entityName + " e"
								+ " where e.id = :id"
								+ " and transactionId(e) = :txId",
						Object.class
				).setParameter( "id", id ).setParameter( "txId", transactionId )
						.getSingleResultOrNull()
		);
		if ( result == null ) {
			return null;
		}
		return ModificationType.values()[( (Number) result ).intValue()];
	}

	@Override
	public List<Object> getEntitiesModifiedAt(Class<?> entityClass, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		return withTemporalIdentifier( ALL_REVISIONS,
				() -> session.createSelectionQuery(
						"select e.id from " + entityName + " e"
								+ " where transactionId(e) = :txId",
						Object.class
				).setParameter( "txId", transactionId ).getResultList()
		);
	}

	@Override
	public boolean isAudited(Class<?> entityClass) {
		return getEntityPersister( entityClass ).getAuditMapping() != null;
	}

	@Override
	public <T> T find(Class<T> entityClass, Object id, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		return withTemporalIdentifier( transactionId,
				() -> session.createSelectionQuery(
						"from " + entityName + " e where e.id = :id",
						entityClass
				).setParameter( "id", id ).getSingleResultOrNull()
		);
	}

	@Override
	public <T> List<T> findEntitiesModifiedAt(Class<T> entityClass, Object transactionId) {
		final var entityName = requireAuditedEntityName( entityClass );
		return withTemporalIdentifier( ALL_REVISIONS,
				() -> session.createSelectionQuery(
						"from " + entityName + " e"
								+ " where transactionId(e) = :txId",
						entityClass
				).setParameter( "txId", transactionId ).getResultList()
		);
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

		final List<Object[]> rows = withTemporalIdentifier( ALL_REVISIONS,
				() -> session.createSelectionQuery( hql, Object[].class )
						.setParameter( "id", id ).getResultList()
		);

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

	// --- helpers ---

	/**
	 * Execute an action with a temporary temporal identifier,
	 * restoring the previous value afterwards.
	 */
	private <R> R withTemporalIdentifier(
			Object temporalIdentifier,
			Supplier<R> action) {
		final var influencers = session.getLoadQueryInfluencers();
		final Object previous = influencers.getTemporalIdentifier();
		influencers.setTemporalIdentifier( temporalIdentifier );
		try {
			return action.get();
		}
		finally {
			influencers.setTemporalIdentifier( previous );
		}
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
