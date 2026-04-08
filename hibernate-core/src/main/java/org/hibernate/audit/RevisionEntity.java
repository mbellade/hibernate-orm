/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.hibernate.Incubating;

/**
 * Marks an entity as the revision entity for audit logging.
 * The annotated class must have:
 * <ul>
 *   <li>a field annotated with {@link RevisionNumber} (typically
 *       the {@code @Id} with {@code @GeneratedValue})</li>
 *   <li>a field annotated with {@link RevisionTimestamp}
 *       (automatically set to the current time)</li>
 * </ul>
 * <p>
 * When a class annotated with {@code @RevisionEntity} is found
 * in the domain model, it's automatically configured as the
 * {@link org.hibernate.temporal.spi.TransactionIdentifierSupplier},
 * no {@code hibernate.temporal.transaction_id_supplier} setting
 * is required.
 * <p>
 * Only one entity may be annotated with {@code @RevisionEntity}.
 *
 * @see org.hibernate.annotations.Audited
 * @see RevisionNumber
 * @see RevisionTimestamp
 * @see RevisionListener
 *
 * @author Marco Belladelli
 *
 * @since 7.0
 */
@Incubating
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RevisionEntity {
	/**
	 * An optional {@link RevisionListener} implementation that
	 * will be called after the revision entity is created, to
	 * populate custom fields (e.g. user, comment).
	 */
	Class<? extends RevisionListener> listener() default RevisionListener.class;
}
