/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.legacy;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.audit.AuditLogFactory;

/**
 * Legacy compatibility factory mirroring
 * {@code org.hibernate.envers.AuditReaderFactory}.
 * <p>
 * Migrate to {@link AuditLogFactory} instead.
 *
 * @deprecated Use {@link AuditLogFactory}
 */
@Deprecated(forRemoval = true)
public final class AuditReaderFactory {
	private AuditReaderFactory() {
	}

	/**
	 * Create an {@link AuditReader} for the given session.
	 *
	 * @param session the Hibernate session
	 * @return the audit reader (must be closed by the caller)
	 */
	public static AuditReader get(Session session) {
		return (AuditReader) AuditLogFactory.create( session );
	}

	/**
	 * Create an {@link AuditReader} for the given entity manager.
	 *
	 * @param entityManager the JPA entity manager
	 * @return the audit reader (must be closed by the caller)
	 */
	public static AuditReader get(EntityManager entityManager) {
		return get( entityManager.unwrap( Session.class ) );
	}
}
