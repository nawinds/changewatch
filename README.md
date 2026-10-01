# ChangeWatch

ChangeWatch — мобильное приложение и backend на Kotlin для отслеживания изменений в Google Sheets, HTTP API и выбранных элементах веб-страниц.

Основной сценарий проекта — мониторинг учебных результатов. Пользователь может подписаться на таблицу преподавателя и получать push-уведомление, когда появился новый балл, изменился существующий результат или появилось новое задание.

Например:

```text
Было:

Лабораторная №4 | —

Стало:

Лабораторная №4 | 8/10
```

Пользователь получает:

```text
Новый балл

Лабораторная №4
8/10
```

Если результат впоследствии изменится:

```text
Баллы изменены

Лабораторная №4
8/10 → 9/10
```

Проект предназначен не только для проверки того, работает ли сайт, а именно для обнаружения **значимых изменений данных**.

---

# Основные возможности

## Google Sheets

Пользователь подключает Google-таблицу, к которой у него есть доступ.

Можно выбрать:

- лист;
- строку пользователя;
- столбцы, за которыми необходимо следить;
- частоту проверки.

Например:

```text
Студент | Lab 1 | Lab 2 | Lab 3
--------------------------------
Иванов  | 10    | 8     | 9
Петров  | 9     | —     | 7
Аксенов | 10    | —     | —
```

Пользователь задаёт:

```text
Идентификатор строки: Аксенов
```

ChangeWatch запоминает состояние:

```text
Lab 1 = 10
Lab 2 = null
Lab 3 = null
```

Позже таблица становится:

```text
Аксенов | 10 | 8 | —
```

Система обнаруживает:

```text
Lab 2:
null → 8
```

и создаёт событие:

```text
GRADE_APPEARED
```

Важно, что мониторинг привязывается не просто к номеру строки вроде `A17`, а к логическому идентификатору пользователя.

Если преподаватель переставит строки:

```text
строка 14 → строка 21
```

мониторинг продолжит работать.

Для приватных таблиц используется официальный Google Sheets API и OAuth 2.0. ChangeWatch не должен обходить авторизацию или получать данные, к которым пользователь не имеет доступа.

---

# Мониторинг API

Можно добавить HTTP endpoint:

```text
GET https://example.com/api/results
```

Например API возвращает:

```json
{
  "student": "nikita",
  "score": 73,
  "status": "checked"
}
```

Пользователь может следить только за определённым значением:

```text
$.score
```

Предыдущее значение:

```text
68
```

Новое:

```text
73
```

Система создаёт изменение:

```text
68 → 73
```

и отправляет push.

Для JSON можно поддержать JSONPath либо собственный простой path:

```text
student.score
results.lab4.points
status
```

---

# Мониторинг веб-страниц

Пользователь добавляет страницу:

```text
https://example.edu/results
```

и выбирает элемент, за которым нужно следить.

Например:

```css
.result-table .lab-4 .score
```

Fetcher загружает страницу и извлекает только нужный участок.

Вместо сравнения всего HTML:

```text
1.2 MB HTML
```

сравнивается только:

```text
Лабораторная №4: 8/10
```

Это уменьшает количество ложных событий из-за счётчиков, рекламы, timestamps и других динамических элементов страницы.

В первой версии поддерживаются только обычные HTML-страницы.

Сайты, которым для отображения данных требуется выполнение сложного JavaScript, авторизация браузера или CAPTCHA, в MVP не поддерживаются.

---

# Правила уведомлений

Пользователь может выбрать, какие изменения его интересуют.

Например:

```text
Любое изменение

Значение появилось

Значение увеличилось

Значение уменьшилось

Значение стало равно X

Текст содержит X
```

Пример:

```text
Следить за Lab 4

Уведомлять:
только когда значение перестанет быть пустым
```

Тогда:

```text
— → —
```

ничего не происходит.

```text
— → 8
```

создаётся уведомление.

---

# История изменений

ChangeWatch хранит историю:

```text
Lab 4

20 сентября
—

22 сентября
8/10

23 сентября
9/10
```

В приложении можно посмотреть diff:

```text
8 → 9
```

и точное время обнаружения изменения.

---

# Совместный мониторинг

