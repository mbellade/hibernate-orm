/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.annotations.inheritance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;

import org.hibernate.annotations.Audited;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SessionFactory
@DomainModel(annotatedClasses = {
		TablePerClassSecondaryTableTest.Base.class,
		TablePerClassSecondaryTableTest.Sub.class,
		TablePerClassSecondaryTableTest.BaseReference.class,
})
@Jira("https://hibernate.atlassian.net/browse/HHH-20956")
public class TablePerClassSecondaryTableTest {

	@Test
	public void persistsSecondaryTableAttributes(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final var base = new Base();
			base.id = 0L;
			base.str1 = "v";
			base.str2 = "w";
			session.persist( base );

			final var sub = new Sub();
			sub.id = 1L;
			sub.str1 = "x";
			sub.str2 = "y";
			session.persist( sub );
			session.flush();
		} );

		final Object[][] values = scope.fromTransaction( session -> new Object[][] {
				session.createNativeQuery(
						"select str1, str2 from secondary_table where base_id = :id",
						Object[].class
				)
						.setParameter( "id", 0L )
						.getSingleResult(),
				session.createNativeQuery(
						"select str1, str2 from secondary_table where base_id = :id",
						Object[].class
				)
						.setParameter( "id", 1L )
						.getSingleResult()
		} );
		assertThat( values[0] ).containsExactly( "v", "w" );
		assertThat( values[1] ).containsExactly( "x", "y" );
		final Object[][] loadedValues = scope.fromTransaction( session -> {
			final var base = session.find( Base.class, 0L );
			final var sub = session.find( Sub.class, 1L );
			return new Object[][] {
					{ base.str1, base.str2 },
					{ sub.str1, sub.str2 }
			};
		} );
		assertThat( loadedValues[0] ).containsExactly( "v", "w" );
		assertThat( loadedValues[1] ).containsExactly( "x", "y" );
	}

	@Test
	public void updatesSecondaryTableAttributeAfterLoading(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final var sub = new Sub();
			sub.id = 20L;
			sub.str1 = "before";
			session.persist( sub );
		} );

		scope.inTransaction( session -> {
			final var sub = session.find( Sub.class, 20L );
			sub.str1 = "after";
		} );

		final String updatedValue = scope.fromTransaction( session ->
				session.createNativeQuery(
						"select str1 from secondary_table where base_id = :id",
						String.class
				)
						.setParameter( "id", 20L )
						.getSingleResult()
		);
		assertThat( updatedValue ).isEqualTo( "after" );
	}

	@Test
	public void joinsSubtypeUsingSecondaryTableColumn(SessionFactoryScope scope) {
		final var result = scope.fromTransaction( session -> {
			final var base = new Base();
			base.id = 10L;
			base.str1 = "base";
			session.persist( base );

			final var sub = new Sub();
			sub.id = 11L;
			sub.str1 = "sub";
			session.persist( sub );
			session.flush();

			final var rows = session.createQuery(
					"select b.id, s.id from Base b left join Sub s on s.str1 = b.str1 " +
							"where b.id in (10, 11) order by b.id",
					Object[].class
			).getResultList();

			session.remove( session.find( Base.class, 10L ) );
			session.remove( session.find( Sub.class, 11L ) );
			return rows;
		} );

		assertThat( result ).hasSize( 2 );
		assertThat( result.get( 0 ) ).containsExactly( 10L, null );
		assertThat( result.get( 1 ) ).containsExactly( 11L, 11L );
	}

	@Test
	public void joinsAssociationUsingSecondaryTableColumn(SessionFactoryScope scope) {
		final String value = scope.fromTransaction( session -> {
			final var base = new Base();
			base.id = 30L;
			base.str1 = "association";
			session.persist( base );

			final var reference = new BaseReference();
			reference.id = 31L;
			reference.base = base;
			session.persist( reference );
			session.flush();

			return session.createQuery(
							"select r from BaseReference r join fetch r.base b where b.str1 = :value",
							BaseReference.class
					)
					.setParameter( "value", "association" )
					.getSingleResult()
					.base.str1;
		} );

		assertThat( value ).isEqualTo( "association" );
	}

	@Test
	public void bulkDeleteRootEntityDeletesDescendantSecondaryTableRows(SessionFactoryScope scope) {
		final int deletedEntityCount = scope.fromTransaction( session -> {
			final var sub = new Sub();
			sub.id = 12L;
			sub.str1 = "sub";
			sub.subValue = "secondary";
			session.persist( sub );
			session.flush();

			return session.createMutationQuery( "delete from Base where id = :id" )
					.setParameter( "id", 12L )
					.executeUpdate();
		} );
		assertThat( deletedEntityCount ).isEqualTo( 1 );

		final var remainingRows = scope.fromTransaction( session ->
				session.createNativeQuery(
						"select sub_id from sub_secondary_table where sub_id = :id",
						Long.class
				)
						.setParameter( "id", 12L )
						.getResultList()
		);
		assertThat( remainingRows ).isEmpty();
	}


	@Entity(name = "Base")
	@Table(name = "Base")
	@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
	@SecondaryTable(
			name = "secondary_table",
			pkJoinColumns = @PrimaryKeyJoinColumn(name = "base_id")
	)
	static class Base {
		@Id
		long id;

		@Audited.Excluded
		@Column(name = "str1", table = "secondary_table")
		String str1;

		@Column(name = "str2", table = "secondary_table")
		String str2;
	}

	@Entity(name = "Sub")
	@SecondaryTable(
			name = "sub_secondary_table",
			pkJoinColumns = @PrimaryKeyJoinColumn(name = "sub_id")
	)
	static class Sub extends Base {
		@Column(name = "sub_value", table = "sub_secondary_table")
		String subValue;
	}

	@Entity(name = "BaseReference")
	@Table(name = "base_reference")
	static class BaseReference {
		@Id
		long id;

		@ManyToOne
		@JoinColumn(name = "base_id")
		Base base;
	}
}
