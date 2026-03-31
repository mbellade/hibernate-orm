/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit.inheritance;

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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests @Audited with JOINED inheritance and an explicit discriminator column.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditJoinedDiscriminatorInheritanceTest.Vehicle.class,
		AuditJoinedDiscriminatorInheritanceTest.Car.class,
		AuditJoinedDiscriminatorInheritanceTest.SportsCar.class,
		AuditJoinedDiscriminatorInheritanceTest.Truck.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.inheritance.AuditJoinedDiscriminatorInheritanceTest$TxIdSupplier"))
class AuditJoinedDiscriminatorInheritanceTest {
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

	@Test
	void testWriteSide(SessionFactoryScope scope) {
		currentTxId = 0;

		scope.inTransaction( session -> {
			session.persist( new Car( 1L, "Sedan", 5 ) );
			session.persist( new Truck( 2L, "Hauler", 10.5 ) );
		} );

		scope.inTransaction( session -> {
			session.find( Car.class, 1L ).name = "Sports Car";
		} );

		scope.inTransaction( session -> {
			session.remove( session.find( Truck.class, 2L ) );
		} );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			assertEquals( 2, auditLog.getRevisions( Car.class, 1L ).size() );
			assertEquals( 2, auditLog.getRevisions( Truck.class, 2L ).size() );
		} );
	}

	@Test
	void testPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		scope.inTransaction( session -> {
			session.persist( new Car( 10L, "Sedan", 5 ) );
			session.persist( new Truck( 11L, "Hauler", 10.5 ) );
		} );

		scope.inTransaction( session -> {
			session.find( Car.class, 10L ).name = "Sports Car";
		} );

		scope.inTransaction( session -> {
			session.remove( session.find( Truck.class, 11L ) );
		} );

		// At REV 1: both exist with original values
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 101 ).openSession() ) {
			var car = s.find( Car.class, 10L );
			assertNotNull( car );
			assertEquals( "Sedan", car.name );

			assertNotNull( s.find( Truck.class, 11L ) );
		}

		// At REV 2: car updated — polymorphic lookup via Vehicle
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 102 ).openSession() ) {
			var car = s.find( Car.class, 10L );
			assertNotNull( car );
			assertEquals( "Sports Car", car.name );

			var vehicle = s.find( Vehicle.class, 10L );
			assertNotNull( vehicle );
			assertEquals( "Sports Car", vehicle.name );

			assertNotNull( s.find( Truck.class, 11L ) );
		}

		// At REV 3: truck deleted
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 103 ).openSession() ) {
			assertNotNull( s.find( Car.class, 10L ) );
			assertNull( s.find( Truck.class, 11L ) );
		}
	}

	@Test
	void testGetHistory(SessionFactoryScope scope) {
		currentTxId = 200;

		scope.inTransaction( session -> {
			session.persist( new Car( 20L, "Sedan", 5 ) );
		} );

		scope.inTransaction( session -> {
			session.find( Car.class, 20L ).name = "Sports Car";
		} );

		scope.inTransaction( session -> {
			session.remove( session.find( Car.class, 20L ) );
		} );

		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( Car.class, 20L );
			assertEquals( 3, history.size() );
			assertEquals( "Sedan", history.get( 0 ).entity().name );
			assertEquals( "Sports Car", history.get( 1 ).entity().name );
			assertNotNull( history.get( 2 ).entity() );
		} );
	}

	@Test
	void testDeepHierarchy(SessionFactoryScope scope) {
		currentTxId = 300;

		scope.inTransaction( session -> {
			session.persist( new Car( 30L, "Plain Car", 4 ) );
			session.persist( new SportsCar( 31L, "Ferrari", 2, 600 ) );
		} );

		scope.inTransaction( session -> {
			var sc = session.find( SportsCar.class, 31L );
			sc.name = "Lamborghini";
			sc.horsepower = 700;
		} );

		scope.inSession( session -> {
			assertEquals( 1, session.getAuditLog().getRevisions( Car.class, 30L ).size() );
			assertEquals( 2, session.getAuditLog().getRevisions( SportsCar.class, 31L ).size() );
		} );

		// Polymorphic find(Car.class) at point-in-time
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 301 ).openSession() ) {
			var car = s.find( Car.class, 31L );
			assertNotNull( car );
			assertEquals( "Ferrari", car.name );
		}

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 302 ).openSession() ) {
			var car = s.find( Car.class, 31L );
			assertNotNull( car );
			assertEquals( "Lamborghini", car.name );
		}

		// History on SportsCar
		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( SportsCar.class, 31L );
			assertEquals( 2, history.size() );
			assertEquals( "Ferrari", history.get( 0 ).entity().name );
			assertEquals( 600, history.get( 0 ).entity().horsepower );
			assertEquals( "Lamborghini", history.get( 1 ).entity().name );
		} );
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "JDVehicle")
	@Inheritance(strategy = InheritanceType.JOINED)
	@DiscriminatorColumn(name = "VEHICLE_TYPE")
	@DiscriminatorValue("VEHICLE")
	static class Vehicle {
		@Id long id;
		String name;
		Vehicle() {}
		Vehicle(long id, String name) { this.id = id; this.name = name; }
	}

	@Entity(name = "JDCar")
	@DiscriminatorValue("CAR")
	static class Car extends Vehicle {
		int seatCount;
		Car() {}
		Car(long id, String name, int seatCount) { super( id, name ); this.seatCount = seatCount; }
	}

	@Entity(name = "JDSportsCar")
	@DiscriminatorValue("SPORTS_CAR")
	static class SportsCar extends Car {
		int horsepower;
		SportsCar() {}
		SportsCar(long id, String name, int seatCount, int horsepower) {
			super( id, name, seatCount );
			this.horsepower = horsepower;
		}
	}

	@Entity(name = "JDTruck")
	@DiscriminatorValue("TRUCK")
	static class Truck extends Vehicle {
		double payload;
		Truck() {}
		Truck(long id, String name, double payload) { super( id, name ); this.payload = payload; }
	}
}
