/*-
 * -\-\-
 * Spotify Styx Common
 * --
 * Copyright (C) 2016 Spotify AB
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

package com.spotify.styx.state;

import static com.spotify.styx.state.RunState.State.DONE;
import static com.spotify.styx.state.RunState.State.ERROR;
import static com.spotify.styx.state.RunState.State.FAILED;
import static com.spotify.styx.state.RunState.State.PREPARE;
import static com.spotify.styx.state.RunState.State.QUEUED;
import static com.spotify.styx.state.RunState.State.RUNNING;
import static com.spotify.styx.state.RunState.State.SUBMITTED;
import static com.spotify.styx.state.RunState.State.SUBMITTING;
import static com.spotify.styx.state.RunState.State.TERMINATED;
import static java.lang.System.currentTimeMillis;
import static java.util.Optional.empty;

import com.google.auto.value.AutoValue;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.EventVisitor;
import com.spotify.styx.model.ExecutionDescription;
import com.spotify.styx.model.TriggerParameters;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.state.Message.MessageLevel;
import com.spotify.styx.util.Time;
import com.spotify.styx.util.TriggerUtil;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * State machine for run states.
 *
 * <p>This implements the following Finite State Transducer (Mealy machine) where the inputs are
 * defined by the {@link Event} enum and outputs defined by the methods of {@link OutputHandler}.
 */
@AutoValue
public abstract class RunState {

  public static final int SUCCESS_EXIT_CODE = 0;
  public static final int MISSING_DEPS_EXIT_CODE = 20;
  public static final int UNRECOVERABLE_FAILURE_EXIT_CODE = 50;
  public static final int UNKNOWN_ERROR_EXIT_CODE = 1;

  private static final double FAILURE_COST = 1.0;
  private static final double MISSING_DEPS_COST = 0.1;
  private static final long NO_EVENTS_PROCESSED = -1L;

  private final EventVisitor<RunState> visitor = new TransitionVisitor();

  public enum State {
    NEW(false),
    QUEUED(false),
    PREPARE(false),
    SUBMITTING(false),
    SUBMITTED(false),
    RUNNING(false),
    TERMINATED(false),
    FAILED(false),
    ERROR(true),
    DONE(true);

    private final boolean terminal;

    State(boolean terminal) {
      this.terminal = terminal;
    }

    public boolean isTerminal() {
      return terminal;
    }
  }

  public abstract WorkflowInstance workflowInstance();
  public abstract State state();
  public abstract long timestamp();
  public abstract StateData data();
  public abstract long counter();

  public static RunState fresh(
      WorkflowInstance workflowInstance,
      Time time) {
    return create(workflowInstance, State.NEW, time.get());
  }

  public static RunState fresh(WorkflowInstance workflowInstance) {
    return fresh(workflowInstance, Instant::now);
  }

  public RunState transition(Event event, Time time) {
    return event.accept(visitor).transitionUpdates(time.get());
  }

  private RunState state(State state, StateData newStateData) {
    return new AutoValue_RunState(
        workflowInstance(), state, timestamp(), newStateData, counter());
  }

  private RunState state(State state) {
    return new AutoValue_RunState(
        workflowInstance(), state, timestamp(), data(), counter());
  }

  private RunState transitionUpdates(Instant instant) {
    return new AutoValue_RunState(
        workflowInstance(), state(), instant.toEpochMilli(), data(), counter() + 1);
  }

  private class TransitionVisitor implements EventVisitor<RunState> {

    @Deprecated
    @Override
    public RunState timeTrigger(WorkflowInstance workflowInstance) {
      switch (state()) {
        case NEW:
          return state( // for backwards compatibility
              SUBMITTED,
              data().builder()
                  .trigger(Trigger.unknown("UNKNOWN"))
                  .triggerId("UNKNOWN") // for backwards compatibility
                  .build());

        default:
          throw illegalTransition("timeTrigger");
      }
    }

