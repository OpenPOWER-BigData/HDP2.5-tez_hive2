/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.serviceplugins.api;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Set;

import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.event.VertexState;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.runtime.api.TaskFailureType;


// Do not make calls into this from within a held lock.

// TODO TEZ-2003 (post) TEZ-2665. Move to the tez-api module
public interface TaskCommunicatorContext extends ServicePluginContextBase {

  // TODO TEZ-2003 (post) TEZ-2666 Enhancements to API
  // - Consolidate usage of IDs
  // - Split the heartbeat API to a liveness check and a status update
  // - Rename and consolidate TaskHeartbeatResponse and TaskHeartbeatRequest
  // - Fix taskStarted needs to be invoked before launching the actual task.
  // - Potentially add methods to report availability stats to the scheduler
  // - Report taskSuccess via a method instead of the heartbeat
  // - Add methods to signal container / task state changes
  // - Maybe add book-keeping as a helper library, instead of each impl tracking container to task etc.
  // - Handling of containres / tasks which no longer exist in the system (formalized interface instead of a shouldDie notification)


  /**
   * Get the application attempt id for the running application. Relevant when running under YARN
   *
   * @return the applicationAttemptId for the running app
   */
  ApplicationAttemptId getApplicationAttemptId();

  /**
   * Get credentials associated with the AppMaster
   *
   * @return credentials
   */
  Credentials getAMCredentials();

  /**
   * Check whether a running attempt can commit. This provides a leader election mechanism amongst
   * multiple running attempts
   *
   * @param taskAttemptId the associated task attempt id
   * @return whether the attempt can commit or not
   * @throws IOException
   */
  boolean canCommit(TezTaskAttemptID taskAttemptId) throws IOException;

  /**
   * Mechanism for a {@link TaskCommunicator} to provide updates on a running task, as well as
   * receive new information which may need to be propagated to the task. This includes events
   * generated by the task and events which need to be sent to the task
   * This method must be invoked periodically to receive updates for a running task
   *
   * @param request the update from the running task.
   * @return the response that is requried by the task.
   * @throws IOException
   * @throws TezException
   */
  TaskHeartbeatResponse heartbeat(TaskHeartbeatRequest request) throws IOException, TezException;

  /**
   * Check whether the container is known by the framework. The state of this container is
   * irrelevant
   *
   * @param containerId the relevant container id
   * @return true if the container is known, false if it isn't
   */
  boolean isKnownContainer(ContainerId containerId);

  /**
   * Inform the framework that a task is alive. This needs to be invoked periodically to avoid the
   * task attempt timing out.
   * Invocations to heartbeat provides the same keep-alive functionality
   *
   * @param taskAttemptId the relevant task attempt
   */
  void taskAlive(TezTaskAttemptID taskAttemptId);

  /**
   * Inform the framework that a container is alive. This need to be invoked periodically to avoid
   * the container attempt timing out.
   * Invocations to heartbeat provides the same keep-alive functionality
   *
   * @param containerId the relevant container id
   */
  void containerAlive(ContainerId containerId);

  /**
   * Inform the framework that the task has started execution
   *
   * @param taskAttemptId the relevant task attempt id
   * @param containerId   the containerId in which the task attempt is running
   */
  void taskStartedRemotely(TezTaskAttemptID taskAttemptId, ContainerId containerId);

  /**
   * Inform the framework that a task has been killed
   *
   * @param taskAttemptId        the relevant task attempt id
   * @param taskAttemptEndReason the reason for the task attempt being killed
   * @param diagnostics          any diagnostics messages which are relevant to the task attempt
   *                             kill
   */
  void taskKilled(TezTaskAttemptID taskAttemptId, TaskAttemptEndReason taskAttemptEndReason,
                  @Nullable String diagnostics);

  /**
   * Inform the framework that a task has failed. This, at the moment, is always treated as a
   * an error which will cause a retry of the task to be triggered, if there are enough retry
   * attempts left.
   *
   * @param taskAttemptId        the relevant task attempt id
   * @param taskFailureType      the type of the error
   * @param taskAttemptEndReason the reason for the task failure
   * @param diagnostics          any diagnostics messages which are relevant to the task attempt
   *                             failure
   */
  void taskFailed(TezTaskAttemptID taskAttemptId, TaskFailureType taskFailureType,
                  TaskAttemptEndReason taskAttemptEndReason,
                  @Nullable String diagnostics);

  /**
   * Register to get notifications on updates to the specified vertex. Notifications will be sent
   * via {@link org.apache.tez.runtime.api.InputInitializer#onVertexStateUpdated(org.apache.tez.dag.api.event.VertexStateUpdate)}
   * </p>
   * <p/>
   * This method can only be invoked once. Duplicate invocations will result in an error.
   *
   * @param vertexName the vertex name for which notifications are required.
   * @param stateSet   the set of states for which notifications are required. null implies all
   */
  void registerForVertexStateUpdates(String vertexName, @Nullable Set<VertexState> stateSet);

  /**
   * Get an identifier for the executing context of the DAG.
   * @return a String identifier for the exeucting context.
   */
  String getCurrentAppIdentifier();

  /**
   * Get the name of the Input vertices for the specified vertex.
   * Root Inputs are not returned.
   *
   * @param vertexName the vertex for which source vertex names will be returned
   * @return an Iterable containing the list of input vertices for the specified vertex
   */
  Iterable<String> getInputVertexNames(String vertexName);

  /**
   * Get the total number of tasks in the given vertex
   *
   * @param vertexName the relevant vertex name
   * @return total number of tasks in this vertex
   */
  int getVertexTotalTaskCount(String vertexName);

  /**
   * Get the number of completed tasks for a given vertex
   *
   * @param vertexName the vertex name
   * @return the number of completed tasks for the vertex
   */
  int getVertexCompletedTaskCount(String vertexName);

  /**
   * Get the number of running tasks for a given vertex
   *
   * @param vertexName the vertex name
   * @return the number of running tasks for the vertex
   */
  int getVertexRunningTaskCount(String vertexName);

  /**
   * Get the start time for the first attempt of the specified task
   *
   * @param vertexName the vertex to which the task belongs
   * @param taskIndex  the index of the task
   * @return the start time for the first attempt of the task
   */
  long getFirstAttemptStartTime(String vertexName, int taskIndex);

  /**
   * Get the start time for the currently executing DAG
   *
   * @return time when the current dag started executing
   */
  long getDagStartTime();

}
