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
 * Marks the property that holds the revision timestamp in a
 * {@link RevisionEntity}. The value is automatically set to
 * the current time when the revision entity is created.
 * <p>
 * Supported types: {@code long}, {@code Long},
 * {@link java.util.Date}, {@link java.time.Instant},
 * {@link java.time.LocalDateTime}.
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
public @interface RevisionTimestamp {
}
