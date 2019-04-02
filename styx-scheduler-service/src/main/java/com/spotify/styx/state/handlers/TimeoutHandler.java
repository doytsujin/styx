/*-
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2016 - 2019 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx.state.handlers;

import static com.spotify.styx.state.StateUtil.hasTimedOut;

import com.spotify.styx.model.Event;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowConfiguration;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.state.OutputHandler;
import com.spotify.styx.state.RunState;
import com.spotify.styx.state.StateManager;
import com.spotify.styx.state.TimeoutConfig;
import com.spotify.styx.util.Time;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link OutputHandler} that issues {@code timeout} events for instances that have timed out according to the
 * {@link TimeoutConfig} and the {@link WorkflowConfiguration#runningTimeout()}.
 */
public class TimeoutHandler implements OutputHandler {

  private static final Logger log = LoggerFactory.getLogger(TimeoutHandler.class);

  private final TimeoutConfig ttls;
  private final Time time;
  private final StateManager stateManager;
  private final Supplier<Map<WorkflowId, Workflow>> workflows;

  public TimeoutHandler(TimeoutConfig ttls, Time time, StateManager stateManager,
                        Supplier<Map<WorkflowId, Workflow>> workflows) {
    this.ttls = Objects.requireNonNull(ttls, "ttls");
    this.time = Objects.requireNonNull(time, "time");
    this.stateManager = Objects.requireNonNull(stateManager, "stateManager");
    this.workflows = Objects.requireNonNull(workflows, "workflows");
  }

  @Override
  public void transitionInto(RunState runState) {
    var workflow = Optional.ofNullable(workflows.get().get(runState.workflowInstance().workflowId()));
    if (hasTimedOut(workflow, runState, time.get(), ttls.ttlOf(runState.state()))) {
      sendTimeout(runState.workflowInstance(), runState);
    }
  }

  private void sendTimeout(WorkflowInstance workflowInstance, RunState runState) {
    log.info("Found stale state {} since {} for workflow {}; Issuing a timeout",
        runState.state(), Instant.ofEpochMilli(runState.timestamp()), workflowInstance);
    stateManager.receiveIgnoreClosed(Event.timeout(workflowInstance), runState.counter());
  }
}