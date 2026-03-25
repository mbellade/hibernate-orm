/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.internal;

import java.time.Instant;

import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.boot.registry.selector.spi.StrategySelector;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.temporal.spi.TransactionIdentifierService;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;

import static org.hibernate.cfg.StateManagementSettings.TRANSACTION_ID_SUPPLIER;
import static org.hibernate.cfg.StateManagementSettings.USE_SERVER_TRANSACTION_TIMESTAMPS;
import static org.hibernate.internal.util.config.ConfigurationHelper.getBoolean;

/**
 * Default implementation of {@link TransactionIdentifierService}.
 * <p>
 * Produces current timestamps by calling {@link Instant#now()}.
 *
 * @see StateManagementSettings#TRANSACTION_ID_SUPPLIER
 * @see StateManagementSettings#USE_SERVER_TRANSACTION_TIMESTAMPS
 *
 * @author Gavin King
 *
 * @since 7.4
 */
public class TransactionIdentifierServiceImpl implements TransactionIdentifierService {

	private final boolean useServerTransactionTimestamps;
	private TransactionIdentifierSupplier<?> identifierSupplier;

	public TransactionIdentifierServiceImpl(ServiceRegistry serviceRegistry) {
		final var settings =
				serviceRegistry.requireService( ConfigurationService.class )
						.getSettings();
		useServerTransactionTimestamps =
				getBoolean( USE_SERVER_TRANSACTION_TIMESTAMPS, settings );
		if ( useServerTransactionTimestamps ) {
			if ( settings.containsKey( TRANSACTION_ID_SUPPLIER ) ) {
				throw new MappingException( "Settings '"
						+ USE_SERVER_TRANSACTION_TIMESTAMPS + "' and '"
						+ TRANSACTION_ID_SUPPLIER + "' are mutually exclusive"
				);
			}
			identifierSupplier = null;
		}
		else {
			identifierSupplier =
					resolveSupplier( settings,
							serviceRegistry.requireService( StrategySelector.class ) );
		}
	}

	@Override
	public void contributeIdentifierSupplier(TransactionIdentifierSupplier<?> supplier) {
		this.identifierSupplier = supplier;
	}

	@Override
	public boolean isIdentifierTypeInstant() {
		return getIdentifierType() == Instant.class;
	}

	@Override
	public Class<?> getIdentifierType() {
		return useServerTransactionTimestamps
				? Instant.class
				: identifierSupplier.getIdentifierType();
	}

	@Override
	public TransactionIdentifierSupplier<?> getIdentifierSupplier() {
		return identifierSupplier;
	}

	@Override
	public boolean isDisabled() {
		return useServerTransactionTimestamps;
	}

	private static TransactionIdentifierSupplier<?> resolveSupplier(
			java.util.Map<String,Object> settings,
			StrategySelector strategySelector) {
		final Object setting = settings.get( TRANSACTION_ID_SUPPLIER );
		if ( setting == null ) {
			return DEFAULT_SUPPLIER;
		}
		else if ( setting instanceof TransactionIdentifierSupplier<?> supplier ) {
			return supplier;
		}
		else if ( setting instanceof Class<?> clazz ) {
			if ( TransactionIdentifierSupplier.class.isAssignableFrom( clazz ) ) {
				return strategySelector.resolveStrategy( TransactionIdentifierSupplier.class, clazz );
			}
			throw new HibernateException(
					"Setting '" + TRANSACTION_ID_SUPPLIER + "' must specify a '"
							+ TransactionIdentifierSupplier.class.getName()
							+ "' implementation"
			);
		}
		else if ( setting instanceof String name ) {
			return strategySelector.resolveStrategy( TransactionIdentifierSupplier.class, name );
		}
		else {
			throw new HibernateException(
					"Setting '" + TRANSACTION_ID_SUPPLIER + "' must specify a '"
							+ TransactionIdentifierSupplier.class.getName()
							+ "' instance, class, or class name"
			);
		}
	}

	private static final TransactionIdentifierSupplier<Instant> DEFAULT_SUPPLIER =
			new TransactionIdentifierSupplier<>() {
				@Override
				public Instant getTransactionIdentifier(SharedSessionContractImplementor session) {
					return Instant.now();
				}

				@Override
				public Class<Instant> getIdentifierType() {
					return Instant.class;
				}
			};
}
