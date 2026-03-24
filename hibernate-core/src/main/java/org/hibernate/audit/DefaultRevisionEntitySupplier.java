/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;

import org.hibernate.Incubating;

/**
 * A built-in {@link RevisionEntitySupplier} that persists
 * {@link DefaultRevisionEntity} instances, providing
 * backwards-compatible behavior matching
 * {@code org.hibernate.envers.DefaultRevisionEntity}.
 * <p>
 * The revision entity's timestamp is automatically set to the
 * current time in milliseconds.
 * <p>
 * Configure via:
 * <pre>
 * hibernate.temporal.transaction_id_supplier=org.hibernate.audit.DefaultRevisionEntitySupplier
 * </pre>
 * And add {@link DefaultRevisionEntity} to the domain model.
 *
 * @see DefaultRevisionEntity
 * @see RevisionEntitySupplier
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
public class DefaultRevisionEntitySupplier extends RevisionEntitySupplier<Integer> {
	public DefaultRevisionEntitySupplier() {
		super( Integer.class, DefaultRevisionEntity.class, null );
	}

	@Override
	protected void initializeRevisionEntity(Object revisionEntity) {
		((DefaultRevisionEntity) revisionEntity).setTimestamp( System.currentTimeMillis() );
	}
}
