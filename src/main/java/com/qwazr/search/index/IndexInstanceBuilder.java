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
package com.qwazr.search.index;

import com.qwazr.search.analysis.AnalyzerContext;
import com.qwazr.search.analysis.AnalyzerDefinition;
import com.qwazr.search.analysis.UpdatableAnalyzer;
import com.qwazr.search.field.FieldDefinition;
import com.qwazr.utils.HashUtils;
import com.qwazr.utils.IOUtils;
import com.qwazr.utils.json.JsonMapper;
import com.qwazr.server.ServerException;
import org.apache.lucene.index.*;
import org.apache.lucene.replicator.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

class IndexInstanceBuilder {

	final static String INDEX_DATA = "data";
	final static String REPL_WORK = "repl_work";
	final static String UUID_FILE = "uuid";
	final static String UUID_MASTER_FILE = "uuid.master";
	final static String SETTINGS_FILE = "settings.json";
	final static String FIELDS_FILE = "fields.json";
	final static String ANALYZERS_FILE = "analyzers.json";
	final static String RESOURCES_DIR = "resources";

	static class FileSet {

		final File uuidFile;
		final File uuidMasterFile;
		final File settingsFile;
		final File indexDirectory;
		final File dataDirectory;
		final File analyzerMapFile;
		final File resourcesDirectory;
		final File fieldMapFile;
		final Path replWorkPath;

		private FileSet(File indexDirectory) {
			this.uuidFile = new File(indexDirectory, UUID_FILE);
			this.uuidMasterFile = new File(indexDirectory, UUID_MASTER_FILE);
			this.indexDirectory = indexDirectory;
			this.dataDirectory = new File(indexDirectory, INDEX_DATA);
			this.analyzerMapFile = new File(indexDirectory, ANALYZERS_FILE);
			this.resourcesDirectory = new File(indexDirectory, RESOURCES_DIR);
			this.fieldMapFile = new File(indexDirectory, FIELDS_FILE);
			this.settingsFile = new File(indexDirectory, SETTINGS_FILE);
			this.replWorkPath = indexDirectory.toPath().resolve(REPL_WORK);
		}
	}

	final SchemaInstance schema;
	final File indexDirectory;
	final FileSet fileSet;
	final SearcherFactory searcherFactory;
	final ExecutorService executorService;

	IndexSettingsDefinition settings;

	Directory dataDirectory = null;

	LinkedHashMap<String, AnalyzerDefinition> analyzerMap = null;
	LinkedHashMap<String, FieldDefinition> fieldMap = null;

	IndexWriter indexWriter = null;
	SearcherManager searcherManager = null;
	UpdatableAnalyzer indexAnalyzer = null;
	UpdatableAnalyzer queryAnalyzer = null;

	FileResourceLoader fileResourceLoader = null;

	LocalReplicator replicator = null;
	ReplicationClient replicationClient = null;
	IndexReplicator indexReplicator = null;

	UUID indexUuid = null;

	IndexInstanceBuilder(final SchemaInstance schema, final File indexDirectory, final IndexSettingsDefinition settings,
			final ExecutorService executorService) {
		this.schema = schema;
		this.indexDirectory = indexDirectory;
		this.settings = settings;
		this.fileSet = new FileSet(indexDirectory);
		this.searcherFactory = new MultiThreadSearcherFactory(executorService);
		this.executorService = executorService;
	}

	private static class MultiThreadSearcherFactory extends SearcherFactory {

		private final ExecutorService executorService;

		private MultiThreadSearcherFactory(final ExecutorService executorService) {
			this.executorService = executorService;
		}

		public IndexSearcher newSearcher(IndexReader reader, IndexReader previousReader) throws IOException {
			return new IndexSearcher(reader, executorService);
		}
	}

