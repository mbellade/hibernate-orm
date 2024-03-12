/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.boot.model.internal;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.hibernate.AnnotationException;
import org.hibernate.AssertionFailure;
import org.hibernate.MappingException;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.hibernate.annotations.DiscriminatorFormula;
import org.hibernate.annotations.DiscriminatorOptions;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Filters;
import org.hibernate.annotations.ForeignKey;
import org.hibernate.annotations.HQLSelect;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Loader;
import org.hibernate.annotations.Mutability;
import org.hibernate.annotations.NaturalIdCache;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.hibernate.annotations.Polymorphism;
import org.hibernate.annotations.PolymorphismType;
import org.hibernate.annotations.Proxy;
import org.hibernate.annotations.RowId;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLDeleteAll;
import org.hibernate.annotations.SQLDeletes;
import org.hibernate.annotations.SQLInsert;
import org.hibernate.annotations.SQLInserts;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.SQLSelect;
import org.hibernate.annotations.SQLUpdate;
import org.hibernate.annotations.SQLUpdates;
import org.hibernate.annotations.SecondaryRow;
import org.hibernate.annotations.SecondaryRows;
import org.hibernate.annotations.SelectBeforeUpdate;
import org.hibernate.annotations.SoftDelete;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;
import org.hibernate.annotations.Tables;
import org.hibernate.annotations.TypeBinderType;
import org.hibernate.annotations.View;
import org.hibernate.annotations.Where;
import org.hibernate.annotations.common.reflection.ReflectionManager;
import org.hibernate.binder.TypeBinder;
import org.hibernate.boot.model.IdentifierGeneratorDefinition;
import org.hibernate.boot.model.NamedEntityGraphDefinition;
import org.hibernate.boot.model.internal.InheritanceState.ElementsToProcess;
import org.hibernate.boot.model.naming.EntityNaming;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.ImplicitEntityNameSource;
import org.hibernate.boot.model.naming.NamingStrategyHelper;
import org.hibernate.boot.model.relational.QualifiedTableName;
import org.hibernate.boot.models.HibernateAnnotations;
import org.hibernate.boot.spi.AccessType;
import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.boot.spi.PropertyData;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.OptimisticLockStyle;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.engine.spi.FilterDefinition;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.jpa.event.spi.CallbackType;
import org.hibernate.mapping.BasicValue;
import org.hibernate.mapping.CheckConstraint;
import org.hibernate.mapping.Component;
import org.hibernate.mapping.DependantValue;
import org.hibernate.mapping.Join;
import org.hibernate.mapping.JoinedSubclass;
import org.hibernate.mapping.MappedSuperclass;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.RootClass;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.mapping.SingleTableSubclass;
import org.hibernate.mapping.Subclass;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.TableOwner;
import org.hibernate.mapping.UnionSubclass;
import org.hibernate.mapping.Value;
import org.hibernate.models.internal.ClassTypeDetailsImpl;
import org.hibernate.models.spi.AnnotationTarget;
import org.hibernate.models.spi.AnnotationUsage;
import org.hibernate.models.spi.ClassDetails;
import org.hibernate.models.spi.MutableAnnotationUsage;
import org.hibernate.models.spi.TypeDetails;
import org.hibernate.spi.NavigablePath;

import org.jboss.logging.Logger;

import jakarta.persistence.Access;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Cacheable;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.PrimaryKeyJoinColumns;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SecondaryTables;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.UniqueConstraint;

import static org.hibernate.boot.model.internal.AnnotatedClassType.MAPPED_SUPERCLASS;
import static org.hibernate.boot.model.internal.AnnotatedDiscriminatorColumn.buildDiscriminatorColumn;
import static org.hibernate.boot.model.internal.AnnotatedJoinColumn.buildInheritanceJoinColumn;
import static org.hibernate.boot.model.internal.BinderHelper.extractFromPackage;
import static org.hibernate.boot.model.internal.BinderHelper.getMappedSuperclassOrNull;
import static org.hibernate.boot.model.internal.BinderHelper.getOverridableAnnotation;
import static org.hibernate.boot.model.internal.BinderHelper.hasToOneAnnotation;
import static org.hibernate.boot.model.internal.BinderHelper.toAliasEntityMap;
import static org.hibernate.boot.model.internal.BinderHelper.toAliasTableMap;
import static org.hibernate.boot.model.internal.EmbeddableBinder.fillEmbeddable;
import static org.hibernate.boot.model.internal.GeneratorBinder.makeIdGenerator;
import static org.hibernate.boot.model.internal.InheritanceState.getInheritanceStateOfSuperEntity;
import static org.hibernate.boot.model.internal.PropertyBinder.addElementsOfClass;
import static org.hibernate.boot.model.internal.PropertyBinder.hasIdAnnotation;
import static org.hibernate.boot.model.internal.PropertyBinder.processElementAnnotations;
import static org.hibernate.boot.model.internal.PropertyHolderBuilder.buildPropertyHolder;
import static org.hibernate.engine.spi.ExecuteUpdateResultCheckStyle.fromResultCheckStyle;
import static org.hibernate.internal.util.StringHelper.isEmpty;
import static org.hibernate.internal.util.StringHelper.isNotEmpty;
import static org.hibernate.internal.util.StringHelper.nullIfEmpty;
import static org.hibernate.internal.util.StringHelper.unqualify;
import static org.hibernate.jpa.event.internal.CallbackDefinitionResolverLegacyImpl.resolveEmbeddableCallbacks;
import static org.hibernate.jpa.event.internal.CallbackDefinitionResolverLegacyImpl.resolveEntityCallbacks;
import static org.hibernate.mapping.SimpleValue.DEFAULT_ID_GEN_STRATEGY;


/**
 * Stateful binder responsible for interpreting information about an {@link Entity} class
 * and producing a {@link PersistentClass} mapping model object.
 *
 * @author Emmanuel Bernard
 */
public class EntityBinder {

	private static final CoreMessageLogger LOG = Logger.getMessageLogger( CoreMessageLogger.class, EntityBinder.class.getName() );
	private static final String NATURAL_ID_CACHE_SUFFIX = "##NaturalId";

	private MetadataBuildingContext context;

	private String name;
	private ClassDetails annotatedClass;
	private PersistentClass persistentClass;
	private Boolean forceDiscriminator;
	private Boolean insertableDiscriminator;
	private PolymorphismType polymorphismType;
	private boolean lazy;
	private ClassDetails proxyClass;
	private String where;
	// todo : we should defer to InFlightMetadataCollector.EntityTableXref for secondary table tracking;
	//		atm we use both from here; HBM binding solely uses InFlightMetadataCollector.EntityTableXref
	private final java.util.Map<String, Join> secondaryTables = new HashMap<>();
	private final java.util.Map<String, Object> secondaryTableJoins = new HashMap<>();
	private final java.util.Map<String, Join> secondaryTablesFromAnnotation = new HashMap<>();
	private final java.util.Map<String, Object> secondaryTableFromAnnotationJoins = new HashMap<>();

	private final List<AnnotationUsage<Filter>> filters = new ArrayList<>();
	private boolean ignoreIdAnnotations;
	private AccessType propertyAccessType = AccessType.DEFAULT;
	private boolean wrapIdsInEmbeddedComponents;
	private String subselect;

	private boolean isCached;
	private String cacheConcurrentStrategy;
	private String cacheRegion;
	private boolean cacheLazyProperty;
	private String naturalIdCacheRegion;

	/**
	 * Bind an entity class. This can be done in a single pass.
	 */
	public static void bindEntityClass(
			ClassDetails clazzToProcess,
			Map<ClassDetails, InheritanceState> inheritanceStates,
			Map<String, IdentifierGeneratorDefinition> generators,
			MetadataBuildingContext context) {
		if ( LOG.isDebugEnabled() ) {
			LOG.debugf( "Binding entity from annotated class: %s", clazzToProcess.getName() );
		}

		//TODO: be more strict with secondary table allowance (not for ids, not for secondary table join columns etc)

		final InheritanceState inheritanceState = inheritanceStates.get( clazzToProcess );
		final PersistentClass superEntity = getSuperEntity( clazzToProcess, inheritanceStates, context, inheritanceState );
		detectedAttributeOverrideProblem( clazzToProcess, superEntity );

		final PersistentClass persistentClass = makePersistentClass( inheritanceState, superEntity, context );
		final EntityBinder entityBinder = new EntityBinder( clazzToProcess, persistentClass, context );
		entityBinder.bindEntity();
		entityBinder.handleClassTable( inheritanceState, superEntity );
		entityBinder.handleSecondaryTables();
		entityBinder.handleCheckConstraints();
		final PropertyHolder holder = buildPropertyHolder(
				clazzToProcess,
				persistentClass,
				entityBinder,
				context,
				inheritanceStates
		);
		entityBinder.handleInheritance( inheritanceState, superEntity, holder );
		entityBinder.handleIdentifier( holder, inheritanceStates, generators, inheritanceState );

		final InFlightMetadataCollector collector = context.getMetadataCollector();
		if ( persistentClass instanceof RootClass ) {
			collector.addSecondPass( new CreateKeySecondPass( (RootClass) persistentClass ) );
			bindSoftDelete( clazzToProcess, (RootClass) persistentClass, inheritanceState, context );
		}
		if ( persistentClass instanceof Subclass) {
			assert superEntity != null;
			superEntity.addSubclass( (Subclass) persistentClass );
		}
		collector.addEntityBinding( persistentClass );
		// process secondary tables and complementary definitions (ie o.h.a.Table)
		collector.addSecondPass( new SecondaryTableFromAnnotationSecondPass( entityBinder, holder ) );
		collector.addSecondPass( new SecondaryTableSecondPass( entityBinder, holder ) );
		// comment, checkConstraint, and indexes are processed here
		entityBinder.processComplementaryTableDefinitions();
		bindCallbacks( clazzToProcess, persistentClass, context );
		entityBinder.callTypeBinders( persistentClass );
	}

	private static void bindSoftDelete(
			ClassDetails classDetails,
			RootClass rootClass,
			InheritanceState inheritanceState,
			MetadataBuildingContext context) {
		// todo (soft-delete) : do we assume all package-level registrations are already available?
		//		or should this be a "second pass"?

		final AnnotationUsage<SoftDelete> softDelete = extractSoftDelete( classDetails, rootClass, inheritanceState, context );
		if ( softDelete == null ) {
			return;
		}

		SoftDeleteHelper.bindSoftDeleteIndicator(
				softDelete,
				rootClass,
				rootClass.getRootTable(),
				context
		);
	}

	private static AnnotationUsage<SoftDelete> extractSoftDelete(
			ClassDetails classDetails,
			RootClass rootClass,
			InheritanceState inheritanceState,
			MetadataBuildingContext context) {
		final AnnotationUsage<SoftDelete> fromClass = classDetails.getAnnotationUsage( SoftDelete.class );
		if ( fromClass != null ) {
			return fromClass;
		}

		ClassDetails classToCheck = classDetails.getSuperClass();
		while ( classToCheck != null ) {
			final AnnotationUsage<SoftDelete> fromSuper = classToCheck.getAnnotationUsage( SoftDelete.class );
			if ( fromSuper != null && classToCheck.hasAnnotationUsage( jakarta.persistence.MappedSuperclass.class ) ) {
				return fromSuper;
			}

			classToCheck = classToCheck.getSuperClass();
		}

		return extractFromPackage( SoftDelete.class, classDetails, context );
	}

	private void handleCheckConstraints() {
		if ( annotatedClass.hasAnnotationUsage( Checks.class ) ) {
			// if we have more than one of them they are not overrideable
			final AnnotationUsage<Checks> explicitUsage = annotatedClass.getAnnotationUsage( Checks.class );
			for ( AnnotationUsage<Check> check : explicitUsage.<AnnotationUsage<Check>>getList( "value" ) ) {
				addCheckToEntity( check );
			}
		}
		else {
			final AnnotationUsage<Check> check = getOverridableAnnotation( annotatedClass, Check.class, context );
			if ( check != null ) {
				addCheckToEntity( check );
			}
		}
	}

