/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import org.hibernate.annotations.Audited;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke tests for @Audited with all three inheritance strategies.
 * Each nested class tests a different strategy with its own entity hierarchy.
 */
class AuditInheritanceSmokeTest {

	public static class TxIdSupplier implements TransactionIdentifierSupplier<Integer> {
		static int currentTxId;

		@Override
		public Integer getTransactionIdentifier(SharedSessionContractImplementor session) {
			return ++currentTxId;
		}

		@Override
		public Class<Integer> getIdentifierType() {
			return Integer.class;
		}
	}

	// ==== SINGLE_TABLE ====

	@Audited
	@Entity(name = "STVehicle")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@DiscriminatorColumn(name = "VEHICLE_TYPE")
	@DiscriminatorValue("VEHICLE")
	static class STVehicle {
		@Id long id;
		String name;
		STVehicle() {}
		STVehicle(long id, String name) { this.id = id; this.name = name; }
	}

	@Entity(name = "STCar")
	@DiscriminatorValue("CAR")
	static class STCar extends STVehicle {
		int seatCount;
		STCar() {}
		STCar(long id, String name, int seatCount) { super( id, name ); this.seatCount = seatCount; }
	}

	@Entity(name = "STTruck")
	@DiscriminatorValue("TRUCK")
	static class STTruck extends STVehicle {
		double payload;
		STTruck() {}
		STTruck(long id, String name, double payload) { super( id, name ); this.payload = payload; }
	}

