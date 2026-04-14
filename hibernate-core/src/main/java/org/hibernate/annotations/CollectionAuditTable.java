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
 * Specifies a custom audit table name (and optionally schema
 * and catalog) for an audited collection.
 * Placed on the collection field or property.
 *
 * @see Audited
 *
 * @since envers-rewrite
 */
@Incubating
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface CollectionAuditTable {
	/**
	 * The name of the collection audit table.
	 */
	String name();

	/**
	 * The schema of the collection audit table.
	 * Defaults to the schema of the owning entity's audit table.
	 */
	String schema() default "";

	/**
	 * The catalog of the collection audit table.
	 * Defaults to the catalog of the owning entity's audit table.
	 */
	String catalog() default "";
}