	/**
	 * For now, we store it on the entity.
	 * Later we will come back and figure out which table it belongs to.
	 */
	private void addCheckToEntity(AnnotationUsage<Check> check) {
		final String name = check.getString( "name" );
		final String constraint = check.getString( "constraints" );
		persistentClass.addCheckConstraint( name.isEmpty()
				? new CheckConstraint( constraint )
				: new CheckConstraint( name, constraint ) );
	}

	private void callTypeBinders(PersistentClass persistentClass) {
		final List<AnnotationUsage<?>> metaAnnotatedList = annotatedClass.getMetaAnnotated( TypeBinderType.class );
		for ( AnnotationUsage<?> metaAnnotated : metaAnnotatedList ) {
			applyTypeBinder( metaAnnotated, persistentClass );
		}
	}

	private void applyTypeBinder(AnnotationUsage<?> metaAnnotated, PersistentClass persistentClass) {
		final AnnotationUsage<TypeBinderType> metaAnnotation = metaAnnotated.getAnnotationDescriptor().getAnnotationUsage( TypeBinderType.class );
		final ClassDetails binderClassDetails = metaAnnotation.getClassDetails( "binder" );
		final Class<TypeBinder<?>> binderClass = binderClassDetails.toJavaClass();

		final Annotation containingAnnotation = metaAnnotated.toAnnotation();

		try {
			//noinspection rawtypes
			final TypeBinder binder = binderClass.getConstructor().newInstance();
			//noinspection unchecked
			binder.bind( containingAnnotation, context, persistentClass );
		}
		catch ( Exception e ) {
			throw new AnnotationException( "error processing @TypeBinderType annotation '" + containingAnnotation + "'", e );
		}
	}

	private void handleIdentifier(
			PropertyHolder propertyHolder,
			Map<ClassDetails, InheritanceState> inheritanceStates,
			Map<String, IdentifierGeneratorDefinition> generators,
			InheritanceState inheritanceState) {
		final ElementsToProcess elementsToProcess = inheritanceState.postProcess( persistentClass, this );
		final Set<String> idPropertiesIfIdClass = handleIdClass(
				persistentClass,
				inheritanceState,
				context,
				propertyHolder,
				elementsToProcess,
				inheritanceStates
		);
		processIdPropertiesIfNotAlready(
				persistentClass,
				inheritanceState,
				context,
				propertyHolder,
				generators,
				idPropertiesIfIdClass,
				elementsToProcess,
				inheritanceStates
		);
	}

	private void processComplementaryTableDefinitions() {
		annotatedClass.forEachAnnotationUsage( org.hibernate.annotations.Table.class, (usage) -> {

			final Table appliedTable = findTable( usage.getString( "appliesTo" ) );

			final String comment = usage.getString( "comment" );
			if ( !comment.isEmpty() ) {
				appliedTable.setComment( comment );
			}

			final String checkConstraint = usage.getString( "checkConstraint" );
			if ( !checkConstraint.isEmpty() ) {
				//noinspection deprecation
				appliedTable.addCheckConstraint( checkConstraint );
			}

			TableBinder.addIndexes( appliedTable, usage.getList( "indexes" ), context );
		} );

		annotatedClass.forEachAnnotationUsage( jakarta.persistence.Table.class, (usage) -> {
			TableBinder.addJpaIndexes( persistentClass.getTable(), usage.getList( "indexes" ), context );
		} );
	}

	private static void detectedAttributeOverrideProblem(
			ClassDetails clazzToProcess,
			PersistentClass superEntity) {
		if ( superEntity != null && (
				clazzToProcess.hasAnnotationUsage( AttributeOverride.class ) ||
						clazzToProcess.hasAnnotationUsage( AttributeOverrides.class ) ) ) {
			LOG.unsupportedAttributeOverrideWithEntityInheritance( clazzToProcess.getName() );
		}
	}

	private Set<String> handleIdClass(
			PersistentClass persistentClass,
			InheritanceState inheritanceState,
			MetadataBuildingContext context,
			PropertyHolder propertyHolder,
			ElementsToProcess elementsToProcess,
			Map<ClassDetails, InheritanceState> inheritanceStates) {
		final Set<String> idPropertiesIfIdClass = new HashSet<>();
		boolean isIdClass = mapAsIdClass(
				inheritanceStates,
				inheritanceState,
				persistentClass,
				propertyHolder,
				elementsToProcess,
				idPropertiesIfIdClass,
				context
		);
		if ( !isIdClass ) {
			setWrapIdsInEmbeddedComponents( elementsToProcess.getIdPropertyCount() > 1 );
		}
		return idPropertiesIfIdClass;
	}

	private boolean mapAsIdClass(
			Map<ClassDetails, InheritanceState> inheritanceStates,
			InheritanceState inheritanceState,
			PersistentClass persistentClass,
			PropertyHolder propertyHolder,
			ElementsToProcess elementsToProcess,
			Set<String> idPropertiesIfIdClass,
			MetadataBuildingContext context) {

		// We are looking for @IdClass
		// In general we map the id class as identifier using the mapping metadata of the main entity's
		// properties and create an identifier mapper containing the id properties of the main entity
		final ClassDetails classWithIdClass = inheritanceState.getClassWithIdClass( false );
		if ( classWithIdClass != null ) {
			final Class<?> idClassValue = classWithIdClass.getAnnotationUsage( IdClass.class ).getClassDetails( "value" ).toJavaClass();
			final ClassDetails compositeClass = context.getMetadataCollector().getSourceModelBuildingContext().getClassDetailsRegistry().resolveClassDetails( idClassValue.getName() );
			final TypeDetails compositeType = new ClassTypeDetailsImpl( compositeClass, TypeDetails.Kind.CLASS );
			final TypeDetails classWithIdType = new ClassTypeDetailsImpl( classWithIdClass, TypeDetails.Kind.CLASS );

			final AccessType accessType = getPropertyAccessType();
			final PropertyData inferredData = new PropertyPreloadedData( accessType, "id", compositeType );
			final PropertyData baseInferredData = new PropertyPreloadedData( accessType, "id", classWithIdType );
			final AccessType propertyAccessor = getPropertyAccessor( compositeClass );

			// In JPA 2, there is a shortcut if the IdClass is the Pk of the associated class pointed to by the id
			// it ought to be treated as an embedded and not a real IdClass (at least in Hibernate's internal way)
			final boolean isFakeIdClass = isIdClassPkOfTheAssociatedEntity(
					elementsToProcess,
					compositeClass,
					inferredData,
					baseInferredData,
					propertyAccessor,
					inheritanceStates,
					context
			);

			if ( isFakeIdClass ) {
				return false;
			}
			else {
				final boolean ignoreIdAnnotations = isIgnoreIdAnnotations();
				setIgnoreIdAnnotations( true );
				bindIdClass(
						inferredData,
						baseInferredData,
						propertyHolder,
						propertyAccessor,
						context,
						inheritanceStates
				);
				final Component mapper = createMapperProperty(
						inheritanceStates,
						persistentClass,
						propertyHolder,
						context,
						classWithIdClass,
						compositeType,
						baseInferredData,
						propertyAccessor,
						true
				);
				setIgnoreIdAnnotations( ignoreIdAnnotations );
				for ( Property property : mapper.getProperties() ) {
					idPropertiesIfIdClass.add( property.getName() );
				}
				return true;
			}
		}
		else {
			return false;
		}
	}

	private Component createMapperProperty(
			Map<ClassDetails, InheritanceState> inheritanceStates,
			PersistentClass persistentClass,
			PropertyHolder propertyHolder,
			MetadataBuildingContext context,
			ClassDetails classWithIdClass,
			TypeDetails compositeClass,
			PropertyData baseInferredData,
			AccessType propertyAccessor,
			boolean isIdClass) {
		final Component mapper = createMapper(
				inheritanceStates,
				persistentClass,
				propertyHolder,
				context,
				classWithIdClass,
				compositeClass,
				baseInferredData,
				propertyAccessor,
				isIdClass
		);
		final Property mapperProperty = new Property();
		mapperProperty.setName( NavigablePath.IDENTIFIER_MAPPER_PROPERTY );
		mapperProperty.setUpdateable( false );
		mapperProperty.setInsertable( false );
		mapperProperty.setPropertyAccessorName( "embedded" );
		mapperProperty.setValue( mapper );
		persistentClass.addProperty( mapperProperty);
		return mapper;
	}

	private Component createMapper(
			Map<ClassDetails, InheritanceState> inheritanceStates,
			PersistentClass persistentClass,
			PropertyHolder propertyHolder,
			MetadataBuildingContext context,
			ClassDetails classWithIdClass,
			TypeDetails compositeClass,
			PropertyData baseInferredData,
			AccessType propertyAccessor,
			boolean isIdClass) {
		final Component mapper = fillEmbeddable(
				propertyHolder,
				new PropertyPreloadedData(
						propertyAccessor,
						NavigablePath.IDENTIFIER_MAPPER_PROPERTY,
						compositeClass
				),
				baseInferredData,
				propertyAccessor,
				false,
				this,
				true,
				true,
				false,
				null,
				null,
				null,
				context,
				inheritanceStates,
				isIdClass
		);
		persistentClass.setIdentifierMapper( mapper );

		// If id definition is on a mapped superclass, update the mapping
		final MappedSuperclass superclass = getMappedSuperclassOrNull( classWithIdClass, inheritanceStates, context );
		if ( superclass != null ) {
			superclass.setDeclaredIdentifierMapper( mapper );
		}
		else {
			// we are for sure on the entity
			persistentClass.setDeclaredIdentifierMapper( mapper );
		}
		return mapper;
	}

	private static PropertyData getUniqueIdPropertyFromBaseClass(
			PropertyData inferredData,
			PropertyData baseInferredData,
			AccessType propertyAccessor,
			MetadataBuildingContext context) {
		final List<PropertyData> baseClassElements = new ArrayList<>();
		final PropertyContainer propContainer = new PropertyContainer(
				baseInferredData.getClassOrElementType().determineRawClass(),
				inferredData.getPropertyType().determineRawClass(),
				propertyAccessor
		);
		addElementsOfClass( baseClassElements, propContainer, context );
		//Id properties are on top and there is only one
		return baseClassElements.get( 0 );
	}

	private static boolean isIdClassPkOfTheAssociatedEntity(
			ElementsToProcess elementsToProcess,
			ClassDetails compositeClass,
			PropertyData inferredData,
			PropertyData baseInferredData,
			AccessType propertyAccessor,
			Map<ClassDetails, InheritanceState> inheritanceStates,
			MetadataBuildingContext context) {
		if ( elementsToProcess.getIdPropertyCount() == 1 ) {
			final PropertyData idPropertyOnBaseClass = getUniqueIdPropertyFromBaseClass(
					inferredData,
					baseInferredData,
					propertyAccessor,
					context
			);
			final InheritanceState state = inheritanceStates.get( idPropertyOnBaseClass.getClassOrElementType() );
			if ( state == null ) {
				return false; //while it is likely a user error, let's consider it is something that might happen
			}
			final ClassDetails associatedClassWithIdClass = state.getClassWithIdClass( true );
			if ( associatedClassWithIdClass == null ) {
				//we cannot know for sure here unless we try and find the @EmbeddedId
				//Let's not do this thorough checking but do some extra validation
				return hasToOneAnnotation( idPropertyOnBaseClass.getAttributeMember() );

			}
			else {
				final AnnotationUsage<IdClass> idClass = associatedClassWithIdClass.getAnnotationUsage( IdClass.class );
				return compositeClass.equals( idClass.getClassDetails( "value" ) );
			}
		}
		else {
			return false;
		}
	}

