#!/bin/bash
if [ "$EUID" -ne 0 ]
  then echo "Please run as root"
  exit
fi

for c in h1 h2 r1 r2 onos; do
    docker kill --signal=9 $c
    docker rm $c
done

ovs-vsctl del-br ovs1
ovs-vsctl del-br ovs2
ovs-vsctl del-br br-ta

# docker compose down

basename -a /sys/class/net/* | grep veth | xargs -I '{}' ip l del {}

delete_vxlan_tunnel ovs2 vxlan-100
delete_vxlan_tunnel ovs3 vxlan-100

ip link del wg0
# ip link del wg1
