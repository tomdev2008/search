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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qwazr.search.analysis.AnalyzerDefinition;
import com.qwazr.search.analysis.CustomAnalyzer;
import com.qwazr.search.analysis.TermConsumer;
import com.qwazr.search.index.QueryContext;
import com.qwazr.utils.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Query;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiFieldQuery extends AbstractQuery {

	@JsonProperty("fields_boosts")
	final public Map<String, Float> fieldsBoosts;

	@JsonProperty("default_operator")
	final public QueryParserOperator defaultOperator;

	@JsonProperty("query_string")
	final public String queryString;

	@JsonProperty("tokenizer")
	final public LinkedHashMap<String, String> tokenizerDefinition;

	@JsonProperty("min_number_should_match")
	final public Integer minNumberShouldMatch;

	@JsonIgnore
	final private Analyzer tokenizerAnalyzer;

	public MultiFieldQuery() {
		fieldsBoosts = null;
		defaultOperator = null;
		tokenizerDefinition = null;
		queryString = null;
		minNumberShouldMatch = null;
		tokenizerAnalyzer = null;
	}

	public MultiFieldQuery(final Map<String, Float> fieldsBoosts, final QueryParserOperator defaultOperator,
			final LinkedHashMap<String, String> tokenizerDefinition, final String queryString,
			final Integer minNumberShouldMatch) {
		this.fieldsBoosts = fieldsBoosts;
		this.defaultOperator = defaultOperator;
		this.tokenizerDefinition = tokenizerDefinition;
		this.queryString = queryString;
		this.minNumberShouldMatch = minNumberShouldMatch;
		this.tokenizerAnalyzer = null;
	}

	public MultiFieldQuery(final Map<String, Float> fieldsBoosts, final QueryParserOperator defaultOperator,
			final Analyzer tokenizerAnalyzer, final String queryString, final Integer minNumberShouldMatch) {
		this.fieldsBoosts = fieldsBoosts;
		this.defaultOperator = defaultOperator;
		this.tokenizerDefinition = null;
		this.queryString = queryString;
		this.minNumberShouldMatch = minNumberShouldMatch;
		this.tokenizerAnalyzer = tokenizerAnalyzer;
	}

	final static Analyzer DEFAULT_TOKEN_ANALYZER;

	static {
		try {
			DEFAULT_TOKEN_ANALYZER = new StandardAnalyzer(new StringReader(""));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	final public Query getQuery(final QueryContext queryContext) throws IOException, ReflectiveOperationException {
		Objects.requireNonNull(fieldsBoosts, "Fields boosts is missing");

		final String queryString = StringUtils.isEmpty(this.queryString) ? queryContext.queryString : this.queryString;
		if (StringUtils.isEmpty(queryString))
			return new org.apache.lucene.search.MatchNoDocsQuery();

		// Build the analyzer used to tokenize the query string
		final Analyzer tokenAnalyzer = tokenizerAnalyzer != null ?
				tokenizerAnalyzer :
				tokenizerDefinition != null ?
						new CustomAnalyzer(queryContext.classLoaderManager, queryContext.resourceLoader,
								new AnalyzerDefinition(null, null, tokenizerDefinition, null)) :
						DEFAULT_TOKEN_ANALYZER;

		final TopLevelTerms topLevelTerms;
		// Parse the queryString and extract terms and frequencies
		try (final TokenStream tokenStream = tokenAnalyzer.tokenStream(StringUtils.EMPTY, queryString)) {
			topLevelTerms =
					new TopLevelTerms(queryContext.indexSearcher.getIndexReader(), tokenStream, fieldsBoosts.keySet(),
							queryContext.queryAnalyzer);
			topLevelTerms.forEachToken();
			tokenStream.end();
		}

		//////
		// Build the final query
		/////
		final org.apache.lucene.search.BooleanQuery.Builder topLevelQuery =
				new org.apache.lucene.search.BooleanQuery.Builder();

		// Determine the top level occur operator
		final BooleanClause.Occur topLevelOccur =
				defaultOperator == null || defaultOperator.queryParseroperator == QueryParser.Operator.AND ?
						BooleanClause.Occur.MUST :
						BooleanClause.Occur.SHOULD;

		// Iterator over terms
		topLevelTerms.terms.forEach(topLevelTerm -> {

			final int minTermFreq = topLevelTerm.allFieldFreq.get();

			if (!acceptTopLevelTerm(minTermFreq, topLevelTerm.userTerm))
				return;

			// The query list for each term
			final List<Query> termQueries = new ArrayList<>();
			topLevelTerm.termsByField.forEach(
					(field, termsFreqs) -> addTermQuery(minTermFreq, topLevelTerm.userTerm, termsFreqs,
							fieldsBoosts.get(field), termQueries));

			// add the top level boolean clause
			if (!termQueries.isEmpty())
				topLevelQuery.add(getTopLevelQuery(termQueries), topLevelOccur);

		});

		if (minNumberShouldMatch != null)
			topLevelQuery.setMinimumNumberShouldMatch(minNumberShouldMatch);
		return topLevelQuery.build();
	}

	protected Query getTopLevelQuery(final List<Query> termQueries) {
		if (termQueries.size() == 1)
			return termQueries.get(0);
		final BooleanQuery.Builder builder = new org.apache.lucene.search.BooleanQuery.Builder();
		termQueries.forEach(query -> builder.add(query, BooleanClause.Occur.SHOULD));
		return builder.build();
	}

	protected boolean acceptTopLevelTerm(final int minTermFreq, final String userTerm) {
		return true;
	}

	protected Query getMultiTermQuery(final int globalMinTermFreq, final String userTerm, final String field,
			final String queryText) {
		return new org.apache.lucene.search.PhraseQuery(field, queryText);
	}

	protected Query getTermQuery(final int globalMinTermFreq, final String userTerm, final Term term,
			final int termFreqInField) {
		if (termFreqInField == 0 || globalMinTermFreq == 0)
			return new org.apache.lucene.search.FuzzyQuery(term);
		else
			return new org.apache.lucene.search.TermQuery(term);
	}

	private void addTermQuery(final int minTermFreq, final String userTerm, final List<TermFreq> termFreqs,
			final float boost, final Collection<Query> queries) {

		if (termFreqs.isEmpty())
			return;  // We don't have terms

		Query query;
		final TermFreq termFreq = termFreqs.get(0);
		if (termFreqs.size() > 1) {
			final StringBuilder sb = new StringBuilder();
			termFreqs.forEach(tf -> {
				sb.append(tf.term.text());
				sb.append(' ');
			});
			final String term = sb.toString().trim();
			query = getMultiTermQuery(minTermFreq, userTerm, termFreq.term.field(), term);
		} else
			query = getTermQuery(minTermFreq, userTerm, termFreq.term, termFreq.freq);

		if (query == null)
			return;

		// Add the optional boost
		if (boost != 1F)
			query = new BoostQuery(query, boost);
		queries.add(query);
	}

	private static class TopLevelTerms extends TermConsumer.WithChar {

		private final Collection<String> fields;
		private final Analyzer queryAnalyzer;
		private final IndexReader indexReader;
		private final List<TopLevelTerm> terms;

		private TopLevelTerms(final IndexReader indexReader, final TokenStream tokenStream,
				final Collection<String> fields, final Analyzer queryAnalyzer) {
			super(tokenStream);
			this.indexReader = indexReader;
			this.fields = fields;
			this.queryAnalyzer = queryAnalyzer;
			this.terms = new ArrayList<>();
		}

		@Override
		public boolean token() {
			final String text = charTermAttr.toString();
			final TopLevelTerm topLevelTerm = new TopLevelTerm(text);
			// The text is submitted to each field/analyzer
			fields.forEach(field -> {
				try (final TokenStream tokenStream = queryAnalyzer.tokenStream(field, text)) {
					final FieldLevelTerms fieldLevelTerms = new FieldLevelTerms(tokenStream, field, indexReader);
					fieldLevelTerms.forEachToken();
					topLevelTerm.allFieldFreq.addAndGet(fieldLevelTerms.minTermFreq);
					topLevelTerm.termsByField.put(field, fieldLevelTerms.termsFreqs);
					tokenStream.end();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			terms.add(topLevelTerm);
			return true;
		}

	}

	public static class TopLevelTerm {

		private final String userTerm;
		private final Map<String, List<TermFreq>> termsByField;
		private final AtomicInteger allFieldFreq;

		private TopLevelTerm(final String userTerm) {
			this.userTerm = userTerm;
			this.termsByField = new LinkedHashMap<>();
			this.allFieldFreq = new AtomicInteger();
		}

		public List<TermFreq> getTermsByFied(final String field) {
			return termsByField.get(field);
		}

		public int getAllFieldFreq() {
			return allFieldFreq.get();
		}
	}

	private static class FieldLevelTerms extends TermConsumer.WithChar {

		private final List<TermFreq> termsFreqs;
		private final String field;
		private final IndexReader indexReader;
		private int minTermFreq;

		private FieldLevelTerms(final TokenStream tokenStream, final String field, final IndexReader indexReader) {
			super(tokenStream);
			this.termsFreqs = new ArrayList<>();
			this.field = field;
			this.indexReader = indexReader;
			this.minTermFreq = Integer.MAX_VALUE;
		}

		@Override
		public boolean token() throws IOException {
			final Term term = new Term(field, charTermAttr.toString());
			final int freq = indexReader.docFreq(term);
			minTermFreq = Math.min(freq, minTermFreq);
			termsFreqs.add(new TermFreq(term, freq));
			return true;
		}
	}

	private static class TermFreq {

		private final Term term;
		private final int freq;

		private TermFreq(final Term term, final int freq) {
			this.term = term;
			this.freq = freq;
		}
	}

}
