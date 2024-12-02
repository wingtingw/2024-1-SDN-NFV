main:
	docker compose up -d
	sudo ovs-vsctl add-br ovsbr
	sudo ovs-vsctl set bridge ovsbr protocols=OpenFlow14
	sudo ovs-vsctl set-controller ovsbr tcp:127.0.0.1:6653
	sudo ovs-docker add-port ovsbr eth2 R1 --ipaddress=172.20.0.2/16
	sudo ovs-docker add-port ovsbr eth1 R3 --ipaddress=172.20.0.3/16
	sudo ovs-docker add-port ovsbr eth1 R4 --ipaddress=172.20.0.4/16
	sudo docker exec R1 sh -c "echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf"
	sudo docker exec R1 sysctl -p
	sudo docker exec R2 sh -c "echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf"
	sudo docker exec R2 sysctl -p
	sudo docker exec R3 sh -c "echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf"
	sudo docker exec R3 sysctl -p
	sudo docker exec R4 sh -c "echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf"
	sudo docker exec R4 sysctl -p
	sudo docker exec R5 sh -c "echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf"
	sudo docker exec R5 sysctl -p
clean:
	docker compose down
	sudo ovs-vsctl del-br ovsbr