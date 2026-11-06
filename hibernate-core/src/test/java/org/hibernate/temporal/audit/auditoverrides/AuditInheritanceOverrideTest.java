package org.hibernate.temporal.audit.auditoverrides;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;

import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.AuditLogFactory;
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
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNoColumn;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNonNullableColumn;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNullableColumn;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertTable;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditInheritanceOverrideTest.SingleBase.class,
		AuditInheritanceOverrideTest.SingleSub.class,
		AuditInheritanceOverrideTest.Target.class,
		AuditInheritanceOverrideTest.JoinedBase.class,
		AuditInheritanceOverrideTest.JoinedSub.class,
		AuditInheritanceOverrideTest.UnionBase.class,
		AuditInheritanceOverrideTest.UnionSub.class,
		AuditInheritanceOverrideTest.SecondarySingleBase.class,
		AuditInheritanceOverrideTest.SecondarySingleSub.class,
		AuditInheritanceOverrideTest.SecondaryJoinedBase.class,
		AuditInheritanceOverrideTest.SecondaryJoinedSub.class,
		AuditInheritanceOverrideTest.IntermediateEntity.class,
		AuditInheritanceOverrideTest.NearestEntity.class,
		AuditInheritanceOverrideTest.InheritedAuditEntity.class
})
@SessionFactory(useCollectingStatementObserver = true)
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditInheritanceOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditInheritanceOverrideTest {
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
			final var target = new Target();
			target.id = 1L;
			target.name = "target";
			session.persist( target );
			session.persist( withTargets( initialize( new SingleBase(), 1L ), target ) );
			session.persist( withTargets( initialize( new SingleSub(), 2L ), target ) );
			session.persist( initialize( new JoinedBase(), 1L ) );
			session.persist( initialize( new JoinedSub(), 2L ) );
			session.persist( initialize( new UnionBase(), 1L ) );
			session.persist( initialize( new UnionSub(), 2L ) );

			final var secondarySingleBase = new SecondarySingleBase();
			secondarySingleBase.id = 1L;
			secondarySingleBase.restored = "base restored";
			secondarySingleBase.hidden = "base hidden";
			session.persist( secondarySingleBase );
			final var secondarySingleSub = new SecondarySingleSub();
			secondarySingleSub.id = 2L;
			secondarySingleSub.restored = "sub restored";
			secondarySingleSub.hidden = "sub hidden";
			secondarySingleSub.ownRestored = "own restored";
			secondarySingleSub.ownHidden = "own hidden";
			session.persist( secondarySingleSub );
			final var secondaryJoinedBase = new SecondaryJoinedBase();
			secondaryJoinedBase.id = 1L;
			secondaryJoinedBase.restored = "base restored";
			secondaryJoinedBase.hidden = "base hidden";
			session.persist( secondaryJoinedBase );
			final var secondaryJoinedSub = new SecondaryJoinedSub();
			secondaryJoinedSub.id = 2L;
			secondaryJoinedSub.restored = "sub restored";
			secondaryJoinedSub.hidden = "sub hidden";
			secondaryJoinedSub.ownRestored = "own restored";
			secondaryJoinedSub.ownHidden = "own hidden";
			session.persist( secondaryJoinedSub );

			final var intermediate = new IntermediateEntity();
			intermediate.id = 2L;
			intermediate.secret = "intermediate secret";
			intermediate.visible = "intermediate visible";
			session.persist( intermediate );
			final var nearest = new NearestEntity();
			nearest.id = 3L;
			nearest.secret = "nearest secret";
			nearest.visible = "nearest visible";
			session.persist( nearest );
			final var inherited = new InheritedAuditEntity();
			inherited.id = 5L;
			inherited.tags.add( "first" );
			session.persist( inherited );
		} );
		scope.inTransaction( session -> {
			session.find( SingleSub.class, 2L ).restored = "updated";
			session.find( JoinedSub.class, 2L ).restored = "updated";
			session.find( UnionSub.class, 2L ).restored = "updated";
			session.find( IntermediateEntity.class, 2L ).secret = "updated secret";
			session.find( NearestEntity.class, 3L ).visible = "updated visible";
			session.find( InheritedAuditEntity.class, 5L ).tags.add( "second" );
		} );
	}

	private static <T extends Fields> T initialize(T entity, long id) {
		entity.id = id;
		entity.restored = "restored";
		entity.hidden = "hidden";
		entity.declaredExcluded = "declared";
		return entity;
	}

	private static SingleBase withTargets(SingleBase entity, Target target) {
		entity.target = target;
		entity.uniformTarget = target;
		return entity;
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void auditTablesUseEffectiveExclusions(DomainModelScope scope) {
		for ( var name : new String[] { "override_single_AUD", "override_joined_AUD" } ) {
			assertNullableColumn( assertTable( scope, name, "restored", "hidden", "declaredExcluded" ), "restored" );
		}
		assertTable( scope, "override_joined_sub_AUD", "detail" );
		assertNoColumn( assertTable( scope, "override_union_AUD", "hidden" ), "restored" );
		assertNoColumn( assertTable( scope, "override_union_AUD" ), "declaredExcluded" );
		assertNoColumn( assertTable( scope, "override_union_sub_AUD", "restored", "declaredExcluded" ), "hidden" );

		final var current = assertTable( scope, "override_single", "target_id", "hidden" );
		final var audit = assertTable( scope, "override_single_AUD", "target_id", "hidden" );
		for ( var name : new String[] { "target_id", "hidden" } ) {
			assertNonNullableColumn( current, name );
			assertNullableColumn( audit, name );
		}

		for ( var name : new String[] { "secondary_single_details_AUD", "secondary_joined_details_AUD" } ) {
			assertNullableColumn( assertTable( scope, name, "restored", "hidden" ), "restored" );
		}
		assertNoColumn( assertTable( scope, "secondary_single_own_AUD", "ownRestored" ), "ownHidden" );
		assertNoColumn( assertTable( scope, "secondary_joined_own_AUD", "ownRestored" ), "ownHidden" );

		assertNoColumn( assertTable( scope, "msc_intermediate_AUD", "secret" ), "visible" );
		assertNoColumn( assertTable( scope, "msc_nearest_AUD", "visible" ), "secret" );
		assertThat( scope.getDomainModel().getEntityBinding( IntermediateEntity.class.getName() )
				.getProperty( "secret" ).isOptimisticLocked() ).isFalse();
	}

	@Test
	void singleTableReadsUseLoadedPersister(SessionFactoryScope scope) {
		assertAuditReads( scope, SingleBase.class, SingleSub.class );
		assertNullableBaseRow( scope, "override_single_AUD" );
		assertSecondaryReads(
				scope,
				SecondarySingleBase.class,
				SecondarySingleSub.class,
				base -> new String[] { base.restored, base.hidden },
				sub -> new String[] { sub.restored, sub.hidden, sub.ownRestored, sub.ownHidden }
		);
	}

	@Test
	void joinedReadsUseLoadedPersister(SessionFactoryScope scope) {
		assertAuditReads( scope, JoinedBase.class, JoinedSub.class );
		assertNullableBaseRow( scope, "override_joined_AUD" );
		assertSecondaryReads(
				scope,
				SecondaryJoinedBase.class,
				SecondaryJoinedSub.class,
				base -> new String[] { base.restored, base.hidden },
				sub -> new String[] { sub.restored, sub.hidden, sub.ownRestored, sub.ownHidden }
		);
	}

	@Test
	void tablePerClassResolvesEachTableIndependently(SessionFactoryScope scope) {
		assertAuditReads( scope, UnionBase.class, UnionSub.class );
	}

	@Test
	void polymorphicPointLoadsPreserveExcludedRows(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var sub = auditLog.find( SingleBase.class, 2L, 1 );
			assertThat( sub ).isInstanceOf( SingleSub.class );
			assertThat( sub.target ).isNull();
			assertThat( sub.hidden ).isNull();
			assertThat( sub.uniformTarget.name ).isEqualTo( "target" );
			assertThat( auditLog.find( SingleBase.class, 1L, 1 ).target.name ).isEqualTo( "target" );
		}
		final var observer = scope.getCollectingStatementObserver();
		observer.clear();
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var sub = session.find( SingleBase.class, 2L );
			assertThat( sub ).isInstanceOf( SingleSub.class );
			assertThat( sub.target ).isNull();
			assertThat( sub.hidden ).isNull();
			assertThat( sub.uniformTarget.name ).isEqualTo( "target" );
			// Only the uniformly audited mandatory association retains its implicit inner join.
			observer.assertQuery( 0 ).containsToken( " join ", 1 );
			assertThat( session.find( SingleBase.class, 1L ).target.name ).isEqualTo( "target" );
		}
	}

	@Test
	void currentStateRemainsMandatory(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final var sub = session.find( SingleBase.class, 2L );
			assertThat( sub ).isInstanceOf( SingleSub.class );
			assertThat( sub.target.name ).isEqualTo( "target" );
			assertThat( sub.uniformTarget.name ).isEqualTo( "target" );
			assertThat( sub.hidden ).isEqualTo( "hidden" );
		} );
	}

	@Test
	void excludedMandatoryColumnsStoreNull(SessionFactoryScope scope) {
		scope.inSession( session -> {
			assertThat( session.createNativeQuery(
					"select count(*) from override_single_AUD where id = 2 and REV = 1"
							+ " and target_id is null and hidden is null",
					Long.class
			).getSingleResult() ).isEqualTo( 1L );
			assertThat( session.createNativeQuery(
					"select hidden from override_single_AUD where id = 1 and REV = 1",
					String.class
			).getSingleResult() ).isEqualTo( "hidden" );
		} );
	}

	@Test
	void collectionOfEntityAuditedThroughMappedSuperclass(DomainModelScope scope, SessionFactoryScope sfScope) {
		assertThat( scope.getDomainModel()
				.getCollectionBinding( InheritedAuditEntity.class.getName() + ".tags" )
				.getAuxiliaryTable() ).isNotNull();
		try (var auditLog = AuditLogFactory.create( sfScope.getSessionFactory() )) {
			assertThat( auditLog.find( InheritedAuditEntity.class, 5L, 1 ).tags ).containsExactly( "first" );
			assertThat( auditLog.find( InheritedAuditEntity.class, 5L, 2 ).tags )
					.containsExactlyInAnyOrder( "first", "second" );
		}
	}

	@Test
	void nearestOverridesControlAuditReads(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var intermediate = auditLog.find( IntermediateEntity.class, 2L, 1 );
			assertThat( intermediate.secret ).isEqualTo( "intermediate secret" );
			assertThat( intermediate.visible ).isNull();
			assertThat( auditLog.find( IntermediateEntity.class, 2L, 2 ).secret )
					.isEqualTo( "updated secret" );
			final var nearest = auditLog.find( NearestEntity.class, 3L, 1 );
			assertThat( nearest.secret ).isNull();
			assertThat( nearest.visible ).isEqualTo( "nearest visible" );
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 2 ).openSession()) {
			final var entity = session.createSelectionQuery( "from NearestEntity", NearestEntity.class )
					.getSingleResult();
			assertThat( entity.secret ).isNull();
			assertThat( entity.visible ).isEqualTo( "updated visible" );
		}
	}

	private static <T extends Fields> void assertAuditReads(
			SessionFactoryScope scope,
			Class<T> baseType,
			Class<? extends T> subType
	) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			assertBaseValues( auditLog.find( baseType, 1L, 1 ) );
			assertSubValues( auditLog.find( subType, 2L, 1 ) );
			assertThat( auditLog.find( subType, 2L, 2 ).restored ).isEqualTo( "updated" );
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			assertSubValues( session.createSelectionQuery( "from " + entityName( subType ), subType )
					.getSingleResult() );
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var results = session.createSelectionQuery(
					"from " + entityName( baseType ) + " order by id",
					baseType
			).getResultList();
			assertThat( results ).hasSize( 2 );
			assertBaseValues( results.get( 0 ) );
			assertThat( results.get( 1 ) ).isInstanceOf( subType );
			assertSubValues( results.get( 1 ) );
		}
	}

	private static void assertBaseValues(Fields entity) {
		assertThat( entity.restored ).isNull();
		assertThat( entity.declaredExcluded ).isNull();
		assertThat( entity.hidden ).isEqualTo( "hidden" );
	}

	private static void assertSubValues(Fields entity) {
		assertThat( entity.restored ).isEqualTo( "restored" );
		assertThat( entity.declaredExcluded ).isEqualTo( "declared" );
		assertThat( entity.hidden ).isNull();
	}

	private static void assertNullableBaseRow(SessionFactoryScope scope, String tableName) {
		scope.inSession( session -> {
			final var value = session.createNativeQuery(
					"select restored from " + tableName + " where id = 1 and REV = 1",
					String.class
			).getSingleResult();
			assertThat( value ).isNull();
		} );
	}

	/**
	 * {@code baseValues} extracts {@code (restored, hidden)},
	 * {@code subValues} extracts {@code (restored, hidden, ownRestored, ownHidden)}.
	 */
	private static <B, S extends B> void assertSecondaryReads(
			SessionFactoryScope scope,
			Class<B> baseType,
			Class<S> subType,
			Function<B, String[]> baseValues,
			Function<S, String[]> subValues
	) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			assertThat( baseValues.apply( auditLog.find( baseType, 1L, 1 ) ) )
					.containsExactly( null, "base hidden" );
			assertThat( subValues.apply( auditLog.find( subType, 2L, 1 ) ) )
					.containsExactly( "sub restored", null, "own restored", null );
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var sub = session.createSelectionQuery( "from " + entityName( subType ), subType )
					.getSingleResult();
			assertThat( subValues.apply( sub ) ).containsExactly( "sub restored", null, "own restored", null );
		}
	}

	private static String entityName(Class<?> type) {
		return type.getAnnotation( Entity.class ).name();
	}

	@MappedSuperclass
	static class Fields {
		@Id
		long id;
		@Column(nullable = false)
		String restored;
		@Column(nullable = false)
		String hidden;
		@Audited.Excluded
		String declaredExcluded;
	}

	@Entity(name = "OverrideSingleBase")
	@Table(name = "override_single")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class SingleBase extends Fields {
		@ManyToOne(optional = false)
		@JoinColumn(name = "target_id", nullable = false)
		Target target;
		@ManyToOne(optional = false)
		@JoinColumn(name = "uniform_target_id", nullable = false)
		Target uniformTarget;
	}

	@Entity(name = "OverrideSingleSub")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "declaredExcluded")
	@Audited.Override(name = "hidden", isAudited = false)
	@Audited.Override(name = "target", isAudited = false)
	static class SingleSub extends SingleBase {
	}

	@Entity(name = "MandatoryOverrideTarget")
	@Table(name = "mandatory_override_target")
	@Audited
	static class Target {
		@Id
		long id;
		String name;
	}

	@Entity(name = "OverrideJoinedBase")
	@Table(name = "override_joined")
	@Inheritance(strategy = InheritanceType.JOINED)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class JoinedBase extends Fields {
	}

	@Entity(name = "OverrideJoinedSub")
	@Table(name = "override_joined_sub")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "declaredExcluded")
	@Audited.Override(name = "hidden", isAudited = false)
	static class JoinedSub extends JoinedBase {
		String detail = "detail";
	}

	@Entity(name = "OverrideUnionBase")
	@Table(name = "override_union")
	@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class UnionBase extends Fields {
	}

	@Entity(name = "OverrideUnionSub")
	@Table(name = "override_union_sub")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "declaredExcluded")
	@Audited.Override(name = "hidden", isAudited = false)
	static class UnionSub extends UnionBase {
	}

	@Entity(name = "SecondarySingleBase")
	@Table(name = "secondary_single")
	@SecondaryTable(name = "secondary_single_details")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class SecondarySingleBase {
		@Id
		long id;
		@Column(table = "secondary_single_details", nullable = false)
		String restored;
		@Column(table = "secondary_single_details")
		String hidden;
	}

	@Entity(name = "SecondarySingleSub")
	@SecondaryTable(name = "secondary_single_own")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "hidden", isAudited = false)
	@Audited.Override(name = "ownRestored")
	@Audited.Override(name = "ownHidden", isAudited = false)
	static class SecondarySingleSub extends SecondarySingleBase {
		@Column(table = "secondary_single_own")
		@Audited.Excluded
		String ownRestored;
		@Column(table = "secondary_single_own")
		String ownHidden;
	}

	@Entity(name = "SecondaryJoinedBase")
	@Table(name = "secondary_joined")
	@SecondaryTable(name = "secondary_joined_details")
	@Inheritance(strategy = InheritanceType.JOINED)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class SecondaryJoinedBase {
		@Id
		long id;
		@Column(table = "secondary_joined_details", nullable = false)
		String restored;
		@Column(table = "secondary_joined_details")
		String hidden;
	}

	@Entity(name = "SecondaryJoinedSub")
	@Table(name = "secondary_joined_sub")
	@SecondaryTable(name = "secondary_joined_own")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "hidden", isAudited = false)
	@Audited.Override(name = "ownRestored")
	@Audited.Override(name = "ownHidden", isAudited = false)
	static class SecondaryJoinedSub extends SecondaryJoinedBase {
		@Column(table = "secondary_joined_own")
		@Audited.Excluded
		String ownRestored;
		@Column(table = "secondary_joined_own")
		String ownHidden;
	}

	@MappedSuperclass
	static class Base {
		@Id
		long id;
		@Audited.Excluded
		String secret;
		String visible;
	}

	@MappedSuperclass
	@Audited.Override(name = "secret")
	@Audited.Override(name = "visible", isAudited = false)
	static class Intermediate extends Base {
	}

	@Entity(name = "IntermediateEntity")
	@Table(name = "msc_intermediate")
	@Audited
	static class IntermediateEntity extends Intermediate {
	}

	@Entity(name = "NearestEntity")
	@Table(name = "msc_nearest")
	@Audited
	@Audited.Override(name = "secret", isAudited = false)
	@Audited.Override(name = "visible")
	static class NearestEntity extends Intermediate {
	}

	@MappedSuperclass
	@Audited
	static class AuditedBase {
		@Id
		long id;
	}

	@Entity(name = "MscInheritedAudit")
	@Table(name = "msc_inherited")
	static class InheritedAuditEntity extends AuditedBase {
		@ElementCollection
		@CollectionTable(name = "msc_inherited_tags")
		Set<String> tags = new HashSet<>();
	}
}
