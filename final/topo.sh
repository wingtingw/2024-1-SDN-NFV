#!/bin/bash
#set -x

if [ "$EUID" -ne 0 ]
  then echo "Please run as root"
  exit
fi

# Creates a veth pair
# params: endpoint1 endpoint2
function create_veth_pair {
    ip link add $1 type veth peer name $2
    ip link set $1 up
    ip link set $2 up
}

# Add a container with a certain image
# params: image_name container_name
function add_container {
    docker run -dit --network=none --privileged --cap-add NET_ADMIN --cap-add SYS_MODULE \
         --hostname $2 --name $2 ${@:3} $1
    pid=$(docker inspect -f '{{.State.Pid}}' $(docker ps -aqf "name=$2"))
    mkdir -p /var/run/netns
    ln -s /proc/$pid/ns/net /var/run/netns/$pid
}

function add_onos_container {
    docker run -dit --privileged --cap-add NET_ADMIN --cap-add SYS_MODULE \
        --hostname $2 \
        -p 8181:8181 -p 8101:8101 -p 6653:6653 -p 2620:2620 \
        -e ONOS_APPS=drivers,fpm,org.onosproject.openflow,gui2 \
        --name $2 ${@:3} $1
    pid=$(docker inspect -f '{{.State.Pid}}' $(docker ps -aqf "name=$2"))
    mkdir -p /var/run/netns
    ln -s /proc/$pid/ns/net /var/run/netns/$pid
}

