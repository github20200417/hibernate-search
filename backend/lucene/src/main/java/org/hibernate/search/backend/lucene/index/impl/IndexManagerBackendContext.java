/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.index.impl;

import java.util.Optional;

import org.hibernate.search.backend.lucene.LuceneBackend;
import org.hibernate.search.backend.lucene.analysis.model.impl.LuceneAnalysisDefinitionRegistry;
import org.hibernate.search.backend.lucene.cfg.LuceneIndexSettings;
import org.hibernate.search.backend.lucene.document.impl.LuceneIndexEntryFactory;
import org.hibernate.search.backend.lucene.document.model.impl.LuceneIndexModel;
import org.hibernate.search.backend.lucene.lowlevel.directory.spi.DirectoryProvider;
import org.hibernate.search.backend.lucene.lowlevel.index.IOStrategyName;
import org.hibernate.search.backend.lucene.lowlevel.index.impl.DebugIOStrategy;
import org.hibernate.search.backend.lucene.lowlevel.index.impl.IOStrategy;
import org.hibernate.search.backend.lucene.lowlevel.index.impl.IndexAccessorImpl;
import org.hibernate.search.backend.lucene.lowlevel.index.impl.NearRealTimeIOStrategy;
import org.hibernate.search.backend.lucene.lowlevel.writer.impl.IndexWriterConfigSource;
import org.hibernate.search.backend.lucene.multitenancy.impl.MultiTenancyStrategy;
import org.hibernate.search.backend.lucene.orchestration.impl.LuceneParallelWorkOrchestratorImpl;
import org.hibernate.search.backend.lucene.orchestration.impl.LuceneSerialWorkOrchestratorImpl;
import org.hibernate.search.backend.lucene.orchestration.impl.LuceneSyncWorkOrchestrator;
import org.hibernate.search.backend.lucene.orchestration.impl.LuceneBatchedWorkProcessor;
import org.hibernate.search.backend.lucene.resources.impl.BackendThreads;
import org.hibernate.search.backend.lucene.schema.management.impl.LuceneIndexSchemaManager;
import org.hibernate.search.backend.lucene.schema.management.impl.SchemaManagementIndexManagerContext;
import org.hibernate.search.backend.lucene.search.impl.LuceneSearchIndexesContext;
import org.hibernate.search.backend.lucene.search.impl.LuceneSearchContext;
import org.hibernate.search.backend.lucene.search.projection.impl.LuceneSearchProjection;
import org.hibernate.search.backend.lucene.search.query.impl.LuceneSearchQueryBuilder;
import org.hibernate.search.backend.lucene.search.query.impl.SearchBackendContext;
import org.hibernate.search.backend.lucene.search.timeout.spi.TimingSource;
import org.hibernate.search.backend.lucene.work.execution.impl.LuceneIndexIndexer;
import org.hibernate.search.backend.lucene.work.execution.impl.LuceneIndexIndexingPlan;
import org.hibernate.search.backend.lucene.work.execution.impl.LuceneIndexWorkspace;
import org.hibernate.search.backend.lucene.work.execution.impl.WorkExecutionBackendContext;
import org.hibernate.search.backend.lucene.work.execution.impl.WorkExecutionIndexManagerContext;
import org.hibernate.search.backend.lucene.work.impl.LuceneWorkFactory;
import org.hibernate.search.engine.backend.common.spi.EntityReferenceFactory;
import org.hibernate.search.engine.backend.mapping.spi.BackendMappingContext;
import org.hibernate.search.engine.backend.session.spi.BackendSessionContext;
import org.hibernate.search.engine.backend.session.spi.DetachedBackendSessionContext;
import org.hibernate.search.engine.backend.work.execution.DocumentCommitStrategy;
import org.hibernate.search.engine.backend.work.execution.DocumentRefreshStrategy;
import org.hibernate.search.engine.backend.work.execution.spi.IndexIndexer;
import org.hibernate.search.engine.backend.work.execution.spi.IndexIndexingPlan;
import org.hibernate.search.engine.backend.work.execution.spi.IndexWorkspace;
import org.hibernate.search.engine.cfg.spi.ConfigurationProperty;
import org.hibernate.search.engine.cfg.spi.ConfigurationPropertySource;
import org.hibernate.search.engine.reporting.FailureHandler;
import org.hibernate.search.engine.reporting.spi.EventContexts;
import org.hibernate.search.engine.search.loading.context.spi.LoadingContextBuilder;
import org.hibernate.search.util.common.impl.SuppressingCloser;
import org.hibernate.search.util.common.reporting.EventContext;

