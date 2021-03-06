/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.hbase.index.covered;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdge;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import com.salesforce.hbase.index.IndexTestingUtils;
import com.salesforce.hbase.index.Indexer;
import com.salesforce.hbase.index.TableName;
import com.salesforce.hbase.index.covered.update.ColumnReference;
import com.salesforce.hbase.index.scanner.Scanner;

/**
 * End-to-End test of just the {@link CoveredColumnsIndexBuilder}, but with a simple
 * {@link IndexCodec} and BatchCache implementation.
 */
public class TestEndToEndCoveredColumnsIndexBuilder {

  public class TestState {

    private HTable table;
    private long ts;
    private VerifyingIndexCodec codec;

    /**
     * @param primary
     * @param codec
     * @param ts
     */
    public TestState(HTable primary, VerifyingIndexCodec codec, long ts) {
      this.table = primary;
      this.ts = ts;
      this.codec = codec;
    }

  }

  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();

  private static final byte[] row = Bytes.toBytes("row");
  private static final byte[] family = Bytes.toBytes("FAM");
  private static final byte[] qual = Bytes.toBytes("qual");
  private static final HColumnDescriptor FAM1 = new HColumnDescriptor(family);

  @Rule
  public TableName TestTable = new TableName();

  private TestState state;

  @BeforeClass
  public static void setupCluster() throws Exception {
    Configuration conf = UTIL.getConfiguration();
    IndexTestingUtils.setupConfig(conf);
    // disable version checking, so we can test against whatever version of HBase happens to be
    // installed (right now, its generally going to be SNAPSHOT versions).
    conf.setBoolean(Indexer.CHECK_VERSION_CONF_KEY, false);
    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void shutdownCluster() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  @Before
  public void setup() throws Exception {
    this.state = setupTest(TestTable.getTableNameString());
  }
    
  private interface TableStateVerifier {

    /**
     * Verify that the state of the table is correct. Should fail the unit test if it isn't as
     * expected.
     * @param state
     */
    public void verify(TableState state);

  }

  /**
   * {@link TableStateVerifier} that ensures the kvs returned from the table match the passed
   * {@link KeyValue}s when querying on the given columns.
   */
  private class ListMatchingVerifier implements TableStateVerifier {

    private List<KeyValue> expectedKvs;
    private ColumnReference[] columns;
    private String msg;

    public ListMatchingVerifier(String msg, List<KeyValue> kvs, ColumnReference... columns) {
      this.expectedKvs = kvs;
      this.columns = columns;
      this.msg = msg;
    }

    @Override
    public void verify(TableState state) {
      try {
        Scanner kvs =
            ((LocalTableState) state).getNonIndexedColumnsTableState(Arrays.asList(columns));

        int count = 0;
        KeyValue kv;
        while ((kv = kvs.next()) != null) {
          KeyValue next = expectedKvs.get(count++);
          assertEquals(
            msg + ": Unexpected kv in table state!\nexpected v1: "
                + Bytes.toString(next.getValue()) + "\nactual v1:" + Bytes.toString(kv.getValue()),
            next, kv);
        }

        assertEquals(msg + ": Didn't find enough kvs in table state!", expectedKvs.size(), count);
      } catch (IOException e) {
        fail(msg + ": Got an exception while reading local table state! " + e.getMessage());
      }
    }
  }

  private class VerifyingIndexCodec extends CoveredIndexCodecForTesting {

    private Queue<TableStateVerifier> verifiers = new ArrayDeque<TableStateVerifier>();

    @Override
    public Iterable<IndexUpdate> getIndexDeletes(TableState state) {
      verify(state);
      return super.getIndexDeletes(state);
    }

    @Override
    public Iterable<IndexUpdate> getIndexUpserts(TableState state) {
      verify(state);
      return super.getIndexUpserts(state);
    }

    private void verify(TableState state) {
      TableStateVerifier verifier = verifiers.poll();
      if (verifier == null) return;
      verifier.verify(state);
    }
  }
  
  /**
   * Test that we see the expected values in a {@link TableState} when doing single puts against a
   * region.
   * @throws Exception on failure
   */
  @Test
  public void testExpectedResultsInTableStateForSinglePut() throws Exception {
    //just do a simple Put to start with
    long ts = state.ts;
    Put p = new Put(row, ts);
    p.add(family, qual, Bytes.toBytes("v1"));
    
    // get all the underlying kvs for the put
    final List<KeyValue> expectedKvs = new ArrayList<KeyValue>();
    final List<KeyValue> allKvs = new ArrayList<KeyValue>();
    allKvs.addAll(p.getFamilyMap().get(family));

    // setup the verifier for the data we expect to write
    // first call shouldn't have anything in the table
    final ColumnReference familyRef =
        new ColumnReference(TestEndToEndCoveredColumnsIndexBuilder.family, ColumnReference.ALL_QUALIFIERS);

    VerifyingIndexCodec codec = state.codec;
    codec.verifiers.add(new ListMatchingVerifier("cleanup state 1", expectedKvs, familyRef));
    codec.verifiers.add(new ListMatchingVerifier("put state 1", allKvs, familyRef));

    // do the actual put (no indexing will actually be done)
    HTable primary = state.table;
    primary.put(p);
    primary.flushCommits();

    // now we do another put to the same row. We should see just the old row state, followed by the
    // new + old
    p = new Put(row, ts + 1);
    p.add(family, qual, Bytes.toBytes("v2"));
    expectedKvs.addAll(allKvs);
    // add them first b/c the ts is newer
    allKvs.addAll(0, p.get(family, qual));
    codec.verifiers.add(new ListMatchingVerifier("cleanup state 2", expectedKvs, familyRef));
    codec.verifiers.add(new ListMatchingVerifier("put state 2", allKvs, familyRef));
    
    // do the actual put
    primary.put(p);
    primary.flushCommits();

    // cleanup after ourselves
    cleanup(state);
  }

  /**
   * Similar to {@link #testExpectedResultsInTableStateForSinglePut()}, but against batches of puts.
   * Previous implementations managed batches by playing current state against each element in the
   * batch, rather than combining all the per-row updates into a single mutation for the batch. This
   * test ensures that we see the correct expected state.
   * @throws Exception on failure
   */
  @Test
  public void testExpectedResultsInTableStateForBatchPuts() throws Exception {
    long ts = state.ts;
    // build up a list of puts to make, all on the same row
    Put p1 = new Put(row, ts);
    p1.add(family, qual, Bytes.toBytes("v1"));
    Put p2 = new Put(row, ts + 1);
    p2.add(family, qual, Bytes.toBytes("v2"));

    // setup all the verifiers we need. This is just the same as above, but will be called twice
    // since we need to iterate the batch.

    // get all the underlying kvs for the put
    final List<KeyValue> allKvs = new ArrayList<KeyValue>(2);
    allKvs.addAll(p2.getFamilyMap().get(family));
    allKvs.addAll(p1.getFamilyMap().get(family));

    // setup the verifier for the data we expect to write
    // both puts should be put into a single batch
    final ColumnReference familyRef =
        new ColumnReference(TestEndToEndCoveredColumnsIndexBuilder.family, ColumnReference.ALL_QUALIFIERS);
    VerifyingIndexCodec codec = state.codec;
    // no previous state in the table
    codec.verifiers.add(new ListMatchingVerifier("cleanup state 1", Collections
        .<KeyValue> emptyList(), familyRef));
    codec.verifiers.add(new ListMatchingVerifier("put state 1", p1.getFamilyMap().get(family),
        familyRef));

    codec.verifiers.add(new ListMatchingVerifier("cleanup state 2", p1.getFamilyMap().get(family),
        familyRef));
    // kvs from both puts should be in the table now
    codec.verifiers.add(new ListMatchingVerifier("put state 2", allKvs, familyRef));

    // do the actual put (no indexing will actually be done)
    HTable primary = state.table;
    primary.setAutoFlush(false);
    primary.put(Arrays.asList(p1, p2));
    primary.flushCommits();

    // cleanup after ourselves
    cleanup(state);
  }

  /**
   * @param tableName name of the table to create for the test
   * @return the supporting state for the test
   */
  private TestState setupTest(String tableName) throws IOException {
    byte[] tableNameBytes = Bytes.toBytes(tableName);
    HTableDescriptor desc = new HTableDescriptor(tableNameBytes);
    desc.addFamily(FAM1);
    // add the necessary simple options to create the builder
    Map<String, String> indexerOpts = new HashMap<String, String>();
    // just need to set the codec - we are going to set it later, but we need something here or the
    // initializer blows up.
    indexerOpts.put(CoveredColumnsIndexBuilder.CODEC_CLASS_NAME_KEY,
      CoveredIndexCodecForTesting.class.getName());
    Indexer.enableIndexing(desc, CoveredColumnsIndexBuilder.class, indexerOpts);

    // create the table
    HBaseAdmin admin = UTIL.getHBaseAdmin();
    admin.createTable(desc);
    HTable primary = new HTable(UTIL.getConfiguration(), tableNameBytes);

    // overwrite the codec so we can verify the current state
    HRegion region = UTIL.getMiniHBaseCluster().getRegions(tableNameBytes).get(0);
    Indexer indexer =
        (Indexer) region.getCoprocessorHost().findCoprocessor(Indexer.class.getName());
    CoveredColumnsIndexBuilder builder =
        (CoveredColumnsIndexBuilder) indexer.getBuilderForTesting();
    VerifyingIndexCodec codec = new VerifyingIndexCodec();
    builder.setIndexCodecForTesting(codec);

    // setup the Puts we want to write
    final long ts = System.currentTimeMillis();
    EnvironmentEdge edge = new EnvironmentEdge() {

      @Override
      public long currentTimeMillis() {
        return ts;
      }
    };
    EnvironmentEdgeManager.injectEdge(edge);

    return new TestState(primary, codec, ts);
  }

  /**
   * Cleanup the test based on the passed state.
   * @param state
   */
  private void cleanup(TestState state) throws IOException {
    EnvironmentEdgeManager.reset();
    state.table.close();
    UTIL.deleteTable(state.table.getTableName());
  }
}