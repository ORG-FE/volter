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

Рядом с `server/target/server.jar` положи **`config.properties`**. Пример в [`config.properties.example`](config.properties.example), все поля разобраны в [`Config.java`](server/src/main/java/dev/c0redev/volter/Config.java). Минимум: `listenPorts`, `token`, **`udpChannels=4`** (жёстко четыре). Остальное по настроению: QUIC и сертификаты, маскировка TCP, кластер `cluster.*`, автообновление `update.*`.

сборка:

```bash
mvn -f server/pom.xml package
java -jar server/target/server.jar
```

Docker + Cloudflare WARP для исходящего трафика: [`docs/docker-warp.md`](docs/docker-warp.md).

### Клиент



### Сборка 

```bash
go build -o volter-client ./cmd/volter-client
cd android && ./gradlew :app:assembleDebug
```
