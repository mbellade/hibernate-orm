/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.RevisionEntitySupplier;
import org.hibernate.audit.RevisionListener;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test demonstrating the {@link RevisionEntitySupplier} with a
 * custom revision entity and {@link RevisionListener}, similar
 * to the Envers {@code @RevisionEntity} + {@code RevisionListener}
 * pattern.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditRevisionEntityTest.MyEntity.class,
		AuditRevisionEntityTest.RevisionInfo.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.AuditRevisionEntityTest$RevisionInfoSupplier"))
class AuditRevisionEntityTest {

	/**
	 * Custom revision entity — maps to a REVINFO-like table.
	 */
	@Entity(name = "RevisionInfo")
	@Table(name = "REVINFO")
	static class RevisionInfo {
		@Id
		@GeneratedValue
		@Column(name = "REV")
		int id;

		@Column(name = "REVTSTMP")
		long timestamp;

		@Column(name = "USERNAME")
		String username;
	}

	/**
	 * Built-in supplier subclass with a {@link RevisionListener}
	 * that populates the revision entity's custom fields.
	 */
	public static class RevisionInfoSupplier extends RevisionEntitySupplier<Integer> {
		public RevisionInfoSupplier() {
			super( RevisionInfo.class, rev -> {
				final var revInfo = (RevisionInfo) rev;
				revInfo.timestamp = Instant.now().toEpochMilli();
				revInfo.username = "test-user";
			} );
		}
	}

	@Audited
	@Entity(name = "MyEntity")
	static class MyEntity {
		@Id
		long id;
		String name;
	}

	@Test
	void testRevisionEntitySupplier(SessionFactoryScope scope) {
		// Create
		scope.getSessionFactory().inTransaction( session -> {
			final var entity = new MyEntity();
			entity.id = 1L;
			entity.name = "original";
			session.persist( entity );
		} );

		// Update
		scope.getSessionFactory().inTransaction( session -> {
			final var entity = session.find( MyEntity.class, 1L );
			entity.name = "updated";
		} );

		// Delete
		scope.getSessionFactory().inTransaction( session -> {
			final var entity = session.find( MyEntity.class, 1L );
			session.remove( entity );
		} );

		// Verify REVINFO rows were created
		scope.getSessionFactory().inTransaction( session -> {
			final var revisions = session.createSelectionQuery(
					"from RevisionInfo order by id", RevisionInfo.class
			).getResultList();
			assertEquals( 3, revisions.size() );
			for ( var rev : revisions ) {
				assertEquals( "test-user", rev.username );
				assertTrue( rev.timestamp > 0 );
			}

			final int rev1 = revisions.get( 0 ).id;
			final int rev2 = revisions.get( 1 ).id;
			final int rev3 = revisions.get( 2 ).id;

			// Read at revision 1 — entity was created
			try ( var s = scope.getSessionFactory().withOptions().atTransaction( rev1 ).open() ) {
				final var entity = s.find( MyEntity.class, 1L );
				assertNotNull( entity );
				assertEquals( "original", entity.name );
			}

			// Read at revision 2 — entity was updated
			try ( var s = scope.getSessionFactory().withOptions().atTransaction( rev2 ).open() ) {
				final var entity = s.find( MyEntity.class, 1L );
				assertNotNull( entity );
				assertEquals( "updated", entity.name );
			}

			// Read at revision 3 — entity was deleted
			try ( var s = scope.getSessionFactory().withOptions().atTransaction( rev3 ).open() ) {
				final var entity = s.find( MyEntity.class, 1L );
				assertNull( entity );
			}
		} );
	}
}
