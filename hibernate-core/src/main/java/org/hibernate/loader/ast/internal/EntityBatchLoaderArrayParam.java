/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.loader.ast.internal;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import org.hibernate.LockOptions;
import org.hibernate.engine.internal.BatchFetchQueueHelper;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.loader.ast.spi.EntityBatchLoader;
import org.hibernate.loader.ast.spi.SqlArrayMultiKeyLoader;
import org.hibernate.metamodel.mapping.BasicEntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.exec.internal.JdbcParameterImpl;
import org.hibernate.sql.exec.spi.JdbcOperationQuerySelect;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.type.BasicType;
import org.hibernate.type.BasicTypeRegistry;

import static org.hibernate.loader.ast.internal.LoaderHelper.createTypedArray;
import static org.hibernate.loader.ast.internal.MultiKeyLoadLogging.MULTI_KEY_LOAD_DEBUG_ENABLED;
import static org.hibernate.loader.ast.internal.MultiKeyLoadLogging.MULTI_KEY_LOAD_LOGGER;

/**
 * SingleIdEntityLoaderSupport implementation based on using a single
 * {@linkplain org.hibernate.type.SqlTypes#ARRAY array} parameter to pass the
 * entire batch of ids.
 *
 * @author Steve Ebersole
 */
public class EntityBatchLoaderArrayParam<T>
		extends SingleIdEntityLoaderSupport<T>
		implements EntityBatchLoader<T>, SqlArrayMultiKeyLoader, Preparable {
	private final int domainBatchSize;

	private BasicEntityIdentifierMapping identifierMapping;
	private JdbcMapping arrayJdbcMapping;
	private JdbcParameter jdbcParameter;
	private SelectStatement sqlAst;
	private JdbcOperationQuerySelect jdbcSelectOperation;
	private SingleIdEntityLoaderStandardImpl<T> singleIdLoader;


	/**
	 * Instantiates the loader
	 *
	 * @param domainBatchSize The number of domain model parts (up to)
	 *
	 * @implNote We delay initializing the internal SQL AST state until first use.  Creating
	 * the SQL AST internally relies on the entity's {@link EntityIdentifierMapping}. However, we
	 * do create the static batch-loader for the entity in the persister constructor and
	 * {@link EntityIdentifierMapping} is not available at that time.  On first use, we know we
	 * have it available
	 */
	public EntityBatchLoaderArrayParam(
			int domainBatchSize,
			EntityMappingType entityDescriptor,
			SessionFactoryImplementor sessionFactory) {
		super( entityDescriptor, sessionFactory );
		this.domainBatchSize = domainBatchSize;

		if ( MULTI_KEY_LOAD_DEBUG_ENABLED ) {
			MULTI_KEY_LOAD_LOGGER.debugf(
					"Batch fetching enabled for `%s` (entity) using ARRAY strategy : %s",
					entityDescriptor.getEntityName(),
					domainBatchSize
			);
		}
	}

	@Override
	public int getDomainBatchSize() {
		return domainBatchSize;
	}

	@Override
	public final T load(
			Object pkValue,
			Object entityInstance,
			LockOptions lockOptions,
			Boolean readOnly,
			SharedSessionContractImplementor session) {
		if ( MULTI_KEY_LOAD_DEBUG_ENABLED ) {
			MULTI_KEY_LOAD_LOGGER.debugf( "Batch fetching entity `%s#%s`", getLoadable().getEntityName(), pkValue );
		}

		final Object[] ids = resolveIdsToInitialize( pkValue, session );
		initializeEntities( ids, pkValue, entityInstance, lockOptions, readOnly, session );

		final EntityKey entityKey = session.generateEntityKey( pkValue, getLoadable().getEntityPersister() );
		//noinspection unchecked
		return (T) session.getPersistenceContext().getEntity( entityKey );
	}

	protected Object[] resolveIdsToInitialize(Object pkValue, SharedSessionContractImplementor session) {
		final Class<?> identifierType = identifierMapping.getJavaType().getJavaTypeClass();
		final Object[] idsToLoad = (Object[]) Array.newInstance( identifierType, domainBatchSize );
		session.getPersistenceContextInternal().getBatchFetchQueue().collectBatchLoadableEntityIds(
				domainBatchSize,
				(index, value) -> idsToLoad[index] = value,
				pkValue,
				getLoadable()
		);
		return Arrays.stream( idsToLoad ).filter( Objects::nonNull ).toArray( this::createIdentifierTypedArray );
	}

	private Object[] createIdentifierTypedArray(int length) {
		return createTypedArray( identifierMapping.getJavaType().getJavaTypeClass(), length );
	}

	private void initializeEntities(
			Object[] idsToInitialize,
			Object pkValue,
			Object entityInstance,
			LockOptions lockOptions,
			Boolean readOnly,
			SharedSessionContractImplementor session) {
		if ( idsToInitialize.length == 1 ) {
			initializeSingleIdLoaderIfNeeded();
			singleIdLoader.load( pkValue, entityInstance, lockOptions, readOnly, session );
		}
		else {
			initializeArrayLoaderIfNeeded();
			LoaderHelper.loadByArrayParameter(
					idsToInitialize,
					sqlAst,
					jdbcSelectOperation,
					jdbcParameter,
					arrayJdbcMapping,
					pkValue,
					entityInstance,
					getLoadable().getRootEntityDescriptor(),
					lockOptions,
					readOnly,
					session
			);
		}

		for ( Object id : idsToInitialize ) {
			// found or not, remove the key from the batch-fetch queue
			BatchFetchQueueHelper.removeBatchLoadableEntityKey( id, getLoadable(), session );
		}
	}

	@Override
	public T load(Object pkValue, LockOptions lockOptions, Boolean readOnly, SharedSessionContractImplementor session) {
		return load( pkValue, null, lockOptions, readOnly, session );
	}

	@Override
	public void prepare() {
		identifierMapping = (BasicEntityIdentifierMapping) getLoadable().getIdentifierMapping();
	}

	private void initializeArrayLoaderIfNeeded() {
		if ( jdbcSelectOperation != null ) {
			return;
		}

		final Class<?> arrayClass = Array.newInstance( identifierMapping.getJavaType().getJavaTypeClass(), 0 ).getClass();

		final BasicTypeRegistry basicTypeRegistry = sessionFactory.getTypeConfiguration().getBasicTypeRegistry();
		final BasicType<?> arrayBasicType = basicTypeRegistry.getRegisteredType( arrayClass );

		arrayJdbcMapping = MultiKeyLoadHelper.resolveArrayJdbcMapping(
				arrayBasicType,
				identifierMapping.getJdbcMapping(),
				arrayClass,
				sessionFactory
		);

		jdbcParameter = new JdbcParameterImpl( arrayJdbcMapping );
		sqlAst = LoaderSelectBuilder.createSelectBySingleArrayParameter(
				getLoadable(),
				identifierMapping,
				LoadQueryInfluencers.NONE,
				LockOptions.NONE,
				jdbcParameter,
				sessionFactory
		);

		jdbcSelectOperation = sessionFactory.getJdbcServices()
				.getJdbcEnvironment()
				.getSqlAstTranslatorFactory()
				.buildSelectTranslator( sessionFactory, sqlAst )
				.translate( JdbcParameterBindings.NO_BINDINGS, QueryOptions.NONE );
	}

	private void initializeSingleIdLoaderIfNeeded() {
		if ( singleIdLoader != null ) {
			return;
		}

		singleIdLoader = new SingleIdEntityLoaderStandardImpl<>( getLoadable(), sessionFactory );
		singleIdLoader.prepare();
	}

	@Override
	public String toString() {
		return String.format(
				Locale.ROOT,
				"EntityBatchLoaderArrayParam(%s [%s])",
				getLoadable().getEntityName(),
				domainBatchSize
		);
	}
}
