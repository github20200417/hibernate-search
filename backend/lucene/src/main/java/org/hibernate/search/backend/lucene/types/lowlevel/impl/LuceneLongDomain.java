/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.types.lowlevel.impl;

import java.io.IOException;
import java.util.Collection;

import org.hibernate.search.util.common.data.Range;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.LongValueFacetCounts;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.LongValuesSource;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;

public class LuceneLongDomain implements LuceneNumericDomain<Long> {
	private static final LuceneNumericDomain<Long> INSTANCE = new LuceneLongDomain();

	public static LuceneNumericDomain<Long> get() {
		return INSTANCE;
	}

	@Override
	public Long getMinValue() {
		return Long.MIN_VALUE;
	}

	@Override
	public Long getMaxValue() {
		return Long.MAX_VALUE;
	}

	@Override
	public Long getPreviousValue(Long value) {
		return Math.addExact( value, -1L );
	}

	@Override
	public Long getNextValue(Long value) {
		return Math.addExact( value, 1L );
	}

	@Override
	public Query createExactQuery(String absoluteFieldPath, Long value) {
		return LongPoint.newExactQuery( absoluteFieldPath, value );
	}

	@Override
	public Query createRangeQuery(String absoluteFieldPath, Long lowerLimit, Long upperLimit) {
		return LongPoint.newRangeQuery(
				absoluteFieldPath, lowerLimit, upperLimit
		);
	}

	@Override
	public SortField.Type getSortFieldType() {
		return SortField.Type.LONG;
	}

	@Override
	public Long fromDocValue(Long longValue) {
		return longValue;
	}

	@Override
	public LongValueFacetCounts createTermsFacetCounts(String absoluteFieldPath, FacetsCollector facetsCollector) throws IOException {
		return new LongValueFacetCounts(
				absoluteFieldPath, LongValuesSource.fromLongField( absoluteFieldPath ),
				facetsCollector
		);
	}

	@Override
	public Facets createRangeFacetCounts(String absoluteFieldPath, FacetsCollector facetsCollector,
			Collection<? extends Range<? extends Long>> ranges) throws IOException {
		return new LongRangeFacetCounts(
				absoluteFieldPath,
				facetsCollector, FacetCountsUtils.createLongRanges( ranges )
		);
	}

	@Override
	public IndexableField createIndexField(String absoluteFieldPath, Long numericValue) {
		return new LongPoint( absoluteFieldPath, numericValue );
	}

	@Override
	public IndexableField createDocValuesField(String absoluteFieldPath, Long numericValue) {
		return new NumericDocValuesField( absoluteFieldPath, numericValue );
	}
}