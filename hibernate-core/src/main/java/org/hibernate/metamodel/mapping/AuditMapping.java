/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.metamodel.mapping;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.hibernate.Incubating;

/**
 * Metadata about audit log tables for entities and collections enabled for audit logging.
 *
 * @see org.hibernate.annotations.Audited
 *
 * @author Gavin King
 *
 * @since 7.4
 */
@Incubating
public interface AuditMapping extends AuxiliaryMapping {

	/**
	 * Get the transaction ID selectable mapping for the given original table.
	 */
	SelectableMapping getTransactionIdMapping(String originalTableName);

	/**
	 * Get the modification type selectable mapping for the given original table,
	 * or {@code null} if the table does not carry a modification type column.
	 */
	@Nullable SelectableMapping getModificationTypeMapping(String originalTableName);

	/**
	 * Get the transaction end selectable mapping for the given original table,
	 * or {@code null} if the validity audit strategy is not active.
	 *
	 * @since envers-rewrite
	 */
	@Nullable SelectableMapping getTransactionEndMapping(String originalTableName);

	/**
	 * Get the transaction end timestamp selectable mapping for the given original table,
	 * or {@code null} if not configured.
	 *
	 * @since envers-rewrite
	 */
	@Nullable SelectableMapping getTransactionEndTimestampMapping(String originalTableName);

}
