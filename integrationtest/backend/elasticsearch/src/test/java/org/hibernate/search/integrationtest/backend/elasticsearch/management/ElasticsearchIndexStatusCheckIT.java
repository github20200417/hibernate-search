/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.integrationtest.backend.elasticsearch.management;

import static org.hibernate.search.util.impl.test.ExceptionMatcherBuilder.isException;

import java.util.EnumSet;

import org.hibernate.search.backend.elasticsearch.cfg.ElasticsearchIndexManagementStrategyConfiguration;
import org.hibernate.search.backend.elasticsearch.cfg.ElasticsearchIndexSettings;
import org.hibernate.search.backend.elasticsearch.cfg.ElasticsearchIndexStatus;
import org.hibernate.search.integrationtest.backend.elasticsearch.testsupport.util.TestElasticsearchClient;
import org.hibernate.search.integrationtest.backend.tck.testsupport.util.rule.SearchSetupHelper;
import org.hibernate.search.util.SearchException;
import org.hibernate.search.util.impl.test.annotation.TestForIssue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for the index status check feature when using automatic index management.
 */
@RunWith(Parameterized.class)
@TestForIssue(jiraKey = "HSEARCH-2456")
public class ElasticsearchIndexStatusCheckIT {

	private static final String BACKEND_NAME = "myElasticsearchBackend";
	private static final String INDEX_NAME = "IndexName";

	@Parameters(name = "With strategy {0}")
	public static EnumSet<ElasticsearchIndexManagementStrategyConfiguration> strategies() {
		// The "NONE" strategy never checks that the index exists.
		return EnumSet.complementOf( EnumSet.of( ElasticsearchIndexManagementStrategyConfiguration.NONE ) );
	}

	@Rule
	public SearchSetupHelper setupHelper = new SearchSetupHelper();

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	@Rule
	public TestElasticsearchClient elasticSearchClient = new TestElasticsearchClient();

	private ElasticsearchIndexManagementStrategyConfiguration strategy;

	public ElasticsearchIndexStatusCheckIT(ElasticsearchIndexManagementStrategyConfiguration strategy) {
		super();
		this.strategy = strategy;
	}

	@Test
	public void indexMissing() throws Exception {
		Assume.assumeFalse( "The strategy " + strategy + " creates an index automatically."
				+ " No point running this test.",
				createsIndex( strategy ) );

		elasticSearchClient.index( INDEX_NAME ).ensureDoesNotExist();

		thrown.expect( SearchException.class );
		thrown.expectMessage( "HSEARCH400050" );

		setup();
	}

	@Test
	public void invalidIndexStatus_creatingIndex() throws Exception {
		Assume.assumeTrue( "The strategy " + strategy + " doesn't creates an index automatically."
				+ " No point running this test.",
				createsIndex( strategy ) );

		// Make sure automatically created indexes will never be green
		elasticSearchClient.template( "yellow_index_because_not_enough_nodes_for_so_many_replicas" )
				.create(
						"*",
						/*
						 * The exact number of replicas we ask for doesn't matter much,
						 * since we're testing with only 1 node (the cluster can't replicate shards)
						 */
						"{'number_of_replicas': 5}"
				);

		elasticSearchClient.index( INDEX_NAME ).ensureDoesNotExist();

		thrown.expect(
				isException( SearchException.class )
						.withMessage( "HSEARCH400007" )
				.causedBy( SearchException.class )
						.withMessage( "HSEARCH400024" )
						.withMessage( "100ms" )
				.build()
		);

		setup();
	}

	@Test
	public void invalidIndexStatus_usingPreexistingIndex() throws Exception {
		// Make sure automatically created indexes will never be green
		elasticSearchClient.template( "yellow_index_because_not_enough_nodes_for_so_many_replicas" )
				.create(
						"*",
						/*
						 * The exact number of replicas we ask for doesn't matter much,
						 * since we're testing with only 1 node (the cluster can't replicate shards)
						 */
						"{'number_of_replicas': 5}"
				);

		elasticSearchClient.index( INDEX_NAME ).deleteAndCreate();

		thrown.expect(
				isException( SearchException.class )
						.withMessage( "HSEARCH400007" )
				.causedBy( SearchException.class )
						.withMessage( "HSEARCH400024" )
						.withMessage( "100ms" )
				.build()
		);

		setup();
	}

	private void setup() {
		withManagementStrategyConfiguration()
				.withIndex(
						"MappedType", INDEX_NAME,
						ctx -> { },
						indexMapping -> { }
				)
				.setup();
	}

	private SearchSetupHelper.SetupContext withManagementStrategyConfiguration() {
		return setupHelper.withDefaultConfiguration( BACKEND_NAME )
				.withIndexDefaultsProperty(
						BACKEND_NAME,
						ElasticsearchIndexSettings.MANAGEMENT_STRATEGY,
						strategy.getExternalName()
				)
				.withIndexDefaultsProperty(
						BACKEND_NAME,
						ElasticsearchIndexSettings.MANAGEMENT_REQUIRED_STATUS,
						ElasticsearchIndexStatus.GREEN.getElasticsearchString()
				)
				.withIndexDefaultsProperty(
						BACKEND_NAME,
						ElasticsearchIndexSettings.MANAGEMENT_REQUIRED_STATUS_WAIT_TIMEOUT,
						"100"
				);
	}

	private boolean createsIndex(ElasticsearchIndexManagementStrategyConfiguration strategy) {
		return !ElasticsearchIndexManagementStrategyConfiguration.NONE.equals( strategy )
				&& !ElasticsearchIndexManagementStrategyConfiguration.VALIDATE.equals( strategy );
	}

}