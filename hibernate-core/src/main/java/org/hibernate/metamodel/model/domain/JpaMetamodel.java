/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.metamodel.model.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;

import jakarta.persistence.metamodel.Metamodel;
import org.hibernate.Incubating;
import org.hibernate.graph.spi.RootGraphImplementor;
import org.hibernate.jpa.spi.JpaCompliance;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Extensions to the JPA-defined {@linkplain Metamodel metamodel} of
 * persistent Java types.
 *
 * @see MappingMetamodel
 *
 * @since 6.0
 * @author Steve Ebersole
 */
@Incubating
public interface JpaMetamodel extends Metamodel {

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Context

	/**
	 * todo (6.0) : should we expose JpaMetamodel from TypeConfiguration?
	 */
	TypeConfiguration getTypeConfiguration();

	ServiceRegistry getServiceRegistry();


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Extended features

	/**
	 * Access to an entity supporting Hibernate's entity-name feature
	 */
	EntityDomainType<?> entity(String entityName);

	/**
	 * Specialized handling for resolving entity-name references in
	 * an HQL query
	 */
	<X> EntityDomainType<X> getHqlEntityReference(String entityName);

	/**
	 * Specialized handling for resolving entity-name references in
	 * an HQL query
	 */
	<X> EntityDomainType<X> resolveHqlEntityReference(String entityName);

	/**
	 * Same as {@link #managedType} except {@code null} is returned rather
	 * than throwing an exception
	 */
	<X> ManagedDomainType<X> findManagedType(Class<X> cls);

	/**
	 * Same as {@link #entity} except {@code null} is returned rather
	 * than throwing an exception
	 */
	<X> EntityDomainType<X> findEntityType(Class<X> cls);

	String qualifyImportableName(String queryName);

	/**
	 * Returns a map that gives access to the enum literal expressions that can be used in queries.
	 * The key is the shorthand enum literal. The value is a map, from enum class to the actual enum value.
	 * This is needed for parsing shorthand enum literals that don't use FQNs.
	 */
	Map<String, Map<Class<?>, Enum<?>>> getAllowedEnumLiteralTexts();

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Covariant returns

	@Override
	<X> ManagedDomainType<X> managedType(Class<X> cls);

	@Override
	<X> EntityDomainType<X> entity(Class<X> cls);

	@Override
	<X> EmbeddableDomainType<X> embeddable(Class<X> cls);


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// JPA defined bulk accessors

	@Override
	Set<ManagedType<?>> getManagedTypes();

	@Override
	Set<EntityType<?>> getEntities();

	@Override
	Set<EmbeddableType<?>> getEmbeddables();

	<T> void addNamedEntityGraph(String graphName, RootGraphImplementor<T> entityGraph);

	<T> RootGraphImplementor<T> findEntityGraphByName(String name);

	<T> List<RootGraphImplementor<? super T>> findEntityGraphsByJavaType(Class<T> entityClass);

	JpaCompliance getJpaCompliance();
}
