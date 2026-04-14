/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.boot.model.internal;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.hibernate.MappingException;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.ModifiedEntityNames;
import org.hibernate.audit.RevisionEntity;
import org.hibernate.audit.RevisionListener;
import org.hibernate.audit.RevisionNumber;
import org.hibernate.audit.RevisionTimestamp;
import org.hibernate.audit.spi.RevisionEntitySupplier;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.models.spi.ClassDetails;
import org.hibernate.models.spi.MemberDetails;
import org.hibernate.resource.beans.spi.ManagedBeanRegistry;
import org.hibernate.mapping.AuxiliaryTableHolder;
import org.hibernate.mapping.Backref;
import org.hibernate.mapping.BasicValue;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.PrimaryKey;
import org.hibernate.mapping.RootClass;
import org.hibernate.mapping.Stateful;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.UnionSubclass;
import org.hibernate.mapping.TableOwner;
import org.hibernate.persister.state.internal.AuditStateManagement;
import org.hibernate.temporal.spi.TransactionIdentifierService;

import static org.hibernate.internal.util.StringHelper.isBlank;

/**
 * Helper for building audit log tables in the boot model.
 */
public final class AuditHelper {
	public static final String TRANSACTION_ID = "transactionId";
	public static final String MODIFICATION_TYPE = "modificationType";
	public static final String TRANSACTION_END = "transactionEnd";
	public static final String TRANSACTION_END_TIMESTAMP = "transactionEndTimestamp";

	// defaults for backward compatibility with envers

	private static final String DEFAULT_TABLE_SUFFIX = "_aud";

	private AuditHelper() {
	}

	static void bindAuditTable(
			Audited audited,
			RootClass rootClass,
			MetadataBuildingContext context) {
		bindAuditTable( audited, (Stateful) rootClass, context );
		bindSecondaryAuditTables( audited, rootClass, context );
		bindSubclassAuditTables( audited, rootClass, context );
	}

	static void bindAuditTable(
			Audited audited,
			Collection collection,
			MetadataBuildingContext context) {
		bindAuditTable( audited, (Stateful) collection, context );
	}

	private static void bindAuditTable(
			Audited audited,
			Stateful auditable,
			MetadataBuildingContext context) {
		final var collector = context.getMetadataCollector();
		final var table = auditable.getMainTable();
		final String explicitAuditTableName = audited.tableName();
		final boolean hasExplicitAuditTableName = !isBlank( explicitAuditTableName );
		final var auditTable = collector.addTable(
				table.getSchema(),
				table.getCatalog(),
				hasExplicitAuditTableName
						? explicitAuditTableName
						: collector.getLogicalTableName( table )
								+ DEFAULT_TABLE_SUFFIX,
				table.getSubselect(),
				table.isAbstract(),
				context,
				hasExplicitAuditTableName
						|| table.getNameIdentifier().isExplicit()
		);
		collector.addTableNameBinding( table.getNameIdentifier(), auditTable );

		// Defer audit column creation to a second pass so the transaction
		// ID type is resolved after all entities are bound — including any
		// @RevisionEntity contributed by mapping contributors
		final String txIdColumnName = audited.transactionId();
		final String modTypeColumnName = audited.modificationType();
		collector.addSecondPass( (OptionalDeterminationSecondPass) ignored -> {
			// Resolve exclusions at second-pass time so collection-managed FK columns
			// (added during collection binding) are detected
			final var excludedColumns = auditable instanceof RootClass rootClass
					? resolveExcludedColumns( rootClass )
					: Set.<String>of();
			copyTableColumns( table, auditTable, excludedColumns );
			final var transactionIdColumn =
					createAuditColumn( txIdColumnName,
							getTransactionIdType( context ), auditTable, context );
			final var modificationTypeColumn =
					createAuditColumn( modTypeColumnName,
							Byte.class, auditTable, context );
			auditTable.addColumn( transactionIdColumn );
			auditTable.addColumn( modificationTypeColumn );
			if ( auditable instanceof Collection ) {
				// Collection audit PK: (REV, all_source_cols)
				createAuditPrimaryKey( auditTable, transactionIdColumn, table.getColumns() );
			}
			else {
				// Entity audit PK: (REV, entity_id_cols) from source table's PK
				createAuditPrimaryKey( auditTable, transactionIdColumn, table.getPrimaryKey().getColumns() );
			}
			enableAudit( auditable, auditTable, transactionIdColumn, modificationTypeColumn );
			createRevisionForeignKey( auditTable, transactionIdColumn, context );
			addTransactionEndColumns( audited, auditable, auditTable, context );
		} );
	}

