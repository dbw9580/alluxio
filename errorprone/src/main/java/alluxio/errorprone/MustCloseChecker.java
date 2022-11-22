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

import alluxio.errorprone.annotation.MustClose;

import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;

/**
 * MustCloseChecker.
 */
@BugPattern(
    name = "MustCloseNotClosed",
    summary = "A type marked as MustClose is not closed",
    severity = SeverityLevel.ERROR
)
public class MustCloseChecker extends BugChecker
    implements BugChecker.ClassTreeMatcher {

  private static final String MUST_CLOSE_CLASS_NAME = MustClose.class.getName();
  private static final Matcher<Tree> IS_AUTOCLOSEABLE = Matchers.isSubtypeOf(AutoCloseable.class);

  static final String DIAG_AUTOCLOSEABLE_NOT_IMPLEMENTED =
      "Annotated with MustClose but AutoCloseable is not implemented";

  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    if (!ASTHelpers.hasAnnotation(tree, MUST_CLOSE_CLASS_NAME, state)) {
      return Description.NO_MATCH;
    }
    if (IS_AUTOCLOSEABLE.matches(tree, state)) {
      return Description.NO_MATCH;
    }
    return buildDescription(tree)
        .setMessage(DIAG_AUTOCLOSEABLE_NOT_IMPLEMENTED)
        .build();
  }
}
