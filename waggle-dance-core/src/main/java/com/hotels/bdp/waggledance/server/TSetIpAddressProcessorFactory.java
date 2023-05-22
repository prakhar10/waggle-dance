/**
 * Copyright (C) 2016-2023 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hotels.bdp.waggledance.server;

import java.net.Socket;

import com.hotels.bdp.waggledance.util.TrackExecutionTime;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.IHMSHandler;
import org.apache.hadoop.hive.metastore.RetryingHMSHandler;
import org.apache.hadoop.hive.metastore.TSetIpAddressProcessor;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class TSetIpAddressProcessorFactory extends TProcessorFactory {

  private final static Logger log = LoggerFactory.getLogger(TSetIpAddressProcessorFactory.class);
  private final HiveConf hiveConf;
  private final FederatedHMSHandlerFactory federatedHMSHandlerFactory;
  private final TTransportMonitor transportMonitor;

  @Autowired
  public TSetIpAddressProcessorFactory(
      HiveConf hiveConf,
      FederatedHMSHandlerFactory federatedHMSHandlerFactory,
      TTransportMonitor transportMonitor) {
    super(null);
    this.hiveConf = hiveConf;
    this.federatedHMSHandlerFactory = federatedHMSHandlerFactory;
    this.transportMonitor = transportMonitor;
  }

  @TrackExecutionTime
  @Override
  public TProcessor getProcessor(TTransport transport) {
    try {
      if (transport instanceof TSocket) {
        Socket socket = ((TSocket) transport).getSocket();
        log.debug("Received a connection from ip: {}", socket.getInetAddress().getHostAddress());
      }
      CloseableIHMSHandler baseHandler = federatedHMSHandlerFactory.create();

      boolean useSASL = hiveConf.getBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL);
      if (useSASL) {
        IHMSHandler tokenHandler = TokenWrappingHMSHandler.newProxyInstance(baseHandler, useSASL);
        IHMSHandler handler = newRetryingHMSHandler(ExceptionWrappingHMSHandler.newProxyInstance(tokenHandler), hiveConf,
                false);
        return new TSetIpAddressProcessor<>(handler);
      } else {
        IHMSHandler handler = newRetryingHMSHandler(ExceptionWrappingHMSHandler.newProxyInstance(baseHandler), hiveConf,
                false);
        transportMonitor.monitor(transport, baseHandler);
        return new TSetIpAddressProcessor<>(handler);
      }
    } catch (MetaException | ReflectiveOperationException | RuntimeException e) {
      throw new RuntimeException("Error creating TProcessor", e);
    }
  }

  @TrackExecutionTime
  private IHMSHandler newRetryingHMSHandler(IHMSHandler baseHandler, HiveConf hiveConf, boolean local)
    throws MetaException {
    return RetryingHMSHandler.getProxy(hiveConf, baseHandler, local);
  }

}
