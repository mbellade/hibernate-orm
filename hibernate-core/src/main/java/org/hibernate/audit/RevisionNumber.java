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
 * Marks the property that holds the revision number in a
 * {@link RevisionEntity}. This should typically be the
 * auto-generated primary key ({@code @Id @GeneratedValue}).
 * <p>
 * The value of this property is set by the persistence layer
 * when the revision entity is inserted (via {@code @GeneratedValue}).
 *
 * @see RevisionEntity
 *
 * @author Marco Belladelli
 *
 * @since 7.0
 */
@Incubating
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface RevisionNumber {
}
