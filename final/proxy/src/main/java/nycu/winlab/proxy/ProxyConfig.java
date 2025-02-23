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
package nycu.winlab.proxy;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onlab.packet.MacAddress;
// import org.onlab.packet.IpAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;
// import java.util.ArrayList;
// import java.util.List;
import java.util.function.Function;

public class ProxyConfig extends Config<ApplicationId> {
    public static final String VIRTUAL_IPV4 = "virtual-ip4";
    public static final String VIRTUAL_IPV6 = "virtual-ip6";
    // public static final String VIRTUAL_MAC = "virtual-mac";
    public static final String FRR_MAC = "frr-mac";
    public static final String H1_MAC = "h1-mac";


    Function<String, String> func = (String e) -> {
        return e;
    };

    @Override
    public boolean isValid() {
        return hasFields(VIRTUAL_IPV4, VIRTUAL_IPV6, FRR_MAC);
    }

    public Ip4Address getVgwIpv4() {
        return Ip4Address.valueOf(get(VIRTUAL_IPV4, null));
    }

    public Ip6Address getVgwIpv6() {
        return Ip6Address.valueOf(get(VIRTUAL_IPV6, null));
    }

    public MacAddress getFrrMac() {
        return MacAddress.valueOf(get(FRR_MAC, null));
    }

    public MacAddress getH1Mac() {
        return MacAddress.valueOf(get(H1_MAC, null));
    }

}