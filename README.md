# Volter  vpn (Ранее ptero-vpn - https://github.com/unitdevgcc/pterovpn) 



License: MIT © c0redev (maxkrya) (parsend)

## Server (Java)

Рядом с JAR положи `config.properties`.

```
listenPorts=25565
token=change-me
udpChannels=4
```

Запуск:

```bash
java -jar server/target/server.jar
```

## Client (Go, Linux / Windows)

Конфиги: `~/.config/volter/` (Linux) или `%APPDATA%\volter\` (Windows), JSON.

Linux:

```bash
sudo ./volter-client \
  --server 1.2.3.4:25565 \
  --token change-me \
  --tun volter0 \
  --tun-cidr 10.13.37.2/24 \
  --mtu 1420
```

Windows (администратор, `wintun.dll` рядом с exe или из релиза):

## systemd

Пример юнита: [contrib/volter-client.service](contrib/volter-client.service).