	private static void bindSecondaryAuditTables(
			Audited audited,
			RootClass rootClass,
			MetadataBuildingContext context) {
		final String txIdColumnName = audited.transactionId();
		context.getMetadataCollector().addSecondPass( (OptionalDeterminationSecondPass) ignored -> {
			for ( var join : rootClass.getJoins() ) {
				final var auditTable = createAuditTable(
						join.getTable(),
						txIdColumnName,
						resolveExcludedColumns( join.getProperties() ),
						context
				);
				createAuditTableForeignKey( auditTable, rootClass.getEntityName(), rootClass.getAuxiliaryTable() );
				// Secondary tables only get tx-id (no mod type, no REVEND)
				join.setAuxiliaryTable( auditTable );
				join.addAuxiliaryColumn( TRANSACTION_ID, auditTable.getPrimaryKey().getColumn( 0 ) );
			}
		} );
	}

	private static void bindSubclassAuditTables(
			Audited audited,
			RootClass rootClass,
			MetadataBuildingContext context) {
		final String txIdColumnName = audited.transactionId();
		final String modTypeColumnName = audited.modificationType();
		// Defer to second pass — subclasses haven't been added to rootClass yet
		context.getMetadataCollector().addSecondPass( (OptionalDeterminationSecondPass) ignored ->
				bindSubclassAuditTables(
						rootClass,
						audited,
						txIdColumnName,
						modTypeColumnName,
						context
				)
		);
	}

	/**
	 * Create audit tables for direct subclasses of {@code parent},
	 * then recurse into their children.
	 */
	private static void bindSubclassAuditTables(
			PersistentClass parent,
			Audited audited,
			String txIdColumnName,
			String modTypeColumnName,
			MetadataBuildingContext context) {
		for ( var subclass : parent.getDirectSubclasses() ) {
			if ( subclass instanceof TableOwner ) {
				final var auditTable = createAuditTable(
						subclass.getTable(),
						txIdColumnName,
						resolveExcludedColumns( subclass.getProperties() ),
						context
				);
				subclass.addAuxiliaryColumn( TRANSACTION_ID, auditTable.getPrimaryKey().getColumn( 0 ) );
				if ( subclass instanceof UnionSubclass ) {
					// TABLE_PER_CLASS: each table is self-contained, needs its own REVTYPE and REVEND
					final var modificationTypeColumn =
							createAuditColumn( modTypeColumnName,
									Byte.class, auditTable, context );
					auditTable.addColumn( modificationTypeColumn );
					subclass.addAuxiliaryColumn( MODIFICATION_TYPE, modificationTypeColumn );
					addTransactionEndColumns( audited, subclass, auditTable, context );
				}
				else {
					// JOINED: REVTYPE/REVEND only on root table; FK to parent audit table
					createAuditTableForeignKey(
							auditTable,
							parent.getEntityName(),
							parent.getAuxiliaryTable()
					);
				}
				subclass.setAuxiliaryTable( auditTable );
				// Recurse into this subclass's children
				bindSubclassAuditTables( subclass, audited, txIdColumnName, modTypeColumnName, context );
			}
		}
	}

	static void enableAudit(
			Stateful model, Table auditTable,
			Column transactionIdColumn, Column modificationTypeColumn) {
		model.setAuxiliaryTable( auditTable );
		model.addAuxiliaryColumn( TRANSACTION_ID, transactionIdColumn );
		model.addAuxiliaryColumn( MODIFICATION_TYPE, modificationTypeColumn );
		model.setStateManagementType( AuditStateManagement.class );
	}

