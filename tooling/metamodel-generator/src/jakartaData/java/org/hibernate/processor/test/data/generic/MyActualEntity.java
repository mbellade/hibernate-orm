/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.processor.test.data.generic;

import jakarta.persistence.Entity;

@Entity
public class MyActualEntity extends MyMappedSuperclass<Integer> {
	public String myString;
}