	private void bindIdClass(
			PropertyData inferredData,
			PropertyData baseInferredData,
			PropertyHolder propertyHolder,
			AccessType propertyAccessor,
			MetadataBuildingContext buildingContext,
			Map<ClassDetails, InheritanceState> inheritanceStates) {
		propertyHolder.setInIdClass( true );

		// Fill simple value and property since and Id is a property
		final PersistentClass persistentClass = propertyHolder.getPersistentClass();
		if ( !(persistentClass instanceof RootClass) ) {
			throw new AnnotationException( "Entity '" + persistentClass.getEntityName()
					+ "' is a subclass in an entity inheritance hierarchy and may not redefine the identifier of the root entity" );
		}
		final RootClass rootClass = (RootClass) persistentClass;
		final Component id = fillEmbeddable(
				propertyHolder,
				inferredData,
				baseInferredData,
				propertyAccessor,
				false,
				this,
				true,
				false,
				false,
				null,
				null,
				null,
				buildingContext,
				inheritanceStates,
				true
		);
		id.setKey( true );
		if ( rootClass.getIdentifier() != null ) {
			throw new AssertionFailure( "Entity '" + persistentClass.getEntityName()
					+ "' has an '@IdClass' and may not have an identifier property" );
		}
		if ( id.getPropertySpan() == 0 ) {
			throw new AnnotationException( "Class '" + id.getComponentClassName()
					+ " is the '@IdClass' for the entity '" + persistentClass.getEntityName()
					+ "' but has no persistent properties" );
		}

		rootClass.setIdentifier( id );

		handleIdGenerator( inferredData, buildingContext, id );

		rootClass.setEmbeddedIdentifier( inferredData.getPropertyType() == null );

		propertyHolder.setInIdClass( null );
	}

	private static void handleIdGenerator(PropertyData inferredData, MetadataBuildingContext buildingContext, Component id) {
		if ( buildingContext.getBootstrapContext().getJpaCompliance().isGlobalGeneratorScopeEnabled() ) {
			buildingContext.getMetadataCollector().addSecondPass( new IdGeneratorResolverSecondPass(
					id,
					inferredData.getAttributeMember(),
					DEFAULT_ID_GEN_STRATEGY,
					"",
					buildingContext
			) );
		}
		else {
			makeIdGenerator(
					id,
					inferredData.getAttributeMember(),
					DEFAULT_ID_GEN_STRATEGY,
					"",
					buildingContext,
					Collections.emptyMap()
			);
		}
	}

	private void handleSecondaryTables() {
		annotatedClass.forEachAnnotationUsage( SecondaryTable.class, (usage) -> {
			addSecondaryTable( usage, null, false );
		} );
	}

	private void handleClassTable(InheritanceState inheritanceState, PersistentClass superEntity) {
		final String schema;
		final String table;
		final String catalog;
		final List<AnnotationUsage<UniqueConstraint>> uniqueConstraints;
		boolean hasTableAnnotation = annotatedClass.hasAnnotationUsage( jakarta.persistence.Table.class );
		if ( hasTableAnnotation ) {
			final AnnotationUsage<jakarta.persistence.Table> tableAnnotation = annotatedClass.getAnnotationUsage( jakarta.persistence.Table.class );
			table = tableAnnotation.getString( "name" );
			schema = tableAnnotation.getString( "schema" );
			catalog = tableAnnotation.getString( "catalog" );
			uniqueConstraints = tableAnnotation.getList( "uniqueConstraints" );
		}
		else {
			//might be no @Table annotation on the annotated class
			schema = "";
			table = "";
			catalog = "";
			uniqueConstraints = Collections.emptyList();
		}

		final InFlightMetadataCollector collector = context.getMetadataCollector();
		if ( inheritanceState.hasTable() ) {
			createTable( inheritanceState, superEntity, schema, table, catalog, uniqueConstraints, collector );
		}
		else {
			if ( hasTableAnnotation ) {
				//TODO: why is this not an error?!
				LOG.invalidTableAnnotation( annotatedClass.getName() );
			}

			if ( inheritanceState.getType() == InheritanceType.SINGLE_TABLE ) {
				// we at least need to properly set up the EntityTableXref
				bindTableForDiscriminatedSubclass( collector.getEntityTableXref( superEntity.getEntityName() ) );
			}
		}
	}

	private void createTable(
			InheritanceState inheritanceState,
			PersistentClass superEntity,
			String schema,
			String table,
			String catalog,
			List<AnnotationUsage<UniqueConstraint>> uniqueConstraints,
			InFlightMetadataCollector collector) {
		final AnnotationUsage<RowId> rowId = annotatedClass.getAnnotationUsage( RowId.class );
		final AnnotationUsage<View> view = annotatedClass.getAnnotationUsage( View.class );
		bindTable(
				schema,
				catalog,
				table,
				uniqueConstraints,
				rowId == null ? null : rowId.getString( "value" ),
				view == null ? null : view.getString( "query" ),
				inheritanceState.hasDenormalizedTable()
						? collector.getEntityTableXref( superEntity.getEntityName() )
						: null
		);
	}

	private void handleInheritance(
			InheritanceState inheritanceState,
			PersistentClass superEntity,
			PropertyHolder propertyHolder) {
		final boolean isJoinedSubclass;
		switch ( inheritanceState.getType() ) {
			case JOINED:
				joinedInheritance( inheritanceState, superEntity, propertyHolder );
				isJoinedSubclass = inheritanceState.hasParents();
				break;
			case SINGLE_TABLE:
				singleTableInheritance( inheritanceState, propertyHolder );
				isJoinedSubclass = false;
				break;
			default:
				isJoinedSubclass = false;
		}

		bindDiscriminatorValue();

		if ( !isJoinedSubclass ) {
			checkNoJoinColumns( annotatedClass );
			if ( annotatedClass.hasAnnotationUsage( OnDelete.class ) ) {
				//TODO: why is this not an error!??
				LOG.invalidOnDeleteAnnotation( propertyHolder.getEntityName() );
			}
		}
	}

	private void singleTableInheritance(InheritanceState inheritanceState, PropertyHolder holder) {
		processDiscriminatorOptions();
		final AnnotatedDiscriminatorColumn discriminatorColumn = processSingleTableDiscriminatorProperties( inheritanceState );
		if ( !inheritanceState.hasParents() ) { // todo : sucks that this is separate from RootClass distinction
			final RootClass rootClass = (RootClass) persistentClass;
			if ( inheritanceState.hasSiblings()
					|| discriminatorColumn != null && !discriminatorColumn.isImplicit() ) {
				bindDiscriminatorColumnToRootPersistentClass( rootClass, discriminatorColumn, holder );
				rootClass.setForceDiscriminator( isForceDiscriminatorInSelects() );
			}
		}
	}

	private void joinedInheritance(InheritanceState state, PersistentClass superEntity, PropertyHolder holder) {
		processDiscriminatorOptions();

		if ( state.hasParents() ) {
			final AnnotatedJoinColumns joinColumns = subclassJoinColumns( annotatedClass, superEntity, context );
			final JoinedSubclass jsc = (JoinedSubclass) persistentClass;
			final DependantValue key = new DependantValue( context, jsc.getTable(), jsc.getIdentifier() );
			jsc.setKey( key );
			handleForeignKeys( annotatedClass, context, key );
			final AnnotationUsage<OnDelete> onDelete = annotatedClass.getAnnotationUsage( OnDelete.class );
			key.setOnDeleteAction( onDelete == null ? null : onDelete.getEnum( "action" ) );
			//we are never in a second pass at that stage, so queue it
			context.getMetadataCollector()
					.addSecondPass( new JoinedSubclassFkSecondPass( jsc, joinColumns, key, context) );
			context.getMetadataCollector()
					.addSecondPass( new CreateKeySecondPass( jsc ) );
		}

		final AnnotatedDiscriminatorColumn discriminatorColumn = processJoinedDiscriminatorProperties( state );
		if ( !state.hasParents() ) {  // todo : sucks that this is separate from RootClass distinction
			final RootClass rootClass = (RootClass) persistentClass;
			// the class we're processing is the root of the hierarchy, so
			// let's see if we had a discriminator column (it's perfectly
			// valid for joined inheritance to not have a discriminator)
			if ( discriminatorColumn == null ) {
				if ( isForceDiscriminatorInSelects() ) {
					throw new AnnotationException( "Entity '" + rootClass.getEntityName()
							+ "' with 'JOINED' inheritance is annotated '@DiscriminatorOptions(force=true)' but has no discriminator column" );
				}
			}
			else {
				// we do have a discriminator column
				if ( state.hasSiblings() || !discriminatorColumn.isImplicit() ) {
					bindDiscriminatorColumnToRootPersistentClass( rootClass, discriminatorColumn, holder );
					rootClass.setForceDiscriminator( isForceDiscriminatorInSelects() );
				}
			}
		}
	}

	private static void checkNoJoinColumns(ClassDetails clazzToProcess) {
		if ( clazzToProcess.hasAnnotationUsage( PrimaryKeyJoinColumns.class )
				|| clazzToProcess.hasAnnotationUsage( PrimaryKeyJoinColumn.class ) ) {
			//TODO: why is this not an error?!
			LOG.invalidPrimaryKeyJoinColumnAnnotation( clazzToProcess.getName() );
		}
	}

	private static void handleForeignKeys(ClassDetails clazzToProcess, MetadataBuildingContext context, DependantValue key) {
		final AnnotationUsage<ForeignKey> foreignKey = clazzToProcess.getAnnotationUsage( ForeignKey.class );
		if ( foreignKey != null && !foreignKey.getString( "name" ).isEmpty() ) {
			key.setForeignKeyName( foreignKey.getString( "name" ) );
		}
		else {
			final AnnotationUsage<PrimaryKeyJoinColumn> pkJoinColumn = clazzToProcess.getSingleAnnotationUsage( PrimaryKeyJoinColumn.class );
			final AnnotationUsage<PrimaryKeyJoinColumns> pkJoinColumns = clazzToProcess.getAnnotationUsage( PrimaryKeyJoinColumns.class );
			final boolean noConstraintByDefault = context.getBuildingOptions().isNoConstraintByDefault();
			if ( pkJoinColumns != null && ( pkJoinColumns.getNestedUsage( "foreignKey" ).getEnum( "value" ) == ConstraintMode.NO_CONSTRAINT
					|| pkJoinColumns.getNestedUsage( "foreignKey" ).getEnum( "value" ) == ConstraintMode.PROVIDER_DEFAULT && noConstraintByDefault ) ) {
				// don't apply a constraint based on ConstraintMode
				key.disableForeignKey();
			}
			else if ( pkJoinColumns != null && isNotEmpty( pkJoinColumns.getNestedUsage( "foreignKey" ).getString( "name" ) ) ) {
				key.setForeignKeyName( pkJoinColumns.getNestedUsage( "foreignKey" ).getString( "name" ) );
				if ( !pkJoinColumns.getNestedUsage( "foreignKey" ).getString( "foreignKeyDefinition" ).isEmpty() ) {
					key.setForeignKeyDefinition( pkJoinColumns.getNestedUsage( "foreignKey" ).getString( "foreignKeyDefinition" ) );
				}
			}
			else if ( pkJoinColumn != null && ( pkJoinColumn.getNestedUsage( "foreignKey" ).getEnum( "value" ) == ConstraintMode.NO_CONSTRAINT
					|| pkJoinColumn.getNestedUsage( "foreignKey" ).getEnum( "value" ) == ConstraintMode.PROVIDER_DEFAULT && noConstraintByDefault ) ) {
				// don't apply a constraint based on ConstraintMode
				key.disableForeignKey();
			}
			else if ( pkJoinColumn != null && isNotEmpty( pkJoinColumn.getNestedUsage( "foreignKey" ).getString( "name" ) ) ) {
				key.setForeignKeyName( pkJoinColumn.getNestedUsage( "foreignKey" ).getString( "name" ) );
				if ( !pkJoinColumn.getNestedUsage( "foreignKey" ).getString( "foreignKeyDefinition" ).isEmpty() ) {
					key.setForeignKeyDefinition( pkJoinColumn.getNestedUsage( "foreignKey" ).getString( "foreignKeyDefinition" ) );
				}
			}
		}
	}

