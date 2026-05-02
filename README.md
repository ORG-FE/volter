<p align="center">
  <img src="assets/brand/volter-icon-512.png" alt="Volter" width="140">
</p>

<h3 align="center">Volter VPN</h3>

<p align="center">
  Hi! Этот проект был создан для изучение vpn и помощи людям. <br>
  <sub>MIT · c0redev · <a href="https://github.com/unitdevgcc">unitdevgcc</a></sub>
</p>

---

Проект вырос из идей [ptero-vpn](https://github.com/unitdevgcc/pterovpn) (Автор тот же - c0redev, как и тут):  те же цели (обход, гибкий транспорт, свой контур), но код и протокол здесь уже другие. Если ты собираешь инстанс для себя или для узкой аудитории, всё нужное лежит в этом репо без лишней хуиты.

### Что здесь лежит

**Сервер (`server/`)** — Как в общем по названию понятно - сервер

**Клиент (`cmd/volter-client`, `internal/`)** — Linux и Windows - tun и т.д найти тут!

**Android (`android/`)** — Тут андройд аппа

### Сервер

Рядом с `server/target/server.jar` положи **`config.properties`**. Черновик ключей в [`config.properties.example`](config.properties.example), все поля разобраны в [`Config.java`](server/src/main/java/dev/c0redev/volter/Config.java). Минимум: `listenPorts`, `token`, **`udpChannels=4`** (жёстко четыре). Остальное по настроению: QUIC и сертификаты, маскировка TCP, кластер `cluster.*`, автообновление `update.*`.

Запуск после сборки:

```bash
mvn -f server/pom.xml package
java -jar server/target/server.jar
```

### Клиент

Профили: **`~/.config/volter/`** или **`%APPDATA%\volter\`**, файлы `*.json`. Без `--server` / `--token` откроется TUI. Для одной команды в консоли нужны сервер и токен, или один **`--key`** в виде `volter://...`. Остальное в **`volter-client --help`** (QUIC, маршруты, `--proxy` вместо tun, на Windows при желании `--system-proxy`). На Linux сейчас и tun, и proxy идут через root. На Windows для tun нужны админские права и **`wintun.dll`** рядом с exe; если tun не хочешь, поднимай SOCKS и маршрутизируй приложения вручную.

### Сборка и релизы

```bash
go build -o volter-client ./cmd/volter-client
cd android && ./gradlew :app:assembleDebug
```

Релизы по тегам **`v*`** собирает [`.github/workflows/release.yml`](.github/workflows/release.yml). В коде апдейтов зашит репозиторий вроде `ORG-FE/volter` — подставь свой форк.

Пример быстрого подключения: (но лучше tui)))

```bash
sudo ./volter-client \
  --server 1.2.3.4:25565 \
  --token secret \
  --tun volter0 \
  --tun-cidr 10.13.37.2/24 \
  --mtu 1420
```
