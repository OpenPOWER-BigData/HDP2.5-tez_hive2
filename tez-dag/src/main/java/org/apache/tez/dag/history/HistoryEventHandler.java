/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.dag.history;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.CompositeService;
import org.apache.tez.common.ReflectionUtils;
import org.apache.tez.dag.api.HistoryLogLevel;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.history.events.DAGSubmittedEvent;
import org.apache.tez.dag.history.logging.HistoryLoggingService;
import org.apache.tez.dag.history.recovery.RecoveryService;
import org.apache.tez.dag.records.TezDAGID;

public class HistoryEventHandler extends CompositeService {

  private static Logger LOG = LoggerFactory.getLogger(HistoryEventHandler.class);

  private final AppContext context;
  private RecoveryService recoveryService;
  private boolean recoveryEnabled;
  private HistoryLoggingService historyLoggingService;

  private HistoryLogLevel amHistoryLogLevel;
  private Map<TezDAGID, HistoryLogLevel> dagIdToLogLevel =
      new ConcurrentHashMap<TezDAGID, HistoryLogLevel>();

  public HistoryEventHandler(AppContext context) {
    super(HistoryEventHandler.class.getName());
    this.context = context;
  }

  @Override
  public void serviceInit(Configuration conf) throws Exception {
    this.recoveryEnabled = context.getAMConf().getBoolean(TezConfiguration.DAG_RECOVERY_ENABLED,
        TezConfiguration.DAG_RECOVERY_ENABLED_DEFAULT);

    String historyServiceClassName = context.getAMConf().get(
        TezConfiguration.TEZ_HISTORY_LOGGING_SERVICE_CLASS,
        TezConfiguration.TEZ_HISTORY_LOGGING_SERVICE_CLASS_DEFAULT);

    LOG.info("Initializing HistoryEventHandler with"
        + "recoveryEnabled=" + recoveryEnabled
        + ", historyServiceClassName=" + historyServiceClassName);

    historyLoggingService =
        ReflectionUtils.createClazzInstance(historyServiceClassName);
    historyLoggingService.setAppContext(context);
    addService(historyLoggingService);

    if (recoveryEnabled) {
      String recoveryServiceClass = conf.get(TezConfiguration.TEZ_AM_RECOVERY_SERVICE_CLASS,
          TezConfiguration.TEZ_AM_RECOVERY_SERVICE_CLASS_DEFAULT);
      recoveryService = ReflectionUtils.createClazzInstance(recoveryServiceClass,
          new Class[]{AppContext.class}, new Object[] {context});
      addService(recoveryService);
    }

    amHistoryLogLevel = HistoryLogLevel.getLogLevel(context.getAMConf(), HistoryLogLevel.DEFAULT);

    super.serviceInit(conf);
  }

  @Override
  public void serviceStart() throws Exception {
    super.serviceStart();
  }

  @Override
  public void serviceStop() throws Exception {
    LOG.info("Stopping HistoryEventHandler");
    super.serviceStop();
  }

  /**
   * Used by events that are critical for recovery
   * DAG Submission/finished and any commit related activites are critical events
   * In short, any events that are instances of SummaryEvent
   * @param event History event
   * @throws IOException
   */
  public void handleCriticalEvent(DAGHistoryEvent event) throws IOException {
    TezDAGID dagId = event.getDagID();
    String dagIdStr = "N/A";
    if(dagId != null) {
      dagIdStr = dagId.toString();
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Handling history event"
          + ", eventType=" + event.getHistoryEvent().getEventType());
    }
    if (recoveryEnabled && event.getHistoryEvent().isRecoveryEvent()) {
      recoveryService.handle(event);
    }
    if (event.getHistoryEvent().isHistoryEvent() && shouldLogEvent(event)) {
      historyLoggingService.handle(event);
    }

    // TODO at some point we should look at removing this once
    // there is a UI in place
    LOG.info("[HISTORY]"
        + "[DAG:" + dagIdStr + "]"
        + "[Event:" + event.getHistoryEvent().getEventType().name() + "]"
        + ": " + event.getHistoryEvent().toString());
  }

  private boolean shouldLogEvent(DAGHistoryEvent event) {
    TezDAGID dagId = event.getDagID();

    HistoryLogLevel dagLogLevel = null;
    if (dagId != null) {
      dagLogLevel = dagIdToLogLevel.get(dagId);
    }
    if (dagLogLevel == null) {
      dagLogLevel = amHistoryLogLevel;
    }

    HistoryEvent historyEvent = event.getHistoryEvent();
    if (historyEvent.getEventType() == HistoryEventType.DAG_SUBMITTED) {
      dagLogLevel = HistoryLogLevel.getLogLevel(((DAGSubmittedEvent)historyEvent).getConf(),
          amHistoryLogLevel);
      dagIdToLogLevel.put(dagId, dagLogLevel);
    } else if (historyEvent.getEventType() == HistoryEventType.DAG_RECOVERED) {
      if (context.getCurrentDAG() != null) {
        dagLogLevel = HistoryLogLevel.getLogLevel(context.getCurrentDAG().getConf(),
            amHistoryLogLevel);
        dagIdToLogLevel.put(dagId, dagLogLevel);
      }
    } else if (historyEvent.getEventType() == HistoryEventType.DAG_FINISHED) {
      if (dagIdToLogLevel.containsKey(dagId)) {
        dagIdToLogLevel.remove(dagId);
      }
    }

    return dagLogLevel.shouldLog(historyEvent.getEventType().getHistoryLogLevel());
  }

  public void handle(DAGHistoryEvent event) {
    try {
      handleCriticalEvent(event);
    } catch (IOException e) {
      LOG.warn("Failed to handle recovery event"
          + ", eventType=" + event.getHistoryEvent().getEventType(), e);
    }
  }

  public boolean hasRecoveryFailed() {
    if (recoveryEnabled) {
      return recoveryService.hasRecoveryFailed();
    } else {
      return false;
    }
  }

}