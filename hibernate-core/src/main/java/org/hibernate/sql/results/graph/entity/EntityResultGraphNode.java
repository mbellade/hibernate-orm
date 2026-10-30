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
	 * Whether this domain-result graph was produced for an audit/historical
	 * read, rather than a regular load of current state.
	 * <p>
	 * This is a session-derived fact stored on a nominally session-independent
	 * graph node, which is only sound because plans built under an audit context
	 * are never cached and reused for ordinary loads (and vice versa): the loader
	 * refuses to cache load plans in audit context, and the SQM plan cache is
	 * keyed on the temporal identifier. Any new plan/result-graph caching site
	 * MUST uphold this partition.
	 * <p>
	 * The flag exists to carry audit-context information to assembler creation,
	 * which has no other route to session state. It is expected to be removed
	 * once audit collection writes resolve the actual concrete owner: at that
	 * point per-concrete-type masking during assembly is only an optimization
	 * and can be performed at row-processing time, where the session is
	 * directly available.
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
