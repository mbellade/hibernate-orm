/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.querycache;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Tuple;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.RequiresDialect;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.hibernate.stat.Statistics;

import static org.assertj.core.api.Assertions.assertThat;

@DomainModel(annotatedClasses = {
		NativeQueryCacheMixedReturnTypeTest.TestUser.class,
		NativeQueryCacheMixedReturnTypeTest.TestUserProfile.class
})
@SessionFactory(generateStatistics = true)
@ServiceRegistry(settings = {
		@Setting(name = AvailableSettings.USE_SECOND_LEVEL_CACHE, value = "true"),
		@Setting(name = AvailableSettings.USE_QUERY_CACHE, value = "true")
})
@RequiresDialect(H2Dialect.class)
public class NativeQueryCacheMixedReturnTypeTest {

	private static final String NATIVE_QUERY = "SELECT u1.* FROM TEST_USER u1, TEST_USER u2 WHERE u2.ID = u1.ID";
	private static final String NATIVE_QUERY_EXTRA_COLS_FIRST =
			"SELECT u1.EXTRA_COL1, u1.EXTRA_COL2, u1.ID, u1.NAME, u1.EMAIL, u1.AGE, u1.ADDRESS, u1.PHONE"
					+ " FROM TEST_USER u1";
	private static final String NATIVE_QUERY_EXTRA_COLS_SCATTERED =
			"SELECT u1.ID, u1.EXTRA_COL1, u1.NAME, u1.EMAIL, u1.EXTRA_COL2, u1.AGE, u1.ADDRESS, u1.PHONE"
					+ " FROM TEST_USER u1";
	private static final String NATIVE_QUERY_ALL_COLS =
			"SELECT u1.ID, u1.NAME, u1.EMAIL, u1.EXTRA_COL1, u1.AGE, u1.ADDRESS, u1.PHONE, u1.EXTRA_COL2"
					+ " FROM TEST_USER u1";
	private static final String NATIVE_QUERY_ENTITY_COLS =
			"SELECT u1.ID, u1.NAME, u1.EMAIL, u1.AGE, u1.ADDRESS, u1.PHONE"
			+ " FROM TEST_USER u1, TEST_USER u2 WHERE u2.ID = u1.ID";

	@BeforeAll
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			// Add extra columns to the table that are not mapped in the entity
			session.createNativeMutationQuery( "ALTER TABLE TEST_USER ADD COLUMN IF NOT EXISTS EXTRA_COL1 VARCHAR(50)" )
					.executeUpdate();
			session.createNativeMutationQuery( "ALTER TABLE TEST_USER ADD COLUMN IF NOT EXISTS EXTRA_COL2 VARCHAR(50)" )
					.executeUpdate();

