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

import alluxio.AbstractClient;
import alluxio.ClientContext;
import alluxio.Constants;
import alluxio.RpcUtils;
import alluxio.concurrent.ForkJoinPoolHelper;
import alluxio.concurrent.jsr.ForkJoinPool;
import alluxio.conf.PropertyKey;
import alluxio.conf.ServerConfiguration;
import alluxio.exception.status.AlluxioStatusException;
import alluxio.grpc.GrpcServer;
import alluxio.grpc.GrpcServerAddress;
import alluxio.grpc.GrpcServerBuilder;
import alluxio.grpc.GrpcService;
import alluxio.grpc.ServiceType;
import alluxio.microbench.grpc_generated.CounterResponse;
import alluxio.microbench.grpc_generated.CounterServiceGrpc;
import alluxio.microbench.grpc_generated.GetCounterRequest;
import alluxio.microbench.grpc_generated.IncrementCounterRequest;
import alluxio.resource.LockResource;
import alluxio.retry.CountingRetry;
import alluxio.security.user.ServerUserState;
import alluxio.util.ThreadFactoryUtils;

import io.grpc.stub.StreamObserver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.CommandLineOptionException;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

@Fork(value = 1, jvmArgsPrepend = "-server")
@Warmup(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 3, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class GRpcExecutorBench {
  public enum ExecutorFlavor {
    FJP, FTP,
  }

  public enum MaxThreadsStrategy {
    EQUAL, MULTIPLY_2X, CONSTANT_500,
  }

  private static final String HOST = "::1";
  private static final InetSocketAddress SOCKET_ADDRESS = new InetSocketAddress(HOST, 10000);

  @Benchmark
  // an arbitrary number of threads that is large enough to exhaust all threads in the pool
  // can be overridden by command line option `-t`
  @Threads(64)
  public long rpcBench(BenchState benchState, ThreadState threadState) throws Exception {
    return threadState.mClient.getCounter();
  }

  /*
   * A baseline that is the upper bound.
   * This executes the blocking operation on the client thread directly, without going through
   * the RPC infrastructure.
   */
  @Benchmark
  @Threads(64)
  public long upperBoundBaseline(BenchState benchState, ThreadState threadState) throws Exception {
    benchState.mFjpManagedBlocker.run();
    return 1;
  }

  @State(Scope.Thread)
  public static class ThreadState {
    private CounterClient mClient;

    @Setup(Level.Trial)
    public void setup() {
      mClient = new CounterClient(ClientContext.create(), SOCKET_ADDRESS);
    }
  }

  @State(Scope.Benchmark)
  public static class BenchState {
    /**
     * Which flavor of executor to use, ForkJoinPool or FixedThreadPool.
     */
    @Param({ "FJP", "FTP" })
    public ExecutorFlavor mExecutorFlavor;
    /**
     * Number of threads in the RPC executor pool.
     */
    @Param({ "4", "8", "16", "32", "64" })
    public int mNumCoreThreads;
    /**
     * Max number of threads allowed in the pool.
     */
    @Param({ "EQUAL" })
    public MaxThreadsStrategy mMaxThreadsStrategy;
    /**
     * How long the dummy RPC service should block, in milliseconds.
     */
    @Param({ "10" })
    public int mBlockTimeMs;

    private ExecutorService mExecutor;
    private GrpcServer mServer;
    private Runnable mFjpManagedBlocker;

    private int getMaxThreads(MaxThreadsStrategy strategy) {
      switch (strategy) {
        case EQUAL:
          return mNumCoreThreads;
        case MULTIPLY_2X:
          return 2 * mNumCoreThreads;
        case CONSTANT_500:
          return 500;
        default:
          throw new IllegalStateException(String.format("Unknown strategy: %s", strategy));
      }
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
      int parallelWidth = Math.min(
          mNumCoreThreads,
          ServerConfiguration.getInt(PropertyKey.MASTER_RPC_EXECUTOR_FJP_PARALLELISM));
      int maxThreads = getMaxThreads(mMaxThreadsStrategy);

      mFjpManagedBlocker = new FjpManagedBlocker(() -> {
        try {
          Thread.sleep(mBlockTimeMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Interrupted when sleeping");
        }
      });

      switch (mExecutorFlavor) {
        case FJP:
          mExecutor = new ForkJoinPool(
              parallelWidth,   // parallelism width
              ThreadFactoryUtils.buildFjp("benchmark-rpc-pool-thread-%d", true), null, true,
              mNumCoreThreads, // #core threads
              maxThreads,      // #max threads
              ServerConfiguration.getInt(
                  PropertyKey.MASTER_RPC_EXECUTOR_FJP_MIN_RUNNABLE), // #min threads
              null,
              ServerConfiguration.getMs(PropertyKey.MASTER_RPC_EXECUTOR_KEEPALIVE),
              TimeUnit.MILLISECONDS);
          break;
        case FTP:
          mExecutor = new ThreadPoolExecutor(
              mNumCoreThreads, // #core threads
              maxThreads,      // #max threads
              0L,              // keepalive time
              TimeUnit.MILLISECONDS,
              //FIXME(bowen): TPE only spawns new threads when the work queue is full.
              // It will never have threads more than corePoolSize, if the work queue is unbounded.
              // See StackOverflow #19528304.
              new LinkedBlockingQueue<>(),
              ThreadFactoryUtils.build("benchmark-rpc-pool-thread-%d", true));
          break;
        default:
          throw new IllegalStateException(
              String.format("Unknown executor flavour: %s", mExecutorFlavor));
      }

      GrpcServerBuilder builder = GrpcServerBuilder
          .forAddress(
              GrpcServerAddress.create(SOCKET_ADDRESS),
              ServerConfiguration.global(),
              ServerUserState.global())
          .executor(mExecutor)
          .addService(
              ServiceType.UNKNOWN_SERVICE,
              new GrpcService(new CounterServiceHandler(mFjpManagedBlocker)));
      mServer = builder.build();
      mServer.start();
    }

    @TearDown(Level.Trial)
    public void cleanup() throws Exception {
      mServer.shutdown();
      if (mExecutor != null && !mExecutor.isTerminated()) {
        mExecutor.shutdownNow();
        if (!mExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          System.err.println("Cannot terminate executor from previous iteration");
        }
      }
    }
  }

  public static void main(String[] args) throws RunnerException, CommandLineOptionException {
    Options argsCli = new CommandLineOptions(args);
    Options opts = new OptionsBuilder()
        .parent(argsCli)
        .include(GRpcExecutorBench.class.getName())
        .shouldDoGC(true)
        .syncIterations(true) // important
        .result("results.json")
        .resultFormat(ResultFormatType.JSON)
        .build();
    new Runner(opts).run();
  }

  static class FjpManagedBlocker implements ForkJoinPool.ManagedBlocker, Runnable {
    private final Runnable mBlocker;

    public FjpManagedBlocker(Runnable blocker) {
      mBlocker = blocker;
    }

    @Override
    public void run() {
      try {
        ForkJoinPoolHelper.safeManagedBlock(this);
      } catch (InterruptedException exc) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted");
      }
    }

    @Override
    public boolean block() throws InterruptedException {
      mBlocker.run();
      return true;
    }

    @Override
    public boolean isReleasable() {
      return false;
    }
  }

  static class CounterClient extends AbstractClient {
    private static final Logger LOG = LoggerFactory.getLogger(CounterClient.class);
    private CounterServiceGrpc.CounterServiceBlockingStub mClient;

    public CounterClient(ClientContext context, InetSocketAddress address) {
      super(context, address);
    }

    public long getCounter() throws AlluxioStatusException {
      return retryRPC(
          new CountingRetry(0), // do not retry
          () -> mClient.getCounter(GetCounterRequest.newBuilder().build()).getCount(),
          LOG,
          "GetCounter",
          ""
      );
    }

    public long increment() throws AlluxioStatusException {
      return retryRPC(
          new CountingRetry(0), // do not retry
          () -> mClient.increment(IncrementCounterRequest.newBuilder().build()).getCount(),
          LOG,
          "Increment",
          ""
      );
    }

    @Override
    protected ServiceType getRemoteServiceType() {
      return ServiceType.UNKNOWN_SERVICE;
    }

    @Override
    protected String getServiceName() {
      return "CounterService";
    }

    @Override
    protected long getServiceVersion() {
      return Constants.UNKNOWN_SERVICE_VERSION;
    }

    @Override
    protected void beforeConnect() throws IOException {
      // the parent method tries to initialize the client by loading config from meta master
      // we don't have a meta master, so just skip that by doing nothing here
    }

    @Override
    protected void afterConnect() throws IOException {
      mClient = CounterServiceGrpc.newBlockingStub(mChannel);
    }
  }

  static class CounterServiceHandler extends CounterServiceGrpc.CounterServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(CounterServiceHandler.class);
    private final ReadWriteLock mLock = new ReentrantReadWriteLock();
    private long mCount = 0;
    /**
     * Creates some delay to simulate a blocking call.
     */
    private final Runnable mBlocker;

    public CounterServiceHandler(@Nullable Runnable blocker) {
      if (blocker == null) {
        mBlocker = () -> { };
      } else {
        mBlocker = blocker;
      }
    }

    @Override
    public void getCounter(GetCounterRequest request,
                           StreamObserver<CounterResponse> responseObserver) {
      RpcUtils.call(
          LOG,
          () -> {
            try (LockResource locked = new LockResource(mLock.readLock())) {
              mBlocker.run();
              return CounterResponse.newBuilder().setCount(mCount).build();
            }
          },
          "GetCounter",
          "counter=%d",
          responseObserver,
          1
      );
    }

    @Override
    public void increment(IncrementCounterRequest request,
                          StreamObserver<CounterResponse> responseObserver) {
      RpcUtils.call(
          LOG,
          () -> {
            try (LockResource locked = new LockResource(mLock.writeLock())) {
              mBlocker.run();
              mCount += 1;
              return CounterResponse.newBuilder().setCount(mCount).build();
            }
          },
          "Increment",
          "counter=%d",
          responseObserver,
          1
      );
    }
  }
}
