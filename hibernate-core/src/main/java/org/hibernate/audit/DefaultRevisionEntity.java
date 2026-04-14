/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.Incubating;

/**
 * A built-in revision entity that matches the schema of
 * {@code org.hibernate.envers.DefaultRevisionEntity}, providing
 * a seamless migration path for envers users.
 * <p>
 * Maps to the {@code REVINFO} table with columns:
 * <ul>
 *   <li>{@code REV}: auto-generated integer primary key</li>
 *   <li>{@code REVTSTMP}: Unix epoch timestamp in milliseconds</li>
 * </ul>
 * <p>
 * To use this entity, add it to the domain model of your application.
 * <p>
 * For entity change tracking (cross-type revision queries),
 * use {@link DefaultTrackingModifiedEntitiesRevisionEntity} instead.
 *
 * @see RevisionEntity
 * @see DefaultTrackingModifiedEntitiesRevisionEntity
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
@RevisionEntity
@Entity(name = "DefaultRevisionEntity")
@Table(name = "REVINFO")
public class DefaultRevisionEntity extends RevisionMapping {
}