			// Insert test data with extra columns
			session.createNativeMutationQuery(
							"INSERT INTO TEST_USER (ID, NAME, EMAIL, AGE, ADDRESS, PHONE, EXTRA_COL1, EXTRA_COL2) VALUES "
							+ "(1, 'john', 'john@test.com', 30, 'ny', '123456', 'ext1', 'ext2')" )
					.executeUpdate();
		} );
	}

	@AfterEach
	public void cleanCache(SessionFactoryScope scope) {
		scope.getSessionFactory().getCache().evictQueryRegions();
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	public void testEntityThenTuple(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with entity return type - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with Tuple return type - cached data is insufficient (entity cached
		// fewer columns), so re-executes and re-populates the cache with complete data
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Third query with Tuple return type - reads from the re-populated cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 1, 0, 0 );
	}

	@Test
	public void testTupleThenEntity(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with Tuple return type - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with entity return type - reads from the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 1, 0, 0 );
	}

	@Test
	public void testEntityThenTupleSameColumnCount(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with entity return type - populates the cache.
		// The query selects exactly the entity-mapped columns, so cached row size
		// matches the column count (no extra columns).
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_ENTITY_COLS, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with Tuple return type - same column count as entity, cached data
		// should be sufficient
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_ENTITY_COLS, Tuple.class );
			query.setCacheable( true );
			assertTestUserTuple( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 1, 0, 0 );
	}

	@Test
	public void testEntityThenTupleExtraColsFirst(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with entity return type - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_FIRST, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with Tuple return type - cached data is insufficient (entity cached
		// fewer columns), so re-executes and re-populates the cache with complete data
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_FIRST, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Third query with Tuple return type - reads from the re-populated cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_FIRST, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 1, 0, 0 );
	}

	@Test
	public void testTupleThenEntityExtraColsFirst(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with Tuple return type - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_FIRST, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with entity return type - reads from the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_FIRST, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 1, 0, 0 );
	}

	@Test
	public void testEntityThenTupleExtraColsScattered(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with entity return type - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_SCATTERED, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with Tuple return type - cached data is insufficient (entity cached
		// fewer columns), so re-executes and re-populates the cache with complete data
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_SCATTERED, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Third query with Tuple return type - reads from the re-populated cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_SCATTERED, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 1, 0, 0 );
	}

	@Test
	public void testTupleThenEntityExtraColsScattered(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with Tuple return type - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_SCATTERED, Tuple.class );
			query.setCacheable( true );
			final var tuple = query.getSingleResult();
			assertTestUserTuple( tuple );
			assertThat( tuple.get( "extra_col1", String.class ) ).isEqualTo( "ext1" );
			assertThat( tuple.get( "extra_col2", String.class ) ).isEqualTo( "ext2" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with entity return type - reads from the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_EXTRA_COLS_SCATTERED, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 1, 0, 0 );
	}

	@Test
	public void testUserProfileThenTestUser(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with TestUserProfile (maps EXTRA_COL1, not AGE) - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_ALL_COLS, TestUserProfile.class );
			query.setCacheable( true );
			final var profile = query.getSingleResult();
			assertThat( profile.getName() ).isEqualTo( "john" );
			assertThat( profile.getEmail() ).isEqualTo( "john@test.com" );
			assertThat( profile.getExtraCol1() ).isEqualTo( "ext1" );
			assertThat( profile.getAddress() ).isEqualTo( "ny" );
			assertThat( profile.getPhone() ).isEqualTo( "123456" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with TestUser (maps AGE, not EXTRA_COL1) - stored mapping is
		// incompatible (no entry for AGE position), so re-executes and re-populates
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_ALL_COLS, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );
	}

	@Test
	public void testTestUserThenUserProfile(SessionFactoryScope scope) {
		final var statistics = scope.getSessionFactory().getStatistics();
		statistics.clear();

		// First query with TestUser (maps AGE, not EXTRA_COL1) - populates the cache
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_ALL_COLS, TestUser.class );
			query.setCacheable( true );
			assertTestUser( query.getSingleResult() );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );

		// Second query with TestUserProfile (maps EXTRA_COL1, not AGE) - stored mapping is
		// incompatible (no entry for EXTRA_COL1 position), so re-executes and re-populates
		scope.inSession( session -> {
			final var query = session.createNativeQuery( NATIVE_QUERY_ALL_COLS, TestUserProfile.class );
			query.setCacheable( true );
			final var profile = query.getSingleResult();
			assertThat( profile.getName() ).isEqualTo( "john" );
			assertThat( profile.getEmail() ).isEqualTo( "john@test.com" );
			assertThat( profile.getExtraCol1() ).isEqualTo( "ext1" );
			assertThat( profile.getAddress() ).isEqualTo( "ny" );
			assertThat( profile.getPhone() ).isEqualTo( "123456" );
		} );

		assertQueryCacheStatistics( statistics, 0, 1, 1 );
	}

	private static void assertQueryCacheStatistics(Statistics statistics, int hits, int misses, int puts) {
		assertThat( statistics.getQueryCacheHitCount() ).isEqualTo( hits );
		assertThat( statistics.getQueryCacheMissCount() ).isEqualTo( misses );
		assertThat( statistics.getQueryCachePutCount() ).isEqualTo( puts );
		statistics.clear();
	}

	private static void assertTestUser(TestUser user) {
		assertThat( user.getName() ).isEqualTo( "john" );
		assertThat( user.getEmail() ).isEqualTo( "john@test.com" );
		assertThat( user.getAge() ).isEqualTo( 30 );
		assertThat( user.getAddress() ).isEqualTo( "ny" );
		assertThat( user.getPhone() ).isEqualTo( "123456" );
	}

	private static void assertTestUserTuple(Tuple tuple) {
		assertThat( tuple.get( "name", String.class ) ).isEqualTo( "john" );
		assertThat( tuple.get( "email", String.class ) ).isEqualTo( "john@test.com" );
		assertThat( tuple.get( "age", Integer.class ) ).isEqualTo( 30 );
		assertThat( tuple.get( "address", String.class ) ).isEqualTo( "ny" );
		assertThat( tuple.get( "phone", String.class ) ).isEqualTo( "123456" );
	}

	@Entity(name = "TestUser")
	@Table(name = "TEST_USER")
	@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
	public static class TestUser {
		@Id
		@Column(name = "ID")
		private Long id;

		@Column(name = "NAME")
		private String name;

		@Column(name = "EMAIL")
		private String email;

		@Column(name = "AGE")
		private Integer age;

		@Column(name = "ADDRESS")
		private String address;

		@Column(name = "PHONE")
		private String phone;

		public Long getId() {
			return id;
		}

		public void setId(Long id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getEmail() {
			return email;
		}

		public void setEmail(String email) {
			this.email = email;
		}

		public Integer getAge() {
			return age;
		}

		public void setAge(Integer age) {
			this.age = age;
		}

		public String getAddress() {
			return address;
		}

		public void setAddress(String address) {
			this.address = address;
		}

		public String getPhone() {
			return phone;
		}

		public void setPhone(String phone) {
			this.phone = phone;
		}
	}

	@Entity(name = "TestUserProfile")
	@Table(name = "TEST_USER")
	@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
	public static class TestUserProfile {
		@Id
		@Column(name = "ID")
		private Long id;

		@Column(name = "NAME")
		private String name;

		@Column(name = "EMAIL")
		private String email;

		@Column(name = "EXTRA_COL1")
		private String extraCol1;

		// Does NOT map AGE — different column subset than TestUser

		@Column(name = "ADDRESS")
		private String address;

		@Column(name = "PHONE")
		private String phone;

		public Long getId() {
			return id;
		}

		public void setId(Long id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getEmail() {
			return email;
		}

		public void setEmail(String email) {
			this.email = email;
		}

		public String getExtraCol1() {
			return extraCol1;
		}

		public void setExtraCol1(String extraCol1) {
			this.extraCol1 = extraCol1;
		}

		public String getAddress() {
			return address;
		}

		public void setAddress(String address) {
			this.address = address;
		}

		public String getPhone() {
			return phone;
		}

		public void setPhone(String phone) {
			this.phone = phone;
		}
	}
}
