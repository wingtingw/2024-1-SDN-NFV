/*
 * Copyright 2023-present Open Networking Foundation
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

package nycu.winlab.vrouter;

// import
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.TCP;
import org.onlab.packet.IpAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.Ip6Prefix;

// import org.onosproject.net.host.InterfaceIpAddress;

import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;

import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;

import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.IntentService;

import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.Host;
import org.onosproject.net.host.HostService;

//import org.onosproject.routeservice;
//import org.onosproject.routeservice.RouteService;
import org.onosproject.routeservice.*;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;

// import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import java.util.*;
//import java.util.HashMap;
import java.util.List;
// import java.nio.ByteBuffer;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collection;
import java.util.HashSet;

import org.onosproject.net.PortNumber;
import org.onosproject.net.DeviceId;


/**
 * Skeletal ONOS application component.
 */
// @reference, global variables, ...
@Component(immediate = true, service = AppComponent.class)
public class AppComponent {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final RouterConfigListener routerListener = new RouterConfigListener();
    private final ConfigFactory<ApplicationId, RouterConfig> factory = new ConfigFactory<ApplicationId, RouterConfig>(
        APP_SUBJECT_FACTORY, RouterConfig.class, "information") {
        @Override
        public RouterConfig createConfig() {
            log.info("RouterConfig factory: createConfig called");
            return new RouterConfig();
        }
    };
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

     @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected InterfaceService intfService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    ApplicationId appId;
    ConnectPoint frrCp;
    MacAddress frrMac;
    List<String> v4peers;
    List<String> v6peers;
    MacAddress hostMac;
    ConnectPoint hostCp;
    Ip4Address hostIpv4;
    Ip6Address hostIpv6;
    List<String> r1Ip4Str;
    List<String> r1Ip6Str;
    List<IpAddress> r1Ip4;
    List<IpAddress> r1Ip6;
    ArrayList<ConnectPoint> peerCPs = new ArrayList<ConnectPoint>();

