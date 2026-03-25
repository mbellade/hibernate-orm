/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.spi;

import org.hibernate.Incubating;
import org.hibernate.audit.RevisionEntity;
import org.hibernate.audit.RevisionEntitySupplier;
import org.hibernate.audit.RevisionListener;
import org.hibernate.audit.RevisionNumber;
import org.hibernate.audit.RevisionTimestamp;
import org.hibernate.models.spi.ClassDetails;
import org.hibernate.models.spi.MemberDetails;

/**
 * Metadata about a detected {@link RevisionEntity @RevisionEntity},
 * collected during entity binding and used to auto-configure a
 * {@link RevisionEntitySupplier}.
 *
 * @param entityClassDetails the revision entity class
 * @param revisionNumberMember the {@link RevisionNumber @RevisionNumber} field
 * @param revisionTimestampMember the {@link RevisionTimestamp @RevisionTimestamp} field
 * @param listenerClass the optional {@link RevisionListener} class
 *
 * @author Marco Belladelli
 *
 * @since 7.0
 */
@Incubating
public record RevisionEntityDescriptor(
		ClassDetails entityClassDetails,
		MemberDetails revisionNumberMember,
		MemberDetails revisionTimestampMember,
		Class<? extends RevisionListener> listenerClass) {
}
