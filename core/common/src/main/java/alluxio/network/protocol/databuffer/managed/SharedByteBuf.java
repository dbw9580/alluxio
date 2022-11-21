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

package alluxio.network.protocol.databuffer.managed;

import alluxio.annotation.SuppressFBWarnings;

import io.netty.buffer.AbstractReferenceCountedByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.util.Objects;

/**
 * A shared buffer.
 * @param <OwnerT>
 */
public class SharedByteBuf<OwnerT extends BufOwner<OwnerT>>
    extends AbstractReferenceCountedByteBuf {
  @SuppressFBWarnings("URF_UNREAD_FIELD")
  private final Class<? extends BufOwner<?>> mOwnerClass;

  private final WeakReference<ByteBuf> mBuf;

  protected SharedByteBuf(ByteBuf buf, Class<? extends OwnerT> ownerClass) {
    super(buf.maxCapacity());
    mBuf = new WeakReference<>(buf);
    mOwnerClass = ownerClass;
  }

  /**
   * Upgrades the weak reference to a strong reference, if the referent is still present.
   * Safety: the strong reference must not be returned or held onto.
   * @return the underlying buffer
   */
  private ByteBuf buf() {
    return Objects.requireNonNull(mBuf.get(), "Owner of this SharedByteBuf is gone");
  }

  @Override
  protected byte _getByte(int index) {
    return buf().getByte(index);
  }

  @Override
  protected short _getShort(int index) {
    return buf().getShort(index);
  }

  @Override
  protected short _getShortLE(int index) {
    return buf().getShortLE(index);
  }

  @Override
  protected int _getUnsignedMedium(int index) {
    return buf().getUnsignedMedium(index);
  }

  @Override
  protected int _getUnsignedMediumLE(int index) {
    return buf().getUnsignedMediumLE(index);
  }

  @Override
  protected int _getInt(int index) {
    return buf().getInt(index);
  }

  @Override
  protected int _getIntLE(int index) {
    return buf().getIntLE(index);
  }

  @Override
  protected long _getLong(int index) {
    return buf().getLong(index);
  }

  @Override
  protected long _getLongLE(int index) {
    return buf().getLongLE(index);
  }

  @Override
  protected void _setByte(int index, int value) {
    buf().setByte(index, value);
  }

  @Override
  protected void _setShort(int index, int value) {
    buf().setShort(index, value);
  }

  @Override
  protected void _setShortLE(int index, int value) {
    buf().setShortLE(index, value);
  }

  @Override
  protected void _setMedium(int index, int value) {
    buf().setMedium(index, value);
  }

  @Override
  protected void _setMediumLE(int index, int value) {
    buf().setMediumLE(index, value);
  }

  @Override
  protected void _setInt(int index, int value) {
    buf().setInt(index, value);
  }

  @Override
  protected void _setIntLE(int index, int value) {
    buf().setIntLE(index, value);
  }

  @Override
  protected void _setLong(int index, long value) {
    buf().setLong(index, value);
  }

  @Override
  protected void _setLongLE(int index, long value) {
    buf().setLongLE(index, value);
  }

  @Override
  public int capacity() {
    return buf().capacity();
  }

  @Override
  public ByteBuf capacity(int newCapacity) {
    buf().capacity(newCapacity);
    return this;
  }

  @Override
  public ByteBufAllocator alloc() {
    return buf().alloc();
  }

  @Override
  @Deprecated
  public ByteOrder order() {
    return buf().order();
  }

  @Override
  public ByteBuf unwrap() {
    // do not allow unwrap
    return null;
  }

  @Override
  public boolean isDirect() {
    return buf().isDirect();
  }

  @Override
  public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
    buf().getBytes(index, dst, dstIndex, length);
    return this;
  }

  @Override
  public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
    buf().getBytes(index, dst, dstIndex, length);
    return this;
  }

  @Override
  public ByteBuf getBytes(int index, ByteBuffer dst) {
    buf().getBytes(index, dst);
    return this;
  }

  @Override
  public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
    buf().getBytes(index, out, length);
    return this;
  }

  @Override
  public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
    return buf().getBytes(index, out, length);
  }

  @Override
  public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
    return buf().getBytes(index, out, position, length);
  }

  @Override
  public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
    buf().setBytes(index, src, srcIndex, length);
    return this;
  }

  @Override
  public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
    buf().setBytes(index, src, srcIndex, length);
    return this;
  }

  @Override
  public ByteBuf setBytes(int index, ByteBuffer src) {
    buf().setBytes(index, src);
    return this;
  }

  @Override
  public int setBytes(int index, InputStream in, int length) throws IOException {
    return buf().setBytes(index, in, length);
  }

  @Override
  public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
    return buf().setBytes(index, in, length);
  }

  @Override
  public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
    return buf().setBytes(index, in, position, length);
  }

  // the returned ByteBuf is freshly allocated and not subject to the lifetime constraints of
  // this buffer
  @Override
  public ByteBuf copy(int index, int length) {
    return buf().copy(index, length);
  }

  @Override
  public int nioBufferCount() {
    return buf().nioBufferCount();
  }

  @Override
  public ByteBuffer nioBuffer(int index, int length) {
    return buf().nioBuffer(index, length);
  }

  @Override
  public ByteBuffer internalNioBuffer(int index, int length) {
    return buf().internalNioBuffer(index, length);
  }

  @Override
  public ByteBuffer[] nioBuffers(int index, int length) {
    return buf().nioBuffers(index, length);
  }

  @Override
  public boolean hasArray() {
    // do not allow access to the underlying array as it breaks ownership
    return buf().hasArray();
  }

  // accessing the underlying byte array or ByteBuffer can break the ownership guarantee of
  // SharedByteBuf, if the caller accidentally keeps the reference and uses it after
  // the buffer is released. but for maximal interoperability we allow it.
  // todo(bowen): rethink about the above decision
  @Override
  public byte[] array() {
    return buf().array();
  }

  @Override
  public int arrayOffset() {
    return buf().arrayOffset();
  }

  @Override
  public boolean hasMemoryAddress() {
    return buf().hasMemoryAddress();
  }

  // accessing memory address directly is inherently unsafe, the caller
  // has many more things to worry about than ownership, so we allow it
  // and trust the caller to handle it with extra care
  @Override
  public long memoryAddress() {
    return buf().memoryAddress();
  }

  @Override
  protected void deallocate() {
    // do nothing as we don't own the buffer
  }
}
