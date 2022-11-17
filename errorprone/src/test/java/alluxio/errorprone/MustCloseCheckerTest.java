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

package alluxio.errorprone;

import static alluxio.errorprone.MustCloseChecker.DIAG_AUTOCLOSEABLE_NOT_IMPLEMENTED;

import com.google.errorprone.CompilationTestHelper;
import com.sun.tools.javac.main.Main;
import org.junit.Before;
import org.junit.Test;

public class MustCloseCheckerTest {
  private com.google.errorprone.CompilationTestHelper mCompilationHelper;

  @Before
  public void setup() {
    mCompilationHelper = CompilationTestHelper.newInstance(MustCloseChecker.class, getClass());
  }

  @Test
  public void autoCloseableNotImplemented() {
    mCompilationHelper
        .addSourceLines("MustCloseNotCloseable.java",
            "package alluxio.errorprone;",
            "import alluxio.errorprone.annotation.MustClose;",
            "",
            "@MustClose",
            "// BUG: Diagnostic matches: AutoCloseableNotImplemented",
            "public class MustCloseNotCloseable {}")
        .expectResult(Main.Result.ERROR)
        .expectErrorMessage("AutoCloseableNotImplemented",
            (msg) -> msg.contains(DIAG_AUTOCLOSEABLE_NOT_IMPLEMENTED))
        .doTest();
  }

  @Test
  public void autoCloseableImplemented() {
    mCompilationHelper
        .addSourceLines("MustCloseAutoCloseableImplemented.java",
            "package alluxio.errorprone;",
            "import alluxio.errorprone.annotation.MustClose;",
            "",
            "@MustClose",
            "public class MustCloseAutoCloseableImplemented implements AutoCloseable {",
            "  @Override",
            "  public void close() {}",
            "}")
        .expectResult(Main.Result.OK)
        .doTest();
  }

  @Test
  public void closeableImplemented() {
    mCompilationHelper
        .addSourceLines("MustCloseCloseableImplemented.java",
            "package alluxio.errorprone;",
            "import alluxio.errorprone.annotation.MustClose;",
            "import java.io.Closeable;",
            "",
            "@MustClose",
            "public class MustCloseCloseableImplemented implements Closeable {",
            "  @Override",
            "  public void close() {}",
            "}")
        .expectResult(Main.Result.OK)
        .doTest();
  }
}
