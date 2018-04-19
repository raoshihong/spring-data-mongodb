/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.lang.Nullable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoException;
import com.mongodb.TransactionOptions;
import com.mongodb.client.ClientSession;

/**
 * A {@link org.springframework.transaction.PlatformTransactionManager} implementation that manages
 * {@link ClientSession} based transactions for a single {@link MongoDbFactory}.
 * <p />
 * Binds a {@link ClientSession} from the specified {@link MongoDbFactory} to the thread.
 * <p />
 * {@link TransactionDefinition#isReadOnly() Readonly} transactions operate on a {@link ClientSession} and enable causal
 * consistency, and also {@link ClientSession#startTransaction() start}, {@link ClientSession#commitTransaction()
 * commit} or {@link ClientSession#abortTransaction() abort} a transaction.
 *
 * @author Christoph Strobl
 * @currentRead Shadow's Edge - Brent Weeks
 * @since 2.1
 * @see <a href="https://www.mongodb.com/transactions">MongoDB Transaction Documentation</a>
 */
public class MongoTransactionManager extends AbstractPlatformTransactionManager
		implements ResourceTransactionManager, InitializingBean {

	private @Nullable MongoDbFactory dbFactory;
	private @Nullable TransactionOptions options;

	/**
	 * Create a new {@link MongoTransactionManager} for bean-style usage.
	 * <p />
	 * <strong>Note:</strong>The {@link MongoDbFactory db factory} has to be {@link #setDbFactory(MongoDbFactory) set}
	 * before using the instance. Use this constructor to prepare a {@link MongoTransactionManager} via a
	 * {@link org.springframework.beans.factory.BeanFactory}.
	 * <p />
	 * Optionally it is possible to set default {@link TransactionOptions transaction options} defining eg.
	 * {@link com.mongodb.ReadConcern} and {@link com.mongodb.WriteConcern}.
	 * 
	 * @see #setDbFactory(MongoDbFactory)
	 * @see #setTransactionSynchronization(int)
	 */
	public MongoTransactionManager() {}

	/**
	 * Create a new {@link MongoTransactionManager} obtaining sessions from the given {@link MongoDbFactory}.
	 *
	 * @param dbFactory must not be {@literal null}.
	 */
	public MongoTransactionManager(MongoDbFactory dbFactory) {
		this(dbFactory, null);
	}

	/**
	 * Create a new {@link MongoTransactionManager} obtaining sessions from the given {@link MongoDbFactory} applying the
	 * given {@link TransactionOptions options}, if present, when starting a new transaction.
	 *
	 * @param dbFactory must not be {@literal null}.
	 * @param options can be {@literal null}.
	 */
	public MongoTransactionManager(MongoDbFactory dbFactory, @Nullable TransactionOptions options) {

		Assert.notNull(dbFactory, "DbFactory must not be null!");

		this.dbFactory = dbFactory;
		this.options = options;
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doGetTransaction()
	 */
	@Override
	protected Object doGetTransaction() throws TransactionException {

		MongoResourceHolder resourceHolder = (MongoResourceHolder) TransactionSynchronizationManager
				.getResource(getRequiredDbFactory());
		return new MongoTransactionObject(resourceHolder);
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#isExistingTransaction(java.lang.Object)
	 */
	@Override
	protected boolean isExistingTransaction(Object transaction) throws TransactionException {
		return extractMongoTransaction(transaction).hasResourceHolder();
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doBegin(java.lang.Object, org.springframework.transaction.TransactionDefinition)
	 */
	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {

		MongoTransactionObject mongoTransactionObject = extractMongoTransaction(transaction);

		MongoResourceHolder resourceHolder = newResourceHolder(definition,
				ClientSessionOptions.builder().causallyConsistent(true).build());
		mongoTransactionObject.setResourceHolder(resourceHolder);

		if (logger.isDebugEnabled()) {
			logger
					.debug(String.format("About to start transaction for session %s.", debugString(resourceHolder.getSession())));
		}

		try {
			mongoTransactionObject.startTransaction(options);
		} catch (MongoException ex) {
			throw new TransactionSystemException(String.format("Could not start Mongo transaction for session %s.",
					debugString(mongoTransactionObject.getSession())), ex);
		}

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Started transaction for session %s.", debugString(resourceHolder.getSession())));
		}

		resourceHolder.setSynchronizedWithTransaction(true);
		TransactionSynchronizationManager.bindResource(dbFactory, resourceHolder);
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doSuspend(java.lang.Object)
	 */
	@Override
	protected Object doSuspend(Object transaction) throws TransactionException {

		MongoTransactionObject mongoTransactionObject = extractMongoTransaction(transaction);
		mongoTransactionObject.setResourceHolder(null);

		return TransactionSynchronizationManager.unbindResource(getRequiredDbFactory());
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doResume(java.lang.Object, java.lang.Object)
	 */
	@Override
	protected void doResume(@Nullable Object transaction, Object suspendedResources) {
		TransactionSynchronizationManager.bindResource(getRequiredDbFactory(), suspendedResources);
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doCommit(org.springframework.transaction.support.DefaultTransactionStatus)
	 */
	@Override
	protected void doCommit(DefaultTransactionStatus status) throws TransactionException {

		MongoTransactionObject mongoTransactionObject = extractMongoTransaction(status);

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("About to commit transaction for session %s.",
					debugString(mongoTransactionObject.getSession())));
		}

		try {
			mongoTransactionObject.commitTransaction();
		} catch (MongoException ex) {

			throw new TransactionSystemException(String.format("Could not commit Mongo transaction for session %s.",
					debugString(mongoTransactionObject.getSession())), ex);
		}
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doRollback(org.springframework.transaction.support.DefaultTransactionStatus)
	 */
	@Override
	protected void doRollback(DefaultTransactionStatus status) throws TransactionException {

		MongoTransactionObject mongoTransactionObject = extractMongoTransaction(status);

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("About to abort transaction for session %s.",
					debugString(mongoTransactionObject.getSession())));
		}

		try {
			mongoTransactionObject.abortTransaction();
		} catch (MongoException ex) {

			throw new TransactionSystemException(String.format("Could not abort Mongo transaction for session %s.",
					debugString(mongoTransactionObject.getSession())), ex);
		}
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doSetRollbackOnly(org.springframework.transaction.support.DefaultTransactionStatus)
	 */
	@Override
	protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {

		MongoTransactionObject transactionObject = extractMongoTransaction(status);
		transactionObject.getRequiredResourceHolder().setRollbackOnly();
	}

	/*
	 * (non-Javadoc)
	 * org.springframework.transaction.support.AbstractPlatformTransactionManager#doCleanupAfterCompletion(java.lang.Object)
	 */
	@Override
	protected void doCleanupAfterCompletion(Object transaction) {

		Assert.isInstanceOf(MongoTransactionObject.class, transaction,
				() -> String.format("Expected to find a %s but it turned out to be %s.", MongoTransactionObject.class,
						transaction.getClass()));

		MongoTransactionObject mongoTransactionObject = (MongoTransactionObject) transaction;

		// Remove the connection holder from the thread.
		TransactionSynchronizationManager.unbindResource(getRequiredDbFactory());
		mongoTransactionObject.getRequiredResourceHolder().clear();

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("About to release Session %s after transaction.",
					debugString(mongoTransactionObject.getSession())));
		}

		mongoTransactionObject.closeSession();
	}

	/**
	 * Set the {@link MongoDbFactory} that this instance should manage transactions for.
	 *
	 * @param dbFactory must not be {@literal null}.
	 */
	public void setDbFactory(MongoDbFactory dbFactory) {

		Assert.notNull(dbFactory, "DbFactory must not be null!");
		this.dbFactory = dbFactory;
	}

	/**
	 * Set the {@link TransactionOptions} to be applied when starting transactions.
	 *
	 * @param options can be {@literal null}.
	 */
	public void setOptions(@Nullable TransactionOptions options) {
		this.options = options;
	}

	/**
	 * Get the {@link MongoDbFactory} that this instance manages transactions for.
	 *
	 * @return can be {@literal null}.
	 */
	@Nullable
	public MongoDbFactory getDbFactory() {
		return dbFactory;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.transaction.support.ResourceTransactionManager#getResourceFactory()
	 */
	@Override
	public MongoDbFactory getResourceFactory() {
		return getRequiredDbFactory();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
		getRequiredDbFactory();
	}

	private MongoResourceHolder newResourceHolder(TransactionDefinition definition, ClientSessionOptions options) {

		MongoDbFactory dbFactory = getResourceFactory();

		MongoResourceHolder resourceHolder = new MongoResourceHolder(dbFactory.getSession(options), dbFactory);
		resourceHolder.setTimeoutIfNotDefaulted(determineTimeout(definition));

		return resourceHolder;
	}

	/**
	 * @throws IllegalStateException if {@link #dbFactory} is {@literal null}.
	 */
	private MongoDbFactory getRequiredDbFactory() {

		Assert.state(dbFactory != null,
				"MongoTransactionManager operates upon a MongoDbFactory. Did you forget to provide one? It's required.");

		return dbFactory;
	}

	private static MongoTransactionObject extractMongoTransaction(Object transaction) {

		Assert.isInstanceOf(MongoTransactionObject.class, transaction,
				() -> String.format("Expected to find a %s but it turned out to be %s.", MongoTransactionObject.class,
						transaction.getClass()));

		return (MongoTransactionObject) transaction;
	}

	private static MongoTransactionObject extractMongoTransaction(DefaultTransactionStatus status) {

		Assert.isInstanceOf(MongoTransactionObject.class, status.getTransaction(),
				() -> String.format("Expected to find a %s but it turned out to be %s.", MongoTransactionObject.class,
						status.getTransaction().getClass()));

		return (MongoTransactionObject) status.getTransaction();
	}

	private String debugString(@Nullable ClientSession session) {

		if (session == null) {
			return "null";
		}

		String debugString = "[" + ClassUtils.getShortName(session.getClass()) + "@" + Integer.toHexString(hashCode())
				+ " ";

		try {
			if (session.getServerSession() != null) {
				debugString += "id = " + session.getServerSession().getIdentifier() + ", ";
				debugString += "causallyConsistent = " + session.isCausallyConsistent() + ", ";
				debugString += "txActive = " + session.hasActiveTransaction() + ", ";
				debugString += "txNumber = " + session.getServerSession().getTransactionNumber() + ", ";
				debugString += "statementId = " + session.getServerSession().getStatementId() + ", ";
				debugString += "clusterTime = " + session.getClusterTime();
			} else {
				debugString += "id = n/a";
				debugString += "causallyConsistent = " + session.isCausallyConsistent() + ", ";
				debugString += "txActive = " + session.hasActiveTransaction() + ", ";
				debugString += "clusterTime = " + session.getClusterTime();
			}
		} catch (RuntimeException e) {
			debugString += "error = " + e.getMessage();
		}

		debugString += "}";

		return debugString;
	}

	/**
	 * MongoDB specific transaction object, representing a {@link MongoResourceHolder}. Used as transaction object by
	 * {@link MongoTransactionManager}.
	 *
	 * @author Christoph Strobl
	 * @since 2.1
	 * @see MongoResourceHolder
	 */
	static class MongoTransactionObject implements SmartTransactionObject {

		private @Nullable MongoResourceHolder resourceHolder;

		MongoTransactionObject(@Nullable MongoResourceHolder resourceHolder) {
			this.resourceHolder = resourceHolder;
		}

		void setResourceHolder(@Nullable MongoResourceHolder resourceHolder) {
			this.resourceHolder = resourceHolder;
		}

		boolean hasResourceHolder() {
			return resourceHolder != null;
		}

		void commitTransaction() {
			getRequiredSession().commitTransaction();
		}

		void abortTransaction() {
			getRequiredSession().abortTransaction();
		}

		void startTransaction(@Nullable TransactionOptions options) {

			ClientSession session = getRequiredSession();
			if (options != null) {
				session.startTransaction(options);
			} else {
				session.startTransaction();
			}
		}

		void closeSession() {

			ClientSession session = getRequiredSession();
			if (session.getServerSession() != null && !session.getServerSession().isClosed()) {
				session.close();
			}
		}

		@Nullable
		ClientSession getSession() {
			return resourceHolder != null ? resourceHolder.getSession() : null;
		}

		private MongoResourceHolder getRequiredResourceHolder() {

			Assert.state(resourceHolder != null, "MongoResourceHolder is required but not present. o_O");
			return resourceHolder;
		}

		private ClientSession getRequiredSession() {

			ClientSession session = getSession();
			Assert.state(session != null, "A Session is required but it turned out to be null.");
			return session;
		}

		/*
		 * (non-Javadoc)
		 * @see  org.springframework.transaction.support.SmartTransactionObject#isRollbackOnly()
		 */
		@Override
		public boolean isRollbackOnly() {
			return this.resourceHolder != null && this.resourceHolder.isRollbackOnly();
		}

		/*
		 * (non-Javadoc)
		 * @see  org.springframework.transaction.support.SmartTransactionObject#flush()
		 */
		@Override
		public void flush() {
			TransactionSynchronizationUtils.triggerFlush();
		}

	}
}