main:
	sudo ./topo.sh
	onos-app localhost install! bridge/target/bridge-1.0-SNAPSHOT.oar
	onos-app localhost install! vrouter/target/vrouter-1.0-SNAPSHOT.oar
	onos-app localhost install! proxy/target/proxy-1.0-SNAPSHOT.oar
	onos-netcfg localhost config/config.json
clean:
	sudo ./topo_clean.sh