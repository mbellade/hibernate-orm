/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit.collection;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import org.hibernate.annotations.Audited;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.SharedSessionContract;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests unidirectional @ManyToMany auditing (join table).
 */
@AuditedTest
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditManyToManyTest.Student.class,
		AuditManyToManyTest.Course.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.collection.AuditManyToManyTest$TxIdSupplier"))
class AuditManyToManyTest {
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

		// REV 1: student enrolled in one course
		scope.inTransaction( session -> {
			var course = new Course( 1L, "Math" );
			session.persist( course );
			var student = new Student( 1L, "Alice" );
			student.courses.add( course );
			session.persist( student );
		} );

		// REV 2: enroll in second course
		scope.inTransaction( session -> {
			var course = new Course( 2L, "Physics" );
			session.persist( course );
			var student = session.find( Student.class, 1L );
			student.courses.add( course );
		} );

		// REV 3: drop first course
		scope.inTransaction( session -> {
			var student = session.find( Student.class, 1L );
			student.courses.removeIf( c -> c.id == 1L );
		} );

		// REV 4: delete student (bulk removal of collection)
		scope.inTransaction( session -> {
			var student = session.find( Student.class, 1L );
			session.remove( student );
		} );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			// Student: ADD + 2 collection changes + DEL = 4 revisions
			assertEquals( 4, auditLog.getRevisions( Student.class, 1L ).size(),
					"Student should have 4 revisions (ADD + 2 collection changes + DEL)" );
			assertEquals( 1, auditLog.getRevisions( Course.class, 1L ).size() );
			assertEquals( 1, auditLog.getRevisions( Course.class, 2L ).size() );
		} );
	}

	@Test
	void testPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		// REV 1: student + Math
		scope.inTransaction( session -> {
			var course = new Course( 10L, "PIT Math" );
			session.persist( course );
			var student = new Student( 10L, "PIT Alice" );
			student.courses.add( course );
			session.persist( student );
		} );

		// REV 2: add Physics
		scope.inTransaction( session -> {
			var course = new Course( 11L, "PIT Physics" );
			session.persist( course );
			session.find( Student.class, 10L ).courses.add( course );
		} );

		// At REV 1: 1 course
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 101 ).openSession() ) {
			var student = s.find( Student.class, 10L );
			assertNotNull( student );
			assertEquals( 1, student.courses.size(), "At REV 1, student should have 1 course" );
			assertEquals( "PIT Math", student.courses.get( 0 ).name );
		}

		// At REV 2: 2 courses
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 102 ).openSession() ) {
			var student = s.find( Student.class, 10L );
			assertNotNull( student );
			assertEquals( 2, student.courses.size(), "At REV 2, student should have 2 courses" );
		}

		// REV 3: drop Math
		scope.inTransaction( session -> {
			session.find( Student.class, 10L ).courses.removeIf( c -> c.id == 10L );
		} );

		// At REV 3: 1 course (Physics only)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 103 ).openSession() ) {
			var student = s.find( Student.class, 10L );
			assertNotNull( student );
			assertEquals( 1, student.courses.size(), "At REV 3, student should have 1 course" );
			assertEquals( "PIT Physics", student.courses.get( 0 ).name );
		}
	}

	/**
	 * Bulk recreation (clear + re-add): only diff audit rows should be written.
	 */
	@Test
	void testPointInTimeReadAfterRecreate(SessionFactoryScope scope) {
		currentTxId = 300;

		// REV 1: student enrolled in Math + Physics
		scope.inTransaction( session -> {
			var c1 = new Course( 30L, "Rec Math" );
			var c2 = new Course( 31L, "Rec Physics" );
			session.persist( c1 );
			session.persist( c2 );
			var student = new Student( 30L, "Rec Alice" );
			student.courses.add( c1 );
			student.courses.add( c2 );
			session.persist( student );
		} );

		// REV 2: recreate — clear and re-add only Physics + new Chemistry
		scope.inTransaction( session -> {
			var c3 = new Course( 32L, "Rec Chemistry" );
			session.persist( c3 );
			var student = session.find( Student.class, 30L );
			student.courses.clear();
			student.courses.add( session.find( Course.class, 31L ) );
			student.courses.add( c3 );
		} );

		// Student: ADD + recreate = 2 revisions (not more)
		scope.inSession( session -> {
			assertEquals( 2, session.getAuditLog().getRevisions( Student.class, 30L ).size(),
					"Student should have exactly 2 revisions (ADD + recreate)" );
		} );

		// At REV 1: 2 courses (Math + Physics)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 301 ).openSession() ) {
			var student = s.find( Student.class, 30L );
			assertNotNull( student );
			assertEquals( 2, student.courses.size(), "At REV 1, student should have 2 courses" );
		}

		// At REV 2: 2 courses (Physics + Chemistry — Math dropped)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 302 ).openSession() ) {
			var student = s.find( Student.class, 30L );
			assertNotNull( student );
			assertEquals( 2, student.courses.size(), "At REV 2, student should have 2 courses" );
			var names = student.courses.stream().map( c -> c.name ).sorted().toList();
			assertEquals( List.of( "Rec Chemistry", "Rec Physics" ), names );
		}
	}

	@Test
	void testGetHistory(SessionFactoryScope scope) {
		currentTxId = 200;

		scope.inTransaction( session -> {
			var course = new Course( 20L, "Hist Math" );
			session.persist( course );
			var student = new Student( 20L, "Hist Alice" );
			student.courses.add( course );
			session.persist( student );
		} );

		scope.inTransaction( session -> {
			var course = new Course( 21L, "Hist Physics" );
			session.persist( course );
			session.find( Student.class, 20L ).courses.add( course );
		} );

		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( Student.class, 20L );
			assertEquals( 2, history.size(), "Student has ADD + MOD (collection change)" );
			assertEquals( "Hist Alice", history.get( 0 ).entity().name );
		} );
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "Student")
	static class Student {
		@Id
		long id;
		String name;
		@ManyToMany
		List<Course> courses = new ArrayList<>();

		Student() {}

		Student(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	@Audited
	@Entity(name = "Course")
	static class Course {
		@Id
		long id;
		String name;

		Course() {}

		Course(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}
}
