/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.hibernate.annotations.Audited;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import org.hibernate.SharedSessionContract;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@SessionFactory
@DomainModel(annotatedClasses = {
		AuditEntityTest.AuditEntity.class,
		AuditEntityTest.EmbeddedEntity.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.AuditEntityTest$TxIdSupplier"))
class AuditEntityTest {
	private static int currentTxId;

	public static class TxIdSupplier implements TransactionIdentifierSupplier<Integer> {
		@Override
		public Integer getTransactionIdentifier(SharedSessionContract session) {
			return ++currentTxId;
		}

	}

	@Test
	void test(SessionFactoryScope scope) {
		scope.getSessionFactory().inTransaction(
				session -> {
					AuditEntity entity = new AuditEntity();
					entity.id = 1L;
					entity.text = "hello";
					entity.stringSet.add( "hello" );
					session.persist( entity );
				}
		);
		scope.getSessionFactory().inTransaction(
				session -> {
					AuditEntity entity = session.find( AuditEntity.class, 1L );
					entity.text = "goodbye";
					entity.stringSet.add( "goodbye" );
				}
		);
		scope.getSessionFactory().inTransaction(
				session -> {
					AuditEntity entity = session.find( AuditEntity.class, 1L );
					session.remove( entity );
				}
		);
		scope.getSessionFactory().inTransaction(
				session -> {
					AuditEntity entity = session.find( AuditEntity.class, 1L );
					assertNull( entity );
				}
		);
		try ( var s = scope.getSessionFactory().withOptions().atTransaction(0).open() ) {
			AuditEntity entity = s.find( AuditEntity.class, 1L );
			assertNull( entity );
			AuditEntity result =
					s.createSelectionQuery( "from AuditEntity where id = 1", AuditEntity.class )
							.getSingleResultOrNull();
			assertNull( result );
		}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction(1).open() ) {
			AuditEntity entity = s.find( AuditEntity.class, 1L );
			assertEquals( "hello", entity.text);
			assertEquals( Set.of("hello"), entity.stringSet);
			AuditEntity result =
					s.createSelectionQuery( "from AuditEntity where id = 1", AuditEntity.class )
							.getSingleResultOrNull();
			assertSame( entity, result );
		}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction(2).open() ) {
			AuditEntity entity = s.find( AuditEntity.class, 1L );
			assertEquals( "goodbye", entity.text);
			assertEquals( Set.of("hello","goodbye"), entity.stringSet );
			AuditEntity result =
					s.createSelectionQuery( "from AuditEntity where id = 1", AuditEntity.class )
							.getSingleResultOrNull();
			assertSame( entity, result );
		}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction(3).open() ) {
			AuditEntity entity = s.find( AuditEntity.class, 1L );
			assertNull( entity );
			AuditEntity result =
					s.createSelectionQuery( "from AuditEntity where id = 1", AuditEntity.class )
							.getSingleResultOrNull();
			assertNull( result );
		}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction(4).open() ) {
			AuditEntity entity = s.find( AuditEntity.class, 1L );
			assertNull( entity );
			AuditEntity result =
					s.createSelectionQuery( "from AuditEntity where id = 1", AuditEntity.class )
							.getSingleResultOrNull();
			assertNull( result );
		}
	}
	@Test
	void testEmbeddedAuditing(SessionFactoryScope scope) {
		currentTxId = 100;

		scope.getSessionFactory().inTransaction( session -> {
			var e = new EmbeddedEntity();
			e.id = 1L;
			e.name = "test";
			e.address = new Address( "123 Main St", "Springfield" );
			session.persist( e );
		} );

		scope.getSessionFactory().inTransaction( session -> {
			var e = session.find( EmbeddedEntity.class, 1L );
			e.address = new Address( "456 Oak Ave", "Shelbyville" );
		} );

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 101 ).open() ) {
			var e = s.find( EmbeddedEntity.class, 1L );
			assertEquals( "123 Main St", e.address.street );
			assertEquals( "Springfield", e.address.city );
		}

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 102 ).open() ) {
			var e = s.find( EmbeddedEntity.class, 1L );
			assertEquals( "456 Oak Ave", e.address.street );
			assertEquals( "Shelbyville", e.address.city );
		}
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "AuditEntity")
	static class AuditEntity {
		@Id
		long id;
		String text;
		@Version
		int version;
		@Audited
		@ElementCollection
		Set<String> stringSet = new HashSet<>();
	}

	@Audited
	@Entity(name = "EmbeddedEntity")
	static class EmbeddedEntity {
		@Id long id;
		String name;
		@Embedded Address address;
	}

	@Embeddable
	static class Address {
		String street;
		String city;
		Address() {}
		Address(String street, String city) { this.street = street; this.city = city; }
	}
}
