# Volter Docker + WARP

sTart:

```bash
cp .env.example .env
write to .env your token and etc.
docker-compose up -d --build
```

Check your shit!

```bash
docker exec volter-server curl -4 https://ifconfig.me
docker logs volter-warp | grep 'WARP exit'
```


RU:
- `PUBLIC_HOST=auto`, автоопределение публичного IPv4 сервера.
- `PUBLIC_HOST=1.2.3.4`, статический IP/домен для client key.
- `VOLTER_PUBLIC_PORT=443`, внешний TCP-порт Volter.
- `VOLTER_LEGACY_PORT=25565`, дополнительный внешний TCP-порт.
- `VOLTER_QUIC_PORT=4433`, внешний UDP QUIC-порт.
- `WARP_IP=172.31.255.10`, статический IP WARP-контейнера в Docker-сети.
- `WARP_SOCKS_PORT=1080`, порт SOCKS5 внутри WARP-контейнера.
- `VOLTER_IP=172.31.255.2`, статический IP Volter-контейнера.
- `WARP_SUBNET=172.31.255.0/24`, меняй, если сеть пересекается с другими Docker-сетями.
- `WARP_LICENSE_KEY=...`, опционально, для WARP+.

```bash
TOKEN=$(grep '^TOKEN=' .env | cut -d= -f2-)
HOST=$(grep '^PUBLIC_HOST=' .env | cut -d= -f2-)
TOKEN="$TOKEN" HOST="$HOST" python3 - <<PY
import base64,json,os
host=os.environ.get('HOST') or 'SERVER_IP'
token=os.environ['TOKEN']
print('volter://' + base64.urlsafe_b64encode(json.dumps({'s':host+':443','k':token},separators=(',',':')).encode()).decode().rstrip('='))
PY
```
