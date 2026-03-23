/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;

import org.hibernate.Incubating;
import org.hibernate.StatelessSession;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.util.ReflectHelper;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;

/**
 * A built-in {@link TransactionIdentifierSupplier} that persists
 * a user-defined revision entity and returns its generated
 * identifier as the transaction id for audit rows.
 * <p>
 * The revision entity is persisted using a temporary child
 * {@link StatelessSession} that shares the parent session's
 * JDBC connection and transaction, so it does not interfere
 * with the user's persistence context.
 * <p>
 * This supplier is {@linkplain #isEager() lazy} — the revision
 * entity is only persisted when the transaction id is first
 * requested (i.e. when an audited entity is actually modified),
 * avoiding empty revisions.
 * <p>
 * An optional {@link RevisionListener} callback can be
 * configured for populating custom fields.
 * <p>
 * Configure via:
 * <pre>
 * hibernate.temporal.transaction_id_supplier=org.hibernate.audit.RevisionEntitySupplier
 * hibernate.audit.revision_entity=com.example.RevisionInfo
 * hibernate.audit.revision_listener=com.example.MyRevisionListener
 * </pre>
 * <p>
 * Or programmatically by subclassing and overriding
 * {@link #createRevisionEntity()} and {@link #initializeRevisionEntity(Object)}.
 *
 * @param <T> the type of the transaction identifier (the revision entity's {@code @Id} type)
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
public class RevisionEntitySupplier<T> implements TransactionIdentifierSupplier<T> {
	private final Class<T> identifierType;
	private final Class<?> revisionEntityClass;
	private final RevisionListener listener;

	/**
	 * Constructor for subclasses that override
	 * {@link #createRevisionEntity()}.
	 */
	protected RevisionEntitySupplier(Class<T> identifierType) {
		this( identifierType, null, null );
	}

	/**
	 * Constructor with explicit revision entity class
	 * and optional listener.
	 */
	public RevisionEntitySupplier(Class<T> identifierType, Class<?> revisionEntityClass, RevisionListener listener) {
		this.identifierType = identifierType;
		this.revisionEntityClass = revisionEntityClass;
		this.listener = listener;
	}

	@Override
	public Class<T> getIdentifierType() {
		return identifierType;
	}

	@Override
	@SuppressWarnings("unchecked")
	public T getTransactionIdentifier(SharedSessionContractImplementor session) {
		final Object revisionEntity = createRevisionEntity();
		initializeRevisionEntity( revisionEntity );
		if ( listener != null ) {
			listener.newRevision( revisionEntity );
		}
		return (T) persistRevisionEntity( session, revisionEntity );
	}

	/**
	 * Create a new revision entity instance.
	 * Override this in subclasses for custom instantiation.
	 */
	protected Object createRevisionEntity() {
		if ( revisionEntityClass == null ) {
			throw new IllegalStateException(
					"No revision entity class configured. "
							+ "Either set 'hibernate.audit.revision_entity' "
							+ "or subclass RevisionEntitySupplier and override createRevisionEntity()"
			);
		}
		try {
			return ReflectHelper.getDefaultConstructor( revisionEntityClass ).newInstance();
		}
		catch (Exception e) {
			throw new IllegalStateException(
					"Could not instantiate revision entity: " + revisionEntityClass.getName(), e
			);
		}
	}

	/**
	 * Initialize a newly created revision entity with default
	 * values (e.g. timestamp). Override this in subclasses for
	 * custom initialization.
	 * <p>
	 * The default implementation does nothing. The
	 * {@link RevisionListener} callback, if configured, is
	 * invoked after this method.
	 */
	protected void initializeRevisionEntity(Object revisionEntity) {
		// no-op by default — override in subclasses or use RevisionListener
	}

	/**
	 * Persist the revision entity using a temporary child
	 * {@link StatelessSession} that shares the parent session's
	 * JDBC connection and return its generated identifier.
	 */
	private static Object persistRevisionEntity(
			SharedSessionContractImplementor session,
			Object revisionEntity) {
		try ( StatelessSession childSession = session.statelessWithOptions().connection().open() ) {
			return childSession.insert( revisionEntity );
		}
	}
}
