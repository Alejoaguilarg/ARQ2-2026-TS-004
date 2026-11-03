# Notification Service — Finova

Envía las notificaciones de resultado de cada transferencia (push, email o SMS) a través del proveedor externo. Nunca está en el camino crítico: si se cae, las transferencias siguen y los eventos esperan en la cola (spec F5.8).

Contrato: `docs/spec.md` §1.1, §2 (`RecipientContact`, `NotificationLog`), §4.2–§4.5 y §4.9.

## Qué hace

| Evento (publicado por Transfer) | A quién avisa (§4.9) |
|---|---|
| `TransferCompleted` | Al que envía y al que recibe |
| `TransferRejected` | Al que envía |
| `TransferReversed` | Al que envía |

1. Consume `notification.transfer-events.q` (exchange `transfer.events`).
2. Por cada usuario a notificar:
   - si su notificación ya está `SENT`, la salta;
   - busca su contacto en `recipient_contacts`;
   - envía por su `preferredChannel`;
   - guarda el resultado en `notification_log` (`SENT` o `FAILED`, `attempts + 1`).
3. Hace ack, o pide reintento si algo falló.

| Situación | Qué pasa con el mensaje |
|---|---|
| Todo enviado | ack |
| Proveedor caído, timeout o error | reject → `.retry` (5 s) → vuelve a la cola. Después de 3 reintentos (4 intentos en total) → DLQ |
| JSON inválido, sin envelope, tipo desconocido o campos faltantes | directo a la DLQ |
| Usuario sin contacto, o sin dato para su canal | directo a la DLQ |

El mensaje que va a la DLQ lleva el header `x-notification-error` con la causa.

## Arquitectura (hexagonal, estructura Bancolombia como los demás servicios)

| Módulo | Contenido |
|---|---|
| `domain/model` | `TransferOutcome`, `RecipientContact`, `NotificationLog`, `Notification`, `NotificationMessages` y los puertos: `RecipientContactRepository`, `NotificationLogRepository`, `NotificationProvider` |
| `domain/usecase` | `NotifyTransferOutcomeUseCase`: la regla de negocio, sin Spring |
| `infrastructure/entry-points/rabbitmq-listener` | Adaptador de entrada: consume la cola, ack, reintentos y DLQ |
| `infrastructure/driven-adapters/jpa-repository` | PostgreSQL (`notification_db`) y migraciones Flyway |
| `infrastructure/driven-adapters/rest-consumer` | Proveedor HTTP: `POST /push`, `/email`, `/sms` (WireMock en la Ola 2) |
| `infrastructure/driven-adapters/email-sender` | Correo real por SMTP (opcional, apagado por defecto) |
| `applications/app-service` | `MainApplication`, configuración y composición de los adaptadores |

`NotificationProviderConfig` decide qué adaptador usa cada canal. Por defecto, todos los canales van al proveedor HTTP. Con `ADAPTERS_EMAIL_ENABLED=true`, EMAIL va por SMTP y PUSH/SMS siguen yendo al proveedor HTTP. El caso de uso no cambia en ningún caso.

`ArchitectureTest` (ArchUnit) verifica que el dominio no dependa de frameworks ni de adaptadores.

## Decisiones que el spec no define

1. **Primero se envía, después se registra,** sin dejar una transacción abierta mientras responde el proveedor. Si el servicio se cae justo entre los dos pasos, ese usuario puede recibir la notificación dos veces (entrega at-least-once). Para mitigarlo, al proveedor se le envía `Idempotency-Key: notificationId`.
2. **Contacto inexistente, o sin dato para su canal** → DLQ. No se intenta por otro canal, porque el spec no define un canal alterno.
3. **"3 reintentos" = 4 intentos en total.**
4. **Cuerpo enviado al proveedor:** `{notificationId, channel, to, subject, message, eventType, transferId, amount, currency, reason}`.

## Correr en local

Requisitos: JDK 21, Docker Desktop y la rama con el `docker-compose.yml` del equipo.

**1. Base de datos y simulador del proveedor.** En la raíz del repo, crea un `docker-compose.override.yml` (no se sube: agrégalo a `.git/info/exclude`):

```yaml
services:
  notification-db:
    ports:
      - "127.0.0.1:5432:5432"
  notification-provider-mock:
    ports:
      - "127.0.0.1:8089:8080"
```

Luego levántalos:

```
docker compose up -d notification-db notification-provider-mock
```

**2. Variables de entorno en IntelliJ** (Run → Edit Configurations → `MainApplication` → Environment variables), para usar el RabbitMQ de CloudAMQP:

```
SPRING_RABBITMQ_HOST=<host de CloudAMQP>
SPRING_RABBITMQ_PORT=5671
SPRING_RABBITMQ_SSL_ENABLED=true
SPRING_RABBITMQ_VIRTUAL_HOST=<vhost>
SPRING_RABBITMQ_USERNAME=<usuario>
SPRING_RABBITMQ_PASSWORD=<contraseña>
```

La base de datos (`localhost:5432`, usuario `notification`) y el proveedor (`http://localhost:8089`) ya están como valores por defecto en `application.yaml`.