	@Nested
	@SessionFactory
	@DomainModel(annotatedClasses = { STVehicle.class, STCar.class, STTruck.class })
	@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
			value = "org.hibernate.temporal.audit.AuditInheritanceSmokeTest$TxIdSupplier"))
	class SingleTableTest {

		@Test
		void testSingleTableInheritance(SessionFactoryScope scope) {
			TxIdSupplier.currentTxId = 500;

			scope.getSessionFactory().inTransaction( session -> {
				session.persist( new STCar( 1L, "Sedan", 5 ) );
				session.persist( new STTruck( 2L, "Hauler", 10.5 ) );
			} );

			scope.getSessionFactory().inTransaction( session -> {
				var car = session.find( STCar.class, 1L );
				car.name = "Sports Car";
				car.seatCount = 2;
			} );

			try (var s = scope.getSessionFactory().withOptions().atTransaction( 501 ).open()) {
				var car = s.find( STCar.class, 1L );
				assertNotNull( car );
				assertEquals( "Sedan", car.name );
				assertEquals( 5, car.seatCount );
			}

			try (var s = scope.getSessionFactory().withOptions().atTransaction( 502 ).open()) {
				var car = s.find( STCar.class, 1L );
				assertNotNull( car );
				assertEquals( "Sports Car", car.name );
				assertEquals( 2, car.seatCount );
			}

			try (var s = scope.getSessionFactory().withOptions().atTransaction( 501 ).open()) {
				var truck = s.find( STTruck.class, 2L );
				assertNotNull( truck );
				assertEquals( "Hauler", truck.name );
				assertEquals( 10.5, truck.payload );
			}
		}
	}

	// ==== JOINED ====

	@Audited
	@Entity(name = "JVehicle")
	@Inheritance(strategy = InheritanceType.JOINED)
	static class JVehicle {
		@Id long id;
		String name;
		JVehicle() {}
		JVehicle(long id, String name) { this.id = id; this.name = name; }
	}

	@Entity(name = "JCar")
	static class JCar extends JVehicle {
		int seatCount;
		JCar() {}
		JCar(long id, String name, int seatCount) { super( id, name ); this.seatCount = seatCount; }
	}

	@Entity(name = "JTruck")
	static class JTruck extends JVehicle {
		double payload;
		JTruck() {}
		JTruck(long id, String name, double payload) { super( id, name ); this.payload = payload; }
	}

	@Nested
	@SessionFactory
	@DomainModel(annotatedClasses = { JVehicle.class, JCar.class, JTruck.class })
	@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
			value = "org.hibernate.temporal.audit.AuditInheritanceSmokeTest$TxIdSupplier"))
	class JoinedTest {

		@Test
		@Disabled("JOINED inheritance read side: per-table temporal predicates not yet implemented")
		void testJoinedInheritance(SessionFactoryScope scope) {
			TxIdSupplier.currentTxId = 600;

			scope.getSessionFactory().inTransaction( session -> {
				session.persist( new JCar( 1L, "Sedan", 5 ) );
				session.persist( new JTruck( 2L, "Hauler", 10.5 ) );
			} );

			scope.getSessionFactory().inTransaction( session -> {
				var car = session.find( JCar.class, 1L );
				car.name = "Sports Car";
				car.seatCount = 2;
			} );

			// Root table columns (name) at rev 1
			try (var s = scope.getSessionFactory().withOptions().atTransaction( 601 ).open()) {
				var car = s.find( JCar.class, 1L );
				assertNotNull( car );
				assertEquals( "Sedan", car.name );
				assertEquals( 5, car.seatCount );
			}

			// Updated at rev 2
			try (var s = scope.getSessionFactory().withOptions().atTransaction( 602 ).open()) {
				var car = s.find( JCar.class, 1L );
				assertNotNull( car );
				assertEquals( "Sports Car", car.name );
				assertEquals( 2, car.seatCount );
			}

			// Truck unchanged
			try (var s = scope.getSessionFactory().withOptions().atTransaction( 601 ).open()) {
				var truck = s.find( JTruck.class, 2L );
				assertNotNull( truck );
				assertEquals( "Hauler", truck.name );
				assertEquals( 10.5, truck.payload );
			}
		}
	}

	// ==== TABLE_PER_CLASS ====

	@Audited
	@Entity(name = "TPCVehicle")
	@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
	static class TPCVehicle {
		@Id long id;
		String name;
		TPCVehicle() {}
		TPCVehicle(long id, String name) { this.id = id; this.name = name; }
	}

	@Entity(name = "TPCCar")
	static class TPCCar extends TPCVehicle {
		int seatCount;
		TPCCar() {}
		TPCCar(long id, String name, int seatCount) { super( id, name ); this.seatCount = seatCount; }
	}

	@Entity(name = "TPCTruck")
	static class TPCTruck extends TPCVehicle {
		double payload;
		TPCTruck() {}
		TPCTruck(long id, String name, double payload) { super( id, name ); this.payload = payload; }
	}

	@Nested
	@SessionFactory
	@DomainModel(annotatedClasses = { TPCVehicle.class, TPCCar.class, TPCTruck.class })
	@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
			value = "org.hibernate.temporal.audit.AuditInheritanceSmokeTest$TxIdSupplier"))
	class TablePerClassTest {

		@Test
		void testTablePerClassInheritance(SessionFactoryScope scope) {
			TxIdSupplier.currentTxId = 700;

			scope.getSessionFactory().inTransaction( session -> {
				session.persist( new TPCCar( 1L, "Sedan", 5 ) );
				session.persist( new TPCTruck( 2L, "Hauler", 10.5 ) );
			} );

			scope.getSessionFactory().inTransaction( session -> {
				var car = session.find( TPCCar.class, 1L );
				car.name = "Sports Car";
				car.seatCount = 2;
			} );

			// Each concrete class has its own table — verify audit works per-table
			try (var s = scope.getSessionFactory().withOptions().atTransaction( 701 ).open()) {
				var car = s.find( TPCCar.class, 1L );
				assertNotNull( car );
				assertEquals( "Sedan", car.name );
				assertEquals( 5, car.seatCount );
			}

			try (var s = scope.getSessionFactory().withOptions().atTransaction( 702 ).open()) {
				var car = s.find( TPCCar.class, 1L );
				assertNotNull( car );
				assertEquals( "Sports Car", car.name );
				assertEquals( 2, car.seatCount );
			}

			try (var s = scope.getSessionFactory().withOptions().atTransaction( 701 ).open()) {
				var truck = s.find( TPCTruck.class, 2L );
				assertNotNull( truck );
				assertEquals( "Hauler", truck.name );
				assertEquals( 10.5, truck.payload );
			}
		}
	}
}
