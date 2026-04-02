/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit.inheritance;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import org.hibernate.annotations.Audited;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.SharedSessionContract;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests @Audited with SINGLE_TABLE inheritance.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditSingleTableInheritanceTest.Vehicle.class,
		AuditSingleTableInheritanceTest.Car.class,
		AuditSingleTableInheritanceTest.SportsCar.class,
		AuditSingleTableInheritanceTest.Truck.class,
		AuditSingleTableInheritanceTest.Driver.class,
		AuditSingleTableInheritanceTest.Team.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.inheritance.AuditSingleTableInheritanceTest$TxIdSupplier"))
class AuditSingleTableInheritanceTest {
	private static int currentTxId;

	public static class TxIdSupplier implements TransactionIdentifierSupplier<Integer> {
		@Override
		public Integer getTransactionIdentifier(SharedSessionContract session) {
			return ++currentTxId;
		}

	}

	@Test
	void testWriteSide(SessionFactoryScope scope) {
		currentTxId = 0;

		scope.inTransaction( session -> {
			session.persist( new SportsCar( 1L, "Sedan", 5, 200 ) );
			session.persist( new Truck( 2L, "Hauler", 10.5 ) );
		} );

		scope.inTransaction( session -> {
			var car = session.find( SportsCar.class, 1L );
			car.name = "Sports Car";
			car.seatCount = 2;
		} );

		scope.inTransaction( session -> session.find( Truck.class, 2L ).name = "Big Hauler" );

		scope.inTransaction( session -> session.remove( session.find( SportsCar.class, 1L ) ) );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			assertThat( auditLog.getRevisions( SportsCar.class, 1L ) ).hasSize( 3 );
			assertThat( auditLog.getRevisions( Truck.class, 2L ) ).hasSize( 2 );
		} );
	}

	@Test
	void testPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		scope.inTransaction( session -> {
			session.persist( new SportsCar( 10L, "Sedan", 5, 200 ) );
			session.persist( new Truck( 11L, "Hauler", 10.5 ) );
		} );

		scope.inTransaction( session -> {
			var car = session.find( SportsCar.class, 10L );
			car.name = "Sports Car";
			car.seatCount = 2;
		} );

		scope.inTransaction( session -> session.find( Truck.class, 11L ).name = "Big Hauler" );

		scope.inTransaction( session -> session.remove( session.find( SportsCar.class, 10L ) ) );

		// At REV 1: original values
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 101 ).openSession() ) {
			var car = s.find( SportsCar.class, 10L );
			assertThat( car ).isNotNull();
			assertThat( car.name ).isEqualTo( "Sedan" );
			assertThat( car.seatCount ).isEqualTo( 5 );

			var truck = s.find( Truck.class, 11L );
			assertThat( truck ).isNotNull();
			assertThat( truck.name ).isEqualTo( "Hauler" );
			assertThat( truck.payload ).isEqualTo( 10.5 );
		}

		// At REV 2: car updated, truck unchanged — polymorphic lookups
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 102 ).openSession() ) {
			var car = s.find( SportsCar.class, 10L );
			assertThat( car ).isNotNull();
			assertThat( car.name ).isEqualTo( "Sports Car" );
			assertThat( car.seatCount ).isEqualTo( 2 );

			assertThat( s.find( Car.class, 10L ) ).isNotNull().extracting( v -> v.name )
					.isEqualTo( "Sports Car" );
			assertThat( s.find( Vehicle.class, 10L ) ).isNotNull().extracting( v -> v.name )
					.isEqualTo( "Sports Car" );

			assertThat( s.find( Truck.class, 11L ) ).isNotNull()
					.extracting( v -> v.name ).isEqualTo( "Hauler" );
		}

		// At REV 3: truck name updated
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 103 ).openSession() ) {
			var truck = s.find( Truck.class, 11L );
			assertThat( truck ).isNotNull();
			assertThat( truck.name ).isEqualTo( "Big Hauler" );
			assertThat( truck.payload ).isEqualTo( 10.5 );
		}

		// At REV 4: car deleted
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 104 ).openSession() ) {
			assertThat( s.find( SportsCar.class, 10L ) ).isNull();
			assertThat( s.find( Truck.class, 11L ) ).isNotNull();
		}
	}

	@Test
	void testGetHistory(SessionFactoryScope scope) {
		currentTxId = 200;

		scope.inTransaction( session -> session.persist( new SportsCar( 20L, "Sedan", 5, 200 ) ) );

		scope.inTransaction( session -> {
			var car = session.find( SportsCar.class, 20L );
			car.name = "Sports Car";
			car.seatCount = 2;
		} );

		scope.inTransaction( session -> session.remove( session.find( SportsCar.class, 20L ) ) );

		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( SportsCar.class, 20L );
			assertThat( history ).hasSize( 3 );
			assertThat( history.get( 0 ).entity().name ).isEqualTo( "Sedan" );
			assertThat( history.get( 0 ).entity().seatCount ).isEqualTo( 5 );
			assertThat( history.get( 1 ).entity().name ).isEqualTo( "Sports Car" );
			assertThat( history.get( 1 ).entity().seatCount ).isEqualTo( 2 );
			assertThat( history.get( 2 ).entity() ).isNotNull();
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
			assertThat( session.getAuditLog().getRevisions( Car.class, 30L ) ).hasSize( 1 );
			assertThat( session.getAuditLog().getRevisions( SportsCar.class, 31L ) ).hasSize( 2 );
		} );

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 301 ).openSession() ) {
			assertThat( s.find( Car.class, 31L ) ).isNotNull()
					.extracting( v -> v.name ).isEqualTo( "Ferrari" );
		}

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 302 ).openSession() ) {
			assertThat( s.find( Car.class, 31L ) ).isNotNull()
					.extracting( v -> v.name ).isEqualTo( "Lamborghini" );
		}

		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( SportsCar.class, 31L );
			assertThat( history ).hasSize( 2 );
			assertThat( history.get( 0 ).entity().name ).isEqualTo( "Ferrari" );
			assertThat( history.get( 0 ).entity().horsepower ).isEqualTo( 600 );
			assertThat( history.get( 1 ).entity().name ).isEqualTo( "Lamborghini" );
		} );
	}

	@Test
	void testToOneAssociation(SessionFactoryScope scope) {
		currentTxId = 400;

		scope.inTransaction( session -> {
			var car = new SportsCar( 40L, "Ferrari", 2, 600 );
			session.persist( car );
			session.persist( new Driver( 41L, "Lewis", car ) );
		} );

		scope.inTransaction( session -> session.find( SportsCar.class, 40L ).name = "Lamborghini" );

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 401 ).openSession() ) {
			var driver = s.find( Driver.class, 41L );
			assertThat( driver ).isNotNull();
			assertThat( driver.vehicle ).isNotNull();
			assertThat( driver.vehicle.name ).isEqualTo( "Ferrari" );
		}

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 402 ).openSession() ) {
			var driver = s.find( Driver.class, 41L );
			assertThat( driver ).isNotNull();
			assertThat( driver.vehicle.name ).isEqualTo( "Lamborghini" );
		}
	}

	@Test
	void testManyToManyAssociation(SessionFactoryScope scope) {
		currentTxId = 500;

		scope.inTransaction( session -> {
			var car = new SportsCar( 50L, "Ferrari", 2, 600 );
			var truck = new Truck( 51L, "Hauler", 10.5 );
			session.persist( car );
			session.persist( truck );
			var team = new Team( 52L, "Racing" );
			team.vehicles.add( car );
			team.vehicles.add( truck );
			session.persist( team );
		} );

		scope.inTransaction( session -> session.find( SportsCar.class, 50L ).name = "Lamborghini" );

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 501 ).openSession() ) {
			var team = s.find( Team.class, 52L );
			assertThat( team ).isNotNull();
			assertThat( team.vehicles ).extracting( v -> v.name )
					.containsExactlyInAnyOrder( "Ferrari", "Hauler" );
		}

		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 502 ).openSession() ) {
			var team = s.find( Team.class, 52L );
			assertThat( team ).isNotNull();
			assertThat( team.vehicles ).extracting( v -> v.name )
					.containsExactlyInAnyOrder( "Hauler", "Lamborghini" );
		}
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "Vehicle")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@DiscriminatorColumn(name = "VEHICLE_TYPE")
	@DiscriminatorValue("VEHICLE")
	static class Vehicle {
		@Id long id;
		String name;
		Vehicle() {}
		Vehicle(long id, String name) { this.id = id; this.name = name; }
	}

	@Entity(name = "Car")
	@DiscriminatorValue("CAR")
	static class Car extends Vehicle {
		int seatCount;
		Car() {}
		Car(long id, String name, int seatCount) { super( id, name ); this.seatCount = seatCount; }
	}

	@Entity(name = "SportsCar")
	@DiscriminatorValue("SPORTS_CAR")
	static class SportsCar extends Car {
		int horsepower;
		SportsCar() {}
		SportsCar(long id, String name, int seatCount, int horsepower) {
			super( id, name, seatCount );
			this.horsepower = horsepower;
		}
	}

	@Entity(name = "Truck")
	@DiscriminatorValue("TRUCK")
	static class Truck extends Vehicle {
		double payload;
		Truck() {}
		Truck(long id, String name, double payload) { super( id, name ); this.payload = payload; }
	}

	@Audited
	@Entity(name = "Driver")
	static class Driver {
		@Id long id;
		String driverName;
		@ManyToOne Vehicle vehicle;
		Driver() {}
		Driver(long id, String driverName, Vehicle vehicle) {
			this.id = id; this.driverName = driverName; this.vehicle = vehicle;
		}
	}

	@Audited
	@Entity(name = "Team")
	static class Team {
		@Id long id;
		String teamName;
		@ManyToMany
		List<Vehicle> vehicles = new ArrayList<>();
		Team() {}
		Team(long id, String teamName) { this.id = id; this.teamName = teamName; }
	}
}
