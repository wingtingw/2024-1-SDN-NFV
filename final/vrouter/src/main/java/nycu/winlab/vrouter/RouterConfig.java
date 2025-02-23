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

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.ConnectPoint;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IpAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class RouterConfig extends Config<ApplicationId> {
    public static final String FRR_LOCATION = "vrrouting";
    public static final String FRR_MAC = "vrrouting-mac";
    public static final String V4PEERS = "v4peers";
    public static final String V6PEERS = "v6peers";
    public static final String HOST_MAC = "host-mac";
    public static final String HOSTCP = "hostCP";
    public static final String HOST_IPV4 = "host-ip4";
    public static final String HOST_IPV6 = "host-ip6";
    public static final String R1_IP4 = "r1-ip4";
    public static final String R1_IP6 = "r1-ip6";
    // public static final String HOST_MAC = "host-mac";

    Function<String, String> func = (String e) -> {
        return e;
    };

    @Override
    public boolean isValid() {
        return hasFields(FRR_LOCATION, FRR_MAC, V4PEERS, V6PEERS, HOSTCP, HOST_IPV4, HOST_IPV6);
    }

    public ConnectPoint getFrrCP() {
        return ConnectPoint.fromString(get(FRR_LOCATION, null));
    }

    public MacAddress getFrrMac() {
        return MacAddress.valueOf(get(FRR_MAC, null));
    }
    public List<String> getv4Peers() {
        return getList(V4PEERS, Function.identity());
    }
    public List<String> getv6Peers() {
        return getList(V6PEERS, Function.identity());
    }

    public ConnectPoint getHostCP() {
        return ConnectPoint.fromString(get(HOSTCP, null));
    }

    public MacAddress getHostMac() {
       return MacAddress.valueOf(get(HOST_MAC, null));
    }

    public Ip4Address getHostIPv4() {
        return Ip4Address.valueOf(get(HOST_IPV4, null));
    }

    public Ip6Address getHostIPv6() {
        return Ip6Address.valueOf(get(HOST_IPV6, null));
    }

    public List<String> getR1Ip4() {
        return getList(R1_IP4, Function.identity());
    }

    public List<String> getR1Ip6() {
        return getList(R1_IP6, Function.identity());
    }

}
