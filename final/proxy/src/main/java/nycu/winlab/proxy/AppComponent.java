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
package nycu.winlab.proxy;

// import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
// import org.onosproject.net.DeviceId;

import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.ARP;
import org.onlab.packet.MacAddress;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
// import org.onosproject.net.Host;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.PortNumber;

import org.onlab.packet.IPv6;
import org.onlab.packet.ICMP6;
// import org.onlab.packet.ndp.NeighborSolicitation;
import org.onlab.packet.ndp.NeighborAdvertisement;
import org.onlab.packet.Ip6Address;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;

import java.nio.ByteBuffer;
// import java.util.Set;
import java.util.HashMap;
import java.util.Map;
// import java.util.Arrays;


@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ProxyConfigListener proxyListener = new ProxyConfigListener();
    private final ConfigFactory<ApplicationId, ProxyConfig> factory = new ConfigFactory<ApplicationId, ProxyConfig>(
        APP_SUBJECT_FACTORY, ProxyConfig.class, "virtual-arps") { //"" "router"
        @Override
        public ProxyConfig createConfig() {
            return new ProxyConfig();
        }
    };

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    MacAddress frrMac;
    MacAddress h1Mac;
   //  MacAddress vgwMac;
    Ip4Address vgwIpv4;
    Ip6Address vgwIpv6;
    private ApplicationId appId;
    private ProxyProcessor processor = new ProxyProcessor();
    private Map<Ip4Address, MacAddress> arpTable = new HashMap<>();
    private Map<Ip6Address, MacAddress> v6Table = new HashMap<>();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.winlab.proxy");

        // Register a packet processor to handle ARP packets
        packetService.addProcessor(processor, PacketProcessor.director(2));

        cfgService.addListener(proxyListener);
        cfgService.registerConfigFactory(factory);

        // Request ARP packets
        TrafficSelector arpselector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_ARP)
                .build();
        packetService.requestPackets(arpselector, PacketPriority.REACTIVE, appId);
        TrafficSelector ndpSelector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV6)
            .matchIPProtocol(IPv6.PROTOCOL_ICMP6)
            .build();
        packetService.requestPackets(ndpSelector, PacketPriority.REACTIVE, appId);

        log.info("proxy started");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        processor = null;

        cfgService.removeListener(proxyListener);
        cfgService.unregisterConfigFactory(factory);

        TrafficSelector arpSelector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_ARP)
            .build();
        packetService.cancelPackets(arpSelector, PacketPriority.REACTIVE, appId);

        TrafficSelector ndpSelector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV6)
            .matchIPProtocol(IPv6.PROTOCOL_ICMP6)
            .build();
        packetService.cancelPackets(ndpSelector, PacketPriority.REACTIVE, appId);

        log.info("proxy stopped");
    }

    private class ProxyConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            /* read config file */
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
             && event.configClass().equals(ProxyConfig.class)) {
                ProxyConfig config = cfgService.getConfig(appId, ProxyConfig.class);
                if (config != null) {
                    log.info("proxy get config info");
                    // vgwMac = config.getVgwMac();
                    vgwIpv4 = config.getVgwIpv4();
                    vgwIpv6 = config.getVgwIpv6();
                    frrMac = config.getFrrMac();
                    h1Mac = config.getH1Mac();
                    //log.info("proxy: get vgwMac = {}, vgwIpv4 = {}, vgwIp46 = {}",
                      //  vgwMac.toString(), vgwIpv4.toString(), vgwIpv6.toString());
                    log.info("get frrMac = {}", frrMac.toString());
                    arpTable.put(Ip4Address.valueOf("192.168.63.1"), frrMac);   // external to sdn
                    arpTable.put(Ip4Address.valueOf("192.168.70.29"), frrMac);
                    arpTable.put(Ip4Address.valueOf("172.16.29.69"), frrMac);
                    arpTable.put(Ip4Address.valueOf("192.168.100.3"), frrMac);
                    arpTable.put(Ip4Address.valueOf("172.16.29.2"), h1Mac);
                    for (Map.Entry<Ip4Address, MacAddress> entry : arpTable.entrySet()) {
                        log.info("IP Addr: {}, MAC Addr: {}", entry.getKey(), entry.getValue());
                    }
                    v6Table.put(Ip6Address.valueOf("fd63::1"), frrMac);
                    v6Table.put(Ip6Address.valueOf("fd70::29"), frrMac);
                    v6Table.put(Ip6Address.valueOf("2a0b:4e07:c4:29::69"), frrMac);
                    v6Table.put(Ip6Address.valueOf("2a0b:4e07:c4:29::2"), h1Mac);
                    
                    for (Map.Entry<Ip6Address, MacAddress> entry : v6Table.entrySet()) {
                        log.info("ipv6 addr: {}, MAC addr: {}", entry.getKey(), entry.getValue());
                    }
                }
             }
        }
    }

    private class ProxyProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                processArpPacket(context, ethPkt);
            } else if (ethPkt.getEtherType() == Ethernet.TYPE_IPV6) {
                processIcmpv6Packet(context, ethPkt);
            }