	private void bindDiscriminatorColumnToRootPersistentClass(
			RootClass rootClass,
			AnnotatedDiscriminatorColumn discriminatorColumn,
			PropertyHolder holder) {
		if ( rootClass.getDiscriminator() == null ) {
			if ( discriminatorColumn == null ) {
				throw new AssertionFailure( "discriminator column should have been built" );
			}
			final AnnotatedColumns columns = new AnnotatedColumns();
			columns.setPropertyHolder( holder );
			columns.setBuildingContext( context );
			columns.setJoins( secondaryTables );
//			discriminatorColumn.setJoins( secondaryTables );
//			discriminatorColumn.setPropertyHolder( holder );
			discriminatorColumn.setParent( columns );

			final BasicValue discriminatorColumnBinding = new BasicValue( context, rootClass.getTable() );
			rootClass.setDiscriminator( discriminatorColumnBinding );
			discriminatorColumn.linkWithValue( discriminatorColumnBinding );
			discriminatorColumnBinding.setTypeName( discriminatorColumn.getDiscriminatorTypeName() );
			rootClass.setPolymorphic( true );
			if ( LOG.isTraceEnabled() ) {
				LOG.tracev( "Setting discriminator for entity {0}", rootClass.getEntityName() );
			}
			context.getMetadataCollector().addSecondPass(
					new NullableDiscriminatorColumnSecondPass( rootClass.getEntityName() )
			);
		}
		if ( insertableDiscriminator != null ) {
			rootClass.setDiscriminatorInsertable( insertableDiscriminator );
		}
	}

	private void processDiscriminatorOptions() {
		final AnnotationUsage<DiscriminatorOptions> discriminatorOptions = annotatedClass.getAnnotationUsage( DiscriminatorOptions.class );
		if ( discriminatorOptions != null ) {
			forceDiscriminator = discriminatorOptions.getBoolean( "force" );
			insertableDiscriminator = discriminatorOptions.getBoolean( "insert" );
		}
	}

	/**
	 * Process all discriminator-related metadata per rules for "single table" inheritance
	 */
	private AnnotatedDiscriminatorColumn processSingleTableDiscriminatorProperties(InheritanceState inheritanceState) {
		final AnnotationUsage<DiscriminatorColumn> discriminatorColumn = annotatedClass.getAnnotationUsage( DiscriminatorColumn.class );
		final AnnotationUsage<DiscriminatorFormula> discriminatorFormula = getOverridableAnnotation( annotatedClass, DiscriminatorFormula.class, context );

		if ( !inheritanceState.hasParents() || annotatedClass.hasAnnotationUsage( Inheritance.class ) ) {
			return buildDiscriminatorColumn( discriminatorColumn, discriminatorFormula, context );
		}
		else {
			// not a root entity
			if ( discriminatorColumn != null ) {
				throw new AnnotationException( "Entity class '" + annotatedClass.getName()
						+  "' is annotated '@DiscriminatorColumn' but it is not the root of the entity inheritance hierarchy");
			}
			if ( discriminatorFormula != null ) {
				throw new AnnotationException( "Entity class '" + annotatedClass.getName()
						+  "' is annotated '@DiscriminatorFormula' but it is not the root of the entity inheritance hierarchy");
			}
			return null;
		}
	}

	/**
	 * Process all discriminator-related metadata per rules for "joined" inheritance, taking
	 * into account {@value AvailableSettings#IMPLICIT_DISCRIMINATOR_COLUMNS_FOR_JOINED_SUBCLASS}
	 * and {@value AvailableSettings#IGNORE_EXPLICIT_DISCRIMINATOR_COLUMNS_FOR_JOINED_SUBCLASS}.
	 */
	private AnnotatedDiscriminatorColumn processJoinedDiscriminatorProperties(InheritanceState inheritanceState) {
		if ( annotatedClass.hasAnnotationUsage( DiscriminatorFormula.class ) ) {
			throw new AnnotationException( "Entity class '" + annotatedClass.getName()
					+  "' has 'JOINED' inheritance and is annotated '@DiscriminatorFormula'" );
		}

		final AnnotationUsage<DiscriminatorColumn> discriminatorColumn = annotatedClass.getAnnotationUsage( DiscriminatorColumn.class );
		if ( !inheritanceState.hasParents() || annotatedClass.hasAnnotationUsage( Inheritance.class ) ) {
			return useDiscriminatorColumnForJoined( discriminatorColumn )
					? buildDiscriminatorColumn( discriminatorColumn, null, context )
					: null;
		}
		else {
			// not a root entity
			if ( discriminatorColumn != null ) {
				throw new AnnotationException( "Entity class '" + annotatedClass.getName()
						+  "' is annotated '@DiscriminatorColumn' but it is not the root of the entity inheritance hierarchy");
			}
			return null;
		}
	}

	/**
	 * We want to process the discriminator column if either:
	 * <ol>
	 * <li>there is an explicit {@link DiscriminatorColumn} annotation and we are not told to ignore it
	 *     via {@value AvailableSettings#IGNORE_EXPLICIT_DISCRIMINATOR_COLUMNS_FOR_JOINED_SUBCLASS}, or
	 * <li>there is no explicit {@link DiscriminatorColumn} annotation but we are told to create it
	 *     implicitly via {@value AvailableSettings#IMPLICIT_DISCRIMINATOR_COLUMNS_FOR_JOINED_SUBCLASS}.
	 * </ol>
	 */
	private boolean useDiscriminatorColumnForJoined(AnnotationUsage<DiscriminatorColumn> discriminatorColumn) {
		if ( discriminatorColumn != null ) {
			boolean ignore = context.getBuildingOptions().ignoreExplicitDiscriminatorsForJoinedInheritance();
			if ( ignore ) {
				LOG.debugf( "Ignoring explicit @DiscriminatorColumn annotation on: %s", annotatedClass.getName() );
			}
			else {
				LOG.applyingExplicitDiscriminatorColumnForJoined(
						annotatedClass.getName(),
						AvailableSettings.IGNORE_EXPLICIT_DISCRIMINATOR_COLUMNS_FOR_JOINED_SUBCLASS
				);
			}
			return !ignore;
		}
		else {
			boolean createImplicit = context.getBuildingOptions().createImplicitDiscriminatorsForJoinedInheritance();
			if ( createImplicit ) {
				LOG.debugf( "Inferring implicit @DiscriminatorColumn using defaults for: %s", annotatedClass.getName() );
			}
			return createImplicit;
		}
	}

	private void processIdPropertiesIfNotAlready(
			PersistentClass persistentClass,
			InheritanceState inheritanceState,
			MetadataBuildingContext context,
			PropertyHolder propertyHolder,
			Map<String, IdentifierGeneratorDefinition> generators,
			Set<String> idPropertiesIfIdClass,
			ElementsToProcess elementsToProcess,
			Map<ClassDetails, InheritanceState> inheritanceStates) {
		final Set<String> missingIdProperties = new HashSet<>( idPropertiesIfIdClass );
		final Set<String> missingEntityProperties = new HashSet<>();
		for ( PropertyData propertyAnnotatedElement : elementsToProcess.getElements() ) {
			final String propertyName = propertyAnnotatedElement.getPropertyName();
			if ( !idPropertiesIfIdClass.contains( propertyName ) ) {
				if ( !idPropertiesIfIdClass.isEmpty() && !isIgnoreIdAnnotations()
						&& hasIdAnnotation( propertyAnnotatedElement.getAttributeMember() ) ) {
					missingEntityProperties.add( propertyName );
				}
				else {
					boolean subclassAndSingleTableStrategy =
							inheritanceState.getType() == InheritanceType.SINGLE_TABLE
									&& inheritanceState.hasParents();
					processElementAnnotations(
							propertyHolder,
							subclassAndSingleTableStrategy
									? Nullability.FORCED_NULL
									: Nullability.NO_CONSTRAINT,
							propertyAnnotatedElement,
							generators,
							this,
							false,
							false,
							false,
							context,
							inheritanceStates
					);
				}
			}
			else {
				missingIdProperties.remove( propertyName );
			}
		}

		if ( !missingIdProperties.isEmpty() ) {
			throw new AnnotationException( "Entity '" + persistentClass.getEntityName()
					+ "' has an '@IdClass' with properties " + getMissingPropertiesString( missingIdProperties )
					+ " which do not match properties of the entity class" );
		}
		else if ( !missingEntityProperties.isEmpty() ) {
			throw new AnnotationException( "Entity '" + persistentClass.getEntityName()
					+ "' has '@Id' annotated properties " + getMissingPropertiesString( missingEntityProperties )
					+ " which do not match properties of the specified '@IdClass'" );
		}
	}

	private static String getMissingPropertiesString(Set<String> propertyNames) {
		final StringBuilder sb = new StringBuilder();
		for ( String property : propertyNames ) {
			if ( sb.length() > 0 ) {
				sb.append( ", " );
			}
			sb.append( "'" ).append( property ).append( "'" );
		}
		return sb.toString();
	}

	private static PersistentClass makePersistentClass(
			InheritanceState inheritanceState,
			PersistentClass superEntity,
			MetadataBuildingContext metadataBuildingContext) {
		//we now know what kind of persistent entity it is
		if ( !inheritanceState.hasParents() ) {
			return new RootClass( metadataBuildingContext );
		}
		else {
			switch ( inheritanceState.getType() ) {
				case SINGLE_TABLE:
					return new SingleTableSubclass( superEntity, metadataBuildingContext );
				case JOINED:
					return new JoinedSubclass( superEntity, metadataBuildingContext );
				case TABLE_PER_CLASS:
					return new UnionSubclass( superEntity, metadataBuildingContext );
				default:
					throw new AssertionFailure( "Unknown inheritance type: " + inheritanceState.getType() );
			}
		}
	}

	private static AnnotatedJoinColumns subclassJoinColumns(
			ClassDetails clazzToProcess,
			PersistentClass superEntity,
			MetadataBuildingContext context) {
		//@Inheritance(JOINED) subclass need to link back to the super entity
		final AnnotatedJoinColumns joinColumns = new AnnotatedJoinColumns();
		joinColumns.setBuildingContext( context );

		final AnnotationUsage<PrimaryKeyJoinColumns> primaryKeyJoinColumns = clazzToProcess.getAnnotationUsage( PrimaryKeyJoinColumns.class );
		if ( primaryKeyJoinColumns != null ) {
			final List<AnnotationUsage<PrimaryKeyJoinColumn>> columns = primaryKeyJoinColumns.getList( "value" );
			if ( !columns.isEmpty() ) {
				for ( AnnotationUsage<PrimaryKeyJoinColumn> column : columns ) {
					buildInheritanceJoinColumn(
							column,
							null,
							superEntity.getIdentifier(),
							joinColumns,
							context
					);
				}
			}
			else {
				final AnnotationUsage<PrimaryKeyJoinColumn> columnAnnotation = clazzToProcess.getAnnotationUsage( PrimaryKeyJoinColumn.class );
				buildInheritanceJoinColumn(
						columnAnnotation,
						null,
						superEntity.getIdentifier(),
						joinColumns,
						context
				);
			}
		}
		else {
			buildInheritanceJoinColumn(
					clazzToProcess.getAnnotationUsage( PrimaryKeyJoinColumn.class ),
					null,
					superEntity.getIdentifier(),
					joinColumns,
					context
			);
		}
		LOG.trace( "Subclass joined column(s) created" );
		return joinColumns;
	}

	private static PersistentClass getSuperEntity(
			ClassDetails clazzToProcess,
			Map<ClassDetails, InheritanceState> inheritanceStates,
			MetadataBuildingContext context,
			InheritanceState inheritanceState) {
		final InheritanceState superState = getInheritanceStateOfSuperEntity( clazzToProcess, inheritanceStates );
		if ( superState == null ) {
			return null;
		}
		else {
			final PersistentClass superEntity = context.getMetadataCollector()
					.getEntityBinding( superState.getClassDetails().getName() );
			//check if superclass is not a potential persistent class
			if ( superEntity == null && inheritanceState.hasParents() ) {
				throw new AssertionFailure( "Subclass has to be bound after its parent class: "
						+ superState.getClassDetails().getName() );
			}
			return superEntity;
		}
	}

