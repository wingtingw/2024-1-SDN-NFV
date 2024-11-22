/*
 * Copyright 2024-present Open Networking Foundation
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
package nycu.winlab.groupmeter;

import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;

import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;

import org.onosproject.net.group.GroupService;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupBucket;
// import org.onosproject.net.group.GroupKey;
import org.onosproject.net.group.DefaultGroupBucket;
import org.onosproject.net.group.DefaultGroupDescription;
import org.onosproject.net.group.GroupBuckets;
import org.onosproject.core.GroupId;
// import org.onosproject.net.group.DefaultGroupKey;

import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterService;
import org.onosproject.net.meter.MeterRequest;
import org.onosproject.net.meter.DefaultMeterRequest;
import org.onosproject.net.meter.Band;
import org.onosproject.net.meter.DefaultBand;

import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.onlab.packet.ARP;
//import org.onlab.packet.IpPrefix;
import com.google.common.collect.ImmutableList;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final NameConfigListener cfgListener = new NameConfigListener();
    private final PacketProcessor p2pProcessor = new PointToPointProcessor();
    private final PacketProcessor arpProcessor = new ProxyArpProcessor();
    private final ConfigFactory<ApplicationId, NameConfig> factory =
        new ConfigFactory<ApplicationId, NameConfig>(APP_SUBJECT_FACTORY, NameConfig.class, "informations") {
            @Override
                public NameConfig createConfig() {
                return new NameConfig();
            }
        };

    private ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected GroupService groupService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MeterService meterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.winlab.groupmeter");
        cfgService.addListener(cfgListener);
        cfgService.registerConfigFactory(factory);
        packetService.addProcessor(p2pProcessor, PacketProcessor.director(2));
        packetService.addProcessor(arpProcessor, PacketProcessor.director(1));
        TrafficSelector.Builder ipv4Selector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(ipv4Selector.build(), PacketPriority.REACTIVE, appId);
        TrafficSelector.Builder arpSelector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_ARP)
            .matchArpOp(ARP.OP_REQUEST);
        packetService.requestPackets(arpSelector.build(), PacketPriority.REACTIVE, appId);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.removeListener(cfgListener);
        cfgService.unregisterConfigFactory(factory);
        packetService.removeProcessor(p2pProcessor);
        packetService.removeProcessor(arpProcessor);
        TrafficSelector.Builder ipv4Selector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(ipv4Selector.build(), PacketPriority.REACTIVE, appId);
        TrafficSelector.Builder arpSelector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP);
        packetService.cancelPackets(arpSelector.build(), PacketPriority.REACTIVE, appId);
        flowRuleService.removeFlowRulesById(appId);
        log.info("Stopped");
    }

    // implement NameConfigListener
    private class NameConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                && event.configClass().equals(NameConfig.class)) {
                NameConfig config = cfgService.getConfig(appId, NameConfig.class);
                if (config == null) {
                    log.error("config return empty");
                }
                if (config != null) {
                    ConnectPoint h1ConnectPoint = ConnectPoint.deviceConnectPoint(config.getHost1());
                    ConnectPoint h2ConnectPoint = ConnectPoint.deviceConnectPoint(config.getHost2());
                    MacAddress mac1 = MacAddress.valueOf(config.getMac1());
                    MacAddress mac2 = MacAddress.valueOf(config.getMac2());
                    IpAddress ip1 = IpAddress.valueOf(config.getIp1());
                    IpAddress ip2 = IpAddress.valueOf(config.getIp2());

                    // logs
                    log.info("Configuration received:");
                    log.info("ConnectPoint_h1: {}, ConnectPoint_h2: {}", config.getHost1(), config.getHost2());
                    log.info("MacAddress_h1: {}, MacAddress_h2: {}", config.getMac1(), config.getMac2());
                    log.info("IpAddress_h1: {}, IpAddress_h2: {}", config.getIp1(), config.getIp2());

                    // s1
                    DeviceId s1DeviceId = DeviceId.deviceId("of:0000000000000001");
                    GroupDescription failoverGroup = createFailoverGroup(s1DeviceId);
                    groupService.addGroup(failoverGroup);
                    FlowRule s1FlowRule = createFlowRuleForS1(s1DeviceId, GroupId.valueOf(1));
                    flowRuleService.applyFlowRules(s1FlowRule);
                    log.info("group and flow rule added on s1");

                    // s4
                    DeviceId s4DeviceId = DeviceId.deviceId("of:0000000000000004");
                    MeterRequest meterRequest = createMeterRequest(s4DeviceId);
                    meterService.submit(meterRequest);

                    MeterId s4MeterId = MeterId.meterId(1);
                    FlowRule s4FlowRule = createFlowRuleForS4(s4DeviceId, s4MeterId, mac2);
                    flowRuleService.applyFlowRules(s4FlowRule);
                    log.info("meter and flow rule added on s4");

                    // Retrieve the meter to get the MeterId
                    /*Meter meter = meterService.getMeters(s4DeviceId).stream()
                        .filter(m -> m.appId().equals(appId))
                        .findFirst().orElse(null);

                    if (meter != null) {
                        MeterId s4MeterId = meter.id();
                        FlowRule s4FlowRule = createFlowRuleForS4(s4DeviceId, s4MeterId, mac1);
                        flowRuleService.applyFlowRules(s4FlowRule);
                        log.info("Meter and flow rule added on s4");
                    } else {
                        log.error("Failed to retrieve MeterId for device {}", s4DeviceId);
                    }*/

                }
            }
        }
    }
    // s1, group, flow rule
    private GroupDescription createFailoverGroup(DeviceId deviceId) {
        GroupId groupId = GroupId.valueOf(0);
        GroupBucket bucket1 = DefaultGroupBucket.createFailoverGroupBucket(
            DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(2)).build(),
            PortNumber.portNumber(2),
            groupId
        );

        GroupBucket bucket2 = DefaultGroupBucket.createFailoverGroupBucket(
            DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(3)).build(),
            PortNumber.portNumber(3),
            groupId
        );
        GroupBuckets groupBuckets = new GroupBuckets(Arrays.asList(bucket1, bucket2));
        // GroupKey groupKey = new DefaultGroupKey(appId.name().getBytes());

        return new DefaultGroupDescription(
            deviceId,
            GroupDescription.Type.FAILOVER,
            groupBuckets,
            null,
            1,
            appId
        );
    }
    private FlowRule createFlowRuleForS1(DeviceId s1DeviceId, GroupId groupId) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchInPort(PortNumber.portNumber(1))
            .matchEthType(Ethernet.TYPE_IPV4)
            .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .group(groupId)
            .build();

        return DefaultFlowRule.builder()
            .forDevice(s1DeviceId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(50000)
            .fromApp(appId)
            .makePermanent()
            .build();
    }
    private MeterRequest createMeterRequest(DeviceId deviceId) {
        return DefaultMeterRequest.builder()
            .fromApp(appId)
            .forDevice(deviceId)
            .withUnit(Meter.Unit.KB_PER_SEC)
            .withBands(ImmutableList.of(DefaultBand.builder()
                .ofType(Band.Type.DROP)
                .withRate(512)
                .burstSize(1024)
                .build()))
            .add();
    }
    private FlowRule createFlowRuleForS4(DeviceId s4DeviceId, MeterId meterId, MacAddress mac2) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchEthDst(mac2)
            .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .meter(meterId)
            .setOutput(PortNumber.portNumber(2))
            .build();

        return DefaultFlowRule.builder()
            .forDevice(s4DeviceId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(40000)
            .fromApp(appId)
            .makePermanent()
            .build();
    }

    // p2p intent
    private class PointToPointProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            log.info("intent!!!");
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            ConnectPoint ingress = pkt.receivedFrom();
            ConnectPoint egress = null;
            NameConfig config = cfgService.getConfig(appId, NameConfig.class);
            if (config == null) {
                log.info("config empty");
            }
            if (config != null) {
                if (dstMac.equals(MacAddress.valueOf(config.getMac1()))) {
                    egress = ConnectPoint.deviceConnectPoint(config.getHost1());
                } else if (dstMac.equals(MacAddress.valueOf(config.getMac2()))) {
                    egress = ConnectPoint.deviceConnectPoint(config.getHost2());
                }
            }
            if (egress != null) {
                PointToPointIntent intent = createPointToPointIntent(ingress, egress, dstMac);
                intentService.submit(intent);
                log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted",
                    ingress.deviceId(), ingress.port(), egress.deviceId(), egress.port());
            } else {
                log.warn("Egress point for destination MAC {} not found", dstMac);
            }
        }
    }
    private PointToPointIntent createPointToPointIntent(ConnectPoint ingress, ConnectPoint egress, MacAddress dstMac) {
        return PointToPointIntent.builder()
            .appId(appId)
            .filteredIngressPoint(new FilteredConnectPoint(ingress))
            .filteredEgressPoint(new FilteredConnectPoint(egress))
            .selector(DefaultTrafficSelector.builder()
                .matchEthDst(dstMac)
                .build())
            .build();
    }
    // proxy arp
    private class ProxyArpProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            MacAddress srcMac = ethPkt.getSourceMAC();
            if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                ARP arpPkt = (ARP) ethPkt.getPayload();
                if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
                    // arp reply
                    IpAddress targetIp = IpAddress.valueOf(IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress()));
                    log.info("targetIp = {}", targetIp);
                    NameConfig config = cfgService.getConfig(appId, NameConfig.class);
                    if (config == null) {
                        log.info("config is empty");
                    }
                    if (config != null) {
                        MacAddress mac1 = MacAddress.valueOf(config.getMac1());
                        MacAddress mac2 = MacAddress.valueOf(config.getMac2());
                        IpAddress ip1 = IpAddress.valueOf(config.getIp1());
                        IpAddress ip2 = IpAddress.valueOf(config.getIp2());
                        if (targetIp.equals(ip2)) {
                            log.info("recieved arp request for ip `{}`", targetIp);
                            if (srcMac.equals(mac1)) {
                                Ethernet arpReply = ARP.buildArpReply(targetIp.getIp4Address(), mac2, ethPkt);
                                arpReply.setSourceMACAddress(mac2);
                                arpReply.setDestinationMACAddress(mac1);
                                TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                    .setOutput(context.inPacket().receivedFrom().port())
                                    .build();
                                OutboundPacket outPkt = new DefaultOutboundPacket(
                                    pkt.receivedFrom().deviceId(),
                                    treatment,
                                    ByteBuffer.wrap(arpReply.serialize())
                                );
                                context.block();
                                packetService.emit(outPkt);
                                log.info("sent arp reply for h2's mac");
                            }
                        } else if (targetIp.equals(ip1)) {
                            if (srcMac.equals(mac2)) {
                                Ethernet arpReply = ARP.buildArpReply(targetIp.getIp4Address(), mac1, ethPkt);
                                arpReply.setSourceMACAddress(mac1);
                                arpReply.setDestinationMACAddress(mac2);
                                TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                    .setOutput(context.inPacket().receivedFrom().port())
                                    .build();
                                OutboundPacket outPkt = new DefaultOutboundPacket(
                                    pkt.receivedFrom().deviceId(),
                                    treatment,
                                    ByteBuffer.wrap(arpReply.serialize())
                                );
                                context.block();
                                packetService.emit(outPkt);
                                log.info("sent arp reply for h1's mac");
                            }
                        }
                    }
                }
                return;
            }
        }
    }
}


