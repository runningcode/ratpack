/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.test.internal.leak

import groovy.transform.CompileStatic
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@CompileStatic
class LeakDetection implements TestRule {

  @Override
  Statement apply(Statement base, Description description) {
    new Statement() {
      @Override
      void evaluate() throws Throwable {
        FlaggingResourceLeakDetectorFactory.install()
        checkLeak()
        base.evaluate()
        checkLeak()
      }

      private void checkLeak() {
        def leak = FlaggingResourceLeakDetectorFactory.LEAKS.poll()
        if (leak) {
          def leaks = [leak]
          while (leak) {
            leak = FlaggingResourceLeakDetectorFactory.LEAKS.poll()
            leaks << leak
          }
          throw new IllegalStateException("RESOURCE LEAKS" + leaks.join("\n\n"))
        }
      }
    }
  }

}