import org.apache.lucene.search.similarities.Similarity;

public class IndexManagerBackendContext implements WorkExecutionBackendContext, SearchBackendContext {

	private static final ConfigurationProperty<IOStrategyName> IO_STRATEGY =
			ConfigurationProperty.forKey( LuceneIndexSettings.IO_STRATEGY )
					.as( IOStrategyName.class, IOStrategyName::of )
					.withDefault( LuceneIndexSettings.Defaults.IO_STRATEGY )
					.build();

	private final LuceneBackend backendAPI;
	private final EventContext eventContext;

	private final BackendThreads threads;
	private final DirectoryProvider directoryProvider;
	private final Similarity similarity;
	private final LuceneWorkFactory workFactory;
	private final MultiTenancyStrategy multiTenancyStrategy;
	private final TimingSource timingSource;
	private final LuceneAnalysisDefinitionRegistry analysisDefinitionRegistry;
	private final FailureHandler failureHandler;
	private final LuceneSyncWorkOrchestrator readOrchestrator;

	public IndexManagerBackendContext(LuceneBackend backendAPI,
			EventContext eventContext,
			BackendThreads threads,
			DirectoryProvider directoryProvider,
			Similarity similarity,
			LuceneWorkFactory workFactory,
			MultiTenancyStrategy multiTenancyStrategy,
			TimingSource timingSource,
			LuceneAnalysisDefinitionRegistry analysisDefinitionRegistry,
			FailureHandler failureHandler,
			LuceneSyncWorkOrchestrator readOrchestrator) {
		this.backendAPI = backendAPI;
		this.eventContext = eventContext;
		this.threads = threads;
		this.directoryProvider = directoryProvider;
		this.similarity = similarity;
		this.multiTenancyStrategy = multiTenancyStrategy;
		this.timingSource = timingSource;
		this.analysisDefinitionRegistry = analysisDefinitionRegistry;
		this.workFactory = workFactory;
		this.failureHandler = failureHandler;
		this.readOrchestrator = readOrchestrator;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + eventContext + "]";
	}

	@Override
	public <R> IndexIndexingPlan<R> createIndexingPlan(
			WorkExecutionIndexManagerContext indexManagerContext,
			LuceneIndexEntryFactory indexEntryFactory,
			BackendSessionContext sessionContext,
			EntityReferenceFactory<R> entityReferenceFactory,
			DocumentCommitStrategy commitStrategy, DocumentRefreshStrategy refreshStrategy) {
		multiTenancyStrategy.checkTenantId( sessionContext.tenantIdentifier(), eventContext );

		return new LuceneIndexIndexingPlan<>(
				workFactory,
				indexManagerContext,
				indexEntryFactory,
				sessionContext,
				entityReferenceFactory,
				commitStrategy, refreshStrategy
		);
	}

	@Override
	public IndexIndexer createIndexer(
			WorkExecutionIndexManagerContext indexManagerContext,
			LuceneIndexEntryFactory indexEntryFactory,
			BackendSessionContext sessionContext) {
		multiTenancyStrategy.checkTenantId( sessionContext.tenantIdentifier(), eventContext );

		return new LuceneIndexIndexer(
				workFactory,
				indexEntryFactory,
				indexManagerContext,
				sessionContext
		);
	}

	@Override
	public IndexWorkspace createWorkspace(WorkExecutionIndexManagerContext indexManagerContext,
			DetachedBackendSessionContext sessionContext) {
		multiTenancyStrategy.checkTenantId( sessionContext.tenantIdentifier(), eventContext );

		return new LuceneIndexWorkspace( workFactory, indexManagerContext, sessionContext );
	}

