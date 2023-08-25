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

package alluxio.client;

import com.google.common.hash.Funnel;
import com.google.common.hash.PrimitiveSink;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Client identity.
 */
public class ClientIdentity {
  private final byte[] mIdentity;

  ClientIdentity(byte[] identity) {
    mIdentity = identity;
  }

  /**
   * Creates an identity from an App ID as a string.
   *
   * @param appId a string representing an App ID
   * @return client identity
   */
  public static ClientIdentity fromAppId(String appId) {
    byte[] bytes = appId.getBytes(StandardCharsets.UTF_8);
    return new ClientIdentity(bytes);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ClientIdentity that = (ClientIdentity) o;
    return Arrays.equals(mIdentity, that.mIdentity);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(mIdentity);
  }

  /**
   * Funnel to hash a client identity.
   */
  public static class ClientIdentityFunnel implements Funnel<ClientIdentity> {

    @Override
    public void funnel(ClientIdentity from, PrimitiveSink into) {
      into.putBytes(from.mIdentity);
    }
  }
}
