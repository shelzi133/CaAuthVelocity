# CaAuthVelocity

Плагин для Velocity, который связывает прокси с [CaAuth](https://github.com/shelzi133/CaAuth). Работает вместе с [tiAuth](https://github.com/1050TIt0p/tiAuth): после входа игрока он требует привязать аккаунт к Discord или Telegram и подтвердить вход через бота. Также принимает команды от CaAuth (кик при отвязке, смена пароля) и может поднять SOCKS5-туннель для ботов.

## Возможности

- **Привязка аккаунта**: игрок получает код и отправляет его боту в Discord или Telegram. Время на привязку ограничено, иначе кик.
- **Подтверждение входа** через бота. При отказе или таймауте игрок кикается.
- **Твинки**: игрок вводит в чат код твинка из бота, подтверждения идут на основной аккаунт.
- **Boss bar** с обратным отсчётом на каждом этапе.
- **Входящий API**: CaAuth отправляет на прокси кик при отвязке и смену пароля в tiAuth.
- **SOCKS5-туннель** (по умолчанию выключен): прокси для ботов CaAuth, если Discord или Telegram недоступны из региона основного сервера. Список разрешённых хостов настраивается.
- Все сообщения настраиваются в `config.yml`.

## Требования

- Velocity 3.4.0+
- Java 21
- [tiAuth](https://github.com/1050TIt0p/tiAuth) (обязательная зависимость)
- [CaAuth](https://github.com/shelzi133/CaAuth) на Paper-сервере с включённым `module.auth.velocity.enabled`

## Установка

1. Положите `CaAuthVelocity-<версия>.jar` в папку `plugins` на Velocity.
2. Запустите прокси, чтобы создался `plugins/caauthvelocity/config.yml`.
3. Впишите в `api.host` и `api.port` адрес API основного плагина CaAuth.
4. Скопируйте `module.auth.velocity.secret` из конфига CaAuth в `api.secret`. Без секрета проверка входа отключена.
5. Выполните `/caav reload` или перезапустите прокси.

## Настройка

```yaml
api:
  host: "127.0.0.1"
  port: 25599
  secret: ""
  connect-timeout-seconds: 5
  fail-open: false
```

- `fail-open: false`: если API CaAuth недоступен, игрок кикается. При `true` вход разрешается без проверки.

```yaml
inbound:
  enabled: true
  bind-host: "127.0.0.1"
  port: 25600
```

Адрес, на котором прокси принимает запросы от CaAuth (кик, смена пароля). Тот же `secret`. Если CaAuth на другом сервере, поменяйте `bind-host` и закройте порт фаерволом для всех, кроме него.

```yaml
verification:
  require-binding: true
  bind-timeout-seconds: 300
  bind-poll-seconds: 3
  reminder-seconds: 15
  twink-timeout-seconds: 300
```

```yaml
tunnel:
  enabled: false
  bind-host: "0.0.0.0"
  port: 1080
  username: "calisto"
  password: ""
  allowed-hosts:
    - api.telegram.org
    - discord.com
```

Если `enabled: true` и пароль пустой, он генерируется автоматически и записывается в `config.yml`. Дальше в конфиге CaAuth укажите прокси `SOCKS5` с этим хостом, портом, логином и паролем. Если `allowed-hosts` пуст, туннель пропускает любые хосты.

## Команды

| Команда | Описание | Право |
|---|---|---|
| `/caav reload` | Перезагрузить конфиг | `caauthvelocity.reload` |

## Связанные проекты

- [CaAuth](https://github.com/shelzi133/CaAuth): основной плагин для Paper
- [tiAuth](https://github.com/1050TIt0p/tiAuth): авторизация на прокси