	private static void bindCallbacks(ClassDetails entityClass, PersistentClass persistentClass, MetadataBuildingContext context) {
		final ReflectionManager reflection = context.getBootstrapContext().getReflectionManager();
		for ( CallbackType callbackType : CallbackType.values() ) {
			persistentClass.addCallbackDefinitions( resolveEntityCallbacks( reflection, entityClass, callbackType ) );
		}
		context.getMetadataCollector().addSecondPass( persistentClasses -> {
			for ( Property property : persistentClass.getDeclaredProperties() ) {
				final Class<?> mappedClass = persistentClass.getMappedClass();
				if ( property.isComposite() ) {
					for ( CallbackType type : CallbackType.values() ) {
						property.addCallbackDefinitions( resolveEmbeddableCallbacks( reflection, mappedClass, property, type ) );
					}
				}
			}
		} );
	}

	public boolean wrapIdsInEmbeddedComponents() {
		return wrapIdsInEmbeddedComponents;
	}

	/**
	 * Use as a fake one for Collection of elements
	 */
	public EntityBinder() {
	}

	public EntityBinder(ClassDetails annotatedClass, PersistentClass persistentClass, MetadataBuildingContext context) {
		this.context = context;
		this.persistentClass = persistentClass;
		this.annotatedClass = annotatedClass;
	}

	/**
	 * Delegates to {@link PersistentClass#isPropertyDefinedInHierarchy},
	 * after verifying that there is a {@link PersistentClass} available.
	 *
	 * @param name The name of the property to check
	 *
	 * @return {@code true} if a property by that given name does already exist in the super hierarchy.
	 */
	public boolean isPropertyDefinedInSuperHierarchy(String name) {
		// Yes, yes... persistentClass can be null because EntityBinder
		// can be used to bind Components (naturally!)
		return persistentClass != null
			&& persistentClass.isPropertyDefinedInSuperHierarchy( name );
	}

	private void bindRowManagement() {
		final AnnotationUsage<DynamicInsert> dynamicInsertAnn = annotatedClass.getAnnotationUsage( DynamicInsert.class );
		persistentClass.setDynamicInsert( dynamicInsertAnn != null && dynamicInsertAnn.getBoolean( "value" ) );
		final AnnotationUsage<DynamicUpdate> dynamicUpdateAnn = annotatedClass.getAnnotationUsage( DynamicUpdate.class );
		persistentClass.setDynamicUpdate( dynamicUpdateAnn != null && dynamicUpdateAnn.getBoolean( "value" ) );
		final AnnotationUsage<SelectBeforeUpdate> selectBeforeUpdateAnn = annotatedClass.getAnnotationUsage( SelectBeforeUpdate.class );
		persistentClass.setSelectBeforeUpdate( selectBeforeUpdateAnn != null && selectBeforeUpdateAnn.getBoolean( "value" ) );
	}

	private void bindOptimisticLocking() {
		final AnnotationUsage<OptimisticLocking> optimisticLockingAnn = annotatedClass.getAnnotationUsage( OptimisticLocking.class );
		persistentClass.setOptimisticLockStyle(
				getVersioning( optimisticLockingAnn == null ? OptimisticLockType.VERSION : optimisticLockingAnn.getEnum( "type" ) )
		);
	}

	private void bindPolymorphism() {
		final AnnotationUsage<Polymorphism> polymorphismAnn = annotatedClass.getAnnotationUsage( Polymorphism.class );
		polymorphismType = polymorphismAnn == null ? PolymorphismType.IMPLICIT : polymorphismAnn.getEnum( "type" );
	}

	private void bindEntityAnnotation() {
		final AnnotationUsage<Entity> entity = annotatedClass.getAnnotationUsage( Entity.class );
		if ( entity == null ) {
			throw new AssertionFailure( "@Entity should never be missing" );
		}
		final String entityName = entity.getString( "name" );
		name = entityName.isEmpty() ? unqualify( annotatedClass.getName() ) : entityName;
	}

	public boolean isRootEntity() {
		// This is the best option I can think of here since
		// PersistentClass is most likely not yet fully populated
		return persistentClass instanceof RootClass;
	}

	public void bindEntity() {
		bindEntityAnnotation();
		bindRowManagement();
		bindOptimisticLocking();
		bindPolymorphism();
		bindProxy();
		bindBatchSize();
		bindWhere();
		bindCache();
		bindNaturalIdCache();
		bindFiltersInHierarchy();

		persistentClass.setAbstract( annotatedClass.isAbstract() );
		persistentClass.setClassName( annotatedClass.getName() );
		persistentClass.setJpaEntityName( name );
		persistentClass.setEntityName( annotatedClass.getName() );
		persistentClass.setCached( isCached );
		persistentClass.setLazy( lazy );
		if ( proxyClass != null ) {
			persistentClass.setProxyInterfaceName( proxyClass.getName() );
		}

		if ( persistentClass instanceof RootClass ) {
			bindRootEntity();
		}
		else if ( !isMutable() ) {
			LOG.immutableAnnotationOnNonRoot( annotatedClass.getName() );
		}

		ensureNoMutabilityPlan();

		bindCustomSql();
		bindSynchronize();
		bindFilters();

		registerImportName();

		processNamedEntityGraphs();
	}

	private void ensureNoMutabilityPlan() {
		if ( annotatedClass.hasAnnotationUsage( Mutability.class ) ) {
			throw new MappingException( "@Mutability is not allowed on entity" );
		}
	}

	private boolean isMutable() {
		return !annotatedClass.hasAnnotationUsage(Immutable.class);
	}

	private void registerImportName() {
		LOG.debugf( "Import with entity name %s", name );
		try {
			context.getMetadataCollector().addImport( name, persistentClass.getEntityName() );
			final String entityName = persistentClass.getEntityName();
			if ( !entityName.equals( name ) ) {
				context.getMetadataCollector().addImport( entityName, entityName );
			}
		}
		catch (MappingException me) {
			throw new AnnotationException( "Use of the same entity name twice: " + name, me );
		}
	}

	private void bindRootEntity() {
		final RootClass rootClass = (RootClass) persistentClass;
		rootClass.setMutable( isMutable() );
		rootClass.setExplicitPolymorphism( isExplicitPolymorphism( polymorphismType ) );
		if ( isNotEmpty( where ) ) {
			rootClass.setWhere( where );
		}
		if ( cacheConcurrentStrategy != null ) {
			rootClass.setCacheConcurrencyStrategy( cacheConcurrentStrategy );
			rootClass.setCacheRegionName( cacheRegion );
			rootClass.setLazyPropertiesCacheable( cacheLazyProperty );
		}
		rootClass.setNaturalIdCacheRegionName( naturalIdCacheRegion );
	}

	private boolean isForceDiscriminatorInSelects() {
		return forceDiscriminator == null
				? context.getBuildingOptions().shouldImplicitlyForceDiscriminatorInSelect()
				: forceDiscriminator;
	}

	private void bindCustomSql() {
		//TODO: tolerate non-empty table() member here if it explicitly names the main table

		final AnnotationUsage<SQLInsert> sqlInsert = findMatchingSqlAnnotation( "", SQLInsert.class, SQLInserts.class );
		if ( sqlInsert != null ) {
			persistentClass.setCustomSQLInsert(
					sqlInsert.getString( "sql" ).trim(),
					sqlInsert.getBoolean( "callable" ),
					fromResultCheckStyle( sqlInsert.getEnum( "check" ) )
			);
		}

		final AnnotationUsage<SQLUpdate> sqlUpdate = findMatchingSqlAnnotation( "", SQLUpdate.class, SQLUpdates.class );
		if ( sqlUpdate != null ) {
			persistentClass.setCustomSQLUpdate(
					sqlUpdate.getString( "sql" ).trim(),
					sqlUpdate.getBoolean( "callable" ),
					fromResultCheckStyle( sqlUpdate.getEnum( "check" ) )
			);
		}

		final AnnotationUsage<SQLDelete> sqlDelete = findMatchingSqlAnnotation( "", SQLDelete.class, SQLDeletes.class );
		if ( sqlDelete != null ) {
			persistentClass.setCustomSQLDelete(
					sqlDelete.getString( "sql" ).trim(),
					sqlDelete.getBoolean( "callable" ),
					fromResultCheckStyle( sqlDelete.getEnum( "check" ) )
			);
		}

		final AnnotationUsage<SQLDeleteAll> sqlDeleteAll = annotatedClass.getAnnotationUsage( SQLDeleteAll.class );
		if ( sqlDeleteAll != null ) {
			throw new AnnotationException("@SQLDeleteAll does not apply to entities: "
					+ persistentClass.getEntityName());
		}

		final AnnotationUsage<SQLSelect> sqlSelect = getOverridableAnnotation( annotatedClass, SQLSelect.class, context );
		if ( sqlSelect != null ) {
			final String loaderName = persistentClass.getEntityName() + "$SQLSelect";
			persistentClass.setLoaderName( loaderName );
			QueryBinder.bindNativeQuery( loaderName, sqlSelect, annotatedClass, context );
		}

		final AnnotationUsage<HQLSelect> hqlSelect = annotatedClass.getAnnotationUsage( HQLSelect.class );
		if ( hqlSelect != null ) {
			final String loaderName = persistentClass.getEntityName() + "$HQLSelect";
			persistentClass.setLoaderName( loaderName );
			QueryBinder.bindQuery( loaderName, hqlSelect, context );
		}

		final AnnotationUsage<Loader> loader = annotatedClass.getAnnotationUsage( Loader.class );
		if ( loader != null ) {
			persistentClass.setLoaderName( loader.getString( "namedQuery" ) );
		}

		final AnnotationUsage<Subselect> subselect = annotatedClass.getAnnotationUsage( Subselect.class );
		if ( subselect != null ) {
			this.subselect = subselect.getString( "value" );
		}
	}

	private void bindFilters() {
		for ( AnnotationUsage<Filter> filter : filters ) {
			final String filterName = filter.getString( "name" );
			String condition = filter.getString( "condition" );
			if ( condition.isEmpty() ) {
				condition = getDefaultFilterCondition( filterName );
			}
			persistentClass.addFilter(
					filterName,
					condition,
					filter.getBoolean( "deduceAliasInjectionPoints" ),
					toAliasTableMap( filter.getList( "aliases" ) ),
					toAliasEntityMap( filter.getList( "aliases" ) )
			);
		}
	}

	private String getDefaultFilterCondition(String filterName) {
		final FilterDefinition definition = context.getMetadataCollector().getFilterDefinition( filterName );
		if ( definition == null ) {
			throw new AnnotationException( "Entity '" + name
					+ "' has a '@Filter' for an undefined filter named '" + filterName + "'" );
		}
		final String condition = definition.getDefaultFilterCondition();
		if ( isEmpty( condition ) ) {
			throw new AnnotationException( "Entity '" + name +
					"' has a '@Filter' with no 'condition' and no default condition was given by the '@FilterDef' named '"
					+ filterName + "'" );
		}
		return condition;
	}

	private void bindSynchronize() {
		if ( annotatedClass.hasAnnotationUsage( Synchronize.class ) ) {
			final JdbcEnvironment jdbcEnvironment = context.getMetadataCollector().getDatabase().getJdbcEnvironment();
			final AnnotationUsage<Synchronize> synchronize = annotatedClass.getAnnotationUsage( Synchronize.class);
			final boolean logical = synchronize.getBoolean( "logical" );
			final List<String> tableNames = synchronize.getList( "value" );
			for ( String tableName : tableNames ) {
				String physicalName = logical ? toPhysicalName( jdbcEnvironment, tableName ) : tableName;
				persistentClass.addSynchronizedTable( physicalName );
			}
		}
	}

	private String toPhysicalName(JdbcEnvironment jdbcEnvironment, String logicalName) {
		return context.getBuildingOptions().getPhysicalNamingStrategy()
				.toPhysicalTableName(
						jdbcEnvironment.getIdentifierHelper().toIdentifier( logicalName ),
						jdbcEnvironment
				)
				.render( jdbcEnvironment.getDialect() );
	}

	public PersistentClass getPersistentClass() {
		return persistentClass;
	}

	private void processNamedEntityGraphs() {
		annotatedClass.forEachAnnotationUsage( NamedEntityGraph.class, this::processNamedEntityGraph );
	}