	private void buildCommon() throws IOException, ReflectiveOperationException, URISyntaxException {

		if (!indexDirectory.exists())
			indexDirectory.mkdir();
		if (!indexDirectory.isDirectory())
			throw new IOException("This name is not valid. No directory exists for this location: "
					+ indexDirectory.getAbsolutePath());

		// Manage the index UUID
		if (fileSet.uuidFile.exists()) {
			if (!fileSet.uuidFile.isFile())
				throw new IOException("The UUID path is not a file: " + fileSet.uuidFile);
			indexUuid = UUID.fromString(IOUtils.readFileAsString(fileSet.uuidFile));
		} else {
			indexUuid = HashUtils.newTimeBasedUUID();
			IOUtils.writeStringAsFile(indexUuid.toString(), fileSet.uuidFile);
		}

		//Loading the settings
		if (settings == null)
			settings = fileSet.settingsFile.exists() ?
					JsonMapper.MAPPER.readValue(fileSet.settingsFile, IndexSettingsDefinition.class) :
					IndexSettingsDefinition.EMPTY;
		else
			JsonMapper.MAPPER.writeValue(fileSet.settingsFile, settings);

		//Loading the fields
		final File fieldMapFile = new File(indexDirectory, FIELDS_FILE);
		fieldMap = fieldMapFile.exists() ?
				JsonMapper.MAPPER.readValue(fieldMapFile, FieldDefinition.MapStringFieldTypeRef) :
				new LinkedHashMap<>();

		//Loading the fields
		final File analyzerMapFile = new File(indexDirectory, ANALYZERS_FILE);
		analyzerMap = analyzerMapFile.exists() ?
				JsonMapper.MAPPER.readValue(analyzerMapFile, AnalyzerDefinition.MapStringAnalyzerTypeRef) :
				new LinkedHashMap<>();

		// Set the resource loader
		fileResourceLoader = new FileResourceLoader(schema.getClassLoaderManager(), null, fileSet.resourcesDirectory);

		final AnalyzerContext context =
				new AnalyzerContext(schema.getClassLoaderManager(), fileResourceLoader, analyzerMap, fieldMap, false);
		indexAnalyzer = new UpdatableAnalyzer(context.indexAnalyzerMap);
		queryAnalyzer = new UpdatableAnalyzer(context.queryAnalyzerMap);

		// Open and lock the data directory
		dataDirectory = FSDirectory.open(fileSet.dataDirectory.toPath());

	}

	private void openOrCreateIndex() throws ReflectiveOperationException, IOException {
		final IndexWriterConfig indexWriterConfig = new IndexWriterConfig(indexAnalyzer);
		indexWriterConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
		if (settings != null) {
			if (settings.similarity_class != null && !settings.similarity_class.isEmpty())
				indexWriterConfig.setSimilarity(
						IndexUtils.findSimilarity(schema.getClassLoaderManager(), settings.similarity_class));
			if (settings.ram_buffer_size != null)
				indexWriterConfig.setRAMBufferSizeMB(settings.ram_buffer_size);
		}
		final SerialMergeScheduler serialMergeScheduler = new SerialMergeScheduler();
		indexWriterConfig.setMergeScheduler(serialMergeScheduler);
		final SnapshotDeletionPolicy snapshotDeletionPolicy =
				new SnapshotDeletionPolicy(indexWriterConfig.getIndexDeletionPolicy());
		indexWriterConfig.setIndexDeletionPolicy(snapshotDeletionPolicy);
		indexWriter = new IndexWriter(dataDirectory, indexWriterConfig);
		if (indexWriter.hasUncommittedChanges())
			indexWriter.commit();
	}

	private void buildSlave() throws IOException, URISyntaxException, ReflectiveOperationException {

		// We just want to be sure the index exists.
		openOrCreateIndex();
		indexWriter.close();
		indexWriter = null;

		Callable<Boolean> callback = () -> {
			searcherManager.maybeRefresh();
			schema.mayBeRefresh(false);
			return true;
		};
		ReplicationClient.ReplicationHandler handler = new IndexReplicationHandler(dataDirectory, callback);
		ReplicationClient.SourceDirectoryFactory factory = new PerSessionDirectoryFactory(fileSet.replWorkPath);
		indexReplicator = new IndexReplicator(schema.getService(), settings.master, fileSet.uuidMasterFile);
		replicationClient = new ReplicationClient(indexReplicator, handler, factory);

		// we build the SearcherManager
		searcherManager = new SearcherManager(dataDirectory, searcherFactory);
	}

	private void buildMaster() throws IOException, ReflectiveOperationException {

		openOrCreateIndex();

		// Manage the master replication (revision publishing)
		replicator = new LocalReplicator();
		replicator.publish(new IndexRevision(indexWriter));

		// Finally we build the SearcherManager
		searcherManager = new SearcherManager(indexWriter, searcherFactory);
	}

	private void abort() {
		IOUtils.closeQuietly(replicationClient, searcherManager, indexAnalyzer, queryAnalyzer, replicator);
		if (indexWriter != null && indexWriter.isOpen())
			IOUtils.closeQuietly(indexWriter);
		IOUtils.closeQuietly(dataDirectory);
	}

	IndexInstance build() throws ReflectiveOperationException, IOException, URISyntaxException {
		try {
			buildCommon();
			if (fileSet.uuidMasterFile.exists() || settings.master != null)
				buildSlave();
			else
				buildMaster();
			return new IndexInstance(schema.getClassLoaderManager(), this);
		} catch (IOException | ReflectiveOperationException | URISyntaxException e) {
			abort();
			throw e;
		} catch (Exception e) {
			abort();
			throw new ServerException(e);
		}
	}
}
