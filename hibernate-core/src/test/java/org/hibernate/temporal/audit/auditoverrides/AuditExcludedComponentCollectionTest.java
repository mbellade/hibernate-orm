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
import jakarta.persistence.Table;

import org.hibernate.annotations.Audited;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.DomainModelScope;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.SessionFactory;

import org.junit.jupiter.api.Test;

import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNoTable;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertTable;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditExcludedComponentCollectionTest.ExcludedOwner.class,
		AuditExcludedComponentCollectionTest.UnionOwner.class,
		AuditExcludedComponentCollectionTest.RestoredOwner.class
})
@SessionFactory
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
class AuditExcludedComponentCollectionTest {
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