    @Override
    public RunState triggerExecution(WorkflowInstance workflowInstance, Trigger trigger,
        TriggerParameters parameters) {
      switch (state()) {
        case NEW:
          return state(
              QUEUED,
              data().builder()
                  .trigger(trigger)
                  .triggerId(TriggerUtil.triggerId(trigger)) // for backwards compatibility
                  .triggerParameters(parameters)
                  .build());

        default:
          throw illegalTransition("triggerExecution");
      }
    }

    @Deprecated
    @Override
    public RunState created(WorkflowInstance workflowInstance, String executionId,
        String dockerImage) {
      switch (state()) {
        case PREPARE:
        case QUEUED:
          return state(
              SUBMITTED, // for backwards compatibility
              data().builder()
                  .executionId(executionId)
                  .executionDescription(ExecutionDescription.forImage(dockerImage))
                  .tries(data().tries() + 1)
                  .build());

        default:
          throw illegalTransition("created");
      }
    }

    @Override
    public RunState info(WorkflowInstance workflowInstance, Message message) {
      switch (state()) {
        case QUEUED:
          return state(
              QUEUED,
              data().builder()
                  .messages(message)
                  .build());

        default:
          throw illegalTransition("info");
      }
    }

    @Override
    public RunState dequeue(WorkflowInstance workflowInstance, Set<String> resourceIds) {
      switch (state()) {
        case QUEUED:
          return state(
              PREPARE,
              data().builder()
                  .retryDelayMillis(empty())
                  .resourceIds(resourceIds)
                  .build());

        default:
          throw illegalTransition("dequeue");
      }
    }

    @Override
    public RunState submit(WorkflowInstance workflowInstance, ExecutionDescription executionDescription,
        String executionId) {
      switch (state()) {
        case QUEUED: // for backwards compatibility
        case PREPARE:
          return state(
              SUBMITTING,
              data().builder()
                  .executionDescription(executionDescription)
                  .executionId(executionId)
                  .build());

        default:
          throw illegalTransition("submit");
      }
    }

    @Override
    public RunState submitted(WorkflowInstance workflowInstance, String executionId, String runnerId) {
      switch (state()) {
        case SUBMITTING:
          return state(
              SUBMITTED,
              data().builder()
                  .tries(data().tries() + 1)
                  // backwards compatibility
                  .executionId(data().executionId().orElse(executionId))
                  .runnerId(runnerId)
                  .build());

        default:
          throw illegalTransition("submitted");
      }
    }

    @Override
    public RunState started(WorkflowInstance workflowInstance) {
      switch (state()) {
        case SUBMITTED:
        case PREPARE:
          return state(RUNNING);

        default:
          throw illegalTransition("started");
      }
    }

    @Override
    public RunState terminate(WorkflowInstance workflowInstance, Optional<Integer> exitCode) {
      switch (state()) {
        case RUNNING:
          final double cost = exitCost(exitCode);
          final int consecutiveFailures = consecutiveFailures(data(), exitCode);
          final MessageLevel level = messageLevel(exitCode);

          final StateData newStateData = data().builder()
              .retryCost(data().retryCost() + cost)
              .lastExit(exitCode)
              .consecutiveFailures(consecutiveFailures)
              .messages(Message.create(level, "Exit code: " + exitCode.map(String::valueOf).orElse("-")))
              .build();

          return state(TERMINATED, newStateData);

        default:
          throw illegalTransition("terminate");
      }
    }

    double exitCost(Optional<Integer> exitCode) {
      return exitCode.map(c -> {
        switch (c) {
          case SUCCESS_EXIT_CODE:      return 0.0;
          case MISSING_DEPS_EXIT_CODE: return MISSING_DEPS_COST;
          default:                     return FAILURE_COST;
        }
      }).orElse(FAILURE_COST);
    }

    int consecutiveFailures(StateData data, Optional<Integer> exitCode) {
      return exitCode.map(c -> {
        switch (c) {
          case SUCCESS_EXIT_CODE:
          case MISSING_DEPS_EXIT_CODE:
            return 0;
          default:
            return data.consecutiveFailures() + 1;
        }
      }).orElse(data.consecutiveFailures() + 1);
    }

