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

package alluxio.microbench;

import alluxio.AlluxioURI;
import alluxio.annotation.SuppressFBWarnings;
import alluxio.conf.path.PrefixPathMatcher;

import com.google.common.collect.ImmutableSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.Optional;

public class PrefixMatcherBench {
  @SuppressFBWarnings("UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR")
  @State(Scope.Benchmark)
  public static class BenchState {
    public AlluxioURI mTarget;
    public PrefixPathMatcher mMatcher;

    @Param({"1", "10", "20"})
    public int mTargetDepth;

    @Setup
    public void before() {
      mMatcher = new PrefixPathMatcher(ImmutableSet.of("/", "/1", "/2"));
      StringBuilder targetBuilder = new StringBuilder();
      for (int i = 0; i < mTargetDepth; i++) {
        targetBuilder.append("/").append(i);
      }
      mTarget = new AlluxioURI(targetBuilder.toString());
    }
  }

  @Benchmark
  public Optional<List<String>> testMatch(BenchState state) throws Exception {
    return state.mMatcher.match(state.mTarget);
  }
}
