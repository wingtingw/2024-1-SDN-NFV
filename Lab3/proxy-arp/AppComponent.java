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
package nycu.winlab.proxyarp;

import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;

import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.ARP;


import org.onosproject.net.PortNumber;
// import org.onosproject.net.DeviceId;

import org.onosproject.net.flow.FlowRuleService;

//
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
// import org.onosproject.net.flow.FlowRule;
// import org.onosproject.net.flow.DefaultFlowRule;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;


    private ProxyArpProcessor processor = new ProxyArpProcessor();
    private ApplicationId appId;
    private Map<MacAddress, Ip4Address> arpTable = new HashMap<>();

    @Activate
    protected void activate() {

        // register your app
        appId = coreService.registerApplication("nycu.winlab.proxyarp");

        // add a packet processor to packetService
        packetService.addProcessor(processor, PacketProcessor.director(2));

        // install a flowrule for packet-in
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {

        // remove flowrule installed by your app
        flowRuleService.removeFlowRulesById(appId);

        // remove your packet processor
        packetService.removeProcessor(processor);
        processor = null;

        // remove flowrule you installed for packet-in
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);

        log.info("Stopped");
    }

    private class ProxyArpProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            // Stop processing if the packet has been handled, since we
            // can't do any more to it.
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt == null) {
                return;
            }
            // DeviceId recDevId = pkt.receivedFrom().deviceId();
            ARP arpPkt = (ARP) ethPkt.getPayload();
            PortNumber recPort = pkt.receivedFrom().port();
            Ip4Address srcIp = Ip4Address.valueOf(arpPkt.getSenderProtocolAddress());
            Ip4Address dstIp = Ip4Address.valueOf(arpPkt.getTargetProtocolAddress());
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = null;

            if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
                handleArpRequest(context, ethPkt, srcIp, dstIp, srcMac);
            } else if (arpPkt.getOpCode() == ARP.OP_REPLY) {
                handleArpReply(context, srcIp, srcMac);
            }
            // rec packet-in from new device, create new table for it
            // if (bridgeTable.get(recDevId) == null) {
               // bridgeTable.put(recDevId, new HashMap<>());
            // }
// TODO

// arp table miss
// "TABLE MISS. Send request to edge ports"
// onos recieves arp reply from host
// "RECV REPLY. Requested MAC = {}", mac
// arp table hit
// "TABLE HIT. Requested MAC = {}", mac
        }

        private void handleArpRequest(PacketContext context, Ethernet ethPkt,
         Ip4Address srcIp, Ip4Address dstIp, MacAddress srcMac) {
            // add src ip-mac to table
            if (!arpTable.containsKey(srcMac)) {
                arpTable.put(srcMac, srcIp);
                log.info("Added ARP table entry: Mac = {}, Ip = {}", srcMac, srcIp);
            }
            MacAddress dstMac = null;
            for (Map.Entry<MacAddress, Ip4Address> entry : arpTable.entrySet()) {
                if (entry.getValue().equals(dstIp)) {
                    dstMac = entry.getKey();
                    log.info("TABLE HIT. Requested MAC = {}", dstMac);
                    break;
                }
            }
            // table miss
            if (dstMac == null) {
                log.info("TABLE MISS. Send request to edge ports.");
                flood(context); // flood the request
                return;
            }
            // table hit, arp reply
            Ethernet arpReply = ARP.buildArpReply(dstIp, dstMac, ethPkt);
            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(context.inPacket().receivedFrom().port())
                .build();
            packetService.emit(new DefaultOutboundPacket(
                context.inPacket().receivedFrom().deviceId(),
                treatment,
                ByteBuffer.wrap(arpReply.serialize())
            ));
            // log.info("Sent ARP reply for IP = {}, MAC = {}", dstIp, dstMac);
        }

    private void handleArpReply(PacketContext context, Ip4Address srcIp, MacAddress srcMac) {
        if (!arpTable.containsKey(srcMac)) {
            arpTable.put(srcMac, srcIp);
            log.info("RECV REPLY. Requested MAC = {}", srcMac);
        }
    }

    private void flood(PacketContext context) {
        InboundPacket pkt = context.inPacket();
        packetOut(context, PortNumber.FLOOD);
    }

    private void packetOut(PacketContext context, PortNumber dstPort) {
        context.treatmentBuilder().setOutput(dstPort);
        context.send();
    }
// ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    }
}