# Set container interface's IP address and gateway
# params: container_name infname [ipaddress] [gw addr]
function set_intf_container {
    pid=$(docker inspect -f '{{.State.Pid}}' $(docker ps -aqf "name=$1"))
    ifname=$2
    ipaddr=$3
    echo "Add interface $ifname with IP $ipaddr to container $1"

    ip link set "$ifname" netns "$pid"
    if [ $# -ge 3 ]; then
        ip netns exec "$pid" ip addr add "$ipaddr" dev "$ifname"
    fi
    ip netns exec "$pid" ip link set "$ifname" up
    if [ $# -ge 4 ]; then
        ip netns exec "$pid" route add default gw $4
    fi
}

# Set container interface's ipv6 address and gateway
# params: container_name infname [ipaddress] [gw addr]
function set_v6intf_container {
    pid=$(docker inspect -f '{{.State.Pid}}' $(docker ps -aqf "name=$1"))
    ifname=$2
    ipaddr=$3
    echo "Add interface $ifname with ip $ipaddr to container $1"

    if [ $# -ge 3 ]
    then
        ip netns exec "$pid" ip addr add "$ipaddr" dev "$ifname"
    fi
    ip netns exec "$pid" ip link set "$ifname" up
    if [ $# -ge 4 ]
    then
        ip netns exec "$pid" route -6 add default gw $4
    fi
}

# Connects a container to an OVS switch
# params: ovs container [ipaddress] [gw addr]
function build_ovs_container_path {
    ovs_inf="veth$1$2"
    container_inf="veth$2$1"
    create_veth_pair $ovs_inf $container_inf
    ovs-vsctl add-port $1 $ovs_inf
    set_intf_container $2 $container_inf $3 $4
}

# Connects two OVS switches
# params: ovs1 ovs2
function build_ovs_path {
    inf1="veth$1$2"
    inf2="veth$2$1"
    create_veth_pair $inf1 $inf2
    ovs-vsctl add-port $1 $inf1
    ovs-vsctl add-port $2 $inf2
}

# Connects a container to a Linux bridge
# params: bridge_name container_name [ipaddress] [gw addr]
function build_bridge_container_path {
    br_inf="veth$1$2"
    container_inf="veth$2$1"
    create_veth_pair $br_inf $container_inf
    brctl addif $1 $br_inf
    set_intf_container $2 $container_inf $3 $4
}

HOSTIMAGE="sdnfv-final-host"
ROUTERIMAGE="sdnfv-final-frr"
ONOSIMAGE="sdnfv-final-onos"
BASEDIR=$(dirname "$0")

# Build Docker images
docker build containers/host -t "$HOSTIMAGE"
docker build containers/frr -t "$ROUTERIMAGE"
docker build containers/onos -t "$ONOSIMAGE"

# Start ONOS and R1, R2
# docker compose up -d
# pid=$(docker inspect -f '{{.State.Pid}}' onos)
# mkdir -p /var/run/netns
# ln -s /proc/$pid/ns/net /var/run/netns/$pid
# start onos not using docker compose
echo "build container onos"
add_onos_container $ONOSIMAGE onos

# Add FRR routers
echo "build container r1"
add_container $ROUTERIMAGE r1 \
    -v $(realpath $BASEDIR/config/r1.conf):/etc/frr/frr.conf \
    -v $(realpath $BASEDIR/config/daemons):/etc/frr/daemons \
    --add-host host.docker.internal:host-gateway
echo "build container r2"
add_container $ROUTERIMAGE r2 \
    -v $(realpath $BASEDIR/config/r2.conf):/etc/frr/frr.conf \
    -v $(realpath $BASEDIR/config/daemons):/etc/frr/daemons

# link onos r1
ip link add veth0 type veth peer name veth1
pid1=$(docker inspect -f '{{.State.Pid}}' onos)
pid2=$(docker inspect -f '{{.State.Pid}}' r1)
ip link set veth0 netns $pid1
ip link set veth1 netns $pid2
ip netns exec $pid1 ip link set veth0 up
ip netns exec $pid2 ip link set veth1 up
ip netns exec $pid1 ip a a 192.168.100.1/24 dev veth0 
ip netns exec $pid2 ip a a 192.168.100.4/24 dev veth1

# Add hosts
echo "add host h1"
add_container $HOSTIMAGE h1
echo "add host h2"
add_container $HOSTIMAGE h2

# Set up OVS bridges
ovs-vsctl add-br ovs1
ovs-vsctl set bridge ovs1 other_config:datapath-id=0000000000000001
ovs-vsctl set bridge ovs1 protocols=OpenFlow14
ovs-vsctl set-controller ovs1 tcp:127.0.0.1:6653
ip link set ovs1 up

echo "set ovs2"
ovs-vsctl add-br ovs2
ovs-vsctl set bridge ovs2 other_config:datapath-id=0000000000000002
ovs-vsctl set bridge ovs2 protocols=OpenFlow14
ovs-vsctl set-controller ovs2 tcp:127.0.0.1:6653
ip link set ovs2 up

# add bridge
# brctl addbr bre2
# ip link set bre2 up
# sudo docker exec -it r2 ip route add default via 172.17.29.1/24

# Connect R1 to OVS1
build_ovs_container_path ovs1 r1 172.16.29.69/24
echo "add ip to r1 ovs1"
pid=$(docker inspect -f '{{.State.Pid}}' $(docker ps -aqf "name=r1"))
ip netns exec "$pid" ip addr add 192.168.63.1/24 dev vethr1ovs1
ip netns exec "$pid" ip addr add 192.168.70.29/24 dev vethr1ovs1
ip netns exec "$pid" ip addr add 192.168.100.3/24 dev vethr1ovs1
set_v6intf_container r1 vethr1ovs1 fd63::1/64
set_v6intf_container r1 vethr1ovs1 fd70::29/64
set_v6intf_container r1 vethr1ovs1 2a0b:4e07:c4:29::69/64

# Connect OVS1 to OVS2
build_ovs_path ovs2 ovs1

# Connect OVS2 to h1
# ""
build_ovs_container_path ovs2 h1 172.16.29.2/24 172.16.29.69
# set_v6intf_container h1 vethh1ovs1 2a0b:4e07:c4:29::2/64--net ip -6 route add default 2a0b:4e07:c4:29::69 dev vethh1ovs2

pid=$(docker inspect -f '{{.State.Pid}}' $(docker ps -aqf "name=h1"))
nsenter --target "$pid" --net ip -6 addr add 2a0b:4e07:c4:29::2/64 dev vethh1ovs2
nsenter --target "$pid" --net ip -6 route add default via 2a0b:4e07:c4:29::69 dev vethh1ovs2

# Connect H2 and R2 via Linux bridge
create_veth_pair vethr2h2 vethh2r2
set_intf_container h2 vethh2r2 172.17.29.2/24 172.17.29.1
set_intf_container r2 vethr2h2 172.17.29.1/24

set_v6intf_container h2 vethh2r2 2a0b:4e07:c4:129::2/64 2a0b:4e07:c4:129::1
set_v6intf_container r2 vethr2h2 2a0b:4e07:c4:129::1/64


# Connect R2 to OVS1
echo "connect r2 to ovs1"
build_ovs_container_path ovs1 r2 192.168.63.2/24 192.168.63.1
set_v6intf_container r2 vethr2ovs1 fd63::2/64

sudo docker exec r1 sh -c "echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf"
sudo docker exec r1 sh -c "echo 'net.ipv6.conf.all.forwarding=1' >> /etc/sysctl.conf"
sudo docker exec r1 sysctl -p
# sudo docker exec r1 ip route add 172.16.29.1 dev vethr1ovs1
# sudo docker exec r1 iptables -t nat -A POSTROUTING -o vethovs1r1 -j MASQUERADE

sudo docker exec r2 sh -c "echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf"
sudo docker exec r2 sh -c "echo 'net.ipv6.conf.all.forwarding=1' >> /etc/sysctl.conf"
sudo docker exec r2 sysctl -p

wg-quick up wg0

# ovs-vsctl add-port ovs2 vxlan2to3 \
#     -- set interface vxlan2to3 type=vxlan \
#     options:remote_ip=192.168.60.29

ovs-vsctl add-port ovs2 vxlan2peer \
    -- set interface vxlan2peer type=vxlan \
    options:remote_ip=192.168.61.28

ovs-vsctl show
