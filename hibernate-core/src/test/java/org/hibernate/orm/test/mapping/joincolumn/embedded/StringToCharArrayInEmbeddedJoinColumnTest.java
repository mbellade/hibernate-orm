/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.mapping.joincolumn.embedded;

import java.io.Serializable;
import java.util.List;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Marco Belladelli
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		StringToCharArrayInEmbeddedJoinColumnTest.Vehicle.class,
		StringToCharArrayInEmbeddedJoinColumnTest.VehicleInvoice.class
})
@JiraKey("HHH-16040")
public class StringToCharArrayInEmbeddedJoinColumnTest {
	@BeforeAll
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			Vehicle vehicle = new Vehicle();
			vehicle.setId( 1L );
			vehicle.setCharArrayProp( "2020".toCharArray() );
			session.persist( vehicle );

			VehicleInvoice invoice = new VehicleInvoice();
			invoice.setId( new VehicleInvoiceId( "2020", 2020 ) );
			invoice.setVehicle( vehicle );
			session.persist( invoice );
		} );
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			session.createMutationQuery( "delete from VehicleInvoice" ).executeUpdate();
			session.createMutationQuery( "delete from Vehicle" ).executeUpdate();
		} );
	}

	@Test
	public void testAssociation(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			List<VehicleInvoice> resultList = session.createQuery(
					"from VehicleInvoice",
					VehicleInvoice.class
			).getResultList();
			assertEquals( 1, resultList.size() );
			assertEquals( 1L, resultList.get( 0 ).getVehicle().getId() );
			assertEquals( "2020", new String( resultList.get( 0 ).getVehicle().getCharArrayProp() ) );
		} );
	}

	@Embeddable
	public static class VehicleInvoiceId implements Serializable {
		@Column(name = "string_col")
		private String stringProp;

		@Column(name = "int_col")
		private int intProp;

		public VehicleInvoiceId() {
		}

		public VehicleInvoiceId(String stringProp, int intProp) {
			this.stringProp = stringProp;
			this.intProp = intProp;
		}

		public String getStringProp() {
			return stringProp;
		}

		public void setStringProp(String stringProp) {
			this.stringProp = stringProp;
		}

		public int getIntProp() {
			return intProp;
		}

		public void setIntProp(int intProp) {
			this.intProp = intProp;
		}
	}

	@Entity(name = "VehicleInvoice")
	public static class VehicleInvoice {
		@EmbeddedId
		private VehicleInvoiceId id;

		@ManyToOne(fetch = FetchType.EAGER)
		@JoinColumn(name = "string_col", referencedColumnName = "char_array_col", insertable = false, updatable = false)
		private Vehicle vehicle;

		public VehicleInvoiceId getId() {
			return id;
		}

		public void setId(VehicleInvoiceId id) {
			this.id = id;
		}

		public Vehicle getVehicle() {
			return vehicle;
		}

		public void setVehicle(Vehicle vehicle) {
			this.vehicle = vehicle;
		}
	}

	@Entity(name = "Vehicle")
	public static class Vehicle implements Serializable {
		@Id
		private Long id;

		@Column(name = "char_array_col", nullable = false)
		private char[] charArrayProp;

		public Long getId() {
			return id;
		}

		public void setId(Long id) {
			this.id = id;
		}

		public char[] getCharArrayProp() {
			return charArrayProp;
		}

		public void setCharArrayProp(char[] charArrayProp) {
			this.charArrayProp = charArrayProp;
		}
	}
}

