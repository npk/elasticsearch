/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.benchmark.search.facet;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.action.bulk.BulkRequestBuilder;
import org.elasticsearch.common.RandomStringGenerator;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.SizeValue;
import org.elasticsearch.common.util.concurrent.jsr166y.ThreadLocalRandom;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.node.Node;

import static org.elasticsearch.client.Requests.*;
import static org.elasticsearch.cluster.metadata.IndexMetaData.*;
import static org.elasticsearch.common.settings.ImmutableSettings.*;
import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.xcontent.QueryBuilders.*;
import static org.elasticsearch.node.NodeBuilder.*;
import static org.elasticsearch.search.facet.FacetBuilders.*;

/**
 * @author kimchy (shay.banon)
 */
public class TermsFacetSearchBenchmark {

    public static void main(String[] args) throws Exception {
        Settings settings = settingsBuilder()
                .put("index.refresh_interval", "-1")
                .put("gateway.type", "local")
                .put(SETTING_NUMBER_OF_SHARDS, 2)
                .put(SETTING_NUMBER_OF_REPLICAS, 0)
                .build();

        Node node1 = nodeBuilder().settings(settingsBuilder().put(settings).put("name", "node1")).node();
        Node node2 = nodeBuilder().settings(settingsBuilder().put(settings).put("name", "node2")).node();

        Node clientNode = nodeBuilder().settings(settingsBuilder().put(settings).put("name", "client")).client(true).node();

        Client client = clientNode.client();

        long COUNT = SizeValue.parseSizeValue("2m").singles();
        int BATCH = 100;
        int QUERY_WARMUP = 20;
        int QUERY_COUNT = 200;
        int NUMBER_OF_TERMS = 200;
        int NUMBER_OF_MULTI_VALUE_TERMS = 10;
        int STRING_TERM_SIZE = 5;

        long[] lValues = new long[NUMBER_OF_TERMS];
        for (int i = 0; i < NUMBER_OF_TERMS; i++) {
            lValues[i] = ThreadLocalRandom.current().nextLong();
        }
        String[] sValues = new String[NUMBER_OF_TERMS];
        for (int i = 0; i < NUMBER_OF_TERMS; i++) {
            sValues[i] = RandomStringGenerator.randomAlphabetic(STRING_TERM_SIZE);
        }

        Thread.sleep(10000);
        try {
            client.admin().indices().create(createIndexRequest("test")).actionGet();

            StopWatch stopWatch = new StopWatch().start();

            System.out.println("--> Indexing [" + COUNT + "] ...");
            long ITERS = COUNT / BATCH;
            long i = 1;
            int counter = 0;
            for (; i <= ITERS; i++) {
                BulkRequestBuilder request = client.prepareBulk();
                for (int j = 0; j < BATCH; j++) {
                    counter++;

                    XContentBuilder builder = jsonBuilder().startObject();
                    builder.field("id", Integer.toString(counter));
                    builder.field("s_value", sValues[counter % sValues.length]);
                    builder.field("i_value", lValues[counter % lValues.length]);

                    builder.startArray("sm_value");
                    for (int k = 0; k < NUMBER_OF_MULTI_VALUE_TERMS; k++) {
                        builder.value(sValues[ThreadLocalRandom.current().nextInt(sValues.length)]);
                    }
                    builder.endArray();

                    builder.startArray("lm_value");
                    for (int k = 0; k < NUMBER_OF_MULTI_VALUE_TERMS; k++) {
                        builder.value(lValues[ThreadLocalRandom.current().nextInt(sValues.length)]);
                    }
                    builder.endArray();

                    builder.endObject();

                    request.add(Requests.indexRequest("test").type("type1").id(Integer.toString(counter))
                            .source(builder));
                }
                BulkResponse response = request.execute().actionGet();
                if (response.hasFailures()) {
                    System.err.println("--> failures...");
                }
                if (((i * BATCH) % 10000) == 0) {
                    System.out.println("--> Indexed " + (i * BATCH) + " took " + stopWatch.stop().lastTaskTime());
                    stopWatch.start();
                }
            }
            System.out.println("--> Indexing took " + stopWatch.totalTime() + ", TPS " + (((double) (COUNT)) / stopWatch.totalTime().secondsFrac()));
        } catch (Exception e) {
            System.out.println("--> Index already exists, ignoring indexing phase, waiting for green");
            ClusterHealthResponse clusterHealthResponse = client.admin().cluster().prepareHealth().setWaitForGreenStatus().setTimeout("10m").execute().actionGet();
            if (clusterHealthResponse.timedOut()) {
                System.err.println("--> Timed out waiting for cluster health");
            }
        }
        client.admin().indices().prepareRefresh().execute().actionGet();
        COUNT = client.prepareCount().setQuery(matchAllQuery()).execute().actionGet().count();
        System.out.println("--> Number of docs in index: " + COUNT);


        long totalQueryTime = 0;

        // S_VALUE
        client.admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();

        System.out.println("--> Warmup (s_value) ...");
        // run just the child query, warm up first
        for (int j = 0; j < QUERY_WARMUP; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("s_value").field("s_value"))
                    .execute().actionGet();
            if (j == 0) {
                System.out.println("--> Loading (s_value) took: " + searchResponse.took());
            }
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
        }
        System.out.println("--> Warmup (s_value) DONE");