	private void processNamedEntityGraph(AnnotationUsage<NamedEntityGraph> annotation) {
		if ( annotation == null ) {
			return;
		}
		context.getMetadataCollector().addNamedEntityGraph(
				new NamedEntityGraphDefinition( annotation.toAnnotation(), name, persistentClass.getEntityName() )
		);
	}

	public void bindDiscriminatorValue() {
		final String discriminatorValue = annotatedClass.hasAnnotationUsage( DiscriminatorValue.class )
				? annotatedClass.getAnnotationUsage( DiscriminatorValue.class ).getString( "value" )
				: null;
		if ( isEmpty( discriminatorValue ) ) {
			final Value discriminator = persistentClass.getDiscriminator();
			if ( discriminator == null ) {
				persistentClass.setDiscriminatorValue( name );
			}
			else if ( "character".equals( discriminator.getType().getName() ) ) {
				throw new AnnotationException( "Entity '" + name
						+ "' has a discriminator of character type and must specify its '@DiscriminatorValue'" );
			}
			else if ( "integer".equals( discriminator.getType().getName() ) ) {
				persistentClass.setDiscriminatorValue( String.valueOf( name.hashCode() ) );
			}
			else {
				persistentClass.setDiscriminatorValue( name ); //Spec compliant
			}
		}
		else {
			persistentClass.setDiscriminatorValue( discriminatorValue );
		}
	}

	private OptimisticLockStyle getVersioning(OptimisticLockType type) {
		switch ( type ) {
			case VERSION:
				return OptimisticLockStyle.VERSION;
			case NONE:
				return OptimisticLockStyle.NONE;
			case DIRTY:
				return OptimisticLockStyle.DIRTY;
			case ALL:
				return OptimisticLockStyle.ALL;
			default:
				throw new AssertionFailure( "optimistic locking not supported: " + type );
		}
	}

	private boolean isExplicitPolymorphism(PolymorphismType type) {
		switch ( type ) {
			case IMPLICIT:
				return false;
			case EXPLICIT:
				return true;
			default:
				throw new AssertionFailure( "Unknown polymorphism type: " + type );
		}
	}

	public void bindBatchSize() {
		final AnnotationUsage<BatchSize> batchSize = annotatedClass.getAnnotationUsage( BatchSize.class );
		persistentClass.setBatchSize( batchSize != null ? batchSize.getInteger( "size" ) : -1 );
	}

	public void bindProxy() {
		final AnnotationUsage<Proxy> proxy = annotatedClass.getAnnotationUsage( Proxy.class );
		if ( proxy != null ) {
			lazy = proxy.getBoolean( "lazy" );
			proxyClass = lazy ? proxy.getClassDetails( "proxyClass" ) : null;
		}
		else {
			//needed to allow association lazy loading.
			lazy = true;
			proxyClass = annotatedClass;
		}
	}

	public void bindWhere() {
		final AnnotationUsage<Where> where = getOverridableAnnotation( annotatedClass, Where.class, context );
		if ( where != null ) {
			this.where = where.getString( "clause" );
		}
		final AnnotationUsage<SQLRestriction> restriction = getOverridableAnnotation( annotatedClass, SQLRestriction.class, context );
		if ( restriction != null ) {
			this.where = restriction.getString( "value" );
		}
	}

	public void setWrapIdsInEmbeddedComponents(boolean wrapIdsInEmbeddedComponents) {
		this.wrapIdsInEmbeddedComponents = wrapIdsInEmbeddedComponents;
	}

	private void bindNaturalIdCache() {
		naturalIdCacheRegion = null;
		final AnnotationUsage<NaturalIdCache> naturalIdCacheAnn = annotatedClass.getAnnotationUsage( NaturalIdCache.class );
		if ( naturalIdCacheAnn == null ) {
			return;
		}

		final String region = naturalIdCacheAnn.getString( "region" );
		if ( region.isEmpty() ) {
			final AnnotationUsage<Cache> explicitCacheAnn = annotatedClass.getAnnotationUsage( Cache.class );

			naturalIdCacheRegion = explicitCacheAnn != null && isNotEmpty( explicitCacheAnn.getString( "region") )
					? explicitCacheAnn.getString( "region") + NATURAL_ID_CACHE_SUFFIX
					: annotatedClass.getName() + NATURAL_ID_CACHE_SUFFIX;
		}
		else {
			naturalIdCacheRegion = naturalIdCacheAnn.getString( "region" );
		}
	}

	private void bindCache() {
		isCached = false;
		cacheConcurrentStrategy = null;
		cacheRegion = null;
		cacheLazyProperty = true;
		final SharedCacheMode sharedCacheMode  = context.getBuildingOptions().getSharedCacheMode();
		if ( persistentClass instanceof RootClass ) {
			bindRootClassCache( sharedCacheMode, context );
		}
		else {
			bindSubclassCache( sharedCacheMode );
		}
	}

	private void bindSubclassCache(SharedCacheMode sharedCacheMode) {
		if ( annotatedClass.hasAnnotationUsage( Cache.class ) ) {
			final String className = persistentClass.getClassName() == null
					? annotatedClass.getName()
					: persistentClass.getClassName();
			throw new AnnotationException("Entity class '" + className
					+  "' is annotated '@Cache' but it is a subclass in an entity inheritance hierarchy"
					+" (only root classes may define second-level caching semantics)");
		}

		final AnnotationUsage<Cacheable> cacheable = annotatedClass.getAnnotationUsage( Cacheable.class );
		isCached = cacheable == null && persistentClass.getSuperclass() != null
				// we should inherit the root class caching config
				? persistentClass.getSuperclass().isCached()
				//TODO: is this even correct?
				//      Do we even correctly support selectively enabling caching on subclasses like this?
				: isCacheable( sharedCacheMode, cacheable );
	}

	private void bindRootClassCache(SharedCacheMode sharedCacheMode, MetadataBuildingContext context) {
		final AnnotationUsage<Cache> cache = annotatedClass.getAnnotationUsage( Cache.class );
		final AnnotationUsage<Cacheable> cacheable = annotatedClass.getAnnotationUsage( Cacheable.class );
		final AnnotationUsage<Cache> effectiveCache;
		if ( cache != null ) {
			// preserve legacy behavior of circumventing SharedCacheMode when Hibernate's @Cache is used.
			isCached = true;
			effectiveCache = cache;
		}
		else {
			effectiveCache = buildCacheMock( annotatedClass, context );
			isCached = isCacheable( sharedCacheMode, cacheable );
		}
		cacheConcurrentStrategy = resolveCacheConcurrencyStrategy( effectiveCache.getEnum( "usage" ) );
		cacheRegion = effectiveCache.getString( "region" );
		cacheLazyProperty = isCacheLazy( effectiveCache, annotatedClass );
	}

	private static boolean isCacheLazy(AnnotationUsage<Cache> effectiveCache, ClassDetails annotatedClass) {
		if ( !effectiveCache.getBoolean( "includeLazy" ) ) {
			return false;
		}
		return switch ( effectiveCache.getString( "include" ).toLowerCase( Locale.ROOT ) ) {
			case "all" -> true;
			case "non-lazy" -> false;
			default -> throw new AnnotationException(
					"Class '" + annotatedClass.getName()
							+ "' has a '@Cache' with undefined option 'include=\"" + effectiveCache.getString( "include" ) + "\"'" );
		};
	}

	private static boolean isCacheable(SharedCacheMode sharedCacheMode, AnnotationUsage<Cacheable> explicitCacheableAnn) {
		return switch ( sharedCacheMode ) {
			case ALL ->
				// all entities should be cached
					true;
			case ENABLE_SELECTIVE, UNSPECIFIED ->
				// Hibernate defaults to ENABLE_SELECTIVE, the only sensible setting
				// only entities with @Cacheable(true) should be cached
					explicitCacheableAnn != null && explicitCacheableAnn.getBoolean( "value" );
			case DISABLE_SELECTIVE ->
				// only entities with @Cacheable(false) should not be cached
					explicitCacheableAnn == null || explicitCacheableAnn.getBoolean( "value" );
			default ->
				// treat both NONE and UNSPECIFIED the same
					false;
		};
	}

	private static String resolveCacheConcurrencyStrategy(CacheConcurrencyStrategy strategy) {
		final org.hibernate.cache.spi.access.AccessType accessType = strategy.toAccessType();
		return accessType == null ? null : accessType.getExternalName();
	}

	private static AnnotationUsage<Cache> buildCacheMock(ClassDetails classDetails, MetadataBuildingContext context) {
		final MutableAnnotationUsage<Cache> cacheUsage = HibernateAnnotations.CACHE.createUsage(
				classDetails,
				context.getMetadataCollector().getSourceModelBuildingContext()
		);
		cacheUsage.setAttributeValue( "region", classDetails.getName() );
		cacheUsage.setAttributeValue( "usage", determineCacheConcurrencyStrategy( context ) );
		return cacheUsage;
	}

	private static CacheConcurrencyStrategy determineCacheConcurrencyStrategy(MetadataBuildingContext context) {
		return CacheConcurrencyStrategy.fromAccessType( context.getBuildingOptions().getImplicitCacheAccessType() );
	}

	private static class EntityTableNamingStrategyHelper implements NamingStrategyHelper {
		private final String className;
		private final String entityName;
		private final String jpaEntityName;

		private EntityTableNamingStrategyHelper(String className, String entityName, String jpaEntityName) {
			this.className = className;
			this.entityName = entityName;
			this.jpaEntityName = jpaEntityName;
		}

		@Override
		public Identifier determineImplicitName(final MetadataBuildingContext buildingContext) {
			return buildingContext.getBuildingOptions().getImplicitNamingStrategy().determinePrimaryTableName(
					new ImplicitEntityNameSource() {
						private final EntityNaming entityNaming = new EntityNaming() {
							@Override
							public String getClassName() {
								return className;
							}

							@Override
							public String getEntityName() {
								return entityName;
							}

							@Override
							public String getJpaEntityName() {
								return jpaEntityName;
							}
						};

						@Override
						public EntityNaming getEntityNaming() {
							return entityNaming;
						}

						@Override
						public MetadataBuildingContext getBuildingContext() {
							return buildingContext;
						}
					}
			);
		}

		@Override
		public Identifier handleExplicitName(String explicitName, MetadataBuildingContext buildingContext) {
			return buildingContext.getMetadataCollector()
					.getDatabase()
					.getJdbcEnvironment()
					.getIdentifierHelper()
					.toIdentifier( explicitName );
		}

		@Override
		public Identifier toPhysicalName(Identifier logicalName, MetadataBuildingContext buildingContext) {
			return buildingContext.getBuildingOptions().getPhysicalNamingStrategy().toPhysicalTableName(
					logicalName,
					buildingContext.getMetadataCollector().getDatabase().getJdbcEnvironment()
			);
		}
	}

	public void bindTableForDiscriminatedSubclass(InFlightMetadataCollector.EntityTableXref superTableXref) {
		if ( !(persistentClass instanceof SingleTableSubclass) ) {
			throw new AssertionFailure(
					"Was expecting a discriminated subclass [" + SingleTableSubclass.class.getName() +
							"] but found [" + persistentClass.getClass().getName() + "] for entity [" +
							persistentClass.getEntityName() + "]"
			);
		}

		context.getMetadataCollector().addEntityTableXref(
				persistentClass.getEntityName(),
				context.getMetadataCollector().getDatabase().toIdentifier(
						context.getMetadataCollector().getLogicalTableName( superTableXref.getPrimaryTable() )
				),
				superTableXref.getPrimaryTable(),
				superTableXref
		);
	}

