/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.mapping.converted.converter.registrations.genericentity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test that {@link org.hibernate.annotations.ConverterRegistration} works
 * correctly when an entity has a generic type parameter bounded by another
 * class (e.g. {@code Book<T extends Person>}).
 *
 * @author Vincent Bouthinon
 * @author Marco Belladelli
 */
@DomainModel(
		annotatedClasses = ConverterRegistrationWithGenericEntityTest.Book.class,
		annotatedPackageNames = "org.hibernate.orm.test.mapping.converted.converter.registrations.genericentity"
)
@SessionFactory
@Jira("https://hibernate.atlassian.net/browse/HHH-20276")
class ConverterRegistrationWithGenericEntityTest {

	@Test
	void testPersistAndReadGenericEntity(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final Book<?> book = new Book<>();
			book.setTitle( "Dune" );
			session.persist( book );
		} );

		scope.inTransaction( session -> {
			final Book<?> book = session.createSelectionQuery( "from Book", Book.class ).getSingleResult();
			assertThat( book.getTitle() ).isEqualTo( "[Dune]" );
		} );
	}

	@AfterAll
	void tearDown(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Entity(name = "Book")
	@Table(name = "book_hhh20276")
	public static class Book<T extends Person> {

		@Id
		@GeneratedValue
		private Long id;

		private String title;

		public Long getId() {
			return id;
		}

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}
	}

	public static class Person {
	}

	@Converter(autoApply = true)
	public static class StringConverter implements AttributeConverter<String, String> {

		@Override
		public String convertToDatabaseColumn(String attribute) {
			return attribute;
		}

		@Override
		public String convertToEntityAttribute(String dbData) {
			return dbData == null ? null : "[" + dbData + "]";
		}
	}
}
