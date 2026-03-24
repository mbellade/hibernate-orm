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
import org.hibernate.audit.AuditLog;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for @Audited behavior with entity associations.
 * Ordered from base cases to most complex scenarios.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditToOneAssociationTest.Publisher.class,
		AuditToOneAssociationTest.Author.class,
		AuditToOneAssociationTest.Book.class,
		AuditToOneAssociationTest.LazyBook.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.AuditToOneAssociationTest$TxIdSupplier"))
class AuditToOneAssociationTest {
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

	// ---- 1. Write side ----

	/**
	 * Test ManyToOne write: verify FK value is stored in the audit table
	 * and changes to the association are tracked across revisions.
	 */
	@Test
	void testManyToOneWriteSide(SessionFactoryScope scope) {
		currentTxId = 0;

		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author( 1L, "Author A" );
			session.persist( author );
			session.persist( new Book( 1L, "Book 1", author ) );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			var authorB = new Author( 2L, "Author B" );
			session.persist( authorB );
			session.find( Book.class, 1L ).setAuthor( authorB );
		} );

		scope.getSessionFactory().inTransaction( session ->
			session.find( Book.class, 1L ).setAuthor( null )
		);

		assertEquals( 3, scope.getSessionFactory().getAuditLog()
				.getRevisions( Book.class, 1L ).size() );
	}

	// ---- 2. Null association ----

	/**
	 * Test ManyToOne with null association: verify null FK is correctly
	 * stored and read back from the audit table.
	 */
	@Test
	void testManyToOneNullAssociation(SessionFactoryScope scope) {
		currentTxId = 300;

		scope.getSessionFactory().inTransaction( session ->
			session.persist( new Book( 30L, "Orphan Book", null ) )
		);

		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 301 ).openStatelessSession() ) {
			assertNull( s.get( Book.class, 30L ).getAuthor(), "Author should be null" );
		}
	}

	// ---- 3. Point-in-time reads (single level) ----

	/**
	 * Test ManyToOne read: verify point-in-time reads resolve
	 * the associated entity at the correct historical state.
	 */
	@Test
	void testManyToOnePointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author( 10L, "Original Author" );
			session.persist( author );
			session.persist( new Book( 10L, "My Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session ->
			session.find( Author.class, 10L ).setName( "Renamed Author" )
		);

		scope.getSessionFactory().inTransaction( session -> {
			var newAuthor = new Author( 11L, "New Author" );
			session.persist( newAuthor );
			session.find( Book.class, 10L ).setAuthor( newAuthor );
		} );

		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 101 ).openStatelessSession() ) {
			assertEquals( "Original Author", s.get( Book.class, 10L ).getAuthor().getName() );
		}

		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 102 ).openStatelessSession() ) {
			assertEquals( "Renamed Author", s.get( Book.class, 10L ).getAuthor().getName() );
		}

		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 103 ).openStatelessSession() ) {
			var book = s.get( Book.class, 10L );
			assertEquals( 11L, book.getAuthor().id );
			assertEquals( "New Author", book.getAuthor().getName() );
		}
	}

	// ---- 4. Point-in-time reads (nested: Book → Author → Publisher) ----

	@Test
	void testNestedAssociationPointInTime(SessionFactoryScope scope) {
		currentTxId = 700;

		scope.getSessionFactory().inTransaction( session -> {
			var pub = new Publisher( 70L, "PIT Pub V1" );
			session.persist( pub );
			var author = new Author( 70L, "PIT Author V1", pub );
			session.persist( author );
			session.persist( new Book( 70L, "PIT Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			session.find( Publisher.class, 70L ).setName( "PIT Pub V2" );
			session.find( Author.class, 70L ).setName( "PIT Author V2" );
			session.find( Book.class, 70L ).setTitle( "PIT Book v2" );
		} );

		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 701 ).openStatelessSession() ) {
			var book = s.get( Book.class, 70L );
			assertEquals( "PIT Book", book.getTitle() );
			assertEquals( "PIT Author V1", book.getAuthor().getName() );
			assertEquals( "PIT Pub V1", book.getAuthor().getPublisher().getName() );
		}

		try ( var s = scope.getSessionFactory().withStatelessOptions()
				.atTransaction( 702 ).openStatelessSession() ) {
			var book = s.get( Book.class, 70L );
			assertEquals( "PIT Book v2", book.getTitle() );
			assertEquals( "PIT Author V2", book.getAuthor().getName() );
			assertEquals( "PIT Pub V2", book.getAuthor().getPublisher().getName() );
		}
	}

	// ---- 5. All-revisions via getHistory() (select-fetch, single level) ----

	@Test
	void testManyToOneAllRevisionsMode(SessionFactoryScope scope) {
		currentTxId = 200;

		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author( 20L, "Author V1" );
			session.persist( author );
			session.persist( new Book( 20L, "History Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			session.find( Author.class, 20L ).setName( "Author V2" );
			session.find( Book.class, 20L ).setTitle( "History Book v2" );
		} );

		var history = scope.getSessionFactory().getAuditLog().getHistory( Book.class, 20L );
		assertEquals( 2, history.size() );

		assertEquals( ModificationType.ADD, history.get( 0 ).modificationType() );
		assertEquals( "History Book", history.get( 0 ).entity().getTitle() );
		assertEquals( "Author V1", history.get( 0 ).entity().getAuthor().getName() );

		assertEquals( ModificationType.MOD, history.get( 1 ).modificationType() );
		assertEquals( "History Book v2", history.get( 1 ).entity().getTitle() );
		assertEquals( "Author V2", history.get( 1 ).entity().getAuthor().getName() );
	}

	// ---- 6. All-revisions via getHistory() (select-fetch, nested) ----

	@Test
	void testNestedAssociationGetHistory(SessionFactoryScope scope) {
		currentTxId = 800;

		scope.getSessionFactory().inTransaction( session -> {
			var pub = new Publisher( 80L, "Hist Pub V1" );
			session.persist( pub );
			var author = new Author( 80L, "Hist Author V1", pub );
			session.persist( author );
			session.persist( new Book( 80L, "Hist Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			session.find( Publisher.class, 80L ).setName( "Hist Pub V2" );
			session.find( Author.class, 80L ).setName( "Hist Author V2" );
			session.find( Book.class, 80L ).setTitle( "Hist Book v2" );
		} );

		var history = scope.getSessionFactory().getAuditLog().getHistory( Book.class, 80L );
		assertEquals( 2, history.size() );

		assertEquals( "Hist Book", history.get( 0 ).entity().getTitle() );
		assertEquals( "Hist Author V1", history.get( 0 ).entity().getAuthor().getName() );
		assertEquals( "Hist Pub V1", history.get( 0 ).entity().getAuthor().getPublisher().getName() );

		assertEquals( "Hist Book v2", history.get( 1 ).entity().getTitle() );
		assertEquals( "Hist Author V2", history.get( 1 ).entity().getAuthor().getName() );
		assertEquals( "Hist Pub V2", history.get( 1 ).entity().getAuthor().getPublisher().getName() );
	}

	// ---- 7. Join-fetch in all-revisions mode (single level) ----

	@Test
	void testManyToOneJoinFetchAllRevisions(SessionFactoryScope scope) {
		currentTxId = 500;

		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author( 50L, "JF Author V1" );
			session.persist( author );
			session.persist( new Book( 50L, "JF Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			session.find( Author.class, 50L ).setName( "JF Author V2" );
			session.find( Book.class, 50L ).setTitle( "JF Book v2" );
		} );

		try ( var session = scope.getSessionFactory().withOptions()
				.atTransaction( AuditLog.ALL_REVISIONS ).openSession() ) {
			final var rows = session.createSelectionQuery(
					"select e, transactionId(e), modificationType(e)"
							+ " from AuditBook e join fetch e.author"
							+ " where e.id = :id"
							+ " order by transactionId(e)",
					Object[].class
			).setParameter( "id", 50L ).getResultList();

			assertEquals( 2, rows.size() );
			assertEquals( "JF Author V1", ((Book) rows.get( 0 )[0]).getAuthor().getName() );
			assertEquals( "JF Author V2", ((Book) rows.get( 1 )[0]).getAuthor().getName() );
		}
	}

	// ---- 8. Join-fetch in all-revisions mode (nested: Book → Author → Publisher) ----

	@Test
	void testNestedJoinFetchAllRevisions(SessionFactoryScope scope) {
		currentTxId = 600;

		scope.getSessionFactory().inTransaction( session -> {
			var pub = new Publisher( 60L, "Pub V1" );
			session.persist( pub );
			var author = new Author( 60L, "Nested Author V1", pub );
			session.persist( author );
			session.persist( new Book( 60L, "Nested Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			session.find( Publisher.class, 60L ).setName( "Pub V2" );
			session.find( Author.class, 60L ).setName( "Nested Author V2" );
			session.find( Book.class, 60L ).setTitle( "Nested Book v2" );
		} );

		try ( var session = scope.getSessionFactory().withOptions()
				.atTransaction( AuditLog.ALL_REVISIONS ).openSession() ) {
			final var rows = session.createSelectionQuery(
					"select e, transactionId(e), modificationType(e)"
							+ " from AuditBook e"
							+ " join fetch e.author a"
							+ " join fetch a.publisher"
							+ " where e.id = :id"
							+ " order by transactionId(e)",
					Object[].class
			).setParameter( "id", 60L ).getResultList();

			assertEquals( 2, rows.size() );

			var book1 = (Book) rows.get( 0 )[0];
			assertEquals( "Nested Book", book1.getTitle() );
			assertEquals( "Nested Author V1", book1.getAuthor().getName() );
			assertEquals( "Pub V1", book1.getAuthor().getPublisher().getName() );

			var book2 = (Book) rows.get( 1 )[0];
			assertEquals( "Nested Book v2", book2.getTitle() );
			assertEquals( "Nested Author V2", book2.getAuthor().getName() );
			assertEquals( "Pub V2", book2.getAuthor().getPublisher().getName() );
		}
	}

	// ---- 9. Lazy proxy with temporal capture ----

	@Test
	void testLazyManyToOnePointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 400;

		scope.getSessionFactory().inTransaction( session -> {
			var author = new Author( 40L, "Lazy Author V1" );
			session.persist( author );
			session.persist( new LazyBook( 40L, "Lazy Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session ->
			session.find( Author.class, 40L ).setName( "Lazy Author V2" )
		);

		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 401 ).openSession() ) {
			assertEquals( "Lazy Author V1", s.find( LazyBook.class, 40L ).getAuthor().getName() );
		}

		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 402 ).openSession() ) {
			assertEquals( "Lazy Author V2", s.find( LazyBook.class, 40L ).getAuthor().getName() );
		}
	}

	// ---- 10. Lazy proxy with nested associations ----

	@Test
	void testLazyNestedPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 900;

		scope.getSessionFactory().inTransaction( session -> {
			var pub = new Publisher( 90L, "Lazy Pub V1" );
			session.persist( pub );
			var author = new Author( 90L, "Lazy Nested Author V1", pub );
			session.persist( author );
			session.persist( new LazyBook( 90L, "Lazy Nested Book", author ) );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			session.find( Publisher.class, 90L ).setName( "Lazy Pub V2" );
			session.find( Author.class, 90L ).setName( "Lazy Nested Author V2" );
			session.find( LazyBook.class, 90L ).setTitle( "Lazy Nested Book v2" );
		} );

		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 901 ).openSession() ) {
			var author = s.find( LazyBook.class, 90L ).getAuthor();
			assertEquals( "Lazy Nested Author V1", author.getName() );
			assertEquals( "Lazy Pub V1", author.getPublisher().getName() );
		}

		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 902 ).openSession() ) {
			var author = s.find( LazyBook.class, 90L ).getAuthor();
			assertEquals( "Lazy Nested Author V2", author.getName() );
			assertEquals( "Lazy Pub V2", author.getPublisher().getName() );
		}
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "AuditPublisher")
	static class Publisher {
		@Id
		long id;
		String name;

		Publisher() {}

		Publisher(long id, String name) {
			this.id = id;
			this.name = name;
		}

		String getName() {
			return name;
		}

		void setName(String name) {
			this.name = name;
		}
	}

	@Audited
	@Entity(name = "AuditAuthor")
	static class Author {
		@Id
		long id;
		String name;
		@ManyToOne
		Publisher publisher;

		Author() {}

		Author(long id, String name) {
			this.id = id;
			this.name = name;
		}

		Author(long id, String name, Publisher publisher) {
			this.id = id;
			this.name = name;
			this.publisher = publisher;
		}

		String getName() {
			return name;
		}

		void setName(String name) {
			this.name = name;
		}

		Publisher getPublisher() {
			return publisher;
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

		Book() {}

		Book(long id, String title, Author author) {
			this.id = id;
			this.title = title;
			this.author = author;
		}

		String getTitle() {
			return title;
		}

		void setTitle(String title) {
			this.title = title;
		}

		Author getAuthor() {
			return author;
		}

		void setAuthor(Author author) {
			this.author = author;
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

		LazyBook() {}

		LazyBook(long id, String title, Author author) {
			this.id = id;
			this.title = title;
			this.author = author;
		}

		String getTitle() {
			return title;
		}

		void setTitle(String title) {
			this.title = title;
		}

		Author getAuthor() {
			return author;
		}
	}
}