	/**
	 * Create a middle audit table for unidirectional @OneToMany @JoinColumn.
	 * The table tracks collection membership with (parent_key, child_key, REV, REVTYPE)
	 * <p>
	 * The child entity's FK column is on the child table, but from an entity model
	 * perspective the collection is part of the parent entity's state.
	 */
	static void bindOneToManyAuditTable(
			Audited audited,
			Collection collection,
			String referencedEntityName,
			MetadataBuildingContext context) {
		final var collector = context.getMetadataCollector();
		final var ownerTable = collection.getOwner().getTable();

		// Table name: {OwnerJpaEntityName}_{ChildJpaEntityName}_aud (envers convention)
		final String ownerSimpleName = collection.getOwner().getJpaEntityName();
		final var referencedEntity = collector.getEntityBinding( referencedEntityName );
		final String childSimpleName = referencedEntity.getJpaEntityName();
		final String auditTableName = ownerSimpleName + "_" + childSimpleName + DEFAULT_TABLE_SUFFIX;

		final var auditTable = collector.addTable(
				ownerTable.getSchema(),
				ownerTable.getCatalog(),
				auditTableName,
				null,
				false,
				context,
				false
		);

		final String txIdColumnName = audited.transactionId();
		final String modTypeColumnName = audited.modificationType();
		collector.addSecondPass( (OptionalDeterminationSecondPass) ignored -> {
			final var keyColumns = new ArrayList<Column>();
			// Copy the FK columns (parent key) from the collection's key
			for ( var column : collection.getKey().getColumns() ) {
				final var copy = column.clone();
				copy.setUnique( false );
				copy.setUniqueKeyName( null );
				auditTable.addColumn( copy );
				keyColumns.add( copy );
			}
			// Copy the child identifier columns from the referenced entity
			for ( var column : referencedEntity.getKey().getColumns() ) {
				final var copy = column.clone();
				copy.setUnique( false );
				copy.setUniqueKeyName( null );
				auditTable.addColumn( copy );
				keyColumns.add( copy );
			}
			// Audit columns
			final var transactionIdColumn = createAuditColumn(
					txIdColumnName,
					getTransactionIdType( context ),
					auditTable,
					context
			);
			final var modificationTypeColumn = createAuditColumn(
					modTypeColumnName,
					Byte.class,
					auditTable,
					context
			);
			auditTable.addColumn( transactionIdColumn );
			auditTable.addColumn( modificationTypeColumn );
			createAuditPrimaryKey( auditTable, transactionIdColumn, keyColumns );
			createRevisionForeignKey( auditTable, transactionIdColumn, context );
			enableAudit( collection, auditTable, transactionIdColumn, modificationTypeColumn );
			addTransactionEndColumns( audited, collection, auditTable, context );
		} );
	}

	static void bindRevisionEntity(
			RevisionEntity revisionEntity,
			RootClass rootClass,
			ClassDetails classDetails,
			MetadataBuildingContext context) {
		final var modelsContext = context.getBootstrapContext().getModelsContext();

		// todo (envers-rewrite) : @RevisionEntity currently requires @Entity;
		//  could we automatically imply @Entity for @RevisionEntity classes
		//  so users don't need both annotations?

		// The entity must not be audited
		if ( classDetails.hasAnnotationUsage( Audited.class, modelsContext ) ) {
			throw new MappingException( "The @RevisionEntity entity cannot be audited" );
		}

		// Scan class members (including supertypes) for @RevisionNumber,
		// @RevisionTimestamp, and @ModifiedEntityNames. We need the names
		// and type eagerly to configure the supplier before audit table
		// second passes create the REV column.
		MemberDetails revNumberMember = null;
		MemberDetails revTimestampMember = null;
		MemberDetails modifiedEntityNamesMember = null;
		for ( var current = classDetails; current != null; current = current.getSuperClass() ) {
			for ( var member : current.getFields() ) {
				revNumberMember = checkAnnotation( member, revNumberMember, RevisionNumber.class, classDetails );
				revTimestampMember = checkAnnotation( member, revTimestampMember, RevisionTimestamp.class, classDetails );
				modifiedEntityNamesMember = checkAnnotation( member, modifiedEntityNamesMember, ModifiedEntityNames.class, classDetails );
			}
			for ( var member : current.getMethods() ) {
				revNumberMember = checkAnnotation( member, revNumberMember, RevisionNumber.class, classDetails );
				revTimestampMember = checkAnnotation( member, revTimestampMember, RevisionTimestamp.class, classDetails );
				modifiedEntityNamesMember = checkAnnotation( member, modifiedEntityNamesMember, ModifiedEntityNames.class, classDetails );
			}
		}

		if ( revNumberMember == null ) {
			throw new MappingException(
					"@RevisionEntity '" + classDetails.getName()
					+ "' must have a property annotated with @RevisionNumber"
			);
		}
		if ( revTimestampMember == null ) {
			throw new MappingException(
					"@RevisionEntity '" + classDetails.getName()
					+ "' must have a property annotated with @RevisionTimestamp"
			);
		}

		// Configure the supplier eagerly
		final var serviceRegistry = context.getBootstrapContext().getServiceRegistry();
		final Class<? extends RevisionListener> listenerClass = revisionEntity.listener();
		final RevisionListener listener = listenerClass != RevisionListener.class
				? serviceRegistry.requireService( ManagedBeanRegistry.class ).getBean( listenerClass ).getBeanInstance()
				: null;
		final var supplier = new RevisionEntitySupplier<>(
				classDetails.toJavaClass(),
				revNumberMember.resolveAttributeName(),
				revTimestampMember.resolveAttributeName(),
				listener,
				modifiedEntityNamesMember != null
						? modifiedEntityNamesMember.resolveAttributeName()
						: null
		);
		final var revNumberType = revNumberMember.getType().determineRawClass().toJavaClass();
		serviceRegistry.requireService( TransactionIdentifierService.class )
				.contributeIdentifierSupplier( supplier, revNumberType );

		// Defer validation (basic type, mapped as Hibernate property) and
		// unique constraint to second pass when entity properties are fully bound
		final String entityName = rootClass.getEntityName();
		final String revNumberName = revNumberMember.resolveAttributeName();
		final String revTimestampName = revTimestampMember.resolveAttributeName();
		context.getMetadataCollector().addSecondPass( (OptionalDeterminationSecondPass) ignored ->
				validateRevisionEntity( entityName, revNumberName, revTimestampName, context )
		);
	}

