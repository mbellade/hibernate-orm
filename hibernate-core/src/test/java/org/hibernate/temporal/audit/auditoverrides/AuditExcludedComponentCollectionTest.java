/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hibernate.temporal.audit.auditoverrides;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.temporal.spi.ChangesetIdentifierSupplier;
import org.hibernate.testing.orm.junit.AfterClassTemplate;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.BeforeClassTemplate;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.DomainModelScope;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNoTable;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertTable;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditExcludedComponentCollectionTest.ExcludedOwner.class,
		AuditExcludedComponentCollectionTest.UnionOwner.class,
		AuditExcludedComponentCollectionTest.RestoredOwner.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditExcludedComponentCollectionTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
class AuditExcludedComponentCollectionTest {
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
			final var base = new UnionOwner();
			base.id = 1L;
			base.address = new Address();
			base.address.lines.add( "excluded" );
			session.persist( base );
			final var restored = new RestoredOwner();
			restored.id = 2L;
			restored.address = new Address();
			restored.address.lines.addAll( Set.of( "first", "second" ) );
			session.persist( restored );
			final var baseReference = new ExcludedOwner();
			baseReference.id = 1L;
			baseReference.owner = base;
			session.persist( baseReference );
			final var restoredReference = new ExcludedOwner();
			restoredReference.id = 2L;
			restoredReference.owner = restored;
			session.persist( restoredReference );
		} );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void polymorphicReadMasksExcludedComponent(SessionFactoryScope scope) {
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var owners = session.createSelectionQuery(
					"from UnionComponentOwner order by id",
					UnionOwner.class
			).getResultList();
			assertThat( owners ).hasSize( 2 );
			assertComponentValues( owners.get( 0 ), owners.get( 1 ) );
		}
	}

	@Test
	void joinedFetchMasksExcludedComponent(SessionFactoryScope scope) {
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var references = session.createSelectionQuery(
					"from ExcludedComponentOwner e join fetch e.owner o left join fetch o.address.lines order by e.id",
					ExcludedOwner.class
			).getResultList();
			assertThat( references ).hasSize( 2 );
			assertComponentValues( references.get( 0 ).owner, references.get( 1 ).owner );
		}
	}

	private static void assertComponentValues(UnionOwner base, UnionOwner restored) {
		assertThat( base.address ).isNull();
		assertThat( restored ).isInstanceOf( RestoredOwner.class );
		assertThat( restored.address ).isNotNull();
		assertThat( restored.address.lines ).containsExactlyInAnyOrder( "first", "second" );
	}

	@Test
	void excludedComponentHasNoMiddleTables(DomainModelScope scope) {
		assertNoMiddleTable( scope, ExcludedOwner.class, "address.lines" );
		assertNoMiddleTable( scope, ExcludedOwner.class, "address.details.notes" );
	}

	@Test
	void subclassRestoresComponentButNotExcludedMembers(DomainModelScope scope) {
		assertTable( scope, middleTableName( scope, UnionOwner.class, "address.lines" ) );
		assertTable( scope, middleTableName( scope, UnionOwner.class, "address.details.notes" ) );
		assertNoMiddleTable( scope, UnionOwner.class, "address.excludedLines" );
		assertNoMiddleTable( scope, UnionOwner.class, "address.details.excludedNotes" );
	}

	private static void assertNoMiddleTable(DomainModelScope scope, Class<?> owner, String path) {
		assertNoTable( scope, middleTableName( scope, owner, path ) );
	}

	private static String middleTableName(DomainModelScope scope, Class<?> owner, String path) {
		return scope.getDomainModel().getCollectionBinding( owner.getName() + "." + path )
				.getCollectionTable().getName() + "_AUD";
	}

	@Entity(name = "ExcludedComponentOwner")
	@Table(name = "excluded_component_owner")
	@Audited
	static class ExcludedOwner {
		@Id
		long id;
		@Embedded
		@Audited.Excluded
		Address address;
		@ManyToOne
		UnionOwner owner;
	}

	@Entity(name = "UnionComponentOwner")
	@Table(name = "union_component_owner")
	@Audited
	static class UnionOwner {
		@Id
		long id;
		@Embedded
		@Audited.Excluded
		Address address;
	}

	@Entity(name = "RestoredComponentOwner")
	@Audited.Override(name = "address")
	static class RestoredOwner extends UnionOwner {
	}

	@Embeddable
	static class Address {
		@ElementCollection
		Set<String> lines = new HashSet<>();
		@ElementCollection
		@Audited.Excluded
		Set<String> excludedLines = new HashSet<>();
		@Embedded
		Details details;
	}

	@Embeddable
	static class Details {
		@ElementCollection
		Set<String> notes = new HashSet<>();
		@ElementCollection
		@Audited.Excluded
		Set<String> excludedNotes = new HashSet<>();
	}
}
