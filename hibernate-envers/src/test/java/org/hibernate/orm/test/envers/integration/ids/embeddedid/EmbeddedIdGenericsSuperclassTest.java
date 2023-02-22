/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.envers.integration.ids.embeddedid;

import java.io.Serializable;
import java.util.Random;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Marco Belladelli
 */
@DomainModel(annotatedClasses = {
		EmbeddedIdGenericsSuperclassTest.Customer.class,
		EmbeddedIdGenericsSuperclassTest.Invoice.class
})
@SessionFactory
@JiraKey("HHH-16188")
public class EmbeddedIdGenericsSuperclassTest {
	@BeforeAll
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final Customer customer = new Customer( 1, "Francisco" );
			session.persist( customer );
			final Invoice invoice = new Invoice( 2 );
			session.persist( invoice );
		} );
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			session.createMutationQuery( "delete from Customer" ).executeUpdate();
			session.createMutationQuery( "delete from Invoice" ).executeUpdate();
		} );
	}

	@Test
	public void testCustomer(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final Customer customer = session.createQuery(
					"from Customer c where c.id.someDomainField = 1",
					Customer.class
			).getSingleResult();
			assertThat( customer ).isNotNull();
			assertThat( customer.getCode() ).isEqualTo( 1 );
			assertThat( customer.getName() ).isEqualTo( "Francisco" );
		} );
	}

	@Test
	public void testInvoice(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final Invoice invoice = session.createQuery(
					"from Invoice a where a.id.someOtherDomainField = 1",
					Invoice.class
			).getSingleResult();
			assertThat( invoice ).isNotNull();
			assertThat( invoice.getSerial() ).isEqualTo( 2 );
		} );
	}

	@Embeddable
	@MappedSuperclass
	public abstract static class DomainEntityId implements Serializable {
		private Long domainId;

		public DomainEntityId() {
			Random random = new Random();
			this.domainId = random.nextLong();
		}
	}

	@MappedSuperclass
	public abstract static class DomainEntityModel<ID extends DomainEntityId> {
		@EmbeddedId
		private ID id;

		protected DomainEntityModel(ID id) {
			this.id = id;
		}

		public ID getId() {
			return id;
		}
	}

	@Embeddable
	public static class CustomerId extends DomainEntityId {
		private int someDomainField;

		public CustomerId() {
			super();
			this.someDomainField = 1;
		}
	}

	@Entity(name = "Customer")
	public static class Customer extends DomainEntityModel<CustomerId> {
		private Integer code;
		private String name;

		public Customer() {
			super( new CustomerId() );
		}

		public Customer(Integer code, String name) {
			this();
			this.code = code;
			this.name = name;
		}

		public Integer getCode() {
			return code;
		}

		public void setCode(Integer code) {
			this.code = code;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}

	@Embeddable
	public static class InvoiceId extends DomainEntityId {
		private int someOtherDomainField;

		public InvoiceId() {
			super();
			this.someOtherDomainField = 1;
		}
	}

	@Entity(name = "Invoice")
	public static class Invoice extends DomainEntityModel<InvoiceId> {
		private Integer serial;

		public Invoice() {
			super( new InvoiceId() );
		}

		public Invoice(Integer serial) {
			this();
			this.serial = serial;
		}

		public Integer getSerial() {
			return serial;
		}

		public void setSerial(Integer serial) {
			this.serial = serial;
		}
	}
}

