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
ONOSIMAGE="sdnfv-final-onos"
BASEDIR=$(dirname "$0")
docker build containers/onos -t "$ONOSIMAGE"
echo "build container onos"
add_onos_container $ONOSIMAGE onos