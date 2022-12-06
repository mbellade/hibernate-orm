/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.test.query.criteria;

import java.util.Date;
import java.util.List;

import org.hibernate.dialect.DB2Dialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaWindow;

import org.hibernate.testing.orm.domain.StandardDomainModel;
import org.hibernate.testing.orm.domain.gambit.EntityOfBasics;
import org.hibernate.testing.orm.junit.DialectFeatureChecks;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.RequiresDialectFeature;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SkipForDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Marco Belladelli
 */
@ServiceRegistry
@DomainModel(standardModels = StandardDomainModel.GAMBIT)
@SessionFactory
@RequiresDialectFeature(feature = DialectFeatureChecks.SupportsWindowFunctions.class)
public class CriteriaWindowFunctionTest {
	@BeforeEach
	public void prepareData(SessionFactoryScope scope) {
		scope.inTransaction(
				em -> {
					Date now = new Date();

					EntityOfBasics entity1 = new EntityOfBasics();
					entity1.setId( 1 );
					entity1.setTheString( "5" );
					entity1.setTheInt( 5 );
					entity1.setTheInteger( -1 );
					entity1.setTheDouble( 5.0 );
					entity1.setTheDate( now );
					entity1.setTheBoolean( true );
					em.persist( entity1 );

					EntityOfBasics entity2 = new EntityOfBasics();
					entity2.setId( 2 );
					entity2.setTheString( "6" );
					entity2.setTheInt( 6 );
					entity2.setTheInteger( -2 );
					entity2.setTheDouble( 6.0 );
					entity2.setTheBoolean( true );
					em.persist( entity2 );

					EntityOfBasics entity3 = new EntityOfBasics();
					entity3.setId( 3 );
					entity3.setTheString( "7" );
					entity3.setTheInt( 7 );
					entity3.setTheInteger( 3 );
					entity3.setTheDouble( 7.0 );
					entity3.setTheBoolean( false );
					entity3.setTheDate( new Date( now.getTime() + 200000L ) );
					em.persist( entity3 );

					EntityOfBasics entity4 = new EntityOfBasics();
					entity4.setId( 4 );
					entity4.setTheString( "13" );
					entity4.setTheInt( 13 );
					entity4.setTheInteger( 4 );
					entity4.setTheDouble( 13.0 );
					entity4.setTheBoolean( false );
					entity4.setTheDate( new Date( now.getTime() + 300000L ) );
					em.persist( entity4 );

					EntityOfBasics entity5 = new EntityOfBasics();
					entity5.setId( 5 );
					entity5.setTheString( "5" );
					entity5.setTheInt( 5 );
					entity5.setTheInteger( 5 );
					entity5.setTheDouble( 9.0 );
					entity5.setTheBoolean( false );
					em.persist( entity5 );
				}
		);
	}

