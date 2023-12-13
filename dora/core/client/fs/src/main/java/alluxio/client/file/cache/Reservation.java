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

package alluxio.client.file.cache;

import alluxio.exception.AlluxioException;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.util.Optional;

public interface Reservation extends CacheUsage, AutoCloseable {
  @Override
  Optional<CacheUsage> partitionedBy(PartitionDescriptor<?> partition);

  /**
   * Reports the space that has been written to.
   *
   * @return space being used
   */
  @Override
  long used();

  /**
   * Reports the space left in this reservation that can be used to append data immediately, without
   * the need to reserve more space.
   *
   * @return space available
   */
  @Override
  long available();

  /**
   * Reports the current size of this reservation, including both used and unused space.
   *
   * @return size of this reservation
   */
  @Override
  long capacity();

  /**
   * Expands the capacity of this reservation so that more space is available.
   * If necessary, pages are evicted from the cache to make the expansion possible.
   *
   * @param bytesToReserve how much more space to expand the reservation
   * @throws ReservationException if failed to expand reserved space
   */
  void expand(long bytesToReserve) throws ReservationException;

  /**
   * Shrinks the size of this reservation and releases space, so that it will be available to
   * other operations and reservations.
   *
   * @param bytesToShrink how much available space to release
   * @throws ReservationException if failed to shrink space
   */
  void shrink(long bytesToShrink) throws ReservationException;

  /**
   * Appends data to the available space.
   *
   * @param data data
   * @throws IOException
   * @throws ReservationException when the data to append is longer than the available space
   */
  void append(ByteBuf data) throws IOException, ReservationException;

  /**
   * Releases this reservation. The data written so far are promoted to permanent pages.
   *
   * @throws Exception
   */
  @Override
  void close() throws Exception;

  public class ReservationException extends AlluxioException {
    protected ReservationException(Throwable cause) {
      super(cause);
    }

    public enum ErrorKind {
      INSUFFICIENT_SPACE,

    }
  }
}
