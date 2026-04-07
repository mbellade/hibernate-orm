/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.boot.model.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.annotations.Audited;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.mapping.AuxiliaryTableHolder;
import org.hibernate.mapping.Backref;
import org.hibernate.mapping.BasicValue;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
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
	public static final String REVISION_END = "revisionEnd";
	public static final String REVISION_END_TIMESTAMP = "revisionEndTimestamp";

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
			addRevisionEndColumns( audited, auditable, auditTable, context );
		} );
	}

	private static void bindSecondaryAuditTables(
			Audited audited,
			RootClass rootClass,
			MetadataBuildingContext context) {
		final var collector = context.getMetadataCollector();
		final String txIdColumnName = audited.transactionId();
		collector.addSecondPass( (OptionalDeterminationSecondPass) ignored -> {
			for ( var join : rootClass.getJoins() ) {
				final var joinTable = join.getTable();
				final var auditTable = collector.addTable(
						joinTable.getSchema(),
						joinTable.getCatalog(),
						collector.getLogicalTableName( joinTable )
								+ DEFAULT_TABLE_SUFFIX,
						joinTable.getSubselect(),
						joinTable.isAbstract(),
						context,
						joinTable.getNameIdentifier().isExplicit()
				);
				copyTableColumns( joinTable, auditTable, Set.of() );
				final var transactionIdColumn =
						createAuditColumn( txIdColumnName,
								getTransactionIdType( context ), auditTable, context );
				auditTable.addColumn( transactionIdColumn );
				createAuditPrimaryKey( auditTable, transactionIdColumn, joinTable.getPrimaryKey().getColumns() );
				// Secondary tables only get tx-id (no mod type, no REVEND)
				join.setAuxiliaryTable( auditTable );
				join.addAuxiliaryColumn( TRANSACTION_ID, transactionIdColumn );
			}
		} );
	}

	private static void bindSubclassAuditTables(
			Audited audited,
			RootClass rootClass,
			MetadataBuildingContext context) {
		final var collector = context.getMetadataCollector();
		final String txIdColumnName = audited.transactionId();
		final String modTypeColumnName = audited.modificationType();
		// Defer to second pass — subclasses haven't been added to rootClass yet
		collector.addSecondPass( (OptionalDeterminationSecondPass) ignored -> {
			for ( var subclass : rootClass.getSubclasses() ) {
				if ( subclass instanceof TableOwner ) {
					final var subTable = subclass.getTable();
					final var subAuditTable = collector.addTable(
							subTable.getSchema(),
							subTable.getCatalog(),
							collector.getLogicalTableName( subTable )
									+ DEFAULT_TABLE_SUFFIX,
							subTable.getSubselect(),
							subTable.isAbstract(),
							context,
							subTable.getNameIdentifier().isExplicit()
					);
					copyTableColumns( subTable, subAuditTable, Set.of() );
					final var transactionIdColumn =
							createAuditColumn( txIdColumnName,
									getTransactionIdType( context ), subAuditTable, context );
					subAuditTable.addColumn( transactionIdColumn );
					createAuditPrimaryKey( subAuditTable, transactionIdColumn, subTable.getPrimaryKey().getColumns() );
					subclass.addAuxiliaryColumn( TRANSACTION_ID, transactionIdColumn );
					// TABLE_PER_CLASS: each table is self-contained, needs REVTYPE
					// JOINED: REVTYPE only on root table (matches envers behavior)
					if ( subclass instanceof UnionSubclass ) {
						final var modificationTypeColumn =
								createAuditColumn( modTypeColumnName,
										Byte.class, subAuditTable, context );
						subAuditTable.addColumn( modificationTypeColumn );
						subclass.addAuxiliaryColumn( MODIFICATION_TYPE, modificationTypeColumn );
					}
					subclass.setAuxiliaryTable( subAuditTable );
					// TABLE_PER_CLASS: each table is self-contained, needs REVEND
					// JOINED: REVEND only on root table (matches envers behavior)
					if ( subclass instanceof UnionSubclass ) {
						addRevisionEndColumns( audited, subclass, subAuditTable, context );
					}
				}
			}
		} );
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
	 * — mirroring the envers {@code @AuditJoinTable} approach.
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
			enableAudit( collection, auditTable, transactionIdColumn, modificationTypeColumn );
			addRevisionEndColumns( audited, collection, auditTable, context );
		} );
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

	static boolean isValidityStrategy(MetadataBuildingContext context) {
		final var value = context.getBootstrapContext().getServiceRegistry()
				.requireService( org.hibernate.engine.config.spi.ConfigurationService.class )
				.getSetting( StateManagementSettings.AUDIT_STRATEGY, String.class, "default" );
		return "validity".equalsIgnoreCase( value );
	}

	private static void addRevisionEndColumns(
			Audited audited,
			AuxiliaryTableHolder holder,
			Table auditTable,
			MetadataBuildingContext context) {
		if ( !isValidityStrategy( context ) ) {
			return;
		}
		final var revEndColumn =
				createAuditColumn( audited.revisionEnd(),
						getTransactionIdType( context ), auditTable, context );
		revEndColumn.setNullable( true );
		auditTable.addColumn( revEndColumn );
		holder.addAuxiliaryColumn( REVISION_END, revEndColumn );

		final String revEndTsName = audited.revisionEndTimestamp();
		if ( !isBlank( revEndTsName ) ) {
			final var revEndTsColumn =
					createAuditColumn( revEndTsName,
							Instant.class, auditTable, context );
			revEndTsColumn.setNullable( true );
			auditTable.addColumn( revEndTsColumn );
			holder.addAuxiliaryColumn( REVISION_END_TIMESTAMP, revEndTsColumn );
		}
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