    MessageLevel messageLevel(Optional<Integer> exitCode) {
      return exitCode.map(c -> {
        switch (c) {
          case SUCCESS_EXIT_CODE:      return MessageLevel.INFO;
          case MISSING_DEPS_EXIT_CODE: return MessageLevel.WARNING;
          default:                     return MessageLevel.ERROR;
        }
      }).orElse(MessageLevel.ERROR);
    }

    @Override
    public RunState runError(WorkflowInstance workflowInstance, String message) {
      switch (state()) {
        case QUEUED:
        case SUBMITTING:
        case SUBMITTED:
        case RUNNING:
        case PREPARE:
          final StateData newStateData = data().builder()
              .retryCost(data().retryCost() + FAILURE_COST)
              .lastExit(empty())
              .consecutiveFailures(data().consecutiveFailures() + 1)
              .messages(Message.error(message))
              .build();

          return state(FAILED, newStateData);

        default:
          throw illegalTransition("runError");
      }
    }

    @Override
    public RunState success(WorkflowInstance workflowInstance) {
      switch (state()) {
        case TERMINATED:
          return state(DONE);

        default:
          throw illegalTransition("success");
      }
    }

    @Override
    public RunState retryAfter(WorkflowInstance workflowInstance, long delayMillis) {
      switch (state()) {
        case TERMINATED:
        case FAILED:
        case QUEUED:
          return state(
              QUEUED,
              data().builder()
                  .retryDelayMillis(delayMillis)
                  .executionId(empty())
                  .executionDescription(empty())
                  .resourceIds(empty())
                  .build());

        default:
          throw illegalTransition("retryAfter");
      }
    }

    @Deprecated
    @Override
    public RunState retry(WorkflowInstance workflowInstance) {
      switch (state()) {
        case TERMINATED:
        case FAILED:
        case QUEUED:
          return state(PREPARE);

        default:
          throw illegalTransition("retry");
      }
    }

    @Override
    public RunState stop(WorkflowInstance workflowInstance) {
      switch (state()) {
        case TERMINATED:
        case FAILED:
          return state(ERROR);

        default:
          throw illegalTransition("stop");
      }
    }

    @Override
    public RunState timeout(WorkflowInstance workflowInstance) {
      return state(FAILED);
    }

    @Override
    public RunState halt(WorkflowInstance workflowInstance) {
      return state(ERROR);
    }
  }

  private IllegalStateException illegalTransition(String event) {
    return new IllegalStateException(workflowInstance() + " received " + event + " while in " + state());
  }

  public static RunState create(
      WorkflowInstance workflowInstance,
      State state) {
    return create(workflowInstance, state, StateData.zero());
  }

  public static RunState create(
      WorkflowInstance workflowInstance,
      State state,
      Instant timestamp) {
    return create(workflowInstance, state, StateData.zero(), timestamp);
  }

  public static RunState create(
      WorkflowInstance workflowInstance,
      State state,
      Instant timestamp,
      long counter) {
    return create(workflowInstance, state, StateData.zero(), timestamp, counter);
  }

  public static RunState create(
      WorkflowInstance workflowInstance,
      State state,
      StateData stateData) {
    return new AutoValue_RunState(
        workflowInstance, state, currentTimeMillis(), stateData, NO_EVENTS_PROCESSED);
  }

  public static RunState create(
      WorkflowInstance workflowInstance,
      State state,
      StateData stateData,
      Instant timestamp) {
    return new AutoValue_RunState(
        workflowInstance, state, timestamp.toEpochMilli(), stateData, NO_EVENTS_PROCESSED);
  }

  public static RunState create(
      WorkflowInstance workflowInstance,
      State state,
      StateData stateData,
      Instant timestamp,
      long counter) {
    return new AutoValue_RunState(
        workflowInstance, state, timestamp.toEpochMilli(), stateData, counter);
  }
}