	@Override
	public LuceneSearchContext createSearchContext(BackendMappingContext mappingContext,
			LuceneSearchIndexesContext indexes) {
		return new LuceneSearchContext(
				mappingContext, analysisDefinitionRegistry, multiTenancyStrategy,
				timingSource,
				indexes
		);
	}

	@Override
	public <H> LuceneSearchQueryBuilder<H> createSearchQueryBuilder(
			LuceneSearchContext searchContext,
			BackendSessionContext sessionContext,
			LoadingContextBuilder<?, ?, ?> loadingContextBuilder,
			LuceneSearchProjection<?, H> rootProjection) {
		multiTenancyStrategy.checkTenantId( sessionContext.tenantIdentifier(), eventContext );

		return new LuceneSearchQueryBuilder<>(
				workFactory,
				readOrchestrator,
				searchContext,
				sessionContext,
				loadingContextBuilder,
				rootProjection
		);
	}

	LuceneBackend toAPI() {
		return backendAPI;
	}

	EventContext getEventContext() {
		return eventContext;
	}

	LuceneIndexEntryFactory createLuceneIndexEntryFactory(LuceneIndexModel model) {
		return new LuceneIndexEntryFactory( model, multiTenancyStrategy );
	}

	IOStrategy createIOStrategy(ConfigurationPropertySource propertySource) {
		switch ( IO_STRATEGY.get( propertySource ) ) {
			case DEBUG:
				return DebugIOStrategy.create( directoryProvider, threads, failureHandler );
			case NEAR_REAL_TIME:
			default:
				return NearRealTimeIOStrategy.create(
						propertySource, directoryProvider,
						timingSource, threads, failureHandler
				);
		}
	}

	LuceneIndexSchemaManager createSchemaManager(SchemaManagementIndexManagerContext context) {
		return new LuceneIndexSchemaManager( workFactory, context );
	}

	Shard createShard(IOStrategy ioStrategy, LuceneIndexModel model, Optional<String> shardId,
			ConfigurationPropertySource propertySource) {
		LuceneParallelWorkOrchestratorImpl managementOrchestrator;
		LuceneSerialWorkOrchestratorImpl indexingOrchestrator;
		IndexAccessorImpl indexAccessor = null;
		String indexName = model.hibernateSearchName();
		EventContext shardEventContext = EventContexts.fromIndexNameAndShardId( model.hibernateSearchName(), shardId );
		IndexWriterConfigSource writerConfigSource = IndexWriterConfigSource.create(
				similarity, model.getIndexingAnalyzer(), propertySource, shardEventContext
		);

		try {
			indexAccessor = ioStrategy.createIndexAccessor(
					indexName, shardEventContext,
					shardId, writerConfigSource
			);
			managementOrchestrator = createIndexManagementOrchestrator( shardEventContext, indexAccessor );
			indexingOrchestrator = createIndexingOrchestrator( shardEventContext, indexAccessor );

			Shard shard = new Shard(
					shardEventContext, indexAccessor,
					managementOrchestrator, indexingOrchestrator
			);
			return shard;
		}
		catch (RuntimeException e) {
			new SuppressingCloser( e )
					// No need to stop the orchestrators, we didn't start them
					.push( indexAccessor );
			throw e;
		}
	}

	private LuceneParallelWorkOrchestratorImpl createIndexManagementOrchestrator(EventContext eventContext,
			IndexAccessorImpl indexAccessor) {
		return new LuceneParallelWorkOrchestratorImpl(
				"Lucene index management orchestrator for " + eventContext.render(),
				eventContext,
				indexAccessor,
				threads
		);
	}

	private LuceneSerialWorkOrchestratorImpl createIndexingOrchestrator(EventContext eventContext,
			IndexAccessorImpl indexAccessor) {
		return new LuceneSerialWorkOrchestratorImpl(
				"Lucene indexing orchestrator for " + eventContext.render(),
				new LuceneBatchedWorkProcessor(
						eventContext, indexAccessor
				),
				threads,
				failureHandler
		);
	}
}