Одна из главных особенностей архитектуры — одинаковый источник не должен загружаться отдельно для каждого пользователя.

Допустим:

```text
30 студентов
```

следят за:

```text
google.com/spreadsheets/.../semester-results
```

Плохой вариант:

```text
30 пользователей
      ↓
30 запросов каждые 30 секунд
      ↓
Google Sheets API
```

ChangeWatch делает:

```text
30 пользователей
      ↓
1 Source
      ↓
1 fetch
      ↓
новый snapshot
      ↓
30 пользовательских правил
```

Таким образом получение данных отделено от пользовательских подписок.

Это уменьшает:

```text
HTTP traffic
Google API quota usage
нагрузку на внешние сайты
```

и создаёт хорошую задачу для event-driven архитектуры.

---

# Архитектура

```text
                   Android App
                 Kotlin / Compose
                        |
                     REST API
                        |
                 ┌──────────────┐
                 │ API Gateway  │
                 └──────┬───────┘
                        |
         ┌──────────────┼──────────────┐
         ↓              ↓              ↓
   User Service   Monitor Service   Source Service
                        |
                        ↓
                   Scheduler
                        |
                        ↓
                      Kafka
                  source.check
                        |
                        ↓
                 Fetcher Service
                        |
             ┌──────────┼──────────┐
             ↓          ↓          ↓
        Google API    HTTP API    HTML
             |
             ↓
        source.fetched
             |
             ↓
            Kafka
             |
             ↓
      Change Processor
             |
        ┌────┴────┐
        ↓         ↓
 no changes    changes
                  |
                  ↓
            change.detected
                  |
                  ↓
           Notification Service
                  |
                  ↓
          Firebase Cloud Messaging
                  |
                  ↓
              Android App
```

---

# Микросервисы

## User Service

Отвечает за:

```text
регистрацию
пользователей
OAuth
устройства
профиль
```

Хранит пользователя и связанные устройства.

---

## Source Service

Описывает внешний источник данных.

Например:

```json
{
  "id": "source-42",
  "type": "GOOGLE_SHEET",
  "url": "...",
  "checkInterval": 60
}
```

Один Source может использоваться большим количеством пользователей.

---

## Monitor Service

Хранит непосредственно пользовательские подписки.

Например:

```text
source:
semester-results

row:
student = "Аксенов Никита"

watch:
Lab 4
Lab 5
Exam
```

И правила:

```text
notifyWhen = VALUE_CHANGED
```

Source отвечает на вопрос:

```text
Откуда получать данные?
```

Monitor:

```text
За чем конкретно следит пользователь?
```

---

## Scheduler Service

Определяет, какие источники пора проверить.

Например:

```text
Source A → каждые 60 секунд
Source B → каждые 5 минут
Source C → каждый час
```

Scheduler не выполняет HTTP-запрос самостоятельно.

Он отправляет в Kafka:

```json
{
  "event": "source.check.requested",
  "sourceId": "42"
}
```

Благодаря этому Fetcher Service можно горизонтально масштабировать.

---

## Fetcher Service

Получает данные из реальных внешних источников.

Внутри находятся адаптеры:

```text
GoogleSheetsFetcher
HttpApiFetcher
HtmlFetcher
```

Общий интерфейс:

```kotlin
interface SourceFetcher {
    suspend fun fetch(source: Source): Snapshot
}
```

Fetcher получает новый snapshot и публикует:

```text
source.fetched
```

---

## Change Processor

Сравнивает предыдущий snapshot с новым.

Например:

```text
old:
Lab 4 = null

new:
Lab 4 = 8
```

Результат:

```json
{
  "type": "VALUE_APPEARED",
  "oldValue": null,
  "newValue": "8"
}
```

После этого Processor определяет, какие пользовательские Monitor подходят под это изменение.

---

## Notification Service

Получает:

```text
change.detected
```

и решает, необходимо ли отправлять уведомление.

Push отправляются через Firebase Cloud Messaging.

Notification Service также отвечает за:

```text
deduplication
retry
notification history
device tokens
```

---

# Kafka topics

Основные события:

```text
source.check.requested

source.fetched

source.fetch.failed

change.detected

notification.requested

notification.sent

notification.failed
```