	public void bindTable(
			String schema,
			String catalog,
			String tableName,
			List<AnnotationUsage<UniqueConstraint>> uniqueConstraints,
			String rowId,
			String viewQuery,
			InFlightMetadataCollector.EntityTableXref denormalizedSuperTableXref) {

		final EntityTableNamingStrategyHelper namingStrategyHelper = new EntityTableNamingStrategyHelper(
				persistentClass.getClassName(),
				persistentClass.getEntityName(),
				name
		);
		final Identifier logicalName = isNotEmpty( tableName )
				? namingStrategyHelper.handleExplicitName( tableName, context )
				: namingStrategyHelper.determineImplicitName( context );

		final Table table = TableBinder.buildAndFillTable(
				schema,
				catalog,
				logicalName,
				persistentClass.isAbstract(),
				uniqueConstraints,
				context,
				subselect,
				denormalizedSuperTableXref
		);

		table.setRowId( rowId );
		table.setViewQuery( viewQuery );

//		final Comment comment = annotatedClass.getAnnotation( Comment.class );
//		if ( comment != null ) {
//			table.setComment( comment.value() );
//		}

		context.getMetadataCollector().addEntityTableXref(
				persistentClass.getEntityName(),
				logicalName,
				table,
				denormalizedSuperTableXref
		);

		if ( persistentClass instanceof TableOwner ) {
			LOG.debugf( "Bind entity %s on table %s", persistentClass.getEntityName(), table.getName() );
			( (TableOwner) persistentClass ).setTable( table );
		}
		else {
			throw new AssertionFailure( "binding a table for a subclass" );
		}
	}

	public void finalSecondaryTableBinding(PropertyHolder propertyHolder) {
		 // This operation has to be done after the id definition of the persistence class.
		 // ie after the properties parsing
		final Iterator<Object> joinColumns = secondaryTableJoins.values().iterator();
		for ( Map.Entry<String, Join> entrySet : secondaryTables.entrySet() ) {
			if ( !secondaryTablesFromAnnotation.containsKey( entrySet.getKey() ) ) {
				createPrimaryColumnsToSecondaryTable( joinColumns.next(), propertyHolder, entrySet.getValue() );
			}
		}
	}

	public void finalSecondaryTableFromAnnotationBinding(PropertyHolder propertyHolder) {
		 // This operation has to be done before the end of the FK second pass processing in order
		 // to find the join columns belonging to secondary tables
		Iterator<Object> joinColumns = secondaryTableFromAnnotationJoins.values().iterator();
		for ( Map.Entry<String, Join> entrySet : secondaryTables.entrySet() ) {
			if ( secondaryTablesFromAnnotation.containsKey( entrySet.getKey() ) ) {
				createPrimaryColumnsToSecondaryTable( joinColumns.next(), propertyHolder, entrySet.getValue() );
			}
		}
	}

	private void createPrimaryColumnsToSecondaryTable(Object column, PropertyHolder propertyHolder, Join join) {
		// `column` will be a list of some sort...
		//noinspection unchecked,rawtypes
		final List<AnnotationUsage> joinColumnSource = (List<AnnotationUsage>) column;
		final AnnotatedJoinColumns annotatedJoinColumns;

		if ( CollectionHelper.isEmpty( joinColumnSource ) ) {
			annotatedJoinColumns = createDefaultJoinColumn( propertyHolder );
		}
		else {
			final List<AnnotationUsage<PrimaryKeyJoinColumn>> pkJoinColumns;
			final List<AnnotationUsage<JoinColumn>> joinColumns;

			//noinspection rawtypes
			final AnnotationUsage first = joinColumnSource.get( 0 );
			if ( first.getAnnotationDescriptor().getAnnotationType().equals( PrimaryKeyJoinColumn.class ) ) {
				//noinspection unchecked,rawtypes
				pkJoinColumns = (List) joinColumnSource;
				joinColumns = null;
			}
			else if ( first.getAnnotationDescriptor().getAnnotationType().equals( JoinColumn.class ) ) {
				pkJoinColumns = null;
				//noinspection unchecked,rawtypes
				joinColumns = (List) joinColumnSource;
			}
			else {
				throw new IllegalArgumentException(
						"Expecting list of AnnotationUsages for either @JoinColumn or @PrimaryKeyJoinColumn"
								+ ", but got as list of AnnotationUsages for @" + first.getAnnotationDescriptor().getAnnotationType().getName()

				);
			}

			annotatedJoinColumns = createJoinColumns( propertyHolder, pkJoinColumns, joinColumns );
		}

		for ( AnnotatedJoinColumn joinColumn : annotatedJoinColumns.getJoinColumns() ) {
			joinColumn.forceNotNull();
		}
		bindJoinToPersistentClass( join, annotatedJoinColumns, context );
	}

	private AnnotatedJoinColumns createDefaultJoinColumn(PropertyHolder propertyHolder) {
		final AnnotatedJoinColumns joinColumns = new AnnotatedJoinColumns();
		joinColumns.setBuildingContext( context );
		joinColumns.setJoins( secondaryTables );
		joinColumns.setPropertyHolder( propertyHolder );
		buildInheritanceJoinColumn(
				null,
				null,
				persistentClass.getIdentifier(),
				joinColumns,
				context
		);
		return joinColumns;
	}

	private AnnotatedJoinColumns createJoinColumns(
			PropertyHolder propertyHolder,
			List<AnnotationUsage<PrimaryKeyJoinColumn>> primaryKeyJoinColumns,
			List<AnnotationUsage<JoinColumn>> joinColumns) {
		final int joinColumnCount = primaryKeyJoinColumns != null ? primaryKeyJoinColumns.size() : joinColumns.size();
		if ( joinColumnCount == 0 ) {
			return createDefaultJoinColumn( propertyHolder );
		}
		else {
			final AnnotatedJoinColumns columns = new AnnotatedJoinColumns();
			columns.setBuildingContext( context );
			columns.setJoins( secondaryTables );
			columns.setPropertyHolder( propertyHolder );
			for ( int colIndex = 0; colIndex < joinColumnCount; colIndex++ ) {
				final AnnotationUsage<PrimaryKeyJoinColumn> primaryKeyJoinColumn = primaryKeyJoinColumns != null
						? primaryKeyJoinColumns.get( colIndex)
						: null;
				final AnnotationUsage<JoinColumn> joinColumn = joinColumns != null
						? joinColumns.get(colIndex)
						: null;
				buildInheritanceJoinColumn(
						primaryKeyJoinColumn,
						joinColumn,
						persistentClass.getIdentifier(),
						columns,
						context
				);
			}
			return columns;
		}
	}

	private void bindJoinToPersistentClass(Join join, AnnotatedJoinColumns joinColumns, MetadataBuildingContext context) {
		DependantValue key = new DependantValue( context, join.getTable(), persistentClass.getIdentifier() );
		join.setKey( key );
		setForeignKeyNameIfDefined( join );
		key.setOnDeleteAction( null );
		TableBinder.bindForeignKey( persistentClass, null, joinColumns, key, false, context );
		key.sortProperties();
		join.createPrimaryKey();
		join.createForeignKey();
		persistentClass.addJoin( join );
	}

	private void setForeignKeyNameIfDefined(Join join) {
		final String tableName = join.getTable().getQuotedName();
		final AnnotationUsage<org.hibernate.annotations.Table> matchingTable = findMatchingComplementaryTableAnnotation( tableName );
		final SimpleValue key = (SimpleValue) join.getKey();
		if ( matchingTable != null && !matchingTable.getNestedUsage( "foreignKey" ).getString( "name" ).isEmpty() ) {
			key.setForeignKeyName( matchingTable.getNestedUsage( "foreignKey" ).getString( "name" ) );
		}
		else {
			final AnnotationUsage<SecondaryTable> jpaSecondaryTable = findMatchingSecondaryTable( join );
			if ( jpaSecondaryTable != null ) {
				final boolean noConstraintByDefault = context.getBuildingOptions().isNoConstraintByDefault();
				if ( jpaSecondaryTable.getNestedUsage( "foreignKey" ).getEnum( "value" ) == ConstraintMode.NO_CONSTRAINT
						|| jpaSecondaryTable.getNestedUsage( "foreignKey" ).getEnum( "value" ) == ConstraintMode.PROVIDER_DEFAULT && noConstraintByDefault ) {
					key.disableForeignKey();
				}
				else {
					key.setForeignKeyName( nullIfEmpty( jpaSecondaryTable.getNestedUsage( "foreignKey" ).getString( "name" ) ) );
					key.setForeignKeyDefinition( nullIfEmpty( jpaSecondaryTable.getNestedUsage( "foreignKey" ).getString( "foreignKeyDefinition" ) ) );
				}
			}
		}
	}

	private AnnotationUsage<SecondaryTable> findMatchingSecondaryTable(Join join) {
		final String nameToMatch = join.getTable().getQuotedName();
		final AnnotationUsage<SecondaryTable> secondaryTable = annotatedClass.getSingleAnnotationUsage( SecondaryTable.class );
		if ( secondaryTable != null && nameToMatch.equals( secondaryTable.getString( "name" ) ) ) {
			return secondaryTable;
		}
		final AnnotationUsage<SecondaryTables> secondaryTables = annotatedClass.getAnnotationUsage( SecondaryTables.class );
		if ( secondaryTables != null ) {
			final List<AnnotationUsage<SecondaryTable>> nestedSecondaryTableList = secondaryTables.getList( "value" );
			for ( AnnotationUsage<SecondaryTable> nestedSecondaryTable : nestedSecondaryTableList ) {
				if ( nestedSecondaryTable != null && nameToMatch.equals( nestedSecondaryTable.getString( "name" ) ) ) {
					return nestedSecondaryTable;
				}
			}
		}
		return null;
	}

	private AnnotationUsage<org.hibernate.annotations.Table> findMatchingComplementaryTableAnnotation(String tableName) {
		final AnnotationUsage<org.hibernate.annotations.Table> table = annotatedClass.getSingleAnnotationUsage( org.hibernate.annotations.Table.class );
		if ( table != null && tableName.equals( table.getString( "appliesTo" ) ) ) {
			return table;
		}
		else {
			final AnnotationUsage<Tables> tables = annotatedClass.getAnnotationUsage( Tables.class );
			if ( tables != null ) {
				for (AnnotationUsage<org.hibernate.annotations.Table> nested : tables.<AnnotationUsage<org.hibernate.annotations.Table>>getList( "value" )) {
					if ( tableName.equals( nested.getString( "appliesTo" ) ) ) {
						return nested;
					}
				}
			}
			return null;
		}
	}

	private AnnotationUsage<SecondaryRow> findMatchingSecondaryRowAnnotation(String tableName) {
		final AnnotationUsage<SecondaryRow> row = annotatedClass.getSingleAnnotationUsage( SecondaryRow.class );
		if ( row != null && ( row.getString( "table" ).isEmpty() || tableName.equals( row.getString( "table" ) ) ) ) {
			return row;
		}
		else {
			final AnnotationUsage<SecondaryRows> tables = annotatedClass.getAnnotationUsage( SecondaryRows.class );
			if ( tables != null ) {
				final List<AnnotationUsage<SecondaryRow>> rowList = tables.getList( "value" );
				for ( AnnotationUsage<SecondaryRow> current : rowList ) {
					if ( tableName.equals( current.getString( "table" ) ) ) {
						return current;
					}
				}
			}
			return null;
		}
	}

	private <T extends Annotation,R extends Annotation> AnnotationUsage<T> findMatchingSqlAnnotation(
			String tableName,
			Class<T> annotationType,
			Class<R> repeatableType) {
		final AnnotationUsage<T> sqlAnnotation = getOverridableAnnotation( annotatedClass, annotationType, context );
		if ( sqlAnnotation != null ) {
			if ( tableName.equals( sqlAnnotation.getString( "table" ) ) ) {
				return sqlAnnotation;
			}
		}
		//TODO: getOverridableAnnotation() does not yet handle @Repeatable annotations
		final AnnotationUsage<R> repeatable = annotatedClass.getAnnotationUsage( repeatableType );
		if ( repeatable != null ) {
			for ( AnnotationUsage<T> nested : repeatable.<AnnotationUsage<T>>getList( "value" ) ) {
				if ( tableName.equals( nested.getString( "table" ) ) ) {
					return nested;
				}
			}
		}
		return null;
	}

