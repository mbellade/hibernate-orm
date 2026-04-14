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
 * Marks a {@code Set<String>} property on a
 * {@link RevisionEntity @RevisionEntity} that holds the names
 * of entity types modified in each revision. The property is
 * typically mapped as an {@code @ElementCollection} to a
 * {@code REVCHANGES} table.
 * <p>
 * When this annotation is present on a revision entity,
 * cross-type revision queries are automatically enabled via
 * {@link AuditLog#getEntityTypesModifiedAt},
 * {@link AuditLog#findAllEntitiesModifiedAt}, and
 * {@link AuditLog#findAllEntitiesGroupedByModificationType}.
 *
 * @see TrackingModifiedEntitiesRevisionMapping
 * @see DefaultTrackingModifiedEntitiesRevisionEntity
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface ModifiedEntityNames {
}