Пример полного потока:

```text
Scheduler
    ↓
source.check.requested

Fetcher
    ↓
source.fetched

Change Processor
    ↓
change.detected

Notification Service
    ↓
notification.sent
```

---

# Защита от повторной обработки

Kafka может доставить сообщение повторно.

Поэтому обработчики должны быть идемпотентными.

Например:

```text
eventId = 8c40...
```

Notification Service сохраняет обработанные eventId.

Если то же событие приходит повторно:

```text
eventId = 8c40...
```

второй push не отправляется.

---

# Outbox Pattern

Событие не должно потеряться между PostgreSQL и Kafka.

Плохой сценарий:

```text
UPDATE monitor
COMMIT

приложение падает

Kafka publish не выполнен
```

Поэтому сервисы, которым необходимо гарантированно публиковать события, используют Transactional Outbox.

В одной транзакции записываются:

```text
domain data
+
outbox event
```

После commit отдельный publisher отправляет событие в Kafka.

---

# Redis

Redis можно использовать для:

```text
распределённых блокировок;
кэширования;
rate limiting;
короткоживущего состояния scheduler;
deduplication.
```

Например Fetcher должен предотвратить ситуацию:

```text
Worker 1 → fetch source 42
Worker 2 → fetch source 42
```

одновременно.

---

# HTTP optimization

Если сервер поддерживает:

```text
ETag
```

или:

```text
Last-Modified
```

Fetcher сохраняет их.

Следующий запрос может содержать:

```http
If-None-Match: "abc123"
```

Если контент не изменился:

```http
304 Not Modified
```

не нужно скачивать страницу и запускать полный diff.

---

# Push-уведомления

Android-приложение использует Firebase Cloud Messaging.

Пример:

```text
ChangeWatch

Появился новый балл

Алгоритмы
Лабораторная №4: 8/10
```

При нажатии пользователь открывает экран изменения:

```text
Лабораторная №4

Было:
—

Стало:
8/10

Обнаружено:
22:41
```

---

# Android-приложение

Стек:

```text
Kotlin
Jetpack Compose
Coroutines
Flow
Retrofit / Ktor Client
Room
Firebase Cloud Messaging
```

Основные экраны:

```text
Авторизация

Мои мониторы

Создание монитора

История изменений

Уведомления

Настройки
```

---

# Создание Google Sheets monitor

Пример пользовательского flow:

```text
Добавить монитор
      ↓
Google Sheets
      ↓
Выбрать таблицу
      ↓
Выбрать лист
      ↓
Найти мою строку
      ↓
Выбрать интересующие столбцы
      ↓
Настроить уведомления
      ↓
Создать
```

Например:

```text
Строка:
student = "Nikita Aksenov"

Следить:
Lab 3
Lab 4
Exam

Уведомлять:
при любом изменении
```

---

# REST API

Пример создания monitor:

```http
POST /api/v1/monitors
```

```json
{
  "sourceId": "42",
  "selector": {
    "rowKey": "student",
    "rowValue": "Nikita Aksenov",
    "columns": [
      "lab3",
      "lab4",
      "exam"
    ]
  },
  "rule": {
    "type": "VALUE_CHANGED"
  }
}
```

Получение monitor:

```http
GET /api/v1/monitors/{id}
```

История:

```http
GET /api/v1/monitors/{id}/changes
```

Удаление:

```http
DELETE /api/v1/monitors/{id}
```

---

# Backend stack

Основной вариант:

```text
Kotlin
Spring Boot
Spring Web
Spring Security
Spring Data
Spring Kafka

PostgreSQL
Redis
Apache Kafka

Firebase Cloud Messaging

Google Sheets API

Docker
Docker Compose
```

Spring Boot здесь удобен, потому что проект специально предназначен для изучения:

```text
микросервисов
Kafka
OAuth
транзакций
observability
distributed systems
```

Использовать Ktor тоже можно, но смешивать Spring и Ktor между сервисами только ради использования двух фреймворков не требуется.

---

# Взаимодействие сервисов

Синхронные операции:

```text
Mobile → REST → Backend
```

Например:

```text
создать monitor
получить monitors
получить историю
```

