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

package alluxio.membership;

import alluxio.wire.WorkerIdentity;
import alluxio.wire.WorkerInfo;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;

/**
 * A bypass no-op membership manager to disable MembershipManager module
 * if used, the original way of using master for registration is leveraged
 * for regression compatibility purpose.
 */
public class MasterMembershipManager implements MembershipManager {

  /**
   * @return MasterMembershipManager
   */
  public static MasterMembershipManager create() {
    return new MasterMembershipManager();
  }

  @Override
  public void join(WorkerInfo worker) throws IOException {
    // NO-OP
  }

  @Override
  public WorkerClusterView getClusterView() throws IOException {
    return new MasterClusterView();
  }

  class MasterClusterView implements WorkerClusterView {
    private final Optional<ClusterViewFilter> mFilter;

    MasterClusterView() {
      mFilter = Optional.empty();
    }

    MasterClusterView(ClusterViewFilter filter) {
      mFilter = Optional.ofNullable(filter);
    }

    @Override
    public Optional<WorkerInfo> getWorkerById(WorkerIdentity workerIdentity) {
      return Optional.empty();
    }

    @Override
    public WorkerClusterView filter(ClusterViewFilter filter) {
      return new MasterClusterView(filter);
    }

    @Override
    public Iterator<WorkerInfo> iterator() {
      return Collections.<WorkerInfo>emptyList().iterator();
    }
  }

  @Override
  public String showAllMembers() {
    return StringUtils.EMPTY;
  }

  @Override
  public void stopHeartBeat(WorkerInfo worker) throws IOException {
    // NO OP
  }

  @Override
  public void decommission(WorkerInfo worker) throws IOException {
    // NO OP
  }

  @Override
  public void close() throws Exception {
    // NO OP
  }
}
