/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.bytecode.enhancement.graph;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Subgraph;
import org.hibernate.Hibernate;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.jpa.HibernateHints;
import org.hibernate.jpa.SpecHints;
import org.hibernate.testing.bytecode.enhancement.extension.BytecodeEnhanced;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DomainModel(annotatedClasses = {
		FetchGraphReadOnlyNpeTest.Account.class,
		FetchGraphReadOnlyNpeTest.Address.class,
		FetchGraphReadOnlyNpeTest.AddressAssignment.class,
		FetchGraphReadOnlyNpeTest.Booking.class,
		FetchGraphReadOnlyNpeTest.BookingAssignment.class,
		FetchGraphReadOnlyNpeTest.Store.class,
		FetchGraphReadOnlyNpeTest.UserName.class
})
@SessionFactory
// We need to disable max fetch depth to trigger the original problem
@ServiceRegistry(settings = {@Setting(name = AvailableSettings.MAX_FETCH_DEPTH, value = "")})
@BytecodeEnhanced
@Jira("https://hibernate.atlassian.net/browse/HHH-20251")
public class FetchGraphReadOnlyNpeTest {

	@BeforeAll
	void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final var booking1 = new Booking( 1L );
			final var booking2 = new Booking( 2L );
			session.persist( booking1 );
			session.persist( booking2 );

			final var userName1 = new UserName( 1L );
			final var userName2 = new UserName( 2L );
			session.persist( userName1 );
			session.persist( userName2 );

			final var account = new Account( 1L, userName1 );
			session.persist( account );

			final var store = new Store( 1L, booking2 );
			session.persist( store );

			final var address1 = new Address( 1L, store );
			final var address2 = new Address( 2L, store );
			session.persist( address1 );
			session.persist( address2 );

			session.persist( new AddressAssignment( 1L, account, booking2, address1, userName2 ) );
			session.persist( new AddressAssignment( 2L, account, booking2, address2, userName1 ) );

			session.persist( new BookingAssignment( 1L, account, booking1 ) );
			session.persist( new BookingAssignment( 2L, account, booking2 ) );
		} );
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void testHibernateBugWithQuery(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final var query = session.createQuery( "select a from Account a where a.id = :id", Account.class )
					.setParameter( "id", 1L )
					.setHint( HibernateHints.HINT_READ_ONLY, true )
					.setHint( SpecHints.HINT_SPEC_FETCH_GRAPH, createFetchGraph( session ) );
			final var account = query.getSingleResult();

			assertThat( account.id ).isEqualTo( 1L );

			// userName should be fetched via the graph
			assertThat( Hibernate.isInitialized( account.userName ) ).isTrue();
			assertThat( account.userName.id ).isEqualTo( 1L );

			// addressAssignments should be fetched via the graph
			assertThat( Hibernate.isInitialized( account.addressAssignments ) ).isTrue();
			assertThat( account.addressAssignments ).hasSize( 2 );
			for ( AddressAssignment aa : account.addressAssignments ) {
				assertThat( Hibernate.isInitialized( aa.userName ) ).isTrue();
				assertThat( Hibernate.isInitialized( aa.address ) ).isTrue();
				assertThat( Hibernate.isInitialized( aa.address.store ) ).isTrue();
				assertThat( Hibernate.isInitialized( aa.booking ) ).isTrue();
			}
		} );
	}

	private EntityGraph<Account> createFetchGraph(SessionImplementor session) {
		final EntityGraph<Account> fetchGraph = session.createEntityGraph( Account.class );
		fetchGraph.addSubgraph( "userName" );
		final Subgraph<AddressAssignment> addressAssignmentSubgraph = fetchGraph.addSubgraph( "addressAssignments" );
		addressAssignmentSubgraph.addSubgraph( "userName" );
		addressAssignmentSubgraph.addSubgraph( "address" ).addSubgraph( "store" );
		addressAssignmentSubgraph.addSubgraph( "booking" );
		return fetchGraph;
	}

	@MappedSuperclass
	public static abstract class AbstractIdEntity {
		@Id
		protected Long id;
	}

	@Entity(name = "Account")
	public static class Account extends AbstractIdEntity {
		@OneToOne(fetch = FetchType.LAZY)
		private UserName userName;

		@OneToMany(fetch = FetchType.LAZY, mappedBy = "account")
		private Set<AddressAssignment> addressAssignments = new HashSet<>();

		@OneToMany(fetch = FetchType.LAZY, mappedBy = "account")
		private Set<BookingAssignment> bookingAssignments = new HashSet<>();

		public Account() {
		}

		public Account(Long id, UserName userName) {
			this.id = id;
			this.userName = userName;
		}
	}

	@Entity(name = "UserName")
	public static class UserName extends AbstractIdEntity {
		@OneToOne(mappedBy = "userName", fetch = FetchType.LAZY)
		private Account device;

		@OneToOne(mappedBy = "userName", fetch = FetchType.LAZY)
		private AddressAssignment addressAssignment;

		public UserName() {
		}

		public UserName(Long id) {
			this.id = id;
		}
	}

	@Entity(name = "AddressAssignment")
	public static class AddressAssignment extends AbstractIdEntity {
		@OneToOne
		private UserName userName;

		@OneToOne
		private Address address;

		@ManyToOne
		private Booking booking;

		@ManyToOne
		private Account account;

		public AddressAssignment() {
		}

		public AddressAssignment(Long id, Account account, Booking booking, Address address, UserName userName) {
			this.id = id;
			this.account = account;
			this.booking = booking;
			this.address = address;
			this.userName = userName;
		}
	}

	@Entity(name = "Address")
	public static class Address extends AbstractIdEntity {
		@ManyToOne
		private Store store;

		@OneToOne(mappedBy = "address", fetch = FetchType.LAZY)
		private AddressAssignment addressAssignment;

		public Address() {
		}

		public Address(Long id, Store store) {
			this.id = id;
			this.store = store;
		}
	}

	@Entity(name = "Store")
	public static class Store extends AbstractIdEntity {
		@ManyToOne(fetch = FetchType.LAZY)
		private Booking booking;

		public Store() {
		}

		public Store(Long id, Booking booking) {
			this.id = id;
			this.booking = booking;
		}
	}

	@Entity(name = "Booking")
	public static class Booking extends AbstractIdEntity {
		@OneToMany(fetch = FetchType.LAZY, mappedBy = "booking")
		private Set<Store> stores = new HashSet<>();

		public Booking() {
		}

		public Booking(Long id) {
			this.id = id;
		}
	}

	@Entity(name = "BookingAssignment")
	public static class BookingAssignment extends AbstractIdEntity {
		@ManyToOne
		private Account account;

		@ManyToOne
		private Booking booking;

		public BookingAssignment() {
		}

		public BookingAssignment(Long id, Account account, Booking booking) {
			this.id = id;
			this.account = account;
			this.booking = booking;
		}
	}
}
