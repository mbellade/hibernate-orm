package org.hibernate.sql.results.graph.entity;

import org.hibernate.engine.spi.FetchOptions;
import org.hibernate.graph.spi.GraphHelper;
import org.hibernate.graph.spi.GraphImplementor;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.model.domain.JpaMetamodel;
import org.hibernate.sql.results.graph.DomainResultGraphNode;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.metamodel.mapping.EntityValuedModelPart;
import org.hibernate.spi.NavigablePath;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * Represents a reference to an entity either as a return, fetch, or collection element or index.
 *
 * @author Steve Ebersole
 */
@org.hibernate.SPI({ org.hibernate.SPI.Role.USE, org.hibernate.SPI.Role.IMPLEMENT })
public interface EntityResultGraphNode extends DomainResultGraphNode, FetchParent {
	@Override
	NavigablePath getNavigablePath();

	EntityValuedModelPart getEntityValuedModelPart();

	default FetchOptions getFetchOptions() {
		return FetchOptions.NONE;
	}

	@Override
	default boolean containsAnyNonScalarResults() {
		return true;
	}

	@Override
	default JavaType<?> getResultJavaType() {
		return getEntityValuedModelPart().getEntityMappingType().getMappedJavaType();
	}

	@Override
	default EntityMappingType getReferencedMappingContainer() {
		return getEntityValuedModelPart().getEntityMappingType();
	}

	/**
	 * Whether this graph was built for an audit read instead of a current-state load.
	 * <p>
	 * This is session-derived state on a cacheable graph. It is sound only because
	 * audit and current-state plans are never shared: loaders bypass plan caching
	 * when {@code temporalIdentifier != null}, and the SQM plan cache keys on the
	 * temporal context. Any future audit-plan caching must preserve this partition.
	 */
	default boolean isAuditRead() {
		return false;
	}

	@Override
	default boolean appliesTo(GraphImplementor<?> graphImplementor, JpaMetamodel metamodel) {
		final String entityName = getEntityValuedModelPart().getEntityMappingType().getEntityName();
		return GraphHelper.appliesTo( graphImplementor, metamodel.entity( entityName ) );
	}
}
