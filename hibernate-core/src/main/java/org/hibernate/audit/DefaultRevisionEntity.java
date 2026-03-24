/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.Incubating;

/**
 * A built-in revision entity that matches the schema of
 * {@code org.hibernate.envers.DefaultRevisionEntity}, providing
 * a seamless migration path for envers users.
 * <p>
 * Maps to the {@code REVINFO} table with columns:
 * <ul>
 *   <li>{@code REV} — auto-generated integer primary key</li>
 *   <li>{@code REVTSTMP} — Unix epoch timestamp in milliseconds</li>
 * </ul>
 * <p>
 * To use this entity, add it to the domain model and configure
 * {@link DefaultRevisionEntitySupplier} as the transaction ID
 * supplier:
 * <pre>
 * hibernate.temporal.transaction_id_supplier=org.hibernate.audit.DefaultRevisionEntitySupplier
 * </pre>
 *
 * @see DefaultRevisionEntitySupplier
 *
 * @author Marco Belladelli
 *
 * @since envers-rewrite
 */
@Incubating
@Entity(name = "DefaultRevisionEntity")
@Table(name = "REVINFO")
public class DefaultRevisionEntity implements Serializable {
	@Id
	@GeneratedValue
	@Column(name = "REV")
	private int id;

	@Column(name = "REVTSTMP")
	private long timestamp;

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	@Transient
	public Date getRevisionDate() {
		return new Date( timestamp );
	}

	public long getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	@Override
	public boolean equals(Object o) {
		if ( this == o ) {
			return true;
		}
		if ( !(o instanceof DefaultRevisionEntity that) ) {
			return false;
		}
		return id == that.id
				&& timestamp == that.timestamp;
	}

	@Override
	public int hashCode() {
		int result = id;
		result = 31 * result + Long.hashCode( timestamp );
		return result;
	}

	@Override
	public String toString() {
		return "DefaultRevisionEntity(id = " + id
				+ ", revisionDate = " + getRevisionDate() + ")";
	}
}
