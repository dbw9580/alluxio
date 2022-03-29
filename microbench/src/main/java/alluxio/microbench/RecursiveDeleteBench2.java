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

import static alluxio.client.WriteType.MUST_CACHE;
import static alluxio.master.journal.JournalType.NOOP;
import static alluxio.security.authentication.AuthType.NOSASL;

import alluxio.AlluxioURI;
import alluxio.conf.PropertyKey;
import alluxio.conf.ServerConfiguration;
import alluxio.grpc.DeletePOptions;
import alluxio.master.LocalAlluxioMaster;
import alluxio.master.file.DefaultFileSystemMaster;
import alluxio.master.file.FileSystemMaster;
import alluxio.master.file.RpcContext;
import alluxio.master.file.contexts.CompleteFileContext;
import alluxio.master.file.contexts.CreateDirectoryContext;
import alluxio.master.file.contexts.CreateFileContext;
import alluxio.master.file.contexts.DeleteContext;
import alluxio.master.file.meta.InodeTree;
import alluxio.master.file.meta.LockedInodePath;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-server")
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
public class RecursiveDeleteBench2 {
  @State(Scope.Benchmark)
  public static class BenchState {
    public static final AlluxioURI DIR_PATH = new AlluxioURI("/rm-bench");

    @Param({"100", "200", "400"})
    public long mNumDirectChild;
    @Param({"flat", "nested_1x"})
    public String mDirLayout;
    public int mTotalDescendents;

    // cached constants
    private static final CreateDirectoryContext CACHED_CREATE_DIR_CONTEXT =
        CreateDirectoryContext.defaults().setWriteType(MUST_CACHE);
    private static final CreateFileContext CACHED_CREATE_FILE_CONTEXT =
        CreateFileContext.defaults().setWriteType(MUST_CACHE);
    private static final CompleteFileContext CACHED_COMPLETE_FILE_CONTEXT =
        CompleteFileContext.defaults();
    private static final DeleteContext CACHED_DELETE_CONTEXT =
        DeleteContext.create(DeletePOptions.newBuilder().setAlluxioOnly(true).setRecursive(true));

    // accessed by bench methods
    private LocalAlluxioMaster mMockMaster;
    private DefaultFileSystemMaster mFsMaster;
    private InodeTree mInodeTree;

    @Setup(Level.Iteration)
    public void beforeIteration() throws Exception {
      // compute total num of files to create
      switch (mDirLayout) {
        case "flat":
          mTotalDescendents = (int) mNumDirectChild;
          break;
        case "nested_1x":
          mTotalDescendents = (int) (mNumDirectChild * mNumDirectChild);
          break;
        case "nested_2x":
          mTotalDescendents = (int) (mNumDirectChild * mNumDirectChild * mNumDirectChild);
          break;
        default:
          // unreachable
          throw new IllegalArgumentException("Not a valid dir layout option: " + mDirLayout);
      }

      // override some defaults to save time creating files
      ServerConfiguration.set(PropertyKey.SECURITY_AUTHENTICATION_TYPE, NOSASL.getAuthName());
      ServerConfiguration.set(PropertyKey.SECURITY_AUTHORIZATION_PERMISSION_ENABLED, false);
      ServerConfiguration.set(PropertyKey.MASTER_JOURNAL_TYPE, NOOP.toString());
      ServerConfiguration.set(PropertyKey.USER_FILE_WRITE_TYPE_DEFAULT, MUST_CACHE.name());
      ServerConfiguration.set(PropertyKey.USER_FILE_METADATA_SYNC_INTERVAL, -1);
      // no client is involved in this benchmark so do not use op id and retry cache
      ServerConfiguration.set(PropertyKey.USER_FILE_INCLUDE_OPERATION_ID, false);
      ServerConfiguration.set(PropertyKey.MASTER_FILE_SYSTEM_OPERATION_RETRY_CACHE_ENABLED, false);

      // create a single master cluster with no workers
      mMockMaster = LocalAlluxioMaster.create(false);
      mMockMaster.start();
      FileSystemMaster fsMaster =
          mMockMaster.getMasterProcess().getMaster(FileSystemMaster.class);
      Preconditions.checkState(fsMaster instanceof DefaultFileSystemMaster);
      mFsMaster = (DefaultFileSystemMaster) fsMaster;

      mInodeTree = (InodeTree) MethodUtils.invokeMethod(mFsMaster, true, "getInodeTree");
    }

    // NOTE: Level.Invocation is fine since the benchmarked method is usually heavy (> 1ms)
    @Setup(Level.Invocation)
    public void beforeInvocation() throws Exception {
      mFsMaster.createDirectory(DIR_PATH, CACHED_CREATE_DIR_CONTEXT);
      for (int i = 0; i < mTotalDescendents; i++) {
        final AlluxioURI path = DIR_PATH.join(Integer.toString(i));
        mFsMaster.createFile(path, CACHED_CREATE_FILE_CONTEXT);
        mFsMaster.completeFile(path, CACHED_COMPLETE_FILE_CONTEXT);
      }
    }

    @TearDown(Level.Iteration)
    public void afterIteration() throws Exception {
      mMockMaster.stop();
      // release references so the GC will hopefully do its job sooner
      mMockMaster = null;
      mFsMaster = null;
      mInodeTree = null;
    }

    @TearDown(Level.Invocation)
    public void afterInvocation() throws Exception {
      // nothing to do here
    }
  }

  @Benchmark
  public void dumb(BenchState state) throws Exception {
    try (RpcContext rpcContext = state.mFsMaster.createRpcContext(BenchState.CACHED_DELETE_CONTEXT);
         LockedInodePath lockedInodePath = state.mInodeTree.lockInodePath(
             BenchState.DIR_PATH, InodeTree.LockPattern.WRITE_EDGE)) {
      state.mFsMaster.deleteInternal(
          rpcContext,
          lockedInodePath,
          BenchState.CACHED_DELETE_CONTEXT
      );
    }
  }

  public static void main(String[] args) throws Exception {
    Options argsCli = new CommandLineOptions(args);
    Options opts = new OptionsBuilder()
        .parent(argsCli)
        //.include(RecursiveDeleteBench2.class.getName())
        .include(".*RecursiveDeleteBench2.dumb.*")
        .shouldDoGC(true)
        .addProfiler("gc")
        .result("results.json")
        .resultFormat(ResultFormatType.JSON)
        .build();
    new Runner(opts).run();
  }
}