/*
            if (ethPkt == null) {// || ethPkt.getEtherType() != Ethernet.TYPE_ARP) {
                return;
            }
*/
        }

        private void processArpPacket(PacketContext context, Ethernet ethPkt) {
            ARP arpPacket = (ARP) ethPkt.getPayload();
            short arpOpCode = arpPacket.getOpCode();
            // Ip4Address targetIp = Ip4Address.valueOf(arpPacket.getTargetProtocolAddress());
            Ip4Address srcIp = Ip4Address.valueOf(arpPacket.getSenderProtocolAddress());
            MacAddress srcMac = ethPkt.getSourceMAC();
            //"" added next line
            Ip4Address dstIp = Ip4Address.valueOf(arpPacket.getTargetProtocolAddress());
            MacAddress dstMac = arpTable.get(dstIp);

            if (arpOpCode == ARP.OP_REQUEST) {
                log.info("get arp request");
                // handleArpRequest(context, ethPkt, arpPacket, targetIp);
                //"" commented out next line
                // Ip4Address dstIp = Ip4Address.valueOf(arpPacket.getTargetProtocolAddress());
                if (arpTable.get(srcIp) == null) {
                    log.info("proxy: unknown ip src, put in arp table");
                    arpTable.put(srcIp, srcMac);
                }
                if (dstMac == null) {
                    log.info("TABLE MISS. ignore");
                    // for (ConnectPoint cp : edgePortService)
                    // flood(context);
                } else {
                    log.info("TABLE HIT. source ip= {}, Reqeusted IP = {}, corresponding MAC = {}",
                        srcIp.toString(), dstIp.toString(), dstMac.toString());
                    sendArpReply(context, srcMac, dstMac, dstIp);
                }
            } else if (arpOpCode == ARP.OP_REPLY) {
                if (arpTable.get(srcIp) == null) {
                    arpTable.put(srcIp, srcMac);
                }
                log.info("RECV REPLY. Requested MAC = {}", srcMac.toString());
            }
        }

        private void processIcmpv6Packet(PacketContext context, Ethernet ethPkt) {
            IPv6 ipv6Packet = (IPv6) ethPkt.getPayload();
            if (!(ipv6Packet.getPayload() instanceof ICMP6)) {
                return;
            }

            ICMP6 icmp6Packet = (ICMP6) ipv6Packet.getPayload();
            //"" icmp6Packet."getPayload()""
            // NeighborSolicitation nsPacket = (NeighborSolicitation) icmp6Packet.getPayload();
            //"" srcip method
            Ip6Address srcIp = Ip6Address.valueOf(ipv6Packet.getSourceAddress());
            //Ip6Address srcIp = nsPacket.getSourceAddress();
            MacAddress srcMac = ethPkt.getSourceMAC();
            //"" added next line
            Ip6Address dstIp = Ip6Address.valueOf(ipv6Packet.getDestinationAddress());
            MacAddress dstMac = v6Table.get(dstIp);

            if (icmp6Packet.getIcmpType() == ICMP6.NEIGHBOR_SOLICITATION) {
                // NeighborSolicitation nsPacket = (NeighborSolicitation) icmp6Packet;
                // Ip6Address dstIp = nsPacket.getTargetAddress();
                if (v6Table.get(srcIp) == null) {
                    v6Table.put(srcIp, srcMac);
                }
                if (dstMac == null) {
                    // log.info("TABLE MISS. Send request to edge ports");
                    // flood(context);
                } else {
                    log.info("TABLE HIT. Reqeusted IP = {}, corresponding MAC = {}",
                        dstIp.toString(), dstMac.toString());
                    sendNeighborAdvertisement(context, srcMac, dstMac, dstIp);
                }
                // handleNeighborSolicitation(context, ethPkt, ipv6Packet, (NeighborSolicitation) icmp6Packet);
            } else if (icmp6Packet.getIcmpType() == ICMP6.NEIGHBOR_ADVERTISEMENT) {
                if (v6Table.get(srcIp) == null) {
                    v6Table.put(srcIp, srcMac);
                }
                log.info("RECV REPLY. Request MAC = {}", srcMac.toString());
            }
        }

        // private void handleArpRequest(PacketContext context, Ethernet ethPkt, ARP arpPacket, Ip4Address targetIp) {
        //     MacAddress srcMac = ethPkt.getSourceMAC();

        //     if (targetIp.equals(vgwIpv4)) {
        //         log.info("ARP request for virtual gateway IP: {}", targetIp);
        //         sendArpReply(context, srcMac, vgwMac, vgwIpv4);
        //         return;
        //     }

        //     Set<Host> hosts = hostService.getHostsByIp(targetIp);
        //     if (!hosts.isEmpty()) {
        //         Host host = hosts.iterator().next();
        //         MacAddress hostMac = host.mac();
        //         log.info("ARP request for host IP: {}. Host MAC: {}", targetIp, hostMac);
        //         sendArpReply(context, srcMac, vgwMac, targetIp);
        //         return;
        //     }

        //     log.info("Unknown ARP target IP: {}. Flooding ARP request.", targetIp);
        //     flood(context);
        // }

        private void sendArpReply(PacketContext context, MacAddress targetMac,
            MacAddress senderMac, Ip4Address senderIp) {
            ARP reply = (ARP) new ARP()
                    .setHardwareType(ARP.HW_TYPE_ETHERNET)
                    .setProtocolType(ARP.PROTO_TYPE_IP)
                    .setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH)
                    .setProtocolAddressLength((byte) Ip4Address.BYTE_LENGTH)
                    .setOpCode(ARP.OP_REPLY)
                    .setSenderHardwareAddress(senderMac.toBytes())
                    .setSenderProtocolAddress(senderIp.toInt())
                    .setTargetHardwareAddress(targetMac.toBytes())
                    .setTargetProtocolAddress(Ip4Address.valueOf(targetMac.toBytes()).toInt());

            Ethernet ethReply = new Ethernet();
            ethReply.setSourceMACAddress(senderMac);
            ethReply.setDestinationMACAddress(targetMac);
            ethReply.setEtherType(Ethernet.TYPE_ARP);
            ethReply.setPayload(reply);

            packetService.emit(new DefaultOutboundPacket(
                    context.inPacket().receivedFrom().deviceId(),
                    DefaultTrafficTreatment.builder()
                        .setOutput(context.inPacket().receivedFrom().port()).build(),
                    ByteBuffer.wrap(ethReply.serialize())
            ));

            log.info("Sent ARP reply: {} is at {}", senderIp, senderMac);
        }

        // private void handleNeighborSolicitation(PacketContext context,
            // Ethernet ethPkt, IPv6 ipv6Packet, NeighborSolicitation nsPacket) {
        //     Ip6Address targetIp = nsPacket.getTargetAddress();
        //     MacAddress srcMac = ethPkt.getSourceMAC();

        //     if (targetIp.equals(vgwIpv6)) {
        //         log.info("Neighbor Solicitation for virtual gateway IPv6: {}", targetIp);
        //         sendNeighborAdvertisement(context, srcMac, vgwMac, targetIp);
        //         return;
        //     }

        //     Set<Host> hosts = hostService.getHostsByIp(targetIp);
        //     if (!hosts.isEmpty()) {
        //         Host host = hosts.iterator().next();
        //         MacAddress hostMac = host.mac();
        //         log.info("Neighbor Solicitation for host IPv6: {}. Host MAC: {}", targetIp, hostMac);
        //         sendNeighborAdvertisement(context, srcMac, vgwMac, targetIp);
        //         return;
        //     }

        //     log.info("Unknown Neighbor Solicitation target IPv6: {}. Flooding NS message.", targetIp);
        //     flood(context);
        // }

        private void sendNeighborAdvertisement(PacketContext context, MacAddress targetMac,
            MacAddress senderMac, Ip6Address senderIp) {
            NeighborAdvertisement naPacket = new NeighborAdvertisement()
                    .setRouterFlag((byte) 0) // Not a router
                    .setOverrideFlag((byte) 1) // Override existing cache
                    .setTargetAddress(senderIp.toOctets());
                    //""
                    //.setTargetHardwareAddress(senderMac.toBytes());

            //ICMP6 icmp6Packet = ICMP6.create(ICMP6.NEIGHBOR_ADVERTISEMENT, (byte) 0);
            ICMP6 icmp6Packet = new ICMP6();
            icmp6Packet.setIcmpType(ICMP6.NEIGHBOR_ADVERTISEMENT);
            icmp6Packet.setIcmpCode((byte) 0);
            icmp6Packet.setPayload(naPacket);

            IPv6 ipv6Packet = new IPv6();
            ipv6Packet.setSourceAddress(senderIp.toOctets());
            ipv6Packet.setDestinationAddress(targetMac.toBytes()); // Send directly to the requester
            ipv6Packet.setNextHeader(IPv6.PROTOCOL_ICMP6);
            ipv6Packet.setPayload(icmp6Packet);

            Ethernet ethReply = new Ethernet();
            ethReply.setSourceMACAddress(senderMac);
            ethReply.setDestinationMACAddress(targetMac);
            ethReply.setEtherType(Ethernet.TYPE_IPV6);
            ethReply.setPayload(ipv6Packet);

            // byte[] serializedPacket = ethReply.serialize();
            // int packetSize = serializedPacket.length;
            // InterfaceService interfaceService = DefaultServiceDirextory.getService(InterfaceService.class)
            // DeviceId deviceId = context.inPacket().receivedFrom().deviceId();
            // PortNumber protNumber = context.inPacket().receivedFrom().port();
            // int mtu = interfaceService.getInterfacesByPort(deviceId, PortNumber)
            //     .stream()
            //     .mpToInt(i -> i.mtu())
            //     .findFirst()
            //     .orElse(255)
            // if (packetSize > 255) {
            //     log.info("packet size exceeds 255");
            //     fragment2Send(context, ethReply, 255, deviceId, PortNumber);
            // }

            packetService.emit(new DefaultOutboundPacket(
                    context.inPacket().receivedFrom().deviceId(),
                    DefaultTrafficTreatment.builder().setOutput(context.inPacket().receivedFrom().port()).build(),
                    ByteBuffer.wrap(ethReply.serialize())
            ));

            log.info("Sent Neighbor Advertisement: {} is at {}", senderIp, senderMac);
        }

        // private void fragment2Send(PacketContext context, Ethernet ethFrame, int mtu,
        //         DeviceId deviceId, PortNumber portNumber) {
        //     IPv6 ipv6Packet = (IPv6) ethFrame.getPayload();
        //     byte[] payload = ipv6Packet.getPayload().serialize();
        //     int ipv6HeaderLength = ipv6Packet.getHeaderEtLength();
        //     int fragmentHeaderLength = 8; // Fragment header is 8 bytes
        //     int maxPayloadPerFragment = mtu - ipv6HeaderLength - fragmentHeaderLength;

        //     // Fragmentation ID (randomly generated or incremented for uniqueness)
        //     int fragmentId = new Random().nextInt(0xFFFF);
        //     for (int offset = 0; offset < payload.length; offset += maxPayloadPerFragment) {
        //         boolean moreFragments = offset + maxPayloadPerFragment < payload.length;
        //         int fragmentSize = Math.min(maxPayloadPerFragment, payload.length - offset);

        //         // Create Fragment Header
        //         IPv6 fragment = new IPv6();
        //         fragment.setSourceAddress(ipv6Packet.getSourceAddress());
        //         fragment.setDestinationAddress(ipv6Packet.getDestinationAddress());
        //         fragment.setNextHeader(IPv6.PROTOCOL_FRAGMENT);
        //         fragment.setPayload(new IPv6.FragmentHeader()
        //                 .setNextHeader(ipv6Packet.getNextHeader())
        //                 .setFragmentOffset(offset / 8)
        //                 .setMFlag(moreFragments ? 1 : 0) // More Fragments flag
        //                 .setId(fragmentId)
        //                 .setPayload(Arrays.copyOfRange(payload, offset, offset + fragmentSize))
        //         );

        //         // Create Ethernet frame for the fragment
        //         Ethernet fragmentFrame = new Ethernet();
        //         fragmentFrame.setSourceMACAddress(ethFrame.getSourceMAC());
        //         fragmentFrame.setDestinationMACAddress(ethFrame.getDestinationMAC());
        //         fragmentFrame.setEtherType(Ethernet.TYPE_IPV6);
        //         fragmentFrame.setPayload(fragment);

        //         // Emit the fragment
        //         packetService.emit(new DefaultOutboundPacket(
        //                 deviceId,
        //                 DefaultTrafficTreatment.builder().setOutput(portNumber).build(),
        //                 ByteBuffer.wrap(fragmentFrame.serialize())
        //         ));

        //         log.info("Fragment sent: offset={}, size={}, moreFragments={}",
        //                 offset, fragmentSize, moreFragments);
        //     }

        // }

        private void flood(PacketContext context) {
            packetService.emit(new DefaultOutboundPacket(
                    context.inPacket().receivedFrom().deviceId(),
                    DefaultTrafficTreatment.builder().setOutput(PortNumber.FLOOD).build(),
                    context.inPacket().unparsed()
            ));
        }
    }
}