	/**
	 * Check if a member has the given annotation. If found, validate no
	 * duplicate and return the member; otherwise return the existing value.
	 */
	private static MemberDetails checkAnnotation(
			MemberDetails member,
			@Nullable MemberDetails existing,
			Class<? extends Annotation> annotationType,
			ClassDetails classDetails) {
		if ( member.hasDirectAnnotationUsage( annotationType ) ) {
			if ( existing != null ) {
				throw new MappingException(
						"@RevisionEntity '" + classDetails.getName()
						+ "' has multiple members annotated with @"
						+ annotationType.getSimpleName()
				);
			}
			return member;
		}
		return existing;
	}

	/**
	 * Second-pass validation: verify {@code @RevisionNumber} and
	 * {@code @RevisionTimestamp} are mapped as basic properties, and
	 * add a unique constraint on non-ID {@code @RevisionNumber}.
	 */
	private static void validateRevisionEntity(
			String entityName,
			String revNumberName,
			String revTimestampName,
			MetadataBuildingContext context) {
		final var entityBinding = context.getMetadataCollector().getEntityBinding( entityName );
		if ( entityBinding == null ) {
			return;
		}
		final var revNumberProperty = requireBasicProperty( entityBinding, revNumberName, "@RevisionNumber" );
		requireBasicProperty( entityBinding, revTimestampName, "@RevisionTimestamp" );
		// Add unique constraint on non-ID @RevisionNumber
		if ( revNumberProperty != entityBinding.getIdentifierProperty() ) {
			for ( var column : revNumberProperty.getColumns() ) {
				column.setUnique( true );
			}
		}
	}

	/**
	 * Validate that a named property exists and is mapped as a {@link BasicValue}.
	 */
	private static Property requireBasicProperty(
			PersistentClass entityBinding,
			String propertyName,
			String annotationName) {
		final Property property;
		try {
			property = entityBinding.getProperty( propertyName );
		}
		catch (MappingException e) {
			throw new MappingException(
					annotationName + " member '" + propertyName
					+ "' is not mapped as a property on @RevisionEntity '"
					+ entityBinding.getEntityName() + "'"
			);
		}
		if ( !(property.getValue() instanceof BasicValue) ) {
			throw new MappingException(
					annotationName + " property '" + entityBinding.getEntityName()
					+ "." + propertyName + "' must be a basic attribute"
			);
		}
		return property;
	}

