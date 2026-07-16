/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit.collection;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.AuditLogFactory;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.mapping.Table;
import org.hibernate.temporal.spi.ChangesetIdentifierSupplier;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.BeforeClassTemplate;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.DomainModelScope;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link Audited.CollectionTable} is honored for unidirectional
 * {@code @OneToMany} without {@code @JoinColumn} (i.e. the join-table path).
 */
@Jira("https://hibernate.atlassian.net/browse/HHH-20698")
@AuditedTest
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditUnidirectionalOneToManyCollectionTableTest.Department.class,
		AuditUnidirectionalOneToManyCollectionTableTest.Employee.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.collection.AuditUnidirectionalOneToManyCollectionTableTest$TxIdSupplier"))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditUnidirectionalOneToManyCollectionTableTest {
	private static int currentTxId;

	public static class TxIdSupplier implements ChangesetIdentifierSupplier<Integer> {
		@Override
		public Integer generateIdentifier(SharedSessionContract session) {
			return ++currentTxId;
		}
	}

	private int revCreate; // Department(1) + Employee(1, "Alice")
	private int revAdd;    // add Employee(2, "Bob")
	private int revRemove; // remove Employee(1)

	@BeforeClassTemplate
	void initData(SessionFactoryScope scope) {
		currentTxId = 0;
		final var sf = scope.getSessionFactory();

		// Rev 1: department + one employee
		sf.inTransaction( session -> {
			var emp = new Employee( 1L, "Alice" );
			session.persist( emp );
			var dept = new Department( 1L, "Engineering" );
			dept.employees.add( emp );
			session.persist( dept );
		} );
		revCreate = currentTxId;

		// Rev 2: add second employee
		sf.inTransaction( session -> {
			var emp = new Employee( 2L, "Bob" );
			session.persist( emp );
			var dept = session.find( Department.class, 1L );
			dept.employees.add( emp );
		} );
		revAdd = currentTxId;

		// Rev 3: remove first employee from department
		sf.inTransaction( session -> {
			var dept = session.find( Department.class, 1L );
			dept.employees.removeIf( e -> e.id == 1L );
		} );
		revRemove = currentTxId;
	}

	@Test
	@Order(1)
	void testCustomAuditTableName(DomainModelScope domainModelScope) {
		var tableNames = domainModelScope.getDomainModel().collectTableMappings()
				.stream().map( Table::getName ).collect( Collectors.toSet() );
		assertTrue( tableNames.contains( "my_custom_employees_audited" ),
				"Custom @Audited.CollectionTable name should be used for the join-table audit table" );
	}

	@Test
	@Order(2)
	void testWriteSide(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			// Department: ADD + 2 collection changes = 3 revisions
			assertEquals( 3, auditLog.getChangesets( Department.class, 1L ).size(),
					"Department should have 3 revisions (ADD + add employee + remove employee)" );
			assertEquals( 1, auditLog.getChangesets( Employee.class, 1L ).size(),
					"Employee 1 should have 1 revision (ADD only)" );
			assertEquals( 1, auditLog.getChangesets( Employee.class, 2L ).size(),
					"Employee 2 should have 1 revision (ADD only)" );
		}
	}

	@Test
	@Order(3)
	void testPointInTimeRead(SessionFactoryScope scope) {
		final var sf = scope.getSessionFactory();

		// At revCreate: department should have 1 employee (Alice)
		try (var s = sf.withOptions().atChangeset( revCreate ).openSession()) {
			var dept = s.find( Department.class, 1L );
			assertNotNull( dept );
			assertEquals( 1, dept.employees.size(), "At revCreate, department should have 1 employee" );
			assertEquals( "Alice", dept.employees.get( 0 ).name );
		}

		// At revAdd: department should have 2 employees
		try (var s = sf.withOptions().atChangeset( revAdd ).openSession()) {
			var dept = s.find( Department.class, 1L );
			assertNotNull( dept );
			assertEquals( 2, dept.employees.size(), "At revAdd, department should have 2 employees" );
			var names = dept.employees.stream().map( e -> e.name ).sorted().toList();
			assertEquals( List.of( "Alice", "Bob" ), names );
		}

		// At revRemove: department should have 1 employee (Bob only)
		try (var s = sf.withOptions().atChangeset( revRemove ).openSession()) {
			var dept = s.find( Department.class, 1L );
			assertNotNull( dept );
			assertEquals( 1, dept.employees.size(), "At revRemove, department should have 1 employee" );
			assertEquals( "Bob", dept.employees.get( 0 ).name );
		}
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "Department")
	static class Department {
		@Id
		long id;
		String name;
		@OneToMany
		@Audited.CollectionTable(name = "my_custom_employees_audited")
		List<Employee> employees = new ArrayList<>();

		Department() {
		}

		Department(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	@Audited
	@Entity(name = "Employee")
	static class Employee {
		@Id
		long id;
		String name;

		Employee() {
		}

		Employee(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}
}
