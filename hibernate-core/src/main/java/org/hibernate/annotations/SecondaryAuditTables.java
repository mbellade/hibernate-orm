/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.hibernate.Incubating;

/**
 * Container for repeatable {@link SecondaryAuditTable} annotations.
 *
 * @see SecondaryAuditTable
 *
 * @since envers-rewrite
 */
@Incubating
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SecondaryAuditTables {
	SecondaryAuditTable[] value();
}
