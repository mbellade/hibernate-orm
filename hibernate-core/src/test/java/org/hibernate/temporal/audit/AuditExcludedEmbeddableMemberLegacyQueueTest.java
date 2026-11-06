/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hibernate.temporal.audit;

import org.hibernate.cfg.FlushSettings;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.Setting;

@ServiceRegistry(settings = {
		@Setting(name = FlushSettings.FLUSH_QUEUE_TYPE, value = "legacy"),
		@Setting(
				name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
				value = "org.hibernate.temporal.audit.AuditExcludedEmbeddableMemberTest$TxIdSupplier"
		)
})
class AuditExcludedEmbeddableMemberLegacyQueueTest extends AuditExcludedEmbeddableMemberTest {
}
