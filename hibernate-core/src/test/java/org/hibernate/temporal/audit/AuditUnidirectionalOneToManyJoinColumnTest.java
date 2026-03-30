/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for unidirectional @OneToMany with @JoinColumn (no mappedBy, FK on child table).
 * This is a one-to-many where the parent owns the relationship via a FK column
 * on the child table, but there's no back-reference @ManyToOne on the child.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditUnidirectionalOneToManyJoinColumnTest.Department.class,
		AuditUnidirectionalOneToManyJoinColumnTest.Employee.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.AuditUnidirectionalOneToManyJoinColumnTest$TxIdSupplier"))
class AuditUnidirectionalOneToManyJoinColumnTest {
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

	/**
	 * Write side: insert, remove, and bulk-delete via unidirectional @OneToMany @JoinColumn.
	 */
	@Test
	void testWriteSide(SessionFactoryScope scope) {
		currentTxId = 0;

		// REV 1: department + one employee (ADD for employee + MOD for FK set)
		scope.inTransaction( session -> {
			var emp = new Employee( 1L, "Alice" );
			session.persist( emp );
			var dept = new Department( 1L, "Engineering" );
			dept.employees.add( emp );
			session.persist( dept );
		} );

		// REV 2: add second employee (ADD for employee + MOD for FK set)
		scope.inTransaction( session -> {
			var emp = new Employee( 2L, "Bob" );
			session.persist( emp );
			var dept = session.find( Department.class, 1L );
			dept.employees.add( emp );
		} );

		// REV 3: remove first employee from department (MOD for FK null-out)
		scope.inTransaction( session -> {
			var dept = session.find( Department.class, 1L );
			dept.employees.removeIf( e -> e.id == 1L );
		} );

		// REV 4: delete department (bulk removal of remaining employees from collection)
		scope.inTransaction( session -> {
			var dept = session.find( Department.class, 1L );
			session.remove( dept );
		} );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			// Department: REV 1 (ADD) + REV 4 (DEL) = 2 revisions
			assertEquals( 2, auditLog.getRevisions( Department.class, 1L ).size(),
					"Department should have 2 revisions (ADD + DEL)" );

			// Employee 1: ADD (persist) + MOD (FK set) + MOD (FK null-out) = 3 revisions
			assertEquals( 3, auditLog.getRevisions( Employee.class, 1L ).size(),
					"Employee 1 should have 3 revisions (ADD + MOD FK set + MOD FK null)" );

			// Employee 2: ADD (persist) + MOD (FK set) + MOD (FK null via bulk removal) = 3 revisions
			assertEquals( 3, auditLog.getRevisions( Employee.class, 2L ).size(),
					"Employee 2 should have 3 revisions (ADD + MOD FK set + MOD FK null via bulk removal)" );
		} );
	}

	/**
	 * Read side: point-in-time reads should load the department
	 * with the correct employees at each revision.
	 */
	@Test
	void testPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		// REV 1: department + employee A
		scope.inTransaction( session -> {
			var emp = new Employee( 10L, "PIT Alice" );
			session.persist( emp );
			var dept = new Department( 10L, "PIT Engineering" );
			dept.employees.add( emp );
			session.persist( dept );
		} );

		// REV 2: add employee B
		scope.inTransaction( session -> {
			var emp = new Employee( 11L, "PIT Bob" );
			session.persist( emp );
			var dept = session.find( Department.class, 10L );
			dept.employees.add( emp );
		} );

		// REV 3: remove employee A
		scope.inTransaction( session -> {
			var dept = session.find( Department.class, 10L );
			dept.employees.removeIf( e -> e.id == 10L );
		} );

		// At REV 1: department should have 1 employee (Alice)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 101 ).openSession() ) {
			var dept = s.find( Department.class, 10L );
			assertNotNull( dept );
			assertEquals( 1, dept.employees.size(), "At REV 1, department should have 1 employee" );
			assertEquals( "PIT Alice", dept.employees.get( 0 ).name );
		}

		// At REV 2: department should have 2 employees
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 102 ).openSession() ) {
			var dept = s.find( Department.class, 10L );
			assertNotNull( dept );
			assertEquals( 2, dept.employees.size(), "At REV 2, department should have 2 employees" );
			var names = dept.employees.stream().map( e -> e.name ).sorted().toList();
			assertEquals( List.of( "PIT Alice", "PIT Bob" ), names );
		}

		// At REV 3: department should have 1 employee (Bob only)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 103 ).openSession() ) {
			var dept = s.find( Department.class, 10L );
			assertNotNull( dept );
			assertEquals( 1, dept.employees.size(), "At REV 3, department should have 1 employee" );
			assertEquals( "PIT Bob", dept.employees.get( 0 ).name );
		}
	}

	/**
	 * Historical read: getHistory on the department should return
	 * all revisions with correct employee snapshots.
	 */
	@Test
	void testGetHistory(SessionFactoryScope scope) {
		currentTxId = 200;

		// REV 1: department + employee
		scope.inTransaction( session -> {
			var emp = new Employee( 20L, "Hist Alice" );
			session.persist( emp );
			var dept = new Department( 20L, "Hist Engineering" );
			dept.employees.add( emp );
			session.persist( dept );
		} );

		// REV 2: add employee B
		scope.inTransaction( session -> {
			var emp = new Employee( 21L, "Hist Bob" );
			session.persist( emp );
			var dept = session.find( Department.class, 20L );
			dept.employees.add( emp );
		} );

		// REV 3: delete department
		scope.inTransaction( session -> {
			var dept = session.find( Department.class, 20L );
			session.remove( dept );
		} );

		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( Department.class, 20L );
			// Department: ADD + DEL = 2 revisions
			assertEquals( 2, history.size(), "Department should have 2 history entries" );
			assertEquals( "Hist Engineering", history.get( 0 ).entity().name );
		} );
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "UniOtmDepartment")
	static class Department {
		@Id
		long id;
		String name;
		@OneToMany
		@JoinColumn(name = "department_id")
		List<Employee> employees = new ArrayList<>();

		Department() {}

		Department(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	@Audited
	@Entity(name = "UniOtmEmployee")
	static class Employee {
		@Id
		long id;
		String name;

		Employee() {}

		Employee(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}
}