    private RouterPacketProcessor routerProcessor = new RouterPacketProcessor();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.winlab.vrouter");
        packetService.addProcessor(routerProcessor, PacketProcessor.director(6));
        cfgService.addListener(routerListener);
        cfgService.registerConfigFactory(factory);
        log.info("RouterConfigListener registered");
        requestPacketsIn();
        log.info("Started: " + appId.name());
    }

    @Deactivate
    protected void deactivate() {
        cancelPacketsIn();
        cfgService.removeListener(routerListener);
        cfgService.unregisterConfigFactory(factory);
        packetService.removeProcessor(routerProcessor);
        routerProcessor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        requestPacketsIn();
    }
    // 小心ndp
    private void requestPacketsIn() {
        TrafficSelector.Builder selectorv4 = DefaultTrafficSelector.builder();
        selectorv4.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selectorv4.build(), PacketPriority.REACTIVE, appId);
        TrafficSelector.Builder selectorv6 = DefaultTrafficSelector.builder();
        selectorv6.matchEthType(Ethernet.TYPE_IPV6);
        packetService.requestPackets(selectorv6.build(), PacketPriority.REACTIVE, appId);
    }

    private void cancelPacketsIn() {
        TrafficSelector.Builder selectorv4 = DefaultTrafficSelector.builder();
        selectorv4.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selectorv4.build(), PacketPriority.REACTIVE, appId);
        TrafficSelector.Builder selectorv6 = DefaultTrafficSelector.builder();
        selectorv6.matchEthType(Ethernet.TYPE_IPV6);
        packetService.cancelPackets(selectorv6.build(), PacketPriority.REACTIVE, appId);
    }

    private class RouterConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
             && event.configClass().equals(RouterConfig.class)) {
                RouterConfig config = cfgService.getConfig(appId, RouterConfig.class);
                if (config == null) {
                    log.warn("Router: config is null");
                }
                if (config != null) {
                    log.info("vrouter, recieved config");
                    hostCp = config.getHostCP();
                    hostIpv4 = config.getHostIPv4();
                    hostIpv6 = config.getHostIPv6();
                    hostMac = config.getHostMac();
                    frrCp = config.getFrrCP();
                    frrMac = config.getFrrMac();
                    v4peers = config.getv4Peers();
                    v6peers = config.getv6Peers();
                    r1Ip4Str = config.getR1Ip4(); 
                    r1Ip6Str = config.getR1Ip6();
                    for (String it : r1Ip4Str) {
                        // r1Ip4.value();
                        if(it != null) {
                            log.warn("line220, it = {}", it);
                        }
                        r1Ip4.add(IpAddress.valueOf(it));
                    }
                    for (String it : r1Ip6Str) {
                        r1Ip6.add(IpAddress.valueOf(it));
                    }
                    // build bgp intent
                    for (String it : v4peers) {
                        String[] str = it.split(",");
                        IpAddress selfIp = IpAddress.valueOf(str[0]);
                        IpAddress peerIp = IpAddress.valueOf(str[1]);
                        Interface peerIntf = intfService.getMatchingInterface(peerIp);
                        ConnectPoint peerCp = peerIntf.connectPoint();
                        TrafficSelector.Builder selector2peer = DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV4)
                            .matchIPSrc(selfIp.toIpPrefix())
                            .matchIPDst(peerIp.toIpPrefix());
                        TrafficSelector.Builder selector2R1 = DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV4)
                            .matchIPSrc(peerIp.toIpPrefix())
                            .matchIPDst(selfIp.toIpPrefix());
                        bgpIntent(frrCp, peerCp, selector2peer);
                        bgpIntent(peerCp, frrCp, selector2R1);
                    }
                    for (String it : v6peers) {
                        String[] str = it.split(",");
                        IpAddress selfIp = IpAddress.valueOf(str[0]);
                        IpAddress peerIp = IpAddress.valueOf(str[1]);
                        Interface peerIntf = intfService.getMatchingInterface(peerIp);
                        ConnectPoint peerCp = peerIntf.connectPoint();
                        TrafficSelector.Builder selector2peer = DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV6)
                            .matchIPv6Src(selfIp.toIpPrefix())
                            .matchIPv6Dst(peerIp.toIpPrefix());
                        TrafficSelector.Builder selector2R1 = DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV6)
                            .matchIPv6Src(peerIp.toIpPrefix())
                            .matchIPv6Dst(selfIp.toIpPrefix());
                        bgpIntent(frrCp, peerCp, selector2peer);
                        bgpIntent(peerCp, frrCp, selector2R1);
                    }
                }
            }
        }

        private void bgpIntent(ConnectPoint ingress, ConnectPoint egress, TrafficSelector.Builder selector) {
            PointToPointIntent intent = PointToPointIntent.builder()
                .filteredIngressPoint(new FilteredConnectPoint(ingress))
                .filteredEgressPoint(new FilteredConnectPoint(egress))
                .selector(selector.build())
                // .treatment(treatment.build())
                .priority(30)
                .appId(appId)
                .build();
            intentService.submit(intent);
            log.info("[BGP Intent] {} => {} installed.", ingress, egress);
        }
    }

    private class RouterPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            //if (context.isHandled()) {
              //  log.warn("vrouter: packet already handled");
              //  return;
            //}
            log.info("vrouter: handling");
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            ConnectPoint srcCp = pkt.receivedFrom();
            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                IPv4 ipv4 = (IPv4) ethPkt.getPayload();
                IpAddress dstIp = IpAddress.valueOf(ipv4.getDestinationAddress());
                IpAddress srcIp = IpAddress.valueOf(ipv4.getSourceAddress());
                // to internal
                if (dstIp.equals(hostIpv4)) {  // to h1
                    TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPDst(hostIpv4.toIpPrefix())
                        .matchIPSrc(srcIp.toIpPrefix());
                    TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                        .setEthDst(hostMac)
                        .setEthSrc(frrMac);
                    p2pintent(srcCp, hostCp, selector, treatment);
                } else if (r1Ip4 == null) {
                    log.warn("nulllllllll at 303, vrouter");
                } else if (r1Ip4.contains(dstIp)) {  // to r1
                    TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPDst(dstIp.toIpPrefix())
                        .matchIPSrc(srcIp.toIpPrefix());
                    TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                        .setEthDst(frrMac)
                        .setEthSrc(frrMac);
                    p2pintent(srcCp, frrCp, selector, treatment);
                } else {  // to external
                    if (dstIp.equals(IpAddress.valueOf("172.17.29.2")) || dstIp.equals("192.168.63.2")) {  // to as1
                        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV6)
                            .matchIPDst(dstIp.toIpPrefix())
                            .matchIPSrc(srcIp.toIpPrefix());
                        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                            .setEthDst(MacAddress.valueOf("02:ae:91:14:38:45"))  // r2 mac
                            .setEthSrc(frrMac);
                        DeviceId dstDevice = DeviceId.deviceId("of:0000000000000001");
                        PortNumber dstPort = PortNumber.portNumber("3");
                        ConnectPoint egress = new ConnectPoint(dstDevice, dstPort);
                        p2pintent(srcCp, egress, selector, treatment);
                    } else {  // to peer
                        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                            .matchEthType(Ethernet.TYPE_IPV6)
                            .matchIPDst(dstIp.toIpPrefix())
                            .matchIPSrc(srcIp.toIpPrefix());
                        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                            .setEthDst(MacAddress.valueOf("EE:EA:FF:10:12:2F")) // peer mac
                            .setEthSrc(frrMac);
                        DeviceId dstDevice = DeviceId.deviceId("of:0000000000000002");
                        PortNumber dstPort = PortNumber.portNumber("3");
                        ConnectPoint egress = new ConnectPoint(dstDevice, dstPort);
                        p2pintent(srcCp, egress, selector, treatment);
                    }
                }
            } else if (ethPkt.getEtherType() == Ethernet.TYPE_IPV6) {
                // to internal
                // if (dstIp.equals(hostIpv6)) {  // to h1
                    
                // } else if (r1Ip6.contains(dstIp)) {  // to r1

                // } else {  // to external
                //     if (dstIp.equals(IpAddress.valueOf("2a0b:4e07:c4:128::2")) || dstIp.equals(IpAddress.valueOf("fd63::2/64"))) {  // to as1

                //     } else {  // to peer
                    
                //     }
                // }
            }
                
        }

        private ResolvedRoute getRoute(IpAddress targetIp) {
            Collection<RouteTableId> routingTable = routeService.getRouteTables();
            for (RouteTableId tableID : routingTable) {
                for (RouteInfo info : routeService.getRoutes(tableID)) {
                    ResolvedRoute bestRoute = info.bestRoute().get();

                    IpPrefix dstPrefix = bestRoute.prefix();
                    if (dstPrefix.contains(targetIp)) {
                        return bestRoute;
                    }
                }
            }
            return null;
        }
        private void p2pintent(ConnectPoint ingress, ConnectPoint egress, TrafficSelector.Builder selector, TrafficTreatment.Builder treatment) {
            PointToPointIntent intent = PointToPointIntent.builder()
                        .filteredIngressPoint(new FilteredConnectPoint(ingress))
                        .filteredEgressPoint(new FilteredConnectPoint(egress))
                        .selector(selector.build())
                        .treatment(treatment.build())
                        .priority(40)
                        .appId(appId)
                        .build();
            intentService.submit(intent);
            log.info("[Intent] {} => {} installed, to h1, ipv4.", ingress, egress);
        }
    }
}