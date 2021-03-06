/*
 * Copyright 2016 the original author or authors.
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

package ratpack.core.service.internal;

import org.slf4j.Logger;
import ratpack.core.server.StartupFailureException;
import ratpack.core.server.internal.DefaultRatpackServer;
import ratpack.core.service.*;
import ratpack.exec.ExecController;
import ratpack.func.Nullable;
import ratpack.func.Predicate;
import ratpack.exec.registry.Registry;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ServicesGraph {

  public static final Logger LOGGER = DefaultRatpackServer.LOGGER;

  private final List<Node> nodes;
  private final AtomicInteger toStartCount = new AtomicInteger();
  private final AtomicInteger toStopCount = new AtomicInteger();
  private final AtomicInteger starting = new AtomicInteger();

  private final CountDownLatch startLatch = new CountDownLatch(1);
  private final CountDownLatch stopLatch = new CountDownLatch(1);
  private final ExecController execController;

  private final AtomicReference<StartupFailureException> failureRef = new AtomicReference<>();
  private volatile boolean startupFailed;

  public ServicesGraph(Registry registry) throws Exception {
    this.nodes = Collections.unmodifiableList(
      StreamSupport.stream(registry.getAll(Service.class).spliterator(), false)
        .map(Node::new)
        .collect(Collectors.toList())
    );
    this.toStartCount.set(nodes.size());
    this.toStopCount.set(nodes.size());
    this.execController = registry.get(ExecController.class);

    defineDependencies(registry);
  }

  private void defineDependencies(Registry registry) throws Exception {
    SpecBacking specBacking = new SpecBacking();
    for (ServiceDependencies dependencies : registry.getAll(ServiceDependencies.class)) {
      dependencies.define(specBacking);
    }
    for (Node node : nodes) {
      DependsOn dependsOn = node.getDependsOn();
      if (dependsOn != null) {
        specBacking.dependsOn(
          s -> node.getImplClass().isInstance(s),
          s -> {
            for (Class<?> dependencyType : dependsOn.value()) {
              if (dependencyType.isInstance(s)) {
                return true;
              }
            }
            return false;
          }
        );
      }
    }
  }

  public synchronized void start(StartEvent startEvent) throws StartupFailureException, InterruptedException {
    if (startLatch.getCount() > 0) {
      if (nodes.isEmpty()) {
        startLatch.countDown();
      } else {
        LOGGER.info("Initializing " + nodes.size() + " services...");
        starting.incrementAndGet();
        //noinspection Convert2streamapi
        for (Node node : nodes) {
          if (node.dependencies.isEmpty()) {
            node.start(startEvent);
          }
        }

        if (starting.decrementAndGet() == 0 && toStartCount.get() > 0) {
          onCycle();
        }
      }
    }

    startLatch.await();
    StartupFailureException startupFailureException = failureRef.get();
    if (startupFailureException != null) {
      stop(new DefaultEvent(startEvent.getRegistry(), false));
      throw startupFailureException;
    }
  }

  public synchronized void stop(StopEvent stopEvent) throws InterruptedException {
    if (stopLatch.getCount() > 0) {
      doStop(stopEvent);
    }
    stopLatch.await();
  }

  private void doStop(StopEvent stopEvent) {
    if (nodes.isEmpty()) {
      stopLatch.countDown();
    } else {
      LOGGER.info("Stopping " + nodes.size() + " services...");
      nodes.forEach(n -> {
        if (n.dependentsToStopCount.get() == 0) {
          n.stop(stopEvent);
        }
      });
    }
  }

  private void serviceDidStart(Node node, StartEvent startEvent) {
    node.dependencies.forEach(Node::dependentStarted);

    if (toStartCount.decrementAndGet() == 0) {
      if (startupFailed) {
        StartupFailureException exception = processFailure();
        failureRef.set(exception);
      }
      startLatch.countDown();
    } else {
      node.dependents.forEach(n -> n.dependencyStarted(startEvent));
      if (starting.decrementAndGet() == 0 && toStartCount.get() > 0) {
        onCycle();
      }
    }
  }

  private void onCycle() {
    String joinedServiceNames = nodes.stream()
      .filter(Node::notStarted)
      .map(n -> n.service.getName())
      .collect(Collectors.joining(", "));

    failureRef.set(new StartupFailureException("dependency cycle detected involving the following services: [" + joinedServiceNames + "]"));
    startLatch.countDown();
  }

  @Nullable
  private StartupFailureException processFailure() {
    StartupFailureException exception = null;
    for (Node node : nodes) {
      if (node.startError != null) {
        StartupFailureException nodeException = new StartupFailureException("Service '" + node.service.getName() + "' failed to start", node.startError);
        if (exception == null) {
          exception = nodeException;
        } else {
          exception.addSuppressed(nodeException);
        }
      }
    }
    return exception;
  }

  private void serviceDidStop(Node node, StopEvent stopEvent) {
    if (toStopCount.decrementAndGet() == 0) {
      stopLatch.countDown();
    } else {
      node.dependencies.forEach(n -> n.dependentStopped(stopEvent));
    }
  }

  private class SpecBacking implements ServiceDependenciesSpec {
    @Override
    public ServiceDependenciesSpec dependsOn(Predicate<? super Service> dependents, Predicate<? super Service> dependencies) throws Exception {
      List<Node> dependentNodes = new ArrayList<>();
      List<Node> dependencyNodes = new ArrayList<>();
      for (Node node : nodes) {
        boolean dependent = false;
        if (dependents.apply(node.service)) {
          dependent = true;
          dependentNodes.add(node);
        }
        if (dependencies.apply(node.service)) {
          if (dependent) {
            throw new IllegalStateException("Service '" + node.service.getName() + "' marked as dependent and dependency");
          }
          dependencyNodes.add(node);
        }
      }
      for (Node dependencyNode : dependencyNodes) {
        for (Node dependentNode : dependentNodes) {
          dependentNode.addDependency(dependencyNode);
          dependencyNode.addDependent(dependentNode);
        }
      }
      return this;
    }
  }

  private class Node {

    private final Service service;
    private final Set<Node> dependents = new HashSet<>();
    private final AtomicInteger dependentsToStopCount = new AtomicInteger();
    private final Set<Node> dependencies = new HashSet<>();
    private final AtomicInteger dependenciesToStartCount = new AtomicInteger();

    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile boolean running;
    private volatile Throwable startError;

    public Node(Service service) {
      this.service = service;
    }

    public DependsOn getDependsOn() {
      return getImplClass().getAnnotation(DependsOn.class);
    }

    private Class<?> getImplClass() {
      return service.getClass();
    }

    public boolean notStarted() {
      return !running;
    }

    public void addDependency(Node node) {
      dependencies.add(node);
      dependenciesToStartCount.incrementAndGet();
    }

    public void addDependent(Node node) {
      dependents.add(node);
    }

    public void dependencyStarted(StartEvent startEvent) {
      if (dependenciesToStartCount.decrementAndGet() == 0) {
        start(startEvent);
      }
    }

    public void dependentStarted() {
      dependentsToStopCount.incrementAndGet();
    }

    public void dependentStopped(StopEvent stopEvent) {
      if (dependentsToStopCount.decrementAndGet() <= 0) {
        stop(stopEvent);
      }
    }

    public void start(StartEvent startEvent) {
      starting.incrementAndGet();
      if (startupFailed) {
        serviceDidStart(this, startEvent);
        return;
      }

      execController.fork()
        .onComplete(e -> {
          if (startError == null) {
            running = true;
          }
          serviceDidStart(this, startEvent);
        })
        .onError(e -> {
          startError = e;
          startupFailed = true;
        })
        .start(e -> service.onStart(startEvent));
    }

    public void stop(StopEvent stopEvent) {
      if (stopped.compareAndSet(false, true)) {
        if (running) {
          execController.fork()
            .onComplete(e ->
              serviceDidStop(this, stopEvent)
            )
            .onError(e ->
              LOGGER.warn("Service '" + service.getName() + "' thrown an exception while stopping.", e)
            )
            .start(e -> service.onStop(stopEvent));
        } else {
          serviceDidStop(this, stopEvent);
        }
      }
    }
  }

  public static boolean isOfType(Service service, Class<?> type) {
    return type.isInstance(service);
  }

}
