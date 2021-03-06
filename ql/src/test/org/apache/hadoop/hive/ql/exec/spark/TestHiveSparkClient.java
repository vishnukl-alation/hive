/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.spark;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.DriverFactory;
import org.apache.hadoop.hive.ql.IDriver;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.security.authorization.plugin.sqlstd.SQLStdHiveAuthorizerFactory;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hive.spark.client.JobContext;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class TestHiveSparkClient {

  @Test
  public void testSetJobGroupAndDescription() throws Exception {

    HiveConf conf = new HiveConf();
    conf.setVar(HiveConf.ConfVars.HIVE_AUTHORIZATION_MANAGER,
            SQLStdHiveAuthorizerFactory.class.getName());
    conf.setBoolVar(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY, false);
    conf.setVar(HiveConf.ConfVars.HIVE_EXECUTION_ENGINE, "spark");
    conf.set("spark.master", "local");
    conf.set("spark.local.dir", Paths.get(System.getProperty("test.tmp.dir"),
            "TestHiveSparkClient-local-dir").toString());

    SessionState.start(conf);
    FileSystem fs = FileSystem.getLocal(conf);
    Path tmpDir = new Path("TestSparkPlan-tmp");

    IDriver driver = null;
    JavaSparkContext sc = null;

    try {
      driver = DriverFactory.newDriver(conf);
      Assert.assertEquals(0, driver.run("create table test (col int)").getResponseCode());

      String query = "select * from test order by col";
      driver.compile(query);
      List<SparkTask> sparkTasks = Utilities.getSparkTasks(driver.getPlan().getRootTasks());
      Assert.assertEquals(1, sparkTasks.size());

      SparkTask sparkTask = sparkTasks.get(0);

      conf.set(MRJobConfig.JOB_NAME, query);
      JobConf jobConf = new JobConf(conf);

      SparkConf sparkConf = new SparkConf();
      sparkConf.setMaster("local");
      sparkConf.setAppName("TestSparkPlan-app");
      sc = new JavaSparkContext(sparkConf);

      byte[] jobConfBytes = KryoSerializer.serializeJobConf(jobConf);
      byte[] scratchDirBytes = KryoSerializer.serialize(tmpDir);
      byte[] sparkWorkBytes = KryoSerializer.serialize(sparkTask.getWork());

      RemoteHiveSparkClient.JobStatusJob job = new RemoteHiveSparkClient.JobStatusJob(jobConfBytes,
              scratchDirBytes, sparkWorkBytes);

      JobContext mockJobContext = mock(JobContext.class);
      when(mockJobContext.sc()).thenReturn(sc);

      job.call(mockJobContext);

      Assert.assertTrue(sc.getLocalProperty("spark.job.description").contains(query));
      Assert.assertTrue(sc.getLocalProperty("spark.jobGroup.id")
              .contains(sparkTask.getWork().getQueryId()));
    } finally {
      if (driver != null) {
        Assert.assertEquals(0, driver.run("drop table if exists test").getResponseCode());
        driver.destroy();
      }
      if (sc != null) {
        sc.close();
      }
      if (fs.exists(tmpDir)) {
        fs.delete(tmpDir, true);
      }
    }
  }
}
