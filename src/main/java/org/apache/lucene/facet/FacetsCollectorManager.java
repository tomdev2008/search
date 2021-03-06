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
 **/
package org.apache.lucene.facet;

import org.apache.lucene.search.CollectorManager;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public class FacetsCollectorManager implements CollectorManager<FacetsCollector, FacetsCollector> {

	public final static FacetsCollector EMPTY = new FacetsCollector();

	@Override
	public FacetsCollector newCollector() throws IOException {
		return new FacetsCollector();
	}

	@Override
	public FacetsCollector reduce(Collection<FacetsCollector> collectors) throws IOException {
		if (collectors == null || collectors.size() == 0)
			return EMPTY;
		if (collectors.size() == 1)
			return collectors.iterator().next();
		return new ReducedFacetsCollector(collectors);
	}

	private static class ReducedFacetsCollector extends FacetsCollector {

		public ReducedFacetsCollector(final Collection<FacetsCollector> facetsCollectors) {
			final List<MatchingDocs> matchingDocs = this.getMatchingDocs();
			facetsCollectors.forEach(facetsCollector -> matchingDocs.addAll(facetsCollector.getMatchingDocs()));
		}
	}

}