	private static <T extends Annotation> String tableMember(Class<T> annotationType, T sqlAnnotation) {
		if (SQLInsert.class.equals(annotationType)) {
			return ((SQLInsert) sqlAnnotation).table();
		}
		else if (SQLUpdate.class.equals(annotationType)) {
			return ((SQLUpdate) sqlAnnotation).table();
		}
		else if (SQLDelete.class.equals(annotationType)) {
			return ((SQLDelete) sqlAnnotation).table();
		}
		else if (SQLDeleteAll.class.equals(annotationType)) {
			return ((SQLDeleteAll) sqlAnnotation).table();
		}
		else {
			throw new AssertionFailure("Unknown annotation type");
		}
	}

	private static <T extends Annotation> Annotation[] valueMember(Class<T> repeatableType, T sqlAnnotation) {
		if (SQLInserts.class.equals(repeatableType)) {
			return ((SQLInserts) sqlAnnotation).value();
		}
		else if (SQLUpdates.class.equals(repeatableType)) {
			return ((SQLUpdates) sqlAnnotation).value();
		}
		else if (SQLDeletes.class.equals(repeatableType)) {
			return ((SQLDeletes) sqlAnnotation).value();
		}
		else {
			throw new AssertionFailure("Unknown annotation type");
		}
	}

	//Used for @*ToMany @JoinTable
	public Join addJoinTable(AnnotationUsage<JoinTable> joinTable, PropertyHolder holder, boolean noDelayInPkColumnCreation) {
		return addJoin(
				holder,
				noDelayInPkColumnCreation,
				false,
				joinTable.getString( "name" ),
				joinTable.getString( "schema" ),
				joinTable.getString( "catalog" ),
				joinTable.getList( "joinColumns" ),
				joinTable.getList( "uniqueConstraints" )
		);
	}

	public Join addSecondaryTable(AnnotationUsage<SecondaryTable> secondaryTable, PropertyHolder holder, boolean noDelayInPkColumnCreation) {
		final Join join = addJoin(
				holder,
				noDelayInPkColumnCreation,
				true,
				secondaryTable.getString( "name" ),
				secondaryTable.getString( "schema" ),
				secondaryTable.getString( "catalog" ),
				secondaryTable.getList( "pkJoinColumns" ),
				secondaryTable.getList( "uniqueConstraints" )
		);
		final Table table = join.getTable();
		new IndexBinder( context ).bindIndexes( table, secondaryTable.getList( "indexes" ) );
		return join;
	}

	private Join addJoin(
			PropertyHolder propertyHolder,
			boolean noDelayInPkColumnCreation,
			boolean secondaryTable,
			String name,
			String schema,
			String catalog,
			Object joinColumns,
			List<AnnotationUsage<UniqueConstraint>> uniqueConstraints) {
		final QualifiedTableName logicalName = new QualifiedTableName(
				Identifier.toIdentifier( catalog ),
				Identifier.toIdentifier( schema ),
				context.getMetadataCollector()
						.getDatabase()
						.getJdbcEnvironment()
						.getIdentifierHelper()
						.toIdentifier(name)
		);
		return createJoin(
				propertyHolder,
				noDelayInPkColumnCreation,
				secondaryTable,
				joinColumns,
				logicalName,
				TableBinder.buildAndFillTable(
						schema,
						catalog,
						logicalName.getTableName(),
						false,
						uniqueConstraints,
						context
				)
		);
	}

	Join createJoin(
			PropertyHolder propertyHolder,
			boolean noDelayInPkColumnCreation,
			boolean secondaryTable,
			Object joinColumns,
			QualifiedTableName logicalName,
			Table table) {
		final Join join = new Join();
		persistentClass.addJoin( join );

		final InFlightMetadataCollector.EntityTableXref tableXref
				= context.getMetadataCollector().getEntityTableXref( persistentClass.getEntityName() );
		assert tableXref != null : "Could not locate EntityTableXref for entity [" + persistentClass.getEntityName() + "]";
		tableXref.addSecondaryTable( logicalName, join );

		// No check constraints available on joins
		join.setTable( table );

		// Somehow keep joins() for later.
		// Has to do the work later because it needs PersistentClass id!
		LOG.debugf( "Adding secondary table to entity %s -> %s",
				persistentClass.getEntityName(), join.getTable().getName() );

		handleSecondaryRowManagement( join );
		processSecondaryTableCustomSql( join );

		if ( noDelayInPkColumnCreation ) {
			// A non-null propertyHolder means than we process the Pk creation without delay
			createPrimaryColumnsToSecondaryTable( joinColumns, propertyHolder, join );
		}
		else {
			final String quotedName = table.getQuotedName();
			if ( secondaryTable ) {
				secondaryTablesFromAnnotation.put( quotedName, join );
				secondaryTableFromAnnotationJoins.put( quotedName, joinColumns );
			}
			else {
				secondaryTableJoins.put( quotedName, joinColumns );
			}
			secondaryTables.put( quotedName, join );
		}

		return join;
	}

	private void handleSecondaryRowManagement(Join join) {
		final String tableName = join.getTable().getQuotedName();
		final AnnotationUsage<org.hibernate.annotations.Table> matchingTable = findMatchingComplementaryTableAnnotation( tableName );
		final AnnotationUsage<SecondaryRow> matchingRow = findMatchingSecondaryRowAnnotation( tableName );
		if ( matchingRow != null ) {
			join.setInverse( !matchingRow.getBoolean( "owned" ) );
			join.setOptional( matchingRow.getBoolean( "optional" ) );
		}
		else if ( matchingTable != null ) {
			join.setInverse( matchingTable.getBoolean( "inverse" ) );
			join.setOptional( matchingTable.getBoolean( "optional" ) );
		}
		else {
			//default
			join.setInverse( false );
			join.setOptional( true ); //perhaps not quite per-spec, but a Good Thing anyway
		}
	}

	private void processSecondaryTableCustomSql(Join join) {
		final String tableName = join.getTable().getQuotedName();
		final AnnotationUsage<org.hibernate.annotations.Table> matchingTable = findMatchingComplementaryTableAnnotation( tableName );
		final AnnotationUsage<SQLInsert> sqlInsert = findMatchingSqlAnnotation( tableName, SQLInsert.class, SQLInserts.class );
		if ( sqlInsert != null ) {
			join.setCustomSQLInsert(
					sqlInsert.getString( "sql" ).trim(),
					sqlInsert.getBoolean( "callable" ),
					fromResultCheckStyle( sqlInsert.getEnum( "check" ) )
			);
		}
		else if ( matchingTable != null ) {
			final AnnotationUsage<Annotation> matchingTableInsert = matchingTable.getNestedUsage( "sqlInsert" );
			final String matchingTableInsertSql = matchingTableInsert.getString( "sql" ).trim();
			if ( !matchingTableInsertSql.isEmpty() ) {
				join.setCustomSQLInsert(
						matchingTableInsertSql,
						matchingTableInsert.getBoolean( "callable" ),
						fromResultCheckStyle( matchingTableInsert.getEnum( "check" ) )
				);
			}
		}

		final AnnotationUsage<SQLUpdate> sqlUpdate = findMatchingSqlAnnotation( tableName, SQLUpdate.class, SQLUpdates.class );
		if ( sqlUpdate != null ) {
			join.setCustomSQLUpdate(
					sqlUpdate.getString( "sql" ).trim(),
					sqlUpdate.getBoolean( "callable" ),
					fromResultCheckStyle( sqlUpdate.getEnum( "check" ) )
			);
		}
		else if ( matchingTable != null ) {
			final AnnotationUsage<Annotation> matchingTableUpdate = matchingTable.getNestedUsage( "sqlUpdate" );
			final String matchingTableUpdateSql = matchingTableUpdate.getString( "sql" ).trim();
			if ( !matchingTableUpdateSql.isEmpty() ) {
				join.setCustomSQLUpdate(
						matchingTableUpdateSql,
						matchingTableUpdate.getBoolean( "callable" ),
						fromResultCheckStyle( matchingTableUpdate.getEnum( "check" ) )
				);
			}
		}

		final AnnotationUsage<SQLDelete> sqlDelete = findMatchingSqlAnnotation( tableName, SQLDelete.class, SQLDeletes.class );
		if ( sqlDelete != null ) {
			join.setCustomSQLDelete(
					sqlDelete.getString( "sql" ).trim(),
					sqlDelete.getBoolean( "callable" ),
					fromResultCheckStyle( sqlDelete.getEnum( "check" ) )
			);
		}
		else if ( matchingTable != null ) {
			final AnnotationUsage<Annotation> matchingTableDelete = matchingTable.getNestedUsage( "sqlDelete" );
			final String deleteSql = matchingTableDelete.getString( "sql" ).trim();
			if ( !deleteSql.isEmpty() ) {
				join.setCustomSQLDelete(
						deleteSql,
						matchingTableDelete.getBoolean( "callable" ),
						fromResultCheckStyle( matchingTableDelete.getEnum( "check" ) )
				);
			}
		}
	}

	public java.util.Map<String, Join> getSecondaryTables() {
		return secondaryTables;
	}

	public static String getCacheConcurrencyStrategy(CacheConcurrencyStrategy strategy) {
		final org.hibernate.cache.spi.access.AccessType accessType = strategy.toAccessType();
		return accessType == null ? null : accessType.getExternalName();
	}

	public void addFilter(AnnotationUsage<Filter> filter) {
		filters.add( filter );
	}

	public boolean isIgnoreIdAnnotations() {
		return ignoreIdAnnotations;
	}

	public void setIgnoreIdAnnotations(boolean ignoreIdAnnotations) {
		this.ignoreIdAnnotations = ignoreIdAnnotations;
	}

	private Table findTable(String tableName) {
		for ( Table table : persistentClass.getTableClosure() ) {
			if ( table.getQuotedName().equals( tableName ) ) {
				//we are in the correct table to find columns
				return table;
			}
		}
		//maybe a join/secondary table
		for ( Join join : secondaryTables.values() ) {
			if ( join.getTable().getQuotedName().equals( tableName ) ) {
				return join.getTable();
			}
		}
		throw new AnnotationException( "Entity '" + name
				+ "' has a '@org.hibernate.annotations.Table' annotation which 'appliesTo' an unknown table named '"
				+ tableName + "'" );
	}

	public AccessType getPropertyAccessType() {
		return propertyAccessType;
	}

	public void setPropertyAccessType(AccessType propertyAccessType) {
		this.propertyAccessType = getExplicitAccessType( annotatedClass );
		// only set the access type if there is no explicit access type for this class
		if ( this.propertyAccessType == null ) {
			this.propertyAccessType = propertyAccessType;
		}
	}

	public AccessType getPropertyAccessor(AnnotationTarget element) {
		final AccessType accessType = getExplicitAccessType( element );
		return accessType == null ? propertyAccessType : accessType;
	}

	public AccessType getExplicitAccessType(AnnotationTarget element) {
		AccessType accessType = null;
		if ( element != null ) {
			final AnnotationUsage<Access> access = element.getAnnotationUsage( Access.class );
			if ( access != null ) {
				accessType = AccessType.getAccessStrategy( access.getEnum( "value" ) );
			}
		}
		return accessType;
	}

	/**
	 * Process the filters defined on the given class, as well as all filters
	 * defined on the MappedSuperclass(es) in the inheritance hierarchy
	 */
	public void bindFiltersInHierarchy() {

		bindFilters( annotatedClass );

		ClassDetails classToProcess = annotatedClass.getSuperClass();
		while ( classToProcess != null ) {
			final AnnotatedClassType classType = context.getMetadataCollector().getClassType( classToProcess );
			if ( classType == MAPPED_SUPERCLASS ) {
				bindFilters( classToProcess );
			}
			else {
				break;
			}
			classToProcess = classToProcess.getSuperClass();
		}
	}

	private void bindFilters(AnnotationTarget element) {
		final AnnotationUsage<Filters> filters = getOverridableAnnotation( element, Filters.class, context );
		if ( filters != null ) {
			for ( AnnotationUsage<Filter> filter : filters.<AnnotationUsage<Filter>>getList( "value" ) ) {
				addFilter( filter );
			}
		}
		final AnnotationUsage<Filter> filter = element.getSingleAnnotationUsage( Filter.class );
		if ( filter != null ) {
			addFilter( filter );
		}
	}
}
