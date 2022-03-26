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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import alluxio.AlluxioURI;
import alluxio.grpc.DeletePOptions;
import alluxio.master.file.DefaultFileSystemMaster;
import alluxio.master.file.PersistJob;
import alluxio.master.file.RpcContext;
import alluxio.master.file.activesync.ActiveSyncManager;
import alluxio.master.file.contexts.DeleteContext;
import alluxio.master.file.meta.InodeDirectory;
import alluxio.master.file.meta.InodeLockManager;
import alluxio.master.file.meta.InodeTree;
import alluxio.master.file.meta.LockedInodePath;
import alluxio.master.file.meta.LockedInodePathList;
import alluxio.master.file.meta.MountTable;
import alluxio.master.metastore.InodeStore;
import alluxio.master.metastore.heap.HeapInodeStore;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-server")
@Warmup(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 6, time = 3, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
public class RecursiveDeleteBench {

  @State(Scope.Benchmark)
  public static class BenchState {
    private DefaultFileSystemMaster mMaster;
    private RpcContext mRpcContext;
    private DeleteContext mDeleteContext;
    private LockedInodePath mLockedInodePath;

    private <T> T mockField(String fieldName, Class<T> cls) throws IllegalAccessException {
      T fieldValue = mock(cls);
      return mockField(fieldName, fieldValue);
    }

    private <T> T mockField(String fieldName, T fieldValue) throws IllegalAccessException {
      Field field = FieldUtils.getDeclaredField(DefaultFileSystemMaster.class, fieldName, true);
      Preconditions.checkNotNull(field, String.format("Field %s not found", field));
      FieldUtils.writeField(field, mMaster, fieldValue, true);
      return fieldValue;
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
      mRpcContext = RpcContext.NOOP;

      InodeDirectory view = mock(InodeDirectory.class);
      doReturn(true).when(view).isMountPoint();
      doReturn(true).when(view).isDirectChildrenLoaded();
      doReturn(0L).when(view).getChildCount();
      mLockedInodePath = new LockedInodePath(
          new AlluxioURI("/"),
          new HeapInodeStore(),
          new InodeLockManager(),
          view,
          InodeTree.LockPattern.WRITE_EDGE,
          true
      );

      DeletePOptions.Builder optionsBuilder =
          DeletePOptions.newBuilder().setRecursive(true).setAlluxioOnly(true);
      mDeleteContext = mock(DeleteContext.class);
      doReturn(optionsBuilder).when(mDeleteContext).getOptions();

      mMaster = mock(DefaultFileSystemMaster.class);
      doCallRealMethod().when(mMaster).deleteInternal(any(), any(), any());

      InodeTree inodeTree = mockField("mInodeTree", InodeTree.class);
      doReturn(false).when(inodeTree).isRootId(anyLong());
      doReturn(new LockedInodePathList(ImmutableList.of())).when(inodeTree).getDescendants(any());
      doReturn(new AlluxioURI("/")).when(inodeTree).getPath(any());
      doNothing().when(inodeTree).deleteInode(any(), any(), anyLong());

      mockField("mInodeStore", InodeStore.class);

      MountTable mountTable = mockField("mMountTable", MountTable.class);
      doReturn(true).when(mountTable).isMountPoint(any());
      doReturn(true).when(mountTable).delete(any(), any(), anyBoolean());

      ActiveSyncManager syncManager = mockField("mSyncManager", ActiveSyncManager.class);
      when(syncManager.isSyncPoint(any())).thenReturn(false);

      mockField("mPersistRequests", new HashMap<Long, alluxio.time.ExponentialTimer>());
      mockField("mPersistJobs", new HashMap<Long, PersistJob>());
    }

    @TearDown(Level.Trial)
    public void cleanup() {
      // do nothing
    }
  }

  @Benchmark
  public void dumb(BenchState state) throws Exception {
    state.mMaster.deleteInternal(state.mRpcContext, state.mLockedInodePath, state.mDeleteContext);
  }

  public static void main(String[] args) throws Exception {
    Options argsCli = new CommandLineOptions(args);
    Options opts = new OptionsBuilder()
        .parent(argsCli)
        .include(RecursiveDeleteBench.class.getName())
        .shouldDoGC(true)
        .result("results.json")
        .resultFormat(ResultFormatType.JSON)
        .build();
    new Runner(opts).run();
  }
}