**3. Arrancar `MainApplication`.** Flyway crea las tablas y carga los contactos de ejemplo. Para comprobarlo:

```
docker compose exec notification-db psql -U notification -d notification_db -c "select email, preferred_channel from recipient_contacts;"
```

## Correo real (Gmail)

1. En una cuenta Gmail de pruebas: activa la verificación en 2 pasos y crea una **contraseña de aplicación**.
2. Agrega estas variables en IntelliJ:

```
ADAPTERS_EMAIL_ENABLED=true
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=<tu cuenta de pruebas>@gmail.com
SPRING_MAIL_PASSWORD=<contraseña de aplicación>
```

3. Apunta a Ana y a Beto (los usuarios de demo de Identity) a tu bandeja, **solo en tu base local** (el repo es público). Gmail entrega `cuenta+algo@gmail.com` en la misma bandeja:

```sql
UPDATE recipient_contacts SET email = '<cuenta>+ana@gmail.com'  WHERE user_id = '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c';
UPDATE recipient_contacts SET email = '<cuenta>+beto@gmail.com' WHERE user_id = '2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f';
```

Si cambias `R__seed_recipient_contacts.sql`, Flyway lo vuelve a ejecutar y restaura los correos de ejemplo.

## Probar sin Transfer: mensajes de ejemplo

**Con Postman (recomendado):** importa `postman/notification-service.postman_collection.json`. Publica cada mensaje de `samples/` en `transfer.events` usando la API HTTP de RabbitMQ y verifica que quedó enrutado a una cola. Se puede correr todo seguido con el *Collection Runner*.

- Los valores iniciales de las variables apuntan al RabbitMQ local del compose (`http://localhost:15672`, vhost `%2F`, usuario `finova`).
- Para CloudAMQP, cambia el **Current value** de `rabbitUrl` (`https://<host de CloudAMQP>`), `vhost`, `rabbitUser` y `rabbitPassword`. Postman no exporta el *Current value*, así que la contraseña no termina en el repo.
- Para repetir las pruebas desde cero:
  ```
  docker compose exec notification-db psql -U notification -d notification_db -c "TRUNCATE notification_log;"
  ```

**A mano:** en la consola de RabbitMQ: **Exchanges → `transfer.events` → Publish message**.
- **Routing key:** `transfer.completed`, `transfer.rejected` o `transfer.reversed`.
- **Properties:** `content_type` = `application/json`, `message_id` = el `messageId` del archivo.
- **Payload:** el contenido del archivo de `samples/`.

| Archivo | Qué demuestra |
|---|---|
| `transfer-completed.json` | Ana le transfiere a Beto: avisa a los dos (EMAIL) |
| `transfer-rejected.json` | Avisa solo a Ana, con el motivo |
| `transfer-reversed.json` | Avisa solo a Ana: el dinero fue devuelto |
| `transfer-completed-sms-push.json` | Canales SMS y PUSH (simulador) |
| `transfer-completed-falla.json` | Ana queda `SENT`. El usuario "falla" recibe 503 del simulador (`infra/notification-provider-mock/mappings/notifications-falla.json`), se reintenta cada 5 s y termina en `notification.transfer-events.q.dlq` con `attempts = 4` |
| `mensaje-invalido.txt` | Lo que hoy envía Transfer (`toString()`): va directo a la DLQ |

Para ver el resultado:

```
docker compose exec notification-db psql -U notification -d notification_db -c "select user_id, event_type, channel, status, attempts from notification_log order by created_at;"
```

## Tests

```
gradlew test -x pitest
```

- Dominio y caso de uso: tests unitarios sin Spring, con adaptadores en memoria.
- Adaptadores: listener (ack, reintento, DLQ), proveedor HTTP (`MockRestServiceServer`), SMTP (mock) y JPA.
- `JpaAdaptersIntegrationTest` corre las migraciones en un PostgreSQL 16 real con Testcontainers. Necesita Docker Desktop encendido; si no, se omite.
- Sin `-x pitest` también corren las pruebas de mutación (más lentas), igual que en los demás servicios.
- El CI del repo (`.github/workflows/ci.yml`) corre `./gradlew build` y exige **cobertura de líneas ≥ 90 %** sumando todos los módulos menos `app-service`. Cada módulo deja su reporte en `build/reports/jacocoHtml/index.html`.

## Con el sistema completo (Docker Compose)

`docker compose --profile apps up -d --build` construye este servicio con `deployment/Dockerfile`. El compose ya inyecta la base de datos, RabbitMQ y `NOTIFICATION_PROVIDER_URI`.

Para correo real dentro de Docker, agrega las variables `ADAPTERS_EMAIL_ENABLED` y `SPRING_MAIL_*` al servicio `notification-service` en tu `docker-compose.override.yml`, tomándolas de tu `.env` local.

## Pendientes con el equipo

- **Transfer** debe publicar el envelope JSON de §4.1 con el payload completo. Hoy usa `toString()` y envía `senderUserId`, `recipientUserId` y `currency` en `null`.
- **Identity:** `R__seed_recipient_contacts.sql` usa los mismos usuarios de demo de Identity (Ana, Beto y el auditor, `V2__demo_users.sql`). Si Identity los cambia, hay que actualizar este archivo.
