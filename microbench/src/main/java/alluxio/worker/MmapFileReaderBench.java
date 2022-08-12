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

package alluxio.worker;

import alluxio.AlluxioTestDirectory;
import alluxio.Constants;
import alluxio.util.io.BufferUtils;
import alluxio.util.io.FileUtils;

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
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.CommandLineOptionException;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A benchmark on read performance between open/read and mmap operations.
 */
@Fork(value = 1, jvmArgsPrepend = "-server")
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
public class MmapFileReaderBench {

  @State(Scope.Benchmark)
  public static class BaseState {
    @Param({"100", "1000", "10000", "100000"})
    public int mFileSizeKb;

    int mFileSize;

    byte[] mBuf;
    byte[] mAnotherBuf;

    public Path mFilePath;

    @Setup(Level.Trial)
    public void setup() throws Exception {
      mFileSize = mFileSizeKb * Constants.KB;
      mBuf = new byte[mFileSize];
      mAnotherBuf = new byte[mFileSize];

      mFilePath = AlluxioTestDirectory.ALLUXIO_TEST_DIRECTORY.toPath().resolve("file");
      FileUtils.createFile(mFilePath.toString());
      byte[] buf = BufferUtils.getIncreasingByteArray(100 * Constants.KB);
      try (OutputStream os = Files.newOutputStream(mFilePath)) {
        for (int i = 0; i < mFileSize; i += buf.length) {
          int length = Math.min(mFileSize - i, buf.length);
          os.write(buf, 0, length);
        }
        os.flush();
      }
    }

    @TearDown(Level.Trial)
    public void cleanup() throws Exception {
      Files.deleteIfExists(mFilePath);
    }
  }

  @State(Scope.Benchmark)
  public static class NormalReadBenchState {
    public RandomAccessFile mFile;

    @Setup(Level.Trial)
    public void setup(BaseState baseState) throws Exception {
      mFile = new RandomAccessFile(baseState.mFilePath.toFile(), "r");
    }

    @TearDown(Level.Trial)
    public void cleanup(BaseState baseState) throws Exception {
      mFile.close();
    }
  }

  @State(Scope.Benchmark)
  public static class MmapBenchState {
    private RandomAccessFile mFile;
    public FileChannel mFileChannel;

    @Setup(Level.Trial)
    public void setup(BaseState baseState) throws Exception {
      mFile = new RandomAccessFile(baseState.mFilePath.toFile(), "r");
      mFileChannel = mFile.getChannel();
    }

    @TearDown(Level.Trial)
    public void cleanup(BaseState baseState) throws Exception {
      mFileChannel.close();
      mFile.close();
    }
  }

  @State(Scope.Benchmark)
  public static class MmapReuseBufferBenchState {
    public ByteBuffer mMappedByteBuffer;

    @Setup(Level.Iteration)
    public void createMapping(BaseState baseState, MmapBenchState mmapBenchState) throws Exception {
      mMappedByteBuffer = mmapBenchState.mFileChannel.map(
          FileChannel.MapMode.READ_ONLY, 0, baseState.mFileSize);
      // prefetch to avoid page faults
      mMappedByteBuffer.get(baseState.mBuf, 0, baseState.mFileSize);
    }
  }

  @Benchmark
  public void readFile(BaseState baseState, NormalReadBenchState state, Blackhole bh)
      throws Exception {
    state.mFile.seek(0);
    state.mFile.readFully(baseState.mBuf, 0, baseState.mFileSize);
    bh.consume(baseState.mBuf);
  }

  @Benchmark
  public void mmapReadNewMapping(BaseState baseState, MmapBenchState state, Blackhole bh)
      throws Exception {
    ByteBuffer buffer =
        state.mFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, baseState.mFileSize);
    buffer.get(baseState.mBuf, 0, baseState.mFileSize);
    bh.consume(baseState.mBuf);
  }

  @Benchmark
  public void mmapReadReusedMapping(BaseState baseState, MmapReuseBufferBenchState state,
      Blackhole bh) throws Exception {
    state.mMappedByteBuffer.rewind();
    state.mMappedByteBuffer.get(baseState.mBuf, 0, baseState.mFileSize);
    bh.consume(baseState.mBuf);
  }

  @Benchmark
  public void baseline(BaseState baseState, Blackhole bh) {
    System.arraycopy(baseState.mBuf, 0, baseState.mAnotherBuf, 0, baseState.mBuf.length);
    bh.consume(baseState.mAnotherBuf);
  }

  public static void main(String[] args) throws RunnerException, CommandLineOptionException {
    Options argsCli = new CommandLineOptions(args);
    Options opts = new OptionsBuilder()
        .parent(argsCli)
        .include(MmapFileReaderBench.class.getName())
        .result("results.json")
        .resultFormat(ResultFormatType.JSON)
        .shouldDoGC(true)
        .build();
    new Runner(opts).run();
  }
}
