/**
 * Готовые фрагменты настройки мониторинга: администратор копирует их с экрана источника. Скрипт Zabbix
 * совпадает с docs/integrations/zabbix-webhook.js.
 */

export function alertmanagerConfig(url: string, token: string): string {
  return `receivers:
  - name: easyitsm
    webhook_configs:
      - url: ${url}
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials: ${token}

route:
  receiver: easyitsm
  group_by: [alertname, instance]
  repeat_interval: 4h`;
}

export function grafanaHint(url: string): string {
  return `Contact point → Webhook
URL: ${url}
HTTP Method: POST
Authorization Header - Scheme: Bearer
Authorization Header - Credentials: <токен>`;
}

export const ZABBIX_PARAMETERS = [
  ['url', '{$EASYITSM.URL}'],
  ['token', '{$EASYITSM.TOKEN}'],
  ['event_id', '{EVENT.ID}'],
  ['trigger_id', '{TRIGGER.ID}'],
  ['status', '{EVENT.STATUS}'],
  ['value', '{EVENT.VALUE}'],
  ['severity', '{EVENT.SEVERITY}'],
  ['name', '{EVENT.NAME}'],
  ['host', '{HOST.NAME}'],
  ['message', '{ALERT.MESSAGE}'],
  ['tags', '{EVENT.TAGS}'],
  ['started_at', '{EVENT.DATE} {EVENT.TIME}'],
  ['url_event', '{$ZABBIX.URL}/tr_events.php?triggerid={TRIGGER.ID}&eventid={EVENT.ID}'],
] as const;

export const ZABBIX_SCRIPT = `// Easy ITSM: тип оповещения «Webhook» для Zabbix 6.0+.
var params = JSON.parse(value);
var body = {
  event_id: params.event_id,
  trigger_id: params.trigger_id,
  status: params.status,
  value: params.value,
  severity: params.severity,
  name: params.name,
  host: params.host,
  message: params.message,
  tags: params.tags,
  started_at: params.started_at,
  url: params.url_event
};
var req = new HttpRequest();
req.addHeader('Content-Type: application/json');
req.addHeader('Authorization: Bearer ' + params.token);
var resp = req.post(params.url, JSON.stringify(body));
if (req.getStatus() < 200 || req.getStatus() >= 300) {
  throw 'Easy ITSM: HTTP ' + req.getStatus() + ' ' + resp;
}
return 'OK';`;

export function curlExample(url: string, token: string): string {
  return `curl -X POST ${url} \\
  -H 'Authorization: Bearer ${token}' \\
  -H 'Content-Type: application/json' \\
  -d '{"fingerprint":"disk-db1","status":"firing","severity":"high","title":"Мало места на db-1","host":"db-1"}'`;
}
