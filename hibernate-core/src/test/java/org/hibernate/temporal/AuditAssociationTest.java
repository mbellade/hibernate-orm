/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.ModificationType;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for @Audited behavior with entity associations.
 * Investigates ManyToOne write-side and read-side behavior,
 * including point-in-time and all-revisions modes.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditAssociationTest.Author.class,
		AuditAssociationTest.Book.class,
		AuditAssociationTest.LazyBook.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.AuditAssociationTest$TxIdSupplier"))
class AuditAssociationTest {
	private static int currentTxId;

	public static class TxIdSupplier implements TransactionIdentifierSupplier<Integer> {
		@Override
		public Integer getTransactionIdentifier(SharedSessionContractImplementor session) {
			return ++currentTxId;
		}

		@Override
		public Class<Integer> getIdentifierType() {
			return Integer.class;
		}
	}

	/**
	 * Test ManyToOne write: verify FK value is stored in the audit table
	 * and changes to the association are tracked across revisions.
	 */
	@Test
	void testManyToOneWriteSide(SessionFactoryScope scope) {
		currentTxId = 0;

		// tx 1: create author and book with association
		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author();
			author.id = 1L;
			author.name = "Author A";
			session.persist( author );

			var book = new Book();
			book.id = 1L;
			book.title = "Book 1";
			book.author = author;
			session.persist( book );
		} );

		// tx 2: change the association (reassign to different author)
		scope.getSessionFactory().inTransaction( session -> {
			var authorB = new Author();
			authorB.id = 2L;
			authorB.name = "Author B";
			session.persist( authorB );

			var book = session.find( Book.class, 1L );
			book.author = authorB;
		} );

		// tx 3: nullify the association
		scope.getSessionFactory().inTransaction( session -> {
			var book = session.find( Book.class, 1L );
			book.author = null;
		} );

		// Verify audit history via AuditLog
		var auditLog = scope.getSessionFactory().getAuditLog();
		var revisions = auditLog.getRevisions( Book.class, 1L );
		assertEquals( 3, revisions.size(), "Book should have 3 revisions" );
	}

	/**
	 * Test ManyToOne read: verify point-in-time reads resolve
	 * the associated entity at the correct historical state.
	 */
	@Test
	void testManyToOnePointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		// tx 101: create author and book
		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author();
			author.id = 10L;
			author.name = "Original Author";
			session.persist( author );

			var book = new Book();
			book.id = 10L;
			book.title = "My Book";
			book.author = author;
			session.persist( book );
		} );

		// tx 102: update the author's name
		scope.getSessionFactory().inTransaction( session -> {
			var author = session.find( Author.class, 10L );
			author.name = "Renamed Author";
		} );

		// tx 103: reassign book to a new author
		scope.getSessionFactory().inTransaction( session -> {
			var newAuthor = new Author();
			newAuthor.id = 11L;
			newAuthor.name = "New Author";
			session.persist( newAuthor );

			var book = session.find( Book.class, 10L );
			book.author = newAuthor;
		} );

		// Read book at tx 101: should have original author with original name
		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 101 ).openStatelessSession() ) {
			var book = s.get( Book.class, 10L );
			assertNotNull( book, "Book should exist at tx 101" );
			assertNotNull( book.author, "Book should have an author at tx 101" );
			assertEquals( "Original Author", book.author.name,
					"Author should have original name at tx 101" );
		}

		// Read book at tx 102: book unchanged, but author name changed
		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 102 ).openStatelessSession() ) {
			var book = s.get( Book.class, 10L );
			assertNotNull( book );
			assertNotNull( book.author );
			assertEquals( "Renamed Author", book.author.name,
					"Author should have renamed name at tx 102" );
		}

		// Read book at tx 103: book now points to new author
		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 103 ).openStatelessSession() ) {
			var book = s.get( Book.class, 10L );
			assertNotNull( book );
			assertNotNull( book.author );
			assertEquals( 11L, book.author.id,
					"Book should point to new author at tx 103" );
			assertEquals( "New Author", book.author.name );
		}
	}

	/**
	 * Test ManyToOne in all-revisions mode: associations are loaded
	 * from the audit table at the same revision as the parent entity.
	 * The per-row temporal identifier is set via the REV_TXN column
	 * that is implicitly selected for audited entities.
	 */
	@Test
	void testManyToOneAllRevisionsMode(SessionFactoryScope scope) {
		currentTxId = 200;

		// tx 201: create author and book
		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author();
			author.id = 20L;
			author.name = "Author V1";
			session.persist( author );

			var book = new Book();
			book.id = 20L;
			book.title = "History Book";
			book.author = author;
			session.persist( book );
		} );

		// tx 202: update author name + book title
		scope.getSessionFactory().inTransaction( session -> {
			var author = session.find( Author.class, 20L );
			author.name = "Author V2";

			var book = session.find( Book.class, 20L );
			book.title = "History Book v2";
		} );

		// Get full history of the book
		var history = scope.getSessionFactory().getAuditLog()
				.getHistory( Book.class, 20L );

		assertEquals( 2, history.size(), "Book should have 2 revisions (tx 201 ADD, tx 202 MOD)" );

		// tx 201: ADD — book with author at V1 name
		assertEquals( ModificationType.ADD, history.get( 0 ).modificationType() );
		assertEquals( "History Book", history.get( 0 ).entity().title );
		assertNotNull( history.get( 0 ).entity().author,
				"Author should be loaded from audit table" );
		assertEquals( "Author V1", history.get( 0 ).entity().author.name,
				"Author should reflect the name at tx 201, not the current name" );

		// tx 202: MOD — updated title, author at V2 name
		assertEquals( ModificationType.MOD, history.get( 1 ).modificationType() );
		assertEquals( "History Book v2", history.get( 1 ).entity().title );
		assertNotNull( history.get( 1 ).entity().author,
				"Author should be loaded from audit table" );
		assertEquals( "Author V2", history.get( 1 ).entity().author.name,
				"Author should reflect the name at tx 202" );
	}

	/**
	 * Test ManyToOne with null association: verify null FK is correctly
	 * stored and read back from the audit table.
	 */
	@Test
	void testManyToOneNullAssociation(SessionFactoryScope scope) {
		currentTxId = 300;

		// tx 301: create book without author
		scope.getSessionFactory().inTransaction( session -> {
			var book = new Book();
			book.id = 30L;
			book.title = "Orphan Book";
			session.persist( book );
		} );

		// Read at tx 301
		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 301 ).openStatelessSession() ) {
			var book = s.get( Book.class, 30L );
			assertNotNull( book );
			assertNull( book.author, "Author should be null" );
		}
	}

	/**
	 * Test lazy ManyToOne in point-in-time mode: the proxy should
	 * initialize from the audit table at the captured revision,
	 * not from the current table.
	 */
	@Test
	void testLazyManyToOnePointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 400;

		// tx 401: create author and lazy book
		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author();
			author.id = 40L;
			author.name = "Lazy Author V1";
			session.persist( author );

			var book = new LazyBook();
			book.id = 40L;
			book.title = "Lazy Book";
			book.author = author;
			session.persist( book );
		} );

		// tx 402: update the author's name
		scope.getSessionFactory().inTransaction( session -> {
			var author = session.find( Author.class, 40L );
			author.name = "Lazy Author V2";
		} );

		// Read lazy book at tx 401: proxy should load author from audit table at tx 401
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 401 ).openSession() ) {
			var book = s.find( LazyBook.class, 40L );
			assertNotNull( book, "LazyBook should exist at tx 401" );
			// Accessing getAuthor().getName() triggers proxy initialization —
			// the proxy should use the captured temporal identifier (401)
			// to load from the audit table, getting "Lazy Author V1"
			var author = book.getAuthor();
			assertNotNull( author, "Author should not be null" );
			assertEquals( "Lazy Author V1", author.getName(),
					"Lazy proxy should load author at tx 401, not current state" );
		}

		// Read lazy book at tx 402: author name should reflect update
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 402 ).openSession() ) {
			var book = s.find( LazyBook.class, 40L );
			assertNotNull( book );
			var author = book.getAuthor();
			assertNotNull( author );
			assertEquals( "Lazy Author V2", author.getName(),
					"Lazy proxy should load author at tx 402" );
		}
	}

	@Audited
	@Entity(name = "AuditAuthor")
	static class Author {
		@Id
		long id;
		String name;

		String getName() {
			return name;
		}
	}

	@Audited
	@Entity(name = "AuditBook")
	static class Book {
		@Id
		long id;
		String title;
		@ManyToOne
		Author author;

		Author getAuthor() {
			return author;
		}
	}

	@Audited
	@Entity(name = "AuditLazyBook")
	static class LazyBook {
		@Id
		long id;
		String title;
		@ManyToOne(fetch = FetchType.LAZY)
		Author author;

		Author getAuthor() {
			return author;
		}
	}
}
