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
package com.qwazr.search.analysis;

import com.qwazr.utils.IOUtils;
import com.qwazr.server.ServerException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.core.KeywordAnalyzer;

import java.util.Map;

final public class UpdatableAnalyzer extends DelegatingAnalyzerWrapper {

	private final Analyzer defaultAnalyzer = new KeywordAnalyzer();

	private volatile Map<String, Analyzer> analyzerMap;

	public UpdatableAnalyzer(final Map<String, Analyzer> analyzerMap) throws ServerException {
		super(PER_FIELD_REUSE_STRATEGY);
		update(analyzerMap);
	}

	final public synchronized void update(final Map<String, Analyzer> analyzerMap) throws ServerException {
		final Map<String, Analyzer> oldAnalyzerMap = this.analyzerMap;
		this.analyzerMap = analyzerMap;
		close(oldAnalyzerMap);
	}

	private static void close(final Map<String, Analyzer> analyzerMap) {
		if (analyzerMap == null)
			return;
		analyzerMap.forEach((s, analyzer) -> IOUtils.closeQuietly(analyzer));
	}

	@Override
	final public void close() {
		close(analyzerMap);
		super.close();
	}

	@Override
	final public Analyzer getWrappedAnalyzer(final String name) {
		final Analyzer analyzer = analyzerMap.get(name);
		return analyzer == null ? defaultAnalyzer : analyzer;
	}

}