        totalQueryTime = 0;
        for (int j = 0; j < QUERY_COUNT; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("s_value").field("s_value"))
                    .execute().actionGet();
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
            totalQueryTime += searchResponse.tookInMillis();
        }
        System.out.println("--> Terms Facet (s_value) " + (totalQueryTime / QUERY_COUNT) + "ms");


        // S_VALUE (Map)
        client.admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();

        System.out.println("--> Warmup (s_value) ...");
        // run just the child query, warm up first
        for (int j = 0; j < QUERY_WARMUP; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("s_value").field("s_value").executionHint("map"))
                    .execute().actionGet();
            if (j == 0) {
                System.out.println("--> Loading (s_value) took: " + searchResponse.took());
            }
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
        }
        System.out.println("--> Warmup (s_value) DONE");

        totalQueryTime = 0;
        for (int j = 0; j < QUERY_COUNT; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("s_value").field("s_value").executionHint("map"))
                    .execute().actionGet();
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
            totalQueryTime += searchResponse.tookInMillis();
        }
        System.out.println("--> Terms Facet (map) (s_value) " + (totalQueryTime / QUERY_COUNT) + "ms");

        // L VALUE
        client.admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();

        System.out.println("--> Warmup (l_value) ...");
        // run just the child query, warm up first
        for (int j = 0; j < QUERY_WARMUP; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("l_value").field("l_value"))
                    .execute().actionGet();
            if (j == 0) {
                System.out.println("--> Loading (l_value) took: " + searchResponse.took());
            }
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
        }
        System.out.println("--> Warmup (l_value) DONE");

        totalQueryTime = 0;
        for (int j = 0; j < QUERY_COUNT; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("l_value").field("l_value"))
                    .execute().actionGet();
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
            totalQueryTime += searchResponse.tookInMillis();
        }
        System.out.println("--> Terms Facet (l_value) " + (totalQueryTime / QUERY_COUNT) + "ms");

        // SM VALUE

        client.admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();

        System.out.println("--> Warmup (sm_value) ...");
        // run just the child query, warm up first
        for (int j = 0; j < QUERY_WARMUP; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("sm_value").field("sm_value"))
                    .execute().actionGet();
            if (j == 0) {
                System.out.println("--> Loading (sm_value) took: " + searchResponse.took());
            }
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
        }
        System.out.println("--> Warmup (sm_value) DONE");


        totalQueryTime = 0;
        for (int j = 0; j < QUERY_COUNT; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("sm_value").field("sm_value"))
                    .execute().actionGet();
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
            totalQueryTime += searchResponse.tookInMillis();
        }
        System.out.println("--> Terms Facet (sm_value) " + (totalQueryTime / QUERY_COUNT) + "ms");

        // SM VALUE (map)

        client.admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();

        System.out.println("--> Warmup (sm_value) ...");
        // run just the child query, warm up first
        for (int j = 0; j < QUERY_WARMUP; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("sm_value").field("sm_value").executionHint("map"))
                    .execute().actionGet();
            if (j == 0) {
                System.out.println("--> Loading (sm_value) took: " + searchResponse.took());
            }
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
        }
        System.out.println("--> Warmup (sm_value) DONE");


        totalQueryTime = 0;
        for (int j = 0; j < QUERY_COUNT; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("sm_value").field("sm_value").executionHint("map"))
                    .execute().actionGet();
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
            totalQueryTime += searchResponse.tookInMillis();
        }
        System.out.println("--> Terms Facet (map) (sm_value) " + (totalQueryTime / QUERY_COUNT) + "ms");


        // LM VALUE

        client.admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();

        System.out.println("--> Warmup (lm_value) ...");
        // run just the child query, warm up first
        for (int j = 0; j < QUERY_WARMUP; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("lm_value").field("lm_value"))
                    .execute().actionGet();
            if (j == 0) {
                System.out.println("--> Loading (lm_value) took: " + searchResponse.took());
            }
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
        }
        System.out.println("--> Warmup (lm_value) DONE");


        totalQueryTime = 0;
        for (int j = 0; j < QUERY_COUNT; j++) {
            SearchResponse searchResponse = client.prepareSearch()
                    .setQuery(matchAllQuery())
                    .addFacet(termsFacet("lm_value").field("lm_value"))
                    .execute().actionGet();
            if (searchResponse.hits().totalHits() != COUNT) {
                System.err.println("--> mismatch on hits");
            }
            totalQueryTime += searchResponse.tookInMillis();
        }
        System.out.println("--> Terms Facet (lm_value) " + (totalQueryTime / QUERY_COUNT) + "ms");

        clientNode.close();

        node1.close();
        node2.close();
    }
}
