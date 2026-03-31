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
 * Tests unidirectional @OneToMany with @JoinColumn auditing.
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

	@Test
	void testWriteSide(SessionFactoryScope scope) {
		currentTxId = 0;

		// REV 1: department + one employee
		scope.inTransaction( session -> {
			var emp = new Employee( 1L, "Alice" );
			session.persist( emp );
			var dept = new Department( 1L, "Engineering" );
			dept.employees.add( emp );
			session.persist( dept );
		} );

		// REV 2: add second employee
		scope.inTransaction( session -> {
			var emp = new Employee( 2L, "Bob" );
			session.persist( emp );
			var dept = session.find( Department.class, 1L );
			dept.employees.add( emp );
		} );

		// REV 3: remove first employee from department
		scope.inTransaction( session -> {
			var dept = session.find( Department.class, 1L );
			dept.employees.removeIf( e -> e.id == 1L );
		} );

		// REV 4: delete department
		scope.inTransaction( session -> {
			var dept = session.find( Department.class, 1L );
			session.remove( dept );
		} );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			// Department: REV 1 (ADD) + REV 2 (collection change) + REV 3 (collection change) + REV 4 (DEL)
			var deptRevs = auditLog.getRevisions( Department.class, 1L );
			assertEquals( 4, deptRevs.size(),
					"Department should have 4 revisions (ADD + 2 collection changes + DEL)" );

			// Employees: only ADD revisions (FK changes tracked on parent side, not child)
			assertEquals( 1, auditLog.getRevisions( Employee.class, 1L ).size(),
					"Employee 1 should have 1 revision (ADD only)" );
			assertEquals( 1, auditLog.getRevisions( Employee.class, 2L ).size(),
					"Employee 2 should have 1 revision (ADD only)" );
		} );
	}

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
			// Department: ADD + MOD (collection change) + DEL = 3 revisions
			assertEquals( 3, history.size(), "Department should have 3 history entries" );
			assertEquals( "Hist Engineering", history.get( 0 ).entity().name );
		} );
	}

	/**
	 * Bulk recreation (clear + re-add): only diff audit rows should be written.
	 */
	@Test
	void testPointInTimeReadAfterRecreate(SessionFactoryScope scope) {
		currentTxId = 300;

		// REV 1: department with Alice + Bob
		scope.inTransaction( session -> {
			var e1 = new Employee( 30L, "Rec Alice" );
			var e2 = new Employee( 31L, "Rec Bob" );
			session.persist( e1 );
			session.persist( e2 );
			var dept = new Department( 30L, "Rec Engineering" );
			dept.employees.add( e1 );
			dept.employees.add( e2 );
			session.persist( dept );
		} );

		// REV 2: recreate — clear and re-add Bob + new Charlie
		scope.inTransaction( session -> {
			var e3 = new Employee( 32L, "Rec Charlie" );
			session.persist( e3 );
			var dept = session.find( Department.class, 30L );
			dept.employees.clear();
			dept.employees.add( session.find( Employee.class, 31L ) );
			dept.employees.add( e3 );
		} );

		// Department: ADD + recreate = 2 revisions (not more)
		scope.inSession( session -> {
			assertEquals( 2, session.getAuditLog().getRevisions( Department.class, 30L ).size(),
					"Department should have exactly 2 revisions (ADD + recreate)" );
		} );

		// At REV 1: 2 employees
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 301 ).openSession() ) {
			var dept = s.find( Department.class, 30L );
			assertNotNull( dept );
			assertEquals( 2, dept.employees.size(), "At REV 1, department should have 2 employees" );
		}

		// At REV 2: 2 employees (Bob + Charlie — Alice dropped)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 302 ).openSession() ) {
			var dept = s.find( Department.class, 30L );
			assertNotNull( dept );
			assertEquals( 2, dept.employees.size(), "At REV 2, department should have 2 employees" );
			var names = dept.employees.stream().map( e -> e.name ).sorted().toList();
			assertEquals( List.of( "Rec Bob", "Rec Charlie" ), names );
		}
	}

	@Test
	void testChildPropertyUpdate(SessionFactoryScope scope) {
		currentTxId = 400;

		// REV 1: department + employee
		scope.inTransaction( session -> {
			var emp = new Employee( 40L, "Upd Alice" );
			session.persist( emp );
			var dept = new Department( 40L, "Upd Engineering" );
			dept.employees.add( emp );
			session.persist( dept );
		} );

		// REV 2: update employee name (no collection change)
		scope.inTransaction( session -> {
			var emp = session.find( Employee.class, 40L );
			emp.name = "Upd Alice v2";
		} );

		// Employee should have 2 revisions (ADD + MOD)
		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			assertEquals( 2, auditLog.getRevisions( Employee.class, 40L ).size(),
					"Employee should have 2 revisions (ADD + property update)" );
			// Department: only 1 revision (initial persist) — no collection change
			assertEquals( 1, auditLog.getRevisions( Department.class, 40L ).size(),
					"Department should still have 1 revision" );
		} );

		// Point-in-time: employee name should reflect the update
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 402 ).openSession() ) {
			var dept = s.find( Department.class, 40L );
			assertNotNull( dept );
			assertEquals( 1, dept.employees.size() );
			assertEquals( "Upd Alice v2", dept.employees.get( 0 ).name );
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
		@JoinColumn(name = "department_id")
		List<Employee> employees = new ArrayList<>();

		Department() {}

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

		Employee() {}

		Employee(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}
}
