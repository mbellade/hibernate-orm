/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.DefaultRevisionEntity;
import org.hibernate.audit.DefaultRevisionEntitySupplier;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the built-in {@link DefaultRevisionEntity} and
 * {@link DefaultRevisionEntitySupplier}, which provide
 * backwards-compatible behavior matching envers'
 * {@code DefaultRevisionEntity}.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		DefaultRevisionEntityTest.Book.class,
		DefaultRevisionEntity.class
})
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.audit.DefaultRevisionEntitySupplier"
))
class DefaultRevisionEntityTest {

	@Audited
	@Entity(name = "Book")
	static class Book {
		@Id
		long id;
		String title;
	}

	@Test
	void testDefaultRevisionEntity(SessionFactoryScope scope) {
		final long beforeTest = System.currentTimeMillis();

		// Create
		scope.getSessionFactory().inTransaction( session -> {
			final var book = new Book();
			book.id = 1L;
			book.title = "Original Title";
			session.persist( book );
		} );

		// Update
		scope.getSessionFactory().inTransaction( session -> {
			final var book = session.find( Book.class, 1L );
			book.title = "Updated Title";
		} );

		// Delete
		scope.getSessionFactory().inTransaction( session -> {
			final var book = session.find( Book.class, 1L );
			session.remove( book );
		} );

		// Verify REVINFO rows
		scope.getSessionFactory().inTransaction( session -> {
			final var revisions = session.createSelectionQuery(
					"from DefaultRevisionEntity order by id", DefaultRevisionEntity.class
			).getResultList();
			assertEquals( 3, revisions.size() );

			for ( var rev : revisions ) {
				assertTrue( rev.getTimestamp() >= beforeTest,
						"Timestamp should be >= test start time" );
				assertNotNull( rev.getRevisionDate() );
			}

			final int rev1 = revisions.get( 0 ).getId();
			final int rev2 = revisions.get( 1 ).getId();
			final int rev3 = revisions.get( 2 ).getId();

			// Verify sequential revision numbers
			assertTrue( rev1 < rev2 );
			assertTrue( rev2 < rev3 );

			// Read at rev1 — entity was created
			try ( var s = scope.getSessionFactory().withOptions()
					.atTransaction( rev1 ).open() ) {
				final var book = s.find( Book.class, 1L );
				assertNotNull( book );
				assertEquals( "Original Title", book.title );
			}

			// Read at rev2 — entity was updated
			try ( var s = scope.getSessionFactory().withOptions()
					.atTransaction( rev2 ).open() ) {
				final var book = s.find( Book.class, 1L );
				assertNotNull( book );
				assertEquals( "Updated Title", book.title );
			}

			// Read at rev3 — entity was deleted
			try ( var s = scope.getSessionFactory().withOptions()
					.atTransaction( rev3 ).open() ) {
				final var book = s.find( Book.class, 1L );
				assertNull( book );
			}
		} );
	}

}
