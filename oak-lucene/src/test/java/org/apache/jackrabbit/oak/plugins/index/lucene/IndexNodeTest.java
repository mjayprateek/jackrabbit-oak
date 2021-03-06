/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.index.lucene;

import java.io.File;
import java.io.IOException;

import org.apache.jackrabbit.oak.plugins.index.lucene.hybrid.NRTIndexFactory;
import org.apache.jackrabbit.oak.plugins.index.lucene.reader.DefaultIndexReaderFactory;
import org.apache.jackrabbit.oak.plugins.index.lucene.reader.LuceneIndexReaderFactory;
import org.apache.jackrabbit.oak.plugins.index.lucene.util.IndexDefinitionBuilder;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor;
import static org.apache.jackrabbit.oak.plugins.index.lucene.FieldNames.PATH;
import static org.apache.jackrabbit.oak.plugins.index.lucene.TestUtil.newDoc;
import static org.apache.jackrabbit.oak.plugins.nodetype.write.InitialContent.INITIAL_CONTENT;
import static org.junit.Assert.*;

public class IndexNodeTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder(new File("target"));

    private NodeState root = INITIAL_CONTENT;
    private NodeBuilder builder = root.builder();

    private IndexCopier indexCopier;
    private NRTIndexFactory nrtFactory;
    private LuceneIndexReaderFactory readerFactory;

    @Before
    public void setUp() throws IOException {
        indexCopier = new IndexCopier(sameThreadExecutor(), temporaryFolder.getRoot());
        nrtFactory = new NRTIndexFactory(indexCopier);
        readerFactory = new DefaultIndexReaderFactory(Mounts.defaultMountInfoProvider(), indexCopier);
        LuceneIndexEditorContext.configureUniqueId(builder);
    }

    @After
    public void cleanup() throws IOException {
        nrtFactory.close();
        indexCopier.close();
    }

    @Test
    public void nullIndexNode() throws Exception{
        assertNull(IndexNode.open("/foo", root, builder.getNodeState(), readerFactory, null));
        assertNull(IndexNode.open("/foo", root, builder.getNodeState(), readerFactory, nrtFactory));
    }

    @Test
    public void nonNullIndex_OnlyNRT() throws Exception{
        IndexNode node = IndexNode.open("/foo", root, createNRTIndex(), readerFactory, nrtFactory);
        assertNotNull(node.getSearcher());
        TopDocs docs = node.getSearcher().search(new TermQuery(new Term(PATH, "/content/en")), 100);
        assertEquals(0, docs.totalHits);

        node.getLocalWriter().updateDocument("/content/en", newDoc("/content/en"));
        node.refreshReadersOnWriteIfRequired();

        docs = node.getSearcher().search(new TermQuery(new Term(PATH, "/content/en")), 100);
        assertEquals(1, docs.totalHits);
    }

    @Test
    public void nullIndex_NonFreshIndex() throws Exception{
        NodeBuilder builder = createNRTIndex().builder();
        NodeBuilder rootBuilder = root.builder();
        rootBuilder.child(IndexNode.ASYNC);
        assertNull(IndexNode.open("/foo", rootBuilder.getNodeState(), builder.getNodeState(), readerFactory, nrtFactory));
    }

    private static NodeState createNRTIndex(){
        IndexDefinitionBuilder idx = new IndexDefinitionBuilder();
        idx.indexRule("nt:base").property("foo").propertyIndex();
        idx.async("async", "sync");
        return idx.build();
    }
}