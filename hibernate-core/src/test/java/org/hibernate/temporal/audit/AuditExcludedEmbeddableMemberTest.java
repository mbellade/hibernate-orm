/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hibernate.temporal.audit;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.MappingException;
import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.NaturalId;
import org.hibernate.audit.AuditLogFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.mapping.Column;
import org.hibernate.temporal.spi.ChangesetIdentifierSupplier;
import org.hibernate.type.SqlTypes;
import org.hibernate.testing.orm.junit.AfterClassTemplate;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.BeforeClassTemplate;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.DomainModelScope;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.ServiceRegistryScope;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link Audited.Excluded @Audited.Excluded} on members of embeddables:
 * excluded nested columns are absent from audit tables, read as null (or the
 * primitive default) and changes to them alone do not create revisions.
 */
@AuditedTest
@DomainModel(annotatedClasses = {
		AuditExcludedEmbeddableMemberTest.Person.class,
		AuditExcludedEmbeddableMemberTest.Tag.class,
		AuditExcludedEmbeddableMemberTest.Shape.class,
		AuditExcludedEmbeddableMemberTest.Circle.class,
		AuditExcludedEmbeddableMemberTest.Vehicle.class,
		AuditExcludedEmbeddableMemberTest.Car.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.AuditExcludedEmbeddableMemberTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
class AuditExcludedEmbeddableMemberTest {
	private static int currentTxId;

	public static class TxIdSupplier implements ChangesetIdentifierSupplier<Integer> {
		@Override
		public Integer generateIdentifier(SharedSessionContract session) {
			return ++currentTxId;
		}
	}

	@BeforeClassTemplate
	void createData(SessionFactoryScope scope) {
		currentTxId = 0;
		scope.inTransaction( session -> {
			final var tag = new Tag();
			tag.id = 1L;
			session.persist( tag );
			session.persist( new Person( 1L, new Address( "Rome", "secret", 5, new Geo( "Z1", "hint" ), tag ) ) );
			session.persist( new Person( 2L, null ) );
			final var shape = new Shape();
			shape.id = 1L;
			shape.geo = new Geo( "S1", "hint" );
			session.persist( shape );
			final var circle = new Circle();
			circle.id = 2L;
			circle.geo = new Geo( "C1", "hint" );
			circle.radius = 3;
			session.persist( circle );
			final var car = new Car();
			car.id = 1L;
			car.geo = new Geo( "V1", "hint" );
			session.persist( car );
		} );
		// Change the current state after changeset 1: excluded members only for the person
		scope.inTransaction( session -> {
			final var person = session.find( Person.class, 1L );
			person.address.note = "changed";
			person.address.lines.add( "added" );
			session.find( Car.class, 1L ).geo.zone = "V2";
		} );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void excludedNestedColumnsAreNotAudited(DomainModelScope scope) {
		final var personAudit = table( scope, "emb_member_person_AUD" );
		assertColumns( personAudit, true, "name", "city", "zone" );
		assertColumns( personAudit, false, "note", "visits", "hint", "tag_id" );
		for ( var name : new String[] {"emb_member_shape_AUD", "emb_member_circle_AUD", "emb_member_car_AUD"} ) {
			final var audit = table( scope, name );
			assertColumns( audit, true, "zone" );
			assertColumns( audit, false, "hint" );
		}
		assertThat( scope.getDomainModel().collectTableMappings() )
				.noneMatch( table -> table.getName().endsWith( "lines_AUD" ) );
	}

	@Test
	void excludedNestedMembersReadAsDefaults(SessionFactoryScope scope) {
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			assertAuditedState( session.find( Person.class, 1L ) );
			assertThat( session.find( Person.class, 2L ).address ).isNull();
			// JOINED subclass table holding the embeddable
			final var vehicle = session.find( Vehicle.class, 1L );
			assertThat( vehicle ).isInstanceOf( Car.class );
			assertThat( ( (Car) vehicle ).geo.zone ).isEqualTo( "V1" );
			assertThat( ( (Car) vehicle ).geo.hint ).isNull();
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var people = session.createSelectionQuery( "from EmbMemberPerson order by id", Person.class )
					.getResultList();
			assertThat( people ).hasSize( 2 );
			assertAuditedState( people.get( 0 ) );
			assertThat( people.get( 1 ).address ).isNull();
		}
	}

	private static void assertAuditedState(Person person) {
		assertThat( person.name ).isEqualTo( "person" );
		final var address = person.address;
		assertThat( address.city ).isEqualTo( "Rome" );
		assertThat( address.note ).isNull();
		assertThat( address.visits ).isZero();
		assertThat( address.tag ).isNull();
		// Not loaded from the current-state collection table
		assertThat( address.lines ).isNull();
		assertThat( address.geo.zone ).isEqualTo( "Z1" );
		assertThat( address.geo.hint ).isNull();
	}

	@Test
	void unionReadSkipsExcludedNestedColumns(SessionFactoryScope scope) {
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var shapes = session.createSelectionQuery( "from EmbMemberShape order by id", Shape.class )
					.getResultList();
			assertThat( shapes ).hasSize( 2 );
			assertThat( shapes.get( 0 ).geo.zone ).isEqualTo( "S1" );
			assertThat( shapes.get( 0 ).geo.hint ).isNull();
			assertThat( shapes.get( 1 ) ).isInstanceOf( Circle.class );
			assertThat( shapes.get( 1 ).geo.zone ).isEqualTo( "C1" );
			assertThat( shapes.get( 1 ).geo.hint ).isNull();
			assertThat( ( (Circle) shapes.get( 1 ) ).radius ).isEqualTo( 3 );
			// A type restriction renders an optimized union of the matching subclass tables only
			final var circle = session.createSelectionQuery(
					"from EmbMemberShape s where type(s) = EmbMemberCircle",
					Shape.class
			).getSingleResult();
			assertThat( circle.geo.zone ).isEqualTo( "C1" );
			assertThat( circle.geo.hint ).isNull();
		}
	}

	@Test
	void changingOnlyExcludedNestedMembersWritesNoRevision(SessionFactoryScope scope) {
		scope.inTransaction( session -> session.persist(
				new Person( 3L, new Address( "Oslo", "secret", 1, new Geo( "Z1", "hint" ), null ) )
		) );
		scope.inTransaction( session -> {
			final var address = session.find( Person.class, 3L ).address;
			address.note = "changed";
			address.visits = 2;
			address.geo.hint = "changed";
		} );
		assertThat( historySize( scope ) ).isEqualTo( 1 );

		scope.inTransaction( session -> session.find( Person.class, 3L ).address.geo.zone = "Z2" );
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var history = auditLog.getHistory( Person.class, 3L );
			assertThat( history ).hasSize( 2 );
			final var address = history.get( 1 ).entity().address;
			assertThat( address.geo.zone ).isEqualTo( "Z2" );
			assertThat( address.geo.hint ).isNull();
			assertThat( address.note ).isNull();
		}
		scope.inSession( session -> {
			final var address = session.find( Person.class, 3L ).address;
			assertThat( address.note ).isEqualTo( "changed" );
			assertThat( address.visits ).isEqualTo( 2 );
			assertThat( address.geo.hint ).isEqualTo( "changed" );
		} );
	}

	private static int historySize(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			return auditLog.getHistory( Person.class, 3L ).size();
		}
	}

	@Test
	void unsupportedContextsFailForAuditedEntities(ServiceRegistryScope scope) {
		assertRejected( scope, EmbeddedIdEntity.class, "excludedPart" );
		assertRejected( scope, NaturalIdEntity.class, "excludedCode" );
		assertRejected( scope, JsonEntity.class, "excludedValue" );
		// Exclusions are irrelevant without auditing
		new MetadataSources( scope.getRegistry() ).addAnnotatedClass( UnauditedEmbeddedIdEntity.class )
				.buildMetadata();
		// Nested exclusions are moot when the embedded attribute itself is excluded
		new MetadataSources( scope.getRegistry() ).addAnnotatedClass( ExcludedJsonEntity.class )
				.buildMetadata();
	}

	private static void assertRejected(ServiceRegistryScope scope, Class<?> entityClass, String member) {
		final var sources = new MetadataSources( scope.getRegistry() ).addAnnotatedClass( entityClass );
		assertThat( assertThrows( MappingException.class, sources::buildMetadata ) )
				.hasMessageContaining( "'" + member + "'" );
	}

	private static org.hibernate.mapping.Table table(DomainModelScope scope, String name) {
		return scope.getDomainModel().collectTableMappings().stream()
				.filter( table -> table.getName().equalsIgnoreCase( name ) )
				.findFirst().orElseThrow( () -> new AssertionError( "Missing table " + name ) );
	}

	private static void assertColumns(org.hibernate.mapping.Table table, boolean present, String... names) {
		for ( var name : names ) {
			assertThat( table.getColumn( new Column( name ) ) != null )
					.as( table.getName() + "." + name )
					.isEqualTo( present );
		}
	}

	@Audited
	@Entity(name = "EmbMemberPerson")
	@Table(name = "emb_member_person")
	static class Person {
		@Id
		long id;
		String name;
		@Embedded
		Address address;

		Person() {
		}

		Person(long id, Address address) {
			this.id = id;
			this.name = "person";
			this.address = address;
		}
	}

	@Embeddable
	static class Address {
		String city;
		@Audited.Excluded
		String note;
		@Audited.Excluded
		int visits;
		@Audited.Excluded
		@ManyToOne
		Tag tag;
		@Audited.Excluded
		@ElementCollection
		Set<String> lines = new HashSet<>();
		@Embedded
		Geo geo;

		Address() {
		}

		Address(String city, String note, int visits, Geo geo, Tag tag) {
			this.city = city;
			this.note = note;
			this.visits = visits;
			this.geo = geo;
			this.tag = tag;
			this.lines.add( "line" );
		}
	}

	@Embeddable
	static class Geo {
		String zone;
		@Audited.Excluded
		String hint;

		Geo() {
		}

		Geo(String zone, String hint) {
			this.zone = zone;
			this.hint = hint;
		}
	}

	@Audited
	@Entity(name = "EmbMemberTag")
	@Table(name = "emb_member_tag")
	static class Tag {
		@Id
		long id;
	}

	@Audited
	@Entity(name = "EmbMemberShape")
	@Table(name = "emb_member_shape")
	@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
	static class Shape {
		@Id
		long id;
		@Embedded
		Geo geo;
	}

	@Entity(name = "EmbMemberCircle")
	@Table(name = "emb_member_circle")
	static class Circle extends Shape {
		int radius;
	}

	@Audited
	@Entity(name = "EmbMemberVehicle")
	@Table(name = "emb_member_vehicle")
	@Inheritance(strategy = InheritanceType.JOINED)
	static class Vehicle {
		@Id
		long id;
	}

	@Entity(name = "EmbMemberCar")
	@Table(name = "emb_member_car")
	static class Car extends Vehicle {
		@Embedded
		Geo geo;
	}

	@Embeddable
	static class Key {
		long part;
		@Audited.Excluded
		long excludedPart;
	}

	@Audited
	@Entity(name = "EmbMemberEmbeddedId")
	static class EmbeddedIdEntity {
		@EmbeddedId
		Key id;
	}

	@Entity(name = "EmbMemberUnauditedEmbeddedId")
	static class UnauditedEmbeddedIdEntity {
		@EmbeddedId
		Key id;
	}

	@Embeddable
	static class Code {
		String code;
		@Audited.Excluded
		String excludedCode;
	}

	@Audited
	@Entity(name = "EmbMemberNaturalId")
	static class NaturalIdEntity {
		@Id
		long id;
		@NaturalId
		@Embedded
		Code code;
	}

	@Embeddable
	static class JsonValue {
		String value;
		@Audited.Excluded
		String excludedValue;
	}

	@Audited
	@Entity(name = "EmbMemberJson")
	static class JsonEntity {
		@Id
		long id;
		@JdbcTypeCode(SqlTypes.JSON)
		JsonValue json;
	}

	@Audited
	@Entity(name = "EmbMemberExcludedJson")
	static class ExcludedJsonEntity {
		@Id
		long id;
		@Audited.Excluded
		@JdbcTypeCode(SqlTypes.JSON)
		JsonValue json;
	}
}
