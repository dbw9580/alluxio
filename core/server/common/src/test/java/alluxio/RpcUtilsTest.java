/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio;

import static org.junit.Assert.assertEquals;

import alluxio.metrics.MetricsSystem;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.math.Quantiles;
import com.google.common.math.Stats;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RpcUtilsTest {
  private static final Logger LOG = LoggerFactory.getLogger(RpcUtilsTest.class);
  private static final String METHOD_NAME = "CheckAccess"; // an arbitrary rpc method name

  private static int sCounter = 0;

  @Test
  public void stressTestCallAndReturn() throws Exception {
    final int delayPerCall = 50_000;
    final int numCallsPerRun = 500_000;
    final int runs = 20;
    final long[] points = new long[runs];
    Stopwatch watch = Stopwatch.createUnstarted();
    for (int i = 0; i < runs; i++) {
      sCounter = 0;
      watch.reset().start();
      for (int j = 0; j < numCallsPerRun; j++) {
        RpcUtils.callAndReturn(LOG,
            () -> delayWithSideEffect(delayPerCall),
            METHOD_NAME,
            true, // irrelevant as no failure should occur here
            "");
      }
      long elapsed = watch.stop().elapsed(TimeUnit.NANOSECONDS);
      assertEquals(numCallsPerRun, sCounter);
      assertEquals(numCallsPerRun * (i + 1), MetricsSystem.timer(METHOD_NAME).getCount());
      System.out.printf("Run %d: %.3fns per call%n", i, elapsed / (double) numCallsPerRun);
      points[i] = elapsed;
      // force a context switch
      Thread.sleep(100);
    }

    List<Integer> indices = ImmutableList.of(25, 50, 75, 95);
    Map<Integer, Double> percentiles = Quantiles.percentiles().indexes(indices).compute(points);
    Stats stats = Stats.of(points);
    System.out.printf("Total: %.2fms%n", stats.sum() / Constants.MS_NANO);
    System.out.printf("Average: %.3fms%n", stats.mean() / Constants.MS_NANO);
    System.out.printf("Stdev: %.3fms%n", stats.populationStandardDeviation() / Constants.MS_NANO);
    System.out.printf("Per call average: %.3fns%n", stats.mean() / numCallsPerRun);
    indices.forEach((i) ->
        System.out.printf("Per call P%d: %.3fns%n", i, percentiles.get(i) / numCallsPerRun));
  }

  /**
   * Delays for some time proportional to {@code iterations} with the side effect of increasing
   * the static counter by 1.
   */
  private static int delayWithSideEffect(final int iterations) {
    sCounter++;
    int counter = iterations;
    for (; counter > 0; counter--) {
      // create some side effect to prevent the loop being optimized out
      sCounter++;
    }
    // subtract and effectively increase sCounter by one
    sCounter -= iterations;
    return 1;
  }
}