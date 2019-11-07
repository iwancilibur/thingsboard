/**
 * Copyright © 2016-2019 The Thingsboard Authors
 *
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
package org.thingsboard.edge.rpc;

import com.google.common.io.Resources;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.edge.exception.EdgeConnectionException;
import org.thingsboard.server.gen.edge.AssetUpdateMsg;
import org.thingsboard.server.gen.edge.ConnectRequestMsg;
import org.thingsboard.server.gen.edge.ConnectResponseCode;
import org.thingsboard.server.gen.edge.ConnectResponseMsg;
import org.thingsboard.server.gen.edge.DashboardUpdateMsg;
import org.thingsboard.server.gen.edge.DeviceUpdateMsg;
import org.thingsboard.server.gen.edge.DownlinkMsg;
import org.thingsboard.server.gen.edge.EdgeConfiguration;
import org.thingsboard.server.gen.edge.EdgeRpcServiceGrpc;
import org.thingsboard.server.gen.edge.EntityViewUpdateMsg;
import org.thingsboard.server.gen.edge.RequestMsg;
import org.thingsboard.server.gen.edge.RequestMsgType;
import org.thingsboard.server.gen.edge.ResponseMsg;
import org.thingsboard.server.gen.edge.RuleChainUpdateMsg;
import org.thingsboard.server.gen.edge.UplinkMsg;
import org.thingsboard.server.gen.edge.UplinkResponseMsg;

import javax.net.ssl.SSLException;
import java.io.File;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
@Slf4j
public class EdgeGrpcClient implements EdgeRpcClient {

    @Value("${cloud.rpc.host}")
    private String rpcHost;
    @Value("${cloud.rpc.port}")
    private int rpcPort;
    @Value("${cloud.rpc.timeout}")
    private int timeoutSecs;
    @Value("${cloud.rpc.ssl.enabled}")
    private boolean sslEnabled;
    @Value("${cloud.rpc.ssl.cert}")
    private String certResource;

    private ManagedChannel channel;

    private StreamObserver<RequestMsg> inputStream;

    @Override
    public void connect(String edgeKey,
                        String edgeSecret,
                        Consumer<UplinkResponseMsg> onUplinkResponse,
                        Consumer<EdgeConfiguration> onEdgeUpdate,
                        Consumer<DeviceUpdateMsg> onDeviceUpdate,
                        Consumer<AssetUpdateMsg> onAssetUpdate,
                        Consumer<EntityViewUpdateMsg> onEntityViewUpdate,
                        Consumer<RuleChainUpdateMsg> onRuleChainUpdate,
                        Consumer<DashboardUpdateMsg> onDashboardUpdate,
                        Consumer<DownlinkMsg> onDownlink,
                        Consumer<Exception> onError) {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(rpcHost, rpcPort).usePlaintext();
        if (sslEnabled) {
            try {
                builder.sslContext(GrpcSslContexts.forClient().trustManager(new File(Resources.getResource(certResource).toURI())).build());
            } catch (URISyntaxException | SSLException e) {
                log.error("Failed to initialize channel!", e);
                throw new RuntimeException(e);
            }
        }
        channel = builder.build();
        EdgeRpcServiceGrpc.EdgeRpcServiceStub stub = EdgeRpcServiceGrpc.newStub(channel);
        log.info("[{}] Sending a connect request to the TB!", edgeKey);
        this.inputStream = stub.handleMsgs(initOutputStream(edgeKey, onUplinkResponse, onEdgeUpdate, onDeviceUpdate, onAssetUpdate, onEntityViewUpdate, onRuleChainUpdate, onDashboardUpdate, onDownlink, onError));
        this.inputStream.onNext(RequestMsg.newBuilder()
                .setMsgType(RequestMsgType.CONNECT_RPC_MESSAGE)
                .setConnectRequestMsg(ConnectRequestMsg.newBuilder().setEdgeRoutingKey(edgeKey).setEdgeSecret(edgeSecret).build())
                .build());
    }

    @Override
    public void disconnect() throws InterruptedException {
        inputStream.onCompleted();
        if (channel != null) {
            channel.shutdown().awaitTermination(timeoutSecs, TimeUnit.SECONDS);
        }
    }

    @Override
    public void sendUplinkMsg(UplinkMsg msg) {
        this.inputStream.onNext(RequestMsg.newBuilder()
                .setMsgType(RequestMsgType.UPLINK_RPC_MESSAGE)
                .setUplinkMsg(msg)
                .build());
    }

    private StreamObserver<ResponseMsg> initOutputStream(String edgeKey,
                                                         Consumer<UplinkResponseMsg> onUplinkResponse,
                                                         Consumer<EdgeConfiguration> onEdgeUpdate,
                                                         Consumer<DeviceUpdateMsg> onDeviceUpdate,
                                                         Consumer<AssetUpdateMsg> onAssetUpdate,
                                                         Consumer<EntityViewUpdateMsg> onEntityViewUpdate,
                                                         Consumer<RuleChainUpdateMsg> onRuleChainUpdate,
                                                         Consumer<DashboardUpdateMsg> onDashboardUpdate,
                                                         Consumer<DownlinkMsg> onDownlink,
                                                         Consumer<Exception> onError) {
        return new StreamObserver<ResponseMsg>() {
            @Override
            public void onNext(ResponseMsg responseMsg) {
                if (responseMsg.hasConnectResponseMsg()) {
                    ConnectResponseMsg connectResponseMsg = responseMsg.getConnectResponseMsg();
                    if (connectResponseMsg.getResponseCode().equals(ConnectResponseCode.ACCEPTED)) {
                        log.info("[{}] Configuration received: {}", edgeKey, connectResponseMsg.getConfiguration());
                        onEdgeUpdate.accept(connectResponseMsg.getConfiguration());
                    } else {
                        log.error("[{}] Failed to establish the connection! Code: {}. Error message: {}.", edgeKey, connectResponseMsg.getResponseCode(), connectResponseMsg.getErrorMsg());
                        onError.accept(new EdgeConnectionException("Failed to establish the connection! Response code: " + connectResponseMsg.getResponseCode().name()));
                    }
                } else if (responseMsg.hasUplinkResponseMsg()) {
                    log.debug("[{}] Uplink response message received {}", edgeKey, responseMsg.getUplinkResponseMsg());
                    onUplinkResponse.accept(responseMsg.getUplinkResponseMsg());
                }  else if (responseMsg.hasDeviceUpdateMsg()) {
                    log.debug("[{}] Device update message received {}", edgeKey, responseMsg.getDeviceUpdateMsg());
                    onDeviceUpdate.accept(responseMsg.getDeviceUpdateMsg());
                } else if (responseMsg.hasAssetUpdateMsg()) {
                    log.debug("[{}] Asset update message received {}", edgeKey, responseMsg.getAssetUpdateMsg());
                    onAssetUpdate.accept(responseMsg.getAssetUpdateMsg());
                } else if (responseMsg.hasEntityViewUpdateMsg()) {
                    log.debug("[{}] EntityView update message received {}", edgeKey, responseMsg.getEntityViewUpdateMsg());
                    onEntityViewUpdate.accept(responseMsg.getEntityViewUpdateMsg());
                } else if (responseMsg.hasRuleChainUpdateMsg()) {
                    log.debug("[{}] Rule Chain udpate message received {}", edgeKey, responseMsg.getRuleChainUpdateMsg());
                    onRuleChainUpdate.accept(responseMsg.getRuleChainUpdateMsg());
                } else if (responseMsg.hasDashboardUpdateMsg()) {
                    log.debug("[{}] Dashboard message received {}", edgeKey, responseMsg.getDashboardUpdateMsg());
                    onDashboardUpdate.accept(responseMsg.getDashboardUpdateMsg());
                } else if (responseMsg.hasDownlinkMsg()) {
                    log.debug("[{}] Downlink message received for rule chain {}", edgeKey, responseMsg.getDownlinkMsg());
                    onDownlink.accept(responseMsg.getDownlinkMsg());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.debug("[{}] The rpc session received an error!", edgeKey, t);
                onError.accept(new RuntimeException(t));
            }

            @Override
            public void onCompleted() {
                log.debug("[{}] The rpc session was closed!", edgeKey);
            }
        };
    }
}