Асинхронные операции:

```text
Kafka
```

Например:

```text
проверить source
обработать snapshot
обнаружить change
отправить notification
```

---

# Хранилища

Каждый сервис является владельцем своих данных.

Например:

```text
User Service
    ↓
users database

Monitor Service
    ↓
monitors database

Source Service
    ↓
sources database

Notification Service
    ↓
notifications database
```

Один сервис не должен напрямую читать таблицы другого сервиса.

Для передачи информации используются API или события Kafka.

---

# Observability

Все сервисы должны иметь:

```text
structured logs
metrics
distributed tracing
health checks
```

Стек:

```text
Prometheus
Grafana
OpenTelemetry
Jaeger
```

Например можно видеть полный trace:

```text
Scheduler
   ↓ 12 ms

Kafka
   ↓

Fetcher
   ↓ 340 ms

Change Processor
   ↓ 18 ms

Notification
   ↓ 120 ms

FCM
```

---

# Метрики

Полезные backend-метрики:

```text
sources_checked_total

source_check_duration

source_check_failed_total

changes_detected_total

notifications_sent_total

notifications_failed_total

kafka_consumer_lag
```

---

# Ошибки и retry

Внешние сервисы могут временно быть недоступны.

Например:

```text
Google Sheets API → 503
```

Fetcher не должен сразу считать это изменением данных.

Используется retry:

```text
attempt 1
↓
1 second

attempt 2
↓
5 seconds

attempt 3
↓
30 seconds
```

После исчерпания попыток:

```text
source.fetch.failed
```

может попасть в DLQ.

---

# Rate limiting

ChangeWatch не должен бесконтрольно опрашивать внешние сервисы.

Для каждого типа источника задаются ограничения.

Например:

```text
Google Sheets:
не чаще одного запроса в N секунд для одного source
```

Если 100 пользователей подписались на одну таблицу, количество fetch всё равно не увеличивается в 100 раз.

---

# Безопасность

OAuth access/refresh tokens нельзя хранить в открытом виде.

Чувствительные данные должны быть зашифрованы.

Backend никогда не должен:

```text
обходить CAPTCHA
обходить авторизацию
подбирать пароли
получать закрытые данные без разрешения пользователя
```

Мониторить можно только публичные ресурсы либо ресурсы, к которым пользователь сам имеет законный доступ.

---

# MVP

Первая рабочая версия проекта должна поддерживать только:

```text
Android-приложение

Google авторизацию

Google Sheets monitoring

поиск строки пользователя

мониторинг выбранных ячеек

историю изменений

push через FCM

Scheduler

Kafka

PostgreSQL
```

То есть первая версия уже решает реальную задачу:

> Получить уведомление сразу после того, как преподаватель выставил или изменил балл.

Не нужно сразу реализовывать HTML monitoring, API monitoring, Grafana, сложные правила и десять микросервисов.

---

# Version 2

После рабочего MVP добавляются:

```text
HTTP JSON API monitoring

HTML + CSS selector monitoring

несколько условий уведомлений

shared source fetching

Redis

retry / DLQ

Transactional Outbox

distributed tracing
```

---

# Version 3

В дальнейшем можно добавить более сложные функции.

Например автоматически определять:

```text
новые строки;
новые столбцы;
изменение структуры таблицы.
```

И создавать более высокоуровневые события:

```text
Новое задание

Появился дедлайн

Появился балл

Балл изменён
```

Вместо обычного:

```text
cell D17 changed
```

---

# Главная техническая задача

ChangeWatch должен решать задачу:

```text
Получать изменения из множества внешних источников
↓
не опрашивать один источник лишний раз
↓
надёжно обнаруживать изменения
↓
понимать, каких пользователей они касаются
↓
гарантированно доставлять нужные уведомления
```

Это делает проект подходящим для изучения реальных backend-проблем:

```text
event-driven architecture
Kafka
конкурентность
идемпотентность
distributed scheduling
retry
DLQ
rate limiting
OAuth
caching
transactional outbox
push notifications
distributed tracing
```

При этом продукт остаётся достаточно конкретным: **пользователь подписывается на данные, которые ждёт, и получает уведомление сразу после их изменения**.