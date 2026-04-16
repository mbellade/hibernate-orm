/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;


/**
 * The type of modification recorded in the
 * {@linkplain org.hibernate.annotations.Audited#modificationType
 * modification type column} of the audit log.
 *
 * @author Marco Belladelli
 * @since envers-rewrite
 */
public enum ModificationType {
	/**
	 * Creation, encoded as 0
	 */
	ADD,
	/**
	 * Modification, encoded as 1
	 */
	MOD,
	/**
	 * Deletion, encoded as 2
	 */
	DEL
}
