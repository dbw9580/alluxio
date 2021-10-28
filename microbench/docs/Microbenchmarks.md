# Microbenchmarking with JMH

**Table of Contents**

- [What is Microbenchmarking?](#what-is-microbenchmarking)
    - [Compare with stress benchmarks](#compare-with-stress-benchmarks)
- [Why Microbenchmarking?](#why-microbenchmarking)
    - [What microbenchmarks are good for](#what-microbenchmarks-are-good-for)
    - [What microbenchmarks are not good for](#what-microbenchmarks-are-not-good-for)
- [How to write correct microbenchmarks](#how-to-write-correct-microbenchmarks)
    - [Always include a baseline](#always-include-a-baseline)
    - [Avoid common pitfalls](#avoid-common-pitfalls)
- [Examples](#examples)
    - [RPCUtils Benchmark](#rpcutils-benchmark)
    - [RPC Executor Benchmark](#rpc-executor-benchmark)
- [Other Considerations](#other-considerations)

## What is Microbenchmarking?

Microbenchmarking is an approach for precise performance evaluation of an isolated segment of code
at the method, loop, or even statement level. From the
paper *[What’s Wrong with My Benchmark Results? Studying Bad Practices in JMH Benchmarks][paper]* 
(Costa, 2021):

[paper]: https://ieeexplore.ieee.org/document/8747433

> In contrast to stress or load tests, which test the end-to-end performance of a system,
> microbenchmarks are relatively short-running and aim at measuring the fine-grained performance
> of specific units of program code. For instance, a microbenchmark may measure method-level
> execution times of a class, the performance of a specific data structure, or the implementation
> of an algorithm. Consequently, microbenchmarks are typically not used to evaluate system-level
> service level agreements, but rather to ensure the performance of critical low-level code
> components or to compare different implementation alternatives.

### Compare with stress benchmarks

Stress benchmarks are grouped around major system features and high level components, and are
designed to test the maximum performance capacity of a system. Both developers and users can benefit
from them as they provide general performance indicators that are easy to interpret, e.g. the number
of operations achieved during a certain amount of time.

Microbenchmarks, on the other hand, are only meaningful to developers. They are written specifically
around components that are usually internal, stand for abstract concepts that only matter in the
context of programming, or are artificially constructed and not related to any real use cases. The
numbers may not be easy to interpret.

## Why Microbenchmarking?

The performance of the whole system is extremely complicated. It depends on the high level design
and architecture, the implementation of each component, the runtime configurations, and even the OS
and hardware the system is running on. Although it is often a bad design or misconfiguration that
leads to a (critical) end-to-end performance issue, inefficiencies at the component level can be
amplified if coupled with the said bad design or misconfiguration. We need to ensure each component
does not have significant performance issues, so that we can confidently combine them to build more
complex components and the whole system.

### What microbenchmarks are good for

* Safeguard fundamental or performance critical code

  Some components are low-level and heavily depended on, or are used in performance critical paths.
  Changes to these components need to be very careful so that they do not cause a performance
  regression. Microbenchmarks can help detect problems by testing against code before and after the
  changes.

* Compare alternative implementations of a component

  Sometimes specialized implementations are needed for performance purposes (e.g. direct buffer vs
  regular buffer). Microbenchmarks serve as a comparison tool to test how much better the
  specialized implementation performs than a generic implementation, and keep track of the
  performance advantage over time.

### What microbenchmarks are not good for

* Base optimization decisions on microbenchmarks
  > We should forget about small efficiencies, say about 97% of the time: premature optimization 
  > is the root of all evil. -- Donald Knuth

  While Test-Driven Development might be a good idea, Microbenchmark-Driven Development is
  definitely not. Write natural and readable code first, and use microbenchmarks only as a safeguard
  on the most performance-critical parts of the code.

## How to write correct microbenchmarks

Writing correct microbenchmarks can be hard. The rule of thumb is to micro-trust the results of
micro-benchmarks. The following is from the output of every JMH benchmark run:

> REMEMBER: The numbers below are just data. To gain reusable insights, you need to follow up on 
> why the numbers are the way they are. Use profilers (see -prof, -lprof), design factorial 
> experiments, perform baseline and negative tests that provide experimental control, make sure the 
> benchmarking environment is safe on JVM/OS/HW level, ask for reviews from the domain experts. Do 
> not assume the numbers tell you what you want them to tell.

### Always include a baseline

Since the dynamic optimizations JVM does are hard to predict, you can be writing benchmark code that
are not executing as expected. A baseline serves as a fixed point in performance that you can be
absolutely confident about, and if one of the benchmarks outperforms (or underperforms) the
baseline, you can be sure there is something wrong about the benchmark.

### Avoid common pitfalls

This paper, *From the paper What’s Wrong with My Benchmark Results? Studying Bad Practices in JMH
Benchmarks*, details the 5 common pitfalls when writing JMH microbenchmarks. These pitfalls make the
benchmark result unreliable, as they allow JVM to do some optimization that are unwanted for
benchmark code and causes skewed or inaccurate results.

1. Not using the result from a benchmark operation leads to dead code elimination, so benchmarks
   appear incorrectly faster than they should be.
2. Using an accumulator to consume values inside a loop may suffer from skewed results due to loop
   unrolling.
3. Marking benchmark inputs as `final` causes the input to be constant folded.
4. Running fixture methods on every operation invocation bears a heavy JMH overhead.
5. Configuring benchmarks with 0 forks mixes different JVM optimization profiles for different
   benchmark parameters.

The JMH repository has an extensive [list][list] of sample benchmarks that demonstrate the common
pitfalls. They also serve as a tutorial to JMH. Make sure to go through them.

[list]: https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples

Beyond these, some other common bad practices that will hurt the reliability and accuracy of
microbenchmarks are:

1. Running the benchmarks on a noisy machine, like a developer's laptop.
    - It's ok to do so if you are only testing the benchmarks to see if the results it generates
      make sense (i.e. no bugs in benchmarks themselves).
2. Comparing the benchmark results with those from a previous run, maybe with different parameters
   to see how the results vary.
    - Instead, do the comparisons inside the benchmarks, e.g. set different parameters on the state
      object, and let JMH handle them for you.
3. Comparing benchmark runs from two cloud VM instances, even if they are of the same resource
   configurations.
    - Cloud VMs only guarantee that you get the amount of resources promised by the provider, but
      not the identical hardware. Two instances both with 4 cores can have different processor
      models, and their performance can be significantly different.

## Examples

The code for the 2 example benchmarks can be found [here][examples].

[examples]: https://github.com/dbw9580/alluxio/tree/benchmark/rpc-executor/microbench

### RPCUtils Benchmark
  **Background**: a new metric was added to the common `RPCUtils.callAndReturn` method that is used
  in *every* server RPC handler method. We want to be sure adding a metric does not incur too heavy
  a performance overhead.

  The `RPCUtils.callAndReturn` method looks like this:

  ```java
  public static <T> T callAndReturn(
    Logger logger, RpcCallableThrowsIOException<T> callable,
    String methodName, boolean failureOk, String description, Object... args)
  ```

  Each call to this method increase a static metric counter bound to the given RPC method name. The
  proposed change introduces another counter that tracks the total number of RPC calls, regardless
  of the RPC method name.

  It is a static method, has a pretty simple interface, and does not require external dependencies
  other than logging and the said metric system.

  **Benchmark implementation**

  The benchmark is also simple and straightforward. Just compare the performance of this method
  before and after the proposed change:

  ```java
  @Benchmark
  public long testCallAndReturn(BenchParams params) throws Exception {
    return callAndReturn(LOG,
        () -> {
          Blackhole.consumeCPU(params.mDelay);
          return params.mDelay;
        },
        METHOD_NAME,
        true, // irrelevant as no failure should occur here
        "");
  }

  @Benchmark
  public long delayBaseline(BenchParams params) throws Exception {
    Blackhole.consumeCPU(params.mDelay);
    return params.mDelay;
  }

  @State(Scope.Benchmark)
  public static class BenchParams {
    @Param({ "500", "1000", "2000", "4000", "8000", "16000"})
    public long mDelay;
  }
  ```

  A baseline is included to provide a upper bound of the benchmark. Performance score
  of `testCallAndReturn` should be lower than the baseline, otherwise it indicates there's something
  wrong with the benchmark. Note the use of `Blackhole.comsumeCPU` here. This method is provided by
  JMH and is meant to be a more accurate alternative for `for` loops that does spin waiting by
  incrementing a counter (see pitfall No.2).

  We import the `callAndReturn` method directly into our benchmark code, and build and run the
  benchmark with and without the commit that contains the proposed change.

  Output of the benchmark looks like the following:

  ```
  Benchmark                        (mDelay)   Mode  Cnt       Score       Error  Units
  RpcUtilsBench.delayBaseline           500  thrpt    6  882889.213 ±  4287.749  ops/s
  RpcUtilsBench.delayBaseline          1000  thrpt    6  439574.348 ±  1338.270  ops/s
  RpcUtilsBench.delayBaseline          2000  thrpt    6  219351.028 ±  3174.141  ops/s
  RpcUtilsBench.delayBaseline          4000  thrpt    6  109930.276 ±   531.985  ops/s
  RpcUtilsBench.delayBaseline          8000  thrpt    6   54732.489 ±   133.660  ops/s
  RpcUtilsBench.delayBaseline         16000  thrpt    6   27468.895 ±   124.747  ops/s
  RpcUtilsBench.testCallAndReturn       500  thrpt    6  502385.545 ± 28750.758  ops/s
  RpcUtilsBench.testCallAndReturn      1000  thrpt    6  317961.171 ±  3065.558  ops/s
  RpcUtilsBench.testCallAndReturn      2000  thrpt    6  184084.341 ±   728.095  ops/s
  RpcUtilsBench.testCallAndReturn      4000  thrpt    6   99543.659 ±   476.688  ops/s
  RpcUtilsBench.testCallAndReturn      8000  thrpt    6   51786.339 ±  2113.792  ops/s
  RpcUtilsBench.testCallAndReturn     16000  thrpt    6   26587.907 ±   411.168  ops/s
  ```

  After comparing the results from with and without the change, we came to the conclusion that the
  new metric only adds a constant delay, and it is safe to include this change. Details on how to
  reason about the data will go beyond the scope of this article, but those who are interested can
  find them [here][1].

  [1]: https://docs.google.com/spreadsheets/d/1ThgFXkYS_v7tryQkDudApeuW2hCwsC7D/edit#gid=1377711729

### RPC Executor Benchmark
  **Background**: investigation on a user bug report suggests the RPC executor thread pool be
  changed from a `ForkJoinPool` to a `ThreadPoolExecutor`, so that client requests do not exhaust
  threads and make the master hang. The two flavors of executors should perform similarly, although
  they differ in behavior like fairness and blocking. We want to be sure the change does not cause a
  performance regression.

  Unlike the previous example, the RPC executors are part of the RPC infrastructure. It does not
  make sense to test the bare performance of the executors outside of a RPC context; on the other
  hand, involving too many master's logic (fs operations, journaling, etc.) would hinder the very
  purpose of this benchmark, because the unpredictable latency of network and storage can overwhelm
  the tiny performance differences of the two kinds of executors.

  **Benchmark implementation**

  Therefore, we need a real RPC infrastructure that is used by Alluxio masters and clients, while
  bypassing the real RPC handlers of the master by creating a few mock RPC services. This way, we
  can test in a scenario that is close to a real use case, while gain more confidence in the
  benchmark's accuracy.

  This benchmark creates a dummy `CounterService`:

  ```java
  class CounterServiceHandler {
    private long mCount = 0;
    private final Runnable mBlocker;

    public CounterServiceHandler(Runnable blocker) {
      mBlocker = blocker;
    }

    // not real code, but you get the idea
    public CounterResponse getCounter(GetCounterRequest request) {
      mBlocker.run(); // simulate a blocking call
      return CounterResponse.newBuilder().setCount(mCount).build();
    }

    // an increment method that mutates the counter is omitted here
    // there is also a lock that syncs the reads and writes
  }
  ```

  The service gets registered to a real gRPC server:

  ```java
  if (mExecutorFlavor == ForkJoinPool) {
    mExecutor = new ForkJoinPool(/* ... */);
  }
  if (mExecutorFlavor == FixedThreadPool) {
    mExecutor = new ThreadPoolExecutor(/* ... */);
  }
  mBlocker = () -> { Thread.sleep(mBlockTimeMs); };

  GrpcServerBuilder builder = GrpcServerBuilder
      .forAddress(/* ... */)
      .executor(mExecutor)
      .addService(new GrpcService(new CounterServiceHandler(mBlocker)));
  mServer = builder.build();
  mServer.start();
  ```

  The benchmark is the simple part:

  ```java
  @Benchmark
  @Threads(64)
  public long rpcBench(BenchState benchState, 
                       ThreadState threadState) throws Exception {
    return threadState.mClient.getCounter();
  }

  @Benchmark
  @Threads(64)
  public long upperBoundBaseline(BenchState benchState, 
                                 ThreadState threadState) throws Exception {
    benchState.mBlocker.run();
    return 1;
  }
  ```

  where `BenchState` and `ThreadState` are state variables holding benchmark parameters and stuff
  like client and blocker handles.

  The benchmark is executed with 64 threads in parallel, each thread has its own client instance and
  repeatedly calls the RPC method. The baseline is the upper bound of the benchmark, where no real
  RPC communication occurs, and the thread just runs the blocker locally.

  Adjusting how many threads are allowed in the thread pool, we can get a rough idea about
  performance using the two flavors of executors (output is trimmed to save space):

  ```
  Benchmark                             (mExecutorFlavor)  (mNumCoreThreads)     Score      Error  Units
  GRpcExecutorBench.rpcBench                          FJP                  4   279.099 ±    6.355  ops/s
  GRpcExecutorBench.rpcBench                          FJP                  8   653.188 ±   22.372  ops/s
  GRpcExecutorBench.rpcBench                          FJP                 16  1405.916 ±    8.778  ops/s
  GRpcExecutorBench.rpcBench                          FJP                 32  2804.275 ±  259.799  ops/s
  GRpcExecutorBench.rpcBench                          FJP                 64  5106.438 ±  352.552  ops/s
  GRpcExecutorBench.rpcBench                          FTP                  4   360.744 ±   14.278  ops/s
  GRpcExecutorBench.rpcBench                          FTP                  8   728.384 ±   11.121  ops/s
  GRpcExecutorBench.rpcBench                          FTP                 16  1457.495 ±   24.522  ops/s
  GRpcExecutorBench.rpcBench                          FTP                 32  2797.363 ±  103.205  ops/s
  GRpcExecutorBench.rpcBench                          FTP                 64  4951.934 ±  471.800  ops/s
  GRpcExecutorBench.upperBoundBaseline                FJP                  4  5884.300 ±   47.015  ops/s
  GRpcExecutorBench.upperBoundBaseline                FJP                  8  5894.798 ±  111.471  ops/s
  GRpcExecutorBench.upperBoundBaseline                FJP                 16  5901.798 ±  123.169  ops/s
  GRpcExecutorBench.upperBoundBaseline                FJP                 32  5748.888 ± 1038.407  ops/s
  GRpcExecutorBench.upperBoundBaseline                FJP                 64  5819.947 ±   74.418  ops/s
  GRpcExecutorBench.upperBoundBaseline                FTP                  4  5825.197 ±   94.903  ops/s
  GRpcExecutorBench.upperBoundBaseline                FTP                  8  5769.428 ±  258.668  ops/s
  GRpcExecutorBench.upperBoundBaseline                FTP                 16  5738.082 ±  147.788  ops/s
  GRpcExecutorBench.upperBoundBaseline                FTP                 32  5742.435 ±   74.498  ops/s
  GRpcExecutorBench.upperBoundBaseline                FTP                 64  5767.294 ±   95.846  ops/s
  ```

  The results show that the performance of a `ThreadPoolExecutor` is on par with a `ForkJoinPool`.

## Other Considerations

- ### Maintainability
  Microbenchmarks test small units of code that are usually internal and subject to changes like
  refactoring. If significant changes are made to the code that is benchmarked, the benchmarks can
  be hard to reuse and maintain. For example, you have a microbenchmark that compares two
  alternative implementations of a method. If the method has been somehow refactored away, the
  benchmark is now useless, but it does not trigger a compile error, or fail the CI.

