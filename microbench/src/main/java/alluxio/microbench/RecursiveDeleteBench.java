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
import alluxio.annotation.SuppressFBWarnings;
import alluxio.exception.FileDoesNotExistException;
import alluxio.grpc.DeletePOptions;
import alluxio.master.file.DefaultFileSystemMaster;
import alluxio.master.file.PersistJob;
import alluxio.master.file.RpcContext;
import alluxio.master.file.activesync.ActiveSyncManager;
import alluxio.master.file.contexts.DeleteContext;
import alluxio.master.file.meta.Inode;
import alluxio.master.file.meta.InodeDirectory;
import alluxio.master.file.meta.InodeTree;
import alluxio.master.file.meta.LockedInodePath;
import alluxio.master.file.meta.LockedInodePathList;
import alluxio.master.file.meta.MountTable;
import alluxio.master.metastore.InodeStore;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.reflect.FieldUtils;
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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-server")
@Warmup(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 6, time = 3, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
public class RecursiveDeleteBench {

  @State(Scope.Benchmark)
  public static class BenchState {
    private static final long ROOT_INODE_ID = 0xfacad;

    @Param({"4", "16", "64", "256"})
    public long mNumDirectChild;
    @Param({"flat", "nested_1x", "nested_2x"})
    public String mDirLayout;

    private int mTotalDescendents;
    private DefaultFileSystemMaster mMaster;
    private RpcContext mRpcContext;
    private DeleteContext mDeleteContext;
    private LockedInodePath mLockedInodePath;

    // indirect dependencies
    private Inode mRootInode;
    private InodeTree mInodeTree;
    private MountTable mMountTable;
    private InodeStore mInodeStore;

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

    @Setup(Level.Iteration)
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    public void setup() throws Exception {
      // assuming a flat directory
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
      }

      mRpcContext = RpcContext.NOOP;

      InodeDirectory rootDirView = mock(InodeDirectory.class);
      when(rootDirView.getChildCount()).thenReturn(mNumDirectChild);
      when(rootDirView.isDirectChildrenLoaded()).thenReturn(true);
      when(rootDirView.isMountPoint()).thenReturn(false);

      mRootInode = mock(Inode.class);
      when(mRootInode.isDirectory()).thenReturn(true);
      when(mRootInode.asDirectory()).thenReturn(rootDirView);
      when(mRootInode.getId()).thenReturn(ROOT_INODE_ID);
      when(mRootInode.getParentId()).thenReturn(ROOT_INODE_ID);

      mLockedInodePath = mock(LockedInodePath.class);
      when(mLockedInodePath.getInode()).thenReturn(mRootInode);
      when(mLockedInodePath.getLockPattern()).thenReturn(InodeTree.LockPattern.WRITE_EDGE);
      when(mLockedInodePath.fullPathExists()).thenReturn(true);

      DeletePOptions.Builder optionsBuilder =
          DeletePOptions.newBuilder().setRecursive(true).setAlluxioOnly(true);
      mDeleteContext = mock(DeleteContext.class);
      doReturn(optionsBuilder).when(mDeleteContext).getOptions();

      mMaster = mock(DefaultFileSystemMaster.class);
      doCallRealMethod().when(mMaster).deleteInternal(any(), any(), any());

      mInodeTree = mockField("mInodeTree", InodeTree.class);
      when(mInodeTree.isRootId(anyLong())).thenReturn(false);
      when(mInodeTree.getPath(any())).thenReturn(new AlluxioURI("/"));
      doNothing().when(mInodeTree).deleteInode(any(), any(), anyLong());
      final LockedInodePathList list =
          new LockedInodePathList(getLockedInodePathList(mTotalDescendents));
      when(mInodeTree.getDescendants(any())).thenAnswer(invocation -> {
        // pretend we did the heavy lifting of locking all descendents
        // also assume the time complexity of getDescendants() is linear to mTotalDescendents
        Blackhole.consumeCPU(mTotalDescendents);
        return list;
      });

      mInodeStore = mockField("mInodeStore", InodeStore.class);
      when(mInodeStore.hasChildren(rootDirView)).thenReturn(true);

      MountTable.Resolution resolution = mock(MountTable.Resolution.class);
      when(resolution.getUfsMountPointUri()).thenReturn(new AlluxioURI("/whatever"));
      mMountTable = mockField("mMountTable", MountTable.class);
      when(mMountTable.isMountPoint(any())).thenReturn(true);
      when(mMountTable.delete(any(), any(), anyBoolean())).thenReturn(true);
      when(mMountTable.resolve(any())).thenReturn(resolution);

      ActiveSyncManager syncManager = mockField("mSyncManager", ActiveSyncManager.class);
      when(syncManager.isSyncPoint(any())).thenReturn(false);

      mockField("mPersistRequests", new HashMap<Long, alluxio.time.ExponentialTimer>());
      mockField("mPersistJobs", new HashMap<Long, PersistJob>());
    }

    @TearDown(Level.Iteration)
    public void cleanup() throws Exception {
      // verify that some mocked methods indeed get called
//      verify(mMaster).deleteInternal(any(), any(), any());
//      verify(mLockedInodePath).getInode();
//      verify(mInodeTree).getDescendants(any());
//      verify(mMountTable).delete(any(), any(), anyBoolean());
    }

    private List<LockedInodePath> getLockedInodePathList(int numPaths) {
      ArrayList<LockedInodePath> list = new ArrayList<>(numPaths);

      // create a mocked file inode
      Inode inode = mock(Inode.class);
      when(inode.getId()).thenReturn(1234L);
      when(inode.getParentId()).thenReturn(ROOT_INODE_ID);
      when(inode.isPersisted()).thenReturn(false);
      when(inode.isFile()).thenReturn(true);

      LockedInodePath lockedInodePath = mock(LockedInodePath.class);
      when(lockedInodePath.getLockPattern()).thenReturn(InodeTree.LockPattern.WRITE_EDGE);
      try {
        when(lockedInodePath.getInode()).thenReturn(inode);
      } catch (FileDoesNotExistException e) {
        // unreachable
      }

      for (int i = 0; i < numPaths; i++) {
        list.add(lockedInodePath);
      }
      return list;
    }
  }

  @Benchmark
  public void baseline(BenchState state) {
    Blackhole.consumeCPU(state.mNumDirectChild);
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
        //.include(".*dumb.*")
        .shouldDoGC(true)
        .result("results.json")
        .resultFormat(ResultFormatType.JSON)
        .build();
    new Runner(opts).run();
  }
}
