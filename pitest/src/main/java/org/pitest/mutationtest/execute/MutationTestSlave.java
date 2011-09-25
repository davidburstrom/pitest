/*
 * Copyright 2010 Henry Coles
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.pitest.mutationtest.execute;

import java.io.IOException;
import java.lang.management.MemoryNotificationInfo;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import org.pitest.boot.HotSwapAgent;
import org.pitest.functional.F2;
import org.pitest.functional.Prelude;
import org.pitest.internal.IsolationUtils;
import org.pitest.mutationtest.instrument.TimeOutDecoratedTestSource;
import org.pitest.mutationtest.mocksupport.BendJavassistToMyWillTransformer;
import org.pitest.util.CommandLineMessage;
import org.pitest.util.ExitCode;
import org.pitest.util.Glob;
import org.pitest.util.Log;
import org.pitest.util.MemoryWatchdog;
import org.pitest.util.SafeDataInputStream;

public class MutationTestSlave {

  private final static Logger LOG = Log.getLogger();

  public static void main(final String[] args) {

    enablePowerMockSupport();

    Socket s = null;
    Reporter r = null;
    try {
      final int port = Integer.valueOf(args[0]);

      s = new Socket("localhost", port);

      final SafeDataInputStream dis = new SafeDataInputStream(
          s.getInputStream());

      final SlaveArguments paramsFromParent = dis.read(SlaveArguments.class);

      Log.setVerbose(paramsFromParent.isVerbose());

      final F2<Class<?>, byte[], Boolean> hotswap = new F2<Class<?>, byte[], Boolean>() {

        public Boolean apply(final Class<?> a, final byte[] b) {
          return HotSwapAgent.hotSwap(a, b);
        }

      };

      r = new DefaultReporter(s.getOutputStream());
      addMemoryWatchDog(r);

      final MutationTestWorker worker = new MutationTestWorker(hotswap,
          paramsFromParent.config.createMutator(IsolationUtils
              .getContextClassLoader()), IsolationUtils.getContextClassLoader());

      worker.run(paramsFromParent.mutations, r, new TimeOutDecoratedTestSource(
          paramsFromParent.timeoutStrategy, paramsFromParent.tests, r));

    } catch (final Exception ex) {
      LOG.log(Level.WARNING, "Error during mutation test", ex);
      if (r != null) {
        r.done();
      }
      safelyCloseSocket(s);
      System.exit(ExitCode.UNKNOWN_ERROR.getCode());
    } finally {
      if (r != null) {
        r.done();
      }

      safelyCloseSocket(s);
    }

  }

  @SuppressWarnings("unchecked")
  private static void enablePowerMockSupport() {
    // Bwahahahahahahaha
    HotSwapAgent.addTransformer(new BendJavassistToMyWillTransformer(Prelude
        .or(new Glob("javassist/*"))));
  }

  private static void safelyCloseSocket(final Socket s) {
    if (s != null) {
      try {
        s.close();
      } catch (final IOException e) {
        LOG.log(Level.WARNING, "Couldn't close scoket", e);
      }
    }
  }

  private static void addMemoryWatchDog(final Reporter r) {
    final NotificationListener listener = new NotificationListener() {

      public void handleNotification(final Notification notification,
          final Object handback) {
        final String type = notification.getType();
        if (type.equals(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED)) {
          final CompositeData cd = (CompositeData) notification.getUserData();
          final MemoryNotificationInfo memInfo = MemoryNotificationInfo
              .from(cd);
          CommandLineMessage.report(memInfo.getPoolName()
              + " has exceeded the shutdown threshold : " + memInfo.getCount()
              + " times.\n" + memInfo.getUsage());

          r.done();
          System.exit(ExitCode.OUT_OF_MEMORY.getCode());

        } else {
          LOG.warning("Unknown notification: " + notification);
        }
      }

    };

    MemoryWatchdog.addWatchDogToAllPools(90, listener);

  }

}