	/**
	 * Create an audit table for the given source table: copy columns,
	 * add the REV column, create the composite PK, and add the
	 * REV -> REVINFO FK (if a revision entity is configured).
	 */
	private static Table createAuditTable(
			Table sourceTable,
			String txIdColumnName,
			Set<String> excludedColumns,
			MetadataBuildingContext context) {
		final var collector = context.getMetadataCollector();
		final var auditTable = collector.addTable(
				sourceTable.getSchema(),
				sourceTable.getCatalog(),
				collector.getLogicalTableName( sourceTable ) + DEFAULT_TABLE_SUFFIX,
				sourceTable.getSubselect(),
				sourceTable.isAbstract(),
				context,
				sourceTable.getNameIdentifier().isExplicit()
		);
		copyTableColumns( sourceTable, auditTable, excludedColumns );
		final var revColumn = createAuditColumn( txIdColumnName, getTransactionIdType( context ), auditTable, context );
		auditTable.addColumn( revColumn );
		createAuditPrimaryKey( auditTable, revColumn, sourceTable.getPrimaryKey().getColumns() );
		createRevisionForeignKey( auditTable, revColumn, context );
		return auditTable;
	}

	private static void createAuditPrimaryKey(
			Table auditTable,
			Column transactionIdColumn,
			Iterable<Column> sourceKeyColumns) {
		final var pk = new PrimaryKey( auditTable );
		pk.addColumn( transactionIdColumn );
		for ( var sourceCol : sourceKeyColumns ) {
			pk.addColumn( auditTable.getColumn( sourceCol ) );
		}
		auditTable.setPrimaryKey( pk );
	}

	private static Class<?> getTransactionIdType(MetadataBuildingContext context) {
		return context.getBootstrapContext().getServiceRegistry()
				.requireService( TransactionIdentifierService.class )
				.getIdentifierType();
	}

	private static void copyTableColumns(Table sourceTable, Table targetTable, Set<String> excludedColumns) {
		for ( var column : sourceTable.getColumns() ) {
			if ( !excludedColumns.contains( column.getCanonicalName() ) ) {
				final var copy = column.clone();
				// Audit tables must not inherit unique constraints from the source,
				// since the same value can appear at different revisions
				copy.setUnique( false );
				copy.setUniqueKeyName( null );
				targetTable.addColumn( copy );
			}
		}
	}

	private static Column createAuditColumn(
			String columnName,
			Class<?> javaType,
			Table table,
			MetadataBuildingContext context) {
		final var basicValue = new BasicValue( context, table );
		basicValue.setImplicitJavaTypeAccess( typeConfiguration -> javaType );
		final var column = new Column();
		column.setNullable( false );
		column.setValue( basicValue );
		basicValue.addColumn( column );

		final var database = context.getMetadataCollector().getDatabase();
		setColumnName( columnName, column, database, context.getBuildingOptions().getPhysicalNamingStrategy() );
		setTemporalColumnType( column, database, javaType );

		return column;
	}

	private static void setTemporalColumnType(
			Column column,
			Database database,
			Class<?> javaType) {
		if ( Instant.class.equals( javaType ) ) {
			final var temporalTableSupport = database.getDialect().getTemporalTableSupport();
			column.setTemporalPrecision( temporalTableSupport.getTemporalColumnPrecision() );
			column.setSqlTypeCode( temporalTableSupport.getTemporalColumnType() );
		}
	}

	private static void setColumnName(
			String name,
			Column column,
			Database database,
			PhysicalNamingStrategy physicalNamingStrategy) {
		final Identifier physicalColumnName =
				physicalNamingStrategy.toPhysicalColumnName(
					database.toIdentifier( name ),
					database.getJdbcEnvironment()
				);
		column.setName( physicalColumnName.render( database.getDialect() ) );
	}

	private static boolean isValidityStrategy(MetadataBuildingContext context) {
		final var value = context.getBootstrapContext().getServiceRegistry()
				.requireService( ConfigurationService.class )
				.getSetting( StateManagementSettings.AUDIT_STRATEGY, String.class, "default" );
		return "validity".equalsIgnoreCase( value );
	}

