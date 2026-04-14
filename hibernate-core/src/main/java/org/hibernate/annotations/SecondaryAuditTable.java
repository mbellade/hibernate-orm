/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.hibernate.Incubating;

/**
 * Specifies a custom audit table name for a
 * {@link jakarta.persistence.SecondaryTable @SecondaryTable}.
 * Placed on the entity class alongside {@link Audited @Audited}.
 *
 * @see Audited
 *
 * @since envers-rewrite
 */
@Incubating
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(SecondaryAuditTables.class)
public @interface SecondaryAuditTable {
	/**
	 * The name of the secondary table being overridden.
	 */
	String secondaryTableName();

	/**
	 * The custom audit table name for this secondary table.
	 */
	String secondaryAuditTableName();
}