	@AfterEach
	public void tearDown(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> session.createQuery( "delete from EntityOfBasics" ).executeUpdate()
		);
	}

	@Test
	public void testRowNumberWithoutOrder(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
					CriteriaQuery<Long> cr = cb.createQuery( Long.class );
					cr.from( EntityOfBasics.class );

					JpaWindow window = cb.createWindow();
					JpaExpression<Long> rowNumber = cb.rowNumber( window );

					cr.select( rowNumber ).orderBy( cb.asc( cb.literal( 1 ) ) );
					List<Long> resultList = session.createQuery( cr ).getResultList();
					assertEquals( 5, resultList.size() );
					assertEquals( 1L, resultList.get( 0 ) );
					assertEquals( 2L, resultList.get( 1 ) );
					assertEquals( 3L, resultList.get( 2 ) );
					assertEquals( 4L, resultList.get( 3 ) );
					assertEquals( 5L, resultList.get( 4 ) );
				}
		);
	}

	@Test
	public void testFirstValue(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
					CriteriaQuery<Integer> cr = cb.createQuery( Integer.class );
					Root<EntityOfBasics> root = cr.from( EntityOfBasics.class );

					JpaWindow window = cb.createWindow().orderBy( cb.desc( root.get( "theInt" ) ) );
					JpaExpression<Integer> firstValue = cb.firstValue( root.get( "theInt" ), window );

					cr.select( firstValue ).orderBy( cb.asc( cb.literal( 1 ) ) );
					List<Integer> resultList = session.createQuery( cr ).getResultList();
					assertEquals( 5, resultList.size() );
					assertEquals( 13, resultList.get( 0 ) );
					assertEquals( 13, resultList.get( 1 ) );
					assertEquals( 13, resultList.get( 2 ) );
					assertEquals( 13, resultList.get( 3 ) );
					assertEquals( 13, resultList.get( 4 ) );
				}
		);
	}

	@Test
	@SkipForDialect(dialectClass = SQLServerDialect.class)
	@SkipForDialect(dialectClass = DB2Dialect.class)
	public void testNthValue(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
					CriteriaQuery<Integer> cr = cb.createQuery( Integer.class );
					Root<EntityOfBasics> root = cr.from( EntityOfBasics.class );

					JpaWindow window = cb.createWindow().orderBy( cb.desc( root.get( "theInt" ) ) );
					JpaExpression<Integer> nthValue = cb.nthValue(
							root.get( "theInt" ),
							cb.literal( 2 ),
							window
					);

					// todo marco : db2 throws java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1
					//  on getResultList() line, and SqlServer doesn't support nth_value function (even tho it's in Dialect)

					cr.select( nthValue ).orderBy( cb.asc( cb.literal( 1 ), true ) );
					List<Integer> resultList = session.createQuery( cr ).getResultList();
					assertEquals( 5, resultList.size() );
					assertNull( resultList.get( 0 ) );
					assertEquals( 7, resultList.get( 1 ) );
					assertEquals( 7, resultList.get( 2 ) );
					assertEquals( 7, resultList.get( 3 ) );
					assertEquals( 7, resultList.get( 4 ) );
				}
		);
	}

	@Test
	public void testRank(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
					CriteriaQuery<Long> cr = cb.createQuery( Long.class );
					Root<EntityOfBasics> root = cr.from( EntityOfBasics.class );

					JpaWindow window = cb.createWindow()
							.partitionBy( root.get( "theInt" ) )
							.orderBy( cb.asc( root.get( "id" ) ) );
					JpaExpression<Long> rank = cb.rank( window );

					cr.select( rank ).orderBy( cb.asc( cb.literal( 1 ) ) );
					List<Long> resultList = session.createQuery( cr ).getResultList();
					assertEquals( 5, resultList.size() );
					assertEquals( 1L, resultList.get( 0 ) );
					assertEquals( 1L, resultList.get( 1 ) );
					assertEquals( 1L, resultList.get( 2 ) );
					assertEquals( 1L, resultList.get( 3 ) );
					assertEquals( 2L, resultList.get( 4 ) );
				}
		);
	}

	@Test
	public void testReusableWindow(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
					CriteriaQuery<Tuple> cr = cb.createTupleQuery();
					Root<EntityOfBasics> root = cr.from( EntityOfBasics.class );

					JpaWindow window = cb.createWindow()
							.partitionBy( root.get( "theInt" ) )
							.orderBy( cb.asc( root.get( "id" ) ) );
					JpaExpression<Long> rowNumber = cb.rowNumber( window );
					JpaExpression<Double> percentRank = cb.percentRank( window );
					JpaExpression<Double> cumeDist = cb.cumeDist( window );

					cr.multiselect( rowNumber, percentRank, cumeDist ).orderBy( cb.asc( cb.literal( 1 ) ) );
					List<Tuple> resultList = session.createQuery( cr ).getResultList();
					assertEquals( 5, resultList.size() );
					assertEquals( 0D, resultList.get( 0 ).get( 1 ) );
					assertEquals( 0D, resultList.get( 1 ).get( 1 ) );
					assertEquals( 0D, resultList.get( 2 ).get( 1 ) );
					assertEquals( 0D, resultList.get( 3 ).get( 1 ) );
					assertEquals( 1D, resultList.get( 4 ).get( 1 ) );
					assertEquals( 1D, resultList.get( 4 ).get( 2 ) );
				}
		);
	}

	//	@Test
	public void testSumWithFilterAsWindowFunction(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					// todo marco : add filter clause predicate to CriteriaBuilder ?
					//  problem with getting the window functions as aggregate, @see SqmCriteriaNodeBuilder#windowFunction
					TypedQuery<Long> q = session.createQuery(
							"select sum(eob.theInt) filter (where eob.theInt > 5) over (order by eob.theInt) from EntityOfBasics eob order by eob.theInt",
							Long.class
					);
					List<Long> resultList = q.getResultList();
					assertEquals( 5L, resultList.size() );
					assertNull( resultList.get( 0 ) );
					assertNull( resultList.get( 1 ) );
					assertEquals( 6L, resultList.get( 2 ) );
					assertEquals( 13L, resultList.get( 3 ) );
					assertEquals( 26L, resultList.get( 4 ) );
				}
		);
	}

	//	@Test
	public void testFrame(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					// todo marco : how should we do window function frames in Criteria ?
					TypedQuery<Integer> q = session.createQuery(
							"select first_value(eob.theInt) over (order by eob.id rows between 2 preceding and current row) from EntityOfBasics eob order by eob.id",
							Integer.class
					);
					List<Integer> resultList = q.getResultList();
					assertEquals( 5, resultList.size() );
					assertEquals( 5, resultList.get( 0 ) );
					assertEquals( 5, resultList.get( 1 ) );
					assertEquals( 5, resultList.get( 2 ) );
					assertEquals( 6, resultList.get( 3 ) );
					assertEquals( 7, resultList.get( 4 ) );
				}
		);
	}
}
