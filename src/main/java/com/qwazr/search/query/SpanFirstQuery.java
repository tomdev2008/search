/**
 * Copyright 2015-2016 Emmanuel Keller / QWAZR
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qwazr.search.query;

import com.qwazr.search.index.QueryContext;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.search.Query;

import java.io.IOException;

public class SpanFirstQuery extends AbstractQuery {

	final public AbstractSpanQuery spanQuery;
	final public Integer end;

	public SpanFirstQuery() {
		spanQuery = null;
		end = null;
	}

	public SpanFirstQuery(final AbstractSpanQuery spanQuery) {
		this.spanQuery = spanQuery;
		this.end = null;
	}

	public SpanFirstQuery(final AbstractSpanQuery spanQuery, final Integer end) {
		this.spanQuery = spanQuery;
		this.end = end;
	}

	@Override
	final public Query getQuery(final QueryContext queryContext)
			throws IOException, ParseException, QueryNodeException, ReflectiveOperationException {
		return new org.apache.lucene.search.spans.SpanFirstQuery(spanQuery.getQuery(queryContext),
				end == null ? 0 : end);
	}
}