	private static void addTransactionEndColumns(
			Audited audited,
			AuxiliaryTableHolder holder,
			Table auditTable,
			MetadataBuildingContext context) {
		if ( !isValidityStrategy( context ) ) {
			return;
		}
		final var revEndColumn =
				createAuditColumn( audited.transactionEnd(),
						getTransactionIdType( context ), auditTable, context );
		revEndColumn.setNullable( true );
		auditTable.addColumn( revEndColumn );
		holder.addAuxiliaryColumn( TRANSACTION_END, revEndColumn );
		createRevisionForeignKey( auditTable, revEndColumn, context );

		final String revEndTsName = audited.transactionEndTimestamp();
		if ( !isBlank( revEndTsName ) ) {
			final var revEndTsColumn = createAuditColumn( revEndTsName, Instant.class, auditTable, context );
			revEndTsColumn.setNullable( true );
			auditTable.addColumn( revEndTsColumn );
			holder.addAuxiliaryColumn( TRANSACTION_END_TIMESTAMP, revEndTsColumn );
		}
	}

	/**
	 * Create a FK from the audit table's REV (or REVEND) column to the
	 * revision entity's PK. Only applies when {@code @RevisionEntity}
	 * is configured.
	 */
	private static void createRevisionForeignKey(
			Table auditTable,
			Column revColumn,
			MetadataBuildingContext context) {
		final String revisionEntityName = getRevisionEntityName( context );
		if ( revisionEntityName != null ) {
			auditTable.createForeignKey(
					null,
					List.of( revColumn ),
					revisionEntityName,
					null,
					null
			);
		}
	}

	/**
	 * Create a FK from one audit table's PK to another audit table's PK.
	 * Used for JOINED inheritance (child_aud -> parent_aud) and
	 * {@code @SecondaryTable} (secondary_aud -> primary_aud).
	 */
	private static void createAuditTableForeignKey(
			Table sourceAuditTable,
			String rootEntityName,
			Table referencedAuditTable) {
		final var fk = sourceAuditTable.createForeignKey(
				null,
				new ArrayList<>( sourceAuditTable.getPrimaryKey().getColumns() ),
				rootEntityName,
				null,
				null
		);
		fk.setReferencedTable( referencedAuditTable );
	}

	private static @Nullable String getRevisionEntityName(MetadataBuildingContext context) {
		final var supplier = RevisionEntitySupplier.resolve( context.getBootstrapContext().getServiceRegistry() );
		return supplier != null ? supplier.getRevisionEntityClass().getName() : null;
	}

	private static Set<String> resolveExcludedColumns(Iterable<Property> properties) {
		final Set<String> excluded = new HashSet<>();
		for ( var property : properties ) {
			if ( property.isAuditedExcluded() || property instanceof Backref ) {
				for ( var column : property.getColumns() ) {
					excluded.add( column.getCanonicalName() );
				}
			}
		}
		return excluded;
	}

	private static Set<String> resolveExcludedColumns(RootClass rootClass) {
		final Set<String> excluded = new HashSet<>();
		final Set<String> mappedColumns = new HashSet<>();
		// Identifier columns
		for ( var column : rootClass.getIdentifier().getColumns() ) {
			mappedColumns.add( column.getCanonicalName() );
		}
		// Discriminator column
		if ( rootClass.getDiscriminator() != null ) {
			for ( var column : rootClass.getDiscriminator().getColumns() ) {
				mappedColumns.add( column.getCanonicalName() );
			}
		}
		// All properties in the hierarchy (root + subclasses for SINGLE_TABLE)
		collectPropertyColumns( rootClass, mappedColumns, excluded );
		for ( var subclass : rootClass.getSubclasses() ) {
			collectPropertyColumns( subclass, mappedColumns, excluded );
		}
		// Exclude unmapped columns (e.g. FK from unidirectional @OneToMany @JoinColumn)
		for ( var column : rootClass.getMainTable().getColumns() ) {
			if ( !mappedColumns.contains( column.getCanonicalName() ) ) {
				excluded.add( column.getCanonicalName() );
			}
		}
		return excluded;
	}

	private static void collectPropertyColumns(
			PersistentClass persistentClass,
			Set<String> mappedColumns,
			Set<String> excluded) {
		for ( var property : persistentClass.getProperties() ) {
			if ( property.isAuditedExcluded() || property instanceof Backref ) {
				for ( var column : property.getColumns() ) {
					excluded.add( column.getCanonicalName() );
				}
			}
			else {
				for ( var column : property.getColumns() ) {
					mappedColumns.add( column.getCanonicalName() );
				}
			}
		}
	}
}
