/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.elasticsearch.types.sort.impl;

import java.lang.invoke.MethodHandles;

import org.hibernate.search.backend.elasticsearch.gson.impl.JsonAccessor;
import org.hibernate.search.backend.elasticsearch.gson.impl.JsonObjectAccessor;
import org.hibernate.search.backend.elasticsearch.logging.impl.Log;
import org.hibernate.search.backend.elasticsearch.search.impl.ElasticsearchSearchContext;
import org.hibernate.search.backend.elasticsearch.search.impl.ElasticsearchSearchFieldContext;
import org.hibernate.search.backend.elasticsearch.search.sort.impl.ElasticsearchSearchSortCollector;
import org.hibernate.search.backend.elasticsearch.types.codec.impl.ElasticsearchGeoPointFieldCodec;
import org.hibernate.search.engine.search.common.SortMode;
import org.hibernate.search.engine.search.sort.SearchSort;
import org.hibernate.search.engine.search.sort.spi.DistanceSortBuilder;
import org.hibernate.search.engine.spatial.GeoPoint;
import org.hibernate.search.util.common.logging.impl.LoggerFactory;

import com.google.gson.JsonObject;

public class ElasticsearchDistanceSort extends AbstractElasticsearchDocumentValueSort {

	private static final Log log = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	private static final JsonObjectAccessor GEO_DISTANCE_ACCESSOR = JsonAccessor.root().property( "_geo_distance" ).asObject();

	private final GeoPoint center;

	public ElasticsearchDistanceSort(Builder builder) {
		super( builder );
		center = builder.center;
	}

	@Override
	protected void doToJsonSorts(ElasticsearchSearchSortCollector collector, JsonObject innerObject) {
		innerObject.add( absoluteFieldPath, ElasticsearchGeoPointFieldCodec.INSTANCE.encode( center ) );

		JsonObject outerObject = new JsonObject();
		GEO_DISTANCE_ACCESSOR.add( outerObject, innerObject );
		collector.collectDistanceSort( outerObject, absoluteFieldPath, center );
	}

	public static class Builder extends AbstractBuilder<GeoPoint> implements DistanceSortBuilder {
		private final GeoPoint center;

		public Builder(ElasticsearchSearchContext searchContext,
				ElasticsearchSearchFieldContext<GeoPoint> field, GeoPoint center) {
			super( searchContext, field );
			this.center = center;
		}

		@Override
		public void mode(SortMode mode) {
			switch ( mode ) {
				case MIN:
				case MAX:
				case AVG:
				case MEDIAN:
					super.mode( mode );
					break;
				case SUM:
				default:
					throw log.cannotComputeSumForDistanceSort( field.eventContext() );
			}
		}

		@Override
		public SearchSort build() {
			return new ElasticsearchDistanceSort( this );
		}
	}
}
