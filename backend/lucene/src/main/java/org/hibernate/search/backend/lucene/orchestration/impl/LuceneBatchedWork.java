/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.orchestration.impl;

import java.util.concurrent.CompletableFuture;

import org.hibernate.search.backend.lucene.work.impl.IndexingWork;
import org.hibernate.search.engine.backend.orchestration.spi.BatchedWork;

public class LuceneBatchedWork<T> implements BatchedWork<LuceneBatchedWorkProcessor> {
	public final IndexingWork<T> work;
	public final CompletableFuture<T> future;

	LuceneBatchedWork(IndexingWork<T> work, CompletableFuture<T> future) {
		this.work = work;
		this.future = future;
	}

	@Override
	public void submitTo(LuceneBatchedWorkProcessor processor) {
		try {
			T result = processor.submit( work );
			future.complete( result );
		}
		catch (RuntimeException e) {
			markAsFailed( e );
		}
	}

	@Override
	public void markAsFailed(Throwable t) {
		future.completeExceptionally( t );
	}

	String getQueuingKey() {
		return work.getQueuingKey();
	}
}
